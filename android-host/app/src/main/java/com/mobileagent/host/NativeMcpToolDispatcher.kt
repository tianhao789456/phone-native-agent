package com.mobileagent.host

import org.json.JSONObject

class NativeMcpToolDispatcher(
    private val servers: () -> JSONObject,
    private val status: (String) -> JSONObject,
    private val tools: (String, Boolean, String) -> JSONObject,
    private val toolInfo: (String, String) -> JSONObject,
    private val call: (String, JSONObject, Int, String) -> JSONObject,
    private val configure: (JSONObject) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "mcp_servers" -> servers()
            "mcp_status" -> status(arguments.optString("server", ""))
            "mcp_tools" -> tools(
                arguments.optString("search", ""),
                arguments.optBoolean("include_schema", false),
                arguments.optString("server", "")
            )
            "mcp_tool_info" -> toolInfo(arguments.optString("tool"), arguments.optString("server", ""))
            "mcp_call" -> call(
                arguments.optString("tool"),
                arguments.optJSONObject("arguments") ?: JSONObject(),
                arguments.optInt("timeout_ms", 60000).coerceIn(1_000, 300_000),
                arguments.optString("server", "")
            )
            "mcp_configure" -> configure(arguments)
            else -> throw IllegalArgumentException("MCP dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "mcp_servers",
            "mcp_status",
            "mcp_tools",
            "mcp_tool_info",
            "mcp_call",
            "mcp_configure"
        )
    }
}
