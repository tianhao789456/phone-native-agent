package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AgentRuntimeConfig(context: Context) {
    private val prefs = context.getSharedPreferences("mobile-agent-core", Context.MODE_PRIVATE)

    fun permissionMode(): String {
        val value = prefs.getString("permission_mode", MODE_SAFE) ?: MODE_SAFE
        return if (permissionModes.any { it.id == value }) value else MODE_SAFE
    }

    fun setPermissionMode(mode: String) {
        require(permissionModes.any { it.id == mode }) { "Unknown permission mode: $mode" }
        prefs.edit().putString("permission_mode", mode).apply()
    }

    fun terminalEnabled(): Boolean {
        return prefs.getBoolean("terminal_enabled", false)
    }

    fun terminalBaseUrl(): String {
        return prefs.getString("terminal_base_url", DEFAULT_TERMINAL_BASE_URL) ?: DEFAULT_TERMINAL_BASE_URL
    }

    fun setTerminalConfig(enabled: Boolean, baseUrl: String) {
        prefs.edit()
            .putBoolean("terminal_enabled", enabled)
            .putString("terminal_base_url", normalizeTerminalBaseUrl(baseUrl))
            .apply()
    }

    fun mcpEnabled(): Boolean {
        return prefs.getBoolean("mcp_enabled", false)
    }

    fun mcpBaseUrl(): String {
        return prefs.getString("mcp_base_url", DEFAULT_MCP_BASE_URL) ?: DEFAULT_MCP_BASE_URL
    }

    fun mcpAuthToken(): String {
        return prefs.getString("mcp_auth_token", "") ?: ""
    }

    fun setMcpConfig(enabled: Boolean, baseUrl: String, authToken: String = "") {
        prefs.edit()
            .putBoolean("mcp_enabled", enabled)
            .putString("mcp_base_url", normalizeMcpBaseUrl(baseUrl))
            .putString("mcp_auth_token", authToken.trim())
            .apply()
    }

    fun maxToolRounds(): Int {
        return prefs.getInt("max_tool_rounds", DEFAULT_MAX_TOOL_ROUNDS).coerceIn(1, MAX_TOOL_ROUNDS_LIMIT)
    }

    fun setMaxToolRounds(value: Int) {
        prefs.edit().putInt("max_tool_rounds", value.coerceIn(1, MAX_TOOL_ROUNDS_LIMIT)).apply()
    }

    fun configJson(): JSONObject {
        return JSONObject()
            .put("permission_mode", permissionMode())
            .put("permission_modes", permissionModesJson())
            .put("terminal", terminalConfigJson())
            .put("mcp", mcpConfigJson())
            .put("max_tool_rounds", maxToolRounds())
            .put("max_tool_rounds_limit", MAX_TOOL_ROUNDS_LIMIT)
    }

    fun terminalConfigJson(): JSONObject {
        return JSONObject()
            .put("enabled", terminalEnabled())
            .put("base_url", terminalBaseUrl())
            .put("timeout_ms", TERMINAL_TIMEOUT_MS)
    }

    fun mcpConfigJson(): JSONObject {
        return JSONObject()
            .put("enabled", mcpEnabled())
            .put("base_url", mcpBaseUrl())
            .put("has_auth_token", mcpAuthToken().isNotBlank())
            .put("timeout_ms", MCP_TIMEOUT_MS)
    }

    fun permissionModesJson(): JSONArray {
        val array = JSONArray()
        permissionModes.forEach { mode ->
            array.put(
                JSONObject()
                    .put("id", mode.id)
                    .put("label", mode.label)
                    .put("description", mode.description)
            )
        }
        return array
    }

    fun normalizeTerminalBaseUrl(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        if (value.isBlank()) return DEFAULT_TERMINAL_BASE_URL
        require(value.startsWith("http://127.0.0.1") || value.startsWith("http://localhost")) {
            "终端后端地址只能设置为 http://127.0.0.1 或 http://localhost。"
        }
        return value
    }

    fun normalizeMcpBaseUrl(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        if (value.isBlank()) return DEFAULT_MCP_BASE_URL
        require(value.startsWith("http://") || value.startsWith("https://")) {
            "MCP 地址必须以 http:// 或 https:// 开头"
        }
        return value
    }

    data class PermissionMode(val id: String, val label: String, val description: String)

    companion object {
        const val MODE_SAFE = "safe"
        const val MODE_ASK = "ask"
        const val MODE_DANGER = "danger"
        const val MODE_DEVELOPER = "developer"
        const val DEFAULT_TERMINAL_BASE_URL = "http://127.0.0.1:8787"
        const val DEFAULT_MCP_BASE_URL = "http://127.0.0.1:8931"
        const val TERMINAL_TIMEOUT_MS = 3000
        const val MCP_TIMEOUT_MS = 5000
        const val DEFAULT_MAX_TOOL_ROUNDS = 30
        const val MAX_TOOL_ROUNDS_LIMIT = 100

        val permissionModes = listOf(
            PermissionMode(
                MODE_SAFE,
                "安全",
                "默认模式，保守、可读写受限，适合常规办公。"
            ),
            PermissionMode(
                MODE_ASK,
                "确认动作",
                "允许动作工具，但每次会要求在APP内确认，适合交互式控制。"
            ),
            PermissionMode(
                MODE_DANGER,
                "高风险",
                "启用高风险工具（命令执行等），默认默认信任风险提示。"
            ),
            PermissionMode(
                MODE_DEVELOPER,
                "开发者",
                "开发者模式，默认更开放，主要用于调试。"
            )
        )
    }
}
