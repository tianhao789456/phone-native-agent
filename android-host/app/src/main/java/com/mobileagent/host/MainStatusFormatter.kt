package com.mobileagent.host

import org.json.JSONObject

object MainStatusFormatter {
    fun summarizeStatus(status: JSONObject?): String {
        if (status == null || !status.optBoolean("ok")) {
            return "模型 - | 上下文 - | 缓存 - | 权限 - | 终端 - | MCP -"
        }
        val model = status.optString("model", "-")
        val permission = status.optString("permission_mode", "safe")
        val context = status.optJSONObject("context")
        val tokens = context?.optInt("estimated_tokens", 0) ?: 0
        val window = context?.optInt("model_window", 0) ?: 0
        val usedPercent = context?.optDouble("window_used_percent", Double.NaN) ?: Double.NaN
        val messages = context?.optInt("messages", 0) ?: 0
        val compacted = context?.optBoolean("compacted", false) == true
        val usage = status.optJSONObject("usage")
        val latestHit = usage?.optLong("prompt_cache_hit_tokens", 0L) ?: 0L
        val latestMiss = usage?.optLong("prompt_cache_miss_tokens", 0L) ?: 0L
        val sessionUsage = usage?.optJSONObject("session")
        val sessionHit = sessionUsage?.optLong("prompt_cache_hit_tokens", 0L) ?: 0L
        val sessionMiss = sessionUsage?.optLong("prompt_cache_miss_tokens", 0L) ?: 0L
        val request = usage?.optJSONObject("cache_diagnostics")?.optJSONObject("request")
            ?: usage?.optJSONObject("mobile_agent_request")
        val toolCount = request?.optInt("tool_count", 0) ?: 0
        val cache = listOf(
            cacheSummary(latestHit, latestMiss, "本轮"),
            cacheSummary(sessionHit, sessionMiss, "会话")
        ).filter { it.isNotBlank() }.joinToString(" | ")
        val terminalLabel = terminalRuntimeLabel(status.optJSONObject("terminal_runtime"), status.optJSONObject("terminal"))
        val mcpLabel = mcpRuntimeLabel(status.optJSONObject("mcp_runtime"), status.optJSONObject("mcp"))
        val rawContextText = if (window > 0 && !usedPercent.isNaN()) {
            "${formatTokenK(tokens.toLong())}/${formatTokenK(window.toLong())} (${usedPercent.toInt()}%)"
        } else {
            "${formatTokenK(tokens.toLong())}/${messages}条"
        }
        val compactText = if (compacted) " | 已压缩" else ""
        val toolText = if (toolCount > 0) " | 工具 $toolCount" else ""
        return "模型 $model | 上下文 $rawContextText$compactText$toolText | 缓存 $cache | 权限 ${permissionLabel(permission)} | 终端 $terminalLabel | MCP $mcpLabel"
    }

    fun terminalHeaderLabel(status: JSONObject?): String {
        return "终端 ${terminalRuntimeLabel(status?.optJSONObject("terminal_runtime"), status?.optJSONObject("terminal"))}"
    }

    fun mcpHeaderLabel(status: JSONObject?): String {
        return "MCP ${mcpRuntimeLabel(status?.optJSONObject("mcp_runtime"), status?.optJSONObject("mcp"))}"
    }

    fun configSummary(status: JSONObject): String {
        val terminal = status.optJSONObject("terminal") ?: JSONObject()
        val terminalText = if (terminal.optBoolean("enabled")) {
            "终端 ${terminal.optString("base_url", "-")}"
        } else {
            "终端未启用"
        }
        val mcp = status.optJSONObject("mcp") ?: JSONObject()
        val mcpText = if (mcp.optBoolean("enabled")) {
            val tokenText = if (mcp.optBoolean("has_auth_token", false)) "有 token" else "无 token"
            "MCP ${mcp.optString("base_url", "-")} ($tokenText)"
        } else {
            "MCP 未启用"
        }
        val ssh = status.optJSONObject("ssh") ?: JSONObject()
        val sshText = if (ssh.optBoolean("enabled")) {
            val connectedText = if (status.optJSONObject("ssh_runtime")?.optBoolean("connected", false) == true) "已连接" else "未连接"
            val keyText = if (ssh.optString("key_path").isNotBlank()) "有私钥" else "无私钥"
            "SSH ${ssh.optString("host", "-")}:${ssh.optInt("port", 22)} ${ssh.optString("user", "-")} ($connectedText, $keyText)"
        } else {
            "SSH 未启用"
        }
        val maxToolRounds = status.optJSONObject("config")?.optInt("max_tool_rounds", 30) ?: 30
        return "当前配置：权限 ${permissionLabel(status.optString("permission_mode", "safe"))} | 工具轮数上限 $maxToolRounds | $terminalText | $mcpText | $sshText"
    }

    fun formatTokenK(value: Long): String {
        return when {
            value >= 1_000_000L -> {
                val oneDecimal = value / 100_000L / 10.0
                if (value % 1_000_000L == 0L) "${value / 1_000_000L}m" else "${oneDecimal}m"
            }
            value >= 1000L -> {
                val oneDecimal = value / 100L / 10.0
                if (value % 1000L == 0L) "${value / 1000L}k" else "${oneDecimal}k"
            }
            else -> "${value}t"
        }
    }

    fun permissionLabel(mode: String): String {
        return when (mode) {
            AgentRuntimeConfig.MODE_DANGER -> "危险"
            AgentRuntimeConfig.MODE_DEVELOPER -> "开发者"
            AgentRuntimeConfig.MODE_ASK -> "确认"
            else -> "安全"
        }
    }

    private fun terminalRuntimeLabel(runtime: JSONObject?, config: JSONObject?): String {
        if (config?.optBoolean("enabled") != true) return "未启用"
        return when (runtime?.optString("state", "")) {
            "ok" -> "正常"
            "recovered" -> "已恢复"
            "recovering" -> "重连中"
            "checking" -> "检查中"
            "failed" -> "故障"
            "circuit_open" -> "熔断"
            "partial_backend" -> "后端部分可用"
            "backend_unavailable" -> "服务不可用"
            "timeout" -> "超时"
            "connection_refused" -> "连接拒绝"
            "offline" -> "离线"
            else -> "未知"
        }
    }

    private fun mcpRuntimeLabel(runtime: JSONObject?, config: JSONObject?): String {
        if (config?.optBoolean("enabled") != true) return "未启用"
        return when (runtime?.optString("state", "")) {
            "ok" -> "正常"
            "connecting", "checking" -> "连接中"
            "offline" -> "离线"
            "partial_backend" -> "后端部分可用"
            else -> "离线"
        }
    }

    private fun cacheSummary(hit: Long, miss: Long, label: String): String {
        val total = hit + miss
        if (total <= 0L) return "$label -"
        val rate = hit.toDouble() / total.toDouble()
        return "$label 命中率 ${(rate * 100).toInt()}% (${formatTokenK(hit)}/${formatTokenK(total)})"
    }
}
