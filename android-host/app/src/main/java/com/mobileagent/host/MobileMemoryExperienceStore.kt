package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

class MobileMemoryExperienceStore(
    private val experienceFile: File,
    private val paths: () -> JSONObject,
    private val nowIso: () -> String,
    private val nowMs: () -> Long,
    private val readJson: (File, JSONObject) -> JSONObject,
    private val writeJson: (File, JSONObject) -> Unit,
    private val emptyExperience: () -> JSONObject,
    private val generateProcedure: (JSONObject) -> JSONObject
) {
    fun readExperience(): JSONObject = readJson(experienceFile, emptyExperience())

    fun search(arguments: JSONObject): JSONObject {
        val log = readExperience()
        val query = arguments.optString("query", "").trim()
        val app = arguments.optString("app", arguments.optString("package", "")).trim()
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val taskType = arguments.optString("task_type", "").trim()
        val limit = arguments.optInt("limit", 8).coerceIn(1, 50)
        val q = MobileMemoryText.tokenSet(listOf(query, app, scope, taskType).joinToString(" "))
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        for (index in 0 until lessons.length()) {
            val lesson = lessons.optJSONObject(index) ?: continue
            var score = MobileMemoryText.scoreText(listOf(query, app, scope, taskType).joinToString(" "), q, MobileMemoryText.readableExperience(lesson))
            if (app.isNotBlank() && app.equals(lesson.optString("app"), ignoreCase = true)) score += 4
            if (scope.isNotBlank() && scope.equals(lesson.optString("tool_scope"), ignoreCase = true)) score += 4
            if (taskType.isNotBlank() && taskType.equals(lesson.optString("task_type"), ignoreCase = true)) score += 3
            score += MobileMemoryText.confidenceWeight(lesson.optString("confidence", "medium"))
            score += min(lesson.optInt("reinforced", 1) - 1, 3)
            if (score > 0 || (query.isBlank() && app.isBlank() && scope.isBlank())) {
                candidates.add(score to JSONObject(lesson.toString()).put("score", score))
            }
        }
        val matches = JSONArray()
        candidates.sortedByDescending { it.first }.take(limit).forEach { matches.put(it.second) }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put("matches", matches)
            .put("injection", MobileMemoryText.formatExperience(matches))
            .put("paths", paths())
    }

    fun record(arguments: JSONObject): JSONObject {
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray().also { log.put("lessons", it) }
        val app = arguments.optString("app", arguments.optString("package", "general")).trim().ifBlank { "general" }
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val type = MobileMemoryText.sanitizeLessonType(arguments.optString("lesson_type", "general"))
        val taskType = arguments.optString("task_type", "").trim()
        val description = MobileMemoryText.sanitizeSecret(arguments.optString("description", arguments.optString("text", ""))).trim()
        if (description.isBlank()) return error("description is required")
        val sourceTask = arguments.optString("source_task", "").trim()
        val confidence = MobileMemoryText.sanitizeConfidence(arguments.optString("confidence", "medium"))
        val now = nowMs()
        val dupIndex = findDuplicateExperience(lessons, app, scope, type, description)
        val item: JSONObject
        val created: Boolean
        if (dupIndex >= 0) {
            item = lessons.getJSONObject(dupIndex)
            item.put("reinforced", item.optInt("reinforced", 1) + 1)
            item.put("confidence", MobileMemoryText.strongerConfidence(item.optString("confidence", "medium"), confidence))
            item.put("last_seen_ms", now)
            item.put("updated_at_ms", now)
            created = false
        } else {
            item = JSONObject()
                .put("id", log.optJSONObject("stats")?.optInt("total_lessons", lessons.length())?.plus(1) ?: lessons.length() + 1)
                .put("app", app)
                .put("tool_scope", scope)
                .put("task_type", taskType)
                .put("lesson_type", type)
                .put("description", description)
                .put("source_task", sourceTask.take(500))
                .put("confidence", confidence)
                .put("reinforced", 1)
                .put("timestamp_ms", now)
                .put("last_seen_ms", now)
                .put("updated_at_ms", now)
            lessons.put(item)
            created = true
        }
        val stats = log.optJSONObject("stats") ?: JSONObject().also { log.put("stats", it) }
        stats.put("total_lessons", lessons.length())
        log.put("last_updated", nowIso())
        writeJson(experienceFile, log)
        val compacted = if (countScopeLessons(lessons, app, scope) >= 20) compact(JSONObject().put("app", app).put("tool_scope", scope).put("target", 8)) else JSONObject().put("ok", true).put("compacted", false)
        val procedure = if (countScopeLessons(readExperience().optJSONArray("lessons") ?: JSONArray(), app, scope) >= 20) {
            generateProcedure(JSONObject().put("app", app).put("tool_scope", scope))
        } else {
            JSONObject().put("ok", true).put("generated", false).put("reason", "threshold_not_reached")
        }
        return JSONObject()
            .put("ok", true)
            .put("created", created)
            .put("lesson", item)
            .put("auto_compaction", compacted)
            .put("auto_procedure", procedure)
            .put("paths", paths())
    }

    fun update(arguments: JSONObject): JSONObject {
        val id = arguments.optInt("id", -1)
        if (id <= 0) return error("id is required")
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            if (item.optInt("id") != id) continue
            if (arguments.has("confidence")) item.put("confidence", MobileMemoryText.sanitizeConfidence(arguments.optString("confidence")))
            if (arguments.has("lesson_type")) item.put("lesson_type", MobileMemoryText.sanitizeLessonType(arguments.optString("lesson_type")))
            if (arguments.has("description")) item.put("description", MobileMemoryText.sanitizeSecret(arguments.optString("description")).take(1200))
            item.put("updated_at_ms", nowMs())
            item.put("last_seen_ms", nowMs())
            log.put("last_updated", nowIso())
            writeJson(experienceFile, log)
            return JSONObject().put("ok", true).put("lesson", item).put("paths", paths())
        }
        return error("experience id not found: $id")
    }

    fun delete(arguments: JSONObject): JSONObject {
        val id = arguments.optInt("id", -1)
        if (id <= 0) return error("id is required")
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        val kept = JSONArray()
        var removed: JSONObject? = null
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            if (item.optInt("id") == id) removed = item else kept.put(item)
        }
        if (removed == null) return error("experience id not found: $id")
        log.put("lessons", kept)
        val stats = log.optJSONObject("stats") ?: JSONObject().also { log.put("stats", it) }
        stats.put("total_lessons", kept.length())
        log.put("last_updated", nowIso())
        writeJson(experienceFile, log)
        return JSONObject().put("ok", true).put("deleted", removed).put("paths", paths())
    }

    fun compact(arguments: JSONObject): JSONObject {
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        val app = arguments.optString("app", "").trim()
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val target = arguments.optInt("target", 8).coerceIn(1, 20)
        val selected = mutableListOf<JSONObject>()
        val others = JSONArray()
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            val appMatch = app.isBlank() || app.equals(item.optString("app"), ignoreCase = true)
            val scopeMatch = scope.isBlank() || scope.equals(item.optString("tool_scope"), ignoreCase = true)
            if (appMatch && scopeMatch) selected.add(item) else others.put(item)
        }
        if (selected.size <= target) {
            return JSONObject().put("ok", true).put("compacted", false).put("before", selected.size).put("after", selected.size)
        }
        val kept = selected
            .sortedWith(
                compareByDescending<JSONObject> { MobileMemoryText.confidenceWeight(it.optString("confidence", "medium")) }
                    .thenByDescending { it.optInt("reinforced", 1) }
                    .thenByDescending { it.optLong("last_seen_ms", 0L) }
            )
            .take(target)
        kept.forEach { item ->
            item.put("compacted", true)
            item.put("updated_at_ms", nowMs())
            others.put(item)
        }
        val stats = log.optJSONObject("stats") ?: JSONObject().also { log.put("stats", it) }
        stats.put("total_lessons", others.length())
        stats.put("compactions", stats.optInt("compactions", 0) + 1)
        log.put("lessons", others)
        log.put("last_updated", nowIso())
        val history = log.optJSONArray("compaction_history") ?: JSONArray().also { log.put("compaction_history", it) }
        history.put(JSONObject().put("app", app).put("tool_scope", scope).put("before", selected.size).put("after", kept.size).put("timestamp", nowIso()))
        writeJson(experienceFile, log)
        return JSONObject().put("ok", true).put("compacted", true).put("before", selected.size).put("after", kept.size).put("paths", paths())
    }

    fun heuristicExperienceFromTool(tool: String, state: String, summary: String, sourceTask: String): JSONObject? {
        val scope = when {
            tool.startsWith("mcp_") -> "windows_mcp"
            tool.startsWith("host_") -> "phone"
            tool.startsWith("terminal") || tool.startsWith("termux") -> "termux"
            tool.startsWith("ssh") || tool.startsWith("file_") -> "ssh"
            else -> return null
        }
        val type = if (state == "success") "successful_navigation" else "failed_approach"
        val desc = when (scope) {
            "windows_mcp" -> if (state == "success") "Windows MCP tool $tool succeeded. Reuse this path for similar desktop tasks: inspect status/tools before remote calls; evidence: $summary" else "Windows MCP tool $tool failed. Check endpoint, auth, schema, and tool result before retrying; failure: $summary"
            "phone" -> if (state == "success") "Phone control tool $tool succeeded. Verify screen state after the action; evidence: $summary" else "Phone control tool $tool failed. Change target/selector or observe screen before retrying; failure: $summary"
            else -> if (state == "success") "Termux tool $tool succeeded; evidence: $summary" else "Termux tool $tool failed; diagnose backend/timeout before retrying: $summary"
        }.take(900)
        return JSONObject()
            .put("app", "general")
            .put("tool_scope", scope)
            .put("task_type", "")
            .put("lesson_type", type)
            .put("description", desc)
            .put("source_task", sourceTask.take(500))
            .put("confidence", if (state == "success") "medium" else "low")
    }

    private fun findDuplicateExperience(array: JSONArray, app: String, scope: String, type: String, description: String): Int {
        val newTokens = MobileMemoryText.tokenSet(description)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (!item.optString("app").equals(app, ignoreCase = true)) continue
            if (!item.optString("tool_scope").equals(scope, ignoreCase = true)) continue
            if (item.optString("lesson_type") != type) continue
            if (MobileMemoryText.jaccard(newTokens, MobileMemoryText.tokenSet(item.optString("description"))) >= 0.5) return index
        }
        return -1
    }

    private fun countScopeLessons(array: JSONArray, app: String, scope: String): Int {
        var count = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("app").equals(app, ignoreCase = true) && item.optString("tool_scope").equals(scope, ignoreCase = true)) count += 1
        }
        return count
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message).put("paths", paths())
}
