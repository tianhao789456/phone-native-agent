package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativePcBridgeTerminalFallback(
    private val runtimeConfig: AgentRuntimeConfig,
    private val mcpClient: NativeMcpClient,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val setMcpConfig: (Boolean, String, String) -> Unit,
    private val terminalRun: (String, String, Int) -> JSONObject,
    private val terminalScript: (JSONObject) -> JSONObject
) {
    fun recover(
        arguments: JSONObject,
        endpoint: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): JSONObject {
        val enabled = arguments.optBoolean(
            "terminal_fallback",
            arguments.optBoolean("allow_terminal_fallback", true)
        )
        if (!enabled) {
            return JSONObject()
                .put("ok", false)
                .put("skipped", true)
                .put("reason", "terminal_fallback is disabled")
        }

        val steps = JSONArray()
        val runtime = terminalRecovery.runtimeStatus(autoRecover = true, force = true)
        steps.put(JSONObject().put("step", "terminal_runtime").put("result", runtime))
        if (runtime.optString("state") != "ok" && !runtime.optBoolean("available", false)) {
            return JSONObject()
                .put("ok", false)
                .put("status", "terminal_unavailable")
                .put("steps", steps)
                .put("hint", "Termux terminal backend is not available, so terminal SSH fallback cannot run.")
        }

        val host = arguments.optString("terminal_ssh_host", runtimeConfig.sshHost()).ifBlank { runtimeConfig.sshHost() }
        val user = arguments.optString("terminal_ssh_user", runtimeConfig.sshUser()).ifBlank { runtimeConfig.sshUser() }
        val port = arguments.optInt("terminal_ssh_port", runtimeConfig.sshPort()).coerceIn(1, 65535)
        if (host.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("status", "terminal_ssh_host_missing")
                .put("steps", steps)
                .put("hint", "No SSH host is configured for terminal fallback.")
        }

        val sshVersion = terminalRun("ssh -V", "", 10)
        steps.put(JSONObject().put("step", "terminal_ssh_probe").put("result", sshVersion))
        val versionResult = sshVersion.optJSONObject("result")
        val versionOk = sshVersion.optBoolean("ok", false) ||
            versionResult?.optInt("returncode", -1) == 0 ||
            versionResult?.optString("stderr", "")?.contains("OpenSSH", ignoreCase = true) == true
        if (!versionOk) {
            return JSONObject()
                .put("ok", false)
                .put("status", "terminal_openssh_missing")
                .put("steps", steps)
                .put("hint", "Termux is reachable but openssh is missing or not runnable. Install openssh in Termux and configure key auth.")
        }

        val target = if (user.isBlank()) host else "$user@$host"
        val identityPath = arguments.optString("terminal_identity_path", "").trim()
        val identityArg = if (identityPath.isBlank()) "" else "-i ${NativePcBridgeScripts.shellQuote(identityPath)} "
        val endpointPath = NativePcBridgeScripts.parseEndpoint(endpoint).third
        val effectiveEndpoint = "http://127.0.0.1:$localPort$endpointPath"
        val pidFile = ".mobile-agent/pc-bridge/mcp-ssh-forward-$localPort.pid"
        val logFile = ".mobile-agent/pc-bridge/mcp-ssh-forward-$localPort.log"
        val sshCommand = "ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 " +
            "-o ServerAliveCountMax=3 -o StrictHostKeyChecking=accept-new " +
            "-p $port ${identityArg}-N -L 127.0.0.1:$localPort:$remoteHost:$remotePort ${NativePcBridgeScripts.shellQuote(target)}"
        val script = """
set -eu
mkdir -p .mobile-agent/pc-bridge
PID_FILE=${NativePcBridgeScripts.shellQuote(pidFile)}
LOG_FILE=${NativePcBridgeScripts.shellQuote(logFile)}
if [ -f "${'$'}PID_FILE" ]; then
  OLD_PID="$(cat "${'$'}PID_FILE" 2>/dev/null || true)"
  if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then
    echo "already_running pid=${'$'}OLD_PID"
    exit 0
  fi
fi
nohup $sshCommand > "${'$'}LOG_FILE" 2>&1 &
PID="${'$'}!"
echo "${'$'}PID" > "${'$'}PID_FILE"
sleep 2
if kill -0 "${'$'}PID" 2>/dev/null; then
  echo "started pid=${'$'}PID"
  exit 0
fi
echo "ssh_forward_exited"
cat "${'$'}LOG_FILE" 2>/dev/null || true
exit 1
""".trimIndent()
        val started = terminalScript(
            JSONObject()
                .put("script", script)
                .put("interpreter", "sh")
                .put("timeout", arguments.optInt("terminal_fallback_timeout", 30).coerceIn(5, 120))
                .put("wait", true)
                .put("max_output_chars", 12000)
                .put("name", "mcp-ssh-forward-fallback")
        )
        steps.put(JSONObject().put("step", "terminal_forward_start").put("result", started))
        val startedResult = started.optJSONObject("result")
        val startedOk = started.optBoolean("ok", false) || startedResult?.optInt("returncode", -1) == 0
        if (!startedOk) {
            return JSONObject()
                .put("ok", false)
                .put("status", "terminal_forward_failed")
                .put("endpoint", effectiveEndpoint)
                .put("steps", steps)
                .put("hint", "Termux SSH fallback failed. The usual cause is missing Termux SSH key/config for the Windows host.")
        }

        setMcpConfig(true, effectiveEndpoint, runtimeConfig.mcpAuthToken())
        val mcp = mcpClient.runtimeStatus(force = true)
        steps.put(JSONObject().put("step", "mcp_verify_after_terminal_forward").put("result", mcp))
        val mcpOk = mcp.optString("state") == "ok" || mcp.optBoolean("available", false)
        return JSONObject()
            .put("ok", mcpOk)
            .put("status", if (mcpOk) "mcp_recovered_by_terminal_ssh" else "terminal_forward_started_mcp_unhealthy")
            .put("endpoint", effectiveEndpoint)
            .put("host", host)
            .put("port", port)
            .put("user", user)
            .put("local_port", localPort)
            .put("remote_host", remoteHost)
            .put("remote_port", remotePort)
            .put("steps", steps)
    }
}
