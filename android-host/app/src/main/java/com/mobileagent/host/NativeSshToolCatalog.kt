package com.mobileagent.host

import org.json.JSONObject

object NativeSshToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "ssh_status",
                    description = "Return the configured SSH profile, connection state, and last connection or command error.",
                    category = "ssh",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "ssh_diagnose",
                    description = "Diagnose phone-to-PC SSH reachability from the Android network stack. It checks TCP connectivity and whether the remote host returns an SSH banner, which helps distinguish LAN/Tailscale/firewall problems from key-auth problems.",
                    category = "ssh",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "host" to NativeToolSchema.stringProp(""),
                        "port" to NativeToolSchema.intProp(22),
                        "timeout_ms" to NativeToolSchema.intProp(8000)
                    )
                ),
        NativeToolDescriptor(
                    name = "ssh_select_host",
                    description = "Try multiple candidate SSH hosts from the phone, select the first one that returns an SSH banner, and optionally save it as the configured SSH host. Use this instead of asking the user to choose between LAN and Tailscale addresses manually.",
                    category = "ssh",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "hosts" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                        "host" to NativeToolSchema.stringProp(""),
                        "port" to NativeToolSchema.intProp(22),
                        "timeout_ms" to NativeToolSchema.intProp(6000),
                        "apply" to NativeToolSchema.boolProp(true)
                    )
                ),
        NativeToolDescriptor(
                    name = "tailscale_preflight",
                    description = "Check whether the phone can reach the PC over Tailscale SSH before remote computer work. It probes the PC Tailscale address for an SSH banner; if unavailable it can open the Tailscale Android app so the user can connect VPN. Use this before remote/off-LAN PC tasks.",
                    category = "ssh",
                    access = NativeToolAccess.SCREEN_ACTION,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "host" to NativeToolSchema.stringProp(""),
                        "tailscale_host" to NativeToolSchema.stringProp(""),
                        "port" to NativeToolSchema.intProp(22),
                        "timeout_ms" to NativeToolSchema.intProp(6000),
                        "open_app_if_needed" to NativeToolSchema.boolProp(true),
                        "apply_if_ok" to NativeToolSchema.boolProp(false),
                        "package" to NativeToolSchema.stringProp("com.tailscale.ipn")
                    )
                ),
        NativeToolDescriptor(
                    name = "tailscale_ssh_diagnose",
                    description = "Diagnose why a Tailscale address does not behave like SSH from the phone. It checks phone-to-Tailscale TCP/banner, then uses the currently working SSH host to inspect Windows sshd, port 22 listeners, firewall SSH rules, Tailscale status, and IPs. With try_fix=true it attempts to start sshd and add a basic inbound firewall rule.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "host" to NativeToolSchema.stringProp(""),
                        "tailscale_host" to NativeToolSchema.stringProp(""),
                        "port" to NativeToolSchema.intProp(22),
                        "timeout_ms" to NativeToolSchema.intProp(6000),
                        "command_timeout_ms" to NativeToolSchema.intProp(90000),
                        "try_fix" to NativeToolSchema.boolProp(false)
                    ),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "ssh_connect",
                    description = "Open a native SSH session to the configured remote host using key authentication. Use this before remote command execution or file transfer.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "enabled" to NativeToolSchema.boolProp(true),
                        "host" to NativeToolSchema.stringProp(""),
                        "port" to NativeToolSchema.intProp(22),
                        "user" to NativeToolSchema.stringProp(""),
                        "key_path" to NativeToolSchema.stringProp(""),
                        "passphrase" to NativeToolSchema.stringProp(""),
                        "connect_timeout_ms" to NativeToolSchema.intProp(8000),
                        "command_timeout_ms" to NativeToolSchema.intProp(60000)
                    )
                ),
        NativeToolDescriptor(
                    name = "ssh_run",
                    description = "Run a command on the connected SSH host and return stdout, stderr, and exit code. Prefer PowerShell on Windows hosts.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "command" to NativeToolSchema.stringProp(),
                        "cwd" to NativeToolSchema.stringProp(""),
                        "shell" to NativeToolSchema.stringProp("powershell"),
                        "timeout_ms" to NativeToolSchema.intProp(60000)
                    ),
                    required = NativeToolSchema.req("command")
                ),
        NativeToolDescriptor(
                    name = "ssh_forward_status",
                    description = "Return the active SSH local port forwarding state, if any. Use this to verify whether phone localhost is tunneled to a PC service.",
                    category = "ssh",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "ssh_forward_start",
                    description = "Start SSH local port forwarding inside the Android app, for example phone 127.0.0.1:18000 -> PC 127.0.0.1:8000. Use this when the phone cannot directly reach a PC HTTP service but SSH works.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "local_port" to NativeToolSchema.intProp(18000),
                        "remote_host" to NativeToolSchema.stringProp("127.0.0.1"),
                        "remote_port" to NativeToolSchema.intProp(8000)
                    ),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "ssh_forward_stop",
                    description = "Stop the active SSH local port forwarding listener inside the Android app.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.MEDIUM
                ),
        NativeToolDescriptor(
                    name = "ssh_disconnect",
                    description = "Close the active SSH session and clear local connection state.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.MEDIUM
                ),
        NativeToolDescriptor(
                    name = "file_push",
                    description = "Upload a local file from the Android workspace or shared storage to the SSH host over SFTP.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "local_path" to NativeToolSchema.stringProp(),
                        "remote_path" to NativeToolSchema.stringProp(),
                        "overwrite" to NativeToolSchema.boolProp(true)
                    ),
                    required = NativeToolSchema.req("local_path", "remote_path")
                ),
        NativeToolDescriptor(
                    name = "file_pull",
                    description = "Download a file from the SSH host into the Android workspace or shared storage over SFTP.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "remote_path" to NativeToolSchema.stringProp(),
                        "local_path" to NativeToolSchema.stringProp(""),
                        "overwrite" to NativeToolSchema.boolProp(true)
                    ),
                    required = NativeToolSchema.req("remote_path")
                ),
        NativeToolDescriptor(
                    name = "pc_file_workflow",
                    description = "Run the common phone-file-to-PC workflow in one call: verify Android shared storage permission, connect SSH, upload or download a file, optionally run a processing command on the PC with {remote_path}/{local_path} placeholders, and optionally pull a result file back to the phone. Use for WeChat/Download files that are easier to process on the PC.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "direction" to NativeToolSchema.stringProp("phone_to_pc"),
                        "local_path" to NativeToolSchema.stringProp(""),
                        "remote_path" to NativeToolSchema.stringProp(""),
                        "process_command" to NativeToolSchema.stringProp(""),
                        "result_remote_path" to NativeToolSchema.stringProp(""),
                        "result_local_path" to NativeToolSchema.stringProp(""),
                        "cwd" to NativeToolSchema.stringProp(""),
                        "shell" to NativeToolSchema.stringProp("powershell"),
                        "timeout_ms" to NativeToolSchema.intProp(60000),
                        "overwrite" to NativeToolSchema.boolProp(true),
                        "fail_on_process_error" to NativeToolSchema.boolProp(true)
                    ),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "storage_permission_status",
                    description = "Return whether Android all-files access is granted for shared_storage:/ paths such as Download or WeChat-exported files.",
                    category = "ssh",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "storage_permission_open_settings",
                    description = "Open Android settings where the user can grant all-files access for shared_storage:/ file transfer workflows.",
                    category = "ssh",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.MEDIUM
                )
    )
}
