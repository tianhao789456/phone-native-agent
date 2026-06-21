package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object DiagnosticTraceFormatter {
    fun toolTraceItem(tool: String, args: JSONObject, output: JSONObject, step: Int): JSONObject {
        return JSONObject()
            .put("step", step)
            .put("round", 0)
            .put("tool", tool)
            .put("arguments", JSONObject(args.toString()))
            .put("output", JSONObject(output.toString()))
            .put("state", uiToolState(output))
            .put("duration_ms", 0)
            .put("created_at", System.currentTimeMillis() / 1000.0)
    }

    fun diagnosticLoop(trace: JSONArray): JSONObject {
        var failed = 0
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            if (item.optString("state") != "success") failed += 1
        }
        return JSONObject()
            .put("status", if (failed == 0) "completed" else "completed_with_failures")
            .put("rounds", 0)
            .put("max_rounds", 0)
            .put("steps", trace.length())
            .put("failed_steps", failed)
            .put("plan", JSONObject().put("status", "diagnostic").put("goal", "manual tool control"))
            .put("trace", trace)
    }

    fun uiToolState(output: JSONObject): String {
        val verification = output.optJSONObject("verification")
        val result = output.optJSONObject("result")
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

    fun toolStepBrief(output: JSONObject): String {
        val verification = output.optJSONObject("verification")
        val verificationSummary = verification?.optString("summary", "") ?: ""
        if (verificationSummary.isNotBlank()) return verificationSummary.take(240)
        val error = output.optString("error", "")
        if (error.isNotBlank()) return error.take(240)
        val result = output.optJSONObject("result")
        val nestedError = result?.optString("error", "") ?: ""
        if (nestedError.isNotBlank()) return nestedError.take(240)
        return output.toString().take(240)
    }

    fun reconnectSummary(result: JSONObject): String {
        val after = result.optJSONObject("after") ?: JSONObject()
        val terminal = after.optJSONObject("terminal") ?: JSONObject()
        val accessibility = after.optJSONObject("accessibility") ?: JSONObject()
        val terminalText = when (terminal.optString("status")) {
            "ok" -> "终端正常"
            "disabled" -> "终端未启用"
            "" -> "终端未知"
            else -> "终端${terminal.optString("status")}"
        }
        val accessibilityText = if (accessibility.optBoolean("connected", false)) {
            "无障碍已启用"
        } else {
            "无障碍未启用"
        }
        val recovery = result.optJSONObject("recovery") ?: JSONObject()
        val recoveryText = when {
            recovery.optBoolean("skipped", false) -> "未执行"
            recovery.optBoolean("ok", false) -> "恢复成功"
            recovery.length() > 0 -> "重连中"
            else -> "未执行"
        }
        return "重连完成: $terminalText | $accessibilityText | $recoveryText"
    }
}
