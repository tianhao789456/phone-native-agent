package com.mobileagent.host

import org.json.JSONObject

data class NativeToolStepArtifacts(
    val loopStep: JSONObject,
    val toolTraceItem: JSONObject,
    val toolMessage: JSONObject
) {
    companion object {
        fun build(
            call: NativeToolCall,
            toolCallIndex: Int,
            step: Int,
            round: Int,
            durationMs: Long,
            output: JSONObject,
            state: String,
            retriesLeft: Int,
            verification: JSONObject,
            closedLoop: JSONObject,
            evidence: JSONObject,
            verificationDecision: JSONObject,
            summary: String,
            toolMessageContent: (JSONObject) -> String
        ): NativeToolStepArtifacts {
            val loopStep = JSONObject()
                .put("step", step)
                .put("round", round)
                .put("tool_call_index", toolCallIndex)
                .put("tool", call.name)
                .put("state", state)
                .put("duration_ms", durationMs)
                .put("recoverable", state != "success")
                .put("retry_budget_remaining", retriesLeft)
                .put("verification", verification)
                .put("closed_loop", closedLoop)
                .put("evidence", evidence)
                .put("verification_state", verificationDecision)
                .put("summary", summary)

            val toolTraceItem = JSONObject()
                .put("step", step)
                .put("round", round)
                .put("tool", call.name)
                .put("arguments", call.arguments)
                .put("output", output)
                .put("state", state)
                .put("closed_loop", closedLoop)
                .put("evidence", evidence)
                .put("verification_state", verificationDecision)
                .put("duration_ms", durationMs)
                .put("created_at", System.currentTimeMillis() / 1000.0)

            val toolMessage = JSONObject()
                .put("role", "tool")
                .put("tool_call_id", call.id)
                .put("name", call.name)
                .put("content", toolMessageContent(loopStep))

            return NativeToolStepArtifacts(loopStep, toolTraceItem, toolMessage)
        }
    }
}
