package com.mobileagent.host

import org.json.JSONObject

data class NativeToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
) {
    companion object {
        fun fromJson(call: JSONObject): NativeToolCall {
            val function = call.optJSONObject("function") ?: JSONObject()
            return NativeToolCall(
                id = call.optString("id"),
                name = function.optString("name"),
                arguments = parseArguments(function.optString("arguments"))
            )
        }

        private fun parseArguments(raw: String): JSONObject {
            if (raw.isBlank()) return JSONObject()
            return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        }
    }
}
