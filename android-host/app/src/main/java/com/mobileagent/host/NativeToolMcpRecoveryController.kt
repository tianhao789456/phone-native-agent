package com.mobileagent.host

import org.json.JSONObject

class NativeToolMcpRecoveryController(
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val executeTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    private val shouldAutoRecover: (String, JSONObject, Boolean) -> Boolean,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val recoverPcBridge: ((JSONObject) -> JSONObject)?,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun recover(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject
    ): JSONObject? {
        if (!name.startsWith("mcp_")) return null
        if (!shouldAutoRecover(name, firstOutput, actionsApproved)) return null
        if (recoverPcBridge == null) return null
        val recoveryError = mcpCallErrorText(firstOutput)
        if (!looksLikeMcpBackendError(recoveryError)) return null

        val recovery = JSONObject()
            .put("type", "mcp_bridge_auto_recovery")
            .put("original_tool", name)
            .put("original_arguments", JSONObject(arguments.toString()))
            .put("initial_state", stepEvaluator.state(firstOutput))
            .put("initial_output", JSONObject(firstOutput.toString()))
            .put("error", recoveryError)
            .put("retry_attempted", false)

        log(
            "warn",
            "recovery",
            "mcp bridge auto recovery started",
            JSONObject()
                .put("tool", name)
                .put("error", recoveryError)
                .put("initial_state", stepEvaluator.state(firstOutput))
                .put("summary", stepEvaluator.summary(firstOutput))
        )

        val repaired = recoverPcBridge(NativeToolRecoveryConfig.mcpRecoveryArgs())
        recovery.put("repair", repaired)
        val canRetry = repaired.optBoolean("mcp_usable", false) ||
                repaired.optString("status").contains("mcp_recovered") ||
                repaired.optBoolean("ok", false)

        if (!canRetry) {
            recovery.put("status", "recovery_failed")
            recovery.put("retry_attempted", false)
            terminalRecovery.recordOutcome(name, false)
            log(
                "error",
                "recovery",
                "mcp bridge auto recovery failed",
                JSONObject()
                    .put("tool", name)
                    .put("status", repaired.optString("status"))
                    .put("ok", repaired.optBoolean("ok", false))
            )
            return JSONObject(firstOutput.toString()).put("auto_recovery", recovery)
        }

        val retryOutput = executeTool(name, arguments, actionsApproved, taskPlan, sessionId)
        retryOutput.put("verification", verifyToolResult(name, arguments, retryOutput))
        recovery
            .put("retry_attempted", true)
            .put("retry_state", stepEvaluator.state(retryOutput))
            .put("status", if (stepEvaluator.state(retryOutput) == "success") "recovered" else "retry_failed")
        terminalRecovery.recordOutcome(name, stepEvaluator.state(retryOutput) == "success")
        log(
            if (stepEvaluator.state(retryOutput) == "success") "info" else "error",
            "recovery",
            "mcp bridge auto recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("retry_state", stepEvaluator.state(retryOutput))
                .put("status", recovery.optString("status"))
                .put("mcp_usable", repaired.optBoolean("mcp_usable", false))
        )
        return JSONObject(retryOutput.toString()).put("auto_recovery", recovery)
    }

    private fun mcpCallErrorText(output: JSONObject): String {
        val direct = output.optString("error", "")
        if (direct.isNotBlank()) return direct
        val nested = output.optJSONObject("result")?.optString("error", "")
        if (nested != null && nested.isNotBlank()) return nested
        val verification = output.optJSONObject("verification")?.optString("summary", "")
        if (!verification.isNullOrBlank()) return verification
        return stepEvaluator.summary(output)
    }

    private fun looksLikeMcpBackendError(error: String): Boolean {
        val text = error.lowercase().trim()
        if (text.isBlank()) return false
        val markers = setOf(
            "http 502",
            "502",
            "connection refused",
            "connection reset",
            "no route to host",
            "connection timed out",
            "timed out",
            "unreachable",
            "http 5",
            "http error",
            "failed to connect",
            "bad gateway"
        )
        return markers.any { text.contains(it) }
    }
}
