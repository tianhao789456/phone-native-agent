package com.mobileagent.host

import org.json.JSONObject

object NativeDiagnosticsToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "tool_registry",
                    description = "Return a compact index of registered native tools with category, access mode, risk level, and auto-recovery flag. This is intentionally schema-light for progressive loading; use tool_info for one exact tool schema.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "category" to NativeToolSchema.stringProp(""),
                        "search" to NativeToolSchema.stringProp(""),
                        "include_schema" to NativeToolSchema.boolProp(false)
                    )
                ),
        NativeToolDescriptor(
                    name = "tool_info",
                    description = "Return full metadata and JSON schema for one registered native tool. Use this after tool_registry when you need exact arguments for a specific tool.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("name" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("name")
                ),
        NativeToolDescriptor(
                    name = "toolset_request",
                    description = "Control the active toolset for this chat session. Use this when you need tools beyond baseline: request additional groups (planning, workspace, phone, web, terminal, mcp, ssh, plugins, memory, recovery, diagnostics) or explicit tool names before using them.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "mode" to NativeToolSchema.stringProp("set"),
                        "groups" to NativeToolSchema.arrayStringProp(),
                        "tools" to NativeToolSchema.arrayStringProp(),
                        "replace" to NativeToolSchema.boolProp(true),
                        "note" to NativeToolSchema.stringProp("")
                    )
                ),
        NativeToolDescriptor(
                    name = "docs_index",
                    description = "List built-in official Mobile Agent documents. Use this when the user or agent needs to discover available commands, tools, permission modes, terminal setup, or troubleshooting docs.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "docs_read",
                    description = "Read one built-in official Mobile Agent document by path, such as docs/official/commands.md or docs/official/tools.md.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("path" to NativeToolSchema.stringProp(), "max_bytes" to NativeToolSchema.intProp(40000)),
                    required = NativeToolSchema.req("path")
                ),
        NativeToolDescriptor(
                    name = "docs_search",
                    description = "Search built-in official Mobile Agent documents by keyword.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(), "max_matches" to NativeToolSchema.intProp(30)),
                    required = NativeToolSchema.req("query")
                ),
        NativeToolDescriptor(
                    name = "docs_sync",
                    description = "Synchronize built-in official Mobile Agent documents into the APP private workspace under docs/official/ so users and tools can read them as files.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "system_logs",
                    description = "Return recent Android core, API, bridge, tool, and recovery logs for diagnosis. Use this when a tool, API call, bridge, or terminal backend fails.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("limit" to NativeToolSchema.intProp(80), "min_level" to NativeToolSchema.stringProp("debug"))
                ),
        NativeToolDescriptor(
                    name = "self_health_check",
                    description = "Run a local self-check for the Android native core, API key, permission mode, Accessibility service, workspace, and optional Termux terminal backend. Use this before repairing broken tools.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "diagnose_terminal",
                    description = "Diagnose why terminal tools are unavailable or failing. Classifies config, permission mode, endpoint reachability, backend health, and next repair actions.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    autoRecover = false
                ),
        NativeToolDescriptor(
                    name = "pc_bridge_status",
                    description = "Return a combined phone-to-PC bridge status: SSH configuration/runtime, optional SSH reachability diagnosis, and MCP runtime status. Use this before desktop work to decide whether to use SSH, MCP, or both.",
                    category = "diagnostics",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "diagnose_ssh" to NativeToolSchema.boolProp(true),
                        "ssh_host" to NativeToolSchema.stringProp(""),
                        "ssh_port" to NativeToolSchema.intProp(22),
                        "timeout_ms" to NativeToolSchema.intProp(6000)
                    )
                )
    )
}
