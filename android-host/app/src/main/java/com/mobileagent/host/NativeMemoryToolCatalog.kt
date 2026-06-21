package com.mobileagent.host

import org.json.JSONObject

object NativeMemoryToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "memory_query",
                    description = "Check whether durable user memory can answer a user-specific question without operating the phone or remote computer.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("question" to NativeToolSchema.stringProp(), "limit" to NativeToolSchema.intProp(5)),
                    required = NativeToolSchema.req("question")
                ),
        NativeToolDescriptor(
                    name = "memory_search",
                    description = "Search durable user memory, preferences, environment facts, do-not-do rules, insights, and task history.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(), "limit" to NativeToolSchema.intProp(8)),
                    required = NativeToolSchema.req("query")
                ),
        NativeToolDescriptor(
                    name = "memory_summary",
                    description = "Return a UI-friendly durable memory summary with user profile, experience groups, procedures, and active learning state.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(""), "limit" to NativeToolSchema.intProp(80))
                ),
        NativeToolDescriptor(
                    name = "memory_write",
                    description = "Write or reinforce a durable user preference, environment fact, do-not-do rule, or insight. Do not store secrets verbatim.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "type" to NativeToolSchema.stringProp("preference"),
                        "key" to NativeToolSchema.stringProp(""),
                        "value" to NativeToolSchema.stringProp(),
                        "confidence" to NativeToolSchema.stringProp("medium"),
                        "source" to NativeToolSchema.stringProp("agent")
                    ),
                    required = NativeToolSchema.req("value")
                ),
        NativeToolDescriptor(
                    name = "experience_search",
                    description = "Search reusable execution lessons by app/package/tool_scope/task_type/query. Use before repeated phone, desktop/MCP, or terminal tasks.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "query" to NativeToolSchema.stringProp(""),
                        "app" to NativeToolSchema.stringProp(""),
                        "package" to NativeToolSchema.stringProp(""),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "scope" to NativeToolSchema.stringProp(""),
                        "task_type" to NativeToolSchema.stringProp(""),
                        "limit" to NativeToolSchema.intProp(8)
                    )
                ),
        NativeToolDescriptor(
                    name = "experience_record",
                    description = "Record or reinforce a reusable success/failure/UI/timing/environment lesson from a completed task.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "app" to NativeToolSchema.stringProp("general"),
                        "package" to NativeToolSchema.stringProp(""),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "scope" to NativeToolSchema.stringProp(""),
                        "task_type" to NativeToolSchema.stringProp(""),
                        "lesson_type" to NativeToolSchema.stringProp("general"),
                        "description" to NativeToolSchema.stringProp(),
                        "source_task" to NativeToolSchema.stringProp(""),
                        "confidence" to NativeToolSchema.stringProp("medium")
                    ),
                    required = NativeToolSchema.req("description")
                ),
        NativeToolDescriptor(
                    name = "experience_update",
                    description = "Update one reusable experience lesson by id, usually confidence, lesson_type, or description.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "id" to NativeToolSchema.intProp(),
                        "confidence" to NativeToolSchema.stringProp("medium"),
                        "lesson_type" to NativeToolSchema.stringProp(""),
                        "description" to NativeToolSchema.stringProp("")
                    ),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "experience_delete",
                    description = "Delete one reusable experience lesson by id.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.intProp()),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "experience_compact",
                    description = "Compact lessons for an app or tool_scope by keeping the strongest reusable lessons and dropping low-value duplicates.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "app" to NativeToolSchema.stringProp(""),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "scope" to NativeToolSchema.stringProp(""),
                        "target" to NativeToolSchema.intProp(8)
                    )
                ),
        NativeToolDescriptor(
                    name = "procedure_search",
                    description = "Search generated Markdown procedures by app/tool_scope/query. Use before repeated phone, Windows MCP, terminal, or app workflows.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "query" to NativeToolSchema.stringProp(""),
                        "app" to NativeToolSchema.stringProp(""),
                        "package" to NativeToolSchema.stringProp(""),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "scope" to NativeToolSchema.stringProp(""),
                        "limit" to NativeToolSchema.intProp(5)
                    )
                ),
        NativeToolDescriptor(
                    name = "procedure_generate",
                    description = "Generate or update a Markdown procedure from matching ExperienceLog lessons for an app/tool_scope. Windows MCP writes memory/procedures/windows_mcp.md.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "app" to NativeToolSchema.stringProp("general"),
                        "package" to NativeToolSchema.stringProp(""),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "scope" to NativeToolSchema.stringProp("")
                    )
                ),
        NativeToolDescriptor(
                    name = "procedure_read",
                    description = "Read one generated Markdown procedure by path or app/tool_scope.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "path" to NativeToolSchema.stringProp(""),
                        "app" to NativeToolSchema.stringProp("general"),
                        "tool_scope" to NativeToolSchema.stringProp(""),
                        "max_bytes" to NativeToolSchema.intProp(40000)
                    )
                ),
        NativeToolDescriptor(
                    name = "procedure_list",
                    description = "List generated Markdown procedures stored under memory/procedures.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("limit" to NativeToolSchema.intProp(50))
                ),
        NativeToolDescriptor(
                    name = "learning_start",
                    description = "Start lightweight Learning Mode and create a demo trace under memory/demos. It records available app/screen/action summaries, not screenshots.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "label" to NativeToolSchema.stringProp("manual-demo"),
                        "app" to NativeToolSchema.stringProp("unknown"),
                        "tool_scope" to NativeToolSchema.stringProp("phone"),
                        "description" to NativeToolSchema.stringProp("")
                    )
                ),
        NativeToolDescriptor(
                    name = "learning_record",
                    description = "Append one event to the active lightweight Learning Mode trace.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "event_type" to NativeToolSchema.stringProp("note"),
                        "app" to NativeToolSchema.stringProp(""),
                        "summary" to NativeToolSchema.stringProp(""),
                        "screen_summary" to NativeToolSchema.stringProp(""),
                        "details" to JSONObject().put("type", "object")
                    )
                ),
        NativeToolDescriptor(
                    name = "learning_stop",
                    description = "Stop lightweight Learning Mode, write a demo summary, and extract at least one experience/procedure from the trace.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("app" to NativeToolSchema.stringProp(""), "tool_scope" to NativeToolSchema.stringProp(""))
                ),
        NativeToolDescriptor(
                    name = "learning_status",
                    description = "Return whether lightweight Learning Mode is active and where its trace is stored.",
                    category = "memory",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                )
    )
}
