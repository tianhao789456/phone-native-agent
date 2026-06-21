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

    fun terminalEnabled(): Boolean = prefs.getBoolean("terminal_enabled", false)

    fun terminalBaseUrl(): String {
        return prefs.getString("terminal_base_url", DEFAULT_TERMINAL_BASE_URL) ?: DEFAULT_TERMINAL_BASE_URL
    }

    fun setTerminalConfig(enabled: Boolean, baseUrl: String) {
        prefs.edit()
            .putBoolean("terminal_enabled", enabled)
            .putString("terminal_base_url", normalizeTerminalBaseUrl(baseUrl))
            .apply()
    }

    fun mcpEnabled(): Boolean = mcpServerEnabled(mcpActiveServerId())

    fun mcpBaseUrl(): String = mcpServerBaseUrl(mcpActiveServerId())

    fun mcpAuthToken(): String = mcpServerAuthToken(mcpActiveServerId())

    fun setMcpConfig(enabled: Boolean, baseUrl: String, authToken: String = "") {
        setMcpServerConfig(
            id = "default",
            name = "Default MCP",
            type = "desktop",
            enabled = enabled,
            baseUrl = baseUrl,
            authToken = authToken,
            setActive = true
        )
    }

    fun mcpActiveServerId(): String {
        return normalizeMcpServerId(prefs.getString("mcp_active_server", "default") ?: "default")
    }

    fun mcpServers(): JSONArray {
        val raw = prefs.getString("mcp_servers", null)
        val parsed = runCatching { if (raw.isNullOrBlank()) null else JSONArray(raw) }.getOrNull()
        val output = JSONArray()
        val seen = linkedSetOf<String>()
        if (parsed != null) {
            for (index in 0 until parsed.length()) {
                val item = parsed.optJSONObject(index) ?: continue
                val server = normalizeMcpServer(item)
                val id = server.optString("id")
                if (id.isNotBlank() && seen.add(id)) output.put(server)
            }
        }
        if ("default" !in seen) {
            output.put(legacyDefaultMcpServer())
            seen.add("default")
        }
        if ("phone-local" !in seen) {
            output.put(
                JSONObject()
                    .put("id", "phone-local")
                    .put("name", "Phone Local MCP")
                    .put("type", "phone_local")
                    .put("enabled", false)
                    .put("base_url", "http://127.0.0.1:18790/mcp")
                    .put("auth_token", "")
                    .put("has_auth_token", false)
            )
        }
        return output
    }

    fun mcpServer(id: String = mcpActiveServerId()): JSONObject {
        val target = normalizeMcpServerId(id.ifBlank { mcpActiveServerId() })
        val servers = mcpServers()
        for (index in 0 until servers.length()) {
            val item = servers.optJSONObject(index) ?: continue
            if (item.optString("id") == target) return JSONObject(item.toString())
        }
        if (target == "default") return legacyDefaultMcpServer()
        throw IllegalArgumentException("Unknown MCP server: $target")
    }

    fun mcpServerEnabled(id: String = mcpActiveServerId()): Boolean {
        return mcpServer(id).optBoolean("enabled", false)
    }

    fun mcpServerBaseUrl(id: String = mcpActiveServerId()): String {
        return normalizeMcpBaseUrl(mcpServer(id).optString("base_url", DEFAULT_MCP_BASE_URL))
    }

    fun mcpServerAuthToken(id: String = mcpActiveServerId()): String {
        return mcpServer(id).optString("auth_token", "")
    }

    fun setMcpServerConfig(
        id: String,
        name: String = "",
        type: String = "desktop",
        enabled: Boolean,
        baseUrl: String,
        authToken: String = "",
        setActive: Boolean = false
    ) {
        val cleanId = normalizeMcpServerId(id)
        val next = JSONObject()
            .put("id", cleanId)
            .put("name", name.ifBlank { cleanId })
            .put("type", type.ifBlank { "desktop" })
            .put("enabled", enabled)
            .put("base_url", normalizeMcpBaseUrl(baseUrl))
            .put("auth_token", authToken.trim())
            .put("has_auth_token", authToken.trim().isNotBlank())

        val servers = mcpServers()
        val merged = JSONArray()
        var replaced = false
        for (index in 0 until servers.length()) {
            val item = servers.optJSONObject(index) ?: continue
            if (item.optString("id") == cleanId) {
                merged.put(next)
                replaced = true
            } else {
                merged.put(item)
            }
        }
        if (!replaced) merged.put(next)
        val editor = prefs.edit().putString("mcp_servers", merged.toString())
        if (setActive) {
            editor.putString("mcp_active_server", cleanId)
        } else if (prefs.getString("mcp_active_server", "").isNullOrBlank()) {
            editor.putString("mcp_active_server", "default")
        }
        if (cleanId == "default") {
            editor
                .putBoolean("mcp_enabled", enabled)
                .putString("mcp_base_url", normalizeMcpBaseUrl(baseUrl))
                .putString("mcp_auth_token", authToken.trim())
        }
        editor.apply()
    }

    fun sshEnabled(): Boolean = prefs.getBoolean("ssh_enabled", false)

    fun sshHost(): String = prefs.getString("ssh_host", "") ?: ""

    fun sshPort(): Int = prefs.getInt("ssh_port", 22).coerceIn(1, 65535)

    fun sshUser(): String = prefs.getString("ssh_user", "") ?: ""

    fun sshKeyPath(): String = prefs.getString("ssh_key_path", "") ?: ""

    fun sshPassphrase(): String = prefs.getString("ssh_passphrase", "") ?: ""

    fun sshConnectTimeoutMs(): Int = prefs.getInt("ssh_connect_timeout_ms", 8000).coerceIn(1000, 120000)

    fun sshCommandTimeoutMs(): Int = prefs.getInt("ssh_command_timeout_ms", 60000).coerceIn(1000, 600000)

    fun setSshConfig(
        enabled: Boolean,
        host: String,
        port: Int,
        user: String,
        keyPath: String = "",
        passphrase: String = "",
        connectTimeoutMs: Int = sshConnectTimeoutMs(),
        commandTimeoutMs: Int = sshCommandTimeoutMs()
    ) {
        prefs.edit()
            .putBoolean("ssh_enabled", enabled)
            .putString("ssh_host", normalizeSshHost(host))
            .putInt("ssh_port", port.coerceIn(1, 65535))
            .putString("ssh_user", user.trim())
            .putString("ssh_key_path", normalizeSshKeyPath(keyPath))
            .putString("ssh_passphrase", passphrase)
            .putInt("ssh_connect_timeout_ms", connectTimeoutMs.coerceIn(1000, 120000))
            .putInt("ssh_command_timeout_ms", commandTimeoutMs.coerceIn(1000, 600000))
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
            .put("ssh", sshConfigJson())
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
        val servers = mcpServers()
        val publicServers = JSONArray()
        for (index in 0 until servers.length()) {
            val item = JSONObject(servers.optJSONObject(index)?.toString() ?: "{}")
            val id = item.optString("id")
            item.remove("auth_token")
            item.put("has_auth_token", mcpServerAuthToken(id).isNotBlank())
            publicServers.put(item)
        }
        val activeId = mcpActiveServerId()
        return JSONObject()
            .put("enabled", mcpServerEnabled(activeId))
            .put("base_url", mcpServerBaseUrl(activeId))
            .put("has_auth_token", mcpServerAuthToken(activeId).isNotBlank())
            .put("active_server", activeId)
            .put("multi_server_ready", true)
            .put("servers", publicServers)
            .put("timeout_ms", MCP_TIMEOUT_MS)
    }

    fun sshConfigJson(): JSONObject {
        return JSONObject()
            .put("enabled", sshEnabled())
            .put("host", sshHost())
            .put("port", sshPort())
            .put("user", sshUser())
            .put("key_path", sshKeyPath())
            .put("has_passphrase", sshPassphrase().isNotBlank())
            .put("connect_timeout_ms", sshConnectTimeoutMs())
            .put("command_timeout_ms", sshCommandTimeoutMs())
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
            "Terminal backend address must start with http://127.0.0.1 or http://localhost."
        }
        return value
    }

    fun normalizeMcpBaseUrl(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        if (value.isBlank()) return DEFAULT_MCP_BASE_URL
        require(value.startsWith("http://") || value.startsWith("https://")) {
            "MCP address must start with http:// or https://."
        }
        return value
    }

    fun normalizeMcpServerId(id: String): String {
        val value = id.trim().lowercase()
        if (value.isBlank()) return "default"
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "MCP server id must use lowercase letters, numbers, dot, underscore, or hyphen."
        }
        return value
    }

    fun normalizeSshHost(host: String): String {
        val value = host.trim()
        if (value.isBlank()) return ""
        return value.removePrefix("ssh://").removePrefix("SSH://").trim().trimEnd('/')
    }

    fun normalizeSshKeyPath(path: String): String {
        return path.trim()
    }

    private fun normalizeMcpServer(input: JSONObject): JSONObject {
        val id = normalizeMcpServerId(input.optString("id", "default"))
        val token = input.optString(
            "auth_token",
            if (id == "default") prefs.getString("mcp_auth_token", "") ?: "" else ""
        )
        return JSONObject()
            .put("id", id)
            .put("name", input.optString("name", id).ifBlank { id })
            .put("type", input.optString("type", if (id == "phone-local") "phone_local" else "desktop").ifBlank { "desktop" })
            .put("enabled", input.optBoolean("enabled", id == "default" && prefs.getBoolean("mcp_enabled", false)))
            .put("base_url", normalizeMcpBaseUrl(input.optString("base_url", if (id == "default") legacyMcpBaseUrl() else DEFAULT_MCP_BASE_URL)))
            .put("auth_token", token)
            .put("has_auth_token", token.isNotBlank())
    }

    private fun legacyDefaultMcpServer(): JSONObject {
        val token = prefs.getString("mcp_auth_token", "") ?: ""
        return JSONObject()
            .put("id", "default")
            .put("name", "Default MCP")
            .put("type", "desktop")
            .put("enabled", prefs.getBoolean("mcp_enabled", false))
            .put("base_url", legacyMcpBaseUrl())
            .put("auth_token", token)
            .put("has_auth_token", token.isNotBlank())
    }

    private fun legacyMcpBaseUrl(): String {
        return prefs.getString("mcp_base_url", DEFAULT_MCP_BASE_URL) ?: DEFAULT_MCP_BASE_URL
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
            PermissionMode(MODE_SAFE, "\u5b89\u5168", "\u9ed8\u8ba4\u6a21\u5f0f\uff0c\u4fdd\u5b88\u3001\u53ef\u8bfb\u5199\u53d7\u9650\uff0c\u9002\u5408\u5e38\u89c4\u529e\u516c\u3002"),
            PermissionMode(MODE_ASK, "\u786e\u8ba4\u52a8\u4f5c", "\u5141\u8bb8\u52a8\u4f5c\u5de5\u5177\uff0c\u4f46\u6bcf\u6b21\u90fd\u4f1a\u8981\u6c42\u5728\u5e94\u7528\u5185\u786e\u8ba4\uff0c\u9002\u5408\u4ea4\u4e92\u5f0f\u63a7\u5236\u3002"),
            PermissionMode(MODE_DANGER, "\u9ad8\u98ce\u9669", "\u542f\u7528\u9ad8\u98ce\u9669\u5de5\u5177\uff08\u547d\u4ee4\u6267\u884c\u7b49\uff09\uff0c\u9ed8\u8ba4\u5e26\u98ce\u9669\u63d0\u793a\u3002"),
            PermissionMode(MODE_DEVELOPER, "\u5f00\u53d1\u8005", "\u5f00\u53d1\u8005\u6a21\u5f0f\uff0c\u9ed8\u8ba4\u66f4\u5f00\u653e\uff0c\u4e3b\u8981\u7528\u4e8e\u8c03\u8bd5\u3002")
        )
    }
}
