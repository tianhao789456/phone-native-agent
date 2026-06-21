package com.mobileagent.host

import org.json.JSONObject

class NativeToolExecutionManager(
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    private val executeTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val shouldAutoRecover: (String, JSONObject, Boolean) -> Boolean,
    private val terminalFuseOpen: (String) -> JSONObject?,
    private val terminalPowerMode: () -> Boolean,
    private val isAutoRecoverable: (String) -> Boolean,
    private val diagnoseTerminal: () -> JSONObject,
    private val recoverTerminalBackend: (JSONObject) -> JSONObject,
    private val recoverPcBridge: ((JSONObject) -> JSONObject)?,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    private val recoveryController = NativeToolRecoveryController(
        stepEvaluator = stepEvaluator,
        terminalRecovery = terminalRecovery,
        executeTool = executeTool,
        verifyToolResult = verifyToolResult,
        shouldAutoRecover = shouldAutoRecover,
        terminalFuseOpen = terminalFuseOpen,
        terminalPowerMode = terminalPowerMode,
        isAutoRecoverable = isAutoRecoverable,
        diagnoseTerminal = diagnoseTerminal,
        recoverTerminalBackend = recoverTerminalBackend,
        recoverPcBridge = recoverPcBridge,
        log = log
    )

    fun executeWithAutoRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        val firstOutput = executeTool(name, arguments, actionsApproved, taskPlan, sessionId)
        firstOutput.put("verification", verifyToolResult(name, arguments, firstOutput))
        return recoveryController.recover(
            name,
            arguments,
            actionsApproved,
            taskPlan,
            sessionId,
            firstOutput
        ) ?: firstOutput
    }

    fun verify(name: String, arguments: JSONObject, output: JSONObject): JSONObject {
        return verifyToolResult(name, arguments, output)
    }
}
