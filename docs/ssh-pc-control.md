# SSH PC Control

Mobile Agent can use native SSH as the stable backend bridge from the phone to a computer. MCP remains the foreground GUI/tool layer; SSH is the command, repair, and file-transfer base layer.

## Scope

Supported in the Android Host App:

- `ssh_status`: read configured host, user, key path, connection state, and last error.
- `ssh_diagnose`: check TCP reachability and SSH banner from the phone network stack.
- `ssh_select_host`: try LAN/Tailscale candidate hosts and save the first one that returns an SSH banner.
- `ssh_connect`: open a native SSH session with key authentication.
- `ssh_run`: run a remote command and return stdout, stderr, exit code, timeout state, and the exact remote command.
- `ssh_disconnect`: close the active session.
- `ssh_forward_status`: read the active SSH local port forward, if any.
- `ssh_forward_start`: start an in-app SSH local tunnel such as phone `127.0.0.1:18000` -> PC `127.0.0.1:8000`.
- `ssh_forward_stop`: stop the active local tunnel.
- `file_push`: upload a phone workspace/shared-storage file to the SSH host over SFTP.
- `file_pull`: download a remote file into the phone workspace/shared storage over SFTP.
- `mcp_configure`: update the in-app MCP endpoint/token from the agent loop and optionally verify it.
- `pc_bridge_recover`: one-call PC bridge recovery. It can select a working SSH host, connect, start an SSH tunnel for MCP, sync the Windows MCP token, optionally kill/restart MCP, wait, and verify MCP again.
- `tailscale_preflight`: check whether the phone can reach the PC Tailscale address over SSH. If no SSH banner is available, it can open the Tailscale Android app so the user can connect VPN.
- `tailscale_ssh_diagnose`: diagnose phone-to-Tailscale SSH problems from both sides. It checks phone TCP/banner, then uses a working SSH host to inspect Windows `sshd`, port 22, firewall rules, Tailscale status, and IPs.
- `pc_file_workflow`: one-call phone-to-PC file workflow. It checks shared-storage permission, connects SSH, uploads/downloads a file, optionally runs a PC processing command, and optionally pulls the result back to the phone.

Termux is not required for this path.

For desktop workflows that may use both SSH and MCP, use `pc_bridge_status` first. It returns SSH state, optional SSH reachability diagnosis, MCP state, and a recommendation for which bridge to use.

When MCP is `HTTP 502` or offline but SSH is usable, use `pc_bridge_recover` instead of asking the user to type Windows commands manually. The preferred repair path is now SSH local forwarding: the phone connects to `127.0.0.1:18000`, and SSH forwards that traffic to the PC service at `127.0.0.1:8000`.

## Windows OpenSSH Setup

1. Install and start Windows OpenSSH Server.
2. Ensure `sshd` listens on port `22`.
3. Put the phone public key into `C:\Users\<user>\.ssh\authorized_keys`.
4. In the Android Host App config, set:
   - host: LAN IP or Tailscale IP
   - port: `22`
   - user: your Windows user, for example `<windows-user>`
   - key path: `files_root:/ssh/mobile-agent_rsa4096` or another readable private-key path
5. To push files from `shared_storage:/Download/...` or WeChat-exported shared storage, grant the Android Host App file access permission. Without it, Android scoped storage can return `EACCES (Permission denied)` even when the file exists.

Useful Windows checks:

```powershell
Get-Service sshd
Test-NetConnection -ComputerName <host> -Port 22
Get-WinEvent -LogName 'OpenSSH/Operational' -MaxEvents 20
```

Useful phone-side checks over ADB:

```powershell
adb shell "toybox nc -z -w 4 <host> 22; echo exit:$?"
adb shell "toybox timeout 5 toybox nc -w 4 <host> 22 | toybox head -n 1"
```

