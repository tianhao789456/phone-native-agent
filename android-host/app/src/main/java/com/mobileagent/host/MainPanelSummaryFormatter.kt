package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object MainPanelSummaryFormatter {
    fun localToolsSummary(status: JSONObject): String {
        val tools = status.optJSONArray("tools") ?: JSONArray()
        val groups = linkedMapOf(
            "屏幕/无障碍" to mutableListOf<String>(),
            "点击/输入" to mutableListOf<String>(),
            "文件/工作区" to mutableListOf<String>(),
            "终端/SSH/MCP" to mutableListOf<String>(),
            "记忆/技能/插件" to mutableListOf<String>(),
            "诊断/文档" to mutableListOf<String>(),
            "其他" to mutableListOf<String>(),
        )
        for (index in 0 until tools.length()) {
            val name = tools.optString(index)
            if (name.isBlank()) continue
            groups.getValue(localToolCategory(name)).add(name)
        }

        val lines = mutableListOf<String>()
        lines.add("内置工具概览")
        lines.add("共 ${tools.length()} 个工具，按用途分组：")
        for ((category, names) in groups) {
            if (names.isEmpty()) continue
            lines.add("")
            lines.add("$category（${names.size} 个）")
            names.take(6).forEach { lines.add("- $it：${localToolSummary(it)}") }
            if (names.size > 6) lines.add("- 还有 ${names.size - 6} 个")
        }
        lines.add("")
        lines.add("说明：工具数量和名称来自当前运行状态，不写死工具清单。新插件或新工具会进入“其他”，Agent 真正调用时会用 tool_info 查看参数。")
        lines.add("")
        lines.add(MainStatusFormatter.configSummary(status))
        return lines.joinToString("\n")
    }

    fun officialDocsSummary(docsIndex: JSONObject): String {
        val docs = docsIndex.optJSONArray("documents") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (index in 0 until docs.length()) {
            val item = docs.optJSONObject(index) ?: continue
            val title = item.optString("title", item.optString("path"))
            val path = item.optString("path")
            lines.add("- $title：$path")
        }
        return "官方文档\n" +
            "共 ${docs.length()} 篇：\n" +
            lines.joinToString("\n") +
            "\n\nAgent 可用 docs_index / docs_read / docs_search / docs_sync 读取全文或搜索。文档来自应用工作目录 docs/official/。"
    }

    private fun localToolCategory(name: String): String {
        return when {
            name.contains("screen") || name.contains("accessibility") || name.contains("snapshot") || name.contains("observe") -> "屏幕/无障碍"
            name.contains("click") || name.contains("press") || name.contains("swipe") || name.contains("input") || name.contains("text") -> "点击/输入"
            name.contains("file") || name.contains("workspace") || name.contains("storage") || name.contains("path") -> "文件/工作区"
            name.contains("terminal") || name.contains("ssh") || name.contains("mcp") || name.contains("shell") -> "终端/SSH/MCP"
            name.contains("memory") || name.contains("skill") || name.contains("plugin") || name.contains("procedure") || name.contains("learning") -> "记忆/技能/插件"
            name.contains("health") || name.contains("log") || name.contains("docs") || name.contains("tool") || name.contains("report") -> "诊断/文档"
            else -> "其他"
        }
    }

    private fun localToolSummary(name: String): String {
        return when {
            name.contains("accessibility_snapshot") -> "读取结构化 UI 树"
            name.contains("screen") || name.contains("observe") -> "观察当前屏幕"
            name.contains("click") -> "点击界面元素"
            name.contains("input") || name.contains("text") -> "输入文本"
            name.contains("file_push") -> "把手机文件传到电脑"
            name.contains("file_pull") -> "从电脑取回文件"
            name.contains("workspace") -> "读写应用工作区"
            name.contains("terminal") -> "调用手机终端后端"
            name.contains("ssh") -> "通过 SSH 控制电脑"
            name.contains("mcp") -> "通过 MCP 调用电脑工具"
            name.contains("memory") -> "读写记忆和经验"
            name.contains("skill") -> "发现和运行技能"
            name.contains("plugin") -> "运行插件工作流"
            name.contains("docs") -> "读取项目文档"
            name.contains("health") -> "自检运行状态"
            name.contains("log") -> "查看系统日志"
            else -> "新工具或未分类工具，Agent 可按名称继续查看参数"
        }
    }
}
