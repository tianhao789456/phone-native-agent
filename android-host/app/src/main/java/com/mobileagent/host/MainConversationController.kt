package com.mobileagent.host

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class MainConversationController(
    private val prefs: SharedPreferences,
    private val getSessionId: () -> String?
) {
    data class SavedMessage(val role: String, val text: String, val detail: String? = null)

    private val savedMessages = mutableListOf<SavedMessage>()

    fun loadState(): List<SavedMessage> {
        savedMessages.clear()
        val raw = prefs.getString("messages", null) ?: return savedMessages

        var removedSecret = false
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = localizeRole(item.optString("role"))
                val text = localizeSavedText(item.optString("text"))
                val detail = item.optString("detail").ifBlank { null }

                if (MainLocalCommandParser.looksLikeApiKey(text)) {
                    removedSecret = true
                    continue
                }
                if (role.isNotBlank() && text.isNotBlank()) {
                    savedMessages.add(SavedMessage(role, text, detail))
                }
            }
        }

        if (removedSecret) {
            saveState()
        }
        return savedMessages.toList()
    }

    fun addMessage(role: String, text: String, detail: String? = null, persist: Boolean = true): SavedMessage {
        val safeText = if (MainLocalCommandParser.looksLikeApiKey(text)) "[API Key 已隐藏]" else text
        val item = SavedMessage(role, safeText, detail)
        savedMessages.add(item)
        while (savedMessages.size > 80) {
            savedMessages.removeAt(0)
        }
        if (persist) {
            saveState()
        }
        return item
    }

    fun clear() {
        savedMessages.clear()
        saveState()
    }

    fun messages(): List<SavedMessage> = savedMessages.toList()

    fun saveState() {
        val array = JSONArray()
        savedMessages.forEach { item ->
            val json = JSONObject().put("role", item.role).put("text", item.text)
            item.detail?.let { json.put("detail", it) }
            array.put(json)
        }
        prefs.edit()
            .putString("session_id", getSessionId())
            .putString("messages", array.toString())
            .apply()
    }

    private fun localizeRole(role: String): String {
        return when (role) {
            "system" -> "系统"
            "you" -> "我"
            "agent" -> "助手"
            "tools" -> "工具"
            "error" -> "错误"
            else -> role
        }
    }

    private fun localizeSavedText(text: String): String {
        return text
            .replace("ok ", "成功 ")
            .replace("fail ", "失败 ")
    }
}
