package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

enum class NativeToolAccess {
    READ_ONLY,
    SCREEN_ACTION,
    TERMINAL_DELEGATION
}

enum class NativeToolRisk {
    LOW,
    MEDIUM,
    HIGH
}

data class NativeToolDescriptor(
    val name: String,
    val description: String,
    val category: String,
    val access: NativeToolAccess,
    val risk: NativeToolRisk,
    val properties: JSONObject = JSONObject(),
    val required: JSONArray = JSONArray(),
    val autoRecover: Boolean = false,
    val debugHint: String = ""
) {
    fun schema(): JSONObject {
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                            .put("additionalProperties", false)
                    )
            )
    }

    fun metadata(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("access", access.name.lowercase())
            .put("risk", risk.name.lowercase())
            .put("auto_recover", autoRecover)
            .put("debug_hint", debugHint)
            .put("schema", schema().optJSONObject("function")?.optJSONObject("parameters") ?: JSONObject())
    }

    fun indexMetadata(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("access", access.name.lowercase())
            .put("risk", risk.name.lowercase())
            .put("auto_recover", autoRecover)
    }
}
