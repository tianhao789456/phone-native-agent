package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

object AgentEventStore {
    private const val MAX_EVENTS = 300
    private val events = ArrayDeque<JSONObject>()
    private var nextSeq = 1L

    @Synchronized
    fun record(type: String, message: String, details: JSONObject? = null): JSONObject {
        val event = JSONObject()
            .put("seq", nextSeq++)
            .put("type", type)
            .put("message", message)
            .put("created_at_ms", System.currentTimeMillis())
            .put("details", details ?: JSONObject())
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.removeFirst()
        return JSONObject(event.toString())
    }

    @Synchronized
    fun recent(afterSeq: Long = 0L, limit: Int = 100): JSONObject {
        val safeLimit = limit.coerceIn(1, 200)
        val items = JSONArray()
        for (event in events) {
            if (event.optLong("seq", 0L) > afterSeq) {
                items.put(JSONObject(event.toString()))
                if (items.length() >= safeLimit) break
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("after_seq", afterSeq)
            .put("last_seq", if (events.isEmpty()) 0L else events.last.optLong("seq", 0L))
            .put("events", items)
    }
}
