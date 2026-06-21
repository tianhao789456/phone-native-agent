package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeTaskPlanController {
    fun updateTaskPlan(taskPlan: JSONObject, arguments: JSONObject): JSONObject {
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
}
