package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object MainRuntimeSummaryFormatter {
    fun mcpStatus(result: JSONObject): String {
        val available = result.optBoolean("available", result.optBoolean("ok", false))
        val server = result.optString("server", result.optString("active_server", "default")).ifBlank { "default" }
        val endpoint = result.optString("endpoint", result.optJSONObject("config")?.optString("base_url", "") ?: "")
        val toolCount = result.optInt("tool_count", result.optJSONArray("tools")?.length() ?: 0)
        val lines = mutableListOf<String>()
        lines.add(if (available) "MCP 正常" else "MCP 不可用")
        lines.add("当前服务：${serverDisplayName(result, server)}")
        if (endpoint.isNotBlank()) lines.add("地址：$endpoint")
        if (toolCount > 0) lines.add("工具：$toolCount 个")
        appendErrorOrNote(lines, result)
        lines.add(if (available) "说明：电脑端 MCP 已可用，可以调用电脑文件、命令、窗口、截图等能力。" else "建议：确认电脑端 MCP 服务已启动、Tailscale 在线、地址和 Token 正确。")
        lines.add("原始响应已放在详情里，排查时再看。")
        return lines.joinToString("\n")
    }

    fun mcpTools(result: JSONObject): String {
        val tools = result.optJSONArray("tools") ?: JSONArray()
        val total = result.optInt("tool_count", tools.length())
        if (!result.optBoolean("available", result.optBoolean("ok", tools.length() > 0))) {
            val error = result.optString("error", "MCP 工具暂不可用")
            return "MCP 工具不可用\n原因：${error.take(180)}\n建议：先检查 MCP 状态。"
        }
        val groups = linkedMapOf<String, MutableList<String>>()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index)
            val name = item?.optString("name") ?: tools.optString(index)
            if (name.isBlank()) continue
            val summary = "${compactToolName(name)}：${toolSummary(name, item?.optString("description", "") ?: "")}"
            groups.getOrPut(toolCategory(name)) { mutableListOf() }.add(summary)
        }
        val lines = mutableListOf<String>()
        lines.add("MCP 工具概览")
        lines.add("共 $total 个工具，已按用途折叠展示：")
        for ((category, items) in groups) {
            lines.add("")
            lines.add("$category（${items.size} 个）")
            items.take(4).forEach { lines.add("- $it") }
            if (items.size > 4) lines.add("- 还有 ${items.size - 4} 个，完整列表见详情")
        }
        lines.add("")
        lines.add("说明：工具数量和工具名都来自当前 MCP 返回结果；这里不写死工具清单。未识别的新工具会放到“其他能力”，完整参数、schema 和英文原始描述已保留在详情里。")
        return lines.joinToString("\n")
    }

    fun sshStatus(result: JSONObject): String {
        val connected = result.optBoolean("connected", result.optBoolean("available", false))
        val host = result.optString("host", result.optString("selected_host", ""))
        val user = result.optString("user", result.optString("username", ""))
        val lines = mutableListOf<String>()
        lines.add(if (connected) "SSH 已连接" else "SSH 未连接")
        if (host.isNotBlank()) lines.add("地址：$host")
        if (user.isNotBlank()) lines.add("用户：$user")
        appendErrorOrNote(lines, result)
        lines.add(if (connected) "说明：可以通过 SSH 在电脑上执行命令和传文件。" else "建议：先用 SSH 连接或自动选址，再执行电脑命令。")
        return lines.joinToString("\n")
    }

    fun sshConnect(result: JSONObject): String {
        val ok = result.optBoolean("ok", result.optBoolean("connected", false))
        val host = result.optString("host", result.optString("selected_host", ""))
        val lines = mutableListOf<String>()
        lines.add(if (ok) "SSH 连接成功" else "SSH 连接失败")
        if (host.isNotBlank()) lines.add("地址：$host")
        appendErrorOrNote(lines, result)
        lines.add(if (ok) "下一步：可以运行电脑命令或传输文件。" else "建议：检查电脑 OpenSSH 服务、Tailscale 地址和账号配置。")
        return lines.joinToString("\n")
    }

    fun sshDiagnose(result: JSONObject): String {
        val ok = result.optBoolean("ok", result.optBoolean("reachable", false))
        val candidates = result.optJSONArray("candidates") ?: result.optJSONArray("hosts") ?: JSONArray()
        val lines = mutableListOf<String>()
        lines.add(if (ok) "SSH 诊断通过" else "SSH 诊断发现问题")
        if (candidates.length() > 0) {
            lines.add("候选地址：")
            for (index in 0 until minOf(candidates.length(), 5)) lines.add("- ${candidateLabel(candidates, index)}")
            if (candidates.length() > 5) lines.add("- 还有 ${candidates.length() - 5} 个候选地址")
        }
        appendErrorOrNote(lines, result)
        lines.add("说明：诊断详情已保留，可用于判断是局域网、Tailscale、端口还是认证问题。")
        return lines.joinToString("\n")
    }

    fun sshSelectHost(result: JSONObject): String {
        val ok = result.optBoolean("ok", result.optBoolean("selected", false))
        val host = result.optString("host", result.optString("selected_host", result.optString("applied_host", "")))
        val lines = mutableListOf<String>()
        lines.add(if (ok) "SSH 地址已选择" else "SSH 地址选择失败")
        if (host.isNotBlank()) lines.add("当前地址：$host")
        appendErrorOrNote(lines, result)
        lines.add(if (ok) "下一步：可以尝试 SSH 连接。" else "建议：重新诊断候选地址，优先选能返回 SSH banner 的地址。")
        return lines.joinToString("\n")
    }

    fun sshDisconnect(result: JSONObject): String {
        return if (result.optBoolean("ok", true)) {
            "SSH 已断开\n说明：电脑远程命令和文件传输会暂时不可用。"
        } else {
            "SSH 断开失败\n原因：${result.optString("error", "未知错误").take(160)}"
        }
    }

    fun terminalStatus(result: JSONObject): String {
        val available = result.optBoolean("available", false)
        val endpoint = result.optString("endpoint", result.optJSONObject("config")?.optString("base_url", "") ?: "")
        val lines = mutableListOf<String>()
        lines.add(if (available) "终端接口正常" else "终端接口不可用")
        if (endpoint.isNotBlank()) lines.add("地址：$endpoint")
        appendErrorOrNote(lines, result)
        lines.add(if (available) "说明：手机端 Termux HTTP 后端可用。" else "建议：可改走 SSH/MCP，或点“重连/自检”尝试恢复 Termux 后端。")
        return lines.joinToString("\n")
    }

    fun terminalHealth(result: JSONObject): String {
        val state = result.optString("state", if (result.optBoolean("ok", false)) "ok" else "offline")
        val after = result.optJSONObject("after")
        val afterStatus = after?.optString("status", "") ?: ""
        val actions = result.optJSONArray("actions") ?: JSONArray()
        val lines = mutableListOf<String>()
        lines.add("终端健康检查：${stateText(afterStatus.ifBlank { state })}")
        if (actions.length() > 0) {
            lines.add("处理动作：")
            for (index in 0 until minOf(actions.length(), 4)) lines.add("- ${actions.optString(index)}")
        }
        appendErrorOrNote(lines, result)
        lines.add("说明：如果终端反复异常，任务应自动切到 SSH/MCP 或停止并汇报。")
        return lines.joinToString("\n")
    }

    fun systemLogs(result: JSONObject): String {
        val summary = result.optJSONObject("summary") ?: JSONObject()
        val entries = result.optJSONArray("entries") ?: JSONArray()
        val total = summary.optInt("entries", entries.length())
        val lines = mutableListOf<String>()
        lines.add("系统日志")
        lines.add("共记录：$total 条，本次显示最近 ${entries.length()} 条")
        val important = latestLogLines(entries, 6)
        if (important.isNotEmpty()) {
            lines.add("")
            lines.add("最近记录：")
            important.forEach { lines.add("- $it") }
        }
        lines.add("")
        lines.add("说明：这里只显示摘要；完整日志在详情里。")
        return lines.joinToString("\n")
    }

    private fun serverDisplayName(result: JSONObject, server: String): String {
        val servers = result.optJSONObject("config")?.optJSONArray("servers") ?: JSONArray()
        for (index in 0 until servers.length()) {
            val item = servers.optJSONObject(index) ?: continue
            if (item.optString("id") == server || item.optString("name") == server) {
                return item.optString("name", server).ifBlank { server }
            }
        }
        return server
    }

    private fun toolCategory(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("powershell") || lower.contains("filesystem") || lower.contains("process") ||
                lower.contains("registry") || lower.contains("clipboard") || lower.contains("notification") -> "电脑系统"
            lower.contains("snapshot") || lower.contains("screenshot") || lower.contains("window") ||
                lower.contains("app") || lower.contains("click") || lower.contains("type") ||
                lower.contains("scroll") || lower.contains("hotkey") || lower.contains("key") -> "桌面控制"
            lower.contains("som") || lower.contains("ocr") || lower.contains("vision") || lower.contains("image") -> "视觉识别"
            lower.contains("browser") || lower.contains("page") || lower.contains("web") -> "浏览器/网页"
            else -> "其他能力"
        }
    }

    private fun toolSummary(name: String, description: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("powershell") -> "执行电脑 PowerShell 命令"
            lower.contains("filesystem") -> "读写、复制、移动、搜索电脑文件"
            lower.contains("process") -> "查看或结束电脑进程"
            lower.contains("registry") -> "读写 Windows 注册表"
            lower.contains("clipboard") -> "读取或设置电脑剪贴板"
            lower.contains("notification") -> "发送 Windows 通知"
            lower.contains("snapshot") -> "读取桌面截图和界面元素"
            lower.contains("screenshot") -> "获取桌面或窗口截图"
            lower.contains("app") -> "打开、切换或调整窗口"
            lower.contains("click") -> "点击桌面元素或坐标"
            lower.contains("type") -> "向窗口输入文本"
            lower.contains("scroll") -> "滚动窗口内容"
            lower.contains("hotkey") || lower.contains("shortcut") -> "发送快捷键"
            lower.contains("som") || lower.contains("ocr") -> "截图 OCR 和视觉框选识别"
            lower.contains("browser") || lower.contains("page") -> "读取或操作浏览器页面"
            description.isNotBlank() -> description.take(80)
            else -> "新工具或未分类工具，Agent 可按名称和详情继续查看参数"
        }
    }

    private fun compactToolName(name: String): String {
        return name.substringAfterLast("__").substringAfterLast(".").ifBlank { name }
    }

    private fun appendErrorOrNote(lines: MutableList<String>, result: JSONObject) {
        val error = result.optString("error", "")
        val note = result.optString("note", "")
        if (error.isNotBlank()) lines.add("原因：${error.take(180)}")
        if (note.isNotBlank()) lines.add("备注：${note.take(180)}")
    }

    private fun candidateLabel(array: JSONArray, index: Int): String {
        val item = array.optJSONObject(index)
        if (item != null) {
            val host = item.optString("host", item.optString("address", item.optString("endpoint", "")))
            val state = item.optString("state", item.optString("status", ""))
            return listOf(host, state).filter { it.isNotBlank() }.joinToString(" | ").ifBlank { item.toString().take(120) }
        }
        return array.optString(index).take(120)
    }

    private fun latestLogLines(entries: JSONArray, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        val start = maxOf(0, entries.length() - maxLines)
        for (index in start until entries.length()) {
            val item = entries.optJSONObject(index)
            if (item == null) {
                val text = entries.optString(index)
                if (text.isNotBlank()) lines.add(text.take(140))
                continue
            }
            val level = item.optString("level", "")
            val component = item.optString("component", "")
            val message = item.optString("message", item.optString("event", ""))
            val prefix = listOf(level, component).filter { it.isNotBlank() }.joinToString("/")
            val text = if (prefix.isBlank()) message else "$prefix：$message"
            if (text.isNotBlank()) lines.add(text.take(160))
        }
        return lines
    }

    private fun stateText(state: String): String {
        return when (state) {
            "ok" -> "正常"
            "disabled" -> "未启用"
            "offline" -> "离线"
            "recovering" -> "恢复中"
            "failed" -> "失败"
            "" -> "未知"
            else -> state
        }
    }
}
