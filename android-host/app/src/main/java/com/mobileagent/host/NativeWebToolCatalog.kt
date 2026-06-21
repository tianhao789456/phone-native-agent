package com.mobileagent.host

import org.json.JSONObject

object NativeWebToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "web_search",
                    description = "Search the public web directly from the Android native core without Termux. Use for current public information and return source titles, URLs, and snippets.",
                    category = "web",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(), "max_results" to NativeToolSchema.intProp(5)),
                    required = NativeToolSchema.req("query")
                ),
        NativeToolDescriptor(
                    name = "web_extract",
                    description = "Fetch one public HTTP/HTTPS page and extract LLM-friendly structured content. Uses native direct extraction first, Jina Reader fallback when allowed, and optional Termux fallback in developer/danger mode.",
                    category = "web",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "url" to NativeToolSchema.stringProp(),
                        "mode" to NativeToolSchema.stringProp("markdown"),
                        "source" to NativeToolSchema.stringProp("auto"),
                        "max_bytes" to NativeToolSchema.intProp(200000),
                        "use_jina" to NativeToolSchema.boolProp(true),
                        "use_termux" to NativeToolSchema.boolProp(false)
                    ),
                    required = NativeToolSchema.req("url")
                ),
        NativeToolDescriptor(
                    name = "http_get",
                    description = "Fetch a public HTTP/HTTPS URL directly from the Android native core without Termux. Returns status, headers, body preview, byte count, and truncation state.",
                    category = "web",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "url" to NativeToolSchema.stringProp(),
                        "headers" to NativeToolSchema.objectStringMapProp(),
                        "max_bytes" to NativeToolSchema.intProp(200000)
                    ),
                    required = NativeToolSchema.req("url")
                ),
        NativeToolDescriptor(
                    name = "http_post",
                    description = "POST text or JSON to a HTTP/HTTPS endpoint directly from the Android native core without Termux. Use only for user-requested API calls.",
                    category = "web",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "url" to NativeToolSchema.stringProp(),
                        "body" to NativeToolSchema.stringProp(""),
                        "content_type" to NativeToolSchema.stringProp("application/json; charset=utf-8"),
                        "headers" to NativeToolSchema.objectStringMapProp(),
                        "max_bytes" to NativeToolSchema.intProp(200000)
                    ),
                    required = NativeToolSchema.req("url")
                )
    )
}
