package com.mobileagent.host

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class NativePlanningToolDispatcher(
    private val workspace: MobileWorkspace,
    private val updatePlan: (JSONObject, JSONObject) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject, taskPlan: JSONObject): JSONObject {
        return when (name) {
            "get_time" -> {
                val now = ZonedDateTime.now()
                JSONObject()
                    .put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("timezone", now.zone.toString())
            }
            "task_plan_update" -> updatePlan(taskPlan, arguments)
            "task_plan_status" -> JSONObject(taskPlan.toString())
            "task_create" -> workspace.taskCreate(
                arguments.optString("title"),
                arguments.optString("goal", "")
            )
            "task_list" -> workspace.taskList(arguments.optInt("limit", 50))
            "task_update" -> workspace.taskUpdate(
                arguments.optString("task"),
                arguments.optString("status", ""),
                arguments.optString("note", "")
            )
            "task_log_append" -> workspace.taskLogAppend(
                arguments.optString("task"),
                arguments.optString("name", "task.log"),
                arguments.optString("content")
            )
            "task_artifact_write" -> workspace.taskArtifactWrite(
                arguments.optString("task"),
                arguments.optString("name"),
                arguments.optString("content"),
                arguments.optBoolean("overwrite", false)
            )
            "task_reports" -> workspace.taskReports(
                arguments.optString("task"),
                arguments.optInt("limit", 50)
            )
            "task_report_read" -> workspace.taskReportRead(
                arguments.optString("task"),
                arguments.optString("report"),
                arguments.optInt("max_bytes", 1_000_000)
            )
            "task_report_summarize" -> workspace.taskReportSummarize(
                arguments.optString("task"),
                arguments.optString("report")
            )
            "task_failure_latest" -> workspace.latestFailureAnalysis(
                arguments.optInt("max_bytes", 20000)
            )
            else -> throw IllegalArgumentException("Planning dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "get_time",
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
            "task_failure_latest"
        )
    }
}
