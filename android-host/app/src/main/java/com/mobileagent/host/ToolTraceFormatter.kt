package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object ToolTraceFormatter {
    fun buildToolDetail(loop: JSONObject?, trace: JSONArray): String {
        val parts = mutableListOf<String>()
        if (loop != null) {
            parts.add(
                listOf(
                    "任务循环",
                    "状态: ${loop.optString("status", "-")}",
                    "轮次: ${loop.optInt("rounds", 0)}/${loop.optInt("max_rounds", 0)}",
                    "步骤: ${loop.optInt("steps", 0)}",
                    "失败: ${loop.optInt("failed_steps", 0)}"
                ).joinToString("\n")
            )
            loop.optJSONObject("plan")?.let { plan ->
                parts.add(
                    listOf(
                        "计划",
                        "目标: ${plan.optString("goal", "-")}",
                        "状态: ${plan.optString("status", "-")}",
                        compactPlanSteps(plan.optJSONArray("steps"))
                    ).filter { it.isNotBlank() }.joinToString("\n")
                )
            }
        }
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            parts.add(formatToolTraceItem(item, index + 1))
        }
        parts.add(
            "原始 JSON\n" + JSONObject()
                .put("task_loop", loop ?: JSONObject())
                .put("tool_trace", trace)
                .toString(2)
        )
        return parts.joinToString("\n\n---\n\n")
    }

    fun formatJsonPreview(value: JSONObject, maxChars: Int): String {
        val text = runCatching { value.toString(2) }.getOrElse { value.toString() }
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n... JSON truncated, chars=${text.length}"
    }

    fun summarizeToolTrace(trace: JSONArray?): String? {
        if (trace == null || trace.length() == 0) return null
        val lines = mutableListOf<String>()
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            val tool = item.optString("tool", "tool")
            val step = item.optInt("step", index + 1)
            val output = item.optJSONObject("output")
            val ok = output?.optBoolean("ok", false) == true
            val verification = output?.optJSONObject("verification")
            val verificationText = when {
                verification?.optBoolean("required", false) == true && verification.optBoolean("ok", false) -> " | 先前检查成功"
                verification?.optBoolean("required", false) == true -> " | 验证失败"
                else -> ""
            }
            val autoRecovery = output?.optJSONObject("auto_recovery")
            val recoveryText = when (autoRecovery?.optString("status", "")) {
                "recovered" -> " | 自动恢复成功"
                "retry_failed" -> " | 重试失败"
                "recovery_failed" -> " | 自动恢复失败"
                else -> ""
            }
            val state = when {
                ok -> "成功"
                output?.optBoolean("needs_confirmation") == true -> "待确认"
                output?.optBoolean("needs_permission") == true -> "待授权"
                else -> "失败"
            }
            val verificationRecovery = output?.optJSONObject("verification_recovery")
            val verificationRecoveryText = when (verificationRecovery?.optString("status", "")) {
                "recovered" -> " | 验证恢复成功"
                "retry_failed" -> " | 验证重试失败"
                "recovery_failed" -> " | 验证恢复失败"
                "not_attempted" -> " | 未尝试"
                else -> ""
            }
            lines.add("#$step $state $tool$verificationText$recoveryText$verificationRecoveryText")
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    fun summarizeTaskLoop(loop: JSONObject?): String? {
        if (loop == null) return null
        val state = when (loop.optString("status")) {
            "completed" -> "完成"
            "completed_with_failures" -> "完成但有失败"
            "max_rounds_reached" -> "达到轮数上限"
            "no_tools" -> "未找到工具"
            else -> loop.optString("status", "未知")
        }
        val plan = loop.optJSONObject("plan")
        val planText = summarizePlan(plan)
        val base = "任务循环 $state | 步骤 ${loop.optInt("steps", 0)} | 失败 ${loop.optInt("failed_steps", 0)} | 轮次 ${loop.optInt("rounds", 0)}/${loop.optInt("max_rounds", 0)}"
        return if (planText == null) base else "$base\n$planText"
    }

    private fun compactPlanSteps(steps: JSONArray?): String {
        if (steps == null || steps.length() == 0) return ""
        val lines = mutableListOf("步骤:")
        for (index in 0 until steps.length().coerceAtMost(12)) {
            val step = steps.optJSONObject(index) ?: continue
            lines.add("- ${step.optString("id", "${index + 1}")}: ${step.optString("status", "-")} ${step.optString("title", "").take(80)}")
        }
        if (steps.length() > 12) lines.add("- ... 还有 ${steps.length() - 12} 步")
        return lines.joinToString("\n")
    }

    private fun formatToolTraceItem(item: JSONObject, fallbackStep: Int): String {
        val output = item.optJSONObject("output") ?: JSONObject()
        val result = output.optJSONObject("result") ?: JSONObject()
        val lines = mutableListOf<String>()
        val step = item.optInt("step", fallbackStep)
        lines.add("工具 #$step ${item.optString("tool", "tool")}")
        lines.add("状态: ${item.optString("state", "-")} | 耗时: ${item.optLong("duration_ms", 0)}ms")
        lines.add("调用参数:\n${formatJsonPreview(item.optJSONObject("arguments") ?: JSONObject(), 1200)}")
        toolResultSummary(output, result)?.let { lines.add("结果: $it") }
        output.optString("error", "").takeIf { it.isNotBlank() }?.let { lines.add("错误: $it") }
        output.optJSONObject("verification")?.let { verification ->
            lines.add(
                listOf(
                    "验证",
                    "required=${verification.optBoolean("required", false)} ok=${verification.optBoolean("ok", false)} status=${verification.optString("status", "-")}",
                    verification.optString("summary", "")
                ).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
        output.optJSONObject("auto_recovery")?.let { recovery ->
            lines.add("自动恢复:\n${formatRecoverySummary(recovery)}")
        }
        output.optJSONObject("verification_recovery")?.let { recovery ->
            lines.add("验证恢复:\n${formatRecoverySummary(recovery)}")
        }
        appendOutputPreview(lines, "stdout", result)
        appendOutputPreview(lines, "stderr", result)
        if (result.length() > 0) {
            lines.add("结果摘要:\n${formatJsonPreview(result, 1600)}")
        }
        return lines.joinToString("\n")
    }

    private fun toolResultSummary(output: JSONObject, result: JSONObject): String? {
        val parts = mutableListOf<String>()
        if (result.has("ok")) parts.add("ok=${result.optBoolean("ok", false)}")
        result.optString("status", "").takeIf { it.isNotBlank() }?.let { parts.add("status=$it") }
        result.optString("message", "").takeIf { it.isNotBlank() }?.let { parts.add("message=${it.take(180)}") }
        result.optInt("returncode", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { parts.add("returncode=$it") }
        result.optBoolean("timed_out", false).takeIf { it }?.let { parts.add("timed_out=true") }
        result.optString("summary", "").takeIf { it.isNotBlank() }?.let { parts.add("summary=${it.take(220)}") }
        if (parts.isEmpty()) {
            output.optBoolean("ok", false).takeIf { it }?.let { parts.add("ok=true") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun formatRecoverySummary(recovery: JSONObject): String {
        val lines = mutableListOf<String>()
        lines.add("type=${recovery.optString("type", "-")} status=${recovery.optString("status", "-")}")
        recovery.optString("strategy", "").takeIf { it.isNotBlank() }?.let { lines.add("strategy=$it") }
        recovery.optJSONObject("diagnosis")?.let { diagnosis ->
            lines.add("diagnosis=${diagnosis.optString("status", "-")} ${diagnosis.optString("summary", "")}".trim())
        }
        recovery.optJSONObject("retry_verification")?.let { verification ->
            lines.add("retry_verification ok=${verification.optBoolean("ok", false)} status=${verification.optString("status", "-")}")
        }
        return lines.joinToString("\n")
    }

    private fun appendOutputPreview(lines: MutableList<String>, key: String, result: JSONObject) {
        val folded = result.optJSONObject("output")?.optJSONObject(key)
        val text = folded?.optString("text", "")?.ifBlank { result.optString(key, "") } ?: result.optString(key, "")
        if (text.isBlank()) return
        val truncated = folded?.optBoolean("truncated", false) ?: (text.length > 2000)
        val chars = folded?.optInt("chars", text.length) ?: text.length
        val preview = text.take(2000)
        val suffix = if (truncated || text.length > preview.length) "\n... output truncated, chars=$chars" else ""
        lines.add("$key:\n$preview$suffix")
    }

    private fun summarizePlan(plan: JSONObject?): String? {
        if (plan == null) return null
        val steps = plan.optJSONArray("steps") ?: return null
        if (steps.length() == 0) return null
        val status = when (plan.optString("status")) {
            "not_started" -> "未开始"
            "in_progress" -> "进行中"
            "blocked" -> "受阻"
            "completed" -> "完成"
            "failed" -> "失败"
            "cancelled" -> "取消"
            else -> plan.optString("status", "未知")
        }
        var completed = 0
        var failed = 0
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            when (step.optString("status")) {
                "completed" -> completed += 1
                "failed", "blocked" -> failed += 1
            }
        }
        val goal = plan.optString("goal", "").take(40)
        val goalText = if (goal.isBlank()) "" else " | $goal"
        return "计划 $status | ${completed}/${steps.length()} 完成 | 异常 $failed$goalText"
    }
}