The second command should show an SSH banner such as `SSH-2.0-OpenSSH_for_Windows_9.5`. If TCP connects but the banner is blank, diagnose the VPN/Tailscale route before debugging SSH keys.

## Minimal Verification

Call these native tools through the Host bridge or the App task loop.

1. Diagnose reachability:

```json
{
  "tool": "ssh_diagnose",
  "arguments": {
    "host": "<pc-lan-ip>",
    "port": 22,
    "timeout_ms": 8000
  },
  "actions_approved": true
}
```

Expected result: `status=ssh_banner_ok`. If the result is `tcp_connected_no_ssh_banner`, the phone reached the port but did not receive an OpenSSH banner; diagnose VPN/Tailscale/firewall/port routing before changing keys.

2. When multiple addresses are available, select automatically:

```json
{
  "tool": "ssh_select_host",
  "arguments": {
    "hosts": ["<pc-lan-ip>", "<pc-tailscale-ip>"],
    "port": 22,
    "timeout_ms": 6000,
    "apply": true
  },
  "actions_approved": true
}
```

Expected result: `status=selected`; `selected_host` is saved as the configured SSH host when `apply=true`.

3. Connect:

```json
{
  "tool": "ssh_connect",
  "arguments": {
    "host": "<pc-lan-ip>",
    "port": 22,
    "user": "<windows-user>",
    "key_path": "files_root:/ssh/mobile-agent_rsa4096",
    "connect_timeout_ms": 15000,
    "command_timeout_ms": 20000
  },
  "actions_approved": true
}
```

4. Run PowerShell:

```json
{
  "tool": "ssh_run",
  "arguments": {
    "command": "[Environment]::MachineName; whoami",
    "shell": "powershell",
    "timeout_ms": 20000
  },
  "actions_approved": true
}
```

Expected result: `returncode=0`, `timed_out=false`, stdout contains the computer name and Windows user.

5. Push a file:

```json
{
  "tool": "file_push",
  "arguments": {
    "local_path": "workspace:/ssh/roundtrip.txt",
    "remote_path": "mobile-agent-ssh-roundtrip.txt",
    "overwrite": true
  },
  "actions_approved": true
}
```

6. Pull the file back:

```json
{
  "tool": "file_pull",
  "arguments": {
    "remote_path": "mobile-agent-ssh-roundtrip.txt",
    "local_path": "workspace:/ssh/roundtrip-pulled.txt",
    "overwrite": true
  },
  "actions_approved": true
}
```

Expected result: both transfers report `ok=true` and non-negative byte counts. Read the pulled file with `workspace.read` to verify content.

7. Push a shared-storage file after file access is granted:

```json
{
  "tool": "file_push",
  "arguments": {
    "local_path": "shared_storage:/Download/example.txt",
    "remote_path": "example.txt",
    "overwrite": true
  },
  "actions_approved": true
}
```

Expected result: `ok=true`; if the error contains `EACCES`, grant Android file access permission and retry.

Agents should check `storage_permission_status` before using `shared_storage:/Download/...`. If needed, call `storage_permission_open_settings` to open the Android authorization screen for the user.

8. Diagnose and repair the PC bridge:

```json
{
  "tool": "pc_bridge_recover",
  "arguments": {
    "hosts": ["<pc-tailscale-ip>", "<pc-lan-ip>"],
    "ssh_port": 22,
    "mcp_endpoint": "http://127.0.0.1:18000/mcp",
    "sync_auth_token": true,
    "auth_token_path": "<path-to-windows-mcp-auth-token>",
    "use_ssh_tunnel": true,
    "local_forward_port": 18000,
    "remote_forward_host": "127.0.0.1",
    "remote_forward_port": 8000,
    "remote_mcp_endpoint": "http://127.0.0.1:8000/mcp",
    "restart_command": "",
    "kill_port_process": false,
    "command_timeout_ms": 90000
  },
  "actions_approved": true
}
```

