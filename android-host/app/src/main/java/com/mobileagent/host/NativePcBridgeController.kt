package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NativePcBridgeController(
    private val context: Context,
    private val runtimeConfig: AgentRuntimeConfig,
    private val sshBridge: SshBridge,
    private val mcpClient: NativeMcpClient,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val storageAccess: NativeStorageAccess,
    private val setMcpConfig: (Boolean, String, String) -> Unit,
    private val openApp: (String) -> JSONObject,
    private val terminalRun: (String, String, Int) -> JSONObject,
    private val terminalScript: (JSONObject) -> JSONObject
) {
    private val terminalFallback = NativePcBridgeTerminalFallback(
        runtimeConfig = runtimeConfig,
        mcpClient = mcpClient,
        terminalRecovery = terminalRecovery,
        setMcpConfig = setMcpConfig,
        terminalRun = terminalRun,
        terminalScript = terminalScript
    )

    fun healthCheck(arguments: JSONObject): JSONObject {
        val status = status(
            JSONObject()
                .put("diagnose_ssh", arguments.optBoolean("diagnose_ssh", true))
                .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
                .put("check_tailscale", arguments.optBoolean("check_tailscale", false))
                .put("include_mcp_tools", arguments.optBoolean("include_mcp_tools", false))
                .put("include_mcp_config", arguments.optBoolean("include_mcp_config", false))
                .put("hosts", arguments.optJSONArray("hosts") ?: JSONArray())
        )
        val sshUsable = status.optBoolean("ssh_usable", false)
        val mcpUsable = status.optBoolean("mcp_usable", false)
        val decision = when {
            sshUsable && mcpUsable -> "healthy"
            sshUsable -> "mcp_down_repair_with_ssh"
            mcpUsable -> "ssh_down_repair_with_mcp"
            else -> "pc_bridge_offline_report_user"
        }
        val nextAction = when (decision) {
            "healthy" -> "Continue the desktop task. Prefer MCP for GUI/Windows tools and SSH for command/file workflows."
            "mcp_down_repair_with_ssh" -> "Call pc_bridge_recover. SSH can inspect ports, restart Windows-MCP, sync token, and verify MCP again."
            "ssh_down_repair_with_mcp" -> "Use mcp_call PowerShell or pc_bridge_recover to inspect and restart Windows sshd/firewall, then retry SSH."
            else -> "Stop retrying. Report that both MCP and SSH are unavailable, include Tailscale/port diagnostics, and ask the user to check PC power, Tailscale, and sshd."
        }
        return JSONObject()
            .put("ok", true)
            .put("decision", decision)
            .put("ssh_usable", sshUsable)
            .put("mcp_usable", mcpUsable)
            .put("can_self_repair", sshUsable || mcpUsable)
            .put("must_report_user", !sshUsable && !mcpUsable)
            .put("next_action", nextAction)
            .put("status", status)
    }

    fun status(arguments: JSONObject): JSONObject {
        val ssh = sshBridge.status()
        val diagnoseSsh = arguments.optBoolean("diagnose_ssh", true)
        val sshDiagnostic = if (diagnoseSsh) {
            val diagArgs = JSONObject()
                .put("host", arguments.optString("ssh_host", ssh.optString("host")))
                .put("port", arguments.optInt("ssh_port", ssh.optInt("port", 22)))
                .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
            sshBridge.diagnose(diagArgs)
        } else {
            JSONObject().put("skipped", true)
        }
        val tailscaleHost = arguments.optString("tailscale_host", "").ifBlank {
            if (arguments.optBoolean("check_tailscale", false)) findTailscaleHost(arguments.optJSONArray("hosts")) else ""
        }
        val tailscalePreflight = if (tailscaleHost.isNotBlank()) {
            tailscalePreflight(
                JSONObject()
                    .put("host", tailscaleHost)
                    .put("port", arguments.optInt("ssh_port", 22))
                    .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
                    .put("open_app_if_needed", false)
                    .put("apply_if_ok", false)
            )
        } else {
            JSONObject().put("skipped", true)
        }
        val includeMcpTools = arguments.optBoolean("include_mcp_tools", false)
        val includeMcpConfig = arguments.optBoolean("include_mcp_config", false)
        val mcp = mcpSummary(mcpClient.runtimeStatus(force = false), includeMcpTools, includeMcpConfig)
        val sshUsable = ssh.optBoolean("connected", false) || sshDiagnostic.optString("status") == "ssh_banner_ok"
        val mcpUsable = mcp.optString("state") == "ok" || mcp.optBoolean("ok", false)
        val recommendation = when {
            tailscalePreflight.optString("status") == "tailscale_not_connected_or_unreachable" ->
                "Remote Tailscale SSH is not ready. Open Tailscale on the phone, connect VPN, then retry tailscale_preflight or pc_bridge_recover."
            sshUsable && mcpUsable -> "SSH and MCP are both usable. Use SSH for backend commands/files and MCP for foreground GUI/Windows tools."
            sshUsable -> "SSH is usable. Use ssh_connect/ssh_run/file_push/file_pull; use SSH to inspect or restart MCP if needed."
            mcpUsable -> "MCP is usable but SSH is not confirmed. Use MCP for foreground tools and run ssh_diagnose before backend repair."
            else -> "Neither SSH nor MCP is confirmed. Run ssh_select_host or ssh_diagnose, then check MCP service configuration."
        }
        return JSONObject()
            .put("ok", true)
            .put("ssh", ssh)
            .put("ssh_diagnostic", sshDiagnostic)
            .put("tailscale_preflight", tailscalePreflight)
            .put("mcp", mcp)
            .put("ssh_usable", sshUsable)
            .put("mcp_usable", mcpUsable)
            .put("recommendation", recommendation)
    }

    fun recover(arguments: JSONObject): JSONObject {
        val steps = JSONArray()
        val before = status(
            JSONObject()
                .put("diagnose_ssh", arguments.optBoolean("diagnose_ssh", true))
                .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
                .put("include_mcp_tools", arguments.optBoolean("include_mcp_tools", false))
                .put("include_mcp_config", arguments.optBoolean("include_mcp_config", false))
        )
        steps.put(JSONObject().put("step", "before_status").put("result", before))

        val hosts = arguments.optJSONArray("hosts") ?: arguments.optJSONArray("candidates")
        val tailscaleHost = arguments.optString("tailscale_host", "").ifBlank { findTailscaleHost(hosts) }
        if (tailscaleHost.isNotBlank()) {
            val preflight = tailscalePreflight(
                JSONObject()
                    .put("host", tailscaleHost)
                    .put("port", arguments.optInt("ssh_port", 22))
                    .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
                    .put("open_app_if_needed", arguments.optBoolean("open_tailscale_if_needed", true))
                    .put("apply_if_ok", arguments.optBoolean("prefer_tailscale", false))
            )
            steps.put(JSONObject().put("step", "tailscale_preflight").put("result", preflight))
            if (preflight.optString("status") == "tailscale_not_connected_or_unreachable" && hosts?.length() == 1) {
                return JSONObject()
                    .put("ok", false)
                    .put("status", "tailscale_not_connected")
                    .put("steps", steps)
                    .put("ssh_usable", false)
                    .put("mcp_usable", false)
                    .put("hint", "Tailscale SSH is not reachable from the phone. Tailscale was opened if possible; connect it on the phone, then retry pc_bridge_recover.")
            }
        }
        if (hosts != null && hosts.length() > 0) {
            val selected = sshBridge.selectHost(
                JSONObject()
                    .put("hosts", hosts)
                    .put("port", arguments.optInt("ssh_port", 22))
                    .put("timeout_ms", arguments.optInt("timeout_ms", 6000))
                    .put("apply", true)
            )
            steps.put(JSONObject().put("step", "ssh_select_host").put("result", selected))
        }

        val endpoint = arguments.optString("mcp_endpoint", runtimeConfig.mcpBaseUrl()).ifBlank { runtimeConfig.mcpBaseUrl() }
        val timeoutMs = arguments.optInt("command_timeout_ms", runtimeConfig.sshCommandTimeoutMs()).coerceIn(5_000, 300_000)
        val useSshTunnel = arguments.optBoolean("use_ssh_tunnel", true)
        var effectiveEndpoint = endpoint
        val parsedEndpoint = NativePcBridgeScripts.parseEndpoint(endpoint)
        val localPort = arguments.optInt("local_forward_port", 18000).coerceIn(1024, 65535)
        val remoteHost = arguments.optString("remote_forward_host", "127.0.0.1").ifBlank { "127.0.0.1" }
        val remotePort = arguments.optInt(
            "remote_forward_port",
            arguments.optInt("remote_port", parsedEndpoint.first)
        ).coerceIn(1, 65535)
        val remoteEndpoint = arguments.optString(
            "remote_mcp_endpoint",
            "http://$remoteHost:$remotePort${parsedEndpoint.third}"
        ).ifBlank { "http://$remoteHost:$remotePort${parsedEndpoint.third}" }

        val connect = sshBridge.connect(JSONObject())
        steps.put(JSONObject().put("step", "ssh_connect").put("result", connect))
        if (!connect.optBoolean("ok", false)) {
            val mcpRepairSsh = repairSshViaMcp(arguments)
            steps.put(JSONObject().put("step", "mcp_repair_ssh").put("result", mcpRepairSsh))
            if (mcpRepairSsh.optBoolean("ok", false)) {
                val retryConnect = sshBridge.connect(JSONObject())
                steps.put(JSONObject().put("step", "ssh_connect_after_mcp_repair").put("result", retryConnect))
                if (retryConnect.optBoolean("ok", false)) {
                    return recover(JSONObject(arguments.toString()).put("skip_mcp_ssh_repair", true))
                        .put("previous_steps", steps)
                }
            }
            val terminalFallbackResult = terminalFallback.recover(
                arguments = arguments,
                endpoint = endpoint,
                localPort = localPort,
                remoteHost = remoteHost,
                remotePort = remotePort
            )
            steps.put(JSONObject().put("step", "terminal_ssh_forward_fallback").put("result", terminalFallbackResult))
            val fallbackOk = terminalFallbackResult.optBoolean("ok", false)
            return JSONObject()
                .put("ok", fallbackOk)
                .put("status", if (fallbackOk) "mcp_recovered_by_terminal_ssh" else "ssh_unavailable")
                .put("steps", steps)
                .put("error", connect.optString("error"))
                .put(
                    "hint",
                    if (fallbackOk) {
                        "Native SSH failed, but the Termux terminal fallback opened the MCP tunnel. Continue with mcp_tools and mcp_call."
                    } else {
                        "Native SSH could not be established. MCP-based SSH repair and terminal SSH fallback also failed or were unavailable. Report this instead of retrying forever: check PC power, Tailscale, Windows sshd, and firewall."
                    }
                )
        }
        if (useSshTunnel) {
            val forward = sshBridge.startLocalForward(
                JSONObject()
                    .put("local_port", localPort)
                    .put("remote_host", remoteHost)
                    .put("remote_port", remotePort)
            )
            steps.put(JSONObject().put("step", "ssh_forward_start").put("result", forward))
            if (forward.optBoolean("ok", false)) {
                effectiveEndpoint = "http://127.0.0.1:$localPort${parsedEndpoint.third}"
                setMcpConfig(true, effectiveEndpoint, runtimeConfig.mcpAuthToken())
                steps.put(
                    JSONObject()
                        .put("step", "mcp_endpoint_tunnel_configure")
                        .put("result", JSONObject().put("endpoint", effectiveEndpoint).put("ok", true))
                )
            } else {
                val terminalFallbackResult = terminalFallback.recover(
                    arguments = arguments,
                    endpoint = endpoint,
                    localPort = localPort,
                    remoteHost = remoteHost,
                    remotePort = remotePort
                )
                steps.put(JSONObject().put("step", "terminal_ssh_forward_fallback").put("result", terminalFallbackResult))
                if (terminalFallbackResult.optBoolean("ok", false)) {
                    effectiveEndpoint = "http://127.0.0.1:$localPort${parsedEndpoint.third}"
                }
            }
        }
        val authSync = syncMcpAuthFromSsh(arguments, effectiveEndpoint, timeoutMs)
        if (authSync.length() > 0) {
            steps.put(JSONObject().put("step", "mcp_auth_sync").put("result", authSync))
        }
        val diagnose = sshBridge.run(NativePcBridgeScripts.mcpDiagnostic(remoteEndpoint), shell = "powershell", timeoutMs = timeoutMs)
        steps.put(JSONObject().put("step", "mcp_remote_diagnose").put("result", diagnose))

        val killPortProcess = arguments.optBoolean("kill_port_process", false)
        val restartCommand = arguments.optString("restart_command", arguments.optString("start_command", "")).trim()
        if (killPortProcess || restartCommand.isNotBlank()) {
            mcpClient.clearSessions()
            if (killPortProcess) {
                val kill = sshBridge.run(NativePcBridgeScripts.killMcpPort(remoteEndpoint), shell = "powershell", timeoutMs = timeoutMs)
                steps.put(JSONObject().put("step", "kill_mcp_port_process").put("result", kill))
            }
            if (restartCommand.isNotBlank()) {
                val restart = sshBridge.run(restartCommand, shell = arguments.optString("shell", "powershell"), timeoutMs = timeoutMs)
                steps.put(JSONObject().put("step", "restart_mcp").put("result", restart))
            }
            val waitMs = arguments.optInt("wait_ms", 2500).coerceIn(0, 30_000)
            if (waitMs > 0) Thread.sleep(waitMs.toLong())
        }

        val remoteAfter = sshBridge.run(NativePcBridgeScripts.mcpDiagnostic(remoteEndpoint), shell = "powershell", timeoutMs = timeoutMs)
        steps.put(JSONObject().put("step", "mcp_remote_after").put("result", remoteAfter))
        val after = status(
            JSONObject()
                .put("diagnose_ssh", false)
                .put("include_mcp_tools", arguments.optBoolean("include_mcp_tools", false))
                .put("include_mcp_config", arguments.optBoolean("include_mcp_config", false))
        )
        steps.put(JSONObject().put("step", "after_status").put("result", after))

        val mcpUsable = after.optBoolean("mcp_usable", false)
        val restarted = restartCommand.isNotBlank() || killPortProcess
        return JSONObject()
            .put("ok", mcpUsable || connect.optBoolean("ok", false))
            .put("status", when {
                mcpUsable -> "mcp_recovered"
                restarted -> "repair_attempted_mcp_still_unhealthy"
                else -> "diagnosed_restart_command_required"
            })
            .put("mcp_usable", mcpUsable)
            .put("ssh_usable", after.optBoolean("ssh_usable", true))
            .put("endpoint", endpoint)
            .put("effective_endpoint", effectiveEndpoint)
            .put("steps", steps)
            .put(
                "hint",
                if (mcpUsable) {
                    "MCP is reachable again. Continue with mcp_tools and mcp_call."
                } else if (restartCommand.isBlank()) {
                    "MCP is still unhealthy. Provide restart_command or let the agent inspect the remote diagnostics and derive the exact command."
                } else {
                    "Restart was attempted but MCP is still unhealthy. Inspect mcp_remote_after stdout/stderr for the next repair step."
                }
            )
    }

    fun tailscalePreflight(arguments: JSONObject): JSONObject {
        val host = arguments.optString("host", arguments.optString("tailscale_host", "")).trim()
        val port = arguments.optInt("port", 22).coerceIn(1, 65535)
        val timeoutMs = arguments.optInt("timeout_ms", 6000).coerceIn(1000, 30000)
        val openIfNeeded = arguments.optBoolean("open_app_if_needed", true)
        val applyIfOk = arguments.optBoolean("apply_if_ok", false)
        val steps = JSONArray()
        if (host.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("status", "missing_tailscale_host")
                .put("error", "host or tailscale_host is required")
                .put("hint", "Pass the PC Tailscale IPv4 address, for example 100.113.120.40.")
        }

        val diag = sshBridge.diagnose(
            JSONObject()
                .put("host", host)
                .put("port", port)
                .put("timeout_ms", timeoutMs)
        )
        steps.put(JSONObject().put("step", "phone_to_tailscale_ssh_banner").put("result", diag))
        if (diag.optString("status") == "ssh_banner_ok") {
            val applied = if (applyIfOk) {
                sshBridge.selectHost(
                    JSONObject()
                        .put("hosts", JSONArray().put(host))
                        .put("port", port)
                        .put("timeout_ms", timeoutMs)
                        .put("apply", true)
                )
            } else {
                JSONObject().put("skipped", true)
            }
            if (applyIfOk) steps.put(JSONObject().put("step", "apply_tailscale_host").put("result", applied))
            return JSONObject()
                .put("ok", true)
                .put("status", "tailscale_ready")
                .put("host", host)
                .put("port", port)
                .put("diagnostic", diag)
                .put("steps", steps)
                .put("ssh_banner", diag.optString("banner"))
                .put("hint", "Tailscale SSH is reachable. Use this host for remote PC work when the phone is away from the LAN.")
        }

        val appPackage = arguments.optString("package", "com.tailscale.ipn").ifBlank { "com.tailscale.ipn" }
        val installed = context.packageManager.getLaunchIntentForPackage(appPackage) != null
        var opened = JSONObject().put("skipped", true)
        if (openIfNeeded && installed) {
            opened = runCatching { openApp(appPackage) }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("package", appPackage)
                    .put("error", it.message ?: it.javaClass.simpleName)
            }
            steps.put(JSONObject().put("step", "open_tailscale_app").put("result", opened))
        }

        return JSONObject()
            .put("ok", false)
            .put("status", "tailscale_not_connected_or_unreachable")
            .put("host", host)
            .put("port", port)
            .put("tailscale_package", appPackage)
            .put("tailscale_installed", installed)
            .put("opened_tailscale", opened.optBoolean("ok", false))
            .put("diagnostic", diag)
            .put("steps", steps)
            .put(
                "hint",
                if (!installed) {
                    "Tailscale is not installed or is not visible to this app. Install/open Tailscale, sign in, and connect VPN."
                } else if (opened.optBoolean("ok", false)) {
                    "Tailscale SSH is not reachable. The Tailscale app was opened; connect VPN on the phone, then retry."
                } else {
                    "Tailscale SSH is not reachable. Open Tailscale on the phone, connect VPN, then retry."
                }
            )
    }

    fun tailscaleSshDiagnose(arguments: JSONObject): JSONObject {
        val tailscaleHost = arguments.optString("host", arguments.optString("tailscale_host", "")).trim()
        val port = arguments.optInt("port", 22).coerceIn(1, 65535)
        val timeoutMs = arguments.optInt("timeout_ms", 6000).coerceIn(1000, 30000)
        val steps = JSONArray()
        if (tailscaleHost.isNotBlank()) {
            val phoneDiag = sshBridge.diagnose(
                JSONObject()
                    .put("host", tailscaleHost)
                    .put("port", port)
                    .put("timeout_ms", timeoutMs)
            )
            steps.put(JSONObject().put("step", "phone_to_tailscale_ssh").put("result", phoneDiag))
        }
        val connect = sshBridge.connect(JSONObject())
        steps.put(JSONObject().put("step", "ssh_connect_current_host").put("result", connect))
        if (!connect.optBoolean("ok", false)) {
            return JSONObject()
                .put("ok", false)
                .put("status", "current_ssh_unavailable")
                .put("steps", steps)
                .put("hint", "Current SSH host is unavailable, so Windows-side Tailscale/sshd checks cannot run.")
        }

        val tryFix = arguments.optBoolean("try_fix", false)
        val script = NativePcBridgeScripts.tailscaleSshDiagnostic(port, tryFix)
        val remoteDiag = sshBridge.run(script, shell = "powershell", timeoutMs = arguments.optInt("command_timeout_ms", 90000))
        steps.put(JSONObject().put("step", if (tryFix) "windows_tailscale_ssh_diagnose_and_fix" else "windows_tailscale_ssh_diagnose").put("result", remoteDiag))
        val after = if (tailscaleHost.isNotBlank()) {
            sshBridge.diagnose(
                JSONObject()
                    .put("host", tailscaleHost)
                    .put("port", port)
                    .put("timeout_ms", timeoutMs)
            )
        } else {
            JSONObject().put("skipped", true)
        }
        steps.put(JSONObject().put("step", "phone_to_tailscale_after").put("result", after))
        return JSONObject()
            .put("ok", after.optString("status") == "ssh_banner_ok" || remoteDiag.optBoolean("ok", false))
            .put("status", if (after.optString("status") == "ssh_banner_ok") "tailscale_ssh_ok" else "diagnosed")
            .put("steps", steps)
            .put("hint", "If TCP connects but no SSH banner appears, inspect Windows sshd ListenAddress, firewall rules, and Tailscale serve/funnel state from the remote diagnostic output.")
    }

    fun fileWorkflow(arguments: JSONObject): JSONObject {
        val direction = arguments.optString("direction", "phone_to_pc").ifBlank { "phone_to_pc" }
        val steps = JSONArray()
        val storage = storageAccess.status()
        steps.put(JSONObject().put("step", "storage_permission_status").put("result", storage))
        val connect = sshBridge.connect(JSONObject())
        steps.put(JSONObject().put("step", "ssh_connect").put("result", connect))
        if (!connect.optBoolean("ok", false)) {
            return JSONObject()
                .put("ok", false)
                .put("status", "ssh_unavailable")
                .put("steps", steps)
                .put("error", connect.optString("error"))
        }

        var transferredPath = ""
        when (direction) {
            "phone_to_pc", "upload" -> {
                val localPath = arguments.optString("local_path")
                val remotePath = arguments.optString("remote_path")
                val pushed = sshBridge.push(localPath, remotePath, arguments.optBoolean("overwrite", true))
                steps.put(JSONObject().put("step", "file_push").put("result", pushed))
                transferredPath = remotePath
                if (!pushed.optBoolean("ok", false)) {
                    return JSONObject().put("ok", false).put("status", "push_failed").put("steps", steps)
                }
            }
            "pc_to_phone", "download" -> {
                val remotePath = arguments.optString("remote_path")
                val localPath = arguments.optString("local_path", "")
                val pulled = sshBridge.pull(remotePath, localPath, arguments.optBoolean("overwrite", true))
                steps.put(JSONObject().put("step", "file_pull").put("result", pulled))
                transferredPath = pulled.optJSONObject("result")?.optString("local_path").orEmpty()
                if (!pulled.optBoolean("ok", false)) {
                    return JSONObject().put("ok", false).put("status", "pull_failed").put("steps", steps)
                }
            }
            else -> return JSONObject()
                .put("ok", false)
                .put("status", "bad_direction")
                .put("error", "direction must be phone_to_pc/upload or pc_to_phone/download")
                .put("steps", steps)
        }

        val processCommand = arguments.optString("process_command", "").trim()
        if (processCommand.isNotBlank()) {
            val command = processCommand
                .replace("{remote_path}", arguments.optString("remote_path"))
                .replace("{local_path}", arguments.optString("local_path", ""))
            val processed = sshBridge.run(
                command,
                cwd = arguments.optString("cwd", ""),
                shell = arguments.optString("shell", "powershell"),
                timeoutMs = arguments.optInt("timeout_ms", runtimeConfig.sshCommandTimeoutMs())
            )
            steps.put(JSONObject().put("step", "process_on_pc").put("result", processed))
            if (!processed.optBoolean("ok", false) && arguments.optBoolean("fail_on_process_error", true)) {
                return JSONObject().put("ok", false).put("status", "process_failed").put("steps", steps)
            }
        }

        val resultRemotePath = arguments.optString("result_remote_path", "").trim()
        if (resultRemotePath.isNotBlank()) {
            val resultLocalPath = arguments.optString("result_local_path", "")
            val pulled = sshBridge.pull(resultRemotePath, resultLocalPath, arguments.optBoolean("overwrite", true))
            steps.put(JSONObject().put("step", "result_pull").put("result", pulled))
            if (!pulled.optBoolean("ok", false)) {
                return JSONObject().put("ok", false).put("status", "result_pull_failed").put("steps", steps)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("status", "completed")
            .put("direction", direction)
            .put("transferred_path", transferredPath)
            .put("steps", steps)
            .put("hint", "Use this workflow for phone files such as shared_storage:/Download/... then process them on the PC and optionally pull results back.")
    }

    private fun syncMcpAuthFromSsh(arguments: JSONObject, endpoint: String, timeoutMs: Int): JSONObject {
        val explicitToken = arguments.optString("auth_token", "").trim()
        val tokenPath = arguments.optString(
            "auth_token_path",
            ""
        ).trim()
        val tokenCommand = arguments.optString("auth_token_command", "").trim()
        val shouldSync = arguments.optBoolean(
            "sync_auth_token",
            explicitToken.isNotBlank() || tokenCommand.isNotBlank() || tokenPath.isNotBlank()
        )
        if (!shouldSync) return JSONObject()
        val token = when {
            explicitToken.isNotBlank() -> explicitToken
            tokenCommand.isNotBlank() -> {
                val result = sshBridge.run(tokenCommand, shell = "powershell", timeoutMs = timeoutMs)
                if (!result.optBoolean("ok", false)) {
                    return JSONObject()
                        .put("ok", false)
                        .put("status", "auth_token_command_failed")
                        .put("result", result)
                }
                result.optJSONObject("result")?.optString("stdout", "").orEmpty().trim()
            }
            tokenPath.isNotBlank() -> {
                val command = "if (Test-Path -LiteralPath '${tokenPath.replace("'", "''")}') { Get-Content -Raw -LiteralPath '${tokenPath.replace("'", "''")}' }"
                val result = sshBridge.run(command, shell = "powershell", timeoutMs = timeoutMs)
                if (!result.optBoolean("ok", false)) {
                    return JSONObject()
                        .put("ok", false)
                        .put("status", "auth_token_read_failed")
                        .put("path", tokenPath)
                        .put("result", result)
                }
                result.optJSONObject("result")?.optString("stdout", "").orEmpty().trim()
            }
            else -> ""
        }
        if (token.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("status", "auth_token_empty")
                .put("path", tokenPath)
        }
        setMcpConfig(true, endpoint, token)
        return JSONObject()
            .put("ok", true)
            .put("status", "synced")
            .put("endpoint", runtimeConfig.mcpBaseUrl())
            .put("has_auth_token", runtimeConfig.mcpAuthToken().isNotBlank())
            .put("token_source", when {
                explicitToken.isNotBlank() -> "argument"
                tokenCommand.isNotBlank() -> "command"
                else -> "path"
            })
            .put("path", if (explicitToken.isBlank() && tokenCommand.isBlank()) tokenPath else "")
    }

    private fun findTailscaleHost(hosts: JSONArray?): String {
        if (hosts == null) return ""
        for (i in 0 until hosts.length()) {
            val host = hosts.optString(i).trim()
            if (host.startsWith("100.")) return host
        }
        return ""
    }

    private fun repairSshViaMcp(arguments: JSONObject): JSONObject {
        if (arguments.optBoolean("skip_mcp_ssh_repair", false)) {
            return JSONObject().put("ok", false).put("skipped", true).put("reason", "skip_mcp_ssh_repair")
        }
        if (!arguments.optBoolean("repair_ssh_with_mcp", true)) {
            return JSONObject().put("ok", false).put("skipped", true).put("reason", "repair_ssh_with_mcp disabled")
        }
        val mcp = mcpClient.runtimeStatus(force = true)
        if (mcp.optString("state") != "ok" && !mcp.optJSONObject("status")?.optBoolean("available", false).orFalse()) {
            return JSONObject()
                .put("ok", false)
                .put("status", "mcp_unavailable")
                .put("mcp", mcp)
                .put("hint", "MCP is unavailable, so it cannot repair SSH. If SSH is also down, report user intervention.")
        }
        val port = arguments.optInt("ssh_port", runtimeConfig.sshPort()).coerceIn(1, 65535)
        val result = runCatching {
            mcpClient.call(
                tool = "PowerShell",
                arguments = JSONObject()
                    .put("command", NativePcBridgeScripts.tailscaleSshDiagnostic(port, tryFix = true))
                    .put("timeout", arguments.optInt("mcp_repair_timeout", 90).coerceIn(10, 300)),
                timeoutMs = arguments.optInt("mcp_repair_timeout_ms", 120000).coerceIn(10_000, 300_000)
            )
        }.getOrElse {
            return JSONObject()
                .put("ok", false)
                .put("status", "mcp_powershell_repair_failed")
                .put("error", "${it.javaClass.simpleName}: ${it.message}")
                .put("mcp", mcp)
        }
        return JSONObject()
            .put("ok", result.optJSONObject("result")?.optBoolean("isError", true) == false || result.optBoolean("ok", false))
            .put("status", "mcp_powershell_repair_attempted")
            .put("mcp", mcp)
            .put("result", result)
            .put("hint", "MCP PowerShell attempted to repair Windows sshd/firewall. Retry native SSH next.")
    }

    private fun mcpSummary(raw: JSONObject, includeTools: Boolean, includeConfig: Boolean): JSONObject {
        val status = raw.optJSONObject("status")
        val summarizedStatus = if (status != null) {
            JSONObject()
                .put("available", status.optBoolean("available", false))
                .put("configured", status.optBoolean("configured", false))
                .put("mode", status.optString("mode", "remote_mcp"))
                .put("server", status.optString("server"))
                .put("endpoint", status.optString("endpoint"))
                .put("tool_count", status.optInt("tool_count", status.optJSONArray("tools")?.length() ?: 0))
                .put("duration_ms", status.optLong("duration_ms", 0L))
        } else {
            JSONObject()
        }
        status?.optString("error", "")?.takeIf { it.isNotBlank() }?.let { summarizedStatus.put("error", it) }
        if (includeTools) {
            summarizedStatus.put("tools", status?.optJSONArray("tools") ?: JSONArray())
        }
        if (includeConfig) {
            summarizedStatus.put("config", status?.optJSONObject("config") ?: raw.optJSONObject("config") ?: JSONObject())
        }
        val summary = JSONObject()
            .put("configured", raw.optBoolean("configured", status?.optBoolean("configured", false) ?: false))
            .put("endpoint", raw.optString("endpoint", summarizedStatus.optString("endpoint")))
            .put("state", raw.optString("state", ""))
            .put("status", summarizedStatus)
            .put("summary_compacted", !includeTools || !includeConfig)
        raw.optString("error", "").takeIf { it.isNotBlank() }?.let { summary.put("error", it) }
        raw.optBoolean("ok", false).takeIf { it }?.let { summary.put("ok", it) }
        raw.optBoolean("available", false).takeIf { it }?.let { summary.put("available", it) }
        return summary
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false
}
