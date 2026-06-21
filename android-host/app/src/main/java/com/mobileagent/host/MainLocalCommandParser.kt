package com.mobileagent.host

object MainLocalCommandParser {
    fun normalize(text: String): String? {
        val raw = text.trim().trimStart('\ufeff')
        val normalized = raw.lowercase()
        if (normalized.startsWith("-key ") || normalized.startsWith("/key ")) {
            return "key:${raw.substringAfter(' ').trim()}"
        }
        if (looksLikeApiKey(raw)) {
            return "key:$raw"
        }
        if (normalized.startsWith("-perm ") || normalized.startsWith("/perm ") || normalized.startsWith("perm ")) {
            return "perm:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-terminal ") || normalized.startsWith("/terminal ") || normalized.startsWith("terminal ")) {
            return "terminal:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-termux ") || normalized.startsWith("/termux ") || normalized.startsWith("termux ")) {
            return "terminal:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-mcp ") || normalized.startsWith("/mcp ") || normalized.startsWith("mcp ")) {
            return "mcp:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-ssh ") || normalized.startsWith("/ssh ") || normalized.startsWith("ssh ")) {
            return "ssh:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-rounds ") || normalized.startsWith("/rounds ") || normalized.startsWith("rounds ")) {
            return "rounds:${raw.substringAfter(' ').trim()}"
        }
        return when (normalized) {
            "new", "-new", "--new", "/new", "-new-session", "--new-session", "/new-session" -> "new"
            "status", "-status", "--status", "/status" -> "status"
            "tools", "-tools", "--tools", "/tools" -> "tools"
            "docs", "-docs", "--docs", "/docs", "doc", "-doc", "/doc", "官方文档", "-官方文档", "/官方文档" -> "docs"
            "config", "-config", "--config", "/config", "settings", "-settings", "/settings" -> "config"
            "panel", "-panel", "--panel", "/panel", "menu", "-menu", "/menu" -> "panel"
            "reconnect", "-reconnect", "--reconnect", "/reconnect", "health", "-health", "/health" -> "reconnect"
            "logs", "-logs", "--logs", "/logs", "log", "-log", "/log" -> "logs"
            "compact", "-compact", "--compact", "/compact",
            "压缩", "-压缩", "/压缩",
            "压缩上下文", "-压缩上下文", "/压缩上下文" -> "compact"
            "failures", "-failures", "--failures", "/failures",
            "failure", "-failure", "/failure",
            "失败分析", "-失败分析", "/失败分析" -> "failures"
            "help", "-help", "--help", "/help", "?" -> "help"
            "clear", "-clear", "--clear", "/clear" -> "clear"
            else -> null
        }
    }

    fun normalizePermissionMode(mode: String): String {
        return when (mode.lowercase()) {
            "safe", "safe_mode", "安全" -> AgentRuntimeConfig.MODE_SAFE
            "ask", "confirm", "确认", "确认动作" -> AgentRuntimeConfig.MODE_ASK
            "danger", "high", "高风险" -> AgentRuntimeConfig.MODE_DANGER
            "developer", "dev", "开发者", "开发模式" -> AgentRuntimeConfig.MODE_DEVELOPER
            else -> mode.lowercase()
        }
    }

    fun isHighPowerMode(mode: String): Boolean {
        return mode == AgentRuntimeConfig.MODE_DANGER || mode == AgentRuntimeConfig.MODE_DEVELOPER
    }

    fun looksLikeApiKey(text: String): Boolean {
        val value = text.trim()
        if (value.contains(" ") || value.contains("\n") || value.length < 12) return false
        return value.startsWith("sk-") || value.startsWith("sk_") || value.lowercase().startsWith("sk-")
    }
}
