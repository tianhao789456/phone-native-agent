package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeModelRequester(
    private val modelClient: NativeModelClient
) {
    fun requestWithEvents(
        apiKey: String,
        messages: JSONArray,
        phase: String,
        round: Int,
        enabledTools: Set<String>,
        memoryContext: JSONObject? = null
    ): NativeModelResponse {
        val startedAt = System.currentTimeMillis()
        val requestMessages = messagesWithMemoryContext(messages, memoryContext)
        AgentEventStore.record(
            "model_started",
            if (round > 0) "模型请求第 $round 轮工具结果" else "模型请求中",
            JSONObject()
                .put("phase", phase)
                .put("round", round)
                .put("messages", requestMessages.length())
                .put("saved_messages", messages.length())
                .put("memory_injected", memoryContext?.optBoolean("injected", false) == true)
                .put("memory_count", memoryContext?.optInt("memory_count", 0) ?: 0)
                .put("procedure_count", memoryContext?.optInt("procedure_count", 0) ?: 0)
                .put("experience_count", memoryContext?.optInt("experience_count", 0) ?: 0)
                .put("memory_chars", memoryContext?.optString("content", "")?.length ?: 0)
        )
        return try {
            val response = modelClient.request(apiKey, requestMessages, enabledTools)
            AgentEventStore.record(
                "model_finished",
                "模型返回，工具调用 ${response.toolCalls.length()} 个",
                JSONObject()
                    .put("phase", phase)
                    .put("round", round)
                    .put("duration_ms", System.currentTimeMillis() - startedAt)
                    .put("tool_calls", response.toolCalls.length())
                    .put("content_chars", response.content.length)
            )
            response
        } catch (exc: Exception) {
            AgentEventStore.record(
                "model_failed",
                "模型请求失败: ${exc.javaClass.simpleName}",
                JSONObject()
                    .put("phase", phase)
                    .put("round", round)
                    .put("duration_ms", System.currentTimeMillis() - startedAt)
                    .put("error_type", exc.javaClass.simpleName)
                    .put("error", exc.message ?: "")
            )
            throw exc
        }
    }

    private fun messagesWithMemoryContext(messages: JSONArray, memoryContext: JSONObject?): JSONArray {
        val content = memoryContext?.optString("content", "")?.trim().orEmpty()
        if (content.isBlank()) return messages
        val copy = JSONArray()
        val memoryItem = JSONObject()
            .put("role", "system")
            .put(
                "content",
                "[MOBILE_AGENT_RELEVANT_MEMORY_V2]\n" +
                    "This request-specific context is intentionally injected near the latest user message to keep the stable prompt prefix cache-friendly.\n" +
                    content
            )
        val latestUserIndex = findLatestUserMessageIndex(messages)
        for (index in 0 until messages.length()) {
            if (index == latestUserIndex) {
                copy.put(memoryItem)
            }
            copy.put(JSONObject(messages.getJSONObject(index).toString()))
        }
        if (latestUserIndex < 0) copy.put(memoryItem)
        return copy
    }

    private fun findLatestUserMessageIndex(messages: JSONArray): Int {
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index)?.optString("role") == "user") return index
        }
        return -1
    }
}
