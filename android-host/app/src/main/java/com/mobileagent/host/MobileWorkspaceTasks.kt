package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MobileWorkspaceTasks(
    private val resolvePath: (String) -> File,
    private val relativePath: (File) -> String
) {
    fun taskCreate(title: String, goal: String = ""): JSONObject {
        val cleanTitle = title.trim().ifBlank { "task" }.take(160)
        val taskId = "${System.currentTimeMillis()}-${MobileWorkspaceNames.slug(cleanTitle).take(48)}"
        val taskDir = resolvePath("tasks/$taskId")
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
        val tasksRoot = resolvePath("tasks")
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
        val taskDir = resolvePath(task.optString("path"))
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
        val snapshot = File(reportsDir, "task-loop-${MobileWorkspaceNames.sanitizeReportName(runId)}.json")
        latest.writeText(report.toString(2), Charsets.UTF_8)
        snapshot.writeText(report.toString(2), Charsets.UTF_8)
        val finalReport = File(artifactsDir, "final-report.md")
        val traceSummary = File(artifactsDir, "trace-summary.md")
        val taskLoopLog = File(logsDir, "task-loop.log")
        finalReport.writeText(MobileWorkspaceReportFormatter.finalReportMarkdown(report), Charsets.UTF_8)
        traceSummary.writeText(MobileWorkspaceReportFormatter.traceSummaryMarkdown(report), Charsets.UTF_8)
        taskLoopLog.writeText(MobileWorkspaceReportFormatter.loopLogText(report), Charsets.UTF_8)
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
        val relative = MobileWorkspaceNames.normalizeTaskReportPath(relativePath(taskDir), report)
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
        finalReport.writeText(MobileWorkspaceReportFormatter.finalReportMarkdown(json), Charsets.UTF_8)
        traceSummary.writeText(MobileWorkspaceReportFormatter.traceSummaryMarkdown(json), Charsets.UTF_8)
        taskLoopLog.writeText(MobileWorkspaceReportFormatter.loopLogText(json), Charsets.UTF_8)
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
        val tasksRoot = resolvePath("tasks")
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
            meta.put("status", MobileWorkspaceNames.sanitizeTaskStatus(cleanStatus))
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
        val file = File(logsDir, MobileWorkspaceNames.sanitizeRelativeFileName(name.ifBlank { "task.log" })).canonicalFile
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
        val relative = MobileWorkspaceNames.sanitizeRelativeFileName(name.ifBlank { "artifact.txt" })
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

    private fun resolveTaskDir(raw: String): File {
        val value = raw.trim()
        val taskPath = if (value.startsWith("tasks/")) value else "tasks/$value"
        val taskDir = resolvePath(taskPath)
        if (!File(taskDir, "task.json").isFile) {
            throw IllegalArgumentException("task does not exist: $raw")
        }
        return taskDir
    }

    private fun writeFailureAnalysisIfNeeded(artifactsDir: File, report: JSONObject): File? {
        val loop = report.optJSONObject("task_loop") ?: JSONObject()
        val status = loop.optString("status", "")
        val failedSteps = loop.optInt("failed_steps", 0)
        val shouldWrite = failedSteps > 0 || status == "max_rounds_reached" || status == "blocked_by_loop_guard"
        if (!shouldWrite) return null
        val file = File(artifactsDir, "failure-analysis.md")
        file.writeText(MobileWorkspaceReportFormatter.failureAnalysisMarkdown(report), Charsets.UTF_8)
        return file
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
