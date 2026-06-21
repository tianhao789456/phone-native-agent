package com.mobileagent.host

import org.json.JSONObject

object NativeMcpToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "mcp_servers",
                    description = "List configured MCP server profiles. Use this before mcp_status/mcp_tools when multiple computer MCP servers may exist.",
                    category = "mcp",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    "mcp_status",
                    "Check one configured remote MCP server, auth presence, and runtime connectivity. Use server='default' unless the user selected another computer MCP server.",
                    "mcp",
                    NativeToolAccess.READ_ONLY,
                    NativeToolRisk.LOW,
                    NativeToolSchema.props("server" to NativeToolSchema.stringProp("default"))
                ),
        NativeToolDescriptor(
                    name = "mcp_tools",
                    description = "List available remote MCP tools from the configured MCP server as a compact index by default. Call this after mcp_status; set include_schema=true only for a narrow search or use mcp_tool_info before mcp_call.",
                    category = "mcp",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "server" to NativeToolSchema.stringProp("default"),
                        "search" to NativeToolSchema.stringProp(""),
                        "include_schema" to NativeToolSchema.boolProp(false)
                    ),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "mcp_tool_info",
                    description = "Return full schema and metadata for one remote MCP tool by exact name from the active MCP server. Use this after mcp_tools and before mcp_call when arguments are unclear.",
                    category = "mcp",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("tool" to NativeToolSchema.stringProp(), "server" to NativeToolSchema.stringProp("default")),
                    required = NativeToolSchema.req("tool"),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "mcp_call",
                    description = "Call a remote MCP tool by exact name with arguments discovered from mcp_tools. Use this for Windows/desktop operations such as PowerShell, FileSystem, Snapshot, Screenshot, Click, Type, and Scroll. Verify important actions with another read or observation.",
                    category = "mcp",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.HIGH,
                    properties = NativeToolSchema.props(
                        "server" to NativeToolSchema.stringProp("default"),
                        "tool" to NativeToolSchema.stringProp(),
                        "arguments" to JSONObject().put("type", "object"),
                        "timeout_ms" to NativeToolSchema.intProp(60000)
                    ),
                    required = NativeToolSchema.req("tool"),
                    autoRecover = true
                ),
        NativeToolDescriptor(
                    name = "mcp_configure",
                    description = "Update the configured remote MCP endpoint and auth token from inside the agent loop, then optionally verify the connection. Use this when Windows MCP token/URL changed and the phone app still has stale MCP config.",
                    category = "mcp",
                    access = NativeToolAccess.TERMINAL_DELEGATION,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "enabled" to NativeToolSchema.boolProp(true),
                        "id" to NativeToolSchema.stringProp("default"),
                        "server" to NativeToolSchema.stringProp("default"),
                        "name" to NativeToolSchema.stringProp(""),
                        "type" to NativeToolSchema.stringProp("desktop"),
                        "base_url" to NativeToolSchema.stringProp(""),
                        "endpoint" to NativeToolSchema.stringProp(""),
                        "auth_token" to NativeToolSchema.stringProp(""),
                        "token" to NativeToolSchema.stringProp(""),
                        "verify" to NativeToolSchema.boolProp(true),
                        "set_active" to NativeToolSchema.boolProp(true)
                    ),
                    autoRecover = true
                )
    )
}
