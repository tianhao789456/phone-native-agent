package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

enum class NativeToolAccess {
    READ_ONLY,
    SCREEN_ACTION,
    TERMINAL_DELEGATION
}

enum class NativeToolRisk {
    LOW,
    MEDIUM,
    HIGH
}

data class NativeToolDescriptor(
    val name: String,
    val description: String,
    val category: String,
    val access: NativeToolAccess,
    val risk: NativeToolRisk,
    val properties: JSONObject = JSONObject(),
    val required: JSONArray = JSONArray(),
    val autoRecover: Boolean = false,
    val debugHint: String = ""
) {
    fun schema(): JSONObject {
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                            .put("additionalProperties", false)
                    )
            )
    }

    fun metadata(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("access", access.name.lowercase())
            .put("risk", risk.name.lowercase())
            .put("auto_recover", autoRecover)
            .put("debug_hint", debugHint)
            .put("schema", schema().optJSONObject("function")?.optJSONObject("parameters") ?: JSONObject())
    }
}

object NativeToolRegistry {
    private fun stringProp(default: String? = null): JSONObject {
        val value = JSONObject().put("type", "string")
        if (default != null) value.put("default", default)
        return value
    }

    private fun intProp(default: Int? = null): JSONObject {
        val value = JSONObject().put("type", "integer")
        if (default != null) value.put("default", default)
        return value
    }

    private fun boolProp(default: Boolean? = null): JSONObject {
        val value = JSONObject().put("type", "boolean")
        if (default != null) value.put("default", default)
        return value
    }

    private fun objectStringMapProp(): JSONObject {
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", JSONObject().put("type", "string"))
    }

