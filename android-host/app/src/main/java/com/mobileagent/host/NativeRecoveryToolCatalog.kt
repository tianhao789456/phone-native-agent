package com.mobileagent.host

import org.json.JSONObject

object NativeRecoveryToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "recover_terminal_backend",
                    description = "Try to recover the optional Termux terminal backend. It enables the local terminal endpoint, optionally opens Termux, and tries Termux RUN_COMMAND to start scripts/start-http-termux.sh. Requires danger mode and user confirmation.",
                    category = "recovery",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "use_run_command" to NativeToolSchema.boolProp(false),
                        "open_termux" to NativeToolSchema.boolProp(true),
                        "force_restart" to NativeToolSchema.boolProp(true),
                        "wait_ms" to NativeToolSchema.intProp(2500)
                    ),
                    autoRecover = false
                ),
        NativeToolDescriptor(
                    name = "pc_bridge_health_check",
                    description = "Classify the phone-to-PC bridge state into a clear recovery decision: healthy, MCP down but SSH can repair it, SSH down but MCP can repair it, or both down so the agent must stop retrying and report user intervention.",
                    category = "recovery",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "diagnose_ssh" to NativeToolSchema.boolProp(true),
                        "check_tailscale" to NativeToolSchema.boolProp(false),
                        "include_mcp_tools" to NativeToolSchema.boolProp(false),
                        "include_mcp_config" to NativeToolSchema.boolProp(false),
                        "hosts" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                        "timeout_ms" to NativeToolSchema.intProp(6000)
                    ),
                    autoRecover = false
                ),
        NativeToolDescriptor(
                    name = "pc_bridge_recover",
                    description = "Recover the phone-to-PC bridge in one call: optionally select a working SSH host, connect over SSH, diagnose the remote MCP endpoint from Windows, optionally kill the MCP port process, run a restart command, wait, and verify MCP again. Use this when MCP returns 502/offline or desktop control is broken but SSH may work.",
                    category = "recovery",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "hosts" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                        "ssh_port" to NativeToolSchema.intProp(22),
                        "mcp_endpoint" to NativeToolSchema.stringProp(""),
                        "auth_token" to NativeToolSchema.stringProp(""),
                        "auth_token_path" to NativeToolSchema.stringProp(""),
                        "auth_token_command" to NativeToolSchema.stringProp(""),
                        "sync_auth_token" to NativeToolSchema.boolProp(true),
                        "use_ssh_tunnel" to NativeToolSchema.boolProp(true),
                        "local_forward_port" to NativeToolSchema.intProp(18000),
                        "remote_forward_host" to NativeToolSchema.stringProp("127.0.0.1"),
                        "remote_forward_port" to NativeToolSchema.intProp(8000),
                        "remote_port" to NativeToolSchema.intProp(8000),
                        "remote_mcp_endpoint" to NativeToolSchema.stringProp("http://127.0.0.1:8000/mcp"),
                        "terminal_fallback" to NativeToolSchema.boolProp(true),
                        "allow_terminal_fallback" to NativeToolSchema.boolProp(true),
                        "terminal_ssh_host" to NativeToolSchema.stringProp(""),
                        "terminal_ssh_user" to NativeToolSchema.stringProp(""),
                        "terminal_ssh_port" to NativeToolSchema.intProp(22),
                        "terminal_identity_path" to NativeToolSchema.stringProp(""),
                        "terminal_fallback_timeout" to NativeToolSchema.intProp(30),
                        "restart_command" to NativeToolSchema.stringProp(""),
                        "start_command" to NativeToolSchema.stringProp(""),
                        "kill_port_process" to NativeToolSchema.boolProp(false),
                        "shell" to NativeToolSchema.stringProp("powershell"),
                        "timeout_ms" to NativeToolSchema.intProp(6000),
                        "command_timeout_ms" to NativeToolSchema.intProp(60000),
                        "wait_ms" to NativeToolSchema.intProp(2500),
                        "include_mcp_tools" to NativeToolSchema.boolProp(false),
                        "include_mcp_config" to NativeToolSchema.boolProp(false)
                    ),
                    autoRecover = true
                )
    )
}
