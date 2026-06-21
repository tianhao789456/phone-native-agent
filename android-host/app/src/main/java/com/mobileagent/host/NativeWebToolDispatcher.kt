package com.mobileagent.host

import org.json.JSONObject

class NativeWebToolDispatcher(
    private val search: (String, Int) -> JSONObject,
    private val extract: (JSONObject, Boolean) -> JSONObject,
    private val get: (JSONObject) -> JSONObject,
    private val post: (JSONObject) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject, actionsApproved: Boolean): JSONObject {
        return when (name) {
            "web_search" -> search(
                arguments.optString("query"),
                arguments.optInt("max_results", 5)
            )
            "web_extract", "page_extract" -> extract(arguments, actionsApproved)
            "http_get" -> get(arguments)
            "http_post" -> post(arguments)
            else -> throw IllegalArgumentException("Web dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "web_search",
            "web_extract",
            "page_extract",
            "http_get",
            "http_post"
        )
    }
}
