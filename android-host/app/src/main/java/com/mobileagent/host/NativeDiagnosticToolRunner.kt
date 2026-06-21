package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeDiagnosticToolRunner(
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val executeToolDirect: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val executeToolWithAutoRecovery: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject
) {
    fun execute(name: String, arguments: JSONObject, actionsApproved: Boolean = false): JSONObject {
        val startedAt = System.currentTimeMillis()
        AgentEventStore.record(
            "tool_started",
            "诊断工具开始：$name",
            JSONObject()
                .put("tool", name)
                .put("diagnostic", true)
                .put("arguments", arguments.toString().take(1200))
        )
        val taskPlan = JSONObject()
            .put("status", "diagnostic")
            .put("goal", "diagnostic native tool call")
            .put("steps", JSONArray())
            .put("updated_at", System.currentTimeMillis() / 1000)
        val output = if (name in DIRECT_DIAGNOSTIC_TOOLS) {
            executeToolDirect(name, arguments, actionsApproved, taskPlan, null)
        } else {
            executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, null)
        }
        AgentEventStore.record(
            "tool_finished",
            "诊断工具结束：$name / ${stepEvaluator.state(output)}",
            JSONObject()
                .put("tool", name)
                .put("diagnostic", true)
                .put("state", stepEvaluator.state(output))
                .put("duration_ms", System.currentTimeMillis() - startedAt)
                .put("summary", stepEvaluator.summary(output))
        )
        return output
    }

    companion object {
        private val DIRECT_DIAGNOSTIC_TOOLS = NativeMcpToolDispatcher.TOOL_NAMES
    }
}
