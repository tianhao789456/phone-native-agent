package com.mobileagent.host

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MobileWorkspace(private val context: Context) {
    private val root: File = File(context.filesDir, "workspace")
    private val historyRoot: File = File(context.filesDir, "workspace-history")

    fun info(): JSONObject {
        ensureRoot()
        return JSONObject()
            .put("ok", true)
            .put("root", root.canonicalPath)
            .put("aliases", workspaceAliases())
            .put("history_root", historyRoot.canonicalPath)
            .put("exists", root.exists())
            .put("writable", root.canWrite())
            .put("changes", historyRoot.listFiles()?.count { it.isDirectory } ?: 0)
    }

    fun resolvePath(path: String): File {
        return resolve(path)
    }

    fun list(path: String = ".", maxEntries: Int = 100): JSONObject {
        val target = resolve(path)
        if (!target.exists()) {
            return error("Path does not exist: $path")
        }
        if (!target.isDirectory) {
            return error("Path is not a directory: $path")
        }
        val entries = JSONArray()
        target.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.take(maxEntries.coerceIn(1, 500))
            ?.forEach { item ->
                entries.put(
                    JSONObject()
                        .put("name", item.name)
                        .put("path", relativePath(item))
                        .put("type", if (item.isDirectory) "directory" else "file")
                        .put("bytes", if (item.isFile) item.length() else 0L)
                        .put("modified_at", item.lastModified())
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath(target))
            .put("entries", entries)
            .put("count", entries.length())
    }

    fun read(path: String, maxBytes: Int = 20000): JSONObject {
        val target = resolve(path)
        if (!target.exists()) return error("Path does not exist: $path")
        if (!target.isFile) return error("Path is not a file: $path")
        val limit = maxBytes.coerceIn(1, 1_000_000)
        val bytes = target.readBytes()
        val slice = bytes.copyOfRange(0, minOf(bytes.size, limit))
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath(target))
            .put("bytes", bytes.size)
            .put("content", slice.toString(Charsets.UTF_8))
            .put("truncated", bytes.size > limit)
    }

    fun write(path: String, content: String, overwrite: Boolean = false): JSONObject {
        val target = resolve(path)
        if (target.exists() && !overwrite) {
            return error("File exists and overwrite is false: $path")
        }
        val beforeExists = target.exists()
        val beforeBytes = if (beforeExists && target.isFile) target.readBytes() else ByteArray(0)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        val afterBytes = target.readBytes()
        val change = recordChange(
            action = if (beforeExists) "update" else "create",
            path = relativePath(target),
            beforeExists = beforeExists,
            beforeBytes = beforeBytes,
            afterExists = true,
            afterBytes = afterBytes
        )
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath(target))
            .put("bytes", content.toByteArray(Charsets.UTF_8).size)
            .put("overwrite", overwrite)
            .put("change", change)
    }

    fun history(path: String = "", limit: Int = 50): JSONObject {
        ensureHistoryRoot()
        val normalizedPath = path.trim().replace('\\', '/')
        val changes = JSONArray()
        val items = historyRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val meta = File(dir, "meta.json")
                if (!meta.isFile) null else runCatching { JSONObject(meta.readText(Charsets.UTF_8)) }.getOrNull()
            }
            ?.filter { normalizedPath.isBlank() || it.optString("path") == normalizedPath }
            ?.sortedByDescending { it.optLong("created_at_ms") }
            ?.take(limit.coerceIn(1, 200))
            ?: emptyList()
        items.forEach { changes.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("path", if (normalizedPath.isBlank()) JSONObject.NULL else normalizedPath)
            .put("count", changes.length())
            .put("changes", changes)
    }

    fun restore(changeId: String): JSONObject {
        if (changeId.isBlank()) return error("change_id is required")
        val changeDir = File(historyRoot, changeId).canonicalFile
        val historyPath = historyRoot.canonicalFile.toPath()
        if (changeDir.toPath() != historyPath && !changeDir.toPath().startsWith(historyPath)) {
            throw SecurityException("change_id escapes history root")
        }
        val metaFile = File(changeDir, "meta.json")
        if (!metaFile.isFile) return error("Change does not exist: $changeId")
        val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
        val path = meta.optString("path")
        val target = resolve(path)
        val currentExists = target.exists()
        val currentBytes = if (currentExists && target.isFile) target.readBytes() else ByteArray(0)
        val beforeExists = meta.optBoolean("before_exists", false)
        val beforeFile = File(changeDir, "before.bin")
        if (beforeExists) {
            target.parentFile?.mkdirs()
            target.writeBytes(beforeFile.readBytes())
        } else if (target.exists()) {
            target.delete()
        }
        val restoredExists = target.exists()
        val restoredBytes = if (restoredExists && target.isFile) target.readBytes() else ByteArray(0)
        val restoreChange = recordChange(
            action = "restore",
            path = path,
            beforeExists = currentExists,
            beforeBytes = currentBytes,
            afterExists = restoredExists,
            afterBytes = restoredBytes,
            restoredFrom = changeId
        )
        return JSONObject()
            .put("ok", true)
            .put("restored_change_id", changeId)
            .put("path", path)
            .put("restored_exists", restoredExists)
            .put("change", restoreChange)
    }

    fun search(query: String, path: String = ".", maxMatches: Int = 50, maxBytesPerFile: Int = 200000): JSONObject {
        if (query.isBlank()) return error("query is required")
        val start = resolve(path)
        if (!start.exists()) return error("Path does not exist: $path")
        val matches = JSONArray()
        val files = if (start.isFile) listOf(start) else start.walkTopDown().filter { it.isFile }.toList()
        val matchLimit = maxMatches.coerceIn(1, 500)
        val byteLimit = maxBytesPerFile.coerceIn(1000, 1000000)
        for (file in files) {
            if (matches.length() >= matchLimit) break
            if (file.length() > byteLimit) continue
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val lines = text.lines()
            for ((index, line) in lines.withIndex()) {
                if (line.contains(query, ignoreCase = true)) {
                    matches.put(
                        JSONObject()
                            .put("path", relativePath(file))
                            .put("line", index + 1)
                            .put("text", line.take(500))
                    )
                    if (matches.length() >= matchLimit) break
                }
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("path", relativePath(start))
            .put("matches", matches)
            .put("count", matches.length())
    }

    fun taskCreate(title: String, goal: String = ""): JSONObject {
        val cleanTitle = title.trim().ifBlank { "task" }.take(160)
        val taskId = "${System.currentTimeMillis()}-${slug(cleanTitle).take(48)}"
        val taskDir = resolve("tasks/$taskId")
        val artifactsDir = File(taskDir, "artifacts")
        val logsDir = File(taskDir, "logs")
        val reportsDir = File(taskDir, "reports")
        artifactsDir.mkdirs()
        logsDir.mkdirs()
        reportsDir.mkdirs()
        val meta = JSONObject()
            .put("id", taskId)
            .put("title", cleanTitle)
            .put("goal", goal.take(2000))
            .put("status", "created")
            .put("path", relativePath(taskDir))
            .put("artifacts_path", relativePath(artifactsDir))
            .put("logs_path", relativePath(logsDir))
            .put("reports_path", relativePath(reportsDir))
            .put("created_at_ms", System.currentTimeMillis())
            .put("updated_at_ms", System.currentTimeMillis())
        File(taskDir, "task.json").writeText(meta.toString(2), Charsets.UTF_8)
        File(taskDir, "plan.md").writeText("# $cleanTitle\n\nGoal:\n$goal\n\nSteps:\n", Charsets.UTF_8)
        File(taskDir, "README.md").writeText("# $cleanTitle\n\nMobile Agent task workspace.\n", Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("task", meta)
    }

    fun taskList(limit: Int = 50): JSONObject {
        val tasksRoot = resolve("tasks")
        val tasks = JSONArray()
        tasksRoot
            .listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val meta = File(dir, "task.json")
                if (!meta.isFile) null else runCatching { JSONObject(meta.readText(Charsets.UTF_8)) }.getOrNull()
            }
            ?.sortedByDescending { it.optLong("updated_at_ms", it.optLong("created_at_ms")) }
            ?.take(limit.coerceIn(1, 200))
            ?.forEach { tasks.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("root", relativePath(tasksRoot))
            .put("count", tasks.length())
            .put("tasks", tasks)
    }

    fun taskRecordRun(
        title: String,
        goal: String,
        sessionId: String,
        runId: String,
        finalMessage: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray
    ): JSONObject {
        val task = taskCreate(title, goal).optJSONObject("task") ?: return error("failed to create task")
        val taskDir = resolve(task.optString("path"))
        val reportsDir = File(taskDir, "reports")
        val artifactsDir = File(taskDir, "artifacts")
        val logsDir = File(taskDir, "logs")
        reportsDir.mkdirs()
        artifactsDir.mkdirs()
        logsDir.mkdirs()
        val report = JSONObject()
            .put("ok", taskLoop.optString("status") !in setOf("completed_with_failures", "max_rounds_reached", "blocked_by_loop_guard"))
            .put("type", "task_loop_run")
            .put("session_id", sessionId)
            .put("run_id", runId)
            .put("task", task)
            .put("final_message", finalMessage)
            .put("task_loop", taskLoop)
            .put("tool_trace", toolTrace)
            .put("created_at_ms", System.currentTimeMillis())
        val latest = File(reportsDir, "task-loop-latest.json")
        val snapshot = File(reportsDir, "task-loop-${sanitizeReportName(runId)}.json")
        latest.writeText(report.toString(2), Charsets.UTF_8)
        snapshot.writeText(report.toString(2), Charsets.UTF_8)
        val finalReport = File(artifactsDir, "final-report.md")
        val traceSummary = File(artifactsDir, "trace-summary.md")
        val taskLoopLog = File(logsDir, "task-loop.log")
        finalReport.writeText(taskFinalReportMarkdown(report), Charsets.UTF_8)
        traceSummary.writeText(taskTraceSummaryMarkdown(report), Charsets.UTF_8)
        taskLoopLog.writeText(taskLoopLogText(report), Charsets.UTF_8)
        val failureAnalysis = writeFailureAnalysisIfNeeded(artifactsDir, report)
        val artifacts = JSONObject()
            .put("final_report", "${relativePath(taskDir)}/artifacts/${finalReport.name}")
            .put("trace_summary", "${relativePath(taskDir)}/artifacts/${traceSummary.name}")
        if (failureAnalysis != null) {
            artifacts.put("failure_analysis", "${relativePath(taskDir)}/artifacts/${failureAnalysis.name}")
        }
        return JSONObject()
            .put("ok", true)
            .put("task", task)
            .put(
                "reports",
                JSONObject()
                    .put("latest", "${relativePath(taskDir)}/reports/${latest.name}")
                    .put("snapshot", "${relativePath(taskDir)}/reports/${snapshot.name}")
            )
            .put("artifacts", artifacts)
            .put(
                "logs",
                JSONObject()
                    .put("task_loop", "${relativePath(taskDir)}/logs/${taskLoopLog.name}")
            )
    }

    fun taskReports(task: String, limit: Int = 50): JSONObject {
        val taskDir = resolveTaskDir(task)
        val reportsDir = File(taskDir, "reports")
        val items = JSONArray()
        reportsDir
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 200))
            ?.forEach { file ->
                items.put(
                    JSONObject()
                        .put("name", file.name)
                        .put("path", "${relativePath(taskDir)}/reports/${file.name}")
                        .put("size_bytes", file.length())
                        .put("modified_at_ms", file.lastModified())
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("task", relativePath(taskDir))
            .put("count", items.length())
            .put("reports", items)
    }

    fun taskReportRead(task: String, report: String, maxBytes: Int = 1_000_000): JSONObject {
        val taskDir = resolveTaskDir(task)
        val reportsDir = File(taskDir, "reports").canonicalFile
        val relative = normalizeTaskReportPath(relativePath(taskDir), report)
        val file = File(reportsDir, relative).canonicalFile
        if (!file.toPath().startsWith(reportsDir.toPath())) return error("Report path escapes reports root: $report")
        if (!file.isFile) return error("Report does not exist: $report")
        val limit = maxBytes.coerceIn(1_000, 1_000_000)
        if (file.length() > limit) {
            return error("Report is too large: ${file.length()} bytes > $limit bytes")
                .put("size_bytes", file.length())
                .put("max_bytes", limit)
        }
        val text = file.readText(Charsets.UTF_8)
        val output = JSONObject()
            .put("ok", true)
            .put("task", relativePath(taskDir))
            .put("path", "${relativePath(taskDir)}/reports/$relative")
            .put("size_bytes", file.length())
            .put("content", text)
        runCatching { JSONObject(text) }.onSuccess { output.put("json", it) }
        return output
    }

    fun taskReportSummarize(task: String, report: String): JSONObject {
        val taskDir = resolveTaskDir(task)
        val read = taskReportRead(relativePath(taskDir), report, 1_000_000)
        if (!read.optBoolean("ok", false)) return read
        val json = read.optJSONObject("json") ?: return error("Report is not valid JSON: $report")
        val artifactsDir = File(taskDir, "artifacts")
        val logsDir = File(taskDir, "logs")
        artifactsDir.mkdirs()
        logsDir.mkdirs()
        val finalReport = File(artifactsDir, "final-report.md")
        val traceSummary = File(artifactsDir, "trace-summary.md")
        val taskLoopLog = File(logsDir, "task-loop.log")
        finalReport.writeText(taskFinalReportMarkdown(json), Charsets.UTF_8)
        traceSummary.writeText(taskTraceSummaryMarkdown(json), Charsets.UTF_8)
        taskLoopLog.writeText(taskLoopLogText(json), Charsets.UTF_8)
        val failureAnalysis = writeFailureAnalysisIfNeeded(artifactsDir, json)
        val artifacts = JSONObject()
            .put("final_report", "${relativePath(taskDir)}/artifacts/${finalReport.name}")
            .put("trace_summary", "${relativePath(taskDir)}/artifacts/${traceSummary.name}")
        if (failureAnalysis != null) {
            artifacts.put("failure_analysis", "${relativePath(taskDir)}/artifacts/${failureAnalysis.name}")
        }
        return JSONObject()
            .put("ok", true)
            .put("task", relativePath(taskDir))
            .put("source_report", read.optString("path"))
            .put("artifacts", artifacts)
            .put(
                "logs",
                JSONObject()
                    .put("task_loop", "${relativePath(taskDir)}/logs/${taskLoopLog.name}")
            )
    }

    fun latestFailureAnalysis(maxBytes: Int = 20000): JSONObject {
        val tasksRoot = resolve("tasks")
        val candidates = tasksRoot
            .listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { taskDir ->
                val analysis = File(taskDir, "artifacts/failure-analysis.md")
                if (!analysis.isFile) null else taskDir to analysis
            }
            ?.sortedByDescending { it.second.lastModified() }
            ?: emptyList()
        if (candidates.isEmpty()) return error("No failure analysis report found.")

        val (taskDir, analysis) = candidates.first()
        val metaFile = File(taskDir, "task.json")
        val meta = if (metaFile.isFile) {
            runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        val limit = maxBytes.coerceIn(1_000, 1_000_000)
        val bytes = analysis.readBytes()
        val slice = bytes.copyOfRange(0, minOf(bytes.size, limit))
        return JSONObject()
            .put("ok", true)
            .put("task", meta.put("path", relativePath(taskDir)))
            .put("path", "${relativePath(taskDir)}/artifacts/${analysis.name}")
            .put("size_bytes", bytes.size)
            .put("modified_at_ms", analysis.lastModified())
            .put("content", slice.toString(Charsets.UTF_8))
            .put("truncated", bytes.size > limit)
    }

    fun taskUpdate(task: String, status: String = "", note: String = ""): JSONObject {
        val taskDir = resolveTaskDir(task)
        val metaFile = File(taskDir, "task.json")
        val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
        val cleanStatus = status.trim().lowercase()
        if (cleanStatus.isNotBlank()) {
            meta.put("status", sanitizeTaskStatus(cleanStatus))
        }
        if (note.isNotBlank()) {
            meta.put("note", note.take(4000))
        }
        meta.put("updated_at_ms", System.currentTimeMillis())
        metaFile.writeText(meta.toString(2), Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("task", meta)
    }

    fun taskLogAppend(task: String, name: String = "task.log", content: String): JSONObject {
        val taskDir = resolveTaskDir(task)
        val logsDir = File(taskDir, "logs").canonicalFile
        logsDir.mkdirs()
        val file = File(logsDir, sanitizeRelativeFileName(name.ifBlank { "task.log" })).canonicalFile
        if (!file.toPath().startsWith(logsDir.toPath())) return error("Log path escapes logs root: $name")
        file.parentFile?.mkdirs()
        val entry = buildString {
            append("[")
            append(System.currentTimeMillis())
            append("] ")
            append(content)
            if (!content.endsWith("\n")) append("\n")
        }
        file.appendText(entry, Charsets.UTF_8)
        taskUpdate(relativePath(taskDir), "in_progress", "")
        return JSONObject()
            .put("ok", true)
            .put("task", relativePath(taskDir))
            .put("path", "${relativePath(taskDir)}/logs/${logsDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')}")
            .put("bytes", file.length())
    }

    fun taskArtifactWrite(task: String, name: String, content: String, overwrite: Boolean = false): JSONObject {
        val taskDir = resolveTaskDir(task)
        val artifactsDir = File(taskDir, "artifacts").canonicalFile
        artifactsDir.mkdirs()
        val relative = sanitizeRelativeFileName(name.ifBlank { "artifact.txt" })
        val file = File(artifactsDir, relative).canonicalFile
        if (!file.toPath().startsWith(artifactsDir.toPath())) return error("Artifact path escapes artifacts root: $name")
        if (file.exists() && !overwrite) return error("Artifact exists and overwrite is false: $name")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        taskUpdate(relativePath(taskDir), "in_progress", "")
        return JSONObject()
            .put("ok", true)
            .put("task", relativePath(taskDir))
            .put("path", "${relativePath(taskDir)}/artifacts/${artifactsDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')}")
            .put("bytes", content.toByteArray(Charsets.UTF_8).size)
            .put("overwrite", overwrite)
    }

    private fun ensureRoot() {
        root.mkdirs()
    }

    private fun ensureHistoryRoot() {
        historyRoot.mkdirs()
    }

    private fun resolve(path: String): File {
        ensureRoot()
        val raw = path.ifBlank { "." }.trim()
        val alias = resolveAliasedPath(raw)
        if (alias != null) return alias
        if (File(raw).isAbsolute) {
            throw IllegalArgumentException("absolute paths require an explicit alias such as app_root:/, files_root:/, workspace:/, or shared_storage:/")
        }
        val target = File(root, raw).canonicalFile
        val rootPath = root.canonicalFile.toPath()
        if (target.toPath() != rootPath && !target.toPath().startsWith(rootPath)) {
            throw SecurityException("path escapes APP workspace: $path")
        }
        return target
    }

    private fun relativePath(file: File): String {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = file.canonicalFile.toPath()
        if (targetPath == rootPath || targetPath.startsWith(rootPath)) {
            return if (targetPath == rootPath) "." else rootPath.relativize(targetPath).toString().replace('\\', '/')
        }
        return aliasedDisplayPath(file.canonicalFile)
    }

    private fun resolveAliasedPath(raw: String): File? {
        val normalized = raw.replace('\\', '/')
        val alias = Regex("^([A-Za-z_][A-Za-z0-9_]*):/?(.*)$").matchEntire(normalized) ?: return null
        val base = when (alias.groupValues[1]) {
            "workspace" -> root
            "app_root", "files_root" -> context.filesDir
            "shared_storage" -> Environment.getExternalStorageDirectory()
            else -> throw IllegalArgumentException("unknown workspace path alias: ${alias.groupValues[1]}")
        }.canonicalFile
        val child = alias.groupValues[2].trim('/')
        require(!child.split('/').any { it == ".." }) { "aliased path cannot contain .." }
        val target = if (child.isBlank()) base else File(base, child).canonicalFile
        val basePath = base.toPath()
        if (target.toPath() != basePath && !target.toPath().startsWith(basePath)) {
            throw SecurityException("path escapes alias root: $raw")
        }
        return target
    }

    private fun aliasedDisplayPath(file: File): String {
        val aliases = listOf(
            "workspace" to root.canonicalFile,
            "app_root" to context.filesDir.canonicalFile,
            "shared_storage" to Environment.getExternalStorageDirectory().canonicalFile
        )
        val target = file.canonicalFile.toPath()
        aliases.sortedByDescending { it.second.canonicalPath.length }.forEach { (name, base) ->
            val basePath = base.toPath()
            if (target == basePath || target.startsWith(basePath)) {
                val suffix = if (target == basePath) "" else "/" + basePath.relativize(target).toString().replace('\\', '/')
                return "$name:$suffix"
            }
        }
        return file.canonicalPath
    }

    private fun workspaceAliases(): JSONObject {
        return JSONObject()
            .put("workspace:/", root.canonicalPath)
            .put("app_root:/", context.filesDir.canonicalPath)
            .put("files_root:/", context.filesDir.canonicalPath)
            .put("shared_storage:/", Environment.getExternalStorageDirectory().canonicalPath)
    }

    private fun recordChange(
        action: String,
        path: String,
        beforeExists: Boolean,
        beforeBytes: ByteArray,
        afterExists: Boolean,
        afterBytes: ByteArray,
        restoredFrom: String = ""
    ): JSONObject {
        ensureHistoryRoot()
        val changeId = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val dir = File(historyRoot, changeId)
        dir.mkdirs()
        File(dir, "before.bin").writeBytes(beforeBytes)
        File(dir, "after.bin").writeBytes(afterBytes)
        val meta = JSONObject()
            .put("id", changeId)
            .put("action", action)
            .put("path", path)
            .put("before_exists", beforeExists)
            .put("before_bytes", beforeBytes.size)
            .put("after_exists", afterExists)
            .put("after_bytes", afterBytes.size)
            .put("created_at_ms", System.currentTimeMillis())
            .put("restored_from", restoredFrom)
        File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
        return meta
    }

    private fun resolveTaskDir(raw: String): File {
        val value = raw.trim()
        val taskPath = if (value.startsWith("tasks/")) value else "tasks/$value"
        val taskDir = resolve(taskPath)
        if (!File(taskDir, "task.json").isFile) {
            throw IllegalArgumentException("task does not exist: $raw")
        }
        return taskDir
    }

    private fun slug(raw: String): String {
        return raw
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_.-]+"), "-")
            .trim('-')
            .ifBlank { "task" }
    }

    private fun sanitizeReportName(raw: String): String {
        return raw
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-')
            .ifBlank { "report" }
            .take(120)
    }

    private fun sanitizeRelativeFileName(raw: String): String {
        val path = raw.trim().replace('\\', '/').trim('/')
        require(path.isNotBlank()) { "file name is required" }
        require(!path.contains("..")) { "file name cannot contain .." }
        require(!path.startsWith("/")) { "file name must be relative" }
        return path
            .split("/")
            .joinToString("/") { segment ->
                segment
                    .replace(Regex("[^A-Za-z0-9._ -]+"), "-")
                    .trim()
                    .ifBlank { "item" }
            }
            .take(240)
    }

    private fun sanitizeTaskStatus(status: String): String {
        return when (status) {
            "created", "pending", "in_progress", "blocked", "completed", "failed", "cancelled" -> status
            else -> "in_progress"
        }
    }

    private fun taskFinalReportMarkdown(report: JSONObject): String {
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

    private fun taskTraceSummaryMarkdown(report: JSONObject): String {
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

    private fun taskLoopLogText(report: JSONObject): String {
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

    private fun writeFailureAnalysisIfNeeded(artifactsDir: File, report: JSONObject): File? {
        val loop = report.optJSONObject("task_loop") ?: JSONObject()
        val status = loop.optString("status", "")
        val failedSteps = loop.optInt("failed_steps", 0)
        val shouldWrite = failedSteps > 0 || status == "max_rounds_reached" || status == "blocked_by_loop_guard"
        if (!shouldWrite) return null
        val file = File(artifactsDir, "failure-analysis.md")
        file.writeText(taskFailureAnalysisMarkdown(report), Charsets.UTF_8)
        return file
    }

    private fun taskFailureAnalysisMarkdown(report: JSONObject): String {
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

    private fun normalizeTaskReportPath(taskPath: String, raw: String): String {
        var path = raw.trim().replace('\\', '/')
        if (path.startsWith("$taskPath/")) path = path.removePrefix("$taskPath/")
        if (path.startsWith("reports/")) path = path.removePrefix("reports/")
        require(path.isNotBlank()) { "report path is required" }
        require(!path.contains("..")) { "report path cannot contain .." }
        require(!path.startsWith("/")) { "report path must be relative" }
        return path
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
