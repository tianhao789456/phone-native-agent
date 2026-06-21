package com.mobileagent.host

import org.json.JSONObject

object NativePlanningToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "task_plan_update",
                    description = "Create or update the current managed task plan. Use this for multi-step tasks to record goal, done_when completion criteria, step status, evidence, and recovery state before and during tool work.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "goal" to NativeToolSchema.stringProp(""),
                        "status" to NativeToolSchema.stringProp("in_progress"),
                        "steps" to NativeToolSchema.arrayProp(),
                        "done_when" to NativeToolSchema.arrayProp(),
                        "step_id" to NativeToolSchema.stringProp(""),
                        "step_status" to NativeToolSchema.stringProp(""),
                        "note" to NativeToolSchema.stringProp(""),
                        "evidence" to NativeToolSchema.stringProp("")
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
                    properties = NativeToolSchema.props("title" to NativeToolSchema.stringProp(), "goal" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("title")
                ),
        NativeToolDescriptor(
                    name = "task_list",
                    description = "List persistent APP workspace task directories created by task_create.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("limit" to NativeToolSchema.intProp(50))
                ),
        NativeToolDescriptor(
                    name = "task_update",
                    description = "Update persistent task metadata such as status and note.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "status" to NativeToolSchema.stringProp("in_progress"), "note" to NativeToolSchema.stringProp("")),
                    required = NativeToolSchema.req("task")
                ),
        NativeToolDescriptor(
                    name = "task_log_append",
                    description = "Append a timestamped log entry under a persistent task logs folder.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "name" to NativeToolSchema.stringProp("task.log"), "content" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("task", "content")
                ),
        NativeToolDescriptor(
                    name = "task_artifact_write",
                    description = "Write a text artifact under a persistent task artifacts folder.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "name" to NativeToolSchema.stringProp(), "content" to NativeToolSchema.stringProp(), "overwrite" to NativeToolSchema.boolProp(false)),
                    required = NativeToolSchema.req("task", "name", "content")
                ),
        NativeToolDescriptor(
                    name = "task_reports",
                    description = "List saved task-loop reports for a persistent task directory.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "limit" to NativeToolSchema.intProp(50)),
                    required = NativeToolSchema.req("task")
                ),
        NativeToolDescriptor(
                    name = "task_report_read",
                    description = "Read a saved task-loop report from a persistent task directory. Accepts report paths returned by task_reports or task_loop persistence.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "report" to NativeToolSchema.stringProp(), "max_bytes" to NativeToolSchema.intProp(1_000_000)),
                    required = NativeToolSchema.req("task", "report")
                ),
        NativeToolDescriptor(
                    name = "task_report_summarize",
                    description = "Generate human-readable Markdown artifacts from a saved task-loop JSON report.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("task" to NativeToolSchema.stringProp(), "report" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("task", "report")
                ),
        NativeToolDescriptor(
                    name = "task_failure_latest",
                    description = "Return the newest persistent task failure-analysis.md artifact with task metadata and content. Use this after failed tool loops to inspect what broke and what to retry.",
                    category = "planning",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("max_bytes" to NativeToolSchema.intProp(20000))
                )
    )
}
