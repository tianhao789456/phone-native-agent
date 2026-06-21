package com.mobileagent.host

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MobileWorkspace(private val context: Context) {
    private val root: File = File(context.filesDir, "workspace")
    private val historyRoot: File = File(context.filesDir, "workspace-history")
    private val history = MobileWorkspaceHistory(
        historyRoot = historyRoot,
        resolvePath = { path -> resolve(path) },
        relativePath = { file -> relativePath(file) }
    )
    private val tasks = MobileWorkspaceTasks(
        resolvePath = { path -> resolve(path) },
        relativePath = { file -> relativePath(file) }
    )

    fun info(): JSONObject {
        ensureRoot()
        return JSONObject()
            .put("ok", true)
            .put("root", root.canonicalPath)
            .put("aliases", workspaceAliases())
            .put("history_root", historyRoot.canonicalPath)
            .put("exists", root.exists())
            .put("writable", root.canWrite())
            .put("changes", history.countChanges())
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
        val change = history.recordChange(
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
        return history.history(path, limit)
    }

    fun restore(changeId: String): JSONObject {
        return history.restore(changeId)
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
        return tasks.taskCreate(title, goal)
    }

    fun taskList(limit: Int = 50): JSONObject {
        return tasks.taskList(limit)
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
        return tasks.taskRecordRun(title, goal, sessionId, runId, finalMessage, taskLoop, toolTrace)
    }

    fun taskReports(task: String, limit: Int = 50): JSONObject {
        return tasks.taskReports(task, limit)
    }

    fun taskReportRead(task: String, report: String, maxBytes: Int = 1_000_000): JSONObject {
        return tasks.taskReportRead(task, report, maxBytes)
    }

    fun taskReportSummarize(task: String, report: String): JSONObject {
        return tasks.taskReportSummarize(task, report)
    }

    fun latestFailureAnalysis(maxBytes: Int = 20000): JSONObject {
        return tasks.latestFailureAnalysis(maxBytes)
    }

    fun taskUpdate(task: String, status: String = "", note: String = ""): JSONObject {
        return tasks.taskUpdate(task, status, note)
    }

    fun taskLogAppend(task: String, name: String = "task.log", content: String): JSONObject {
        return tasks.taskLogAppend(task, name, content)
    }

    fun taskArtifactWrite(task: String, name: String, content: String, overwrite: Boolean = false): JSONObject {
        return tasks.taskArtifactWrite(task, name, content, overwrite)
    }

    private fun ensureRoot() {
        root.mkdirs()
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

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
