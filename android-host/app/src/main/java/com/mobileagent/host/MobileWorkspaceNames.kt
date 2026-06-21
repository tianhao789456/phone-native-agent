package com.mobileagent.host

object MobileWorkspaceNames {
    fun slug(raw: String): String {
        return raw
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_.-]+"), "-")
            .trim('-')
            .ifBlank { "task" }
    }

    fun sanitizeReportName(raw: String): String {
        return raw
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-')
            .ifBlank { "report" }
            .take(120)
    }

    fun sanitizeRelativeFileName(raw: String): String {
        val path = raw.trim().replace('\\', '/').trim('/')
        require(path.isNotBlank()) { "file name is required" }
        require(!path.contains("..")) { "file name cannot contain .." }
        require(!path.startsWith("/")) { "file name must be relative" }
        return path
            .split("/")
            .joinToString("/") { segment ->
                segment
                    .replace(Regex("[^A-Za-z0-9._ -]+"), "-")
                    .trim()
                    .ifBlank { "item" }
            }
            .take(240)
    }

    fun sanitizeTaskStatus(status: String): String {
        return when (status) {
            "created", "pending", "in_progress", "blocked", "completed", "failed", "cancelled" -> status
            else -> "in_progress"
        }
    }

    fun normalizeTaskReportPath(taskPath: String, raw: String): String {
        var path = raw.trim().replace('\\', '/')
        if (path.startsWith("$taskPath/")) path = path.removePrefix("$taskPath/")
        if (path.startsWith("reports/")) path = path.removePrefix("reports/")
        require(path.isNotBlank()) { "report path is required" }
        require(!path.contains("..")) { "report path cannot contain .." }
        require(!path.startsWith("/")) { "report path must be relative" }
        return path
    }
}
