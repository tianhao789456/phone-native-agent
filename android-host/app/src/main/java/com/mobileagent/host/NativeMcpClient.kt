package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NativeMcpClient(
    private val runtimeConfig: AgentRuntimeConfig
) {
    private val sessions = ConcurrentHashMap<String, String>()

    fun clearSessions() {
        sessions.clear()
    }

    fun endpoint(serverId: String = ""): String {
        val base = runtimeConfig.mcpServerBaseUrl(serverId).trim().trimEnd('/')
        return when {
            base.isBlank() -> "${AgentRuntimeConfig.DEFAULT_MCP_BASE_URL}/mcp"
            base.endsWith("/mcp", ignoreCase = true) -> base
            else -> "$base/mcp"
        }
    }

    fun servers(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("active_server", runtimeConfig.mcpActiveServerId())
            .put("servers", runtimeConfig.mcpConfigJson().optJSONArray("servers") ?: JSONArray())
            .put("usage", "Pass server id to mcp_status, mcp_tools, mcp_tool_info, and mcp_call. Use default for the current Windows MCP unless another server is selected.")
    }

    fun configure(arguments: JSONObject): JSONObject {
        val serverId = runtimeConfig.normalizeMcpServerId(arguments.optString("id", arguments.optString("server", "default")))
        val enabled = arguments.optBoolean("enabled", true)
        val existing = runCatching { runtimeConfig.mcpServer(serverId) }.getOrNull()
        val baseUrl = arguments.optString("base_url", arguments.optString("endpoint", existing?.optString("base_url", "") ?: ""))
            .ifBlank { existing?.optString("base_url") ?: runtimeConfig.mcpBaseUrl() }
        val token = when {
            arguments.has("auth_token") -> arguments.optString("auth_token", "")
            arguments.has("token") -> arguments.optString("token", "")
            else -> existing?.optString("auth_token") ?: runtimeConfig.mcpServerAuthToken(serverId)
        }
        runtimeConfig.setMcpServerConfig(
            id = serverId,
            name = arguments.optString("name", existing?.optString("name", "") ?: serverId),
            type = arguments.optString("type", existing?.optString("type", "desktop") ?: "desktop"),
            enabled = enabled,
            baseUrl = baseUrl,
            authToken = token,
            setActive = arguments.optBoolean("set_active", true)
        )
        clearSessions()
        val verify = if (arguments.optBoolean("verify", true)) status(serverId) else JSONObject().put("skipped", true)
        return JSONObject()
            .put("ok", true)
            .put("configured", runtimeConfig.mcpConfigJson())
            .put("server", serverId)
            .put("endpoint", endpoint(serverId))
            .put("verified", verify.optBoolean("available", false))
            .put("verification", verify)
    }

    fun status(server: String = ""): JSONObject {
        val serverId = runtimeConfig.normalizeMcpServerId(server.ifBlank { runtimeConfig.mcpActiveServerId() })
        if (!runtimeConfig.mcpServerEnabled(serverId)) {
            return JSONObject()
                .put("available", false)
                .put("configured", false)
                .put("mode", "remote_mcp")
                .put("server", serverId)
                .put("config", runtimeConfig.mcpConfigJson())
                .put("error", "MCP backend is disabled")
        }
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val tools = tools(serverId = serverId)
            JSONObject()
                .put("available", true)
                .put("configured", true)
                .put("mode", "remote_mcp")
                .put("server", serverId)
                .put("endpoint", endpoint(serverId))
                .put("config", runtimeConfig.mcpConfigJson())
                .put("tool_count", tools.optInt("tool_count", 0))
                .put("tools", tools.optJSONArray("tools") ?: JSONArray())
                .put("duration_ms", System.currentTimeMillis() - startedAt)
        }.getOrElse { error ->
            JSONObject()
                .put("available", false)
                .put("configured", true)
                .put("mode", "remote_mcp")
                .put("server", serverId)
                .put("endpoint", runCatching { endpoint(serverId) }.getOrDefault(""))
                .put("config", runtimeConfig.mcpConfigJson())
                .put("duration_ms", System.currentTimeMillis() - startedAt)
                .put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun tools(search: String = "", includeSchema: Boolean = false, serverId: String = ""): JSONObject {
        val resolvedServer = runtimeConfig.normalizeMcpServerId(serverId.ifBlank { runtimeConfig.mcpActiveServerId() })
        if (!runtimeConfig.mcpServerEnabled(resolvedServer)) {
            return JSONObject()
                .put("available", false)
                .put("server", resolvedServer)
                .put("error", "MCP backend is disabled")
                .put("config", runtimeConfig.mcpConfigJson())
        }
        val startedAt = System.currentTimeMillis()
        val endpoint = endpoint(resolvedServer)
        val sessionId = ensureSession(resolvedServer, endpoint, AgentRuntimeConfig.MCP_TIMEOUT_MS * 2)
        val response = postJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "tools/list")
                .put("params", JSONObject())
                .toString(),
            AgentRuntimeConfig.MCP_TIMEOUT_MS * 2,
            sessionId,
            runtimeConfig.mcpServerAuthToken(resolvedServer)
        )
        val result = parseResult(response.first, "tools/list")
        val allTools = result.optJSONArray("tools") ?: JSONArray()
        val query = search.trim().lowercase()
        val filtered = JSONArray()
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            if (query.isBlank() ||
                item.optString("name").lowercase().contains(query) ||
                item.optString("description").lowercase().contains(query)
            ) {
                filtered.put(if (includeSchema) item else compactTool(item))
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("available", true)
            .put("configured", true)
            .put("endpoint", endpoint)
            .put("server", resolvedServer)
            .put("mode", "remote_mcp")
            .put("progressive_loading", true)
            .put("detail_tool", "mcp_tool_info")
            .put("include_schema", includeSchema)
            .put("tool_count", filtered.length())
            .put("tools", filtered)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("config", runtimeConfig.mcpConfigJson())
    }

    fun toolInfo(tool: String, serverId: String = ""): JSONObject {
        val resolvedServer = runtimeConfig.normalizeMcpServerId(serverId.ifBlank { runtimeConfig.mcpActiveServerId() })
        val target = tool.trim()
        if (target.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "tool is required")
        }
        val listing = tools(target, includeSchema = true, serverId = resolvedServer)
        val tools = listing.optJSONArray("tools") ?: JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            if (item.optString("name") == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("available", true)
                    .put("server", resolvedServer)
                    .put("endpoint", listing.optString("endpoint"))
                    .put("tool", item)
                    .put("config", runtimeConfig.mcpConfigJson())
            }
        }
        return JSONObject()
            .put("ok", false)
            .put("available", listing.optBoolean("available", false))
            .put("server", resolvedServer)
            .put("error", "MCP tool not found: $target")
            .put("matches", tools)
            .put("config", runtimeConfig.mcpConfigJson())
    }

    fun call(tool: String, arguments: JSONObject, timeoutMs: Int, serverId: String = ""): JSONObject {
        val resolvedServer = runtimeConfig.normalizeMcpServerId(serverId.ifBlank { runtimeConfig.mcpActiveServerId() })
        if (!runtimeConfig.mcpServerEnabled(resolvedServer)) {
            return JSONObject()
                .put("available", false)
                .put("server", resolvedServer)
                .put("error", "MCP backend is disabled")
                .put("config", runtimeConfig.mcpConfigJson())
        }
        val targetTool = tool.trim()
        if (targetTool.isBlank()) {
            throw IllegalArgumentException("tool is required")
        }
        val safeTimeout = timeoutMs.coerceIn(1000, 300000)
        val startedAt = System.currentTimeMillis()
        val endpoint = endpoint(resolvedServer)
        val sessionId = ensureSession(resolvedServer, endpoint, safeTimeout)
        val response = postJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", targetTool)
                        .put("arguments", arguments)
                )
                .toString(),
            safeTimeout,
            sessionId,
            runtimeConfig.mcpServerAuthToken(resolvedServer)
        )
        val result = parseResult(response.first, "tools/call")
        return JSONObject()
            .put("ok", true)
            .put("server", resolvedServer)
            .put("tool", targetTool)
            .put("endpoint", endpoint)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("result", result)
            .put("session_id", response.second ?: "")
            .put("config", runtimeConfig.mcpConfigJson())
    }

    fun runtimeStatus(force: Boolean): JSONObject {
        val status = if (force) {
            runCatching { status() }.getOrElse {
                JSONObject()
                    .put("available", false)
                    .put("error", "${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            status()
        }
        return JSONObject()
            .put("configured", runtimeConfig.mcpEnabled())
            .put("endpoint", runtimeConfig.mcpBaseUrl())
            .put("state", if (status.optBoolean("available", false)) "ok" else "offline")
            .put("status", status)
    }

    private fun compactTool(item: JSONObject): JSONObject {
        val schema = item.optJSONObject("inputSchema") ?: item.optJSONObject("schema") ?: JSONObject()
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val argumentNames = JSONArray()
        val keys = properties.keys()
        while (keys.hasNext()) {
            argumentNames.put(keys.next())
        }
        return JSONObject()
            .put("name", item.optString("name"))
            .put("description", item.optString("description"))
            .put("argument_names", argumentNames)
            .put("has_schema", schema.length() > 0)
    }

    private fun ensureSession(serverId: String, endpoint: String, timeoutMs: Int): String? {
        val sessionKey = "$serverId|$endpoint"
        val existing = sessions[sessionKey]
        if (existing != null && existing.isNotBlank()) {
            return existing
        }
        return runCatching { initializeSession(serverId, endpoint, timeoutMs) }.getOrElse {
            if (it.message?.contains("already initialized", ignoreCase = true) == true) {
                sessions[sessionKey]
            } else {
                throw it
            }
        }
    }

    private fun initializeSession(serverId: String, endpoint: String, timeoutMs: Int): String? {
        val sessionKey = "$serverId|$endpoint"
        val response = postJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("capabilities", JSONObject())
                        .put(
                            "clientInfo",
                            JSONObject()
                                .put("name", "mobile-agent")
                                .put("version", "0.1.0")
                        )
                )
                .toString(),
            timeoutMs,
            null,
            runtimeConfig.mcpServerAuthToken(serverId)
        )
        parseResult(response.first, "initialize")
        val session = response.second ?: sessions[sessionKey]
        if (session != null && session.isNotBlank()) {
            sessions[sessionKey] = session
        }
        return session
    }

    private fun parseResult(payload: JSONObject, method: String): JSONObject {
        val error = payload.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code")
            val msg = error.optString("message")
            val data = error.optString("data")
            throw IllegalStateException("MCP $method failed: $code $msg $data".trim())
        }
        return payload.optJSONObject("result") ?: JSONObject().put("method", method)
    }

    private fun postJson(
        url: String,
        body: String,
        timeoutMs: Int,
        mcpSessionId: String?,
        authToken: String = runtimeConfig.mcpAuthToken()
    ): Pair<JSONObject, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        if (mcpSessionId != null && mcpSessionId.isNotBlank()) {
            connection.setRequestProperty("Mcp-Session-Id", mcpSessionId)
        }
        val auth = authToken.trim()
        if (auth.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $auth")
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body)
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = readResponseText(connection, stream)
        val responseText = extractJsonPayload(text)
        val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        if (connection.responseCode !in 200..299) {
            val detail = response.toString()
            throw IllegalStateException("HTTP ${connection.responseCode}: $detail")
        }
        return Pair(response, connection.getHeaderField("Mcp-Session-Id"))
    }

    private fun readResponseText(connection: HttpURLConnection, stream: InputStream?): String {
        if (stream == null) return ""
        val contentType = connection.contentType ?: ""
        if (!contentType.contains("text/event-stream", ignoreCase = true)) {
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val dataLines = mutableListOf<String>()
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount += 1
                val trimmed = line.trim()
                if (trimmed.isEmpty() && dataLines.isNotEmpty()) break
                if (trimmed.startsWith("data:")) {
                    dataLines.add(trimmed.removePrefix("data:").trim())
                    val joined = dataLines.joinToString("\n").trim()
                    if (looksCompleteJson(joined)) break
                }
                if (lineCount > 1000) {
                    throw IllegalStateException("MCP SSE response exceeded 1000 lines")
                }
            }
        }
        return if (dataLines.isEmpty()) "" else dataLines.joinToString("\n")
    }

    private fun looksCompleteJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun extractJsonPayload(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("event:") && !trimmed.startsWith("data:")) return trimmed
        val dataLines = trimmed
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .toList()
        return dataLines.joinToString("\n").trim()
    }
}