    private fun arrayProp(): JSONObject {
        return JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))
    }

    private fun arrayStringProp(): JSONObject {
        return JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
    }

    private fun props(vararg pairs: Pair<String, JSONObject>): JSONObject {
        val value = JSONObject()
        pairs.forEach { value.put(it.first, it.second) }
        return value
    }

    private fun req(vararg names: String): JSONArray {
        val value = JSONArray()
        names.forEach { value.put(it) }
        return value
    }

    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
            name = "get_time",
            description = "Return current local time and timezone.",
            category = "system",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW
        ),
        NativeToolDescriptor(
            name = "task_plan_update",
            description = "Create or update the current managed task plan. Use this for multi-step tasks to record goal, step status, evidence, and recovery state before and during tool work.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "goal" to stringProp(""),
                "status" to stringProp("in_progress"),
                "steps" to arrayProp(),
                "step_id" to stringProp(""),
                "step_status" to stringProp(""),
                "note" to stringProp(""),
                "evidence" to stringProp("")
            )
        ),
        NativeToolDescriptor(
            name = "task_plan_status",
            description = "Return the current managed task plan with step statuses and evidence.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW
        ),
        NativeToolDescriptor(
            name = "task_create",
            description = "Create a persistent APP workspace task directory with task metadata, plan file, artifacts folder, and logs folder.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("title" to stringProp(), "goal" to stringProp()),
            required = req("title")
        ),
        NativeToolDescriptor(
            name = "task_list",
            description = "List persistent APP workspace task directories created by task_create.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("limit" to intProp(50))
        ),
        NativeToolDescriptor(
            name = "task_update",
            description = "Update persistent task metadata such as status and note.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "status" to stringProp("in_progress"), "note" to stringProp("")),
            required = req("task")
        ),
        NativeToolDescriptor(
            name = "task_log_append",
            description = "Append a timestamped log entry under a persistent task logs folder.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "name" to stringProp("task.log"), "content" to stringProp()),
            required = req("task", "content")
        ),
        NativeToolDescriptor(
            name = "task_artifact_write",
            description = "Write a text artifact under a persistent task artifacts folder.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "name" to stringProp(), "content" to stringProp(), "overwrite" to boolProp(false)),
            required = req("task", "name", "content")
        ),
        NativeToolDescriptor(
            name = "task_reports",
            description = "List saved task-loop reports for a persistent task directory.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "limit" to intProp(50)),
            required = req("task")
        ),
        NativeToolDescriptor(
            name = "task_report_read",
            description = "Read a saved task-loop report from a persistent task directory. Accepts report paths returned by task_reports or task_loop persistence.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "report" to stringProp(), "max_bytes" to intProp(1_000_000)),
            required = req("task", "report")
        ),
        NativeToolDescriptor(
            name = "task_report_summarize",
            description = "Generate human-readable Markdown artifacts from a saved task-loop JSON report.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("task" to stringProp(), "report" to stringProp()),
            required = req("task", "report")
        ),
        NativeToolDescriptor(
            name = "task_failure_latest",
            description = "Return the newest persistent task failure-analysis.md artifact with task metadata and content. Use this after failed tool loops to inspect what broke and what to retry.",
            category = "planning",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("max_bytes" to intProp(20000))
        ),
        NativeToolDescriptor(
            name = "tool_registry",
            description = "Return registered native tools with category, access mode, risk level, schema, auto-recovery flag, and debug hints. Use this to inspect available capabilities before choosing tools.",
            category = "diagnostics",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW
        ),
        NativeToolDescriptor(
            name = "toolset_request",
            description = "Control the active toolset for this chat session. Use this when you need tools beyond baseline: request additional groups (planning, workspace, phone, web, terminal, mcp, plugins, recovery, diagnostics) or explicit tool names before using them.",
            category = "diagnostics",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "mode" to stringProp("set"),
                "groups" to arrayStringProp(),
                "tools" to arrayStringProp(),
                "replace" to boolProp(true),
                "note" to stringProp("")
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
            properties = props("path" to stringProp(), "max_bytes" to intProp(40000)),
            required = req("path")
        ),
        NativeToolDescriptor(
            name = "docs_search",
            description = "Search built-in official Mobile Agent documents by keyword.",
            category = "diagnostics",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(), "max_matches" to intProp(30)),
            required = req("query")
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
            properties = props("limit" to intProp(80), "min_level" to stringProp("debug"))
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
            autoRecover = true
        ),
        NativeToolDescriptor(
            name = "web_search",
            description = "Search the public web directly from the Android native core without Termux. Use for current public information and return source titles, URLs, and snippets.",
            category = "web",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(), "max_results" to intProp(5)),
            required = req("query")
        ),
        NativeToolDescriptor(
            name = "web_extract",
            description = "Fetch one public HTTP/HTTPS page and extract LLM-friendly structured content. Uses native direct extraction first, Jina Reader fallback when allowed, and optional Termux fallback in developer/danger mode.",
            category = "web",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "url" to stringProp(),
                "mode" to stringProp("markdown"),
                "source" to stringProp("auto"),
                "max_bytes" to intProp(200000),
                "use_jina" to boolProp(true),
                "use_termux" to boolProp(false)
            ),
            required = req("url")
        ),
        NativeToolDescriptor(
            name = "page_extract",
            description = "Alias of web_extract. Fetch one public HTTP/HTTPS page and return title, text, markdown, links, source, byte count, and truncation state.",
            category = "web",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "url" to stringProp(),
                "mode" to stringProp("markdown"),
                "source" to stringProp("auto"),
                "max_bytes" to intProp(200000),
                "use_jina" to boolProp(true),
                "use_termux" to boolProp(false)
            ),
            required = req("url")
        ),
        NativeToolDescriptor(
            name = "http_get",
            description = "Fetch a public HTTP/HTTPS URL directly from the Android native core without Termux. Returns status, headers, body preview, byte count, and truncation state.",
            category = "web",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "url" to stringProp(),
                "headers" to objectStringMapProp(),
                "max_bytes" to intProp(200000)
            ),
            required = req("url")
        ),
        NativeToolDescriptor(
            name = "http_post",
            description = "POST text or JSON to a HTTP/HTTPS endpoint directly from the Android native core without Termux. Use only for user-requested API calls.",
            category = "web",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props(
                "url" to stringProp(),
                "body" to stringProp(""),
                "content_type" to stringProp("application/json; charset=utf-8"),
                "headers" to objectStringMapProp(),
                "max_bytes" to intProp(200000)
            ),
            required = req("url")
        ),
        NativeToolDescriptor(
            name = "recover_terminal_backend",
            description = "Try to recover the optional Termux terminal backend. It enables the local terminal endpoint, optionally opens Termux, and tries Termux RUN_COMMAND to start scripts/start-http-termux.sh. Requires danger mode and user confirmation.",
            category = "recovery",
            access = NativeToolAccess.TERMINAL_DELEGATION,
            risk = NativeToolRisk.HIGH,
            properties = props("use_run_command" to boolProp(true), "open_termux" to boolProp(true), "wait_ms" to intProp(2500)),
            autoRecover = true
        ),
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
            properties = props("path" to stringProp("."), "max_entries" to intProp(100))
        ),
        NativeToolDescriptor(
            name = "read_file",
            description = "Read a UTF-8 text file under the Android APP private workspace.",
            category = "workspace",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("path" to stringProp(), "max_bytes" to intProp(20000)),
            required = req("path")
        ),
        NativeToolDescriptor(
            name = "write_file",
            description = "Write a UTF-8 text file under the Android APP private workspace. Successful writes create a recoverable workspace change record with before/after backups.",
            category = "workspace",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props("path" to stringProp(), "content" to stringProp(), "overwrite" to boolProp(false)),
            required = req("path", "content")
        ),
        NativeToolDescriptor(
            name = "workspace_history",
            description = "List recent workspace write/restore change records. Use this before restore or when auditing file modifications.",
            category = "workspace",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("path" to stringProp(""), "limit" to intProp(50))
        ),
        NativeToolDescriptor(
            name = "workspace_restore",
            description = "Restore a workspace file to the state before a recorded change id. This creates a new restore change record.",
            category = "workspace",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props("change_id" to stringProp()),
            required = req("change_id")
        ),
        NativeToolDescriptor(
            name = "plugin_info",
            description = "Return the Android APP native plugin workspace root and plugin counts. This is the foundation for phone-local self-extension.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW
        ),
        NativeToolDescriptor(
            name = "plugin_list",
            description = "List plugin manifests from the Android APP native plugin workspace. Disabled plugins are included by default.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("include_disabled" to boolProp(true))
        ),
        NativeToolDescriptor(
            name = "plugin_create",
            description = "Create or update a plugin manifest in the Android APP native plugin workspace. Use this to stage new tools, skills, or workflows without editing the main APP source.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props(
                "manifest" to JSONObject().put("type", "object"),
                "overwrite" to boolProp(false)
            ),
            required = req("manifest")
        ),
        NativeToolDescriptor(
            name = "plugin_read",
            description = "Read one plugin manifest by id from the Android APP native plugin workspace.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to stringProp()),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "plugin_reports",
            description = "List saved validation, test, and workflow run reports for one plugin.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to stringProp(), "limit" to intProp(50)),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "plugin_report_read",
            description = "Read one saved plugin report from the plugin reports folder. Accepts paths returned by plugin_validate, plugin_test, plugin_run, or plugin_reports.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to stringProp(), "report" to stringProp(), "max_bytes" to intProp(200000)),
            required = req("id", "report")
        ),
        NativeToolDescriptor(
            name = "plugin_validate",
            description = "Validate one plugin manifest and save a validation report in the plugin reports folder. This checks ids, required fields, arrays, and duplicate tool/workflow names without executing plugin code.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to stringProp()),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "plugin_test",
            description = "Run non-executing plugin checks and save a test report in the plugin reports folder. This is the first safe test layer before dynamic plugin execution exists.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to stringProp()),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "plugin_run",
            description = "Run a plugin workflow through the safe adapter. In normal modes workflow steps can call existing read-only native tools. In developer mode, screen actions and terminal delegation may run through the normal permission gate; recursive plugin calls and unknown tools remain blocked.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props("id" to stringProp(), "workflow" to stringProp(), "max_steps" to intProp(20)),
            required = req("id", "workflow")
        ),
        NativeToolDescriptor(
            name = "plugin_set_enabled",
            description = "Enable or disable one plugin manifest. This lets the agent quarantine a bad plugin without deleting it.",
            category = "plugins",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = props("id" to stringProp(), "enabled" to boolProp(true)),
            required = req("id", "enabled")
        ),
        NativeToolDescriptor(
            name = "search_files",
            description = "Search UTF-8 text files under the Android APP private workspace.",
            category = "workspace",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(), "path" to stringProp("."), "max_matches" to intProp(50), "max_bytes_per_file" to intProp(200000)),
            required = req("query")
        ),
        NativeToolDescriptor("host_status", "Return Android Host App and Accessibility state.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW),
        NativeToolDescriptor("host_observe", "Observe the current Android foreground app and compact screen node list together.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, props("max_nodes" to intProp(40))),
        NativeToolDescriptor("host_screen_dump", "Return a compact Accessibility screen node list.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, props("max_nodes" to intProp(40))),
        NativeToolDescriptor(
            name = "host_screen_find",
            description = "Find screen nodes by visible text, content description, view id, or class name.",
            category = "phone",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(), "contains" to boolProp(true), "max_nodes" to intProp(20)),
            required = req("query")
        ),
        NativeToolDescriptor("host_current_app", "Return the package name and root node summary for the current foreground app.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW),
        NativeToolDescriptor("host_open_app", "Open an installed Android app by package name.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("package" to stringProp()), req("package")),
        NativeToolDescriptor("host_click_text", "Click visible text or content description through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("text" to stringProp(), "contains" to boolProp(true)), req("text")),
        NativeToolDescriptor("host_click_view_id", "Click an Android view resource id through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("view_id" to stringProp()), req("view_id")),
        NativeToolDescriptor("host_click_index", "Click a node by index from host_screen_dump through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("index" to intProp()), req("index")),
        NativeToolDescriptor("host_long_press_text", "Long-press visible text or content description through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("text" to stringProp(), "contains" to boolProp(true)), req("text")),
        NativeToolDescriptor("host_long_press_index", "Long-press a node by index from host_screen_dump through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("index" to intProp()), req("index")),
        NativeToolDescriptor("host_input_text", "Set text in the focused or first editable field through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("text" to stringProp()), req("text")),
        NativeToolDescriptor("host_clear_text", "Clear the focused or first editable field through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM),
        NativeToolDescriptor("host_back", "Perform Android Back through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM),
        NativeToolDescriptor("host_home", "Perform Android Home through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM),
        NativeToolDescriptor("host_press_key", "Perform a supported native global key action through Accessibility. Supports back, home, recents, notifications, quick_settings, and power_dialog.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("key" to stringProp()), req("key")),
        NativeToolDescriptor("host_scroll", "Scroll the current page through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("direction" to stringProp("forward"), "text" to stringProp(""), "view_id" to stringProp(""))),
        NativeToolDescriptor("host_swipe_coords", "Swipe from one screen coordinate to another through an Accessibility gesture.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("x1" to intProp(), "y1" to intProp(), "x2" to intProp(), "y2" to intProp(), "duration_ms" to intProp(450)), req("x1", "y1", "x2", "y2")),
        NativeToolDescriptor("host_wait_ms", "Wait for a fixed number of milliseconds, then observe the current screen.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, props("ms" to intProp(1000))),
        NativeToolDescriptor("host_wait_for_text", "Wait until visible text or content description appears on screen, polling Accessibility until timeout.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, props("text" to stringProp(), "timeout_ms" to intProp(5000), "contains" to boolProp(true)), req("text")),
        NativeToolDescriptor("host_open_url", "Open a URL with Android ACTION_VIEW. This launches the user's browser or matching app.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, props("url" to stringProp()), req("url")),
        NativeToolDescriptor(
            "mcp_status",
            "Check the configured remote MCP endpoint, auth presence, and runtime connectivity. Use this first when the user asks to control or inspect a Windows PC, desktop, remote computer, or MCP server.",
            "mcp",
            NativeToolAccess.READ_ONLY,
            NativeToolRisk.LOW
        ),
        NativeToolDescriptor(
            name = "mcp_tools",
            description = "List available remote MCP tools from the configured MCP server, including discoverable tool names, schemas, and raw metadata. Call this after mcp_status and before mcp_call so you can use the exact remote tool name and arguments.",
            category = "mcp",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("search" to stringProp("")),
            autoRecover = true
        ),
        NativeToolDescriptor(
            name = "mcp_call",
            description = "Call a remote MCP tool by exact name with arguments discovered from mcp_tools. Use this for Windows/desktop operations such as PowerShell, FileSystem, Snapshot, Screenshot, Click, Type, and Scroll. Verify important actions with another read or observation.",
            category = "mcp",
            access = NativeToolAccess.TERMINAL_DELEGATION,
            risk = NativeToolRisk.HIGH,
            properties = props(
                "tool" to stringProp(),
                "arguments" to JSONObject().put("type", "object"),
                "timeout_ms" to intProp(60000)
            ),
            required = req("tool"),
            autoRecover = false
        ),
        NativeToolDescriptor(
            name = "memory_query",
            description = "Check whether durable user memory can answer a user-specific question without operating the phone or remote computer.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("question" to stringProp(), "limit" to intProp(5)),
            required = req("question")
        ),
        NativeToolDescriptor(
            name = "memory_search",
            description = "Search durable user memory, preferences, environment facts, do-not-do rules, insights, and task history.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(), "limit" to intProp(8)),
            required = req("query")
        ),
        NativeToolDescriptor(
            name = "memory_summary",
            description = "Return a UI-friendly durable memory summary with user profile, experience groups, procedures, and active learning state.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("query" to stringProp(""), "limit" to intProp(80))
        ),
        NativeToolDescriptor(
            name = "memory_write",
            description = "Write or reinforce a durable user preference, environment fact, do-not-do rule, or insight. Do not store secrets verbatim.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "type" to stringProp("preference"),
                "key" to stringProp(""),
                "value" to stringProp(),
                "confidence" to stringProp("medium"),
                "source" to stringProp("agent")
            ),
            required = req("value")
        ),
        NativeToolDescriptor(
            name = "experience_search",
            description = "Search reusable execution lessons by app/package/tool_scope/task_type/query. Use before repeated phone, desktop/MCP, or terminal tasks.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "query" to stringProp(""),
                "app" to stringProp(""),
                "package" to stringProp(""),
                "tool_scope" to stringProp(""),
                "scope" to stringProp(""),
                "task_type" to stringProp(""),
                "limit" to intProp(8)
            )
        ),
        NativeToolDescriptor(
            name = "experience_record",
            description = "Record or reinforce a reusable success/failure/UI/timing/environment lesson from a completed task.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "app" to stringProp("general"),
                "package" to stringProp(""),
                "tool_scope" to stringProp(""),
                "scope" to stringProp(""),
                "task_type" to stringProp(""),
                "lesson_type" to stringProp("general"),
                "description" to stringProp(),
                "source_task" to stringProp(""),
                "confidence" to stringProp("medium")
            ),
            required = req("description")
        ),
        NativeToolDescriptor(
            name = "experience_update",
            description = "Update one reusable experience lesson by id, usually confidence, lesson_type, or description.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "id" to intProp(),
                "confidence" to stringProp("medium"),
                "lesson_type" to stringProp(""),
                "description" to stringProp("")
            ),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "experience_delete",
            description = "Delete one reusable experience lesson by id.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("id" to intProp()),
            required = req("id")
        ),
        NativeToolDescriptor(
            name = "experience_compact",
            description = "Compact lessons for an app or tool_scope by keeping the strongest reusable lessons and dropping low-value duplicates.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "app" to stringProp(""),
                "tool_scope" to stringProp(""),
                "scope" to stringProp(""),
                "target" to intProp(8)
            )
        ),
        NativeToolDescriptor(
            name = "procedure_search",
            description = "Search generated Markdown procedures by app/tool_scope/query. Use before repeated phone, Windows MCP, terminal, or app workflows.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "query" to stringProp(""),
                "app" to stringProp(""),
                "package" to stringProp(""),
                "tool_scope" to stringProp(""),
                "scope" to stringProp(""),
                "limit" to intProp(5)
            )
        ),
        NativeToolDescriptor(
            name = "procedure_generate",
            description = "Generate or update a Markdown procedure from matching ExperienceLog lessons for an app/tool_scope. Windows MCP writes memory/procedures/windows_mcp.md.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "app" to stringProp("general"),
                "package" to stringProp(""),
                "tool_scope" to stringProp(""),
                "scope" to stringProp("")
            )
        ),
        NativeToolDescriptor(
            name = "procedure_read",
            description = "Read one generated Markdown procedure by path or app/tool_scope.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "path" to stringProp(""),
                "app" to stringProp("general"),
                "tool_scope" to stringProp(""),
                "max_bytes" to intProp(40000)
            )
        ),
        NativeToolDescriptor(
            name = "procedure_list",
            description = "List generated Markdown procedures stored under memory/procedures.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("limit" to intProp(50))
        ),
        NativeToolDescriptor(
            name = "learning_start",
            description = "Start lightweight Learning Mode and create a demo trace under memory/demos. It records available app/screen/action summaries, not screenshots.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "label" to stringProp("manual-demo"),
                "app" to stringProp("unknown"),
                "tool_scope" to stringProp("phone"),
                "description" to stringProp("")
            )
        ),
        NativeToolDescriptor(
            name = "learning_record",
            description = "Append one event to the active lightweight Learning Mode trace.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props(
                "event_type" to stringProp("note"),
                "app" to stringProp(""),
                "summary" to stringProp(""),
                "screen_summary" to stringProp(""),
                "details" to JSONObject().put("type", "object")
            )
        ),
        NativeToolDescriptor(
            name = "learning_stop",
            description = "Stop lightweight Learning Mode, write a demo summary, and extract at least one experience/procedure from the trace.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = props("app" to stringProp(""), "tool_scope" to stringProp(""))
        ),
        NativeToolDescriptor(
            name = "learning_status",
            description = "Return whether lightweight Learning Mode is active and where its trace is stored.",
            category = "memory",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW
        ),
        NativeToolDescriptor("termux_status", "Return whether the optional Termux terminal tool backend is configured and reachable.", "terminal", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, autoRecover = true),
        NativeToolDescriptor("termux_tools", "List tools exposed by the optional Termux terminal backend.", "terminal", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, autoRecover = true),
        NativeToolDescriptor("termux_chat", "Delegate a task to the optional Termux agent backend. Requires danger permission mode and user confirmation.", "terminal", NativeToolAccess.TERMINAL_DELEGATION, NativeToolRisk.HIGH, props("message" to stringProp()), req("message"), autoRecover = true),
        NativeToolDescriptor("terminal_run", "Run a shell command through the optional Termux terminal backend and return stdout, stderr, return code, cwd, and timeout status. Requires danger mode and user confirmation.", "terminal", NativeToolAccess.TERMINAL_DELEGATION, NativeToolRisk.HIGH, props("command" to stringProp(), "cwd" to stringProp(""), "timeout" to intProp(60)), req("command"), autoRecover = true),
        NativeToolDescriptor("terminal_script", "Write a temporary script into the Termux task workspace, run it, save full stdout/stderr/task metadata, and return folded output plus task id and artifact paths. Prefer this over terminal_run for multi-line scripts, tests, generated code, or tasks with large output. Requires danger mode and user confirmation.", "terminal", NativeToolAccess.TERMINAL_DELEGATION, NativeToolRisk.HIGH, props("script" to stringProp(), "interpreter" to stringProp("sh"), "cwd" to stringProp(""), "timeout" to intProp(60), "wait" to boolProp(true), "max_output_chars" to intProp(12000), "name" to stringProp("script")), req("script"), autoRecover = true),
        NativeToolDescriptor("terminal_task_status", "Read a saved or running Termux terminal task by task id. Use this after terminal_script with wait=false, or to inspect saved stdout/stderr artifacts. Requires danger mode and user confirmation.", "terminal", NativeToolAccess.TERMINAL_DELEGATION, NativeToolRisk.HIGH, props("task_id" to stringProp(), "max_output_chars" to intProp(12000)), req("task_id"), autoRecover = true),
        NativeToolDescriptor("terminal_task_cancel", "Cancel a running Termux terminal task by task id. Use this to interrupt long-running background scripts. Requires danger mode and user confirmation.", "terminal", NativeToolAccess.TERMINAL_DELEGATION, NativeToolRisk.HIGH, props("task_id" to stringProp()), req("task_id"), autoRecover = true)
    )

    private val byName = descriptors.associateBy { it.name }
    private val allToolNames = descriptors.map { it.name }.toSet()
    private val toolsByGroup = mapOf(
        "baseline" to linkedSetOf(
            "get_time",
            "toolset_request",
            "tool_registry",
            "task_plan_update",
            "task_plan_status",
            "task_create",
            "task_list",
            "task_update",
            "task_log_append",
            "task_artifact_write",
            "task_reports",
            "task_report_read",
            "task_report_summarize",
            "task_failure_latest",
            "docs_index",
            "docs_read",
            "docs_search",
            "docs_sync",
            "system_logs",
            "self_health_check",
            "mcp_status",
            "mcp_tools",
            "memory_query",
            "memory_search",
            "experience_search",
            "procedure_search"
        ),
        "planning" to descriptors.filter { it.category == "planning" }.mapTo(linkedSetOf()) { it.name },
        "diagnostics" to descriptors.filter { it.category == "diagnostics" }.mapTo(linkedSetOf()) { it.name },
        "web" to descriptors.filter { it.category == "web" }.mapTo(linkedSetOf()) { it.name },
        "workspace" to descriptors.filter { it.category == "workspace" }.mapTo(linkedSetOf()) { it.name },
        "plugins" to descriptors.filter { it.category == "plugins" }.mapTo(linkedSetOf()) { it.name },
        "phone" to descriptors.filter { it.category == "phone" }.mapTo(linkedSetOf()) { it.name },
        "terminal" to descriptors.filter { it.category == "terminal" }.mapTo(linkedSetOf()) { it.name },
        "mcp" to descriptors.filter { it.category == "mcp" }.mapTo(linkedSetOf()) { it.name },
        "memory" to descriptors.filter { it.category == "memory" }.mapTo(linkedSetOf()) { it.name },
        "recovery" to descriptors.filter { it.category == "recovery" }.mapTo(linkedSetOf()) { it.name }
    )
    private val allGroups = toolsByGroup.keys.toSortedSet()

    fun names(): List<String> = descriptors.map { it.name }

    fun schemas(): JSONArray {
        val array = JSONArray()
        descriptors.forEach { array.put(it.schema()) }
        return array
    }

    fun metadata(): JSONArray {
        val array = JSONArray()
        descriptors.forEach { array.put(it.metadata()) }
        return array
    }

    fun metadata(tools: Set<String>): JSONArray {
        val array = JSONArray()
        normalizeTools(tools).forEach { name ->
            descriptor(name)?.let { array.put(it.metadata()) }
        }
        return array
    }

    fun baselineTools(): Set<String> = toolsByGroup["baseline"] ?: linkedSetOf("get_time", "toolset_request")

    fun availableGroups(): JSONArray {
        val array = JSONArray()
        allGroups.forEach { array.put(it) }
        return array
    }

    fun toolsForGroups(groups: Set<String>): Set<String> {
        val normalized = groups.map { it.lowercase() }.toSet()
        if (normalized.isEmpty()) return baselineTools()
        if (normalized.contains("all")) return allToolNames

        val selected = linkedSetOf<String>()
        normalized.forEach { group ->
            toolsByGroup[group]?.let { selected.addAll(it) }
        }
        return if (selected.isNotEmpty()) normalizeTools(selected) else baselineTools()
    }

    fun normalizeTools(toolNames: Set<String>): Set<String> {
        val resolved = linkedSetOf<String>()
        toolNames.forEach { name ->
            if (name in allToolNames) resolved.add(name)
        }
        if (resolved.isEmpty()) return baselineTools()
        resolved.add("toolset_request")
        return resolved
    }

    fun schemasForTools(toolNames: Set<String>): JSONArray {
        val array = JSONArray()
        normalizeTools(toolNames).forEach { name ->
            descriptor(name)?.let { array.put(it.schema()) }
        }
        return array
    }

    fun descriptor(name: String): NativeToolDescriptor? = byName[name]

    fun access(name: String): NativeToolAccess? = byName[name]?.access

    fun isAutoRecoverable(name: String): Boolean = byName[name]?.autoRecover == true
}
