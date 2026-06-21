package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object MemoryPanelFormatter {
    fun formatMemorySummary(result: JSONObject): String {
        val lines = mutableListOf<String>()
        lines.add("路径: ${result.optJSONObject("paths")?.optString("root", "-") ?: "-"}")
        val profile = result.optJSONObject("profile") ?: JSONObject()
        lines.add("UserMemory")
        lines.add(profile.optJSONObject("profile")?.toString(2) ?: "{}")
        lines.add("")
        lines.add("ExperienceLog")
        lines.add(result.optJSONObject("experience_stats")?.toString(2) ?: "{}")
        val groups = result.optJSONObject("experience_groups") ?: JSONObject()
        groups.keys().forEach { key ->
            val arr = groups.optJSONArray(key) ?: JSONArray()
            lines.add("")
            lines.add("[$key] ${arr.length()} 条")
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                lines.add("id=${item.optInt("id")} confidence=${item.optString("confidence")} reinforced=${item.optInt("reinforced")} last_seen=${item.optLong("last_seen_ms")} type=${item.optString("lesson_type")}")
                lines.add(item.optString("description").take(500))
            }
        }
        lines.add("")
        lines.add("Procedures")
        lines.add(formatProcedureList(result))
        return lines.joinToString("\n")
    }

    fun formatExperienceMatches(result: JSONObject): String {
        val arr = result.optJSONArray("matches") ?: JSONArray()
        if (arr.length() == 0) return "没有匹配经验\n\n${result.toString(2)}"
        val lines = mutableListOf<String>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            lines.add("id=${item.optInt("id")} app=${item.optString("app")} tool_scope=${item.optString("tool_scope")} type=${item.optString("lesson_type")} confidence=${item.optString("confidence")} reinforced=${item.optInt("reinforced")} last_seen=${item.optLong("last_seen_ms")}")
            lines.add(item.optString("description").take(700))
            lines.add("")
        }
        return lines.joinToString("\n")
    }

    fun formatProcedureList(result: JSONObject): String {
        val arr = result.optJSONArray("procedures") ?: result.optJSONArray("matches") ?: JSONArray()
        if (arr.length() == 0) return "暂无 Procedure"
        val lines = mutableListOf<String>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            lines.add("${index + 1}. ${item.optString("path")} app=${item.optString("app")} tool_scope=${item.optString("tool_scope")} bytes=${item.optLong("bytes")}")
            if (item.has("preview")) lines.add(item.optString("preview").take(500))
        }
        return lines.joinToString("\n")
    }
}
