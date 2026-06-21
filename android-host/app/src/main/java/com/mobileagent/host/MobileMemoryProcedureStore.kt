package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MobileMemoryProcedureStore(
    private val proceduresDir: File,
    private val relative: (File) -> String,
    private val paths: () -> JSONObject
) {
    fun list(arguments: JSONObject): JSONObject {
        val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
        val files = procedureFiles()
            .sortedByDescending { it.lastModified() }
        val procedures = JSONArray()
        files.take(limit).forEach { file ->
            procedures.put(meta(file))
        }
        return JSONObject().put("ok", true).put("count", procedures.length()).put("procedures", procedures).put("paths", paths())
    }

    fun read(arguments: JSONObject): JSONObject {
        val file = resolveFile(arguments)
        if (!file.isFile) return error("procedure not found: ${relative(file)}")
        return JSONObject()
            .put("ok", true)
            .put("procedure", meta(file))
            .put("content", file.readText(Charsets.UTF_8).take(arguments.optInt("max_bytes", 40000).coerceIn(1000, 200000)))
            .put("paths", paths())
    }

    fun search(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query", "").trim()
        val app = arguments.optString("app", arguments.optString("package", "")).trim()
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val limit = arguments.optInt("limit", 5).coerceIn(1, 50)
        val q = MobileMemoryText.tokenSet(listOf(query, app, scope).joinToString(" "))
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        for (file in procedureFiles()) {
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            val meta = meta(file)
            var score = MobileMemoryText.scoreText(listOf(query, app, scope).joinToString(" "), q, content + " " + meta.toString())
            if (app.isNotBlank() && app.equals(meta.optString("app"), ignoreCase = true)) score += 6
            if (scope.isNotBlank() && scope.equals(meta.optString("tool_scope"), ignoreCase = true)) score += 6
            if (query.isBlank() && app.isBlank() && scope.isBlank()) score += 1
            if (score <= 0) continue
            candidates.add(score to meta.put("score", score).put("preview", MobileMemoryText.procedurePreview(content)))
        }
        val matches = JSONArray()
        candidates.sortedByDescending { it.first }.take(limit).forEach { matches.put(it.second) }
        return JSONObject().put("ok", true).put("query", query).put("count", matches.length()).put("matches", matches).put("paths", paths())
    }

    fun generate(arguments: JSONObject, lessons: JSONArray, updatedAt: String): JSONObject {
        val app = arguments.optString("app", arguments.optString("package", "general")).trim().ifBlank { "general" }
        val scope = arguments.optString("tool_scope", arguments.optString("scope", "")).trim()
        val targetFile = resolveFile(JSONObject().put("app", app).put("tool_scope", scope))
        val selected = mutableListOf<JSONObject>()
        for (index in 0 until lessons.length()) {
            val item = lessons.optJSONObject(index) ?: continue
            val appMatch = app.equals(item.optString("app"), ignoreCase = true) || app == "general"
            val scopeMatch = scope.isBlank() || scope.equals(item.optString("tool_scope"), ignoreCase = true)
            if (appMatch && scopeMatch) selected.add(item)
        }
        if (selected.isEmpty()) return error("no experience lessons found for app=$app tool_scope=$scope")
        val markdown = MobileMemoryText.buildProcedureMarkdown(app, scope, selected, updatedAt)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(markdown, Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("generated", true)
            .put("procedure", meta(targetFile))
            .put("source_lessons", selected.size)
            .put("paths", paths())
    }

    private fun procedureFiles(): List<File> {
        return proceduresDir
            .listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
    }

    private fun resolveFile(arguments: JSONObject): File {
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
            "${MobileMemoryText.slug(app)}${if (scope.isNotBlank()) "_${MobileMemoryText.slug(scope)}" else ""}.md"
        }
        return File(proceduresDir, name)
    }

    private fun meta(file: File): JSONObject {
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

    private fun error(message: String): JSONObject {
        return JSONObject().put("ok", false).put("error", message).put("paths", paths())
    }
}
