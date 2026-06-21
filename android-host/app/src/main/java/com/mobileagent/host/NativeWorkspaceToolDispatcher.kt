package com.mobileagent.host

import org.json.JSONObject

class NativeWorkspaceToolDispatcher(
    private val workspace: MobileWorkspace
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "workspace_info" -> workspace.info()
            "list_files" -> workspace.list(
                arguments.optString("path", "."),
                arguments.optInt("max_entries", 100)
            )
            "read_file" -> workspace.read(
                arguments.optString("path"),
                arguments.optInt("max_bytes", 20000)
            )
            "write_file" -> workspace.write(
                arguments.optString("path"),
                arguments.optString("content"),
                arguments.optBoolean("overwrite", false)
            )
            "workspace_history" -> workspace.history(
                arguments.optString("path", ""),
                arguments.optInt("limit", 50)
            )
            "workspace_restore" -> workspace.restore(arguments.optString("change_id"))
            "search_files" -> workspace.search(
                arguments.optString("query"),
                arguments.optString("path", "."),
                arguments.optInt("max_matches", 50),
                arguments.optInt("max_bytes_per_file", 200000)
            )
            else -> throw IllegalArgumentException("Workspace dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "workspace_info",
            "list_files",
            "read_file",
            "write_file",
            "workspace_history",
            "workspace_restore",
            "search_files"
        )
    }
}
