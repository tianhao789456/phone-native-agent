package com.mobileagent.host

object NativeCoreToolSets {
    val screenActionTools: Set<String> = setOf(
        "host_click_text",
        "host_click_view_id",
        "host_click_index",
        "host_long_press_text",
        "host_long_press_index",
        "host_input_text",
        "host_clear_text",
        "host_back",
        "host_home",
        "host_press_key",
        "host_scroll",
        "host_swipe_coords",
        "host_open_app",
        "host_open_url",
        "intent_open",
        "share_file",
        "open_file_with"
    )

    val terminalDelegationTools: Set<String> = setOf(
        "termux_chat",
        "terminal_run",
        "terminal_script",
        "terminal_task_status",
        "terminal_task_cancel",
        "recover_terminal_backend"
    )

    val verificationTools: Set<String> = setOf(
        "accessibility_snapshot_v2",
        "host_screen_find",
        "host_current_app",
        "host_wait_for_text",
        "mcp_status",
        "mcp_tools",
        "task_plan_status",
        "task_report_read",
        "task_failure_latest",
        "workspace_info",
        "list_files",
        "read_file",
        "search_files",
        "docs_read",
        "docs_search",
        "skill_list",
        "skill_read",
        "memory_search",
        "experience_search",
        "procedure_search",
        "procedure_read",
        "terminal_task_status"
    )
}
