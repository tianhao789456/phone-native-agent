package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

class MobileMemoryStore(context: Context) {
    private val workspaceRoot = File(context.filesDir, "workspace")
    private val root = File(workspaceRoot, "memory")
    private val profileFile = File(root, "user_profile.json")
    private val experienceFile = File(root, "experience_log.json")
    private val reflectionsFile = File(root, "task_reflections.jsonl")
    private val proceduresDir = File(root, "procedures")
    private val demosDir = File(root, "demos")
    private val activeLearningFile = File(demosDir, "active_learning.json")

    fun paths(): JSONObject {
        ensureFiles()
        return JSONObject()
            .put("root", root.canonicalPath)
            .put("user_profile", relative(profileFile))
            .put("experience_log", relative(experienceFile))
            .put("task_reflections", relative(reflectionsFile))
            .put("procedures", relative(proceduresDir))
            .put("demos", relative(demosDir))
    }

    fun summary(query: String = "", limit: Int = 80): JSONObject {
        ensureFiles()
        val profile = readProfile()
        val experience = readExperience()
        val lessons = experience.optJSONArray("lessons") ?: JSONArray()
        val groups = JSONObject()
        var shown = 0
        for (index in 0 until lessons.length()) {
            if (shown >= limit.coerceIn(1, 200)) break
            val lesson = lessons.optJSONObject(index) ?: continue
            val readable = readableExperience(lesson)
            if (query.isNotBlank() && scoreText(query, tokenSet(query), readable) <= 0) continue
            val groupKey = listOf(
                lesson.optString("app", "general").ifBlank { "general" },
                lesson.optString("tool_scope", "").ifBlank { "general" }
            ).joinToString("/")
            val array = groups.optJSONArray(groupKey) ?: JSONArray().also { groups.put(groupKey, it) }
            array.put(JSONObject(lesson.toString()))
            shown += 1
        }
        return JSONObject()
            .put("ok", true)
            .put("profile", profile)
            .put("experience_stats", experience.optJSONObject("stats") ?: JSONObject())
            .put("experience_groups", groups)
            .put("procedures", procedureList(JSONObject().put("limit", 200)).optJSONArray("procedures") ?: JSONArray())
            .put("learning", learningStatus())
            .put("paths", paths())
    }

