package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object NativeToolSchema {
    fun stringProp(default: String? = null): JSONObject {
        val value = JSONObject().put("type", "string")
        if (default != null) value.put("default", default)
        return value
    }

    fun intProp(default: Int? = null): JSONObject {
        val value = JSONObject().put("type", "integer")
        if (default != null) value.put("default", default)
        return value
    }

    fun boolProp(default: Boolean? = null): JSONObject {
        val value = JSONObject().put("type", "boolean")
        if (default != null) value.put("default", default)
        return value
    }

    fun objectStringMapProp(): JSONObject {
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", JSONObject().put("type", "string"))
    }

    fun arrayProp(): JSONObject {
        return JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))
    }

    fun arrayStringProp(): JSONObject {
        return JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
    }

    fun props(vararg pairs: Pair<String, JSONObject>): JSONObject {
        val value = JSONObject()
        pairs.forEach { value.put(it.first, it.second) }
        return value
    }

    fun req(vararg names: String): JSONArray {
        val value = JSONArray()
        names.forEach { value.put(it) }
        return value
    }
}
