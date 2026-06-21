package com.mobileagent.host

import org.json.JSONObject

object NativeToolRecoveryConfig {
    const val TERMINAL_RECOVERY_TIMEOUT_MS = 2500
    const val MCP_LOCAL_FORWARD_PORT = 18000

    fun terminalRecoveryArgs(): JSONObject =
        JSONObject()
            .put("use_run_command", false)
            .put("open_termux", false)
            .put("wait_ms", TERMINAL_RECOVERY_TIMEOUT_MS)

    fun mcpRecoveryArgs(): JSONObject =
        JSONObject()
            .put("diagnose_ssh", true)
            .put("use_ssh_tunnel", true)
            .put("local_forward_port", MCP_LOCAL_FORWARD_PORT)
            .put("wait_ms", TERMINAL_RECOVERY_TIMEOUT_MS)
            .put("sync_auth_token", true)
}
