package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class MobileSkillRegistry(
    private val plugins: MobilePluginRegistry,
    private val memory: MobileMemoryStore
) {
    fun list(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query", "").trim()
        val source = arguments.optString("source", "all").lowercase().trim().ifBlank { "all" }
        val includeDetails = arguments.optBoolean("include_details", false)
        val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
        val skills = mutableListOf<JSONObject>()
        if (source == "all" || source == "plugin" || source == "plugins") {
            collectPluginSkills(query, includeDetails, skills)
        }
        if (source == "all" || source == "procedure" || source == "procedures") {
            collectProcedureSkills(query, includeDetails, limit, skills)
        }
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        skills
            .filter { seen.add(it.optString("id")) }
            .take(limit)
            .forEach { result.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("progressive_loading", true)
            .put("detail_tool", "skill_read")
            .put("run_tool", "skill_run")
            .put("source", source)
            .put("query", query)
            .put("count", result.length())
            .put("skills", result)
    }

    fun read(arguments: JSONObject): JSONObject {
        val id = arguments.optString("id").trim()
        if (id.isBlank()) return error("id is required")
        val parsed = parseId(id)
        return when (parsed.optString("source")) {
            "plugin" -> readPluginSkill(parsed)
            "procedure" -> readProcedureSkill(parsed, arguments.optInt("max_bytes", 40000))
            else -> error("Unknown skill id: $id")
        }
    }

    fun procedureRunPlan(id: String, maxBytes: Int = 40000): JSONObject {
        val parsed = parseId(id)
        if (parsed.optString("source") != "procedure") return error("Not a procedure skill: $id")
        val loaded = readProcedureSkill(parsed, maxBytes)
        if (!loaded.optBoolean("ok", false)) return loaded
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("source", "procedure")
            .put("executable", false)
            .put("run_mode", "follow_procedure")
            .put("recommended_next_step", "Use the loaded procedure as task guidance, execute one step with native tools, then verify before continuing.")
            .put("skill", loaded.optJSONObject("skill") ?: JSONObject())
            .put("content", loaded.optString("content", ""))
    }

    fun parseId(id: String): JSONObject {
        val clean = id.trim()
        val parts = clean.split(":", limit = 3)
        return when {
            parts.size >= 3 && parts[0] == "plugin" -> JSONObject()
                .put("source", "plugin")
                .put("plugin_id", parts[1])
                .put("workflow", parts[2])
                .put("id", clean)
            parts.size >= 2 && parts[0] == "procedure" -> JSONObject()
                .put("source", "procedure")
                .put("key", parts[1])
                .put("id", clean)
            else -> JSONObject().put("source", "").put("id", clean)
        }
    }

    private fun collectPluginSkills(query: String, includeDetails: Boolean, out: MutableList<JSONObject>) {
        val response = plugins.list(includeDisabled = false, includeDetails = true)
        val pluginItems = response.optJSONArray("plugins") ?: JSONArray()
        for (index in 0 until pluginItems.length()) {
            val plugin = pluginItems.optJSONObject(index) ?: continue
            val pluginId = plugin.optString("id")
            val workflows = plugin.optJSONArray("workflows") ?: JSONArray()
            for (workflowIndex in 0 until workflows.length()) {
                val workflow = workflows.optJSONObject(workflowIndex) ?: continue
                val name = workflow.optString("name")
                if (name.isBlank()) continue
                val haystack = listOf(pluginId, plugin.optString("name"), plugin.optString("description"), name, workflow.optString("description")).joinToString(" ")
                if (!matches(query, haystack)) continue
                val item = JSONObject()
                    .put("id", "plugin:$pluginId:$name")
                    .put("source", "plugin")
                    .put("plugin_id", pluginId)
                    .put("name", name)
                    .put("title", "${plugin.optString("name", pluginId)} / $name")
                    .put("description", workflow.optString("description", plugin.optString("description")))
                    .put("executable", true)
                    .put("run_tool", "skill_run")
                    .put("detail_tool", "skill_read")
                if (includeDetails) item.put("workflow", workflow).put("plugin", plugin)
                out.add(item)
            }
        }
    }

    private fun collectProcedureSkills(query: String, includeDetails: Boolean, limit: Int, out: MutableList<JSONObject>) {
        val response = if (query.isBlank()) {
            memory.procedureList(JSONObject().put("limit", limit))
        } else {
            memory.procedureSearch(JSONObject().put("query", query).put("limit", limit))
        }
        val items = response.optJSONArray(if (query.isBlank()) "procedures" else "matches") ?: JSONArray()
        for (index in 0 until items.length()) {
            val procedure = items.optJSONObject(index) ?: continue
            val key = procedure.optString("scope_key", procedure.optString("name").removeSuffix(".md"))
            if (key.isBlank()) continue
            val title = procedureTitle(procedure)
            val item = JSONObject()
                .put("id", "procedure:$key")
                .put("source", "procedure")
                .put("name", title)
                .put("title", title)
                .put("description", "Reusable procedure for ${procedure.optString("app", "general")}/${procedure.optString("tool_scope", "")}".trimEnd('/'))
                .put("path", procedure.optString("path"))
                .put("app", procedure.optString("app"))
                .put("tool_scope", procedure.optString("tool_scope"))
                .put("executable", false)
                .put("run_mode", "follow_procedure")
                .put("run_tool", "skill_run")
                .put("detail_tool", "skill_read")
            if (includeDetails) item.put("procedure", procedure)
            out.add(item)
        }
    }

    private fun readPluginSkill(parsed: JSONObject): JSONObject {
        val pluginId = parsed.optString("plugin_id")
        val workflowName = parsed.optString("workflow")
        val workflow = plugins.workflow(pluginId, workflowName)
        if (!workflow.optBoolean("ok", false)) return workflow
        return JSONObject()
            .put("ok", true)
            .put("id", parsed.optString("id"))
            .put("source", "plugin")
            .put("executable", true)
            .put("run_tool", "skill_run")
            .put("skill", JSONObject()
                .put("id", parsed.optString("id"))
                .put("source", "plugin")
                .put("plugin_id", pluginId)
                .put("name", workflowName)
                .put("workflow", workflow.optJSONObject("workflow") ?: JSONObject())
                .put("plugin_path", workflow.optString("plugin_path")))
    }

    private fun readProcedureSkill(parsed: JSONObject, maxBytes: Int): JSONObject {
        val key = parsed.optString("key")
        val read = memory.procedureRead(
            JSONObject()
                .put("path", key)
                .put("max_bytes", maxBytes.coerceIn(1000, 200000))
        )
        if (!read.optBoolean("ok", false)) return read
        val procedure = read.optJSONObject("procedure") ?: JSONObject()
        val title = procedureTitle(procedure)
        return JSONObject()
            .put("ok", true)
            .put("id", parsed.optString("id"))
            .put("source", "procedure")
            .put("executable", false)
            .put("run_mode", "follow_procedure")
            .put("skill", JSONObject()
                .put("id", parsed.optString("id"))
                .put("source", "procedure")
                .put("name", title)
                .put("procedure", procedure))
            .put("content", read.optString("content"))
    }

    private fun procedureTitle(procedure: JSONObject): String {
        val app = procedure.optString("app").ifBlank { procedure.optString("scope_key", "procedure") }
        val scope = procedure.optString("tool_scope")
        return if (scope.isBlank()) app else "$app / $scope"
    }

    private fun matches(query: String, text: String): Boolean {
        val clean = query.trim()
        if (clean.isBlank()) return true
        return text.contains(clean, ignoreCase = true)
    }

    private fun error(message: String): JSONObject {
        return JSONObject().put("ok", false).put("error", message)
    }
}
