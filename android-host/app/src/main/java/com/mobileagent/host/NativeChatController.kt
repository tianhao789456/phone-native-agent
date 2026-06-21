package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NativeChatController(
    private val runtimeConfig: AgentRuntimeConfig,
    private val systemPrompt: String,
    private val toolsets: NativeToolsetController,
    private val memory: MobileMemoryStore,
    private val contextManager: NativeContextManager,
    private val taskLoopEngine: NativeTaskLoopEngine,
    private val taskMemoryCoordinator: NativeTaskMemoryCoordinator,
    private val stopFlowController: NativeStopFlowController,
    private val sessionStore: NativeSessionStore,
    private val apiKey: () -> String?,
    private val syncOfficialDocsOnce: () -> Unit,
    private val normalizeVisibleChinese: (String) -> String,
    private val log: (String, String, String, JSONObject?) -> Unit
) {
    fun chat(message: String, requestedSessionId: String?, actionsApproved: Boolean = false): JSONObject {
        syncOfficialDocsOnce()
        AgentEventStore.record(
            "chat_started",
            "开始处理用户消息",
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
            ?: throw IllegalStateException("请先设置 API Key，例如 sk-...")

        val sessionId = requestedSessionId ?: sessionStore.currentSessionId() ?: sessionStore.newSession()
        sessionStore.setCurrentSession(sessionId)
        val enabledTools = toolsets.resolve(sessionId)
        val messages = sessionStore.loadMessages(sessionId)
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        } else if (
            messages.optJSONObject(0)?.optString("role") == "system" &&
            messages.optJSONObject(0)?.optString("content") != systemPrompt
        ) {
            messages.put(0, JSONObject().put("role", "system").put("content", systemPrompt))
        }
        val compaction = contextManager.compactMessagesIfNeeded(sessionId, messages)
        if (compaction.optBoolean("compacted", false)) {
            log(
                "info",
                "context",
                "context compacted",
                JSONObject(compaction.toString()).put("session_id", sessionId)
            )
            sessionStore.saveMessages(sessionId, messages)
        }
        val effectiveActionsApproved =
            actionsApproved || runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER
        val modelUserMessage = if (effectiveActionsApproved) {
            "[APP_CONFIRMATION_APPROVED_FOR_THIS_REQUEST]\n" +
                "用户已在 Android 应用确认弹窗中批准本次请求。如果请求所需工具被当前权限模式允许，可以直接调用并根据工具结果继续。" +
                "默认使用简体中文回复；如果这只是聊天、语言偏好或解释问题，不要为了确认状态而调用工具。\n\n" +
                message
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
        stopFlowController.clear(sessionId)

        val taskPlan = JSONObject()
            .put("status", "not_started")
            .put("goal", "")
            .put("steps", JSONArray())
            .put("done_when", JSONArray())
            .put("updated_at", 0L)
        val maxToolRounds = runtimeConfig.maxToolRounds()
        val outcome = taskLoopEngine.run(
            apiKey = apiKey,
            messages = messages,
            sessionId = sessionId,
            memoryContext = memoryContext,
            actionsApproved = effectiveActionsApproved,
            enabledTools = enabledTools,
            taskPlan = taskPlan,
            maxToolRounds = maxToolRounds
        )

        val finalText = normalizeVisibleChinese(outcome.finalText)
        val taskLoop = outcome.taskLoop
        val runId = UUID.randomUUID().toString()
        val taskRecord = if (outcome.steps > 0) {
            taskMemoryCoordinator.persistTaskLoopRun(
                title = taskMemoryCoordinator.taskTitleForRun(taskPlan, message),
                goal = taskMemoryCoordinator.taskGoalForRun(taskPlan, message),
                sessionId = sessionId,
                runId = runId,
                finalText = finalText,
                taskLoop = taskLoop,
                toolTrace = outcome.toolTrace
            )
        } else {
            JSONObject().put("skipped", true).put("reason", "no tool steps")
        }
        taskLoop.put("task_record", taskRecord)
        val memoryRecord = taskMemoryCoordinator.recordTaskMemory(
            apiKey = apiKey,
            userMessage = message,
            finalText = finalText,
            taskLoop = taskLoop,
            toolTrace = outcome.toolTrace,
            runId = runId
        )
        taskLoop.put("memory_record", memoryRecord)
        val loopFailureExperience = taskMemoryCoordinator.recordLoopFailureExperience(
            userMessage = message,
            taskLoop = taskLoop,
            blocker = outcome.stoppedByLoopGuard,
            runId = runId
        )
        taskLoop.put("loop_failure_experience", loopFailureExperience)
        messages.put(JSONObject().put("role", "assistant").put("content", finalText))
        sessionStore.saveMessages(sessionId, messages)
        AgentEventStore.record(
            "chat_finished",
            "用户消息处理完成",
            JSONObject()
                .put("run_id", runId)
                .put("session_id", sessionId)
                .put("tool_rounds", outcome.toolRounds)
                .put("steps", outcome.steps)
                .put("failed_steps", outcome.failedSteps)
        )
        return JSONObject()
            .put("session_id", sessionId)
            .put("run_id", runId)
            .put("message", finalText)
            .put("tool_trace", outcome.toolTrace)
            .put("task_loop", taskLoop)
            .put("tool_rounds", outcome.toolRounds)
            .put("context", contextManager.contextStats(messages))
            .put("context_compaction", compaction)
            .put("memory_context", memoryContext)
            .put("memory_record", memoryRecord)
            .put("usage", contextManager.latestUsage(sessionId))
    }
}
