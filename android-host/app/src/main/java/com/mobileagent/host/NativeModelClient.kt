package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class NativeModelResponse(val content: String, val toolCalls: JSONArray)

class NativeModelClient(
    private val profile: NativeAgentProfile = NativeAgentProfile,
    private val log: (level: String, component: String, message: String, details: JSONObject?) -> Unit,
    private val saveUsage: (JSONObject) -> Unit
) {
    fun request(apiKey: String, messages: JSONArray, enabledTools: Set<String>): NativeModelResponse {
        val requestMessages = fitMessagesToBudget(trimMessages(messages))
        val requestStats = messageStats(requestMessages)
        val payload = JSONObject()
            .put("model", profile.MODEL)
            .put("messages", requestMessages)
            .put("tools", NativeToolRegistry.schemasForTools(enabledTools))
            .put("tool_choice", "auto")
        log(
            "info",
            "api",
            "prepared model request",
            JSONObject()
                .put("messages", requestMessages.length())
                .put("estimated_tokens", requestStats.optInt("estimated_tokens"))
                .put("chars", requestStats.optInt("chars"))
                .put("budget_tokens", REQUEST_TOKEN_BUDGET)
        )
        val response = postJson("${profile.BASE_URL}/chat/completions", payload, apiKey)
        val usage = response.optJSONObject("usage") ?: JSONObject()
        saveUsage(usage)
        val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        return NativeModelResponse(
            content = message.optString("content", ""),
            toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        )
    }

    fun request(apiKey: String, messages: JSONArray): NativeModelResponse {
        return request(apiKey, messages, NativeToolRegistry.names().toSet())
    }

    private fun postJson(url: String, payload: JSONObject, apiKey: String): JSONObject {
        var lastError: Exception? = null
        for (attempt in 1..API_MAX_ATTEMPTS) {
            try {
                val result = postJsonOnce(url, payload, apiKey)
                if (attempt > 1) {
                    log(
                        "info",
                        "api",
                        "api request succeeded after retry",
                        JSONObject().put("attempt", attempt).put("url", url)
                    )
                }
                return result
            } catch (exc: Exception) {
                lastError = exc
                val retryable = isRetryableApiFailure(exc)
                log(
                    if (retryable && attempt < API_MAX_ATTEMPTS) "warn" else "error",
                    "api",
                    "api request failed",
                    JSONObject()
                        .put("attempt", attempt)
                        .put("max_attempts", API_MAX_ATTEMPTS)
                        .put("retryable", retryable)
                        .put("error_type", exc.javaClass.simpleName)
                        .put("error", exc.message ?: "")
                        .put("url", url)
                )
                if (!retryable || attempt >= API_MAX_ATTEMPTS) throw exc
                Thread.sleep((500L * attempt).coerceAtMost(1500L))
            }
        }
        throw lastError ?: IllegalStateException("API request failed")
    }

    private fun postJsonOnce(url: String, payload: JSONObject, apiKey: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 120000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (code !in 200..299) {
            throw ApiHttpException(code, json.optString("error", "HTTP $code"))
        }
        return json
    }

    private fun isRetryableApiFailure(exc: Exception): Boolean {
        return when (exc) {
            is SocketTimeoutException -> true
            is IOException -> true
            is ApiHttpException -> exc.statusCode in 500..599
            else -> false
        }
    }

    private fun trimMessages(messages: JSONArray): JSONArray {
        val raw = JSONArray()
        val first = messages.optJSONObject(0)
        val keepFirstSystem = first?.optString("role") == "system"
        if (keepFirstSystem) {
            raw.put(first)
        }
        val start = if (keepFirstSystem) 1 else 0
        for (index in start until messages.length()) {
            if (keepFirstSystem && index == 0) continue
            raw.put(messages.getJSONObject(index))
        }
        val trimmed = JSONArray()
        var index = 0
        while (index < raw.length()) {
            val item = raw.optJSONObject(index)
            if (item == null) {
                index += 1
                continue
            }
            val role = item.optString("role")
            if (role == "tool") {
                index += 1
                continue
            }
            val toolCalls = item.optJSONArray("tool_calls")
            if (role == "assistant" && toolCalls != null && toolCalls.length() > 0) {
                var complete = true
                for (offset in 1..toolCalls.length()) {
                    val toolItem = raw.optJSONObject(index + offset)
                    if (toolItem?.optString("role") != "tool") {
                        complete = false
                        break
                    }
                }
                if (!complete) {
                    index += 1
                    continue
                }
                trimmed.put(item)
                for (offset in 1..toolCalls.length()) {
                    trimmed.put(raw.getJSONObject(index + offset))
                }
                index += toolCalls.length() + 1
                continue
            }
            trimmed.put(item)
            index += 1
        }
        return trimmed
    }

    private fun fitMessagesToBudget(messages: JSONArray): JSONArray {
        var bounded = sanitizeMessages(messages)
        while (estimatedTokens(bounded) > REQUEST_TOKEN_BUDGET && bounded.length() > MIN_REQUEST_MESSAGES) {
            bounded = dropOldestHistoryMessage(bounded)
        }
        return bounded
    }

    private fun sanitizeMessages(messages: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            sanitized.put(sanitizeMessage(item, isLatest = index == messages.length() - 1))
        }
        return sanitized
    }

    private fun sanitizeMessage(item: JSONObject, isLatest: Boolean): JSONObject {
        val copy = JSONObject(item.toString())
        val role = copy.optString("role")
        val maxContentChars = when {
            role == "system" -> MAX_SYSTEM_CONTENT_CHARS
            role == "user" && isLatest -> MAX_LATEST_USER_CONTENT_CHARS
            role == "user" -> MAX_USER_CONTENT_CHARS
            role == "tool" -> MAX_TOOL_CONTENT_CHARS
            else -> MAX_ASSISTANT_CONTENT_CHARS
        }
        if (copy.has("content")) {
            copy.put("content", truncateWithNotice(copy.optString("content", ""), maxContentChars))
        }
        val toolCalls = copy.optJSONArray("tool_calls")
        if (toolCalls != null) {
            val trimmedCalls = JSONArray()
            for (index in 0 until toolCalls.length()) {
                val call = JSONObject(toolCalls.optJSONObject(index)?.toString() ?: "{}")
                val function = call.optJSONObject("function")
                if (function != null && function.has("arguments")) {
                    function.put(
                        "arguments",
                        truncateWithNotice(function.optString("arguments", ""), MAX_TOOL_ARGUMENT_CHARS)
                    )
                }
                trimmedCalls.put(call)
            }
            copy.put("tool_calls", trimmedCalls)
        }
        return copy
    }

    private fun dropOldestHistoryMessage(messages: JSONArray): JSONArray {
        val keepFirstSystem = messages.optJSONObject(0)?.optString("role") == "system"
        val start = if (keepFirstSystem) 1 else 0
        var dropStart = start
        while (dropStart < messages.length() - 1 && messages.optJSONObject(dropStart)?.optString("role") == "tool") {
            dropStart += 1
        }
        val dropIndexes = mutableSetOf(dropStart)
        val item = messages.optJSONObject(dropStart)
        val toolCalls = item?.optJSONArray("tool_calls")
        if (item?.optString("role") == "assistant" && toolCalls != null && toolCalls.length() > 0) {
            for (offset in 1..toolCalls.length()) {
                val toolIndex = dropStart + offset
                if (toolIndex < messages.length() && messages.optJSONObject(toolIndex)?.optString("role") == "tool") {
                    dropIndexes.add(toolIndex)
                }
            }
        }
        val next = JSONArray()
        for (index in 0 until messages.length()) {
            if (index in dropIndexes) continue
            next.put(messages.getJSONObject(index))
        }
        return next
    }

    private fun truncateWithNotice(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val head = (maxChars * 2 / 3).coerceAtLeast(0)
        val tail = (maxChars - head - 160).coerceAtLeast(0)
        return value.take(head) +
            "\n\n[... Mobile Agent truncated this historical message before sending it to the model: original_chars=${value.length}, kept_chars=$maxChars ...]\n\n" +
            value.takeLast(tail)
    }

    private fun messageStats(messages: JSONArray): JSONObject {
        var chars = 0
        for (index in 0 until messages.length()) {
            chars += messages.optJSONObject(index)?.toString()?.length ?: 0
        }
        return JSONObject()
            .put("messages", messages.length())
            .put("chars", chars)
            .put("estimated_tokens", estimateTokens(chars))
    }

    private fun estimatedTokens(messages: JSONArray): Int {
        var chars = 0
        for (index in 0 until messages.length()) {
            chars += messages.optJSONObject(index)?.toString()?.length ?: 0
        }
        return estimateTokens(chars)
    }

    private fun estimateTokens(chars: Int): Int {
        return (chars / 4).coerceAtLeast(1)
    }

    private class ApiHttpException(val statusCode: Int, message: String) : IOException("HTTP $statusCode: $message")

    companion object {
        private const val API_MAX_ATTEMPTS = 3
        private const val REQUEST_TOKEN_BUDGET = 800_000
        private const val MIN_REQUEST_MESSAGES = 6
        private const val MAX_SYSTEM_CONTENT_CHARS = 30_000
        private const val MAX_LATEST_USER_CONTENT_CHARS = 30_000
        private const val MAX_USER_CONTENT_CHARS = 12_000
        private const val MAX_ASSISTANT_CONTENT_CHARS = 12_000
        private const val MAX_TOOL_CONTENT_CHARS = 8_000
        private const val MAX_TOOL_ARGUMENT_CHARS = 4_000
    }
}
