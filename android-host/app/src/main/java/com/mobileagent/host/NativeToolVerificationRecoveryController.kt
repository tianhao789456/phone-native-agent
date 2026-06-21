package com.mobileagent.host

import org.json.JSONObject

class NativeToolVerificationRecoveryController(
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val executeTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    private val terminalPowerMode: () -> Boolean,
    private val isAutoRecoverable: (String) -> Boolean,
    private val terminalFuseOpen: (String) -> JSONObject?,
    private val diagnoseTerminal: () -> JSONObject,
    private val recoverTerminalBackend: (JSONObject) -> JSONObject,
    private val terminalRecovery: NativeTerminalRecoveryController,
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
        val verification = firstOutput.optJSONObject("verification") ?: return null
        if (!verification.optBoolean("required", false) || verification.optBoolean("ok", false)) return null
        val recovery = JSONObject()
            .put("type", "verification_recovery")
            .put("original_tool", name)
            .put("original_arguments", JSONObject(arguments.toString()))
            .put("initial_verification", JSONObject(verification.toString()))

        log(
            "warn",
            "recovery",
            "verification recovery started",
            JSONObject()
                .put("tool", name)
                .put("status", verification.optString("status"))
                .put("summary", verification.optString("summary"))
        )

        return when (name) {
            "write_file" -> recoverWriteFileVerification(
                name,
                arguments,
                actionsApproved,
                taskPlan,
                sessionId,
                firstOutput,
                recovery
            )
            "terminal_run", "terminal_script", "terminal_task_status" -> recoverTerminalVerification(
                name,
                arguments,
                actionsApproved,
                taskPlan,
                sessionId,
                firstOutput,
                recovery
            )
            else -> null
        }
    }

    private fun recoverWriteFileVerification(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject,
        recovery: JSONObject
    ): JSONObject {
        val retryArguments = JSONObject(arguments.toString()).put("overwrite", true)
        recovery
            .put("strategy", "retry_write_and_read_back")
            .put("retry_arguments", JSONObject(retryArguments.toString()))
        val retryOutput = executeTool(name, retryArguments, actionsApproved, taskPlan, sessionId)
        retryOutput.put("verification", verifyToolResult(name, retryArguments, retryOutput))
        recovery
            .put("retry_attempted", true)
            .put("retry_verification", retryOutput.optJSONObject("verification") ?: JSONObject())
            .put("status", if (stepEvaluator.state(retryOutput) == "success") "recovered" else "retry_failed")
        log(
            if (stepEvaluator.state(retryOutput) == "success") "info" else "error",
            "recovery",
            "verification recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("status", recovery.optString("status"))
                .put("summary", stepEvaluator.summary(retryOutput))
        )
        return JSONObject(retryOutput.toString()).put("verification_recovery", recovery)
    }

    private fun recoverTerminalVerification(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject,
        recovery: JSONObject
    ): JSONObject? {
        if (!actionsApproved || !terminalPowerMode()) {
            recovery
                .put("status", "not_attempted")
                .put("reason", "terminal verification recovery requires danger/developer mode and current-request confirmation")
            return JSONObject(firstOutput.toString()).put("verification_recovery", recovery)
        }
        if (!isAutoRecoverable(name)) return null
        terminalFuseOpen(name)?.let { fuse ->
            return JSONObject(firstOutput.toString()).put(
                "verification_recovery",
                fuse
                    .put("type", "verification_recovery")
                    .put("original_tool", name)
                    .put("status", "circuit_open")
                    .put("retry_attempted", false)
            )
        }
        recovery.put("strategy", "diagnose_recover_retry_terminal")
        val diagnosis = diagnoseTerminal()
        recovery.put("diagnosis", diagnosis)
        if (diagnosis.optString("status") != "ok") {
            val repaired = recoverTerminalBackend(NativeToolRecoveryConfig.terminalRecoveryArgs())
            recovery.put("repair", repaired)
            val afterRepair = repaired.optJSONObject("after") ?: diagnoseTerminal()
            if (!repaired.optBoolean("ok", false) && afterRepair.optString("status") != "ok") {
                recovery
                    .put("status", "recovery_failed")
                    .put("retry_attempted", false)
                terminalRecovery.recordOutcome(name, false)
                return JSONObject(firstOutput.toString()).put("verification_recovery", recovery)
            }
        }
        val retryOutput = executeTool(name, arguments, actionsApproved, taskPlan, sessionId)
        retryOutput.put("verification", verifyToolResult(name, arguments, retryOutput))
        recovery
            .put("retry_attempted", true)
            .put("retry_verification", retryOutput.optJSONObject("verification") ?: JSONObject())
            .put("status", if (stepEvaluator.state(retryOutput) == "success") "recovered" else "retry_failed")
        terminalRecovery.recordOutcome(name, stepEvaluator.state(retryOutput) == "success")
        log(
            if (stepEvaluator.state(retryOutput) == "success") "info" else "error",
            "recovery",
            "terminal verification recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("status", recovery.optString("status"))
                .put("summary", stepEvaluator.summary(retryOutput))
        )
        return JSONObject(retryOutput.toString()).put("verification_recovery", recovery)
    }
}
