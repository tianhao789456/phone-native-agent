package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeHealthCoordinator(
    private val runtimeConfig: AgentRuntimeConfig,
    private val workspace: MobileWorkspace,
    private val profile: NativeAgentProfile,
    private val apiKey: () -> String?,
    private val logSummary: () -> JSONObject,
    private val recentLogs: (Int, String) -> JSONArray,
    private val accessibilityStatus: () -> JSONObject,
    private val workspaceInfo: () -> JSONObject,
    private val diagnoseTerminal: () -> JSONObject,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun selfHealthCheck(): JSONObject {
        val terminalDiagnosis = diagnoseTerminal()
        val accessibility = accessibilityStatus()
        val workspaceSummary = runCatching { workspaceInfo() }
            .getOrElse { JSONObject().put("ok", false).put("error", "${it.javaClass.simpleName}: ${it.message}") }
        val issues = JSONArray()
        if (apiKey().isNullOrBlank()) issues.put("api_key_missing")
        if (!accessibility.optBoolean("connected", false)) issues.put("accessibility_not_connected")
        if (terminalDiagnosis.optString("status") != "ok") issues.put("terminal_${terminalDiagnosis.optString("status")}")
        return JSONObject()
            .put("ok", true)
            .put("healthy", issues.length() == 0)
            .put("runtime", "android-native")
            .put("model", profile.MODEL)
            .put("api_key_set", !apiKey().isNullOrBlank())
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("max_tool_rounds", runtimeConfig.maxToolRounds())
            .put("accessibility", accessibility)
            .put("workspace", workspaceSummary)
            .put("terminal", terminalDiagnosis)
            .put("logs", logSummary())
            .put("recent_logs", recentLogs(20, "warn"))
            .put("issues", issues)
            .put("next_actions", healthNextActions(issues, terminalDiagnosis))
    }

    private fun healthNextActions(issues: JSONArray, terminalDiagnosis: JSONObject): JSONArray {
        val actions = JSONArray()
        for (index in 0 until issues.length()) {
            when (issues.optString(index)) {
                "api_key_missing" -> actions.put("Set model API key in app settings, for example: sk-...")
                "accessibility_not_connected" -> actions.put("Enable Mobile Agent accessibility service in device settings.")
            }
        }
        val repair = terminalDiagnosis.optJSONArray("repair_actions") ?: JSONArray()
        for (index in 0 until repair.length()) {
            actions.put(repair.optString(index))
        }
        return actions
    }
}
