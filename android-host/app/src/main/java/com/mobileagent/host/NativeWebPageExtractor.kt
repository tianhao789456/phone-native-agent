package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

object NativeWebPageExtractor {
    fun fromHtml(
        requestedUrl: String,
        finalUrl: String,
        html: String,
        mode: String,
        maxBytes: Int
    ): JSONObject {
        val title = extractHtmlTitle(html)
        val links = extractHtmlLinks(finalUrl, html)
        val text = compactText(stripHtmlToText(html), maxBytes)
        val markdown = compactText(markdownFromPage(title, text, links), maxBytes)
        val content = contentForMode(mode, title, text, markdown, links)
        return JSONObject()
            .put("url", requestedUrl)
            .put("final_url", finalUrl)
            .put("title", title)
            .put("text", text)
            .put("markdown", markdown)
            .put("links", links)
            .put("content", content)
            .put("mode", mode)
            .put("truncated", markdown.toByteArray(Charsets.UTF_8).size > maxBytes || text.toByteArray(Charsets.UTF_8).size > maxBytes)
    }

    fun contentForMode(mode: String, title: String, text: String, markdown: String, links: JSONArray): String {
        return when (mode) {
            "text" -> text
            "json" -> JSONObject()
                .put("title", title)
                .put("text", text)
                .put("markdown", markdown)
                .put("links", links)
                .toString()
            else -> markdown
        }
    }

    fun compactText(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        return bytes.copyOf(maxBytes).toString(Charsets.UTF_8)
    }

    fun jinaReaderUrl(url: String): String {
        return "https://r.jina.ai/$url"
    }

    fun cleanHtml(value: String): String {
        return htmlDecode(value.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun htmlDecode(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    private fun extractHtmlTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return cleanHtml(match?.groupValues?.getOrNull(1) ?: "").take(240)
    }

    private fun extractHtmlLinks(baseUrl: String, html: String): JSONArray {
        val links = JSONArray()
        val seen = mutableSetOf<String>()
        val regex = Regex(
            "<a\\s+[^>]*href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in regex.findAll(html).take(80)) {
            val rawHref = htmlDecode(match.groupValues[2]).trim()
            if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", ignoreCase = true)) continue
            val resolved = runCatching { URL(URL(baseUrl), rawHref).toString() }.getOrDefault(rawHref)
            if (!seen.add(resolved)) continue
            val text = cleanHtml(match.groupValues[3]).take(180)
            links.put(JSONObject().put("text", text).put("url", resolved))
        }
        return links
    }

    private fun stripHtmlToText(html: String): String {
        return html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?i)</(p|div|section|article|header|footer|main|li|h[1-6]|tr|table)>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let { htmlDecode(it) }
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun markdownFromPage(title: String, text: String, links: JSONArray): String {
        val builder = StringBuilder()
        if (title.isNotBlank()) {
            builder.append("# ").append(title).append("\n\n")
        }
        builder.append(text.trim())
        if (links.length() > 0) {
            builder.append("\n\n## Links\n")
            for (index in 0 until links.length().coerceAtMost(20)) {
                val link = links.optJSONObject(index) ?: continue
                val label = link.optString("text").ifBlank { link.optString("url") }.replace("\n", " ").take(120)
                builder.append("- [").append(label.replace("[", "").replace("]", "")).append("](")
                    .append(link.optString("url")).append(")\n")
            }
        }
        return builder.toString().trim()
    }
}
