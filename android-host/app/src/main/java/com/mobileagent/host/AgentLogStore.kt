package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AgentLogStore {
    private const val PREFS = "mobile-agent-logs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 240
    private val entries = mutableListOf<JSONObject>()

    @Synchronized
    fun record(
        context: Context?,
        level: String,
        component: String,
        message: String,
        details: JSONObject? = null
    ) {
        loadIfNeeded(context)
        val item = JSONObject()
            .put("time_ms", System.currentTimeMillis())
            .put("level", normalizeLevel(level))
            .put("component", component.take(80))
            .put("message", redact(message).take(500))
        if (details != null) item.put("details", redactJson(details))
        entries.add(item)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        save(context)
    }

    @Synchronized
    fun recent(context: Context?, limit: Int = 80, minLevel: String = "debug"): JSONArray {
        loadIfNeeded(context)
        val minRank = levelRank(normalizeLevel(minLevel))
        val result = JSONArray()
        val filtered = entries.filter { levelRank(it.optString("level", "info")) >= minRank }
        val start = (filtered.size - limit.coerceIn(1, MAX_ENTRIES)).coerceAtLeast(0)
        for (index in start until filtered.size) {
            result.put(JSONObject(filtered[index].toString()))
        }
        return result
    }

    @Synchronized
    fun summary(context: Context?): JSONObject {
        loadIfNeeded(context)
        var warnings = 0
        var errors = 0
        entries.forEach {
            when (it.optString("level")) {
                "warn" -> warnings += 1
                "error" -> errors += 1
            }
        }
        return JSONObject()
            .put("entries", entries.size)
            .put("warnings", warnings)
            .put("errors", errors)
            .put("last", entries.lastOrNull() ?: JSONObject())
    }

    @Synchronized
    fun clear(context: Context?) {
        entries.clear()
        save(context)
    }

    private fun loadIfNeeded(context: Context?) {
        if (entries.isNotEmpty() || context == null) return
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, "[]") ?: "[]"
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                entries.add(array.getJSONObject(index))
            }
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    private fun save(context: Context?) {
        if (context == null) return
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, array.toString())
                .apply()
        }
    }

    private fun normalizeLevel(level: String): String {
        return when (level.lowercase()) {
            "debug", "info", "warn", "error" -> level.lowercase()
            "warning" -> "warn"
            else -> "info"
        }
    }

    private fun levelRank(level: String): Int {
        return when (level) {
            "error" -> 3
            "warn" -> 2
            "info" -> 1
            else -> 0
        }
    }

    private fun redactJson(value: JSONObject): JSONObject {
        return JSONObject().put("raw", redact(value.toString()).take(3000))
    }

    private fun redact(value: String): String {
        return value.replace(Regex("sk-[A-Za-z0-9_\\-]{8,}"), "sk-***")
    }
}
