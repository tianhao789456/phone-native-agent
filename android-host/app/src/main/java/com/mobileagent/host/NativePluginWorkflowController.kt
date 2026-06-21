package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativePluginWorkflowController(
    private val plugins: MobilePluginRegistry,
    private val executePluginTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val isPluginToolAllowed: (String) -> Boolean,
    private val getDescriptor: (String) -> NativeToolDescriptor?,
    private val evaluateState: (JSONObject) -> String,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun runPluginWorkflow(
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String? = null
    ): JSONObject {
        val pluginId = arguments.optString("id")
        val workflowName = arguments.optString("workflow")
        val workflowResult = plugins.workflow(pluginId, workflowName)
        if (!workflowResult.optBoolean("ok", false)) return workflowResult
        val workflow = workflowResult.optJSONObject("workflow") ?: JSONObject()
        val steps = workflow.optJSONArray("steps") ?: return JSONObject()
            .put("ok", false)
            .put("error", "Workflow has no steps array: $pluginId/$workflowName")
        val maxSteps = arguments.optInt("max_steps", 20).coerceIn(1, 50)
        val trace = JSONArray()
        var failed = 0
        for (index in 0 until minOf(steps.length(), maxSteps)) {
            val step = steps.optJSONObject(index) ?: JSONObject()
            val tool = step.optString("tool")
            val stepArgs = step.optJSONObject("arguments") ?: JSONObject()
            val started = System.currentTimeMillis()
            val output = if (isPluginToolAllowed(tool)) {
                executePluginTool(tool, stepArgs, actionsApproved, taskPlan, sessionId)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("error", "Plugin workflow cannot call tool: $tool")
                    .put("tool", getDescriptor(tool)?.metadata() ?: JSONObject().put("name", tool))
                    .put(
                        "verification",
                        JSONObject()
                            .put("required", false)
                            .put("ok", false)
                            .put("status", "blocked_by_plugin_adapter")
                            .put("summary", "Plugin adapter blocked this tool.")
                    )
            }
            val state = evaluateState(output)
            if (state != "success") failed += 1
            trace.put(
                JSONObject()
                    .put("step", index + 1)
                    .put("tool", tool)
                    .put("arguments", stepArgs)
                    .put("state", state)
                    .put("duration_ms", System.currentTimeMillis() - started)
                    .put("output", output)
            )
            log(
                "debug",
                "plugin",
                "plugin workflow step",
                JSONObject()
                    .put("plugin_id", pluginId)
                    .put("workflow", workflowName)
                    .put("step", index + 1)
                    .put("tool", tool)
                    .put("state", state)
            )
            if (state != "success" && step.optBoolean("stop_on_failure", true)) break
        }
        val truncated = steps.length() > maxSteps
        val ok = failed == 0 && !truncated
        val result = JSONObject()
            .put("ok", ok)
            .put("id", pluginId)
            .put("workflow", workflowName)
            .put("steps", trace.length())
            .put("failed_steps", failed)
            .put("truncated", truncated)
            .put("trace", trace)
            .put("summary", if (ok) "Plugin workflow completed." else "Plugin workflow completed with failures.")
        runCatching {
            plugins.writeWorkflowRunReport(pluginId, workflowName, result)
        }.onSuccess { reports ->
            result.put("reports", reports)
        }.onFailure { error ->
            result.put("report_error", error.message ?: error.toString())
        }
        return result
    }
}
