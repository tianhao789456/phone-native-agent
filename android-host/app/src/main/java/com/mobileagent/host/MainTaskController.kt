package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import java.util.ArrayDeque
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class MainTaskController(
    private val activity: Activity,
    private val core: NativeAgentCore,
    private val ui: Handler,
    private val isSending: () -> Boolean,
    private val setSending: (Boolean) -> Unit,
    private val getSessionId: () -> String?,
    private val updateSessionId: (String) -> Unit,
    private val onComposerStateChanged: () -> Unit,
    private val saveState: () -> Unit,
    private val addMessage: (role: String, text: String, detail: String?) -> Unit,
    private val onRefreshStatus: () -> Unit,
    private val setLastToolTrace: (JSONArray) -> Unit,
    private val setLastTaskLoop: (JSONObject?) -> Unit,
    private val onLiveStatus: (String) -> Unit,
) {
    private val pendingMessages = ArrayDeque<String>()
    private var interruptRequested = false
    private var lastEventSeq = 0L

    companion object {
        private const val INTERRUPT_PREFIX =
            "[APP_RUNTIME_INTERRUPT]\n" +
                "用户在上一轮执行过程中追加了这条新指令。请先参考上一轮任务循环和最新工具结果，按这条新指令调整计划继续；不要重复已经被用户否定的动作。\n\n"
    }

    fun sendUserMessage(text: String) {
        if (isSending()) {
            queueMessage(text)
            return
        }
        sendTextWithConfirmation(text)
    }

    fun requestStopCurrentTask() {
        if (!isSending()) {
            addMessage(
                "系统",
                if (pendingMessages.isEmpty()) {
                    "当前没有正在运行的任务。"
                } else {
                    "当前还有 ${pendingMessages.size} 条待发送消息。"
                },
                null
            )
            return
        }
        interruptRequested = true
        runCatching { core.requestStop(getSessionId()) }
            .onSuccess { response ->
                if (response.optBoolean("ok")) {
                    addMessage("系统", "已提交停止请求，任务会在下一个安全点终止。", null)
                } else {
                    addMessage("错误", response.optString("error", "停止请求失败"), null)
                }
            }
            .onFailure { exc ->
                addMessage("错误", exc.message ?: exc.javaClass.simpleName, null)
            }
    }

    fun clearForNewSession() {
        pendingMessages.clear()
        interruptRequested = false
    }

    fun pendingCount(): Int = pendingMessages.size

    fun refreshLiveEvents(seconds: Long) {
        val events = core.eventsForUi(lastEventSeq, 20)
        val list = events.optJSONArray("events") ?: JSONArray()
        if (list.length() == 0) {
            onLiveStatus("运行 ${seconds}s | 等待事件")
            return
        }
        val latest = list.optJSONObject(list.length() - 1) ?: return
        lastEventSeq = latest.optLong("seq", lastEventSeq)
        val message = latest.optString("message", latest.optString("type", "event"))
        val details = latest.optJSONObject("details") ?: JSONObject()
        val extra = when (latest.optString("type")) {
            "model_started" -> "消息 ${details.optInt("messages", 0)}"
            "model_finished" -> "工具 ${details.optInt("tool_calls", 0)} | ${details.optLong("duration_ms", 0)}ms"
            "tool_started" -> "步骤 ${details.optInt("step", 0)}"
            "tool_finished" -> "${details.optString("state", "-")} | ${details.optLong("duration_ms", 0)}ms"
            "model_failed" -> details.optString("error", "").take(80)
            else -> ""
        }
        onLiveStatus(listOf("运行 ${seconds}s", message, extra).filter { it.isNotBlank() }.joinToString(" | "))
    }

    fun startLiveEventTracking() {
        lastEventSeq = core.eventsForUi(0L, 1).optLong("last_seq", lastEventSeq)
    }

    fun sendConfirmedMessage(text: String, actionsApproved: Boolean) {
        val visibleText = visibleUserText(text)
        interruptRequested = false
        addMessage("我", visibleText, null)
        setSending(true)

        thread(name = "mobile-agent-chat") {
            val result = runCatching { core.chat(text, getSessionId(), actionsApproved) }
            ui.post {
                setSending(false)
                result
                    .onSuccess { response ->
                        val nextSessionId = response.optString("session_id", getSessionId().orEmpty())
                        if (nextSessionId.isNotBlank()) {
                            updateSessionId(nextSessionId)
                            saveState()
                        }
                        response.optJSONArray("tool_trace")?.let { trace ->
                            ToolTraceFormatter.summarizeToolTrace(trace)?.let { summary ->
                                val loop = response.optJSONObject("task_loop")
                                setLastToolTrace(JSONArray(trace.toString()))
                                setLastTaskLoop(loop?.let { JSONObject(it.toString()) })
                                val loopSummary = ToolTraceFormatter.summarizeTaskLoop(loop)
                                val visibleSummary = listOfNotNull(loopSummary, summary).joinToString("\n")
                                val detail = ToolTraceFormatter.buildToolDetail(loop, trace)
                                addMessage("工具", visibleSummary, detail)
                            }
                            if (!actionsApproved) {
                                findConfirmationNeeded(trace)?.let { pending ->
                                    showToolConfirmationDialog(text, pending)
                                    onRefreshStatus()
                                    return@onSuccess
                                }
                            }
                        }
                        val answer = response.optString("message", response.optString("reply", response.toString()))
                        addMessage("助手", answer.ifBlank { response.toString() }, null)
                        onRefreshStatus()
                        processQueuedMessageIfAny()
                    }
                    .onFailure { exc ->
                        addMessage("错误", exc.message ?: exc.javaClass.simpleName, null)
                        onRefreshStatus()
                        processQueuedMessageIfAny()
                    }
            }
        }
    }

    private fun processQueuedMessageIfAny() {
        if (pendingMessages.isEmpty()) return
        val next = pendingMessages.removeFirst()
        onComposerStateChanged()
        sendTextWithConfirmation(next)
    }

    private fun queueMessage(text: String) {
        pendingMessages.addLast(INTERRUPT_PREFIX + text)
        if (!interruptRequested) {
            interruptRequested = true
            runCatching { core.requestStop(getSessionId()) }
                .onSuccess { response ->
                    if (response.optBoolean("ok")) {
                        addMessage("系统", "已追加新指令，并请求当前任务在下一个安全点停止。队列 ${pendingMessages.size} 条。", null)
                    } else {
                        addMessage("系统", "已追加新指令，但停止请求未生效：${response.optString("error", "未知错误")}。队列 ${pendingMessages.size} 条。", null)
                    }
                }
                .onFailure { exc ->
                    addMessage("系统", "已追加新指令，但停止请求失败：${exc.message ?: exc.javaClass.simpleName}。队列 ${pendingMessages.size} 条。", null)
                }
        } else {
            addMessage("系统", "已继续追加指令，当前任务已经请求停止，队列 ${pendingMessages.size} 条。", null)
        }
        onComposerStateChanged()
    }

    private fun sendTextWithConfirmation(text: String) {
        if (core.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER) {
            sendConfirmedMessage(text, actionsApproved = true)
            return
        }

        if (needsActionConfirmation(text)) {
            AlertDialog.Builder(activity)
                .setTitle("确认执行手机操作")
                .setMessage("下一条指令可能触发自动化、终端、文件、远程电脑或其他应用操作。请确认是否继续。\n\n$text")
                .setNegativeButton("取消", null)
                .setPositiveButton("发送") { _, _ -> sendConfirmedMessage(text, actionsApproved = true) }
                .show()
            return
        }

        sendConfirmedMessage(text, actionsApproved = false)
    }

    private fun findConfirmationNeeded(trace: JSONArray): JSONObject? {
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            val output = item.optJSONObject("output") ?: JSONObject()
            if (output.optBoolean("needs_confirmation", false) || item.optString("state") == "needs_confirmation") {
                return item
            }
        }
        return null
    }

    private fun showToolConfirmationDialog(originalText: String, traceItem: JSONObject) {
        val tool = traceItem.optString("tool", "tool")
        val output = traceItem.optJSONObject("output") ?: JSONObject()
        val detail = buildString {
            append("工具: ").append(tool).append("\n")
            append("状态: ").append(output.optString("state", "unknown")).append("\n")
            append("权限: ").append(output.optString("permission_mode", core.permissionMode())).append("\n\n")
            append("本次请求:\n").append(originalText).append("\n\n")
            append("参数:\n").append(ToolTraceFormatter.formatJsonPreview(traceItem.optJSONObject("arguments") ?: JSONObject(), 1600)).append("\n\n")
            output.optString("error", "").takeIf { it.isNotBlank() }?.let {
                append("原因:\n").append(it).append("\n\n")
            }
            append("确认后会继续执行该工具调用。")
        }
        AlertDialog.Builder(activity)
            .setTitle("确认工具调用")
            .setMessage(detail)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行") { _, _ -> sendConfirmedMessage(originalText, actionsApproved = true) }
            .show()
    }

    private fun needsActionConfirmation(text: String): Boolean {
        val lower = text.lowercase()
        val actionWords = listOf(
            "click", "tap", "input", "type", "send", "delete", "install", "uninstall",
            "open app", "back", "home", "scroll", "press", "choose", "close", "exit",
            "run", "execute", "command", "shell", "terminal", "script", "terminal_run",
            "terminal_script", "terminal_task", "task", "cancel", "interrupt", "stop",
            "recover", "repair", "restart", "reconnect", "recover_terminal_backend",
            "look", "next", "restore", "upload", "open", "return", "swipe",
            "continue", "config", "session",
            "任务", "取消", "中断", "停止", "修复", "恢复", "重启", "重连", "自愈",
            "点击", "输入", "发送", "删除", "安装", "卸载", "打开", "返回", "主页",
            "滑动", "滚动", "运行", "执行", "命令", "终端", "脚本", "电脑", "远程",
            "上传", "下载", "复制", "移动", "文件"
        )
        return actionWords.any { lower.contains(it) }
    }

    private fun visibleUserText(text: String): String {
        return if (text.startsWith(INTERRUPT_PREFIX)) text.removePrefix(INTERRUPT_PREFIX) else text
    }
}
