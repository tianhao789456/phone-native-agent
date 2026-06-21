package com.mobileagent.host

import org.json.JSONObject

private typealias RecoveryAttempt = (
    String,
    JSONObject,
    Boolean,
    JSONObject,
    String?,
    JSONObject
) -> JSONObject?

class NativeToolRecoveryController(
    stepEvaluator: NativeLoopStepEvaluator,
    terminalRecovery: NativeTerminalRecoveryController,
    executeTool: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    shouldAutoRecover: (String, JSONObject, Boolean) -> Boolean,
    terminalFuseOpen: (String) -> JSONObject?,
    terminalPowerMode: () -> Boolean,
    isAutoRecoverable: (String) -> Boolean,
    diagnoseTerminal: () -> JSONObject,
    recoverTerminalBackend: (JSONObject) -> JSONObject,
    recoverPcBridge: ((JSONObject) -> JSONObject)?,
    log: (String, String, String, JSONObject) -> Unit
) {
    private val recoveryChain: List<RecoveryAttempt> = listOf(
        { name, arguments, actionsApproved, taskPlan, sessionId, firstOutput ->
            verificationRecovery.recover(
                name = name,
                arguments = arguments,
                actionsApproved = actionsApproved,
                taskPlan = taskPlan,
                sessionId = sessionId,
                firstOutput = firstOutput
            )
        },
        { name, arguments, actionsApproved, taskPlan, sessionId, firstOutput ->
            mcpRecovery.recover(
                name = name,
                arguments = arguments,
                actionsApproved = actionsApproved,
                taskPlan = taskPlan,
                sessionId = sessionId,
                firstOutput = firstOutput
            )
        },
        { name, arguments, actionsApproved, taskPlan, sessionId, firstOutput ->
            terminalAutoRecovery.recover(
                name = name,
                arguments = arguments,
                actionsApproved = actionsApproved,
                taskPlan = taskPlan,
                sessionId = sessionId,
                firstOutput = firstOutput
            )
        }
    )

    private val verificationRecovery = NativeToolVerificationRecoveryController(
        stepEvaluator = stepEvaluator,
        executeTool = executeTool,
        verifyToolResult = verifyToolResult,
        terminalPowerMode = terminalPowerMode,
        isAutoRecoverable = isAutoRecoverable,
        terminalFuseOpen = terminalFuseOpen,
        diagnoseTerminal = diagnoseTerminal,
        recoverTerminalBackend = recoverTerminalBackend,
        terminalRecovery = terminalRecovery,
        log = log
    )

    private val mcpRecovery = NativeToolMcpRecoveryController(
        stepEvaluator = stepEvaluator,
        executeTool = executeTool,
        verifyToolResult = verifyToolResult,
        shouldAutoRecover = shouldAutoRecover,
        terminalRecovery = terminalRecovery,
        recoverPcBridge = recoverPcBridge,
        log = log
    )

    private val terminalAutoRecovery = NativeToolTerminalAutoRecoveryController(
        stepEvaluator = stepEvaluator,
        executeTool = executeTool,
        verifyToolResult = verifyToolResult,
        shouldAutoRecover = shouldAutoRecover,
        terminalRecovery = terminalRecovery,
        terminalFuseOpen = terminalFuseOpen,
        diagnoseTerminal = diagnoseTerminal,
        recoverTerminalBackend = recoverTerminalBackend,
        log = log
    )

    fun recover(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject
    ): JSONObject? {
        for (attempt in recoveryChain) {
            attempt(
                name,
                arguments,
                actionsApproved,
                taskPlan,
                sessionId,
                firstOutput
            )?.let { return it }
        }
        return null
    }

    fun verifyRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject
    ): JSONObject? {
        return verificationRecovery.recover(
            name = name,
            arguments = arguments,
            actionsApproved = actionsApproved,
            taskPlan = taskPlan,
            sessionId = sessionId,
            firstOutput = firstOutput
        )
    }

    fun mcpRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject
    ): JSONObject? {
        return mcpRecovery.recover(
            name = name,
            arguments = arguments,
            actionsApproved = actionsApproved,
            taskPlan = taskPlan,
            sessionId = sessionId,
            firstOutput = firstOutput
        )
    }

    fun terminalRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?,
        firstOutput: JSONObject
    ): JSONObject? {
        return terminalAutoRecovery.recover(
            name = name,
            arguments = arguments,
            actionsApproved = actionsApproved,
            taskPlan = taskPlan,
            sessionId = sessionId,
            firstOutput = firstOutput
        )
    }
}
