package com.mobileagent.host

import org.json.JSONObject

class NativePcBridgeToolDispatcher(
    private val healthCheck: (JSONObject) -> JSONObject,
    private val status: (JSONObject) -> JSONObject,
    private val recover: (JSONObject) -> JSONObject,
    private val tailscalePreflight: (JSONObject) -> JSONObject,
    private val tailscaleSshDiagnose: (JSONObject) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "pc_bridge_health_check" -> healthCheck(arguments)
            "pc_bridge_status" -> status(arguments)
            "pc_bridge_recover" -> recover(arguments)
            "tailscale_preflight" -> tailscalePreflight(arguments)
            "tailscale_ssh_diagnose" -> tailscaleSshDiagnose(arguments)
            else -> throw IllegalArgumentException("PC bridge dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "pc_bridge_health_check",
            "pc_bridge_status",
            "pc_bridge_recover",
            "tailscale_preflight",
            "tailscale_ssh_diagnose"
        )
    }
}
