package com.mobileagent.host

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class NativeToolsetController(
    private val prefs: SharedPreferences
) {
    fun resolve(sessionId: String?): Set<String> {
        if (sessionId == null) return NativeToolRegistry.baselineTools()
        val raw = prefs.getString(toolsetSessionKey(sessionId), null)
        if (raw.isNullOrBlank()) return NativeToolRegistry.baselineTools()
        val stored = runCatching {
            val array = JSONArray(raw)
            val values = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.optString(index, "").trim()
                if (item.isNotBlank()) values.add(item)
            }
            values
        }.getOrNull()
        return NativeToolRegistry.normalizeTools(stored ?: emptySet())
    }

    fun request(sessionId: String?, arguments: JSONObject): JSONObject {
        val resolvedSessionId = sessionId ?: prefs.getString("current_session_id", null)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "no active session for toolset_request")
                .put("toolset", JSONObject().put("active", JSONArray(NativeToolRegistry.baselineTools().toList())))
        val mode = arguments.optString("mode", "set").lowercase().trim()
        val requestedGroups = parseStringSet(arguments.optJSONArray("groups"))
        val requestedTools = parseStringSet(arguments.optJSONArray("tools"))
        val replace = arguments.optBoolean("replace", true)
        val currentTools = resolve(resolvedSessionId)

        val requestMode = when {
            requestedGroups.isEmpty() && requestedTools.isEmpty() -> "status"
            mode == "query" || mode == "check" || mode == "status" -> "status"
            mode == "add" || mode == "union" -> "add"
            mode == "remove" || mode == "subtract" || mode == "minus" -> "remove"
            mode == "set" || mode == "replace" -> if (replace) "replace" else "merge"
            else -> if (replace) "replace" else "merge"
        }
        val requestedSetFromGroups = if (requestedGroups.isNotEmpty()) {
            NativeToolRegistry.toolsForGroups(requestedGroups)
        } else {
            emptySet()
        }

        val nextTools = when (requestMode) {
            "status" -> currentTools
            "add" -> NativeToolRegistry.normalizeTools(currentTools + requestedSetFromGroups + requestedTools)
            "remove" -> NativeToolRegistry.normalizeTools(currentTools - (requestedSetFromGroups + requestedTools))
            "replace" -> NativeToolRegistry.normalizeTools(requestedSetFromGroups + requestedTools)
            else -> NativeToolRegistry.normalizeTools(currentTools + requestedSetFromGroups + requestedTools)
        }

        if (requestMode != "status") {
            persist(resolvedSessionId, nextTools)
        }
        return JSONObject()
            .put("ok", true)
            .put("mode", requestMode)
            .put(
                "toolset",
                JSONObject()
                    .put(
                        "requested",
                        JSONObject()
                            .put("mode", mode.ifBlank { "set" })
                            .put("groups", jsonArrayFromSet(requestedGroups))
                            .put("tools", jsonArrayFromSet(requestedTools))
                            .put("replace", replace)
                    )
                    .put("available_groups", NativeToolRegistry.availableGroups())
                    .put("active", JSONArray(NativeToolRegistry.normalizeTools(nextTools).toList()))
            )
    }

    fun registry(arguments: JSONObject, sessionId: String?): JSONObject {
        val activeTools = resolve(sessionId)
        val includeSchema = arguments.optBoolean("include_schema", false)
        val category = arguments.optString("category", "")
        val search = arguments.optString("search", "")
        return JSONObject()
            .put("ok", true)
            .put("progressive_loading", true)
            .put("detail_tool", "tool_info")
            .put("available_groups", NativeToolRegistry.availableGroups())
            .put("active_tool_count", activeTools.size)
            .put("tools", NativeToolRegistry.indexMetadata(activeTools, category, search, includeSchema))
    }

    fun info(arguments: JSONObject): JSONObject {
        val name = arguments.optString("name").trim()
        if (name.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "name is required")
        }
        val descriptor = NativeToolRegistry.descriptor(name)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Unknown tool: $name")
        return JSONObject()
            .put("ok", true)
            .put("tool", descriptor.metadata())
    }

    private fun persist(sessionId: String, toolset: Set<String>) {
        val normalized = NativeToolRegistry.normalizeTools(toolset).toList()
        prefs.edit().putString(toolsetSessionKey(sessionId), JSONArray(normalized).toString()).apply()
    }

    private fun parseStringSet(array: JSONArray?): Set<String> {
        val values = mutableSetOf<String>()
        if (array == null) return values
        for (index in 0 until array.length()) {
            val item = array.optString(index, "").trim()
            if (item.isNotBlank()) values.add(item.lowercase())
        }
        return values
    }

    private fun jsonArrayFromSet(values: Set<String>): JSONArray {
        val result = JSONArray()
        values.forEach { result.put(it) }
        return result
    }

    private fun toolsetSessionKey(sessionId: String): String = "toolset_$sessionId"
}
