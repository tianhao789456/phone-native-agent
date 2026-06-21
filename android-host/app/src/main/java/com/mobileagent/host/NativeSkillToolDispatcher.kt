package com.mobileagent.host

import org.json.JSONObject

class NativeSkillToolDispatcher(
    private val skills: MobileSkillRegistry,
    private val runPluginWorkflow: (JSONObject, Boolean, JSONObject, String?) -> JSONObject
) {
    fun execute(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        return when (name) {
            "skill_list" -> skills.list(arguments)
            "skill_read" -> skills.read(arguments)
            "skill_run" -> runSkill(arguments, actionsApproved, taskPlan, sessionId)
            else -> throw IllegalArgumentException("Skill dispatcher cannot handle tool: $name")
        }
    }

    private fun runSkill(
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        val id = arguments.optString("id").trim()
        if (id.isBlank()) return JSONObject().put("ok", false).put("error", "id is required")
        val parsed = skills.parseId(id)
        return when (parsed.optString("source")) {
            "plugin" -> runPluginWorkflow(
                JSONObject()
                    .put("id", parsed.optString("plugin_id"))
                    .put("workflow", parsed.optString("workflow"))
                    .put("max_steps", arguments.optInt("max_steps", 20)),
                actionsApproved,
                taskPlan,
                sessionId
            ).put("skill_id", id).put("source", "plugin")
            "procedure" -> skills.procedureRunPlan(id)
            else -> JSONObject().put("ok", false).put("error", "Unknown skill id: $id")
        }
    }

    companion object {
        val TOOL_NAMES = setOf("skill_list", "skill_read", "skill_run")
    }
}
