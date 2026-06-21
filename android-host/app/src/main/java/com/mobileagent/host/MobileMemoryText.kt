package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object MobileMemoryText {
    fun readableMemoryItem(item: JSONObject): String {
        return item.optString("text").ifBlank {
            listOf(item.optString("key"), item.optString("value"), item.optString("task"), item.optString("final_answer"))
                .filter { it.isNotBlank() }
                .joinToString(": ")
        }
    }

    fun readableExperience(item: JSONObject): String {
        return listOf(
            item.optString("app"),
            item.optString("tool_scope"),
            item.optString("task_type"),
            item.optString("lesson_type"),
            item.optString("description")
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun formatMemory(items: JSONArray): String {
        if (items.length() == 0) return "- none"
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            lines.add("${index + 1}. ${item.optString("text").ifBlank { readableMemoryItem(item) }.take(360)}")
        }
        return lines.joinToString("\n")
    }

    fun formatExperience(items: JSONArray): String {
        if (items.length() == 0) return "- none"
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
            lines.add("${index + 1}. [$prefix${if (scope.isNotBlank()) " $scope" else ""}] ${item.optString("description").take(420)}")
        }
        return lines.joinToString("\n")
    }

    fun formatProcedures(items: JSONArray): String {
        if (items.length() == 0) return "- none"
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            lines.add("${index + 1}. [procedure ${item.optString("scope_key")}] ${item.optString("preview").take(420)}")
        }
        return lines.joinToString("\n")
    }

    fun buildProcedureMarkdown(app: String, scope: String, lessons: List<JSONObject>, updatedAt: String): String {
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
            |$updatedAt
        """.trimMargin() + "\n"
    }

    fun procedurePreview(content: String): String {
        return content
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
            .take(8)
            .joinToString(" ")
            .take(900)
    }

    fun tokenSet(text: String): Set<String> {
        return Regex("[\\p{L}\\p{N}_]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()
    }

    fun scoreTokens(query: Set<String>, text: Set<String>): Int {
        if (query.isEmpty()) return 1
        return query.count { it in text }
    }

    fun scoreText(rawQuery: String, queryTokens: Set<String>, text: String): Int {
        val normalizedQuery = rawQuery.trim().lowercase()
        val normalizedText = text.lowercase()
        var score = scoreTokens(queryTokens, tokenSet(text))
        if (normalizedQuery.isNotBlank() && normalizedText.contains(normalizedQuery)) score += 6
        queryTokens.forEach { token ->
            if (token.length >= 2 && normalizedText.contains(token)) score += 1
        }
        return score
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    fun confidenceWeight(value: String): Int = when (value.lowercase()) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    fun strongerConfidence(a: String, b: String): String = if (confidenceWeight(b) > confidenceWeight(a)) sanitizeConfidence(b) else sanitizeConfidence(a)

    fun sanitizeConfidence(value: String): String = when (value.lowercase()) {
        "high", "medium", "low" -> value.lowercase()
        else -> "medium"
    }

    fun sanitizeLessonType(value: String): String = when (value.lowercase()) {
        "successful_navigation", "failed_approach", "ui_knowledge", "timing", "environment", "general" -> value.lowercase()
        else -> "general"
    }

    fun sanitizeMemoryType(value: String): String = when (value.lowercase()) {
        "user_preference", "preference", "environment", "do_not_do", "insight", "fact" -> value.lowercase()
        else -> "insight"
    }

    fun sanitizeSecret(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{12,}"), "sk-***")
            .replace(Regex("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+"), "$1=***")
    }

    fun slug(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "general" }
            .take(80)
    }
}
