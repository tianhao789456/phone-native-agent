package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object MobileWorkspaceReportFormatter {
    fun finalReportMarkdown(report: JSONObject): String {
        val task = report.optJSONObject("task") ?: JSONObject()
        val loop = report.optJSONObject("task_loop") ?: JSONObject()
        val title = task.optString("title", "Task")
        val finalMessage = report.optString("final_message", "")
        return buildString {
            append("# ").append(title).append("\n\n")
            append("- Status: ").append(loop.optString("status", "unknown")).append("\n")
            append("- Run: ").append(report.optString("run_id", "")).append("\n")
            append("- Session: ").append(report.optString("session_id", "")).append("\n")
            append("- Rounds: ").append(loop.optInt("rounds", 0)).append("/").append(loop.optInt("max_rounds", 0)).append("\n")
            append("- Steps: ").append(loop.optInt("steps", 0)).append("\n")
            append("- Failed steps: ").append(loop.optInt("failed_steps", 0)).append("\n\n")
            append("## Goal\n\n")
            append(task.optString("goal", "").ifBlank { "(none)" }).append("\n\n")
            append("## Final Message\n\n")
            append(finalMessage.ifBlank { "(empty)" }).append("\n\n")
            append("## Evidence\n\n")
            val trace = loop.optJSONArray("trace") ?: JSONArray()
            if (trace.length() == 0) {
                append("- No tool steps recorded.\n")
            } else {
                for (index in 0 until trace.length()) {
                    val step = trace.optJSONObject(index) ?: continue
                    append("- #").append(step.optInt("step", index + 1))
                        .append(" `").append(step.optString("tool", "")).append("` ")
                        .append(step.optString("state", "unknown"))
                    val summary = step.optString("summary", "")
                    if (summary.isNotBlank()) append(" - ").append(summary.replace("\n", " ").take(240))
                    append("\n")
                }
            }
        }
    }

    fun traceSummaryMarkdown(report: JSONObject): String {
        val trace = report.optJSONArray("tool_trace") ?: JSONArray()
        return buildString {
            append("# Tool Trace Summary\n\n")
            append("- Run: ").append(report.optString("run_id", "")).append("\n")
            append("- Tool steps: ").append(trace.length()).append("\n\n")
            for (index in 0 until trace.length()) {
                val step = trace.optJSONObject(index) ?: continue
                append("## Step ").append(step.optInt("step", index + 1)).append(": ")
                    .append(step.optString("tool", "")).append("\n\n")
                append("- Round: ").append(step.optInt("round", 0)).append("\n")
                append("- State: ").append(step.optString("state", "unknown")).append("\n")
                append("- Duration: ").append(step.optLong("duration_ms", 0)).append(" ms\n")
                val output = step.optJSONObject("output") ?: JSONObject()
                val verification = output.optJSONObject("verification") ?: JSONObject()
                if (verification.length() > 0) {
                    append("- Verification: ").append(verification.optString("status", "unknown"))
                        .append(" / ok=").append(verification.optBoolean("ok", false)).append("\n")
                    val summary = verification.optString("summary", "")
                    if (summary.isNotBlank()) append("- Verification summary: ").append(summary.replace("\n", " ").take(300)).append("\n")
                }
                val result = output.optJSONObject("result") ?: JSONObject()
                val stdout = result.optString("stdout", "")
                val stderr = result.optString("stderr", "")
                if (stdout.isNotBlank()) append("\n```stdout\n").append(stdout.take(2000)).append("\n```\n")
                if (stderr.isNotBlank()) append("\n```stderr\n").append(stderr.take(2000)).append("\n```\n")
                append("\n")
            }
        }
    }

    fun loopLogText(report: JSONObject): String {
        val task = report.optJSONObject("task") ?: JSONObject()
        val loop = report.optJSONObject("task_loop") ?: JSONObject()
        val trace = loop.optJSONArray("trace") ?: JSONArray()
        return buildString {
            append("task=").append(task.optString("path", "")).append("\n")
            append("run_id=").append(report.optString("run_id", "")).append("\n")
            append("session_id=").append(report.optString("session_id", "")).append("\n")
            append("status=").append(loop.optString("status", "unknown")).append("\n")
            append("rounds=").append(loop.optInt("rounds", 0)).append("/").append(loop.optInt("max_rounds", 0)).append("\n")
            append("steps=").append(loop.optInt("steps", 0)).append("\n")
            append("failed_steps=").append(loop.optInt("failed_steps", 0)).append("\n")
            append("created_at_ms=").append(report.optLong("created_at_ms", 0)).append("\n")
            append("\nsteps:\n")
            for (index in 0 until trace.length()) {
                val step = trace.optJSONObject(index) ?: continue
                append("- #").append(step.optInt("step", index + 1))
                    .append(" round=").append(step.optInt("round", 0))
                    .append(" tool=").append(step.optString("tool", ""))
                    .append(" state=").append(step.optString("state", "unknown"))
                    .append(" duration_ms=").append(step.optLong("duration_ms", 0))
                val summary = step.optString("summary", "")
                if (summary.isNotBlank()) append(" summary=").append(summary.replace("\n", " ").take(240))
                append("\n")
            }
        }
    }

    fun failureAnalysisMarkdown(report: JSONObject): String {
        val task = report.optJSONObject("task") ?: JSONObject()
        val loop = report.optJSONObject("task_loop") ?: JSONObject()
        val loopTrace = loop.optJSONArray("trace") ?: JSONArray()
        val toolTrace = report.optJSONArray("tool_trace") ?: JSONArray()
        return buildString {
            append("# Failure Analysis\n\n")
            append("- Task: ").append(task.optString("path", "")).append("\n")
            append("- Run: ").append(report.optString("run_id", "")).append("\n")
            append("- Status: ").append(loop.optString("status", "unknown")).append("\n")
            append("- Failed steps: ").append(loop.optInt("failed_steps", 0)).append("\n")
            append("- Rounds: ").append(loop.optInt("rounds", 0)).append("/").append(loop.optInt("max_rounds", 0)).append("\n\n")
            val blocker = loop.optJSONObject("blocker") ?: JSONObject()
            if (blocker.length() > 0) {
                append("## Loop Blocker\n\n")
                append("- Reason: ").append(blocker.optString("reason", "")).append("\n")
                append("- Tool: `").append(blocker.optString("tool", "")).append("`\n")
                append("- State: ").append(blocker.optString("state", "")).append("\n")
                append("- Summary: ").append(blocker.optString("summary", "").replace("\n", " ").take(700)).append("\n")
                val nextActions = blocker.optJSONArray("next_actions") ?: JSONArray()
                if (nextActions.length() > 0) {
                    append("- Next actions:\n")
                    for (index in 0 until nextActions.length()) {
                        append("  - ").append(nextActions.optString(index).replace("\n", " ").take(240)).append("\n")
                    }
                }
                append("\n")
            }
            append("## Failed Steps\n\n")
            var failures = 0
            for (index in 0 until loopTrace.length()) {
                val step = loopTrace.optJSONObject(index) ?: continue
                val state = step.optString("state", "unknown")
                if (state == "success") continue
                failures += 1
                val stepNo = step.optInt("step", index + 1)
                val tool = step.optString("tool", "")
                append("### #").append(stepNo).append(" `").append(tool).append("`\n\n")
                append("- State: ").append(state).append("\n")
                append("- Round: ").append(step.optInt("round", 0)).append("\n")
                append("- Duration: ").append(step.optLong("duration_ms", 0)).append(" ms\n")
                append("- Summary: ").append(step.optString("summary", "").replace("\n", " ").take(500).ifBlank { "(none)" }).append("\n")
                val verification = step.optJSONObject("verification") ?: JSONObject()
                if (verification.length() > 0) {
                    append("- Verification: ").append(verification.optString("status", "unknown"))
                        .append(" / ok=").append(verification.optBoolean("ok", false)).append("\n")
                    val summary = verification.optString("summary", "")
                    if (summary.isNotBlank()) append("- Verification summary: ").append(summary.replace("\n", " ").take(500)).append("\n")
                }
                val full = findToolTraceStep(toolTrace, stepNo)
                appendToolFailureDetails(full)
                append("\n")
            }
            if (failures == 0) {
                append("- No individual failed tool step was recorded. Inspect task status and raw report.\n\n")
            }
            append("## Suggested Next Actions\n\n")
            append("- Inspect the failed tool arguments and verification summary.\n")
            append("- Use task_report_read for the raw JSON report when the Markdown summary is insufficient.\n")
            append("- If the failure is environmental, run self_health_check or the relevant diagnose_* tool.\n")
            append("- If the failure is recoverable, retry with corrected arguments and record the result with task_log_append or task_artifact_write.\n")
        }
    }

    private fun StringBuilder.appendToolFailureDetails(step: JSONObject?) {
        if (step == null) return
        val arguments = step.optJSONObject("arguments") ?: JSONObject()
        if (arguments.length() > 0) {
            append("- Arguments: `").append(arguments.toString().take(500).replace("\n", " ")).append("`\n")
        }
        val output = step.optJSONObject("output") ?: JSONObject()
        val result = output.optJSONObject("result") ?: JSONObject()
        val error = output.optString("error", "").ifBlank { result.optString("error", "") }
        if (error.isNotBlank()) append("- Error: ").append(error.replace("\n", " ").take(500)).append("\n")
        val recovery = output.optJSONObject("auto_recovery") ?: output.optJSONObject("verification_recovery")
        if (recovery != null && recovery.length() > 0) {
            append("- Recovery: ").append(recovery.optString("status", "present")).append("\n")
        }
        val stdout = result.optString("stdout", "")
        val stderr = result.optString("stderr", "")
        if (stdout.isNotBlank()) append("\n```stdout\n").append(stdout.take(2000)).append("\n```\n")
        if (stderr.isNotBlank()) append("\n```stderr\n").append(stderr.take(2000)).append("\n```\n")
    }

    private fun findToolTraceStep(toolTrace: JSONArray, stepNo: Int): JSONObject? {
        for (index in 0 until toolTrace.length()) {
            val step = toolTrace.optJSONObject(index) ?: continue
            if (step.optInt("step", -1) == stepNo) return step
        }
        return null
    }
}