    fun query(question: String, limit: Int = 5): JSONObject {
        val memory = searchMemory(question, limit)
        val answers = JSONArray()
        val q = tokenSet(question)
        val insights = memory.optJSONArray("matches") ?: JSONArray()
        for (index in 0 until insights.length()) {
            val item = insights.optJSONObject(index) ?: continue
            val text = item.optString("text")
            if (scoreTokens(q, tokenSet(text)) >= 2) {
                answers.put(item)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("can_answer", answers.length() > 0)
            .put("answer", if (answers.length() > 0) answers.optJSONObject(0)?.optString("text", "") else "")
            .put("matches", answers)
            .put("memory", memory)
            .put("paths", paths())
    }

    fun searchMemory(query: String, limit: Int = 8): JSONObject {
        ensureFiles()
        val profile = readProfile()
        val q = tokenSet(query)
        val candidates = mutableListOf<Pair<Int, JSONObject>>()

        fun add(kind: String, text: String, path: String, extra: JSONObject = JSONObject()) {
            val score = scoreText(query, q, text)
            if (score <= 0 && query.isNotBlank()) return
            val item = JSONObject(extra.toString())
                .put("kind", kind)
                .put("text", text)
                .put("path", path)
                .put("score", score)
            candidates.add(score to item)
        }

        val profileObj = profile.optJSONObject("profile") ?: JSONObject()
        profileObj.keys().forEach { key ->
            val value = profileObj.opt(key)
            if (value != null && value != JSONObject.NULL) add("profile", "$key: $value", "memory/user_profile.json")
        }
        val arrays = listOf("preferences", "environment", "do_not_do", "insights", "task_history")
        arrays.forEach { key ->
            val array = profile.optJSONArray(key) ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(key, readableMemoryItem(item), "memory/user_profile.json", item)
            }
        }

        val matches = JSONArray()
        candidates
            .sortedWith(compareByDescending<Pair<Int, JSONObject>> { it.first }.thenByDescending { it.second.optLong("updated_at_ms", it.second.optLong("timestamp_ms", 0L)) })
            .take(limit.coerceIn(1, 50))
            .forEach { matches.put(it.second) }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put("matches", matches)
            .put("paths", paths())
    }

    fun writeMemory(arguments: JSONObject): JSONObject {
        ensureFiles()
        val profile = readProfile()
        val type = sanitizeMemoryType(arguments.optString("type", "preference"))
        val key = arguments.optString("key", "").trim().ifBlank { type }
        val value = sanitizeSecret(arguments.optString("value", arguments.optString("text", ""))).trim()
        if (value.isBlank()) return error("value is required")
        val confidence = sanitizeConfidence(arguments.optString("confidence", "medium"))
        val source = arguments.optString("source", "manual")
        val targetArray = when (type) {
            "user_preference", "preference" -> "preferences"
            "environment" -> "environment"
            "do_not_do" -> "do_not_do"
            else -> "insights"
        }
        val array = profile.optJSONArray(targetArray) ?: JSONArray().also { profile.put(targetArray, it) }
        val now = nowMs()
        val newText = "$key: $value"
        val duplicateIndex = findDuplicate(array, newText, key)
        val stored: JSONObject
        val created: Boolean
        if (duplicateIndex >= 0) {
            stored = array.getJSONObject(duplicateIndex)
            stored.put("value", value)
            stored.put("text", newText)
            stored.put("reinforced", stored.optInt("reinforced", 1) + 1)
            stored.put("confidence", strongerConfidence(stored.optString("confidence", "medium"), confidence))
            stored.put("last_seen_ms", now)
            stored.put("updated_at_ms", now)
            created = false
        } else {
            stored = JSONObject()
                .put("type", type)
                .put("key", key)
                .put("value", value)
                .put("text", newText)
                .put("confidence", confidence)
                .put("source", source)
                .put("reinforced", 1)
                .put("timestamp_ms", now)
                .put("last_seen_ms", now)
                .put("updated_at_ms", now)
            array.put(stored)
            created = true
        }
        profile.put("last_updated", nowIso())
        writeJson(profileFile, profile)
        return JSONObject()
            .put("ok", true)
            .put("created", created)
            .put("target", targetArray)
            .put("item", stored)
            .put("paths", paths())
    }

    fun recordTask(task: String, status: String, finalAnswer: String, toolsUsed: JSONArray, appsUsed: JSONArray, runId: String): JSONObject {
        ensureFiles()
        val profile = readProfile()
        val history = profile.optJSONArray("task_history") ?: JSONArray().also { profile.put("task_history", it) }
        val stats = profile.optJSONObject("stats") ?: JSONObject().also { profile.put("stats", it) }
        val appUsage = profile.optJSONObject("app_usage") ?: JSONObject().also { profile.put("app_usage", it) }
        stats.put("total_tasks", stats.optInt("total_tasks", 0) + 1)
        if (status == "completed" || status == "no_tools") {
            stats.put("completed_tasks", stats.optInt("completed_tasks", 0) + 1)
        } else {
            stats.put("failed_tasks", stats.optInt("failed_tasks", 0) + 1)
        }
        val item = JSONObject()
            .put("id", history.length() + 1)
            .put("timestamp", nowIso())
            .put("timestamp_ms", nowMs())
            .put("task", task.take(1000))
            .put("status", status)
            .put("final_answer", sanitizeSecret(finalAnswer.take(2000)))
            .put("tools_used", toolsUsed)
            .put("apps_used", appsUsed)
            .put("run_id", runId)
        history.put(item)
        for (index in 0 until appsUsed.length()) {
            val app = appsUsed.optString(index, "").trim()
            if (app.isBlank()) continue
            val usage = appUsage.optJSONObject(app) ?: JSONObject().put("count", 0).also { appUsage.put(app, it) }
            usage.put("count", usage.optInt("count", 0) + 1)
            usage.put("last_used_ms", nowMs())
            usage.put("last_used", nowIso())
        }
        trimArray(profile, "task_history", 200)
        profile.put("last_updated", nowIso())
        writeJson(profileFile, profile)
        return JSONObject().put("ok", true).put("task", item).put("paths", paths())
    }

    fun searchExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        val log = readExperience()
        val query = arguments.optString("query", "").trim()
        val app = arguments.optString("app", arguments.optString("package", "")).trim()
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val taskType = arguments.optString("task_type", "").trim()
        val limit = arguments.optInt("limit", 8).coerceIn(1, 50)
        val q = tokenSet(listOf(query, app, scope, taskType).joinToString(" "))
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        for (index in 0 until lessons.length()) {
            val lesson = lessons.optJSONObject(index) ?: continue
            var score = scoreText(listOf(query, app, scope, taskType).joinToString(" "), q, readableExperience(lesson))
            if (app.isNotBlank() && app.equals(lesson.optString("app"), ignoreCase = true)) score += 4
            if (scope.isNotBlank() && scope.equals(lesson.optString("tool_scope"), ignoreCase = true)) score += 4
            if (taskType.isNotBlank() && taskType.equals(lesson.optString("task_type"), ignoreCase = true)) score += 3
            score += confidenceWeight(lesson.optString("confidence", "medium"))
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
            .put("injection", formatExperience(matches))
            .put("paths", paths())
    }

    fun recordExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray().also { log.put("lessons", it) }
        val app = arguments.optString("app", arguments.optString("package", "general")).trim().ifBlank { "general" }
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val type = sanitizeLessonType(arguments.optString("lesson_type", "general"))
        val taskType = arguments.optString("task_type", "").trim()
        val description = sanitizeSecret(arguments.optString("description", arguments.optString("text", ""))).trim()
        if (description.isBlank()) return error("description is required")
        val sourceTask = arguments.optString("source_task", "").trim()
        val confidence = sanitizeConfidence(arguments.optString("confidence", "medium"))
        val now = nowMs()
        val dupIndex = findDuplicateExperience(lessons, app, scope, type, description)
        val item: JSONObject
        val created: Boolean
        if (dupIndex >= 0) {
            item = lessons.getJSONObject(dupIndex)
            item.put("reinforced", item.optInt("reinforced", 1) + 1)
            item.put("confidence", strongerConfidence(item.optString("confidence", "medium"), confidence))
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
        val compacted = if (countScopeLessons(lessons, app, scope) >= 20) compactExperience(JSONObject().put("app", app).put("tool_scope", scope).put("target", 8)) else JSONObject().put("ok", true).put("compacted", false)
        val procedure = if (countScopeLessons(readExperience().optJSONArray("lessons") ?: JSONArray(), app, scope) >= 20) {
            procedureGenerate(JSONObject().put("app", app).put("tool_scope", scope))
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

    fun updateExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        val id = arguments.optInt("id", -1)
        if (id <= 0) return error("id is required")
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            if (item.optInt("id") != id) continue
            if (arguments.has("confidence")) item.put("confidence", sanitizeConfidence(arguments.optString("confidence")))
            if (arguments.has("lesson_type")) item.put("lesson_type", sanitizeLessonType(arguments.optString("lesson_type")))
            if (arguments.has("description")) item.put("description", sanitizeSecret(arguments.optString("description")).take(1200))
            item.put("updated_at_ms", nowMs())
            item.put("last_seen_ms", nowMs())
            log.put("last_updated", nowIso())
            writeJson(experienceFile, log)
            return JSONObject().put("ok", true).put("lesson", item).put("paths", paths())
        }
        return error("experience id not found: $id")
    }

    fun deleteExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
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

    fun compactExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
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
                compareByDescending<JSONObject> { confidenceWeight(it.optString("confidence", "medium")) }
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

    fun appendReflection(reflection: JSONObject): JSONObject {
        ensureFiles()
        val item = JSONObject(reflection.toString())
            .put("timestamp", nowIso())
            .put("timestamp_ms", nowMs())
        reflectionsFile.appendText(item.toString() + "\n", Charsets.UTF_8)
        return JSONObject().put("ok", true).put("reflection", item).put("path", relative(reflectionsFile))
    }

    fun buildInjectionContext(userMessage: String): JSONObject {
        val memory = searchMemory(userMessage, 5)
        val exp = searchExperience(JSONObject().put("query", userMessage).put("limit", 8))
        val procedures = procedureSearch(JSONObject().put("query", userMessage).put("limit", 3))
        val memoryText = formatMemory(memory.optJSONArray("matches") ?: JSONArray())
        val experienceText = formatExperience(exp.optJSONArray("matches") ?: JSONArray())
        val procedureText = formatProcedures(procedures.optJSONArray("matches") ?: JSONArray())
        val parts = mutableListOf<String>()
        if (memoryText.isNotBlank()) parts.add("## Relevant Memory\n$memoryText")
        if (procedureText.isNotBlank()) parts.add("## Relevant Procedure\n$procedureText")
        if (experienceText.isNotBlank()) parts.add("## Relevant Experience\n$experienceText")
        val content = parts.joinToString("\n\n").take(6000)
        return JSONObject()
            .put("ok", true)
            .put("injected", content.isNotBlank())
            .put("content", content)
            .put("memory_count", memory.optInt("count", 0))
            .put("procedure_count", procedures.optInt("count", 0))
            .put("experience_count", exp.optInt("count", 0))
            .put("memory", memory.optJSONArray("matches") ?: JSONArray())
            .put("procedures", procedures.optJSONArray("matches") ?: JSONArray())
            .put("experience", exp.optJSONArray("matches") ?: JSONArray())
    }

    fun procedureList(arguments: JSONObject): JSONObject {
        ensureFiles()
        val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
        val files = proceduresDir.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val procedures = JSONArray()
        files.take(limit).forEach { file ->
            procedures.put(procedureMeta(file))
        }
        return JSONObject().put("ok", true).put("count", procedures.length()).put("procedures", procedures).put("paths", paths())
    }

    fun procedureRead(arguments: JSONObject): JSONObject {
        ensureFiles()
        val file = resolveProcedureFile(arguments)
        if (!file.isFile) return error("procedure not found: ${relative(file)}")
        return JSONObject()
            .put("ok", true)
            .put("procedure", procedureMeta(file))
            .put("content", file.readText(Charsets.UTF_8).take(arguments.optInt("max_bytes", 40000).coerceIn(1000, 200000)))
            .put("paths", paths())
    }

    fun procedureSearch(arguments: JSONObject): JSONObject {
        ensureFiles()
        val query = arguments.optString("query", "").trim()
        val app = arguments.optString("app", arguments.optString("package", "")).trim()
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val limit = arguments.optInt("limit", 5).coerceIn(1, 50)
        val q = tokenSet(listOf(query, app, scope).joinToString(" "))
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        val files = proceduresDir.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) } ?: emptyArray()
        for (file in files) {
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            val meta = procedureMeta(file)
            var score = scoreText(listOf(query, app, scope).joinToString(" "), q, content + " " + meta.toString())
            if (app.isNotBlank() && app.equals(meta.optString("app"), ignoreCase = true)) score += 6
            if (scope.isNotBlank() && scope.equals(meta.optString("tool_scope"), ignoreCase = true)) score += 6
            if (query.isBlank() && app.isBlank() && scope.isBlank()) score += 1
            if (score <= 0) continue
            candidates.add(score to meta.put("score", score).put("preview", procedurePreview(content)))
        }
        val matches = JSONArray()
        candidates.sortedByDescending { it.first }.take(limit).forEach { matches.put(it.second) }
        return JSONObject().put("ok", true).put("query", query).put("count", matches.length()).put("matches", matches).put("paths", paths())
    }

    fun procedureGenerate(arguments: JSONObject): JSONObject {
        ensureFiles()
        val app = arguments.optString("app", arguments.optString("package", "general")).trim().ifBlank { "general" }
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val targetFile = resolveProcedureFile(JSONObject().put("app", app).put("tool_scope", scope))
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        val selected = mutableListOf<JSONObject>()
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            val appMatch = app.equals(item.optString("app"), ignoreCase = true) || app == "general"
            val scopeMatch = scope.isBlank() || scope.equals(item.optString("tool_scope"), ignoreCase = true)
            if (appMatch && scopeMatch) selected.add(item)
        }
        if (selected.isEmpty()) return error("no experience lessons found for app=$app tool_scope=$scope")
        val markdown = buildProcedureMarkdown(app, scope, selected)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(markdown, Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("generated", true)
            .put("procedure", procedureMeta(targetFile))
            .put("source_lessons", selected.size)
            .put("paths", paths())
    }

    fun learningStart(arguments: JSONObject): JSONObject {
        ensureFiles()
        if (activeLearningFile.isFile) {
            val active = readJson(activeLearningFile, JSONObject())
            return JSONObject().put("ok", true).put("already_active", true).put("session", active).put("paths", paths())
        }
        val label = arguments.optString("label", "manual-demo").trim().ifBlank { "manual-demo" }
        val app = arguments.optString("app", "unknown").trim().ifBlank { "unknown" }
        val sessionId = "${nowMs()}-${slug(label)}"
        val sessionDir = File(demosDir, sessionId)
        sessionDir.mkdirs()
        val session = JSONObject()
            .put("id", sessionId)
            .put("label", label)
            .put("app", app)
            .put("tool_scope", arguments.optString("tool_scope", "phone"))
            .put("description", arguments.optString("description", ""))
            .put("started_at", nowIso())
            .put("started_at_ms", nowMs())
            .put("dir", relative(sessionDir))
            .put("trace", relative(File(sessionDir, "trace.jsonl")))
        writeJson(File(sessionDir, "meta.json"), session)
        writeJson(activeLearningFile, session)
        appendLearningEvent(session, "start", JSONObject().put("summary", "learning started"))
        return JSONObject().put("ok", true).put("started", true).put("session", session).put("paths", paths())
    }

    fun learningRecord(arguments: JSONObject): JSONObject {
        ensureFiles()
        if (!activeLearningFile.isFile) return error("no active learning session")
        val session = readJson(activeLearningFile, JSONObject())
        val details = arguments.optJSONObject("details") ?: JSONObject()
        val event = appendLearningEvent(
            session,
            arguments.optString("event_type", arguments.optString("event", "note")),
            JSONObject(details.toString())
                .put("app", arguments.optString("app", session.optString("app", "unknown")))
                .put("summary", arguments.optString("summary", ""))
                .put("screen_summary", arguments.optString("screen_summary", ""))
        )
        return JSONObject().put("ok", true).put("event", event).put("session", session).put("paths", paths())
    }

    fun learningStop(arguments: JSONObject): JSONObject {
        ensureFiles()
        if (!activeLearningFile.isFile) return error("no active learning session")
        val session = readJson(activeLearningFile, JSONObject())
        val sessionDir = File(demosDir, session.optString("id"))
        appendLearningEvent(session, "stop", JSONObject().put("summary", "learning stopped"))
        val traceFile = File(sessionDir, "trace.jsonl")
        val events = traceFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty()
        val app = arguments.optString("app", session.optString("app", "unknown")).ifBlank { "unknown" }
        val scope = arguments.optString("tool_scope", session.optString("tool_scope", "phone")).ifBlank { "phone" }
        val summaryText = events.takeLast(40).joinToString("\n") { line -> line.take(600) }
        val summaryFile = File(sessionDir, "summary.md")
        val summaryMd = """
            # Learning Demo ${session.optString("label")}

            - app: $app
            - tool_scope: $scope
            - events: ${events.size}
            - started_at: ${session.optString("started_at")}
            - stopped_at: ${nowIso()}

            ## Trace Summary

            ```jsonl
            $summaryText
            ```
        """.trimIndent()
        summaryFile.writeText(summaryMd, Charsets.UTF_8)
        val experience = recordExperience(
            JSONObject()
                .put("app", app)
                .put("tool_scope", scope)
                .put("lesson_type", "ui_knowledge")
                .put("description", "Learning demo '${session.optString("label")}' recorded ${events.size} events for $app/$scope. Reuse the generated demo trace at ${relative(traceFile)} before repeating this workflow.")
                .put("source_task", summaryMd.take(500))
                .put("confidence", "low")
        )
        val procedure = procedureGenerate(JSONObject().put("app", app).put("tool_scope", scope))
        activeLearningFile.delete()
        return JSONObject()
            .put("ok", true)
            .put("stopped", true)
            .put("session", session)
            .put("events", events.size)
            .put("summary", relative(summaryFile))
            .put("experience", experience)
            .put("procedure", procedure)
            .put("paths", paths())
    }

    fun learningStatus(): JSONObject {
        ensureFiles()
        val active = if (activeLearningFile.isFile) readJson(activeLearningFile, JSONObject()) else JSONObject()
        return JSONObject()
            .put("ok", true)
            .put("active", active.length() > 0)
            .put("session", active)
            .put("paths", paths())
    }

    fun heuristicExperienceFromTool(tool: String, state: String, summary: String, sourceTask: String): JSONObject? {
        val scope = when {
            tool.startsWith("mcp_") -> "windows_mcp"
            tool.startsWith("host_") -> "phone"
            tool.startsWith("terminal") || tool.startsWith("termux") -> "termux"
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

    private fun ensureFiles() {
        root.mkdirs()
        proceduresDir.mkdirs()
        demosDir.mkdirs()
        if (!profileFile.isFile) writeJson(profileFile, emptyProfile())
        if (!experienceFile.isFile) writeJson(experienceFile, emptyExperience())
        if (!reflectionsFile.exists()) reflectionsFile.writeText("", Charsets.UTF_8)
    }

    private fun emptyProfile(): JSONObject {
        return JSONObject()
            .put("schema_version", 1)
            .put("created_at", nowIso())
            .put("last_updated", nowIso())
            .put("stats", JSONObject().put("total_tasks", 0).put("completed_tasks", 0).put("failed_tasks", 0))
            .put("profile", JSONObject().put("primary_language", "zh-CN"))
            .put("app_usage", JSONObject())
            .put("preferences", JSONArray())
            .put("environment", JSONArray())
            .put("do_not_do", JSONArray())
            .put("insights", JSONArray())
            .put("task_history", JSONArray())
    }

    private fun emptyExperience(): JSONObject {
        return JSONObject()
            .put("schema_version", 1)
            .put("created_at", nowIso())
            .put("last_updated", nowIso())
            .put("stats", JSONObject().put("total_lessons", 0).put("tasks_processed", 0).put("compactions", 0))
            .put("compaction_history", JSONArray())
            .put("lessons", JSONArray())
    }

    private fun readProfile(): JSONObject = readJson(profileFile, emptyProfile())
    private fun readExperience(): JSONObject = readJson(experienceFile, emptyExperience())

    private fun readJson(file: File, fallback: JSONObject): JSONObject {
        ensureFilesParentOnly()
        return if (file.isFile) runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse { fallback } else fallback
    }

    private fun ensureFilesParentOnly() {
        root.mkdirs()
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun readableMemoryItem(item: JSONObject): String {
        return item.optString("text").ifBlank {
            listOf(item.optString("key"), item.optString("value"), item.optString("task"), item.optString("final_answer"))
                .filter { it.isNotBlank() }
                .joinToString(": ")
        }
    }

    private fun readableExperience(item: JSONObject): String {
        return listOf(
            item.optString("app"),
            item.optString("tool_scope"),
            item.optString("task_type"),
            item.optString("lesson_type"),
            item.optString("description")
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun formatMemory(items: JSONArray): String {
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            lines.add("- ${item.optString("text").ifBlank { readableMemoryItem(item) }.take(500)}")
        }
        return lines.joinToString("\n")
    }

    private fun formatExperience(items: JSONArray): String {
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val prefix = when (item.optString("lesson_type")) {
                "successful_navigation" -> "worked"
                "failed_approach" -> "avoid"
                "timing" -> "timing"
                "ui_knowledge" -> "ui"
                else -> "note"
            }
            val scope = listOf(item.optString("app"), item.optString("tool_scope")).filter { it.isNotBlank() }.joinToString("/")
            lines.add("- [$prefix${if (scope.isNotBlank()) " $scope" else ""}] ${item.optString("description").take(700)}")
        }
        return lines.joinToString("\n")
    }

    private fun formatProcedures(items: JSONArray): String {
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            lines.add("- [procedure ${item.optString("scope_key")}] ${item.optString("preview").take(700)}")
        }
        return lines.joinToString("\n")
    }

    private fun buildProcedureMarkdown(app: String, scope: String, lessons: List<JSONObject>): String {
        val sorted = lessons.sortedWith(
            compareByDescending<JSONObject> { confidenceWeight(it.optString("confidence", "medium")) }
                .thenByDescending { it.optInt("reinforced", 1) }
                .thenByDescending { it.optLong("last_seen_ms", 0L) }
        )
        fun section(types: Set<String>, fallback: String): String {
            val lines = sorted.filter { it.optString("lesson_type") in types }.take(8).map {
                "- ${it.optString("description").take(500)}"
            }
            return if (lines.isEmpty()) "- $fallback" else lines.joinToString("\n")
        }
        val title = if (scope.isNotBlank()) scope else app
        return """
            |# Procedure: $title
            |
            |## Scope
            |- app: $app
            |- tool_scope: ${scope.ifBlank { "general" }}
            |
            |## Pre-observe
            |${section(setOf("ui_knowledge", "environment"), "Observe current app/screen and confirm this procedure matches the requested task.")}
            |
            |## Standard Steps
            |${section(setOf("successful_navigation", "general"), "Inspect available tools/status first, then execute one action/tool call at a time.")}
            |
            |## Verification
            |- Verify the intended state with a read-only observation, tool output, file read, or MCP status/result before claiming completion.
            |
            |## Failure Handling
            |${section(setOf("failed_approach", "timing"), "If a step fails, change selector/arguments or inspect health/status before retrying. Stop after repeated identical failures.")}
            |
            |## Source Experience Summary
            |${sorted.take(12).joinToString("\n") { "- id=${it.optInt("id")} type=${it.optString("lesson_type")} confidence=${it.optString("confidence")} reinforced=${it.optInt("reinforced", 1)} last_seen=${it.optLong("last_seen_ms", 0L)}" }}
            |
            |## Updated At
            |${nowIso()}
        """.trimMargin() + "\n"
    }

    private fun resolveProcedureFile(arguments: JSONObject): File {
        val rawPath = arguments.optString("path", "").trim()
        if (rawPath.isNotBlank()) {
            val name = rawPath.substringAfterLast('/').substringAfterLast('\\')
            return File(proceduresDir, if (name.endsWith(".md")) name else "$name.md")
        }
        val app = arguments.optString("app", arguments.optString("package", "general")).trim().ifBlank { "general" }
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val name = if (scope.equals("windows_mcp", ignoreCase = true)) {
            "windows_mcp.md"
        } else {
            "${slug(app)}${if (scope.isNotBlank()) "_${slug(scope)}" else ""}.md"
        }
        return File(proceduresDir, name)
    }

    private fun procedureMeta(file: File): JSONObject {
        val name = file.nameWithoutExtension
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        val app = Regex("(?m)^\\s*- app: (.+)$").find(content)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val scope = Regex("(?m)^\\s*- tool_scope: (.+)$").find(content)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return JSONObject()
            .put("path", relative(file))
            .put("name", file.name)
            .put("scope_key", name)
            .put("app", app)
            .put("tool_scope", scope)
            .put("bytes", file.length())
            .put("modified_ms", file.lastModified())
    }

    private fun procedurePreview(content: String): String {
        return content
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
            .take(8)
            .joinToString(" ")
            .take(900)
    }

    private fun appendLearningEvent(session: JSONObject, eventType: String, details: JSONObject): JSONObject {
        val sessionDir = File(demosDir, session.optString("id"))
        sessionDir.mkdirs()
        val event = JSONObject()
            .put("timestamp", nowIso())
            .put("timestamp_ms", nowMs())
            .put("event_type", eventType)
            .put("app", details.optString("app", session.optString("app", "unknown")))
            .put("summary", details.optString("summary", ""))
            .put("screen_summary", details.optString("screen_summary", ""))
            .put("details", details)
        File(sessionDir, "trace.jsonl").appendText(event.toString() + "\n", Charsets.UTF_8)
        return event
    }

    private fun findDuplicate(array: JSONArray, text: String, key: String): Int {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (key.isNotBlank() && item.optString("key").equals(key, ignoreCase = true)) return index
            if (jaccard(tokenSet(text), tokenSet(item.optString("text"))) >= 0.5) return index
        }
        return -1
    }

    private fun findDuplicateExperience(array: JSONArray, app: String, scope: String, type: String, description: String): Int {
        val newTokens = tokenSet(description)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (!item.optString("app").equals(app, ignoreCase = true)) continue
            if (!item.optString("tool_scope").equals(scope, ignoreCase = true)) continue
            if (item.optString("lesson_type") != type) continue
            if (jaccard(newTokens, tokenSet(item.optString("description"))) >= 0.5) return index
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

    private fun trimArray(container: JSONObject, key: String, max: Int) {
        val array = container.optJSONArray(key) ?: return
        if (array.length() <= max) return
        val trimmed = JSONArray()
        for (index in array.length() - max until array.length()) trimmed.put(array.get(index))
        container.put(key, trimmed)
    }

    private fun tokenSet(text: String): Set<String> {
        return Regex("[\\p{L}\\p{N}_]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun scoreTokens(query: Set<String>, text: Set<String>): Int {
        if (query.isEmpty()) return 1
        return query.count { it in text }
    }

    private fun scoreText(rawQuery: String, queryTokens: Set<String>, text: String): Int {
        val normalizedQuery = rawQuery.trim().lowercase()
        val normalizedText = text.lowercase()
        var score = scoreTokens(queryTokens, tokenSet(text))
        if (normalizedQuery.isNotBlank() && normalizedText.contains(normalizedQuery)) score += 6
        queryTokens.forEach { token ->
            if (token.length >= 2 && normalizedText.contains(token)) score += 1
        }
        return score
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun confidenceWeight(value: String): Int = when (value.lowercase()) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    private fun strongerConfidence(a: String, b: String): String = if (confidenceWeight(b) > confidenceWeight(a)) sanitizeConfidence(b) else sanitizeConfidence(a)
    private fun sanitizeConfidence(value: String): String = when (value.lowercase()) {
        "high", "medium", "low" -> value.lowercase()
        else -> "medium"
    }
    private fun sanitizeLessonType(value: String): String = when (value.lowercase()) {
        "successful_navigation", "failed_approach", "ui_knowledge", "timing", "environment", "general" -> value.lowercase()
        else -> "general"
    }
    private fun sanitizeMemoryType(value: String): String = when (value.lowercase()) {
        "user_preference", "preference", "environment", "do_not_do", "insight", "fact" -> value.lowercase()
        else -> "insight"
    }

    private fun sanitizeSecret(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{12,}"), "sk-***")
            .replace(Regex("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+"), "$1=***")
    }

    private fun relative(file: File): String {
        return runCatching { workspaceRoot.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/') }
            .getOrDefault(file.name)
    }

    private fun slug(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "general" }
            .take(80)
    }

    private fun nowIso(): String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    private fun nowMs(): Long = System.currentTimeMillis()
    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message).put("paths", paths())
}
