package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MobileMemoryStore(context: Context) {
    private val workspaceRoot = File(context.filesDir, "workspace")
    private val root = File(workspaceRoot, "memory")
    private val profileFile = File(root, "user_profile.json")
    private val experienceFile = File(root, "experience_log.json")
    private val reflectionsFile = File(root, "task_reflections.jsonl")
    private val proceduresDir = File(root, "procedures")
    private val demosDir = File(root, "demos")
    private val activeLearningFile = File(demosDir, "active_learning.json")
    private val profileStore = MobileMemoryProfileStore(
        profileFile = profileFile,
        paths = { paths() },
        nowIso = { nowIso() },
        nowMs = { nowMs() },
        readJson = { file, fallback -> readJson(file, fallback) },
        writeJson = { file, json -> writeJson(file, json) },
        emptyProfile = { emptyProfile() }
    )
    private val experienceStore = MobileMemoryExperienceStore(
        experienceFile = experienceFile,
        paths = { paths() },
        nowIso = { nowIso() },
        nowMs = { nowMs() },
        readJson = { file, fallback -> readJson(file, fallback) },
        writeJson = { file, json -> writeJson(file, json) },
        emptyExperience = { emptyExperience() },
        generateProcedure = { arguments -> procedureGenerate(arguments) }
    )
    private val procedures = MobileMemoryProcedureStore(
        proceduresDir = proceduresDir,
        relative = { file -> relative(file) },
        paths = { paths() }
    )
    private val learning = MobileMemoryLearningStore(
        demosDir = demosDir,
        activeLearningFile = activeLearningFile,
        relative = { file -> relative(file) },
        paths = { paths() },
        nowIso = { nowIso() },
        nowMs = { nowMs() },
        readJson = { file, fallback -> readJson(file, fallback) },
        writeJson = { file, json -> writeJson(file, json) }
    )

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
            val readable = MobileMemoryText.readableExperience(lesson)
            if (query.isNotBlank() && MobileMemoryText.scoreText(query, MobileMemoryText.tokenSet(query), readable) <= 0) continue
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
        ensureFiles()
        return profileStore.query(question, limit)
    }

    fun searchMemory(query: String, limit: Int = 8): JSONObject {
        ensureFiles()
        return profileStore.searchMemory(query, limit)
    }

    fun writeMemory(arguments: JSONObject): JSONObject {
        ensureFiles()
        return profileStore.writeMemory(arguments)
    }

    fun recordTask(task: String, status: String, finalAnswer: String, toolsUsed: JSONArray, appsUsed: JSONArray, runId: String): JSONObject {
        ensureFiles()
        return profileStore.recordTask(task, status, finalAnswer, toolsUsed, appsUsed, runId)
    }

    fun searchExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        return experienceStore.search(arguments)
    }

    fun recordExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        return experienceStore.record(arguments)
    }

    fun updateExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        return experienceStore.update(arguments)
    }

    fun deleteExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        return experienceStore.delete(arguments)
    }

    fun compactExperience(arguments: JSONObject): JSONObject {
        ensureFiles()
        return experienceStore.compact(arguments)
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
        val memoryItems = memory.optJSONArray("matches") ?: JSONArray()
        val procedureItems = procedures.optJSONArray("matches") ?: JSONArray()
        val experienceItems = exp.optJSONArray("matches") ?: JSONArray()
        val content = buildString {
            append("## Relevant Memory\n")
            append(MobileMemoryText.formatMemory(memoryItems))
            append("\n\n## Relevant Procedure\n")
            append(MobileMemoryText.formatProcedures(procedureItems))
            append("\n\n## Relevant Experience\n")
            append(MobileMemoryText.formatExperience(experienceItems))
        }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(6000)
        return JSONObject()
            .put("ok", true)
            .put("injected", content.isNotBlank())
            .put("content", content)
            .put("memory_count", memoryItems.length())
            .put("procedure_count", procedureItems.length())
            .put("experience_count", experienceItems.length())
            .put("memory", memoryItems)
            .put("procedures", procedureItems)
            .put("experience", experienceItems)
    }

    fun procedureList(arguments: JSONObject): JSONObject {
        ensureFiles()
        return procedures.list(arguments)
    }

    fun procedureRead(arguments: JSONObject): JSONObject {
        ensureFiles()
        return procedures.read(arguments)
    }

    fun procedureSearch(arguments: JSONObject): JSONObject {
        ensureFiles()
        return procedures.search(arguments)
    }

    fun procedureGenerate(arguments: JSONObject): JSONObject {
        ensureFiles()
        val log = readExperience()
        val lessons = log.optJSONArray("lessons") ?: JSONArray()
        return procedures.generate(arguments, lessons, nowIso())
    }

    fun learningStart(arguments: JSONObject): JSONObject {
        ensureFiles()
        return learning.start(arguments)
    }

    fun learningRecord(arguments: JSONObject): JSONObject {
        ensureFiles()
        return learning.record(arguments)
    }

    fun learningStop(arguments: JSONObject): JSONObject {
        ensureFiles()
        val session = learning.activeSession() ?: return error("no active learning session")
        val sessionDir = learning.sessionDir(session)
        learning.appendEvent(session, "stop", JSONObject().put("summary", "learning stopped"))
        val traceFile = learning.traceFile(session)
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
        learning.clearActive()
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
        return learning.status()
    }

    fun heuristicExperienceFromTool(tool: String, state: String, summary: String, sourceTask: String): JSONObject? {
        return experienceStore.heuristicExperienceFromTool(tool, state, summary, sourceTask)
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

    private fun readProfile(): JSONObject = profileStore.readProfile()
    private fun readExperience(): JSONObject = experienceStore.readExperience()

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

    private fun relative(file: File): String {
        return runCatching { workspaceRoot.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/') }
            .getOrDefault(file.name)
    }

    private fun nowIso(): String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    private fun nowMs(): Long = System.currentTimeMillis()
    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message).put("paths", paths())
}
