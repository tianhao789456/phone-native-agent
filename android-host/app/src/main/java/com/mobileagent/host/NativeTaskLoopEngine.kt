package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeTaskLoopEngine(
    private val modelRequester: NativeModelRequester,
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val stopController: NativeStopController,
    private val stepEvents: NativeToolStepEvents,
    private val executeToolWithAutoRecovery: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val verifyToolResult: (String, JSONObject, JSONObject) -> JSONObject,
    private val resolveToolset: (String?) -> Set<String>,
    private val buildUserStopBlock: (String, String, NativeModelResponse?) -> JSONObject,
    private val userStopFinalText: (JSONObject) -> String,
    private val loopGuardFinalText: (JSONObject) -> String
) {
    fun run(
        apiKey: String,
        messages: JSONArray,
        sessionId: String,
        memoryContext: JSONObject?,
        actionsApproved: Boolean,
        enabledTools: Set<String>,
        taskPlan: JSONObject,
        maxToolRounds: Int
    ): NativeTaskLoopOutcome {
        val toolTrace = JSONArray()
        val loopTrace = JSONArray()
        val loopEvidence = JSONArray()
        val loopGuardState = NativeLoopGuardState()
        var availableTools = enabledTools
        var modelResponse = NativeModelResponse("", JSONArray())
        var stoppedByLoopGuard: JSONObject? = null
        var stoppedByUser: JSONObject? = null
        var pendingVerification: JSONObject? = null
        var stepIndex = 0
        var failedSteps = 0
        var toolRounds = 0

        if (!stopController.isRequested(sessionId)) {
            modelResponse = modelRequester.requestWithEvents(
                apiKey = apiKey,
                messages = messages,
                phase = "initial",
                round = 0,
                enabledTools = availableTools,
                memoryContext = memoryContext
            )
        } else {
            stoppedByUser = buildUserStopBlock(sessionId, "before_request", null)
        }

        while (modelResponse.toolCalls.length() > 0 && toolRounds < maxToolRounds) {
            if (stopController.isRequested(sessionId)) {
                stoppedByUser = buildUserStopBlock(sessionId, "before_round", modelResponse)
                break
            }
            toolRounds += 1

            val assistant = JSONObject()
                .put("role", "assistant")
                .put("content", modelResponse.content)
                .put("tool_calls", modelResponse.toolCalls)
            messages.put(assistant)

            for (index in 0 until modelResponse.toolCalls.length()) {
                if (stopController.isRequested(sessionId)) {
                    stoppedByUser = buildUserStopBlock(sessionId, "before_tool", modelResponse)
                    break
                }
                val call = NativeToolCall.fromJson(modelResponse.toolCalls.getJSONObject(index))
                val name = call.name
                val arguments = call.arguments
                stepIndex += 1
                val startedAt = System.currentTimeMillis()
                stepEvents.started(name, arguments, stepIndex, toolRounds)

                val output = executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, sessionId)
                if (name == "toolset_request") {
                    availableTools = resolveToolset(sessionId)
                }

                val verification = output.optJSONObject("verification") ?: verifyToolResult(name, arguments, output)
                val state = stepEvaluator.state(output)
                val summary = stepEvaluator.summary(output)
                stepEvents.finished(name, state, stepIndex, toolRounds, System.currentTimeMillis() - startedAt, summary)
                if (state != "success") failedSteps += 1
                stepEvents.warnFailed(name, state, stepIndex, toolRounds, summary)

                val retriesLeft = loopGuardState.retriesLeft(name, arguments, state)
                val closedLoop = stepEvaluator.closedLoopStep(name, arguments, output, state, retriesLeft)
                val stepEvidence = stepEvaluator.evidenceFromStep(name, arguments, output, verification, closedLoop)
                if (stepEvidence.optBoolean("available", false)) {
                    loopEvidence.put(stepEvidence)
                }

                val verificationDecision = stepEvaluator.updateVerificationState(
                    pending = pendingVerification,
                    tool = name,
                    arguments = arguments,
                    output = output,
                    state = state,
                    closedLoop = closedLoop,
                    evidence = stepEvidence
                )
                pendingVerification = verificationDecision.optJSONObject("pending")
                val durationMs = System.currentTimeMillis() - startedAt
                val artifacts = NativeToolStepArtifacts.build(
                    call = call,
                    toolCallIndex = index,
                    step = stepIndex,
                    round = toolRounds,
                    durationMs = durationMs,
                    output = output,
                    state = state,
                    retriesLeft = retriesLeft,
                    verification = verification,
                    closedLoop = closedLoop,
                    evidence = stepEvidence,
                    verificationDecision = verificationDecision,
                    summary = summary,
                    toolMessageContent = { loopStep -> stepEvaluator.toolMessageContent(output, loopStep) }
                )
                val loopStep = artifacts.loopStep
                toolTrace.put(artifacts.toolTraceItem)
                loopTrace.put(loopStep)
                messages.put(artifacts.toolMessage)

                if (state != "success") {
                    val failureKey = stepEvaluator.failurePatternKey(name, output)
                    val failuresForPattern = loopGuardState.failureCount(failureKey)
                    if (loopGuardState.isRepeatedFailure(failuresForPattern)) {
                        stoppedByLoopGuard = stepEvaluator.buildLoopGuardStop(
                            name,
                            arguments,
                            output,
                            loopStep,
                            reason = "repeated_failure_pattern"
                        )
                        stepEvents.loopGuardStopped("任务闭环已判定失败，停止后续尝试", stoppedByLoopGuard)
                        break
                    }
                }

                if (state != "success" && retriesLeft <= 0) {
                    stoppedByLoopGuard = stepEvaluator.buildLoopGuardStop(
                        name,
                        arguments,
                        output,
                        loopStep,
                        reason = "retry_budget_exhausted"
                    )
                    stepEvents.loopGuardStopped("任务闭环未通过，停止后续尝试", stoppedByLoopGuard)
                    break
                }
            }

            if (stoppedByLoopGuard != null) break
            if (stoppedByUser != null) break
            if (stopController.isRequested(sessionId)) {
                stoppedByUser = buildUserStopBlock(sessionId, "before_next_round", modelResponse)
                break
            }

            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        stepEvaluator.taskLoopRoundHint(
                            round = toolRounds,
                            maxRounds = maxToolRounds,
                            toolCalls = modelResponse.toolCalls.length(),
                            failedSteps = failedSteps,
                            lastLoop = if (loopTrace.length() > 0) loopTrace.optJSONObject(loopTrace.length() - 1) else null
                        )
                    )
            )

            modelResponse = modelRequester.requestWithEvents(
                apiKey = apiKey,
                messages = messages,
                phase = "after_tools",
                round = toolRounds,
                enabledTools = availableTools,
                memoryContext = memoryContext
            )
        }

        val finalText = when {
            stoppedByUser != null -> userStopFinalText(stoppedByUser)
            stoppedByLoopGuard != null -> loopGuardFinalText(stoppedByLoopGuard)
            else -> modelResponse.content.ifBlank {
                if (toolRounds >= maxToolRounds) "任务闭环轮次限制到达，已停止后续尝试。" else ""
            }
        }

        val loopStatus = when {
            stoppedByUser != null -> "user_stopped"
            stoppedByLoopGuard != null -> "blocked_by_loop_guard"
            toolRounds >= maxToolRounds && modelResponse.toolCalls.length() > 0 -> "max_rounds_reached"
            failedSteps > 0 -> "completed_with_failures"
            stepIndex > 0 -> "completed"
            else -> "no_tools"
        }
        val taskLoop = NativeTaskLoopReport.build(
            status = loopStatus,
            rounds = toolRounds,
            maxRounds = maxToolRounds,
            steps = stepIndex,
            failedSteps = failedSteps,
            taskPlan = taskPlan,
            trace = loopTrace,
            evidence = loopEvidence,
            completionReview = stepEvaluator.completionReview(
                status = loopStatus,
                failedSteps = failedSteps,
                pendingVerification = pendingVerification,
                evidence = loopEvidence,
                finalText = finalText
            ),
            stopRequested = stopController.isRequested(sessionId),
            loopGuardBlocker = stoppedByLoopGuard,
            userStopBlocker = stoppedByUser
        )

        return NativeTaskLoopOutcome(
            finalText = finalText,
            toolRounds = toolRounds,
            steps = stepIndex,
            failedSteps = failedSteps,
            taskTrace = loopTrace,
            evidence = loopEvidence,
            toolTrace = toolTrace,
            taskLoop = taskLoop,
            pendingVerification = pendingVerification,
            stoppedByUserBlocker = stoppedByUser,
            stoppedByLoopGuard = stoppedByLoopGuard
        )
    }
}

data class NativeTaskLoopOutcome(
    val finalText: String,
    val toolRounds: Int,
    val steps: Int,
    val failedSteps: Int,
    val taskTrace: JSONArray,
    val evidence: JSONArray,
    val toolTrace: JSONArray,
    val taskLoop: JSONObject,
    val pendingVerification: JSONObject?,
    val stoppedByUserBlocker: JSONObject?,
    val stoppedByLoopGuard: JSONObject?
)
