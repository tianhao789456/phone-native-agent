package com.mobileagent.host

import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

class NativeSessionStore(private val prefs: SharedPreferences) {
    fun newSession(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit()
            .putString("current_session_id", id)
            .putString(sessionKey(id), JSONArray().toString())
            .apply()
        return id
    }

    fun currentSessionId(): String? {
        return prefs.getString("current_session_id", null)
    }

    fun setCurrentSession(sessionId: String) {
        prefs.edit().putString("current_session_id", sessionId).apply()
    }

    fun loadMessages(sessionId: String): JSONArray {
        return runCatching { JSONArray(prefs.getString(sessionKey(sessionId), "[]")) }.getOrDefault(JSONArray())
    }

    fun saveMessages(sessionId: String, messages: JSONArray) {
        prefs.edit()
            .putString(sessionKey(sessionId), messages.toString())
            .putString("current_session_id", sessionId)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    fun updatedAt(): Long {
        return prefs.getLong("updated_at", 0L)
    }

    private fun sessionKey(sessionId: String): String {
        return "session_$sessionId"
    }
}
