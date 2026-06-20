package com.mobileagent.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class RecoveryFuse(var failures: Int, var updatedAtMs: Long)
private data class TerminalRuntimeCache(var value: JSONObject, var updatedAtMs: Long)

class NativeAgentCore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mobile-agent-core", Context.MODE_PRIVATE)
    private val runtimeConfig = AgentRuntimeConfig(context)
    private val workspace = MobileWorkspace(context)
    private val plugins = MobilePluginRegistry(context)
    private val memory = MobileMemoryStore(context)
    private val stopRequests = ConcurrentHashMap<String, AtomicBoolean>()
    private val profile = NativeAgentProfile
    private val modelClient = NativeModelClient(
        log = { level, component, message, details -> log(level, component, message, details) },
        saveUsage = { usage -> saveUsage(usage) }
    )

    fun setApiKey(apiKey: String) {
        prefs.edit().putString("api_key", apiKey.trim()).apply()
    }

    fun config(): JSONObject {
        return runtimeConfig.configJson()
    }

    fun permissionMode(): String {
        return runtimeConfig.permissionMode()
    }

    fun setPermissionMode(mode: String) {
        runtimeConfig.setPermissionMode(mode)
    }

    fun setTerminalConfig(enabled: Boolean, baseUrl: String) {
        runtimeConfig.setTerminalConfig(enabled, baseUrl)
    }

    fun setMcpConfig(enabled: Boolean, baseUrl: String, authToken: String = "") {
        val beforeEnabled = runtimeConfig.mcpEnabled()
        val beforeBase = runtimeConfig.mcpBaseUrl()
        val beforeToken = runtimeConfig.mcpAuthToken()
        runtimeConfig.setMcpConfig(enabled, baseUrl, authToken)
        val changed = beforeEnabled != enabled ||
                beforeBase != runtimeConfig.mcpBaseUrl() ||
                beforeToken != runtimeConfig.mcpAuthToken()
        if (changed) {
            mcpSessions.clear()
        }
    }

    fun setMaxToolRounds(value: Int) {
        runtimeConfig.setMaxToolRounds(value)
    }

    fun terminalStatusForUi(): JSONObject {
        return terminalStatus()
    }

    fun terminalHealthForUi(autoRecover: Boolean = false): JSONObject {
        return terminalRuntimeStatus(autoRecover = autoRecover, force = true)
    }

    fun mcpAuthToken(): String {
        return runtimeConfig.mcpAuthToken()
    }

    fun mcpStatusForUi(): JSONObject {
        return mcpStatus()
    }

    fun mcpToolsForUi(search: String = ""): JSONObject {
        return mcpTools(search)
    }

    fun systemLogsForUi(limit: Int = 80): JSONObject {
        return systemLogs(JSONObject().put("limit", limit))
    }

    fun latestFailureAnalysisForUi(maxBytes: Int = 30000): JSONObject {
        return workspace.latestFailureAnalysis(maxBytes)
    }

    fun requestStop(sessionHint: String?): JSONObject {
        val sessionId = sessionHint ?: prefs.getString("current_session_id", null)
            ?: return JSONObject().put("ok", false).put("error", "no active session")
        val request = stopRequests.getOrPut(sessionId) { AtomicBoolean(false) }
        request.set(true)
        AgentEventStore.record(
            "chat_stop_requested",
            "用户要求停止本轮任务",
            JSONObject()
                .put("session_id", sessionId)
                .put("time", System.currentTimeMillis())
        )
        log(
            "info",
            "core",
            "chat stop requested",
            JSONObject()
                .put("session_id", sessionId)
                .put("source", "ui")
        )
        return JSONObject().put("ok", true).put("session_id", sessionId)
    }

    fun compactCurrentSessionForUi(sessionId: String?): JSONObject {
        val activeSessionId = sessionId ?: prefs.getString("current_session_id", null)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "no active session")
        val messages = loadMessages(activeSessionId)
        val result = compactMessagesIfNeeded(activeSessionId, messages, force = true)
        if (result.optBoolean("compacted", false)) {
            saveMessages(activeSessionId, messages)
        }
        return result
            .put("ok", result.optBoolean("compacted", false))
            .put("context", contextStats(messages))
    }

    fun docsIndexForUi(): JSONObject {
        return docsIndex()
    }

    fun eventsForUi(afterSeq: Long = 0L, limit: Int = 50): JSONObject {
        return AgentEventStore.recent(afterSeq, limit)
    }

    fun executeNativeToolForDiagnostics(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean = false
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        AgentEventStore.record(
            "tool_started",
            "开始工具 $name",
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
        val output = executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, sessionId = null)
        AgentEventStore.record(
            "tool_finished",
            "工具 $name: ${toolStepState(output)}",
            JSONObject()
                .put("tool", name)
                .put("diagnostic", true)
                .put("state", toolStepState(output))
                .put("duration_ms", System.currentTimeMillis() - startedAt)
                .put("summary", toolStepSummary(output))
        )
        return output
    }

    fun reconnectForUi(): JSONObject {
        log("info", "core", "manual reconnect requested")
        HostBridgeServer.start(context)
        val before = selfHealthCheck()
        val terminalBefore = before.optJSONObject("terminal") ?: JSONObject()
        var recovery: JSONObject? = null
        if (runtimeConfig.terminalEnabled() && terminalBefore.optString("status") != "ok") {
            recovery = recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", true)
                    .put("open_termux", true)
                    .put("wait_ms", 3000)
            )
        }
        val after = selfHealthCheck()
        return JSONObject()
            .put("ok", after.optBoolean("ok", false))
            .put("action", "reconnect")
            .put("before", before)
            .put("recovery", recovery ?: JSONObject().put("skipped", true))
            .put("after", after)
    }

    fun newSession(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit()
            .putString("current_session_id", id)
            .putString(sessionKey(id), JSONArray().toString())
            .apply()
        return id
    }

    fun status(sessionId: String?): JSONObject {
        syncOfficialDocsOnce()
        val activeSessionId = sessionId ?: prefs.getString("current_session_id", null)
        val activeToolset = resolveToolsetForSession(activeSessionId)
        val messages = if (activeSessionId == null) JSONArray() else loadMessages(activeSessionId)
        return JSONObject()
            .put("ok", true)
            .put("runtime", "android-native")
            .put("model", profile.MODEL)
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("permission_modes", runtimeConfig.permissionModesJson())
            .put("terminal", runtimeConfig.terminalConfigJson())
            .put("terminal_runtime", terminalRuntimeStatus(autoRecover = true, force = false))
            .put("mcp", runtimeConfig.mcpConfigJson())
            .put("mcp_runtime", mcpRuntimeStatus(force = false))
            .put("config", runtimeConfig.configJson())
            .put("tools", JSONArray(activeToolset.toList()))
            .put("tool_registry", NativeToolRegistry.metadata(activeToolset))
            .put(
                "toolset",
                JSONObject()
                    .put("active", JSONArray(activeToolset.toList()))
                    .put("available_groups", NativeToolRegistry.availableGroups())
                    .put("baseline", JSONArray(NativeToolRegistry.baselineTools().toList()))
            )
            .put("docs", MobileAgentDocs.index(context))
            .put(
                "session",
                JSONObject()
                    .put("id", activeSessionId)
                    .put("messages", messages.length())
                    .put("traces", 0)
                    .put("updated_at", prefs.getLong("updated_at", 0L))
            )
            .put("context", contextStats(messages))
            .put("usage", latestUsage(activeSessionId))
            .put("logs", AgentLogStore.summary(context))
            .put("api_key_set", !apiKey().isNullOrBlank())
    }

    fun chat(message: String, requestedSessionId: String?, actionsApproved: Boolean = false): JSONObject {
        syncOfficialDocsOnce()
        AgentEventStore.record(
            "chat_started",
            "收到请求",
            JSONObject()
                .put("session_id", requestedSessionId ?: "")
                .put("actions_approved", actionsApproved)
                .put("message_chars", message.length)
        )
        log(
            "info",
            "chat",
            "chat request",
            JSONObject()
                .put("session_id", requestedSessionId ?: "")
                .put("actions_approved", actionsApproved)
                .put("developer_mode", runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER)
                .put("message_chars", message.length)
        )
        val apiKey = apiKey()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("请先输入 -key sk-... 配置模型 API Key。")
        }

        val sessionId = requestedSessionId ?: prefs.getString("current_session_id", null) ?: newSession()
        prefs.edit().putString("current_session_id", sessionId).apply()
        var enabledTools = resolveToolsetForSession(sessionId)
        val messages = loadMessages(sessionId)
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system").put("content", profile.systemPrompt))
        } else if (
            messages.optJSONObject(0)?.optString("role") == "system" &&
            messages.optJSONObject(0)?.optString("content") != profile.systemPrompt
        ) {
            messages.put(0, JSONObject().put("role", "system").put("content", profile.systemPrompt))
        }
        val compaction = compactMessagesIfNeeded(sessionId, messages)
        if (compaction.optBoolean("compacted", false)) {
            log(
                "info",
                "context",
                "context compacted",
                JSONObject(compaction.toString()).put("session_id", sessionId)
            )
            saveMessages(sessionId, messages)
        }
        val effectiveActionsApproved = actionsApproved || runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER
        val modelUserMessage = if (effectiveActionsApproved) {
            "[APP_CONFIRMATION_APPROVED_FOR_THIS_REQUEST]\nThe user approved this current request in the Android app confirmation dialog. If a requested tool is allowed by permission mode, call it directly and follow its result.\n\n$message"
        } else {
            message
        }
        messages.put(JSONObject().put("role", "user").put("content", modelUserMessage))
        val memoryContext = memory.buildInjectionContext(message)
        if (memoryContext.optBoolean("injected", false)) {
            log(
                "info",
                "memory",
                "memory context selected for prompt injection",
                JSONObject()
                    .put("session_id", sessionId)
                    .put("memory_count", memoryContext.optInt("memory_count"))
                    .put("procedure_count", memoryContext.optInt("procedure_count"))
                    .put("experience_count", memoryContext.optInt("experience_count"))
                    .put("content_chars", memoryContext.optString("content").length)
            )
        }
        clearStopRequest(sessionId)

        val toolTrace = JSONArray()
        val loopTrace = JSONArray()
        val loopEvidence = JSONArray()
        val failurePatterns = mutableMapOf<String, Int>()
        val taskPlan = JSONObject()
            .put("status", "not_started")
            .put("goal", "")
            .put("steps", JSONArray())
            .put("updated_at", 0L)
        val retryBudget = mutableMapOf<String, Int>()
        var toolRounds = 0
        var stepIndex = 0
        var failedSteps = 0
        var stoppedByLoopGuard: JSONObject? = null
        var stoppedByUser: JSONObject? = null
        var pendingVerification: JSONObject? = null
        val maxToolRounds = runtimeConfig.maxToolRounds()
        var modelResponse = NativeModelResponse("", JSONArray())
        if (!isStopRequested(sessionId)) {
            modelResponse = requestModelWithEvents(apiKey, messages, "initial", 0, enabledTools, memoryContext)
        } else {
            stoppedByUser = buildUserStopBlock(sessionId, "before_request")
        }
        while (modelResponse.toolCalls.length() > 0 && toolRounds < maxToolRounds) {
            if (isStopRequested(sessionId)) {
                stoppedByUser = buildUserStopBlock(sessionId, "before_round")
                break
            }
            toolRounds += 1
            val assistant = JSONObject()
                .put("role", "assistant")
                .put("content", modelResponse.content)
                .put("tool_calls", modelResponse.toolCalls)
            messages.put(assistant)

            for (index in 0 until modelResponse.toolCalls.length()) {
                if (isStopRequested(sessionId)) {
                    stoppedByUser = buildUserStopBlock(sessionId, "before_tool")
                    break
                }
                val call = modelResponse.toolCalls.getJSONObject(index)
                val function = call.optJSONObject("function") ?: JSONObject()
                val name = function.optString("name")
                val arguments = parseArguments(function.optString("arguments"))
                stepIndex += 1
                val startedAt = System.currentTimeMillis()
                AgentEventStore.record(
                    "tool_started",
                    "开始工具 $name",
                    JSONObject()
                        .put("tool", name)
                        .put("step", stepIndex)
                        .put("round", toolRounds)
                        .put("arguments", arguments.toString().take(1200))
                )
                val output = executeToolWithAutoRecovery(name, arguments, effectiveActionsApproved, taskPlan, sessionId)
                if (name == "toolset_request") {
                    enabledTools = resolveToolsetForSession(sessionId)
                }
                val verification = output.optJSONObject("verification") ?: verifyToolResult(name, arguments, output)
                val state = toolStepState(output)
                AgentEventStore.record(
                    "tool_finished",
                    "工具 $name: $state",
                    JSONObject()
                        .put("tool", name)
                        .put("state", state)
                        .put("step", stepIndex)
                        .put("round", toolRounds)
                        .put("duration_ms", System.currentTimeMillis() - startedAt)
                        .put("summary", toolStepSummary(output))
                )
                if (state != "success") failedSteps += 1
                if (state != "success") {
                    log(
                        "warn",
                        "tool",
                        "tool step did not succeed",
                        JSONObject()
                            .put("tool", name)
                            .put("state", state)
                            .put("step", stepIndex)
                            .put("round", toolRounds)
                            .put("summary", toolStepSummary(output))
                    )
                }
                val retryKey = "${name}:${arguments.toString().take(160)}"
                val retriesLeft = if (state == "success") {
                    retryBudget.remove(retryKey)
                    2
                } else {
                    val next = (retryBudget[retryKey] ?: 2) - 1
                    retryBudget[retryKey] = next.coerceAtLeast(0)
                    next.coerceAtLeast(0)
                }
                val closedLoop = closedLoopStep(name, arguments, output, state, retriesLeft)
                val stepEvidence = evidenceFromStep(name, arguments, output, verification, closedLoop)
                if (stepEvidence.optBoolean("available", false)) {
                    loopEvidence.put(stepEvidence)
                }
                val verificationDecision = updateVerificationState(
                    pending = pendingVerification,
                    tool = name,
                    arguments = arguments,
                    output = output,
                    state = state,
                    closedLoop = closedLoop,
                    evidence = stepEvidence
                )
                pendingVerification = verificationDecision.optJSONObject("pending")
                val loopStep = JSONObject()
                    .put("step", stepIndex)
                    .put("round", toolRounds)
                    .put("tool_call_index", index)
                    .put("tool", name)
                    .put("state", state)
                    .put("duration_ms", System.currentTimeMillis() - startedAt)
                    .put("recoverable", state != "success")
                    .put("retry_budget_remaining", retriesLeft)
                    .put("verification", verification)
                    .put("closed_loop", closedLoop)
                    .put("evidence", stepEvidence)
                    .put("verification_state", verificationDecision)
                    .put("summary", toolStepSummary(output))
                toolTrace.put(
                    JSONObject()
                        .put("step", stepIndex)
                        .put("round", toolRounds)
                        .put("tool", name)
                        .put("arguments", arguments)
                        .put("output", output)
                        .put("state", state)
                        .put("closed_loop", closedLoop)
                        .put("evidence", stepEvidence)
                        .put("verification_state", verificationDecision)
                        .put("duration_ms", System.currentTimeMillis() - startedAt)
                        .put("created_at", System.currentTimeMillis() / 1000.0)
                )
                loopTrace.put(loopStep)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("name", name)
                        .put("content", toolMessageContent(output, loopStep))
                )
                if (state != "success") {
                    val failureKey = failurePatternKey(name, output)
                    val failuresForPattern = (failurePatterns[failureKey] ?: 0) + 1
                    failurePatterns[failureKey] = failuresForPattern
                    if (failuresForPattern >= 3) {
                        stoppedByLoopGuard = buildLoopGuardStop(
                            name,
                            arguments,
                            output,
                            loopStep,
                            reason = "repeated_failure_pattern"
                        )
                        AgentEventStore.record(
                            "loop_guard_stopped",
                            "任务循环已停止：重复失败模式",
                            stoppedByLoopGuard
                        )
                        break
                    }
                }
                if (state != "success" && retriesLeft <= 0) {
                    stoppedByLoopGuard = buildLoopGuardStop(
                        name,
                        arguments,
                        output,
                        loopStep,
                        reason = "retry_budget_exhausted"
                    )
                    AgentEventStore.record(
                        "loop_guard_stopped",
                        "任务循环已停止：重复失败",
                        stoppedByLoopGuard
                    )
                    break
                }
            }
            if (stoppedByLoopGuard != null) break
            if (stoppedByUser != null) break
            if (isStopRequested(sessionId)) {
                stoppedByUser = buildUserStopBlock(sessionId, "before_next_round")
                break
            }
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        taskLoopRoundHint(
                            round = toolRounds,
                            maxRounds = maxToolRounds,
                            toolCalls = modelResponse.toolCalls.length(),
                            failedSteps = failedSteps,
                            lastLoop = if (loopTrace.length() > 0) loopTrace.optJSONObject(loopTrace.length() - 1) else null
                        )
                    )
            )
            modelResponse = requestModelWithEvents(apiKey, messages, "after_tools", toolRounds, enabledTools, memoryContext)
        }

        val finalText = when {
            stoppedByUser != null -> userStopFinalText(stoppedByUser)
            stoppedByLoopGuard != null -> loopGuardFinalText(stoppedByLoopGuard)
            else -> modelResponse.content.ifBlank {
                if (toolRounds >= maxToolRounds) "已达到工具调用轮数上限。" else ""
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
        val taskLoop = JSONObject()
            .put("status", loopStatus)
            .put("rounds", toolRounds)
            .put("max_rounds", maxToolRounds)
            .put("steps", stepIndex)
            .put("failed_steps", failedSteps)
            .put("plan", JSONObject(taskPlan.toString()))
            .put("trace", loopTrace)
            .put("evidence", loopEvidence)
            .put(
                "completion_review",
                completionReview(
                    status = loopStatus,
                    failedSteps = failedSteps,
                    pendingVerification = pendingVerification,
                    evidence = loopEvidence,
                    finalText = finalText
                )
            )
            .put("stop_requested", isStopRequested(sessionId))
        stoppedByLoopGuard?.let { taskLoop.put("blocker", it) }
        stoppedByUser?.let { taskLoop.put("blocker", it) }
        val runId = UUID.randomUUID().toString()
        val taskRecord = if (stepIndex > 0) {
            persistTaskLoopRun(
                title = taskTitleForRun(taskPlan, message),
                goal = taskGoalForRun(taskPlan, message),
                sessionId = sessionId,
                runId = runId,
                finalText = finalText,
                taskLoop = taskLoop,
                toolTrace = toolTrace
            )
        } else {
            JSONObject().put("skipped", true).put("reason", "no tool steps")
        }
        taskLoop.put("task_record", taskRecord)
        val memoryRecord = recordTaskMemory(
            apiKey = apiKey,
            userMessage = message,
            finalText = finalText,
            taskLoop = taskLoop,
            toolTrace = toolTrace,
            runId = runId
        )
        taskLoop.put("memory_record", memoryRecord)
        messages.put(JSONObject().put("role", "assistant").put("content", finalText))
        saveMessages(sessionId, messages)
        AgentEventStore.record(
            "chat_finished",
            "请求完成",
            JSONObject()
                .put("run_id", runId)
                .put("session_id", sessionId)
                .put("tool_rounds", toolRounds)
                .put("steps", stepIndex)
                .put("failed_steps", failedSteps)
        )
        return JSONObject()
            .put("session_id", sessionId)
            .put("run_id", runId)
            .put("message", finalText)
            .put("tool_trace", toolTrace)
            .put("task_loop", taskLoop)
            .put("tool_rounds", toolRounds)
            .put("context", contextStats(messages))
            .put("context_compaction", compaction)
            .put("memory_context", memoryContext)
            .put("memory_record", memoryRecord)
            .put("usage", latestUsage(sessionId))
    }

    private fun persistTaskLoopRun(
        title: String,
        goal: String,
        sessionId: String,
        runId: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray
    ): JSONObject {
        return runCatching {
            workspace.taskRecordRun(
                title = title,
                goal = goal,
                sessionId = sessionId,
                runId = runId,
                finalMessage = finalText,
                taskLoop = taskLoop,
                toolTrace = toolTrace
            )
        }.onSuccess { record ->
            log(
                "info",
                "task",
                "task loop report persisted",
                JSONObject()
                    .put("run_id", runId)
                    .put("task", record.optJSONObject("task")?.optString("path", ""))
                    .put("reports", record.optJSONObject("reports") ?: JSONObject())
            )
        }.onFailure { error ->
            log(
                "error",
                "task",
                "task loop report persistence failed",
                JSONObject()
                    .put("run_id", runId)
                    .put("error", error.message ?: error.toString())
            )
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("error", error.message ?: error.toString())
        }
    }

    private fun recordTaskMemory(
        apiKey: String,
        userMessage: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray,
        runId: String
    ): JSONObject {
        return runCatching {
            val toolsUsed = JSONArray()
            val appsUsed = JSONArray()
            val toolExperiences = JSONArray()
            val seenTools = linkedSetOf<String>()
            for (index in 0 until toolTrace.length()) {
                val step = toolTrace.optJSONObject(index) ?: continue
                val tool = step.optString("tool")
                if (tool.isNotBlank()) seenTools.add(tool)
                val state = step.optString("state")
                val output = step.optJSONObject("output") ?: JSONObject()
                val summary = toolStepSummary(output).ifBlank { output.toString().take(300) }
                memory.heuristicExperienceFromTool(tool, state, summary, userMessage)?.let { lesson ->
                    val recorded = memory.recordExperience(lesson)
                    toolExperiences.put(recorded)
                }
                val result = output.optJSONObject("result") ?: JSONObject()
                val app = result.optString("package").ifBlank { result.optString("app") }
                if (app.isNotBlank()) appsUsed.put(app)
            }
            seenTools.forEach { toolsUsed.put(it) }
            val taskHistory = memory.recordTask(
                task = userMessage,
                status = taskLoop.optString("status", "unknown"),
                finalAnswer = finalText,
                toolsUsed = toolsUsed,
                appsUsed = appsUsed,
                runId = runId
            )
            val reflection = JSONObject()
                .put("run_id", runId)
                .put("task", userMessage.take(1000))
                .put("status", taskLoop.optString("status"))
                .put("final_answer", finalText.take(2000))
                .put("tools_used", toolsUsed)
                .put("experience_candidates", toolExperiences.length())
            val reflectionRecord = memory.appendReflection(reflection)
            val extracted = extractMemoryWithModel(apiKey, userMessage, finalText, taskLoop, toolTrace)
            JSONObject()
                .put("ok", true)
                .put("task_history", taskHistory)
                .put("tool_experiences", toolExperiences)
                .put("reflection", reflectionRecord)
                .put("model_extraction", extracted)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "automatic memory recording failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject().put("ok", false).put("error", error.message ?: error.toString())
        }
    }

    private fun extractMemoryWithModel(
        apiKey: String,
        userMessage: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray
    ): JSONObject {
        if (toolTrace.length() == 0) return JSONObject().put("ok", true).put("skipped", true).put("reason", "no tool trace")
        return runCatching {
            val traceSummary = summarizeTraceForMemory(toolTrace)
            val prompt = """
                Extract durable memory from this Mobile Agent task.
                Return only a JSON object with optional arrays:
                {
                  "memory": [{"type":"preference|environment|do_not_do|insight","key":"...","value":"...","confidence":"low|medium|high"}],
                  "experiences": [{"app":"general","tool_scope":"windows_mcp|phone|termux|","task_type":"...","lesson_type":"successful_navigation|failed_approach|ui_knowledge|timing|environment|general","description":"...","confidence":"low|medium|high"}],
                  "reflection": {"what_worked":"...","what_failed":"...","next_time_hint":"..."}
                }
                Store only reusable facts or lessons. Do not store secrets verbatim.

                Task: ${userMessage.take(1200)}
                Status: ${taskLoop.optString("status")}
                Final answer: ${finalText.take(1500)}
                Trace:
                $traceSummary
            """.trimIndent()
            val extractionMessages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You extract compact durable memory for a phone-native agent. Output strict JSON only."))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val response = requestModel(apiKey, extractionMessages, setOf("get_time"))
            val parsed = parseJsonObjectFromText(response.content)
            val memoryItems = JSONArray()
            val memories = parsed.optJSONArray("memory") ?: JSONArray()
            for (index in 0 until memories.length()) {
                val item = memories.optJSONObject(index) ?: continue
                val written = memory.writeMemory(
                    JSONObject()
                        .put("type", item.optString("type", "insight"))
                        .put("key", item.optString("key", "task_insight"))
                        .put("value", item.optString("value", item.optString("text", "")))
                        .put("confidence", item.optString("confidence", "medium"))
                        .put("source", "model_extraction")
                )
                memoryItems.put(written)
            }
            val experienceItems = JSONArray()
            val experiences = parsed.optJSONArray("experiences") ?: JSONArray()
            for (index in 0 until experiences.length()) {
                val item = experiences.optJSONObject(index) ?: continue
                val recorded = memory.recordExperience(
                    JSONObject()
                        .put("app", item.optString("app", "general"))
                        .put("tool_scope", item.optString("tool_scope", item.optString("scope", "")))
                        .put("task_type", item.optString("task_type", ""))
                        .put("lesson_type", item.optString("lesson_type", "general"))
                        .put("description", item.optString("description", item.optString("text", "")))
                        .put("source_task", userMessage)
                        .put("confidence", item.optString("confidence", "medium"))
                )
                experienceItems.put(recorded)
            }
            val reflectionObj = parsed.optJSONObject("reflection") ?: JSONObject()
            val reflection = if (reflectionObj.length() > 0) {
                memory.appendReflection(
                    JSONObject(reflectionObj.toString())
                        .put("task", userMessage.take(1000))
                        .put("status", taskLoop.optString("status"))
                        .put("source", "model_extraction")
                )
            } else {
                JSONObject().put("skipped", true)
            }
            JSONObject()
                .put("ok", true)
                .put("memory_written", memoryItems.length())
                .put("experiences_written", experienceItems.length())
                .put("reflection", reflection)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "model memory extraction failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("error_type", error.javaClass.simpleName)
                .put("error", error.message ?: "")
        }
    }

    private fun learningStopWithModel(arguments: JSONObject): JSONObject {
        val stopped = memory.learningStop(arguments)
        if (!stopped.optBoolean("ok", false)) return stopped
        val key = apiKey()
        if (key.isNullOrBlank()) {
            return JSONObject(stopped.toString())
                .put("model_extraction", JSONObject().put("ok", true).put("skipped", true).put("reason", "api_key_missing"))
        }
        val extraction = runCatching {
            val summaryPath = stopped.optString("summary", "")
            val summaryContent = if (summaryPath.isNotBlank()) {
                workspace.read(summaryPath, 20000).optString("content", "")
            } else {
                stopped.toString(2)
            }
            val prompt = """
                Extract reusable Mobile Agent learning from this demo trace.
                Return only JSON:
                {
                  "experiences": [{"app":"...","tool_scope":"phone|windows_mcp|termux|","lesson_type":"successful_navigation|failed_approach|ui_knowledge|timing|environment|general","description":"...","confidence":"low|medium|high"}],
                  "procedures": [{"app":"...","tool_scope":"..."}]
                }
                Keep descriptions reusable and short. Do not store secrets.

                Learning stop result:
                ${stopped.toString(2).take(4000)}

                Demo summary:
                ${summaryContent.take(8000)}
            """.trimIndent()
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You extract compact reusable agent memory from learning traces. Output strict JSON only."))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val response = requestModel(key, messages, setOf("get_time"))
            val parsed = parseJsonObjectFromText(response.content)
            val recorded = JSONArray()
            val experiences = parsed.optJSONArray("experiences") ?: JSONArray()
            for (index in 0 until experiences.length()) {
                val item = experiences.optJSONObject(index) ?: continue
                recorded.put(
                    memory.recordExperience(
                        JSONObject()
                            .put("app", item.optString("app", arguments.optString("app", "unknown")))
                            .put("tool_scope", item.optString("tool_scope", arguments.optString("tool_scope", "phone")))
                            .put("lesson_type", item.optString("lesson_type", "ui_knowledge"))
                            .put("description", item.optString("description", ""))
                            .put("source_task", summaryContent.take(500))
                            .put("confidence", item.optString("confidence", "low"))
                    )
                )
            }
            val procedures = JSONArray()
            val requestedProcedures = parsed.optJSONArray("procedures") ?: JSONArray()
            for (index in 0 until requestedProcedures.length()) {
                val item = requestedProcedures.optJSONObject(index) ?: continue
                procedures.put(
                    memory.procedureGenerate(
                        JSONObject()
                            .put("app", item.optString("app", arguments.optString("app", "unknown")))
                            .put("tool_scope", item.optString("tool_scope", arguments.optString("tool_scope", "phone")))
                    )
                )
            }
            JSONObject()
                .put("ok", true)
                .put("experiences_written", recorded.length())
                .put("procedures_generated", procedures.length())
                .put("experiences", recorded)
                .put("procedures", procedures)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "learning model extraction failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("error_type", error.javaClass.simpleName)
                .put("error", error.message ?: "")
        }
        return JSONObject(stopped.toString()).put("model_extraction", extraction)
    }

    private fun summarizeTraceForMemory(toolTrace: JSONArray): String {
        val lines = mutableListOf<String>()
        for (index in 0 until toolTrace.length()) {
            val step = toolTrace.optJSONObject(index) ?: continue
            val output = step.optJSONObject("output") ?: JSONObject()
            lines.add(
                "- tool=${step.optString("tool")} state=${step.optString("state")} summary=${toolStepSummary(output).take(500)}"
            )
        }
        return lines.takeLast(30).joinToString("\n")
    }

    private fun parseJsonObjectFromText(text: String): JSONObject {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(text.substring(start, end + 1)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun taskTitleForRun(taskPlan: JSONObject, userMessage: String): String {
        val goal = taskPlan.optString("goal", "").trim()
        return goal.ifBlank { userMessage.trim() }.ifBlank { "tool task" }.take(120)
    }

    private fun taskGoalForRun(taskPlan: JSONObject, userMessage: String): String {
        val goal = taskPlan.optString("goal", "").trim()
        return goal.ifBlank { userMessage.trim() }.take(2000)
    }

    private fun toolMessageContent(output: JSONObject, loopStep: JSONObject): String {
        val verificationState = loopStep.optJSONObject("verification_state") ?: JSONObject()
        return JSONObject()
            .put("tool_output", output)
            .put("loop", loopStep)
            .put("next_instruction", nextLoopInstruction(loopStep.optString("state", "failed")))
            .put("task_loop_v2_instruction", verificationState.optString("next_instruction", "Use concrete evidence before finalizing."))
            .toString()
    }

    private fun taskLoopRoundHint(
        round: Int,
        maxRounds: Int,
        toolCalls: Int,
        failedSteps: Int,
        lastLoop: JSONObject?
    ): String {
        val closedLoop = lastLoop?.optJSONObject("closed_loop") ?: JSONObject()
        val closedStatus = closedLoop.optString("status", "")
        val verificationState = lastLoop?.optJSONObject("verification_state") ?: JSONObject()
        val pending = verificationState.optJSONObject("pending")
        val verifyHint = if (closedStatus == "needs_business_verification") {
            "The last phone action returned ok, but the business goal is not automatically verified. Inspect after_observe or call host_observe/host_wait_for_text before claiming completion."
        } else if (pending != null && pending.length() > 0) {
            "A verification item is still open: ${pending.optString("requirement")}. Satisfy it with an observation/read/status tool or report the blocker."
        } else {
            "Use concrete tool output, verification, or observation evidence before finalizing."
        }
        return "Task loop round $round/$maxRounds finished: tool_calls=$toolCalls, failed_steps=$failedSteps. $verifyHint Continue only if more work is needed. If a tool failed, change strategy instead of repeating the same call. If the goal is satisfied, give a concise final report with evidence."
    }

    private fun evidenceFromStep(
        name: String,
        arguments: JSONObject,
        output: JSONObject,
        verification: JSONObject,
        closedLoop: JSONObject
    ): JSONObject {
        val state = toolStepState(output)
        val result = output.optJSONObject("result") ?: JSONObject()
        val verificationOk = verification.optBoolean("ok", false)
        val hasAfterObserve = result.has("after_observe")
        val summary = evidenceSummary(name, output).ifBlank { toolStepSummary(output) }
        val kind = when {
            hasAfterObserve -> "after_observe"
            verification.optBoolean("required", false) && verificationOk -> "automatic_verification"
            isVerificationTool(name, arguments) && state == "success" -> "verification_tool"
            state == "success" && summary.isNotBlank() -> "tool_output"
            else -> ""
        }
        return JSONObject()
            .put("available", kind.isNotBlank())
            .put("kind", kind)
            .put("tool", name)
            .put("summary", summary.take(700))
            .put("verification_status", verification.optString("status", ""))
            .put("closed_loop_status", closedLoop.optString("status", ""))
            .put("timestamp_ms", System.currentTimeMillis())
    }

    private fun evidenceSummary(name: String, output: JSONObject): String {
        val result = output.optJSONObject("result") ?: JSONObject()
        if (name == "mcp_call") {
            val nested = result.optJSONObject("result") ?: JSONObject()
            val structured = nested.optJSONObject("structuredContent")
            val structuredText = structured?.optString("result", "").orEmpty()
            if (structuredText.isNotBlank()) return structuredText.trim().take(700)
            val content = nested.optJSONArray("content")
            if (content != null && content.length() > 0) {
                val text = content.optJSONObject(0)?.optString("text", "").orEmpty()
                if (text.isNotBlank()) return text.trim().take(700)
            }
        }
        val verification = output.optJSONObject("verification")
        val verificationSummary = verification?.optString("summary", "").orEmpty()
        if (verificationSummary.isNotBlank()) return verificationSummary
        return toolStepSummary(output)
    }

    private fun updateVerificationState(
        pending: JSONObject?,
        tool: String,
        arguments: JSONObject,
        output: JSONObject,
        state: String,
        closedLoop: JSONObject,
        evidence: JSONObject
    ): JSONObject {
        val decision = JSONObject()
            .put("previous_pending", pending ?: JSONObject())
            .put("evidence_available", evidence.optBoolean("available", false))
        val clearsPending = pending != null &&
            pending.length() > 0 &&
            state == "success" &&
            (isVerificationTool(tool, arguments) || evidence.optString("kind") == "automatic_verification")
        val nextPending = when {
            clearsPending -> null
            closedLoop.optString("status") == "needs_business_verification" -> JSONObject()
                .put("tool", tool)
                .put("requirement", "verify_business_state_after_phone_action")
                .put("instruction", "Use after_observe or a read-only observation/wait tool to prove the requested screen/business state before finalizing.")
                .put("created_at_ms", System.currentTimeMillis())
            closedLoop.optString("status") == "needs_observation" -> JSONObject()
                .put("tool", tool)
                .put("requirement", "observe_after_phone_action")
                .put("instruction", "Run host_observe, host_wait_for_text, or another read-only observation before deciding whether the action worked.")
                .put("created_at_ms", System.currentTimeMillis())
            state != "success" -> JSONObject()
                .put("tool", tool)
                .put("requirement", "recover_or_report_failed_step")
                .put("instruction", "Change strategy, recover with a different tool/arguments, or report a blocker. Do not claim success.")
                .put("created_at_ms", System.currentTimeMillis())
            else -> pending
        }
        if (nextPending != null && nextPending.length() > 0) decision.put("pending", nextPending)
        decision.put("cleared", clearsPending)
        decision.put(
            "status",
            when {
                clearsPending -> "cleared"
                nextPending != null && nextPending.length() > 0 -> "pending"
                evidence.optBoolean("available", false) -> "satisfied"
                else -> "no_evidence"
            }
        )
        decision.put(
            "summary",
            when {
                clearsPending -> "Previous verification item was satisfied by $tool."
                nextPending != null && nextPending.length() > 0 -> nextPending.optString("requirement")
                evidence.optBoolean("available", false) -> evidence.optString("summary", "").take(240)
                else -> "No concrete evidence captured for this step."
            }
        )
        decision.put("next_instruction", nextPending?.optString("instruction") ?: "No open verification item. Finalize only with evidence.")
        return decision
    }

    private fun completionReview(
        status: String,
        failedSteps: Int,
        pendingVerification: JSONObject?,
        evidence: JSONArray,
        finalText: String
    ): JSONObject {
        val pendingOpen = pendingVerification != null && pendingVerification.length() > 0
        val evidenceCount = evidence.length()
        val ok = status in setOf("completed", "no_tools") && failedSteps == 0 && !pendingOpen
        return JSONObject()
            .put("ok", ok)
            .put("status", if (ok) "verified_or_no_open_loop_items" else "needs_attention")
            .put("loop_status", status)
            .put("failed_steps", failedSteps)
            .put("evidence_count", evidenceCount)
            .put("pending_verification", pendingVerification ?: JSONObject())
            .put("final_answer_chars", finalText.length)
            .put(
                "summary",
                if (ok) {
                    "Loop completed with $evidenceCount evidence item(s), $failedSteps failed step(s), and no open verification item."
                } else {
                    "Loop needs attention: failed_steps=$failedSteps, pending_verification=$pendingOpen, evidence_count=$evidenceCount."
                }
            )
            .put(
                "instruction",
                if (ok) {
                    "Final answer may cite evidence from task_loop.evidence or tool outputs."
                } else {
                    "Do not treat this as fully verified. Inspect pending_verification, failed steps, and task reports before continuing."
                }
            )
    }

    private fun isVerificationTool(name: String, arguments: JSONObject): Boolean {
        if (name in verificationTools) return true
        if (name == "mcp_call") {
            val remote = arguments.optString("tool", "").lowercase()
            return remote in setOf("snapshot", "screenshot", "waitfor", "filesystem", "clipboard")
        }
        return false
    }

    private fun failurePatternKey(name: String, output: JSONObject): String {
        return "$name:${toolStepSummary(output).lowercase().take(160)}"
    }

    private fun nextLoopInstruction(state: String): String {
        return when (state) {
            "success" -> "Use this result and its verification/closed_loop fields as evidence. For phone actions, verify the actual screen/business state from after_observe or another observation before final report."
            "needs_confirmation" -> "Do not claim success. Explain that this step needs a fresh user confirmation or choose a read-only fallback."
            "needs_permission" -> "Do not claim success. Explain the missing permission or choose a lower-permission fallback."
            else -> "Do not claim success. Inspect the error and verification field, choose a fallback, retry with corrected arguments, restore if needed, or report the blocker."
        }
    }

    private fun closedLoopStep(
        name: String,
        arguments: JSONObject,
        output: JSONObject,
        state: String,
        retriesLeft: Int
    ): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val hasAfterObserve = result.has("after_observe")
        val isPhoneAction = name in screenActionTools
        val status = when {
            state != "success" && retriesLeft <= 0 -> "blocked_retry_budget_exhausted"
            state != "success" -> "action_failed_retry_or_change_strategy"
            isPhoneAction && hasAfterObserve -> "needs_business_verification"
            isPhoneAction -> "needs_observation"
            else -> "tool_verified_or_not_required"
        }
        return JSONObject()
            .put("enabled", true)
            .put("tool", name)
            .put("phase", if (isPhoneAction) "act_observe_verify" else "tool_verify")
            .put("status", status)
            .put("phone_action", isPhoneAction)
            .put("arguments", JSONObject(arguments.toString()))
            .put("observation_available", hasAfterObserve)
            .put("retry_budget_remaining", retriesLeft)
            .put("instruction", closedLoopInstruction(status))
    }

    private fun closedLoopInstruction(status: String): String {
        return when (status) {
            "needs_business_verification" -> "Inspect after_observe or run a read-only observation/wait tool. Do not claim the user goal is complete until the observed screen proves it."
            "needs_observation" -> "Run host_observe or another read-only check before deciding whether the step worked."
            "action_failed_retry_or_change_strategy" -> "Retry only with corrected arguments or a different tool. Do not repeat the same failed call blindly."
            "blocked_retry_budget_exhausted" -> "Stop the loop and report the blocker with the latest observation and attempted action."
            else -> "Proceed only with evidence from tool output or verification."
        }
    }

    private fun buildLoopGuardStop(
        name: String,
        arguments: JSONObject,
        output: JSONObject,
        loopStep: JSONObject,
        reason: String = "retry_budget_exhausted"
    ): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        return JSONObject()
            .put("ok", false)
            .put("type", "loop_guard_stop")
            .put("reason", reason)
            .put("tool", name)
            .put("arguments", JSONObject(arguments.toString()))
            .put("state", loopStep.optString("state", "failed"))
            .put("summary", toolStepSummary(output))
            .put("verification", output.optJSONObject("verification") ?: JSONObject())
            .put("latest_observation", result.optJSONObject("after_observe") ?: JSONObject())
            .put("next_actions", JSONArray()
                .put("检查 latest_observation，确认当前屏幕或后端状态。")
                .put("换一个工具或参数重试，不要重复同一个失败调用。")
                .put("如果需要用户介入，明确说明卡住位置和需要用户做什么。")
            )
    }

    private fun isStopRequested(sessionId: String): Boolean {
        return stopRequests[sessionId]?.get() == true
    }

    private fun clearStopRequest(sessionId: String) {
        stopRequests[sessionId]?.set(false)
    }

    private fun buildUserStopBlock(sessionId: String, phase: String, modelResponse: NativeModelResponse? = null): JSONObject {
        val lastText = modelResponse?.content?.ifBlank { "" } ?: ""
        return JSONObject()
            .put("ok", false)
            .put("type", "user_requested_stop")
            .put("session_id", sessionId)
            .put("phase", phase)
            .put("summary", "用户要求停止本次执行，已在 $phase 阶段中断。")
            .put("last_model_output_preview", lastText.take(240))
    }

    private fun userStopFinalText(blocker: JSONObject): String {
        return "已收到停止指令，本次任务已停止。\n" +
            "阶段：${blocker.optString("phase", "")}，请确认要继续的目标后继续发送新消息。"
    }

    private fun loopGuardFinalText(blocker: JSONObject): String {
        return buildString {
            append("我先停下来了，避免继续重复失败。\n\n")
            append("卡住工具：").append(blocker.optString("tool", "-")).append("\n")
            append("失败状态：").append(blocker.optString("state", "-")).append("\n")
            append("原因：").append(blocker.optString("summary", "重试预算耗尽")).append("\n\n")
            append("我已经把最新观察和失败详情写入任务报告。下一步应该先检查当前屏幕/后端状态，再换工具或参数继续。")
        }
    }

    private fun toolStepState(output: JSONObject): String {
        val result = output.optJSONObject("result")
        val verification = output.optJSONObject("verification")
        return when {
            verification?.optBoolean("required", false) == true && !verification.optBoolean("ok", false) -> "failed"
            result?.has("ok") == true && !result.optBoolean("ok", true) -> "failed"
            result?.has("available") == true && !result.optBoolean("available", true) -> "failed"
            output.optBoolean("ok", false) -> "success"
            output.optBoolean("needs_confirmation", false) -> "needs_confirmation"
            output.optBoolean("needs_permission", false) -> "needs_permission"
            else -> "failed"
        }
    }

    private fun toolStepSummary(output: JSONObject): String {
        val result = output.optJSONObject("result")
        val nestedError = result?.optString("error", "")?.takeIf { it.isNotBlank() }
        val error = output.optString("error", "").ifBlank { nestedError.orEmpty() }
        if (error.isNotBlank()) return error.take(240)
        val verification = output.optJSONObject("verification")
        val verificationSummary = verification?.optString("summary", "") ?: ""
        if (verificationSummary.isNotBlank()) return verificationSummary.take(240)
        val stdout = result?.optString("stdout", "")?.ifBlank { output.optString("stdout", "") }
            ?: output.optString("stdout", "")
        if (stdout.isNotBlank()) return stdout.trim().take(240)
        return output.toString().take(240)
    }

    private fun verifyToolResult(name: String, arguments: JSONObject, output: JSONObject): JSONObject {
        output.optJSONObject("verification")?.let { return it }
        if (!output.optBoolean("ok", false)) {
            return JSONObject()
                .put("required", false)
                .put("ok", false)
                .put("status", "skipped_tool_failed")
                .put("summary", "Tool failed before verification.")
        }
        return when (name) {
            "write_file" -> verifyWorkspaceWrite(arguments, output)
            "workspace_restore" -> verifyWorkspaceRestore(output)
            "web_extract", "page_extract" -> verifyWebExtract(output)
            "terminal_run" -> verifyTerminalRun(output)
            "terminal_script" -> verifyTerminalScript(arguments, output)
            "terminal_task_status" -> verifyTerminalTaskStatus(output)
            else -> JSONObject()
                .put("required", false)
                .put("ok", true)
                .put("status", "not_required")
                .put("summary", "No automatic verification required for $name.")
        }
    }

    private fun verifyWorkspaceWrite(arguments: JSONObject, output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val path = result.optString("path", arguments.optString("path"))
        val expected = arguments.optString("content")
        if (path.isBlank()) {
            return verificationFailed("workspace_write", "write_file returned no path")
        }
        val readBack = runCatching { workspace.read(path, (expected.toByteArray(Charsets.UTF_8).size + 16).coerceAtLeast(1024)) }
            .getOrElse { return verificationFailed("workspace_write", "${it.javaClass.simpleName}: ${it.message}") }
        val actual = readBack.optString("content")
        val ok = readBack.optBoolean("ok", false) && !readBack.optBoolean("truncated", false) && actual == expected
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "content_mismatch")
            .put("summary", if (ok) "Verified write_file by reading back $path." else "write_file verification failed for $path.")
            .put("evidence", JSONObject().put("path", path).put("bytes", result.optInt("bytes", -1)).put("read_ok", readBack.optBoolean("ok", false)))
    }

    private fun verifyWorkspaceRestore(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val path = result.optString("path")
        if (path.isBlank()) return verificationFailed("workspace_restore", "workspace_restore returned no path")
        val shouldExist = result.optBoolean("restored_exists", true)
        val readBack = runCatching { workspace.read(path, 1024) }.getOrNull()
        val ok = if (shouldExist) {
            readBack?.optBoolean("ok", false) == true
        } else {
            readBack?.optBoolean("ok", false) != true
        }
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "restore_state_mismatch")
            .put("summary", if (ok) "Verified workspace_restore state for $path." else "workspace_restore verification failed for $path.")
            .put("evidence", JSONObject().put("path", path).put("expected_exists", shouldExist).put("read_ok", readBack?.optBoolean("ok", false) ?: false))
    }

    private fun verifyWebExtract(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val content = result.optString("content")
            .ifBlank { result.optString("markdown") }
            .ifBlank { result.optString("text") }
        val ok = content.trim().length >= 80 || result.optString("title").isNotBlank()
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "empty_content")
            .put(
                "summary",
                if (ok) {
                    "Verified page extraction from ${result.optString("source", "unknown")} with ${content.length} chars."
                } else {
                    "web_extract returned no usable title or content."
                }
            )
            .put(
                "evidence",
                JSONObject()
                    .put("source", result.optString("source", ""))
                    .put("title", result.optString("title", "").take(160))
                    .put("content_chars", content.length)
                    .put("truncated", result.optBoolean("truncated", false))
            )
    }

    private fun verifyTerminalRun(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val timedOut = result.optBoolean("timed_out", false)
        val hasReturnCode = result.has("returncode")
        val returnCode = result.optInt("returncode", -1)
        val ok = !timedOut && hasReturnCode && returnCode == 0
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "command_failed")
            .put("summary", if (ok) "Verified terminal_run returncode=0." else "terminal_run verification failed: returncode=$returnCode timed_out=$timedOut.")
            .put("evidence", JSONObject().put("returncode", returnCode).put("timed_out", timedOut))
    }

    private fun verifyTerminalScript(arguments: JSONObject, output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val wait = arguments.optBoolean("wait", true)
        if (!wait) {
            return JSONObject()
                .put("required", false)
                .put("ok", true)
                .put("status", "deferred")
                .put("summary", "terminal_script is running in background; verify with terminal_task_status.")
                .put("evidence", JSONObject().put("task_id", result.optString("task_id", "")))
        }
        val status = result.optString("status", "")
        val timedOut = result.optBoolean("timed_out", false)
        val hasReturnCode = result.has("returncode")
        val returnCode = result.optInt("returncode", -1)
        val ok = !timedOut && (!hasReturnCode || returnCode == 0) && status.lowercase() !in setOf("failed", "cancelled", "timeout", "timed_out")
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "script_failed")
            .put("summary", if (ok) "Verified terminal_script completion." else "terminal_script verification failed: status=$status returncode=$returnCode timed_out=$timedOut.")
            .put("evidence", JSONObject().put("status", status).put("returncode", returnCode).put("timed_out", timedOut).put("task_id", result.optString("task_id", "")))
    }

    private fun verifyTerminalTaskStatus(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val status = result.optString("status", "")
        val returnCode = result.optInt("returncode", 0)
        val ok = status.lowercase() !in setOf("failed", "cancelled", "timeout", "timed_out") && returnCode == 0
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "task_unhealthy")
            .put("summary", if (ok) "Verified terminal task status." else "terminal task status is unhealthy: status=$status returncode=$returnCode.")
            .put("evidence", JSONObject().put("status", status).put("returncode", returnCode).put("task_id", result.optString("task_id", "")))
    }

    private fun verificationFailed(status: String, summary: String): JSONObject {
        return JSONObject()
            .put("required", true)
            .put("ok", false)
            .put("status", status)
            .put("summary", summary)
    }

    private fun executeToolWithAutoRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        val firstOutput = executeTool(name, arguments, actionsApproved, taskPlan, sessionId)
        firstOutput.put("verification", verifyToolResult(name, arguments, firstOutput))
        recoverVerificationFailure(name, arguments, actionsApproved, taskPlan, sessionId, firstOutput)?.let { return it }
        if (!shouldAutoRecoverTerminal(name, firstOutput, actionsApproved)) {
            return firstOutput
        }
        terminalRecoveryFuseOpen(name)?.let { fuse ->
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
            .put("initial_state", toolStepState(firstOutput))
            .put("initial_output", JSONObject(firstOutput.toString()))

        log(
            "warn",
            "recovery",
            "terminal auto recovery started",
            JSONObject()
                .put("tool", name)
                .put("initial_state", toolStepState(firstOutput))
                .put("summary", toolStepSummary(firstOutput))
        )

        val diagnosis = diagnoseTerminal()
        recovery.put("diagnosis", diagnosis)

        val repaired = if (diagnosis.optString("status") == "ok") {
            JSONObject()
                .put("ok", true)
                .put("skipped", true)
                .put("reason", "terminal backend already reachable during diagnosis")
        } else {
            recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", true)
                    .put("open_termux", false)
                    .put("wait_ms", 2500)
            )
        }
        recovery.put("repair", repaired)

        val afterRepair = repaired.optJSONObject("after") ?: diagnoseTerminal()
        val canRetry = repaired.optBoolean("ok", false) || afterRepair.optString("status") == "ok"
        if (!canRetry) {
            recovery.put("status", "recovery_failed")
            recovery.put("retry_attempted", false)
            recordTerminalRecoveryOutcome(name, false)
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
            .put("retry_state", toolStepState(retryOutput))
            .put("status", if (toolStepState(retryOutput) == "success") "recovered" else "retry_failed")
        recordTerminalRecoveryOutcome(name, toolStepState(retryOutput) == "success")
        log(
            if (toolStepState(retryOutput) == "success") "info" else "error",
            "recovery",
            "terminal auto recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("retry_state", toolStepState(retryOutput))
                .put("status", recovery.optString("status"))
        )
        return JSONObject(retryOutput.toString()).put("auto_recovery", recovery)
    }

    private fun recoverVerificationFailure(
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
            "write_file" -> retryWorkspaceWriteAfterVerificationFailure(
                name,
                arguments,
                actionsApproved,
                taskPlan,
                sessionId,
                firstOutput,
                recovery
            )
            "terminal_run", "terminal_script", "terminal_task_status" -> recoverTerminalVerificationFailure(
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

    private fun retryWorkspaceWriteAfterVerificationFailure(
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
            .put("status", if (toolStepState(retryOutput) == "success") "recovered" else "retry_failed")
        log(
            if (toolStepState(retryOutput) == "success") "info" else "error",
            "recovery",
            "verification recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("status", recovery.optString("status"))
                .put("summary", toolStepSummary(retryOutput))
        )
        return JSONObject(retryOutput.toString()).put("verification_recovery", recovery)
    }

    private fun recoverTerminalVerificationFailure(
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
        if (!NativeToolRegistry.isAutoRecoverable(name)) return null
        terminalRecoveryFuseOpen(name)?.let { fuse ->
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
            val repaired = recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", true)
                    .put("open_termux", false)
                    .put("wait_ms", 2500)
            )
            recovery.put("repair", repaired)
            val afterRepair = repaired.optJSONObject("after") ?: diagnoseTerminal()
            if (!repaired.optBoolean("ok", false) && afterRepair.optString("status") != "ok") {
                recovery
                    .put("status", "recovery_failed")
                    .put("retry_attempted", false)
                recordTerminalRecoveryOutcome(name, false)
                return JSONObject(firstOutput.toString()).put("verification_recovery", recovery)
            }
        }
        val retryOutput = executeTool(name, arguments, actionsApproved, taskPlan, sessionId)
        retryOutput.put("verification", verifyToolResult(name, arguments, retryOutput))
        recovery
            .put("retry_attempted", true)
            .put("retry_verification", retryOutput.optJSONObject("verification") ?: JSONObject())
            .put("status", if (toolStepState(retryOutput) == "success") "recovered" else "retry_failed")
        recordTerminalRecoveryOutcome(name, toolStepState(retryOutput) == "success")
        log(
            if (toolStepState(retryOutput) == "success") "info" else "error",
            "recovery",
            "terminal verification recovery retry finished",
            JSONObject()
                .put("tool", name)
                .put("status", recovery.optString("status"))
                .put("summary", toolStepSummary(retryOutput))
        )
        return JSONObject(retryOutput.toString()).put("verification_recovery", recovery)
    }

    private fun terminalRecoveryFuseOpen(name: String): JSONObject? {
        val now = System.currentTimeMillis()
        return synchronized(terminalRecoveryFuses) {
            val fuse = terminalRecoveryFuses[name] ?: return@synchronized null
            if (now - fuse.updatedAtMs > TERMINAL_RECOVERY_FUSE_WINDOW_MS) {
                terminalRecoveryFuses.remove(name)
                return@synchronized null
            }
            if (fuse.failures < TERMINAL_RECOVERY_FUSE_FAILURES) return@synchronized null
            JSONObject()
                .put("ok", false)
                .put("reason", "terminal recovery circuit is open after repeated failed recovery attempts")
                .put("failures", fuse.failures)
                .put("window_ms", TERMINAL_RECOVERY_FUSE_WINDOW_MS)
                .put("next_actions", JSONArray()
                    .put("Run diagnose_terminal or termux_status to inspect the backend state.")
                    .put("Use recover_terminal_backend manually after fixing Termux or endpoint configuration.")
                    .put("Start a new app process or wait for the fuse window before automatic recovery resumes."))
        }
    }

    private fun recordTerminalRecoveryOutcome(name: String, recovered: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(terminalRecoveryFuses) {
            if (recovered) {
                terminalRecoveryFuses.remove(name)
                return
            }
            val current = terminalRecoveryFuses[name]
            if (current == null || now - current.updatedAtMs > TERMINAL_RECOVERY_FUSE_WINDOW_MS) {
                terminalRecoveryFuses[name] = RecoveryFuse(1, now)
            } else {
                current.failures += 1
                current.updatedAtMs = now
            }
        }
    }

    private fun shouldAutoRecoverTerminal(name: String, output: JSONObject, actionsApproved: Boolean): Boolean {
        if (!actionsApproved) return false
        if (!terminalPowerMode()) return false
        if (!NativeToolRegistry.isAutoRecoverable(name)) return false
        val state = toolStepState(output)
        if (state == "success" || state == "needs_confirmation" || state == "needs_permission") return false
        val result = output.optJSONObject("result")
        val errorText = output.optString("error", "")
            .ifBlank { result?.optString("error", "") ?: "" }
            .lowercase()
        if (errorText.contains("command is required") || errorText.contains("task_id is required")) return false
        return true
    }

    private fun taskPlanUpdate(taskPlan: JSONObject, arguments: JSONObject): JSONObject {
        val goal = arguments.optString("goal", "").trim()
        if (goal.isNotBlank()) taskPlan.put("goal", goal.take(500))
        val status = arguments.optString("status", "").trim()
        if (status.isNotBlank()) taskPlan.put("status", sanitizePlanStatus(status))

        val incomingSteps = arguments.optJSONArray("steps")
        if (incomingSteps != null) {
            val steps = JSONArray()
            for (index in 0 until incomingSteps.length().coerceAtMost(20)) {
                val item = incomingSteps.optJSONObject(index) ?: continue
                val id = item.optString("id", "step-${index + 1}").ifBlank { "step-${index + 1}" }
                val title = item.optString("title", item.optString("task", "")).ifBlank { id }
                steps.put(
                    JSONObject()
                        .put("id", id.take(80))
                        .put("title", title.take(240))
                        .put("status", sanitizeStepStatus(item.optString("status", "pending")))
                        .put("note", item.optString("note", "").take(500))
                        .put("evidence", item.optString("evidence", "").take(500))
                )
            }
            taskPlan.put("steps", steps)
        }

        val stepId = arguments.optString("step_id", "").trim()
        if (stepId.isNotBlank()) {
            val steps = taskPlan.optJSONArray("steps") ?: JSONArray().also { taskPlan.put("steps", it) }
            var found = false
            for (index in 0 until steps.length()) {
                val item = steps.optJSONObject(index) ?: continue
                if (item.optString("id") == stepId) {
                    updatePlanStep(item, arguments)
                    found = true
                    break
                }
            }
            if (!found && steps.length() < 20) {
                val item = JSONObject()
                    .put("id", stepId.take(80))
                    .put("title", arguments.optString("note", stepId).ifBlank { stepId }.take(240))
                    .put("status", sanitizeStepStatus(arguments.optString("step_status", "in_progress")))
                    .put("note", arguments.optString("note", "").take(500))
                    .put("evidence", arguments.optString("evidence", "").take(500))
                steps.put(item)
            }
        }

        taskPlan.put("updated_at", System.currentTimeMillis() / 1000)
        return JSONObject(taskPlan.toString())
    }

    private fun pluginRun(
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
            val output = if (isPluginAllowedTool(tool)) {
                executeToolWithAutoRecovery(tool, stepArgs, actionsApproved, taskPlan, sessionId)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("error", "Plugin workflow cannot call tool: $tool")
                    .put("tool", NativeToolRegistry.descriptor(tool)?.metadata() ?: JSONObject().put("name", tool))
                    .put(
                        "verification",
                        JSONObject()
                            .put("required", false)
                            .put("ok", false)
                            .put("status", "blocked_by_plugin_adapter")
                            .put("summary", "Plugin adapter blocked this tool.")
                    )
            }
            val state = toolStepState(output)
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

    private fun isPluginAllowedTool(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "plugin_run") return false
        if (name.startsWith("plugin_")) return false
        val descriptor = NativeToolRegistry.descriptor(name) ?: return false
        return descriptor.access == NativeToolAccess.READ_ONLY ||
            runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER
    }

    private fun updatePlanStep(item: JSONObject, arguments: JSONObject) {
        val stepStatus = arguments.optString("step_status", "").trim()
        if (stepStatus.isNotBlank()) item.put("status", sanitizeStepStatus(stepStatus))
        val note = arguments.optString("note", "").trim()
        if (note.isNotBlank()) item.put("note", note.take(500))
        val evidence = arguments.optString("evidence", "").trim()
        if (evidence.isNotBlank()) item.put("evidence", evidence.take(500))
    }

    private fun sanitizePlanStatus(value: String): String {
        return when (value.lowercase()) {
            "not_started", "pending", "in_progress", "blocked", "completed", "failed", "cancelled" -> value.lowercase()
            else -> "in_progress"
        }
    }

    private fun sanitizeStepStatus(value: String): String {
        return when (value.lowercase()) {
            "pending", "in_progress", "completed", "failed", "blocked", "skipped", "cancelled" -> value.lowercase()
            else -> "pending"
        }
    }

    private fun requestModel(
        apiKey: String,
        messages: JSONArray,
        enabledTools: Set<String>
    ): NativeModelResponse {
        return modelClient.request(apiKey, messages, enabledTools)
    }

    private fun requestModelWithEvents(
        apiKey: String,
        messages: JSONArray,
        phase: String,
        round: Int,
        enabledTools: Set<String>,
        memoryContext: JSONObject? = null
    ): NativeModelResponse {
        val startedAt = System.currentTimeMillis()
        val requestMessages = messagesWithMemoryContext(messages, memoryContext)
        AgentEventStore.record(
            "model_started",
            if (round > 0) "模型请求第 $round 轮工具结果" else "模型请求中",
            JSONObject()
                .put("phase", phase)
                .put("round", round)
                .put("messages", requestMessages.length())
                .put("saved_messages", messages.length())
                .put("memory_injected", memoryContext?.optBoolean("injected", false) == true)
                .put("memory_count", memoryContext?.optInt("memory_count", 0) ?: 0)
                .put("procedure_count", memoryContext?.optInt("procedure_count", 0) ?: 0)
                .put("experience_count", memoryContext?.optInt("experience_count", 0) ?: 0)
                .put("memory_chars", memoryContext?.optString("content", "")?.length ?: 0)
        )
        return try {
            val response = requestModel(apiKey, requestMessages, enabledTools)
            AgentEventStore.record(
                "model_finished",
                "模型返回，工具调用 ${response.toolCalls.length()} 个",
                JSONObject()
                    .put("phase", phase)
                    .put("round", round)
                    .put("duration_ms", System.currentTimeMillis() - startedAt)
                    .put("tool_calls", response.toolCalls.length())
                    .put("content_chars", response.content.length)
            )
            response
        } catch (exc: Exception) {
            AgentEventStore.record(
                "model_failed",
                "模型请求失败: ${exc.javaClass.simpleName}",
                JSONObject()
                    .put("phase", phase)
                    .put("round", round)
                    .put("duration_ms", System.currentTimeMillis() - startedAt)
                    .put("error_type", exc.javaClass.simpleName)
                    .put("error", exc.message ?: "")
            )
            throw exc
        }
    }

    private fun messagesWithMemoryContext(messages: JSONArray, memoryContext: JSONObject?): JSONArray {
        val content = memoryContext?.optString("content", "")?.trim().orEmpty()
        if (content.isBlank()) return messages
        val copy = JSONArray()
        val first = messages.optJSONObject(0)
        if (first?.optString("role") == "system") {
            copy.put(JSONObject(first.toString()))
            copy.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "[MOBILE_AGENT_RELEVANT_MEMORY_V2]\n$content")
            )
            for (index in 1 until messages.length()) {
                copy.put(JSONObject(messages.getJSONObject(index).toString()))
            }
        } else {
            copy.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "[MOBILE_AGENT_RELEVANT_MEMORY_V2]\n$content")
            )
            for (index in 0 until messages.length()) {
                copy.put(JSONObject(messages.getJSONObject(index).toString()))
            }
        }
        return copy
    }

    private fun executeTool(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        permissionGate(name, actionsApproved)?.let { return it }
        return try {
            val result = when (name) {
                "get_time" -> JSONObject()
                    .put("iso", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("timezone", ZonedDateTime.now().zone.toString())
                "task_plan_update" -> taskPlanUpdate(taskPlan, arguments)
                "task_plan_status" -> JSONObject(taskPlan.toString())
                "task_create" -> workspace.taskCreate(
                    arguments.optString("title"),
                    arguments.optString("goal", "")
                )
                "task_list" -> workspace.taskList(arguments.optInt("limit", 50))
                "task_update" -> workspace.taskUpdate(
                    arguments.optString("task"),
                    arguments.optString("status", ""),
                    arguments.optString("note", "")
                )
                "task_log_append" -> workspace.taskLogAppend(
                    arguments.optString("task"),
                    arguments.optString("name", "task.log"),
                    arguments.optString("content")
                )
                "task_artifact_write" -> workspace.taskArtifactWrite(
                    arguments.optString("task"),
                    arguments.optString("name"),
                    arguments.optString("content"),
                    arguments.optBoolean("overwrite", false)
                )
                "task_reports" -> workspace.taskReports(
                    arguments.optString("task"),
                    arguments.optInt("limit", 50)
                )
                "task_report_read" -> workspace.taskReportRead(
                    arguments.optString("task"),
                    arguments.optString("report"),
                    arguments.optInt("max_bytes", 1_000_000)
                )
                "task_report_summarize" -> workspace.taskReportSummarize(
                    arguments.optString("task"),
                    arguments.optString("report")
                )
                "task_failure_latest" -> workspace.latestFailureAnalysis(
                    arguments.optInt("max_bytes", 20000)
                )
                "toolset_request" -> applyToolsetRequest(sessionId, arguments)
                "tool_registry" -> JSONObject()
                    .put("ok", true)
                    .put("tools", NativeToolRegistry.metadata())
                "docs_index" -> docsIndex()
                "docs_read" -> docsRead(arguments)
                "docs_search" -> docsSearch(arguments)
                "docs_sync" -> docsSync()
                "memory_query" -> memory.query(
                    arguments.optString("question"),
                    arguments.optInt("limit", 5)
                )
                "memory_search" -> memory.searchMemory(
                    arguments.optString("query"),
                    arguments.optInt("limit", 8)
                )
                "memory_summary" -> memory.summary(
                    arguments.optString("query", ""),
                    arguments.optInt("limit", 80)
                )
                "memory_write" -> memory.writeMemory(arguments)
                "experience_search" -> memory.searchExperience(arguments)
                "experience_record" -> memory.recordExperience(arguments)
                "experience_update" -> memory.updateExperience(arguments)
                "experience_delete" -> memory.deleteExperience(arguments)
                "experience_compact" -> memory.compactExperience(arguments)
                "procedure_search" -> memory.procedureSearch(arguments)
                "procedure_generate" -> memory.procedureGenerate(arguments)
                "procedure_read" -> memory.procedureRead(arguments)
                "procedure_list" -> memory.procedureList(arguments)
                "learning_start" -> memory.learningStart(arguments)
                "learning_record" -> memory.learningRecord(arguments)
                "learning_stop" -> learningStopWithModel(arguments)
                "learning_status" -> memory.learningStatus()
                "system_logs" -> systemLogs(arguments)
                "self_health_check" -> selfHealthCheck()
                "diagnose_terminal" -> diagnoseTerminal()
                "web_search" -> webSearch(
                    arguments.optString("query"),
                    arguments.optInt("max_results", 5)
                )
                "web_extract", "page_extract" -> webExtract(arguments, actionsApproved)
                "http_get" -> httpGet(arguments)
                "http_post" -> httpPost(arguments)
                "recover_terminal_backend" -> recoverTerminalBackend(arguments)
                "workspace_info" -> workspace.info()
                "list_files" -> workspace.list(
                    arguments.optString("path", "."),
                    arguments.optInt("max_entries", 100)
                )
                "read_file" -> workspace.read(
                    arguments.optString("path"),
                    arguments.optInt("max_bytes", 20000)
                )
                "write_file" -> workspace.write(
                    arguments.optString("path"),
                    arguments.optString("content"),
                    arguments.optBoolean("overwrite", false)
                )
                "workspace_history" -> workspace.history(
                    arguments.optString("path", ""),
                    arguments.optInt("limit", 50)
                )
                "workspace_restore" -> workspace.restore(arguments.optString("change_id"))
                "plugin_info" -> plugins.info()
                "plugin_list" -> plugins.list(arguments.optBoolean("include_disabled", true))
                "plugin_create" -> plugins.create(
                    arguments.optJSONObject("manifest") ?: JSONObject(),
                    arguments.optBoolean("overwrite", false)
                )
                "plugin_read" -> plugins.read(arguments.optString("id"))
                "plugin_reports" -> plugins.reports(
                    arguments.optString("id"),
                    arguments.optInt("limit", 50)
                )
                "plugin_report_read" -> plugins.readReport(
                    arguments.optString("id"),
                    arguments.optString("report"),
                    arguments.optInt("max_bytes", 200000)
                )
                "plugin_validate" -> plugins.validate(arguments.optString("id"))
                "plugin_test" -> plugins.test(arguments.optString("id"))
                "plugin_run" -> pluginRun(arguments, actionsApproved, taskPlan, sessionId)
                "plugin_set_enabled" -> plugins.setEnabled(
                    arguments.optString("id"),
                    arguments.optBoolean("enabled", true)
                )
                "search_files" -> workspace.search(
                    arguments.optString("query"),
                    arguments.optString("path", "."),
                    arguments.optInt("max_matches", 50),
                    arguments.optInt("max_bytes_per_file", 200000)
                )
                "host_status" -> AccessibilityState.status(context)
                "host_observe" -> AccessibilityState.observe(arguments.optInt("max_nodes", 40))
                "host_screen_dump" -> AccessibilityState.dump(arguments.optInt("max_nodes", 40))
                "host_screen_find" -> AccessibilityState.find(
                    arguments.optString("query"),
                    arguments.optBoolean("contains", true),
                    arguments.optInt("max_nodes", 20)
                )
                "host_current_app" -> AccessibilityState.currentApp()
                "host_open_app" -> openApp(arguments.optString("package"))
                "host_click_text" -> AccessibilityState.clickText(
                    arguments.optString("text"),
                    arguments.optBoolean("contains", true)
                )
                "host_click_view_id" -> AccessibilityState.clickViewId(arguments.optString("view_id"))
                "host_click_index" -> AccessibilityState.clickIndex(arguments.optInt("index", -1))
                "host_long_press_text" -> AccessibilityState.longPressText(
                    arguments.optString("text"),
                    arguments.optBoolean("contains", true)
                )
                "host_long_press_index" -> AccessibilityState.longPressIndex(arguments.optInt("index", -1))
                "host_input_text" -> AccessibilityState.inputText(arguments.optString("text"))
                "host_clear_text" -> AccessibilityState.clearText()
                "host_back" -> AccessibilityState.back()
                "host_home" -> AccessibilityState.home()
                "host_press_key" -> AccessibilityState.pressKey(arguments.optString("key"))
                "host_scroll" -> AccessibilityState.scroll(
                    arguments.optString("direction", "forward"),
                    arguments.optString("text", ""),
                    arguments.optString("view_id", "")
                )
                "host_swipe_coords" -> AccessibilityState.swipeCoords(
                    arguments.optInt("x1"),
                    arguments.optInt("y1"),
                    arguments.optInt("x2"),
                    arguments.optInt("y2"),
                    arguments.optLong("duration_ms", 450L)
                )
                "host_wait_ms" -> AccessibilityState.waitMs(arguments.optLong("ms", 1000L))
                "host_wait_for_text" -> AccessibilityState.waitForText(
                    arguments.optString("text"),
                    arguments.optLong("timeout_ms", 5000L),
                    arguments.optBoolean("contains", true)
                )
                "host_open_url" -> openUrl(arguments.optString("url"))
                "termux_status" -> terminalStatus()
                "termux_tools" -> terminalTools()
                "termux_chat" -> terminalChat(arguments.optString("message"))
                "terminal_run" -> terminalRun(
                    arguments.optString("command"),
                    arguments.optString("cwd", ""),
                    arguments.optInt("timeout", 60)
                )
                "terminal_script" -> terminalScript(arguments)
                "terminal_task_status" -> terminalTaskStatus(
                    arguments.optString("task_id"),
                    arguments.optInt("max_output_chars", 12000)
                )
                "terminal_task_cancel" -> terminalTaskCancel(arguments.optString("task_id"))
                "mcp_status" -> mcpStatus()
                "mcp_tools" -> mcpTools(arguments.optString("search", ""))
                "mcp_call" -> mcpCall(
                    arguments.optString("tool"),
                    arguments.optJSONObject("arguments") ?: JSONObject(),
                    arguments.optInt("timeout_ms", 60000).coerceIn(1_000, 300_000)
                )
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }
            JSONObject().put("ok", true).put("result", result)
        } catch (exc: Exception) {
            log(
                "error",
                "tool",
                "tool execution failed",
                JSONObject()
                    .put("tool", name)
                    .put("error_type", exc.javaClass.simpleName)
                    .put("error", exc.message ?: "")
                    .put("arguments", arguments.toString().take(1000))
            )
            JSONObject().put("ok", false).put("error", "${exc.javaClass.simpleName}: ${exc.message}")
        }
    }

    private fun applyToolsetRequest(sessionId: String?, arguments: JSONObject): JSONObject {
        val resolvedSessionId = sessionId ?: prefs.getString("current_session_id", null)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "no active session for toolset_request")
                .put("toolset", JSONObject().put("active", JSONArray(NativeToolRegistry.baselineTools().toList())))
        val mode = arguments.optString("mode", "set").lowercase().trim()
        val requestedGroups = parseStringSet(arguments.optJSONArray("groups"))
        val requestedTools = parseStringSet(arguments.optJSONArray("tools"))
        val replace = arguments.optBoolean("replace", true)
        val currentTools = resolveToolsetForSession(resolvedSessionId)

        val requestMode = when {
            requestedGroups.isEmpty() && requestedTools.isEmpty() -> "status"
            mode == "query" || mode == "check" || mode == "status" -> "status"
            mode == "add" || mode == "union" -> "add"
            mode == "remove" || mode == "subtract" || mode == "minus" -> "remove"
            mode == "set" || mode == "replace" -> if (replace) "replace" else "merge"
            else -> if (replace) "replace" else "merge"
        }
        val requestedSetFromGroups = if (requestedGroups.isNotEmpty()) {
            NativeToolRegistry.toolsForGroups(requestedGroups)
        } else {
            emptySet<String>()
        }

        val nextTools = when (requestMode) {
            "status" -> currentTools
            "add" -> NativeToolRegistry.normalizeTools(currentTools + requestedSetFromGroups + requestedTools)
            "remove" -> NativeToolRegistry.normalizeTools(currentTools - (requestedSetFromGroups + requestedTools))
            "replace" -> NativeToolRegistry.normalizeTools(requestedSetFromGroups + requestedTools)
            else -> NativeToolRegistry.normalizeTools(currentTools + requestedSetFromGroups + requestedTools)
        }

        if (requestMode != "status") {
            persistToolsetForSession(resolvedSessionId, nextTools)
        }
        return JSONObject()
            .put("ok", true)
            .put("mode", requestMode)
            .put("toolset", JSONObject()
                .put("requested", JSONObject()
                    .put("mode", mode.ifBlank { "set" })
                    .put("groups", jsonArrayFromSet(requestedGroups))
                    .put("tools", jsonArrayFromSet(requestedTools))
                    .put("replace", replace)
                )
                .put("available_groups", NativeToolRegistry.availableGroups())
                .put("active", JSONArray(NativeToolRegistry.normalizeTools(nextTools).toList()))
            )
    }

    private fun docsIndex(): JSONObject {
        return MobileAgentDocs.index(context)
    }

    private fun docsRead(arguments: JSONObject): JSONObject {
        return MobileAgentDocs.read(
            context,
            arguments.optString("path"),
            arguments.optInt("max_bytes", 40000)
        )
    }

    private fun docsSearch(arguments: JSONObject): JSONObject {
        return MobileAgentDocs.search(
            context,
            arguments.optString("query"),
            arguments.optInt("max_matches", 30)
        )
    }

    private fun docsSync(): JSONObject {
        return MobileAgentDocs.syncToWorkspace(context, workspace)
    }

    private fun permissionGate(name: String, actionsApproved: Boolean): JSONObject? {
        val mode = runtimeConfig.permissionMode()
        val descriptor = NativeToolRegistry.descriptor(name)
        val toolMetadata = descriptor?.metadata() ?: JSONObject().put("name", name).put("registered", false)
        val access = descriptor?.access ?: NativeToolAccess.READ_ONLY
        if (mode == AgentRuntimeConfig.MODE_DEVELOPER) return null
        if (access == NativeToolAccess.READ_ONLY) {
            return null
        }
        if (access == NativeToolAccess.SCREEN_ACTION) {
            if (mode == AgentRuntimeConfig.MODE_SAFE) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "当前是安全模式，只允许观察。请在 APP 配置中切换到确认操作或最高权限后再操作手机。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "动作工具需要用户在 APP 弹窗中确认本次请求：$name")
            }
            return null
        }
        if (access == NativeToolAccess.TERMINAL_DELEGATION) {
            if (!terminalPowerMode()) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "终端委托需要最高权限或开发者模式。请在 APP 配置中主动选择。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "终端委托需要用户确认本次请求：$name")
            }
            return null
        }
        if (name in screenActionTools) {
            if (mode == AgentRuntimeConfig.MODE_SAFE) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("error", "当前是安全模式，只允许观察。请在 APP 配置中切换到“确认操作”或“最高权限”后再操作手机。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("error", "动作工具需要用户在 APP 弹窗中确认本次请求：$name")
            }
        }
        if (name in terminalDelegationTools) {
            if (!terminalPowerMode()) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("error", "终端委托需要最高权限或开发者模式。请在 APP 配置中主动选择。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("error", "终端委托需要用户确认本次请求：$name")
            }
        }
        return null
    }

    private fun toolNames(): List<String> {
        return NativeToolRegistry.names()
    }

    private fun toolSchemas(): JSONArray {
        return JSONArray()
            .put(
                toolSchema(
                    "get_time",
                    "Return current local time and timezone.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "task_plan_update",
                    "Create or update the current managed task plan. Use this for multi-step tasks to record goal, step status, evidence, and recovery state before and during tool work.",
                    JSONObject()
                        .put("goal", JSONObject().put("type", "string").put("default", ""))
                        .put("status", JSONObject().put("type", "string").put("default", "in_progress"))
                        .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")))
                        .put("step_id", JSONObject().put("type", "string").put("default", ""))
                        .put("step_status", JSONObject().put("type", "string").put("default", ""))
                        .put("note", JSONObject().put("type", "string").put("default", ""))
                        .put("evidence", JSONObject().put("type", "string").put("default", ""))
                )
            )
            .put(
                toolSchema(
                    "task_plan_status",
                    "Return the current managed task plan with step statuses and evidence.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "system_logs",
                    "Return recent Android core, API, bridge, tool, and recovery logs for diagnosis. Use this when a tool, API call, bridge, or terminal backend fails.",
                    JSONObject()
                        .put("limit", JSONObject().put("type", "integer").put("default", 80))
                        .put("min_level", JSONObject().put("type", "string").put("default", "debug"))
                )
            )
            .put(
                toolSchema(
                    "self_health_check",
                    "Run a local self-check for the Android native core, API key, permission mode, Accessibility service, workspace, and optional Termux terminal backend. Use this before repairing broken tools.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "diagnose_terminal",
                    "Diagnose why terminal tools are unavailable or failing. Classifies config, permission mode, endpoint reachability, backend health, and next repair actions.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "recover_terminal_backend",
                    "Try to recover the optional Termux terminal backend. It enables the local terminal endpoint, optionally opens Termux, and tries Termux RUN_COMMAND to start scripts/start-http-termux.sh. Requires danger mode and user confirmation.",
                    JSONObject()
                        .put("use_run_command", JSONObject().put("type", "boolean").put("default", true))
                        .put("open_termux", JSONObject().put("type", "boolean").put("default", true))
                        .put("wait_ms", JSONObject().put("type", "integer").put("default", 2500))
                )
            )
            .put(
                toolSchema(
                    "workspace_info",
                    "Return the Android APP private workspace root and basic status.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "list_files",
                    "List files under the Android APP private workspace.",
                    JSONObject()
                        .put("path", JSONObject().put("type", "string").put("default", "."))
                        .put("max_entries", JSONObject().put("type", "integer").put("default", 100))
                )
            )
            .put(
                toolSchema(
                    "read_file",
                    "Read a UTF-8 text file under the Android APP private workspace. Path aliases are available through workspace_info.",
                    JSONObject()
                        .put("path", JSONObject().put("type", "string"))
                        .put("max_bytes", JSONObject().put("type", "integer").put("default", 20000)),
                    JSONArray().put("path")
                )
            )
            .put(
                toolSchema(
                    "write_file",
                    "Write a UTF-8 text file under the Android APP private workspace.",
                    JSONObject()
                        .put("path", JSONObject().put("type", "string"))
                        .put("content", JSONObject().put("type", "string"))
                        .put("overwrite", JSONObject().put("type", "boolean").put("default", false)),
                    JSONArray().put("path").put("content")
                )
            )
            .put(
                toolSchema(
                    "search_files",
                    "Search UTF-8 text files under the Android APP private workspace.",
                    JSONObject()
                        .put("query", JSONObject().put("type", "string"))
                        .put("path", JSONObject().put("type", "string").put("default", "."))
                        .put("max_matches", JSONObject().put("type", "integer").put("default", 50))
                        .put("max_bytes_per_file", JSONObject().put("type", "integer").put("default", 200000)),
                    JSONArray().put("query")
                )
            )
            .put(
                toolSchema(
                    "host_status",
                    "Return Android Host App and Accessibility state.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "host_observe",
                    "Observe the current Android foreground app and compact screen node list together.",
                    JSONObject().put(
                        "max_nodes",
                        JSONObject().put("type", "integer").put("default", 40)
                    )
                )
            )
            .put(
                toolSchema(
                    "host_screen_dump",
                    "Return a compact Accessibility screen node list.",
                    JSONObject().put(
                        "max_nodes",
                        JSONObject().put("type", "integer").put("default", 40)
                    )
                )
            )
            .put(
                toolSchema(
                    "host_screen_find",
                    "Find screen nodes by visible text, content description, view id, or class name.",
                    JSONObject()
                        .put("query", JSONObject().put("type", "string"))
                        .put("contains", JSONObject().put("type", "boolean").put("default", true))
                        .put("max_nodes", JSONObject().put("type", "integer").put("default", 20)),
                    JSONArray().put("query")
                )
            )
            .put(
                toolSchema(
                    "host_current_app",
                    "Return the package name and root node summary for the current foreground app.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "host_open_app",
                    "Open an installed Android app by package name.",
                    JSONObject().put("package", JSONObject().put("type", "string")),
                    JSONArray().put("package")
                )
            )
            .put(
                toolSchema(
                    "host_click_text",
                    "Click visible text or content description through the Accessibility backend.",
                    JSONObject()
                        .put("text", JSONObject().put("type", "string"))
                        .put("contains", JSONObject().put("type", "boolean").put("default", true)),
                    JSONArray().put("text")
                )
            )
            .put(
                toolSchema(
                    "host_click_view_id",
                    "Click an Android view resource id through the Accessibility backend.",
                    JSONObject().put("view_id", JSONObject().put("type", "string")),
                    JSONArray().put("view_id")
                )
            )
            .put(
                toolSchema(
                    "host_click_index",
                    "Click a node by index from host_screen_dump through the Accessibility backend.",
                    JSONObject().put("index", JSONObject().put("type", "integer")),
                    JSONArray().put("index")
                )
            )
            .put(
                toolSchema(
                    "host_input_text",
                    "Set text in the focused or first editable field through the Accessibility backend.",
                    JSONObject().put("text", JSONObject().put("type", "string")),
                    JSONArray().put("text")
                )
            )
            .put(
                toolSchema(
                    "host_back",
                    "Perform Android Back through the Accessibility backend.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "host_home",
                    "Perform Android Home through the Accessibility backend.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "host_scroll",
                    "Scroll the current page through the Accessibility backend.",
                    JSONObject()
                        .put("direction", JSONObject().put("type", "string").put("default", "forward"))
                        .put("text", JSONObject().put("type", "string").put("default", ""))
                        .put("view_id", JSONObject().put("type", "string").put("default", ""))
                )
            )
            .put(
                toolSchema(
                    "termux_status",
                    "Return whether the optional Termux terminal tool backend is configured and reachable.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "termux_tools",
                    "List tools exposed by the optional Termux terminal backend.",
                    JSONObject()
                )
            )
            .put(
                toolSchema(
                    "termux_chat",
                    "Delegate a task to the optional Termux agent backend. Requires danger permission mode and user confirmation.",
                    JSONObject().put("message", JSONObject().put("type", "string")),
                    JSONArray().put("message")
                )
            )
            .put(
                toolSchema(
                    "terminal_run",
                    "Run a shell command through the optional Termux terminal backend and return stdout, stderr, return code, cwd, and timeout status. Requires danger mode and user confirmation.",
                    JSONObject()
                        .put("command", JSONObject().put("type", "string"))
                        .put("cwd", JSONObject().put("type", "string").put("default", ""))
                        .put("timeout", JSONObject().put("type", "integer").put("default", 60)),
                    JSONArray().put("command")
                )
            )
            .put(
                toolSchema(
                    "terminal_script",
                    "Write a temporary script into the Termux task workspace, run it, save full stdout/stderr/task metadata, and return folded output plus task id and artifact paths. Prefer this over terminal_run for multi-line scripts, tests, generated code, or tasks with large output. Requires danger mode and user confirmation.",
                    JSONObject()
                        .put("script", JSONObject().put("type", "string"))
                        .put("interpreter", JSONObject().put("type", "string").put("default", "sh"))
                        .put("cwd", JSONObject().put("type", "string").put("default", ""))
                        .put("timeout", JSONObject().put("type", "integer").put("default", 60))
                        .put("wait", JSONObject().put("type", "boolean").put("default", true))
                        .put("max_output_chars", JSONObject().put("type", "integer").put("default", 12000))
                        .put("name", JSONObject().put("type", "string").put("default", "script")),
                    JSONArray().put("script")
                )
            )
            .put(
                toolSchema(
                    "terminal_task_status",
                    "Read a saved or running Termux terminal task by task id. Use this after terminal_script with wait=false, or to inspect saved stdout/stderr artifacts. Requires danger mode and user confirmation.",
                    JSONObject()
                        .put("task_id", JSONObject().put("type", "string"))
                        .put("max_output_chars", JSONObject().put("type", "integer").put("default", 12000)),
                    JSONArray().put("task_id")
                )
            )
            .put(
                toolSchema(
                    "terminal_task_cancel",
                    "Cancel a running Termux terminal task by task id. Use this to interrupt long-running background scripts. Requires danger mode and user confirmation.",
                    JSONObject().put("task_id", JSONObject().put("type", "string")),
                    JSONArray().put("task_id")
                )
            )
    }

    private fun toolSchema(
        name: String,
        description: String,
        properties: JSONObject,
        required: JSONArray = JSONArray()
    ): JSONObject {
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                            .put("additionalProperties", false)
                    )
            )
    }

    private fun parseArguments(raw: String): JSONObject {
        return runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    }

    private fun parseStringSet(array: JSONArray?): Set<String> {
        val values = mutableSetOf<String>()
        if (array == null) return values
        for (index in 0 until array.length()) {
            val item = array.optString(index, "").trim()
            if (item.isNotBlank()) values.add(item.lowercase())
        }
        return values
    }

    private fun jsonArrayFromSet(values: Set<String>): JSONArray {
        val result = JSONArray()
        values.forEach { result.put(it) }
        return result
    }

    private fun terminalStatus(): JSONObject {
        val config = runtimeConfig.terminalConfigJson()
        if (!config.optBoolean("enabled")) {
            return JSONObject()
                .put("available", false)
                .put("configured", false)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("note", "终端工具后端未启用；普通聊天不依赖 Termux。")
        }
        val health = runCatching {
            getJson(terminalUrl("/health"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS)
        }
        val terminal = runCatching {
            getJson(terminalUrl("/terminal/status"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2)
        }
        terminal.onSuccess { terminalStatus ->
            val output = JSONObject()
                .put("available", terminalStatus.optBoolean("available", false))
                .put("configured", true)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("status", if (terminalStatus.optBoolean("available", false)) "ok" else "backend_unavailable")
                .put("backend", terminalStatus)
            health.onSuccess { output.put("health", it) }
            health.onFailure { output.put("health_error", "${it.javaClass.simpleName}: ${it.message}") }
            return output
        }
        health.onSuccess { status ->
            return JSONObject()
                .put("available", false)
                .put("configured", true)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("status", "partial_backend")
                .put("health", status)
                .put("terminal_status_error", terminal.exceptionOrNull()?.message ?: "")
        }
        val exc = terminal.exceptionOrNull() ?: health.exceptionOrNull()
        return JSONObject()
            .put("available", false)
            .put("configured", true)
            .put("mode", "optional_tool_backend")
            .put("config", config)
            .put("status", "offline")
            .put("error", "${exc?.javaClass?.simpleName ?: "Error"}: ${exc?.message ?: "unknown"}")
    }

    private fun terminalRuntimeStatus(autoRecover: Boolean, force: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        synchronized(terminalRuntimeLock) {
            if (!force && terminalRuntimeCache != null && now - terminalRuntimeCache!!.updatedAtMs < TERMINAL_RUNTIME_CACHE_MS) {
                return JSONObject(terminalRuntimeCache!!.value.toString())
            }
            if (terminalRuntimeBusy) {
                return terminalRuntimeCache?.value?.let { cached ->
                    JSONObject(cached.toString()).put("monitor", "busy")
                } ?: terminalRuntimeBase("checking")
            }
            terminalRuntimeBusy = true
        }
        try {
            val status = terminalStatus()
            val runtime = terminalRuntimeFromStatus(status)
            if (runtime.optString("state") == "ok" || runtime.optString("state") == "disabled" || !autoRecover) {
                cacheTerminalRuntime(runtime)
                return runtime
            }
            if (!terminalPowerMode()) {
                val failed = JSONObject(runtime.toString())
                    .put("state", "failed")
                    .put("recovery_attempted", false)
                    .put("reason", "terminal recovery requires danger or developer permission mode")
                cacheTerminalRuntime(failed)
                return failed
            }
            terminalRecoveryFuseOpen("terminal_monitor")?.let { fuse ->
                val circuit = JSONObject(runtime.toString())
                    .put("state", "circuit_open")
                    .put("recovery_attempted", false)
                    .put("fuse", fuse)
                cacheTerminalRuntime(circuit)
                return circuit
            }
            cacheTerminalRuntime(JSONObject(runtime.toString()).put("state", "recovering"))
            val recovery = recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", true)
                    .put("open_termux", false)
                    .put("wait_ms", 2500)
            )
            val after = terminalRuntimeFromStatus(terminalStatus())
            val recovered = after.optString("state") == "ok"
            recordTerminalRecoveryOutcome("terminal_monitor", recovered)
            val output = JSONObject(after.toString())
                .put("state", if (recovered) "recovered" else "failed")
                .put("recovery_attempted", true)
                .put("recovery", recovery)
            cacheTerminalRuntime(output)
            return output
        } finally {
            synchronized(terminalRuntimeLock) {
                terminalRuntimeBusy = false
            }
        }
    }

    private fun terminalRuntimeFromStatus(status: JSONObject): JSONObject {
        val state = when {
            !status.optBoolean("configured", false) -> "disabled"
            status.optBoolean("available", false) -> "ok"
            status.optString("status").isNotBlank() -> status.optString("status")
            else -> "offline"
        }
        return terminalRuntimeBase(state)
            .put("available", status.optBoolean("available", false))
            .put("status", status.optString("status", state))
            .put("detail", status)
    }

    private fun terminalRuntimeBase(state: String): JSONObject {
        return JSONObject()
            .put("state", state)
            .put("configured", runtimeConfig.terminalEnabled())
            .put("base_url", runtimeConfig.terminalBaseUrl())
            .put("checked_at_ms", System.currentTimeMillis())
    }

    private fun cacheTerminalRuntime(value: JSONObject) {
        synchronized(terminalRuntimeLock) {
            terminalRuntimeCache = TerminalRuntimeCache(JSONObject(value.toString()), System.currentTimeMillis())
        }
    }

    private fun systemLogs(arguments: JSONObject): JSONObject {
        val limit = arguments.optInt("limit", 80).coerceIn(1, 200)
        val minLevel = arguments.optString("min_level", "debug")
        return JSONObject()
            .put("ok", true)
            .put("summary", AgentLogStore.summary(context))
            .put("entries", AgentLogStore.recent(context, limit, minLevel))
    }

    private fun selfHealthCheck(): JSONObject {
        val terminalDiagnosis = diagnoseTerminal()
        val accessibility = AccessibilityState.status(context)
        val workspaceInfo = runCatching { workspace.info() }
            .getOrElse { JSONObject().put("ok", false).put("error", "${it.javaClass.simpleName}: ${it.message}") }
        val issues = JSONArray()
        if (apiKey().isNullOrBlank()) issues.put("api_key_missing")
        if (!accessibility.optBoolean("connected", false)) issues.put("accessibility_not_connected")
        if (terminalDiagnosis.optString("status") != "ok") issues.put("terminal_${terminalDiagnosis.optString("status")}")
        return JSONObject()
            .put("ok", issues.length() == 0)
            .put("runtime", "android-native")
            .put("model", profile.MODEL)
            .put("api_key_set", !apiKey().isNullOrBlank())
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("max_tool_rounds", runtimeConfig.maxToolRounds())
            .put("accessibility", accessibility)
            .put("workspace", workspaceInfo)
            .put("terminal", terminalDiagnosis)
            .put("logs", AgentLogStore.summary(context))
            .put("recent_logs", AgentLogStore.recent(context, 20, "warn"))
            .put("issues", issues)
            .put("next_actions", healthNextActions(issues, terminalDiagnosis))
    }

    private fun healthNextActions(issues: JSONArray, terminalDiagnosis: JSONObject): JSONArray {
        val actions = JSONArray()
        for (index in 0 until issues.length()) {
            when (issues.optString(index)) {
                "api_key_missing" -> actions.put("在 APP 输入 -key sk-... 保存模型 API Key")
                "accessibility_not_connected" -> actions.put("打开无障碍设置并启用 Mobile Agent Host")
            }
        }
        val repair = terminalDiagnosis.optJSONArray("repair_actions") ?: JSONArray()
        for (index in 0 until repair.length()) actions.put(repair.optString(index))
        return actions
    }

    private fun diagnoseTerminal(): JSONObject {
        log("info", "terminal", "diagnose terminal requested")
        val config = runtimeConfig.terminalConfigJson()
        val result = JSONObject()
            .put("configured", config.optBoolean("enabled"))
            .put("base_url", config.optString("base_url"))
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("repair_actions", JSONArray())

        if (!runtimeConfig.terminalEnabled()) {
            return result
                .put("ok", false)
                .put("status", "disabled")
                .put("summary", "终端工具后端未在 APP 配置中启用。")
                .put("repair_actions", JSONArray().put("启用终端接口为 http://127.0.0.1:8787"))
        }
        if (!terminalPowerMode()) {
            result.put("repair_actions", result.getJSONArray("repair_actions").put("切换到最高权限 danger 或开发者 developer 后再执行终端工具"))
        }

        val terminalStatus = runCatching {
            getJson(terminalUrl("/terminal/status"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2)
        }
        terminalStatus.onSuccess { status ->
            return result
                .put("ok", status.optBoolean("ok", true))
                .put("status", if (status.optBoolean("available", false)) "ok" else "backend_unavailable")
                .put("summary", "终端后端可达。")
                .put("backend", status)
        }

        val health = runCatching {
            getJson(terminalUrl("/health"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2)
        }
        health.onSuccess { status ->
            return result
                .put("ok", false)
                .put("status", "partial_backend")
                .put("summary", "后端 /health 可达，但 /terminal/status 不可用，可能是后端版本旧或启动脚本未更新。")
                .put("health", status)
                .put("terminal_status_error", terminalStatus.exceptionOrNull()?.message ?: "")
                .put("repair_actions", result.getJSONArray("repair_actions").put("重启 Termux 后端并确认已同步新版 http_server.py"))
        }

        val error = terminalStatus.exceptionOrNull()
        val message = "${error?.javaClass?.simpleName ?: "Error"}: ${error?.message ?: "unknown"}"
        val status = when {
            message.contains("Connection refused", ignoreCase = true) -> "connection_refused"
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) -> "timeout"
            message.contains("No route", ignoreCase = true) -> "network_unreachable"
            else -> "offline"
        }
        return result
            .put("ok", false)
            .put("status", status)
            .put("summary", "终端后端不可达：$message")
            .put("error", message)
            .put(
                "repair_actions",
                result.getJSONArray("repair_actions")
                    .put("尝试 recover_terminal_backend 启动 Termux HTTP 后端")
                    .put("如果恢复失败，打开 Termux 并运行 sh scripts/start-http-termux.sh")
                    .put("确认 Termux 允许外部 RUN_COMMAND：~/.termux/termux.properties 中 allow-external-apps=true")
            )
    }

    private fun webSearch(query: String, maxResults: Int): JSONObject {
        return runCatching {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) {
                throw IllegalArgumentException("query is required")
            }
            val safeMax = maxResults.coerceIn(1, 6)
            val url = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(cleanQuery, "UTF-8")
            val html = getText(url, 12000).take(300000)
            val results = JSONArray()
            val resultRegex = Regex(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val matches = resultRegex.findAll(html).take(safeMax).toList()
            for (match in matches) {
                val rawUrl = htmlDecode(match.groupValues[1])
                val title = cleanHtml(match.groupValues[2]).take(240)
                val finalUrl = extractDuckDuckGoUrl(rawUrl).take(600)
                val start = match.range.last.coerceAtMost(html.length)
                val nextChunk = html.substring(start, (start + 1600).coerceAtMost(html.length))
                val snippet = Regex(
                    "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>|<div[^>]+class=\"result__snippet\"[^>]*>(.*?)</div>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(nextChunk)?.let {
                    cleanHtml(it.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() } ?: "").take(500)
                } ?: ""
                if (title.isNotBlank() && finalUrl.isNotBlank()) {
                    results.put(
                        JSONObject()
                            .put("title", title)
                            .put("url", finalUrl)
                            .put("snippet", snippet)
                    )
                }
            }
            JSONObject()
                .put("ok", results.length() > 0)
                .put("query", cleanQuery)
                .put("provider", "duckduckgo_html")
                .put("results", results)
                .put("error", if (results.length() == 0) "No search results parsed." else "")
        }.getOrElse { error ->
            log(
                "error",
                "web",
                "web search failed",
                JSONObject()
                    .put("query", query.take(200))
                    .put("error_type", error.javaClass.simpleName)
                    .put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("query", query)
                .put("provider", "duckduckgo_html")
                .put("results", JSONArray())
                .put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun webExtract(arguments: JSONObject, actionsApproved: Boolean): JSONObject {
        val url = arguments.optString("url").trim()
        if (url.isBlank()) throw IllegalArgumentException("url is required")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val mode = arguments.optString("mode", "markdown").lowercase().ifBlank { "markdown" }
        val source = arguments.optString("source", "auto").lowercase().ifBlank { "auto" }
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(4096, 1_000_000)
        val attempts = JSONArray()
        var lastResult: JSONObject? = null
        val candidates = when (source) {
            "auto" -> buildList {
                add("direct")
                if (arguments.optBoolean("use_jina", true)) add("jina")
                if (arguments.optBoolean("use_termux", false)) add("termux")
            }
            "direct", "jina", "termux" -> listOf(source)
            else -> throw IllegalArgumentException("source must be one of auto, direct, jina, termux")
        }
        for (candidate in candidates) {
            val attemptedAt = System.currentTimeMillis()
            val extracted = runCatching {
                when (candidate) {
                    "direct" -> directPageExtract(url, mode, maxBytes)
                    "jina" -> jinaPageExtract(url, mode, maxBytes)
                    "termux" -> termuxPageExtract(url, mode, maxBytes, actionsApproved)
                    else -> null
                }
            }
            if (extracted.isSuccess) {
                val result = extracted.getOrNull()
                if (result != null) {
                    lastResult = result
                    attempts.put(
                        JSONObject()
                            .put("source", candidate)
                            .put("ok", result.optBoolean("ok", false))
                            .put("duration_ms", System.currentTimeMillis() - attemptedAt)
                            .put("content_chars", result.optString("content").length)
                            .put("error", result.optString("error", ""))
                    )
                    val content = result.optString("content")
                    if (result.optBoolean("ok", false) && (content.trim().length >= 80 || result.optString("title").isNotBlank())) {
                        return result.put("attempts", attempts)
                    }
                }
            } else {
                val error = extracted.exceptionOrNull()
                attempts.put(
                    JSONObject()
                        .put("source", candidate)
                        .put("ok", false)
                        .put("duration_ms", System.currentTimeMillis() - attemptedAt)
                        .put("error", "${error?.javaClass?.simpleName}: ${error?.message}")
                )
            }
        }
        lastResult?.let {
            return JSONObject(it.toString())
                .put("attempts", attempts)
                .put("error", it.optString("error").ifBlank { "No extraction source returned usable content." })
        }
        return JSONObject()
            .put("ok", false)
            .put("url", url)
            .put("source", source)
            .put("mode", mode)
            .put("attempts", attempts)
            .put("error", "No extraction source returned usable content.")
    }

    private fun directPageExtract(url: String, mode: String, maxBytes: Int): JSONObject {
        val startedAt = System.currentTimeMillis()
        val fetched = webFetchText(url, maxBytes)
        val parsed = pageExtractionFromHtml(url, fetched.optString("final_url", url), fetched.optString("body"), mode, maxBytes)
        return parsed
            .put("ok", parsed.optString("content").isNotBlank())
            .put("source", "direct")
            .put("url", url)
            .put("final_url", fetched.optString("final_url", url))
            .put("status", fetched.optInt("status", 0))
            .put("bytes", fetched.optInt("bytes", 0))
            .put("truncated", fetched.optBoolean("truncated", false) || parsed.optBoolean("truncated", false))
            .put("duration_ms", System.currentTimeMillis() - startedAt)
    }

    private fun jinaPageExtract(url: String, mode: String, maxBytes: Int): JSONObject {
        val startedAt = System.currentTimeMillis()
        val readerUrl = buildJinaReaderUrl(url)
        val fetched = webFetchText(readerUrl, maxBytes)
        val markdown = fetched.optString("body").trim()
        val title = markdown.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trimStart('#')
            ?.trim()
            ?.take(240)
            ?: ""
        val content = pageContentForMode(mode, title, markdown, markdown, JSONArray())
        return JSONObject()
            .put("ok", markdown.isNotBlank())
            .put("source", "jina")
            .put("url", url)
            .put("reader_url", readerUrl)
            .put("final_url", fetched.optString("final_url", readerUrl))
            .put("status", fetched.optInt("status", 0))
            .put("title", title)
            .put("text", compactText(markdown, maxBytes))
            .put("markdown", compactText(markdown, maxBytes))
            .put("links", JSONArray())
            .put("content", content)
            .put("bytes", fetched.optInt("bytes", 0))
            .put("truncated", fetched.optBoolean("truncated", false) || markdown.toByteArray(Charsets.UTF_8).size > maxBytes)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
    }

    private fun termuxPageExtract(url: String, mode: String, maxBytes: Int, actionsApproved: Boolean): JSONObject {
        val permissionMode = runtimeConfig.permissionMode()
        val terminalAllowed = permissionMode == "developer" || (permissionMode == "danger" && actionsApproved)
        if (!terminalAllowed) {
            return JSONObject()
                .put("ok", false)
                .put("source", "termux")
                .put("url", url)
                .put("error", "Termux extraction requires developer mode or danger mode with approved actions.")
        }
        val script = """
import html
import json
import re
import sys
import urllib.request
from html.parser import HTMLParser

url = ${JSONObject.quote(url)}
limit = $maxBytes

class Extractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip = 0
        self.title_mode = False
        self.title = ""
        self.parts = []
        self.links = []
        self.current_href = None
        self.current_link = []
    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in ("script", "style", "noscript"):
            self.skip += 1
        if tag == "title":
            self.title_mode = True
        if tag in ("p", "div", "br", "li", "section", "article", "h1", "h2", "h3", "tr"):
            self.parts.append("\n")
        if tag == "a":
            attrs = dict(attrs)
            self.current_href = attrs.get("href")
            self.current_link = []
    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in ("script", "style", "noscript") and self.skip:
            self.skip -= 1
        if tag == "title":
            self.title_mode = False
        if tag == "a" and self.current_href:
            text = " ".join("".join(self.current_link).split())
            if text:
                self.links.append({"text": text[:160], "url": self.current_href})
            self.current_href = None
            self.current_link = []
    def handle_data(self, data):
        if self.skip:
            return
        if self.title_mode:
            self.title += data
        self.parts.append(data)
        if self.current_href:
            self.current_link.append(data)

req = urllib.request.Request(url, headers={"User-Agent": "MobileAgent/0.1 Termux"})
with urllib.request.urlopen(req, timeout=25) as response:
    raw = response.read(limit + 1)
    final_url = response.geturl()
    status = response.status
truncated = len(raw) > limit
body = raw[:limit].decode("utf-8", "replace")
parser = Extractor()
parser.feed(body)
text = html.unescape(" ".join("\n".join(parser.parts).split()))
title = html.unescape(" ".join(parser.title.split()))[:240]
markdown = ("# " + title + "\n\n" if title else "") + text
print(json.dumps({
    "ok": bool(text or title),
    "source": "termux",
    "url": url,
    "final_url": final_url,
    "status": status,
    "title": title,
    "text": text[:limit],
    "markdown": markdown[:limit],
    "links": parser.links[:40],
    "bytes": len(raw[:limit]),
    "truncated": truncated
}, ensure_ascii=False))
""".trimIndent()
        val terminal = terminalScript(
            JSONObject()
                .put("script", script)
                .put("interpreter", "python")
                .put("timeout", 45)
                .put("wait", true)
                .put("max_output_chars", maxBytes.coerceIn(12000, 50000))
                .put("name", "web-extract")
        )
        val stdout = terminal.optString("stdout")
            .ifBlank { terminal.optJSONObject("output")?.optJSONObject("stdout")?.optString("text") ?: "" }
            .ifBlank { terminal.optJSONObject("result")?.optString("stdout") ?: "" }
            .ifBlank {
                terminal.optJSONObject("result")
                    ?.optJSONObject("output")
                    ?.optJSONObject("stdout")
                    ?.optString("text")
                    ?: ""
            }
        val stderr = terminal.optString("stderr")
            .ifBlank { terminal.optJSONObject("output")?.optJSONObject("stderr")?.optString("text") ?: "" }
            .ifBlank { terminal.optJSONObject("result")?.optString("stderr") ?: "" }
            .ifBlank {
                terminal.optJSONObject("result")
                    ?.optJSONObject("output")
                    ?.optJSONObject("stderr")
                    ?.optString("text")
                    ?: ""
            }
        if (stdout.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("source", "termux")
                .put("url", url)
                .put("terminal", terminal)
                .put("stderr", stderr.take(2000))
                .put("error", if (stderr.isBlank()) "Termux script returned no stdout." else stderr.take(500))
        }
        val parsed = JSONObject(stdout.trim().lineSequence().last())
        val links = parsed.optJSONArray("links") ?: JSONArray()
        val content = pageContentForMode(mode, parsed.optString("title"), parsed.optString("text"), parsed.optString("markdown"), links)
        return parsed
            .put("content", content)
            .put("mode", mode)
            .put("terminal_task_id", terminal.optString("task_id", ""))
    }

    private fun webFetchText(url: String, maxBytes: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 MobileAgent/0.1")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyBytes = readBytesLimited(stream, maxBytes + 1)
        val truncated = bodyBytes.size > maxBytes
        val visibleBytes = if (truncated) bodyBytes.copyOf(maxBytes) else bodyBytes
        val body = visibleBytes.toString(Charsets.UTF_8)
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${body.take(300)}")
        }
        return JSONObject()
            .put("url", url)
            .put("final_url", connection.url.toString())
            .put("status", code)
            .put("bytes", visibleBytes.size)
            .put("truncated", truncated)
            .put("body", body)
    }

    private fun pageExtractionFromHtml(
        requestedUrl: String,
        finalUrl: String,
        html: String,
        mode: String,
        maxBytes: Int
    ): JSONObject {
        val title = extractHtmlTitle(html)
        val links = extractHtmlLinks(finalUrl, html)
        val text = compactText(stripHtmlToText(html), maxBytes)
        val markdown = compactText(markdownFromPage(title, text, links), maxBytes)
        val content = pageContentForMode(mode, title, text, markdown, links)
        return JSONObject()
            .put("url", requestedUrl)
            .put("final_url", finalUrl)
            .put("title", title)
            .put("text", text)
            .put("markdown", markdown)
            .put("links", links)
            .put("content", content)
            .put("mode", mode)
            .put("truncated", markdown.toByteArray(Charsets.UTF_8).size > maxBytes || text.toByteArray(Charsets.UTF_8).size > maxBytes)
    }

    private fun extractHtmlTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return cleanHtml(match?.groupValues?.getOrNull(1) ?: "").take(240)
    }

    private fun extractHtmlLinks(baseUrl: String, html: String): JSONArray {
        val links = JSONArray()
        val seen = mutableSetOf<String>()
        val regex = Regex(
            "<a\\s+[^>]*href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in regex.findAll(html).take(80)) {
            val rawHref = htmlDecode(match.groupValues[2]).trim()
            if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", ignoreCase = true)) continue
            val resolved = runCatching { URL(URL(baseUrl), rawHref).toString() }.getOrDefault(rawHref)
            if (!seen.add(resolved)) continue
            val text = cleanHtml(match.groupValues[3]).take(180)
            links.put(JSONObject().put("text", text).put("url", resolved))
        }
        return links
    }

    private fun stripHtmlToText(html: String): String {
        return html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?i)</(p|div|section|article|header|footer|main|li|h[1-6]|tr|table)>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let { htmlDecode(it) }
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun markdownFromPage(title: String, text: String, links: JSONArray): String {
        val builder = StringBuilder()
        if (title.isNotBlank()) {
            builder.append("# ").append(title).append("\n\n")
        }
        builder.append(text.trim())
        if (links.length() > 0) {
            builder.append("\n\n## Links\n")
            for (index in 0 until links.length().coerceAtMost(20)) {
                val link = links.optJSONObject(index) ?: continue
                val label = link.optString("text").ifBlank { link.optString("url") }.replace("\n", " ").take(120)
                builder.append("- [").append(label.replace("[", "").replace("]", "")).append("](")
                    .append(link.optString("url")).append(")\n")
            }
        }
        return builder.toString().trim()
    }

    private fun pageContentForMode(mode: String, title: String, text: String, markdown: String, links: JSONArray): String {
        return when (mode) {
            "text" -> text
            "json" -> JSONObject()
                .put("title", title)
                .put("text", text)
                .put("markdown", markdown)
                .put("links", links)
                .toString()
            else -> markdown
        }
    }

    private fun compactText(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        return bytes.copyOf(maxBytes).toString(Charsets.UTF_8)
    }

    private fun buildJinaReaderUrl(url: String): String {
        return "https://r.jina.ai/$url"
    }

    private fun httpGet(arguments: JSONObject): JSONObject {
        val url = arguments.optString("url").trim()
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(1024, 500000)
        val headers = arguments.optJSONObject("headers") ?: JSONObject()
        return httpRequest("GET", url, null, "", headers, maxBytes)
    }

    private fun httpPost(arguments: JSONObject): JSONObject {
        val url = arguments.optString("url").trim()
        val body = arguments.optString("body", "")
        val contentType = arguments.optString("content_type", "application/json; charset=utf-8")
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(1024, 500000)
        val headers = arguments.optJSONObject("headers") ?: JSONObject()
        return httpRequest("POST", url, body, contentType, headers, maxBytes)
    }

    private fun httpRequest(
        method: String,
        url: String,
        body: String?,
        contentType: String,
        headers: JSONObject,
        maxBytes: Int
    ): JSONObject {
        if (url.isBlank()) throw IllegalArgumentException("url is required")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val startedAt = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "MobileAgent/0.1 Android")
        val headerNames = headers.keys()
        while (headerNames.hasNext()) {
            val name = headerNames.next()
            if (name.equals("Authorization", ignoreCase = true)) {
                connection.setRequestProperty(name, headers.optString(name))
            } else if (name.isNotBlank()) {
                connection.setRequestProperty(name, headers.optString(name))
            }
        }
        if (method == "POST") {
            connection.doOutput = true
            if (contentType.isNotBlank()) connection.setRequestProperty("Content-Type", contentType)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body ?: "")
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyBytes = readBytesLimited(stream, maxBytes + 1)
        val truncated = bodyBytes.size > maxBytes
        val visibleBytes = if (truncated) bodyBytes.copyOf(maxBytes) else bodyBytes
        val responseBody = visibleBytes.toString(Charsets.UTF_8)
        return JSONObject()
            .put("ok", code in 200..299)
            .put("method", method)
            .put("url", url)
            .put("status", code)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("headers", responseHeaders(connection))
            .put("bytes", visibleBytes.size)
            .put("truncated", truncated)
            .put("body", responseBody)
    }

    private fun readBytesLimited(stream: java.io.InputStream?, limit: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < limit) {
                val read = input.read(buffer, 0, (limit - total).coerceAtMost(buffer.size))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            return output.toByteArray()
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): JSONObject {
        val result = JSONObject()
        connection.headerFields.forEach { (name, values) ->
            if (name != null && values != null) {
                result.put(name, values.joinToString(", "))
            }
        }
        return result
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 Mobile Agent")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}: ${body.take(300)}")
        }
        return body
    }

    private fun extractDuckDuckGoUrl(rawUrl: String): String {
        val marker = "uddg="
        val index = rawUrl.indexOf(marker)
        if (index < 0) return rawUrl
        val encoded = rawUrl.substring(index + marker.length).substringBefore("&")
        return URLDecoder.decode(encoded, "UTF-8")
    }

    private fun cleanHtml(value: String): String {
        return htmlDecode(value.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun htmlDecode(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    private fun mcpEndpoint(): String {
        val base = runtimeConfig.mcpBaseUrl().trim().trimEnd('/')
        return when {
            base.isBlank() -> "${AgentRuntimeConfig.DEFAULT_MCP_BASE_URL}/mcp"
            base.endsWith("/mcp", ignoreCase = true) -> base
            else -> "$base/mcp"
        }
    }

    private fun mcpStatus(): JSONObject {
        if (!runtimeConfig.mcpEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("configured", false)
                .put("mode", "remote_mcp")
                .put("config", runtimeConfig.mcpConfigJson())
                .put("error", "MCP backend is disabled")
        }
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val tools = mcpTools()
            JSONObject()
                .put("available", true)
                .put("configured", true)
                .put("mode", "remote_mcp")
                .put("config", runtimeConfig.mcpConfigJson())
                .put("tool_count", tools.optInt("tool_count", 0))
                .put("tools", tools.optJSONArray("tools") ?: JSONArray())
                .put("duration_ms", System.currentTimeMillis() - startedAt)
        }.getOrElse { error ->
            JSONObject()
                .put("available", false)
                .put("configured", true)
                .put("mode", "remote_mcp")
                .put("config", runtimeConfig.mcpConfigJson())
                .put("duration_ms", System.currentTimeMillis() - startedAt)
                .put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun mcpTools(search: String = ""): JSONObject {
        if (!runtimeConfig.mcpEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "MCP backend is disabled")
                .put("config", runtimeConfig.mcpConfigJson())
        }
        val startedAt = System.currentTimeMillis()
        val endpoint = mcpEndpoint()
        val sessionId = ensureMcpSession(endpoint, AgentRuntimeConfig.MCP_TIMEOUT_MS * 2)
        val response = mcpPostJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "tools/list")
                .put("params", JSONObject())
                .toString(),
            AgentRuntimeConfig.MCP_TIMEOUT_MS * 2,
            sessionId
        )
        val result = parseMcpResult(response.first, "tools/list")
        val allTools = result.optJSONArray("tools") ?: JSONArray()
        val query = search.trim().lowercase()
        val filtered = JSONArray()
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            if (query.isBlank() ||
                item.optString("name").lowercase().contains(query) ||
                item.optString("description").lowercase().contains(query)
            ) {
                filtered.put(item)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("available", true)
            .put("configured", true)
            .put("endpoint", endpoint)
            .put("mode", "remote_mcp")
            .put("tool_count", filtered.length())
            .put("tools", filtered)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("config", runtimeConfig.mcpConfigJson())
    }

    private fun mcpCall(tool: String, arguments: JSONObject, timeoutMs: Int): JSONObject {
        if (!runtimeConfig.mcpEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "MCP backend is disabled")
                .put("config", runtimeConfig.mcpConfigJson())
        }
        val targetTool = tool.trim()
        if (targetTool.isBlank()) {
            throw IllegalArgumentException("tool is required")
        }
        val safeTimeout = timeoutMs.coerceIn(1000, 300000)
        val startedAt = System.currentTimeMillis()
        val endpoint = mcpEndpoint()
        val sessionId = ensureMcpSession(endpoint, safeTimeout)
        val response = mcpPostJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", targetTool)
                        .put("arguments", arguments)
                )
                .toString(),
            safeTimeout,
            sessionId
        )
        val result = parseMcpResult(response.first, "tools/call")
        return JSONObject()
            .put("ok", true)
            .put("tool", targetTool)
            .put("endpoint", endpoint)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("result", result)
            .put("session_id", response.second ?: "")
            .put("config", runtimeConfig.mcpConfigJson())
    }

    private fun mcpRuntimeStatus(force: Boolean): JSONObject {
        val status = if (force) {
            runCatching { mcpStatus() }.getOrElse {
                JSONObject()
                    .put("available", false)
                    .put("error", "${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            mcpStatus()
        }
        return JSONObject()
            .put("configured", runtimeConfig.mcpEnabled())
            .put("endpoint", runtimeConfig.mcpBaseUrl())
            .put("state", if (status.optBoolean("available", false)) "ok" else "offline")
            .put("status", status)
    }

    private fun ensureMcpSession(endpoint: String, timeoutMs: Int): String? {
        val existing = mcpSessions[endpoint]
        if (existing != null && existing.isNotBlank()) {
            return existing
        }
        return runCatching { initializeMcpSession(endpoint, timeoutMs) }.getOrElse {
            if (it.message?.contains("already initialized", ignoreCase = true) == true) {
                mcpSessions[endpoint]
            } else {
                throw it
            }
        }
    }

    private fun initializeMcpSession(endpoint: String, timeoutMs: Int): String? {
        val response = mcpPostJson(
            endpoint,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("capabilities", JSONObject())
                        .put(
                            "clientInfo",
                            JSONObject()
                                .put("name", "mobile-agent")
                                .put("version", "0.1.0")
                        )
                )
                .toString(),
            timeoutMs,
            null
        )
        parseMcpResult(response.first, "initialize")
        val session = response.second ?: mcpSessions[endpoint]
        if (session != null && session.isNotBlank()) {
            mcpSessions[endpoint] = session
        }
        return session
    }

    private fun parseMcpResult(payload: JSONObject, method: String): JSONObject {
        val error = payload.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code")
            val msg = error.optString("message")
            val data = error.optString("data")
            throw IllegalStateException("MCP $method failed: $code $msg $data".trim())
        }
        return payload.optJSONObject("result") ?: JSONObject().put("method", method)
    }

    private fun mcpPostJson(
        url: String,
        body: String,
        timeoutMs: Int,
        mcpSessionId: String?
    ): Pair<JSONObject, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        if (mcpSessionId != null && mcpSessionId.isNotBlank()) {
            connection.setRequestProperty("Mcp-Session-Id", mcpSessionId)
        }
        val auth = runtimeConfig.mcpAuthToken().trim()
        if (auth.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $auth")
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body)
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val responseText = extractMcpJsonPayload(text)
        val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        if (connection.responseCode !in 200..299) {
            val detail = response.toString()
            throw IllegalStateException("HTTP ${connection.responseCode}: $detail")
        }
        return Pair(response, connection.getHeaderField("Mcp-Session-Id"))
    }

    private fun extractMcpJsonPayload(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("event:") && !trimmed.startsWith("data:")) return trimmed
        val dataLines = trimmed
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .toList()
        return dataLines.joinToString("\n").trim()
    }

    private fun recoverTerminalBackend(arguments: JSONObject): JSONObject {
        log(
            "warn",
            "terminal",
            "recover terminal backend requested",
            JSONObject()
                .put("use_run_command", arguments.optBoolean("use_run_command", true))
                .put("open_termux", arguments.optBoolean("open_termux", true))
        )
        val useRunCommand = arguments.optBoolean("use_run_command", true)
        val openTermux = arguments.optBoolean("open_termux", true)
        val waitMs = arguments.optInt("wait_ms", 2500).coerceIn(500, 10000)
        runtimeConfig.setTerminalConfig(true, AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL)

        val before = diagnoseTerminal()
        val actions = JSONArray().put("enabled_terminal_config")
        val errors = JSONArray()

        if (useRunCommand) {
            val started = runCatching { startTermuxHttpWithRunCommand() }
            if (started.isSuccess) {
                actions.put("sent_termux_run_command")
            } else {
                errors.put("${started.exceptionOrNull()?.javaClass?.simpleName}: ${started.exceptionOrNull()?.message}")
            }
        }
        if (openTermux) {
            val opened = runCatching { openApp("com.termux") }
            if (opened.isSuccess) actions.put("opened_termux") else errors.put("open_termux_failed: ${opened.exceptionOrNull()?.message}")
        }

        Thread.sleep(waitMs.toLong())
        val after = diagnoseTerminal()
        return JSONObject()
            .put("ok", after.optString("status") == "ok")
            .put("before", before)
            .put("after", after)
            .put("actions", actions)
            .put("errors", errors)
            .put("note", "如果 RUN_COMMAND 被 Termux 拒绝，需要在 Termux 配置 allow-external-apps=true 后重启 Termux。")
    }

    private fun startTermuxHttpWithRunCommand() {
        val intent = Intent()
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            .putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(
                    "-lc",
                    "cd /sdcard/Download/mobile-agent && sh scripts/start-http-termux.sh"
                )
            )
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/sdcard/Download/mobile-agent")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        context.startService(intent)
    }

    private fun openApp(packageName: String): JSONObject {
        if (packageName.isBlank() || packageName.contains("/") || packageName.contains(" ")) {
            throw IllegalArgumentException("package must be an Android package name")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject()
                .put("ok", false)
                .put("package", packageName)
                .put("error", "No launch intent for package: $packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("action", "open_app")
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 500))
    }

    private fun openUrl(url: String): JSONObject {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            throw IllegalArgumentException("url is required")
        }
        if (!cleanUrl.startsWith("https://", ignoreCase = true) && !cleanUrl.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("url", cleanUrl)
            .put("action", "open_url")
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 800))
    }

    private fun terminalTools(): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        return getJson(terminalUrl("/tools"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS)
            .put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun terminalChat(message: String): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        if (message.isBlank()) {
            throw IllegalArgumentException("message is required")
        }
        return postJsonNoAuth(
            terminalUrl("/chat"),
            JSONObject().put("message", message),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 40
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun terminalRun(command: String, cwd: String, timeout: Int): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        if (command.isBlank()) {
            throw IllegalArgumentException("command is required")
        }
        val safeTimeout = timeout.coerceIn(1, 600)
        val payload = JSONObject()
            .put("command", command)
            .put("timeout", safeTimeout)
        if (cwd.isNotBlank()) {
            payload.put("cwd", cwd)
        }
        return postJsonNoAuth(
            terminalUrl("/terminal/run"),
            payload,
            (safeTimeout * 1000 + AgentRuntimeConfig.TERMINAL_TIMEOUT_MS).coerceAtMost(610000)
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun terminalScript(arguments: JSONObject): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        val script = arguments.optString("script")
        if (script.isBlank()) {
            throw IllegalArgumentException("script is required")
        }
        val safeTimeout = arguments.optInt("timeout", 60).coerceIn(1, 600)
        val payload = JSONObject()
            .put("script", script)
            .put("timeout", safeTimeout)
            .put("interpreter", arguments.optString("interpreter", "sh"))
            .put("wait", arguments.optBoolean("wait", true))
            .put("max_output_chars", arguments.optInt("max_output_chars", 12000).coerceIn(1000, 50000))
            .put("name", arguments.optString("name", "script"))
        val cwd = arguments.optString("cwd", "")
        if (cwd.isNotBlank()) {
            payload.put("cwd", cwd)
        }
        val timeoutMs = if (payload.optBoolean("wait", true)) {
            (safeTimeout * 1000 + AgentRuntimeConfig.TERMINAL_TIMEOUT_MS).coerceAtMost(610000)
        } else {
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2
        }
        return postJsonNoAuth(
            terminalUrl("/terminal/script"),
            payload,
            timeoutMs
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun terminalTaskStatus(taskId: String, maxOutputChars: Int): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        if (taskId.isBlank()) {
            throw IllegalArgumentException("task_id is required")
        }
        val result = getJson(
            terminalUrl("/terminal/tasks/${encodePathSegment(taskId)}"),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 4
        )
        return result
            .put("endpoint", runtimeConfig.terminalBaseUrl())
            .put("requested_max_output_chars", maxOutputChars.coerceIn(1000, 50000))
    }

    private fun terminalTaskCancel(taskId: String): JSONObject {
        if (!runtimeConfig.terminalEnabled()) {
            return JSONObject()
                .put("available", false)
                .put("error", "终端工具后端未启用。")
                .put("config", runtimeConfig.terminalConfigJson())
        }
        if (taskId.isBlank()) {
            throw IllegalArgumentException("task_id is required")
        }
        return postJsonNoAuth(
            terminalUrl("/terminal/tasks/${encodePathSegment(taskId)}/cancel"),
            JSONObject(),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 4
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun terminalUrl(path: String): String {
        val base = runtimeConfig.terminalBaseUrl().trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return base + suffix
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun getJson(url: String, timeoutMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(json.optString("error", "HTTP ${connection.responseCode}"))
        }
        return json
    }

    private fun postJsonNoAuth(url: String, payload: JSONObject, timeoutMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(json.optString("error", "HTTP ${connection.responseCode}"))
        }
        return json
    }

    private fun contextStats(messages: JSONArray): JSONObject {
        var estimated = 0
        val byRole = JSONObject()
        var compacted = false
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            val role = item.optString("role", "unknown")
            val content = item.optString("content", "")
            if (content.startsWith(CONTEXT_COMPACTION_MARKER)) compacted = true
            val tokens = estimateTokens(content)
            estimated += tokens
            byRole.put(role, byRole.optInt(role, 0) + tokens)
        }
        return JSONObject()
            .put("messages", messages.length())
            .put("estimated_tokens", estimated)
            .put("model_window", profile.MODEL_WINDOW_TOKENS)
            .put(
                "window_used_percent",
                if (estimated == 0) 0.0 else estimated * 100.0 / profile.MODEL_WINDOW_TOKENS.toDouble()
            )
            .put("compacted", compacted)
            .put("compactions", prefs.getInt("context_compactions", 0))
            .put("auto_compact_token_threshold", CONTEXT_COMPACT_TOKEN_THRESHOLD)
            .put("auto_compact_policy", "token_only")
            .put("by_role", byRole)
    }

    private fun compactMessagesIfNeeded(sessionId: String, messages: JSONArray, force: Boolean = false): JSONObject {
        val beforeStats = contextStats(messages)
        val beforeMessages = messages.length()
        val beforeTokens = beforeStats.optInt("estimated_tokens", 0)
        if (!force && beforeTokens <= CONTEXT_COMPACT_TOKEN_THRESHOLD) {
            return JSONObject()
                .put("compacted", false)
                .put("reason", "below_threshold")
                .put("messages", beforeMessages)
                .put("estimated_tokens", beforeTokens)
        }
        if (messages.length() <= CONTEXT_KEEP_RECENT_MESSAGES + 2) {
            return JSONObject()
                .put("compacted", false)
                .put("reason", "not_enough_history")
                .put("messages", beforeMessages)
                .put("estimated_tokens", beforeTokens)
        }

        val keepFirstSystem = messages.optJSONObject(0)?.optString("role") == "system"
        var tailStart = (messages.length() - CONTEXT_KEEP_RECENT_MESSAGES).coerceAtLeast(if (keepFirstSystem) 1 else 0)
        while (tailStart < messages.length() && messages.optJSONObject(tailStart)?.optString("role") == "tool") {
            tailStart += 1
        }
        if (tailStart >= messages.length() - 1) {
            tailStart = (messages.length() - 8).coerceAtLeast(if (keepFirstSystem) 1 else 0)
        }

        val older = JSONArray()
        val olderStart = if (keepFirstSystem) 1 else 0
        for (index in olderStart until tailStart) {
            older.put(messages.getJSONObject(index))
        }
        val summary = buildContextCompactionSummary(older, beforeMessages, beforeTokens)
        val compacted = JSONArray()
        if (keepFirstSystem) {
            compacted.put(JSONObject(messages.getJSONObject(0).toString()))
        }
        compacted.put(JSONObject().put("role", "system").put("content", summary))
        for (index in tailStart until messages.length()) {
            compacted.put(messages.getJSONObject(index))
        }

        while (messages.length() > 0) {
            messages.remove(0)
        }
        for (index in 0 until compacted.length()) {
            messages.put(compacted.getJSONObject(index))
        }
        val afterStats = contextStats(messages)
        val compactions = prefs.getInt("context_compactions", 0) + 1
        prefs.edit()
            .putInt("context_compactions", compactions)
            .putLong("context_last_compacted_at", System.currentTimeMillis())
            .apply()
        return JSONObject()
            .put("compacted", true)
            .put("session_id", sessionId)
            .put("before_messages", beforeMessages)
            .put("after_messages", messages.length())
            .put("before_estimated_tokens", beforeTokens)
            .put("after_estimated_tokens", afterStats.optInt("estimated_tokens", 0))
            .put("kept_recent_messages", messages.length() - 2)
            .put("compactions", compactions)
    }

    private fun buildContextCompactionSummary(older: JSONArray, beforeMessages: Int, beforeTokens: Int): String {
        val users = mutableListOf<String>()
        val assistants = mutableListOf<String>()
        val tools = mutableListOf<String>()
        var previousSummary = ""
        for (index in 0 until older.length()) {
            val item = older.optJSONObject(index) ?: continue
            val role = item.optString("role")
            val content = item.optString("content", "").trim()
            if (content.startsWith(CONTEXT_COMPACTION_MARKER)) {
                previousSummary = content.take(2500)
                continue
            }
            when (role) {
                "user" -> users.add(content.take(220))
                "assistant" -> assistants.add(content.take(220))
                "tool" -> tools.add(toolSummaryForCompaction(item).take(260))
            }
        }
        val builder = StringBuilder()
        builder.append(CONTEXT_COMPACTION_MARKER).append("\n")
        builder.append("这是自动压缩后的旧会话摘要，用于减少后续模型输入。回答时优先相信最近未压缩消息；本摘要只保留旧上下文的任务线索和重要证据。\n")
        builder.append("压缩前消息数：").append(beforeMessages).append("；估算上下文：").append(beforeTokens).append(" tokens。\n")
        if (previousSummary.isNotBlank()) {
            builder.append("\n上一次摘要：\n").append(previousSummary.removePrefix(CONTEXT_COMPACTION_MARKER).trim().take(1800)).append("\n")
        }
        appendCompactionSection(builder, "旧用户需求", users.takeLast(8))
        appendCompactionSection(builder, "旧助手结论", assistants.takeLast(8))
        appendCompactionSection(builder, "旧工具证据", tools.takeLast(10))
        return builder.toString().take(CONTEXT_COMPACT_SUMMARY_CHARS)
    }

    private fun appendCompactionSection(builder: StringBuilder, title: String, items: List<String>) {
        if (items.isEmpty()) return
        builder.append("\n").append(title).append("：\n")
        items.forEach { item ->
            val clean = item.replace(Regex("\\s+"), " ").trim()
            if (clean.isNotBlank()) builder.append("- ").append(clean).append("\n")
        }
    }

    private fun toolSummaryForCompaction(item: JSONObject): String {
        val name = item.optString("name", "tool")
        val content = item.optString("content", "")
        val parsed = runCatching { JSONObject(content) }.getOrNull()
        val loop = parsed?.optJSONObject("loop")
        val state = loop?.optString("state", "") ?: ""
        val summary = loop?.optString("summary", "") ?: content
        return "$name $state $summary"
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun saveUsage(usage: JSONObject) {
        val sessionId = prefs.getString("current_session_id", null) ?: "default"
        val cache = cacheUsageTokens(usage)
        val cached = cache.first
        val miss = cache.second
        val total = usage.optLong("prompt_tokens", cached + miss)
        prefs.edit()
            .putString(usageLatestKey(sessionId), usage.toString())
            .putLong(usagePromptKey(sessionId), prefs.getLong(usagePromptKey(sessionId), 0L) + total)
            .putLong(usageHitKey(sessionId), prefs.getLong(usageHitKey(sessionId), 0L) + cached)
            .putLong(usageMissKey(sessionId), prefs.getLong(usageMissKey(sessionId), 0L) + miss)
            .apply()
    }

    private fun latestUsage(sessionId: String?): JSONObject {
        val keySessionId = sessionId ?: prefs.getString("current_session_id", null) ?: "default"
        val usage = runCatching { JSONObject(prefs.getString(usageLatestKey(keySessionId), "{}") ?: "{}") }.getOrDefault(JSONObject())
        val cache = cacheUsageTokens(usage)
        val cached = cache.first
        val miss = cache.second
        if (cached > 0 || miss > 0) {
            usage.put("prompt_cache_hit_tokens", cached)
            usage.put("prompt_cache_miss_tokens", miss)
            usage.put("cache_hit_rate", cached.toDouble() / (cached + miss).coerceAtLeast(1))
        }
        val sessionCached = prefs.getLong(usageHitKey(keySessionId), 0L)
        val sessionMiss = prefs.getLong(usageMissKey(keySessionId), 0L)
        val sessionDenominator = (sessionCached + sessionMiss).coerceAtLeast(1L)
        usage.put(
            "session",
            JSONObject()
                .put("session_id", keySessionId)
                .put("prompt_tokens", prefs.getLong(usagePromptKey(keySessionId), 0L))
                .put("prompt_cache_hit_tokens", sessionCached)
                .put("prompt_cache_miss_tokens", sessionMiss)
                .put("cache_hit_rate", sessionCached.toDouble() / sessionDenominator.toDouble())
        )
        usage.put("scope", "latest_and_session")
        return usage
    }

    private fun cacheUsageTokens(usage: JSONObject): Pair<Long, Long> {
        val directHit = usage.optLong("prompt_cache_hit_tokens", -1L)
        val directMiss = usage.optLong("prompt_cache_miss_tokens", -1L)
        if (directHit >= 0L || directMiss >= 0L) {
            return directHit.coerceAtLeast(0L) to directMiss.coerceAtLeast(0L)
        }
        val details = usage.optJSONObject("prompt_tokens_details")
        val cached = details?.optLong("cached_tokens", 0L) ?: 0L
        val total = usage.optLong("prompt_tokens", 0L)
        return cached to (total - cached).coerceAtLeast(0L)
    }

    private fun terminalPowerMode(): Boolean {
        val mode = runtimeConfig.permissionMode()
        return mode == AgentRuntimeConfig.MODE_DANGER || mode == AgentRuntimeConfig.MODE_DEVELOPER
    }

    private fun apiKey(): String? {
        return prefs.getString("api_key", null)
    }

    private fun loadMessages(sessionId: String): JSONArray {
        return runCatching { JSONArray(prefs.getString(sessionKey(sessionId), "[]")) }.getOrDefault(JSONArray())
    }

    private fun resolveToolsetForSession(sessionId: String?): Set<String> {
        if (sessionId == null) return NativeToolRegistry.baselineTools()
        val raw = prefs.getString(toolsetSessionKey(sessionId), null)
        if (raw.isNullOrBlank()) return NativeToolRegistry.baselineTools()
        val stored = runCatching {
            val array = JSONArray(raw)
            val values = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.optString(index, "").trim()
                if (item.isNotBlank()) values.add(item)
            }
            values
        }.getOrNull()
        return NativeToolRegistry.normalizeTools(stored ?: emptySet())
    }

    private fun persistToolsetForSession(sessionId: String, toolset: Set<String>) {
        val normalized = NativeToolRegistry.normalizeTools(toolset).toList()
        prefs.edit().putString(toolsetSessionKey(sessionId), JSONArray(normalized).toString()).apply()
    }

    private fun saveMessages(sessionId: String, messages: JSONArray) {
        prefs.edit()
            .putString(sessionKey(sessionId), messages.toString())
            .putString("current_session_id", sessionId)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun sessionKey(sessionId: String): String {
        return "session_$sessionId"
    }

    private fun toolsetSessionKey(sessionId: String): String = "toolset_$sessionId"

    private fun usageLatestKey(sessionId: String): String = "usage_latest_$sessionId"
    private fun usagePromptKey(sessionId: String): String = "usage_prompt_tokens_$sessionId"
    private fun usageHitKey(sessionId: String): String = "usage_cache_hit_$sessionId"
    private fun usageMissKey(sessionId: String): String = "usage_cache_miss_$sessionId"

    private fun syncOfficialDocsOnce() {
        if (officialDocsSynced) return
        synchronized(NativeAgentCore::class.java) {
            if (officialDocsSynced) return
            val result = runCatching { MobileAgentDocs.syncToWorkspace(context, workspace) }
            result.onSuccess {
                log(
                    "info",
                    "docs",
                    "official docs synced",
                    JSONObject()
                        .put("written_count", it.optInt("written_count", 0))
                        .put("root", it.optString("root", "docs/official"))
                )
            }.onFailure {
                log(
                    "warn",
                    "docs",
                    "official docs sync failed",
                    JSONObject()
                        .put("error_type", it.javaClass.simpleName)
                        .put("error", it.message ?: "")
                )
            }
            officialDocsSynced = true
        }
    }

    private fun log(level: String, component: String, message: String, details: JSONObject? = null) {
        AgentLogStore.record(context, level, component, message, details)
    }

    companion object {
        @Volatile
        private var officialDocsSynced = false

        private const val TERMINAL_RECOVERY_FUSE_FAILURES = 2
        private const val TERMINAL_RECOVERY_FUSE_WINDOW_MS = 10 * 60 * 1000L
        private const val TERMINAL_RUNTIME_CACHE_MS = 10_000L
        private const val CONTEXT_COMPACTION_MARKER = "[MOBILE_AGENT_CONTEXT_COMPACTION_V1]"
        private const val CONTEXT_COMPACT_TOKEN_THRESHOLD = 500_000
        private const val CONTEXT_KEEP_RECENT_MESSAGES = 40
        private const val CONTEXT_COMPACT_SUMMARY_CHARS = 8_000
        private val terminalRecoveryFuses = mutableMapOf<String, RecoveryFuse>()
        private val terminalRuntimeLock = Any()
        private var terminalRuntimeBusy = false
        private var terminalRuntimeCache: TerminalRuntimeCache? = null
        private val mcpSessions = ConcurrentHashMap<String, String>()

        private val screenActionTools = setOf(
            "host_click_text",
            "host_click_view_id",
            "host_click_index",
            "host_long_press_text",
            "host_long_press_index",
            "host_input_text",
            "host_clear_text",
            "host_back",
            "host_home",
            "host_press_key",
            "host_scroll",
            "host_swipe_coords",
            "host_open_app",
            "host_open_url"
        )
        private val terminalDelegationTools = setOf(
            "termux_chat",
            "terminal_run",
            "terminal_script",
            "terminal_task_status",
            "terminal_task_cancel",
            "recover_terminal_backend"
        )
        private val verificationTools = setOf(
            "host_observe",
            "host_screen_dump",
            "host_screen_find",
            "host_current_app",
            "host_wait_for_text",
            "mcp_status",
            "mcp_tools",
            "task_plan_status",
            "task_report_read",
            "task_failure_latest",
            "workspace_info",
            "list_files",
            "read_file",
            "search_files",
            "docs_read",
            "docs_search",
            "memory_search",
            "experience_search",
            "procedure_search",
            "procedure_read",
            "terminal_task_status"
        )
        private val terminalAutoRecoverTools = setOf(
            "termux_status",
            "termux_tools",
            "termux_chat",
            "terminal_run",
            "terminal_script",
            "terminal_task_status",
            "terminal_task_cancel"
        )
    }
}
