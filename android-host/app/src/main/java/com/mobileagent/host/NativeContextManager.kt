package com.mobileagent.host

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class NativeContextManager(
    private val prefs: SharedPreferences,
    private val modelWindowTokens: Int,
    private val compactTokenThreshold: Int = 500_000,
    private val keepRecentMessages: Int = 40,
    private val compactSummaryChars: Int = 8_000,
    private val requestTokenBudget: Int = 800_000,
    private val contextCompactionMarker: String = "[MOBILE_AGENT_CONTEXT_COMPACTION_V1]"
) {
    fun contextStats(messages: JSONArray): JSONObject {
        var estimated = 0
        val byRole = JSONObject()
        var compacted = false
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            val role = item.optString("role", "unknown")
            val content = item.optString("content", "")
            if (content.startsWith(contextCompactionMarker)) compacted = true
            val tokens = estimateTokens(content)
            estimated += tokens
            byRole.put(role, byRole.optInt(role, 0) + tokens)
        }
        return JSONObject()
            .put("messages", messages.length())
            .put("estimated_tokens", estimated)
            .put("model_window", modelWindowTokens)
            .put(
                "window_used_percent",
                if (estimated == 0) 0.0 else estimated * 100.0 / modelWindowTokens.toDouble()
            )
            .put("compacted", compacted)
            .put("compactions", prefs.getInt("context_compactions", 0))
            .put("auto_compact_token_threshold", compactTokenThreshold)
            .put("auto_compact_policy", "token_only")
            .put("request_token_budget", requestTokenBudget)
            .put("request_trim_policy", "token_budget_only")
            .put("cache_prefix_policy", "stable_system_and_sorted_tools_before_dynamic_messages")
            .put("by_role", byRole)
    }

    fun compactMessagesIfNeeded(sessionId: String, messages: JSONArray, force: Boolean = false): JSONObject {
        val beforeStats = contextStats(messages)
        val beforeMessages = messages.length()
        val beforeTokens = beforeStats.optInt("estimated_tokens", 0)
        if (!force && beforeTokens <= compactTokenThreshold) {
            return JSONObject()
                .put("compacted", false)
                .put("reason", "below_threshold")
                .put("messages", beforeMessages)
                .put("estimated_tokens", beforeTokens)
        }
        if (messages.length() <= keepRecentMessages + 2) {
            return JSONObject()
                .put("compacted", false)
                .put("reason", "not_enough_history")
                .put("messages", beforeMessages)
                .put("estimated_tokens", beforeTokens)
        }

        val keepFirstSystem = messages.optJSONObject(0)?.optString("role") == "system"
        var tailStart = (messages.length() - keepRecentMessages).coerceAtLeast(if (keepFirstSystem) 1 else 0)
        while (tailStart < messages.length() && messages.optJSONObject(tailStart)?.optString("role") == "tool") {
            tailStart += 1
        }
        if (tailStart >= messages.length() - 1) {
            tailStart = (messages.length() - 8).coerceAtLeast(if (keepFirstSystem) 1 else 0)
        }

        val older = JSONArray()
        val olderStart = if (keepFirstSystem) 1 else 0
        for (index in olderStart until tailStart) {
            older.put(messages.getJSONObject(index))
        }
        val summary = buildContextCompactionSummary(older, beforeMessages, beforeTokens)
        val compacted = JSONArray()
        if (keepFirstSystem) {
            compacted.put(JSONObject(messages.getJSONObject(0).toString()))
        }
        compacted.put(JSONObject().put("role", "system").put("content", summary))
        for (index in tailStart until messages.length()) {
            compacted.put(messages.getJSONObject(index))
        }

        while (messages.length() > 0) {
            messages.remove(0)
        }
        for (index in 0 until compacted.length()) {
            messages.put(compacted.getJSONObject(index))
        }
        val afterStats = contextStats(messages)
        val compactions = prefs.getInt("context_compactions", 0) + 1
        prefs.edit()
            .putInt("context_compactions", compactions)
            .putLong("context_last_compacted_at", System.currentTimeMillis())
            .apply()
        return JSONObject()
            .put("compacted", true)
            .put("session_id", sessionId)
            .put("before_messages", beforeMessages)
            .put("after_messages", messages.length())
            .put("before_estimated_tokens", beforeTokens)
            .put("after_estimated_tokens", afterStats.optInt("estimated_tokens", 0))
            .put("kept_recent_messages", messages.length() - 2)
            .put("compactions", compactions)
    }

    fun saveUsage(usage: JSONObject) {
        val sessionId = prefs.getString("current_session_id", null) ?: "default"
        val cache = cacheUsageTokens(usage)
        val cached = cache.first
        val miss = cache.second
        val total = usage.optLong("prompt_tokens", cached + miss)
        prefs.edit()
            .putString(usageLatestKey(sessionId), usage.toString())
            .putLong(usagePromptKey(sessionId), prefs.getLong(usagePromptKey(sessionId), 0L) + total)
            .putLong(usageHitKey(sessionId), prefs.getLong(usageHitKey(sessionId), 0L) + cached)
            .putLong(usageMissKey(sessionId), prefs.getLong(usageMissKey(sessionId), 0L) + miss)
            .apply()
    }

    fun latestUsage(sessionId: String?): JSONObject {
        val keySessionId = sessionId ?: prefs.getString("current_session_id", null) ?: "default"
        val usage = runCatching { JSONObject(prefs.getString(usageLatestKey(keySessionId), "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val cache = cacheUsageTokens(usage)
        val cached = cache.first
        val miss = cache.second
        val total = (cached + miss).coerceAtLeast(1L)
        if (cached > 0 || miss > 0) {
            usage.put("prompt_cache_hit_tokens", cached)
            usage.put("prompt_cache_miss_tokens", miss)
            usage.put("cache_hit_rate", cached.toDouble() / total.toDouble())
        }
        val sessionCached = prefs.getLong(usageHitKey(keySessionId), 0L)
        val sessionMiss = prefs.getLong(usageMissKey(keySessionId), 0L)
        val sessionDenominator = (sessionCached + sessionMiss).coerceAtLeast(1L)
        usage.put(
            "session",
            JSONObject()
                .put("session_id", keySessionId)
                .put("prompt_tokens", prefs.getLong(usagePromptKey(keySessionId), 0L))
                .put("prompt_cache_hit_tokens", sessionCached)
                .put("prompt_cache_miss_tokens", sessionMiss)
                .put("cache_hit_rate", sessionCached.toDouble() / sessionDenominator.toDouble())
        )
        usage.put("scope", "latest_and_session")
        usage.put(
            "cache_diagnostics",
            JSONObject()
                .put("provider", "deepseek")
                .put("hit_rate_basis", "prompt_cache_hit_tokens / (prompt_cache_hit_tokens + prompt_cache_miss_tokens)")
                .put("latest_cacheable_tokens", cached + miss)
                .put("session_cacheable_tokens", sessionCached + sessionMiss)
                .put("prefix_policy", "stable_system_and_sorted_tools_before_dynamic_messages")
                .put("request", usage.optJSONObject("mobile_agent_request") ?: JSONObject())
        )
        return usage
    }

    private fun buildContextCompactionSummary(older: JSONArray, beforeMessages: Int, beforeTokens: Int): String {
        val users = mutableListOf<String>()
        val assistants = mutableListOf<String>()
        val tools = mutableListOf<String>()
        var previousSummary = ""
        for (index in 0 until older.length()) {
            val item = older.optJSONObject(index) ?: continue
            val role = item.optString("role")
            val content = item.optString("content", "").trim()
            if (content.startsWith(contextCompactionMarker)) {
                previousSummary = content.take(compactSummaryChars / 3)
                continue
            }
            when (role) {
                "user" -> users.add(content.take(220))
                "assistant" -> assistants.add(content.take(220))
                "tool" -> tools.add(toolSummaryForCompaction(item).take(260))
            }
        }
        val builder = StringBuilder()
        builder.append(contextCompactionMarker).append("\n")
        builder.append("上下文自动压缩统计：总消息=").append(beforeMessages)
            .append("，总token=").append(beforeTokens).append("。\n")
        if (previousSummary.isNotBlank()) {
            builder.append("\n上一版摘要如下：\n")
                .append(previousSummary.removePrefix(contextCompactionMarker).trim().take(1800))
                .append("\n")
        }
        appendCompactionSection(builder, "最近用户消息", users.takeLast(8))
        appendCompactionSection(builder, "最近助手消息", assistants.takeLast(8))
        appendCompactionSection(builder, "最近工具消息", tools.takeLast(10))
        return builder.toString().take(compactSummaryChars)
    }

    private fun appendCompactionSection(builder: StringBuilder, title: String, items: List<String>) {
        if (items.isEmpty()) return
        builder.append("\n").append(title).append("：\n")
        items.forEach { item ->
            val clean = item.replace(Regex("\\s+"), " ").trim()
            if (clean.isNotBlank()) builder.append("- ").append(clean).append("\n")
        }
    }

    private fun toolSummaryForCompaction(item: JSONObject): String {
        val name = item.optString("name", "tool")
        val content = item.optString("content", "")
        val parsed = runCatching { JSONObject(content) }.getOrNull()
        val loop = parsed?.optJSONObject("loop")
        val state = loop?.optString("state", "") ?: ""
        val summary = loop?.optString("summary", "") ?: content
        return "$name $state $summary"
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun cacheUsageTokens(usage: JSONObject): Pair<Long, Long> {
        val directHit = usage.optLong("prompt_cache_hit_tokens", -1L)
        val directMiss = usage.optLong("prompt_cache_miss_tokens", -1L)
        if (directHit >= 0L || directMiss >= 0L) {
            return directHit.coerceAtLeast(0L) to directMiss.coerceAtLeast(0L)
        }
        val details = usage.optJSONObject("prompt_tokens_details")
        val cached = details?.optLong("cached_tokens", 0L) ?: 0L
        val total = usage.optLong("prompt_tokens", 0L)
        return cached to (total - cached).coerceAtLeast(0L)
    }

    private fun usageLatestKey(sessionId: String): String = "usage_latest_$sessionId"
    private fun usagePromptKey(sessionId: String): String = "usage_prompt_tokens_$sessionId"
    private fun usageHitKey(sessionId: String): String = "usage_cache_hit_$sessionId"
    private fun usageMissKey(sessionId: String): String = "usage_cache_miss_$sessionId"
}
