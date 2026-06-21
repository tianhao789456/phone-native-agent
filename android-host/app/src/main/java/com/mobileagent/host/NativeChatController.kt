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
            "鏀跺埌鐢ㄦ埛娑堟伅",
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
        stopFlowController.clear(sessionId)

        val taskPlan = JSONObject()
            .put("status", "not_started")
            .put("goal", "")
            .put("steps", JSONArray())
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
        messages.put(JSONObject().put("role", "assistant").put("content", finalText))
        sessionStore.saveMessages(sessionId, messages)
        AgentEventStore.record(
            "chat_finished",
            "浠诲姟澶勭悊瀹屾垚",
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
