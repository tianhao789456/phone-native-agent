package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MobileMemoryProfileStore(
    private val profileFile: File,
    private val paths: () -> JSONObject,
    private val nowIso: () -> String,
    private val nowMs: () -> Long,
    private val readJson: (File, JSONObject) -> JSONObject,
    private val writeJson: (File, JSONObject) -> Unit,
    private val emptyProfile: () -> JSONObject
) {
    fun readProfile(): JSONObject = readJson(profileFile, emptyProfile())

    fun query(question: String, limit: Int = 5): JSONObject {
        val memory = searchMemory(question, limit)
        val answers = JSONArray()
        val q = MobileMemoryText.tokenSet(question)
        val insights = memory.optJSONArray("matches") ?: JSONArray()
        for (index in 0 until insights.length()) {
            val item = insights.optJSONObject(index) ?: continue
            val text = item.optString("text")
            if (MobileMemoryText.scoreTokens(q, MobileMemoryText.tokenSet(text)) >= 2) {
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
        val profile = readProfile()
        val q = MobileMemoryText.tokenSet(query)
        val candidates = mutableListOf<Pair<Int, JSONObject>>()

        fun add(kind: String, text: String, path: String, extra: JSONObject = JSONObject()) {
            val score = MobileMemoryText.scoreText(query, q, text)
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
                add(key, MobileMemoryText.readableMemoryItem(item), "memory/user_profile.json", item)
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
        val profile = readProfile()
        val type = MobileMemoryText.sanitizeMemoryType(arguments.optString("type", "preference"))
        val key = arguments.optString("key", "").trim().ifBlank { type }
        val value = MobileMemoryText.sanitizeSecret(arguments.optString("value", arguments.optString("text", ""))).trim()
        if (value.isBlank()) return error("value is required")
        val confidence = MobileMemoryText.sanitizeConfidence(arguments.optString("confidence", "medium"))
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
            stored.put("confidence", MobileMemoryText.strongerConfidence(stored.optString("confidence", "medium"), confidence))
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

    fun updateMemory(arguments: JSONObject): JSONObject {
        val profile = readProfile()
        val target = arguments.optString("target", "").trim()
        val key = arguments.optString("key", "").trim()
        val text = arguments.optString("text", "").trim()
        val value = MobileMemoryText.sanitizeSecret(arguments.optString("value", "")).trim()
        val confidence = arguments.optString("confidence", "").trim()
        val arrays = if (target.isNotBlank()) listOf(target) else listOf("preferences", "environment", "do_not_do", "insights", "task_history")
        for (arrayName in arrays) {
            val array = profile.optJSONArray(arrayName) ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyMatches = key.isNotBlank() && item.optString("key").equals(key, ignoreCase = true)
                val textMatches = text.isNotBlank() && MobileMemoryText.readableMemoryItem(item).contains(text, ignoreCase = true)
                if (!keyMatches && !textMatches) continue
                if (value.isNotBlank()) {
                    item.put("value", value)
                    item.put("text", "${item.optString("key", key).ifBlank { arrayName }}: $value")
                }
                if (arguments.has("type")) item.put("type", MobileMemoryText.sanitizeMemoryType(arguments.optString("type")))
                if (confidence.isNotBlank()) item.put("confidence", MobileMemoryText.sanitizeConfidence(confidence))
                item.put("updated_at_ms", nowMs())
                item.put("last_seen_ms", nowMs())
                profile.put("last_updated", nowIso())
                writeJson(profileFile, profile)
                return JSONObject()
                    .put("ok", true)
                    .put("target", arrayName)
                    .put("item", item)
                    .put("paths", paths())
            }
        }
        return error("memory item not found; provide target plus key or text")
    }

    fun deleteMemory(arguments: JSONObject): JSONObject {
        val profile = readProfile()
        val target = arguments.optString("target", "").trim()
        val key = arguments.optString("key", "").trim()
        val text = arguments.optString("text", "").trim()
        val arrays = if (target.isNotBlank()) listOf(target) else listOf("preferences", "environment", "do_not_do", "insights", "task_history")
        for (arrayName in arrays) {
            val array = profile.optJSONArray(arrayName) ?: continue
            val kept = JSONArray()
            var removed: JSONObject? = null
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyMatches = key.isNotBlank() && item.optString("key").equals(key, ignoreCase = true)
                val textMatches = text.isNotBlank() && MobileMemoryText.readableMemoryItem(item).contains(text, ignoreCase = true)
                if (removed == null && (keyMatches || textMatches)) {
                    removed = item
                } else {
                    kept.put(item)
                }
            }
            if (removed != null) {
                profile.put(arrayName, kept)
                profile.put("last_updated", nowIso())
                writeJson(profileFile, profile)
                return JSONObject()
                    .put("ok", true)
                    .put("target", arrayName)
                    .put("deleted", removed)
                    .put("paths", paths())
            }
        }
        return error("memory item not found; provide target plus key or text")
    }

    fun recordTask(task: String, status: String, finalAnswer: String, toolsUsed: JSONArray, appsUsed: JSONArray, runId: String): JSONObject {
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
            .put("final_answer", MobileMemoryText.sanitizeSecret(finalAnswer.take(2000)))
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

    private fun findDuplicate(array: JSONArray, text: String, key: String): Int {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (key.isNotBlank() && item.optString("key").equals(key, ignoreCase = true)) return index
            if (MobileMemoryText.jaccard(MobileMemoryText.tokenSet(text), MobileMemoryText.tokenSet(item.optString("text"))) >= 0.5) return index
        }
        return -1
    }

    private fun trimArray(container: JSONObject, key: String, max: Int) {
        val array = container.optJSONArray(key) ?: return
        if (array.length() <= max) return
        val trimmed = JSONArray()
        for (index in array.length() - max until array.length()) trimmed.put(array.get(index))
        container.put(key, trimmed)
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message).put("paths", paths())
}
