package com.mobileagent.host

import org.json.JSONObject

class NativeToolTerminalAutoRecoveryController(
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val executeTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    private val shouldAutoRecover: (String, JSONObject, Boolean) -> Boolean,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val terminalFuseOpen: (String) -> JSONObject?,
    private val diagnoseTerminal: () -> JSONObject,
    private val recoverTerminalBackend: (JSONObject) -> JSONObject,
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
        if (!shouldAutoRecover(name, firstOutput, actionsApproved)) {
            return null
        }
        terminalFuseOpen(name)?.let { fuse ->
            return JSONObject(firstOutput.toString()).put(
                "auto_recovery",
                fuse
                    .put("type", "terminal_auto_recovery")
                    .put("original_tool", name)
                    .put("status", "circuit_open")
                    .put("retry_attempted", false)
            )
        }

        val recovery = JSONObject()
            .put("type", "terminal_auto_recovery")
            .put("original_tool", name)
            .put("original_arguments", JSONObject(arguments.toString()))
            .put("initial_state", stepEvaluator.state(firstOutput))
            .put("initial_output", JSONObject(firstOutput.toString()))

        log(
            "warn",
            "recovery",
            "terminal auto recovery started",
            JSONObject()
                .put("tool", name)
                .put("initial_state", stepEvaluator.state(firstOutput))
                .put("summary", stepEvaluator.summary(firstOutput))
        )

        val diagnosis = diagnoseTerminal()
        recovery.put("diagnosis", diagnosis)

        val repaired = if (diagnosis.optString("status") == "ok") {
            JSONObject()
                .put("ok", true)
                .put("skipped", true)
                .put("reason", "terminal backend already reachable during diagnosis")
        } else {
            recoverTerminalBackend(NativeToolRecoveryConfig.terminalRecoveryArgs())
        }
        recovery.put("repair", repaired)

        val afterRepair = repaired.optJSONObject("after") ?: diagnoseTerminal()
        val canRetry = repaired.optBoolean("ok", false) || afterRepair.optString("status") == "ok"
        if (!canRetry) {
            recovery.put("status", "recovery_failed")
            recovery.put("retry_attempted", false)
            terminalRecovery.recordOutcome(name, false)
            log(
                "error",
                "recovery",
                "terminal auto recovery failed",
                JSONObject()
                    .put("tool", name)
                    .put("diagnosis", diagnosis.optString("status"))
                    .put("repair_ok", repaired.optBoolean("ok", false))
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
            "terminal auto recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("retry_state", stepEvaluator.state(retryOutput))
                .put("status", recovery.optString("status"))
        )
        return JSONObject(retryOutput.toString()).put("auto_recovery", recovery)
    }
}
