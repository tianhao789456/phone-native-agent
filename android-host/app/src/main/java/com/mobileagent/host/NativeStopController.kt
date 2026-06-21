package com.mobileagent.host

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NativeStopController(
    private val log: (String, String, String, JSONObject) -> Unit
) {
    private val stopRequests = ConcurrentHashMap<String, AtomicBoolean>()

    fun request(sessionId: String): JSONObject {
        stopRequests.getOrPut(sessionId) { AtomicBoolean(false) }.set(true)
        AgentEventStore.record(
            "chat_stop_requested",
            "用户要求停止本轮任务",
            JSONObject()
                .put("session_id", sessionId)
                .put("time", System.currentTimeMillis())
        )
        log(
            "info",
            "core",
            "chat stop requested",
            JSONObject()
                .put("session_id", sessionId)
                .put("source", "ui")
        )
        return JSONObject().put("ok", true).put("session_id", sessionId)
    }

    fun isRequested(sessionId: String): Boolean {
        return stopRequests[sessionId]?.get() == true
    }

    fun clear(sessionId: String) {
        stopRequests[sessionId]?.set(false)
    }

    fun block(sessionId: String, phase: String, modelResponse: NativeModelResponse? = null): JSONObject {
        val lastText = modelResponse?.content?.ifBlank { "" } ?: ""
        return JSONObject()
            .put("ok", false)
            .put("type", "user_requested_stop")
            .put("session_id", sessionId)
            .put("phase", phase)
            .put("summary", "用户要求停止本次执行，已在 $phase 阶段中断。")
            .put("last_model_output_preview", lastText.take(240))
    }
}