Expected result when Windows MCP is already running on the PC: `status=mcp_recovered`, `ssh_usable=true`, `mcp_usable=true`, and `effective_endpoint=http://127.0.0.1:18000/mcp`. After that, call `mcp_tools` and then `mcp_call` with the exact remote tool name.

If Windows MCP is not running, pass the exact `restart_command`; the tool will run it over SSH and verify MCP again. If no start command is known, the result includes SSH and MCP diagnostics so the agent can derive the next command instead of asking the user to type it.

9. Verify the SSH tunnel directly:

```json
{
  "tool": "ssh_forward_status",
  "arguments": {},
  "actions_approved": true
}
```

Expected result after recovery: `active=true`, `local_host=127.0.0.1`, `local_port=18000`, `remote_host=127.0.0.1`, `remote_port=8000`.

10. Preflight Tailscale before off-LAN work:

```json
{
  "tool": "tailscale_preflight",
  "arguments": {
    "host": "<pc-tailscale-ip>",
    "port": 22,
    "open_app_if_needed": true,
    "apply_if_ok": true
  },
  "actions_approved": true
}
```

Expected result when the phone VPN is connected: `status=tailscale_ready` and `ssh_banner=SSH-2.0-OpenSSH_for_Windows_9.5`.

Expected result when phone Tailscale is off: `status=tailscale_not_connected_or_unreachable`; the tool opens Tailscale if possible. The user should connect VPN, then retry this preflight or `pc_bridge_recover`.

11. Diagnose Tailscale SSH:

```json
{
  "tool": "tailscale_ssh_diagnose",
  "arguments": {
    "host": "<pc-tailscale-ip>",
    "port": 22,
    "try_fix": false
  },
  "actions_approved": true
}
```

Expected result: it reports whether the phone receives an SSH banner from the Tailscale address and includes Windows-side `sshd`, firewall, and Tailscale status. Use `try_fix=true` only when the user wants the agent to attempt `sshd`/firewall repair.

12. Process a phone file on the PC and pull the result back:

```json
{
  "tool": "pc_file_workflow",
  "arguments": {
    "direction": "phone_to_pc",
    "local_path": "shared_storage:/Download/input.txt",
    "remote_path": "input.txt",
    "process_command": "Get-Content -LiteralPath '{remote_path}' | Set-Content -LiteralPath 'output.txt'",
    "result_remote_path": "output.txt",
    "result_local_path": "shared_storage:/Download/output.txt",
    "overwrite": true
  },
  "actions_approved": true
}
```

Expected result: `storage_permission_status`, `ssh_connect`, `file_push`, optional `process_on_pc`, and optional `result_pull` all appear in the step trace.

## Field Checklist

For MCP, direct phone HTTP to the PC can fail even when SSH works. The recommended repair is:

1. Start Windows MCP on the PC at `127.0.0.1:8000` / `0.0.0.0:8000`.
2. Use `pc_bridge_recover` with `use_ssh_tunnel=true`.
3. The app starts `127.0.0.1:18000 -> 127.0.0.1:8000` over SSH.
4. The app updates MCP to `http://127.0.0.1:18000/mcp`.
5. The app syncs the Windows MCP token from the configured `auth_token_path` or `auth_token_command`.
6. `mcp_tools` and `mcp_call` then run through the tunnel.

After recovery, verify with `mcp_tools` and a small `mcp_call` such as a PowerShell command that prints the computer name.

For off-LAN remote work, the verified path is:

1. Run `tailscale_preflight` against the PC Tailscale IP.
2. If it returns `tailscale_ready`, run `pc_bridge_recover` with `tailscale_host`, `prefer_tailscale=true`, and `use_ssh_tunnel=true`.
3. The app connects SSH through Tailscale, starts the MCP tunnel, syncs the token, and sets MCP to `http://127.0.0.1:18000/mcp`.
4. Verify with a small `mcp_call` such as a PowerShell command that prints the computer name.
