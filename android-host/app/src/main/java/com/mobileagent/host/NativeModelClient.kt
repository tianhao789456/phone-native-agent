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
        val payload = JSONObject()
            .put("model", profile.MODEL)
            .put("messages", trimMessages(messages, profile.MESSAGE_TRIM_LIMIT))
            .put("tools", NativeToolRegistry.schemasForTools(enabledTools))
            .put("tool_choice", "auto")
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

    private fun trimMessages(messages: JSONArray, limit: Int): JSONArray {
        val raw = JSONArray()
        val first = messages.optJSONObject(0)
        val keepFirstSystem = first?.optString("role") == "system"
        if (keepFirstSystem) {
            raw.put(first)
        }
        val start = (messages.length() - limit).coerceAtLeast(0)
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

    private class ApiHttpException(val statusCode: Int, message: String) : IOException("HTTP $statusCode: $message")

    companion object {
        private const val API_MAX_ATTEMPTS = 3
    }
}
