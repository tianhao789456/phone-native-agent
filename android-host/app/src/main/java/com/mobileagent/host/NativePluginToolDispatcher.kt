package com.mobileagent.host

import org.json.JSONObject

class NativePluginToolDispatcher(
    private val plugins: MobilePluginRegistry,
    private val runWorkflow: (JSONObject, Boolean, JSONObject, String?) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        return when (name) {
            "plugin_info" -> plugins.info()
            "plugin_list" -> plugins.list(
                arguments.optBoolean("include_disabled", true),
                arguments.optBoolean("include_details", false)
            )
            "plugin_create" -> plugins.create(
                arguments.optJSONObject("manifest") ?: JSONObject(),
                arguments.optBoolean("overwrite", false)
            )
            "plugin_read" -> plugins.read(arguments.optString("id"))
            "plugin_reports" -> plugins.reports(arguments.optString("id"), arguments.optInt("limit", 50))
            "plugin_report_read" -> plugins.readReport(
                arguments.optString("id"),
                arguments.optString("report"),
                arguments.optInt("max_bytes", 200000)
            )
            "plugin_validate" -> plugins.validate(arguments.optString("id"))
            "plugin_test" -> plugins.test(arguments.optString("id"))
            "plugin_run" -> runWorkflow(arguments, actionsApproved, taskPlan, sessionId)
            "plugin_set_enabled" -> plugins.setEnabled(
                arguments.optString("id"),
                arguments.optBoolean("enabled", true)
            )
            else -> throw IllegalArgumentException("Plugin dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "plugin_info",
            "plugin_list",
            "plugin_create",
            "plugin_read",
            "plugin_reports",
            "plugin_report_read",
            "plugin_validate",
            "plugin_test",
            "plugin_run",
            "plugin_set_enabled"
        )
    }
}
