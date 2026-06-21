package com.mobileagent.host

import org.json.JSONObject

object NativeWorkspaceToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "workspace_info",
                    description = "Return the Android APP private workspace root and basic status.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "list_files",
                    description = "List files under the Android APP private workspace.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("path" to NativeToolSchema.stringProp("."), "max_entries" to NativeToolSchema.intProp(100))
                ),
        NativeToolDescriptor(
                    name = "read_file",
                    description = "Read a UTF-8 text file under the Android APP private workspace.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("path" to NativeToolSchema.stringProp(), "max_bytes" to NativeToolSchema.intProp(20000)),
                    required = NativeToolSchema.req("path")
                ),
        NativeToolDescriptor(
                    name = "write_file",
                    description = "Write a UTF-8 text file under the Android APP private workspace. Successful writes create a recoverable workspace change record with before/after backups.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props("path" to NativeToolSchema.stringProp(), "content" to NativeToolSchema.stringProp(), "overwrite" to NativeToolSchema.boolProp(false)),
                    required = NativeToolSchema.req("path", "content")
                ),
        NativeToolDescriptor(
                    name = "workspace_history",
                    description = "List recent workspace write/restore change records. Use this before restore or when auditing file modifications.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("path" to NativeToolSchema.stringProp(""), "limit" to NativeToolSchema.intProp(50))
                ),
        NativeToolDescriptor(
                    name = "workspace_restore",
                    description = "Restore a workspace file to the state before a recorded change id. This creates a new restore change record.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props("change_id" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("change_id")
                ),
        NativeToolDescriptor(
                    name = "search_files",
                    description = "Search UTF-8 text files under the Android APP private workspace.",
                    category = "workspace",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(), "path" to NativeToolSchema.stringProp("."), "max_matches" to NativeToolSchema.intProp(50), "max_bytes_per_file" to NativeToolSchema.intProp(200000)),
                    required = NativeToolSchema.req("query")
                )
    )
}
