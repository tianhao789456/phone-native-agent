package com.mobileagent.host

import org.json.JSONObject

class NativeDiagnosticsToolDispatcher(
    private val toolsetRequest: (String?, JSONObject) -> JSONObject,
    private val toolRegistry: (JSONObject, String?) -> JSONObject,
    private val toolInfo: (JSONObject) -> JSONObject,
    private val docsIndex: () -> JSONObject,
    private val docsRead: (JSONObject) -> JSONObject,
    private val docsSearch: (JSONObject) -> JSONObject,
    private val docsSync: () -> JSONObject,
    private val systemLogs: (JSONObject) -> JSONObject,
    private val selfHealthCheck: () -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject, sessionId: String?): JSONObject {
        return when (name) {
            "toolset_request" -> toolsetRequest(sessionId, arguments)
            "tool_registry" -> toolRegistry(arguments, sessionId)
            "tool_info" -> toolInfo(arguments)
            "docs_index" -> docsIndex()
            "docs_read" -> docsRead(arguments)
            "docs_search" -> docsSearch(arguments)
            "docs_sync" -> docsSync()
            "system_logs" -> systemLogs(arguments)
            "self_health_check" -> selfHealthCheck()
            else -> throw IllegalArgumentException("Diagnostics dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "toolset_request",
            "tool_registry",
            "tool_info",
            "docs_index",
            "docs_read",
            "docs_search",
            "docs_sync",
            "system_logs",
            "self_health_check"
        )
    }
}
