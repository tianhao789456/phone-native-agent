package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import kotlin.concurrent.thread

class MainFailureController(
    private val activity: Activity,
    private val core: NativeAgentCore,
    private val ui: Handler,
    private val addMessage: (role: String, text: String, detail: String?) -> Unit,
    private val refreshStatus: () -> Unit,
    private val getLastToolTrace: () -> JSONArray?,
    private val setLastToolTrace: (JSONArray) -> Unit,
    private val setLastTaskLoop: (JSONObject?) -> Unit,
    private val sendConfirmedMessage: (String, Boolean) -> Unit,
    private val showDetailsScrollable: (title: String, detail: String) -> Unit,
) {
    fun showLatestFailureAnalysis() {
        addMessage("系统", "正在读取最近失败分析...", null)
        thread(name = "mobile-agent-failure-analysis") {
            val result = runCatching { core.latestFailureAnalysisForUi(30000) }
            ui.post {
                result
                    .onSuccess { analysis ->
                        if (!analysis.optBoolean("ok", false)) {
                            addMessage("系统", analysis.optString("error", "未找到故障分析"), null)
                            return@onSuccess
                        }
                        val path = analysis.optString("path", "-")
                        val task = analysis.optJSONObject("task") ?: JSONObject()
                        val title = task.optString("title", "未命名故障任务")
                        val content = analysis.optString("content", "")
                        val truncated = if (analysis.optBoolean("truncated", false)) "\\n\\n... 内容被截断" else ""
                        val detail = buildString {
                            append("任务: ").append(title).append("\n")
                            append("路径: ").append(path).append("\n")
                            append("大小: ").append(analysis.optInt("size_bytes", 0)).append(" bytes\n\n")
                            append(content)
                            append(truncated)
                        }
                        addMessage("工具", "最近失败分析：$title\n$path", detail)
                        showDetailsScrollable("故障分析: $title", detail)
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName, null) }
            }
        }
    }

    fun retryLastFailedStep() {
        val target = findLastFailedTraceItem()
        if (target == null) {
                            addMessage("系统", "没有可重试的失败工具步骤", null)
            return
        }
        val tool = target.optString("tool", "")
        val args = target.optJSONObject("arguments") ?: JSONObject()
        AlertDialog.Builder(activity)
            .setTitle("重试失败步骤")
            .setMessage("将重新执行工具：$tool\n\n参数：${ToolTraceFormatter.formatJsonPreview(args, 800)}")
            .setNegativeButton("取消", null)
            .setPositiveButton("重试") { _, _ -> runDiagnosticToolRetry(tool, args) }
            .show()
    }

    fun cancelRunningTerminalTasks() {
        val taskIds = findRunningTerminalTaskIds()
        if (taskIds.isEmpty()) {
            addMessage("系统", "当前没有进行中的后台任务", null)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("停止后台任务")
            .setMessage("将尝试停止 ${taskIds.size} 个终端任务：\n${taskIds.joinToString("\n")}")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止") { _, _ ->
                thread(name = "mobile-agent-cancel-tasks") {
                    val trace = JSONArray()
                    for (taskId in taskIds) {
                        val args = JSONObject().put("task_id", taskId)
                        val output = core.executeNativeToolForDiagnostics("terminal_task_cancel", args, true)
                        trace.put(DiagnosticTraceFormatter.toolTraceItem("terminal_task_cancel", args, output, trace.length() + 1))
                    }
                    ui.post {
                        setLastToolTrace(JSONArray(trace.toString()))
                        setLastTaskLoop(DiagnosticTraceFormatter.diagnosticLoop(trace))
                        val summary = ToolTraceFormatter.summarizeToolTrace(trace) ?: "任务完成后将继续执行下一个"
                        addMessage("工具", summary, ToolTraceFormatter.buildToolDetail(DiagnosticTraceFormatter.diagnosticLoop(trace), trace))
                        refreshStatus()
                    }
                }
            }
            .show()
    }

    fun continueFailedTask() {
        val target = findLastFailedTraceItem()
        if (target == null) {
            addMessage("系统", "没有找到可继续的失败任务步骤", null)
            return
        }
        val tool = target.optString("tool", "tool")
        val summary = target.optJSONObject("output")?.let { DiagnosticTraceFormatter.toolStepBrief(it) } ?: target.optString("state", "failed")
        sendConfirmedMessage(
            "检测到上一个失败的工具步骤，正在恢复 $tool，请确认参数：$summary。系统将继续执行后续动作，若失败会再次展示验证结果。",
            false
        )
    }

    private fun runDiagnosticToolRetry(tool: String, args: JSONObject) {
        addMessage("系统", "正在重试工具 $tool", null)
        thread(name = "mobile-agent-tool-retry") {
            val output = core.executeNativeToolForDiagnostics(tool, JSONObject(args.toString()), true)
            val trace = JSONArray().put(DiagnosticTraceFormatter.toolTraceItem(tool, args, output, 1))
            ui.post {
                setLastToolTrace(JSONArray(trace.toString()))
                setLastTaskLoop(DiagnosticTraceFormatter.diagnosticLoop(trace))
                val summary = ToolTraceFormatter.summarizeToolTrace(trace) ?: "工具执行完成"
                addMessage("工具", summary, ToolTraceFormatter.buildToolDetail(DiagnosticTraceFormatter.diagnosticLoop(trace), trace))
                refreshStatus()
            }
        }
    }

    private fun findLastFailedTraceItem(): JSONObject? {
        val trace = getLastToolTrace() ?: return null
        for (index in trace.length() - 1 downTo 0) {
            val item = trace.optJSONObject(index) ?: continue
            val output = item.optJSONObject("output") ?: JSONObject()
            if (DiagnosticTraceFormatter.uiToolState(output) != "success") return JSONObject(item.toString())
        }
        return null
    }

    private fun findRunningTerminalTaskIds(): List<String> {
        val trace = getLastToolTrace() ?: return emptyList()
        val taskIds = linkedSetOf<String>()
        for (index in 0 until trace.length()) {
            val output = trace.optJSONObject(index)?.optJSONObject("output") ?: continue
            val result = output.optJSONObject("result") ?: continue
            val status = result.optString("status", "").lowercase()
            val taskId = result.optString("task_id", "")
            if (taskId.isNotBlank() && status == "running") taskIds.add(taskId)
        }
        return taskIds.toList()
    }
}
