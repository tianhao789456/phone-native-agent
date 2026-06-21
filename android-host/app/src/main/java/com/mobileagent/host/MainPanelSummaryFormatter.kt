package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object MainPanelSummaryFormatter {
    fun localToolsSummary(status: JSONObject): String {
        val tools = status.optJSONArray("tools") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (index in 0 until tools.length()) {
            lines.add("- ${tools.optString(index)}")
        }
        return "当前内置工具：\n" + lines.joinToString("\n") + "\n\n" + MainStatusFormatter.configSummary(status)
    }

    fun officialDocsSummary(docsIndex: JSONObject): String {
        val docs = docsIndex.optJSONArray("documents") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (index in 0 until docs.length()) {
            val item = docs.optJSONObject(index) ?: continue
            lines.add("- ${item.optString("path")} - ${item.optString("title")}")
        }
        return "官方文档：\n" +
            lines.joinToString("\n") +
            "\\n\\nAgent 可用工具：docs_index / docs_read / docs_search / docs_sync\\n文件内容从应用工作目录加载，路径为 docs/official/"
    }
}
