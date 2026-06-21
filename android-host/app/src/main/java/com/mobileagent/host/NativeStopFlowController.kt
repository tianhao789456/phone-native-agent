package com.mobileagent.host

import org.json.JSONObject

class NativeStopFlowController(
    private val stopController: NativeStopController
) {
    fun isRequested(sessionId: String): Boolean = stopController.isRequested(sessionId)

    fun clear(sessionId: String) = stopController.clear(sessionId)

    fun buildUserStopBlock(sessionId: String, phase: String, modelResponse: NativeModelResponse? = null): JSONObject {
        return stopController.block(sessionId, phase, modelResponse)
    }

    fun userStopFinalText(blocker: JSONObject): String {
        return "The user requested stop at phase: ${blocker.optString("phase", "unknown")}. You can send a follow-up instruction to continue."
    }

    fun loopGuardFinalText(blocker: JSONObject): String {
        return buildString {
            append("Task loop paused to avoid repeated failures.\n")
            append("Tool: ").append(blocker.optString("tool", "-")).append("\n")
            append("State: ").append(blocker.optString("state", "-")).append("\n")
            append("Summary: ").append(blocker.optString("summary", "no summary")).append("\n\n")
            append("Suggestion: try a different path or add clearer goal in a follow-up.")
        }
    }
}
