package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeLoopStepEvaluator(
    private val screenActionTools: Set<String>,
    private val verificationTools: Set<String>
) {
    fun toolMessageContent(output: JSONObject, loopStep: JSONObject): String {
        val verificationState = loopStep.optJSONObject("verification_state") ?: JSONObject()
        return JSONObject()
            .put("tool_output", output)
            .put("loop", loopStep)
            .put("next_instruction", nextLoopInstruction(loopStep.optString("state", "failed")))
            .put("task_loop_v2_instruction", verificationState.optString("next_instruction", "Use concrete evidence before finalizing."))
            .toString()
    }

    fun taskLoopRoundHint(
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
            "The last phone action returned ok, but the business goal is not automatically verified. Inspect after_observe or call accessibility_snapshot_v2/host_wait_for_text before claiming completion."
        } else if (pending != null && pending.length() > 0) {
            "A verification item is still open: ${pending.optString("requirement")}. Satisfy it with an observation/read/status tool or report the blocker."
        } else {
            "Use concrete tool output, verification, or observation evidence before finalizing."
        }
        return "Task loop round $round/$maxRounds finished: tool_calls=$toolCalls, failed_steps=$failedSteps. $verifyHint Keep a strict plan -> one action -> observe -> verify rhythm: after one state-changing action, observe or update the plan before another action. Continue only if more work is needed. If a tool failed, change strategy instead of repeating the same call. If the goal is satisfied, give a concise final report with evidence."
    }

    fun isStateChangingAction(name: String): Boolean {
        if (name in screenActionTools) return true
        return name in setOf(
            "terminal_run",
            "terminal_script",
            "terminal_task_cancel",
            "recover_terminal_backend",
            "ssh_run",
            "ssh_script",
            "ssh_connect",
            "file_push",
            "file_pull",
            "mcp_call",
            "pc_bridge_recover",
            "pc_file_workflow",
            "skill_run",
            "experience_record",
            "experience_update",
            "experience_delete",
            "memory_write",
            "memory_update",
            "memory_delete",
            "procedure_generate",
            "learning_start",
            "learning_record",
            "learning_stop"
        )
    }

    fun evidenceFromStep(
        name: String,
        arguments: JSONObject,
        output: JSONObject,
        verification: JSONObject,
        closedLoop: JSONObject
    ): JSONObject {
        val state = state(output)
        val result = output.optJSONObject("result") ?: JSONObject()
        val verificationOk = verification.optBoolean("ok", false)
        val hasAfterObserve = result.has("after_observe")
        val summary = evidenceSummary(name, output).ifBlank { summary(output) }
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

    fun updateVerificationState(
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
                .put("instruction", "Run accessibility_snapshot_v2 or host_wait_for_text before deciding whether the action worked.")
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

    fun completionReview(
        status: String,
        failedSteps: Int,
        pendingVerification: JSONObject?,
        evidence: JSONArray,
        taskPlan: JSONObject,
        finalText: String
    ): JSONObject {
        val pendingOpen = pendingVerification != null && pendingVerification.length() > 0
        val evidenceCount = evidence.length()
        val doneWhen = taskPlan.optJSONArray("done_when") ?: JSONArray()
        val hasDoneWhen = doneWhen.length() > 0
        val doneWhenSatisfied = !hasDoneWhen || evidenceCount > 0
        val ok = status == "completed" && failedSteps == 0 && !pendingOpen && doneWhenSatisfied
        return JSONObject()
            .put("ok", ok)
            .put("status", if (ok) "verified_or_no_open_loop_items" else "needs_attention")
            .put("loop_status", status)
            .put("failed_steps", failedSteps)
            .put("evidence_count", evidenceCount)
            .put("done_when", JSONArray(doneWhen.toString()))
            .put("done_when_count", doneWhen.length())
            .put("done_when_satisfied", doneWhenSatisfied)
            .put("missing_done_when_evidence", hasDoneWhen && evidenceCount == 0)
            .put("pending_verification", pendingVerification ?: JSONObject())
            .put("final_answer_chars", finalText.length)
            .put(
                "summary",
                if (ok) {
                    "Loop completed with $evidenceCount evidence item(s), $failedSteps failed step(s), no open verification item, and done_when satisfied."
                } else {
                    "Loop needs attention: failed_steps=$failedSteps, pending_verification=$pendingOpen, evidence_count=$evidenceCount, done_when_satisfied=$doneWhenSatisfied."
                }
            )
            .put(
                "instruction",
                if (ok) {
                    "Final answer may cite evidence from task_loop.evidence or tool outputs."
                } else {
                    "Do not treat this as fully verified. Inspect pending_verification, failed steps, done_when, and task reports before continuing."
                }
            )
    }

    fun failurePatternKey(name: String, output: JSONObject): String {
        return "$name:${summary(output).lowercase().take(160)}"
    }

    fun closedLoopStep(
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

    fun buildLoopGuardStop(
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
            .put("summary", summary(output))
            .put("verification", output.optJSONObject("verification") ?: JSONObject())
            .put("latest_observation", result.optJSONObject("after_observe") ?: JSONObject())
            .put("next_actions", JSONArray()
                .put("检查 latest_observation，确认当前屏幕或后端状态。")
                .put("换一个工具或参数重试，不要重复同一个失败调用。")
                .put("如果需要用户介入，明确说明卡住位置和需要用户做什么。")
            )
    }

    fun state(output: JSONObject): String {
        val result = output.optJSONObject("result")
        val verification = output.optJSONObject("verification")
        return when {
            verification?.optBoolean("required", false) == true && !verification.optBoolean("ok", false) -> "failed"
            result?.has("healthy") == true && output.optBoolean("ok", false) -> "success"
            result?.has("ok") == true && !result.optBoolean("ok", true) -> "failed"
            result?.has("available") == true && !result.optBoolean("available", true) -> "failed"
            output.optBoolean("ok", false) -> "success"
            output.optBoolean("needs_confirmation", false) -> "needs_confirmation"
            output.optBoolean("needs_permission", false) -> "needs_permission"
            else -> "failed"
        }
    }

    fun summary(output: JSONObject): String {
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
        return summary(output)
    }

    private fun isVerificationTool(name: String, arguments: JSONObject): Boolean {
        if (name in verificationTools) return true
        if (name == "mcp_call") {
            val remote = arguments.optString("tool", "").lowercase()
            return remote in setOf("snapshot", "screenshot", "waitfor", "filesystem", "clipboard")
        }
        return false
    }

    private fun nextLoopInstruction(state: String): String {
        return when (state) {
            "success" -> "Use this result and its verification/closed_loop fields as evidence. For phone actions, verify the actual screen/business state from after_observe or another observation before final report."
            "needs_confirmation" -> "Do not claim success. Explain that this step needs a fresh user confirmation or choose a read-only fallback."
            "needs_permission" -> "Do not claim success. Explain the missing permission or choose a lower-permission fallback."
            else -> "Do not claim success. Inspect the error and verification field, choose a fallback, retry with corrected arguments, restore if needed, or report the blocker."
        }
    }

    private fun closedLoopInstruction(status: String): String {
        return when (status) {
            "needs_business_verification" -> "Inspect after_observe or run a read-only observation/wait tool. Do not claim the user goal is complete until the observed screen proves it."
            "needs_observation" -> "Run accessibility_snapshot_v2 or another read-only check before deciding whether the step worked."
            "action_failed_retry_or_change_strategy" -> "Retry only with corrected arguments or a different tool. Do not repeat the same failed call blindly."
            "blocked_retry_budget_exhausted" -> "Stop the loop and report the blocker with the latest observation and attempted action."
            else -> "Proceed only with evidence from tool output or verification."
        }
    }
}
