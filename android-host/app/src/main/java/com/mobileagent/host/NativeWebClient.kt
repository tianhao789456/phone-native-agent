package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class NativeWebClient(
    private val runtimeConfig: AgentRuntimeConfig,
    private val terminalScript: (JSONObject) -> JSONObject,
    private val log: (String, String, String, JSONObject?) -> Unit
) {
    fun search(query: String, maxResults: Int): JSONObject {
        return runCatching {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) {
                throw IllegalArgumentException("query is required")
            }
            val safeMax = maxResults.coerceIn(1, 6)
            val url = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(cleanQuery, "UTF-8")
            val html = getText(url, 12000).take(300000)
            val results = JSONArray()
            val resultRegex = Regex(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val matches = resultRegex.findAll(html).take(safeMax).toList()
            for (match in matches) {
                val rawUrl = NativeWebPageExtractor.htmlDecode(match.groupValues[1])
                val title = NativeWebPageExtractor.cleanHtml(match.groupValues[2]).take(240)
                val finalUrl = extractDuckDuckGoUrl(rawUrl).take(600)
                val start = match.range.last.coerceAtMost(html.length)
                val nextChunk = html.substring(start, (start + 1600).coerceAtMost(html.length))
                val snippet = Regex(
                    "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>|<div[^>]+class=\"result__snippet\"[^>]*>(.*?)</div>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(nextChunk)?.let {
                    NativeWebPageExtractor.cleanHtml(it.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() } ?: "").take(500)
                } ?: ""
                if (title.isNotBlank() && finalUrl.isNotBlank()) {
                    results.put(
                        JSONObject()
                            .put("title", title)
                            .put("url", finalUrl)
                            .put("snippet", snippet)
                    )
                }
            }
            JSONObject()
                .put("ok", results.length() > 0)
                .put("query", cleanQuery)
                .put("provider", "duckduckgo_html")
                .put("results", results)
                .put("error", if (results.length() == 0) "No search results parsed." else "")
        }.getOrElse { error ->
            log(
                "error",
                "web",
                "web search failed",
                JSONObject()
                    .put("query", query.take(200))
                    .put("error_type", error.javaClass.simpleName)
                    .put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("query", query)
                .put("provider", "duckduckgo_html")
                .put("results", JSONArray())
                .put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun extract(arguments: JSONObject, actionsApproved: Boolean): JSONObject {
        val url = arguments.optString("url").trim()
        if (url.isBlank()) throw IllegalArgumentException("url is required")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val mode = arguments.optString("mode", "markdown").lowercase().ifBlank { "markdown" }
        val source = arguments.optString("source", "auto").lowercase().ifBlank { "auto" }
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(4096, 1_000_000)
        val attempts = JSONArray()
        var lastResult: JSONObject? = null
        val candidates = when (source) {
            "auto" -> buildList {
                add("direct")
                if (arguments.optBoolean("use_jina", true)) add("jina")
                if (arguments.optBoolean("use_termux", false)) add("termux")
            }
            "direct", "jina", "termux" -> listOf(source)
            else -> throw IllegalArgumentException("source must be one of auto, direct, jina, termux")
        }
        for (candidate in candidates) {
            val attemptedAt = System.currentTimeMillis()
            val extracted = runCatching {
                when (candidate) {
                    "direct" -> directPageExtract(url, mode, maxBytes)
                    "jina" -> jinaPageExtract(url, mode, maxBytes)
                    "termux" -> termuxPageExtract(url, mode, maxBytes, actionsApproved)
                    else -> null
                }
            }
            if (extracted.isSuccess) {
                val result = extracted.getOrNull()
                if (result != null) {
                    lastResult = result
                    attempts.put(
                        JSONObject()
                            .put("source", candidate)
                            .put("ok", result.optBoolean("ok", false))
                            .put("duration_ms", System.currentTimeMillis() - attemptedAt)
                            .put("content_chars", result.optString("content").length)
                            .put("error", result.optString("error", ""))
                    )
                    val content = result.optString("content")
                    if (result.optBoolean("ok", false) && (content.trim().length >= 80 || result.optString("title").isNotBlank())) {
                        return result.put("attempts", attempts)
                    }
                }
            } else {
                val error = extracted.exceptionOrNull()
                attempts.put(
                    JSONObject()
                        .put("source", candidate)
                        .put("ok", false)
                        .put("duration_ms", System.currentTimeMillis() - attemptedAt)
                        .put("error", "${error?.javaClass?.simpleName}: ${error?.message}")
                )
            }
        }
        lastResult?.let {
            return JSONObject(it.toString())
                .put("attempts", attempts)
                .put("error", it.optString("error").ifBlank { "No extraction source returned usable content." })
        }
        return JSONObject()
            .put("ok", false)
            .put("url", url)
            .put("source", source)
            .put("mode", mode)
            .put("attempts", attempts)
            .put("error", "No extraction source returned usable content.")
    }

    private fun directPageExtract(url: String, mode: String, maxBytes: Int): JSONObject {
        val startedAt = System.currentTimeMillis()
        val fetched = webFetchText(url, maxBytes)
        val parsed = NativeWebPageExtractor.fromHtml(url, fetched.optString("final_url", url), fetched.optString("body"), mode, maxBytes)
        return parsed
            .put("ok", parsed.optString("content").isNotBlank())
            .put("source", "direct")
            .put("url", url)
            .put("final_url", fetched.optString("final_url", url))
            .put("status", fetched.optInt("status", 0))
            .put("bytes", fetched.optInt("bytes", 0))
            .put("truncated", fetched.optBoolean("truncated", false) || parsed.optBoolean("truncated", false))
            .put("duration_ms", System.currentTimeMillis() - startedAt)
    }

    private fun jinaPageExtract(url: String, mode: String, maxBytes: Int): JSONObject {
        val startedAt = System.currentTimeMillis()
        val readerUrl = NativeWebPageExtractor.jinaReaderUrl(url)
        val fetched = webFetchText(readerUrl, maxBytes)
        val markdown = fetched.optString("body").trim()
        val title = markdown.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trimStart('#')
            ?.trim()
            ?.take(240)
            ?: ""
        val content = NativeWebPageExtractor.contentForMode(mode, title, markdown, markdown, JSONArray())
        return JSONObject()
            .put("ok", markdown.isNotBlank())
            .put("source", "jina")
            .put("url", url)
            .put("reader_url", readerUrl)
            .put("final_url", fetched.optString("final_url", readerUrl))
            .put("status", fetched.optInt("status", 0))
            .put("title", title)
            .put("text", NativeWebPageExtractor.compactText(markdown, maxBytes))
            .put("markdown", NativeWebPageExtractor.compactText(markdown, maxBytes))
            .put("links", JSONArray())
            .put("content", content)
            .put("bytes", fetched.optInt("bytes", 0))
            .put("truncated", fetched.optBoolean("truncated", false) || markdown.toByteArray(Charsets.UTF_8).size > maxBytes)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
    }

    private fun termuxPageExtract(url: String, mode: String, maxBytes: Int, actionsApproved: Boolean): JSONObject {
        val permissionMode = runtimeConfig.permissionMode()
        val terminalAllowed = permissionMode == "developer" || (permissionMode == "danger" && actionsApproved)
        if (!terminalAllowed) {
            return JSONObject()
                .put("ok", false)
                .put("source", "termux")
                .put("url", url)
                .put("error", "Termux extraction requires developer mode or danger mode with approved actions.")
        }
        val script = """
import html
import json
import re
import sys
import urllib.request
from html.parser import HTMLParser

url = ${JSONObject.quote(url)}
limit = $maxBytes

class Extractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip = 0
        self.title_mode = False
        self.title = ""
        self.parts = []
        self.links = []
        self.current_href = None
        self.current_link = []
    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in ("script", "style", "noscript"):
            self.skip += 1
        if tag == "title":
            self.title_mode = True
        if tag in ("p", "div", "br", "li", "section", "article", "h1", "h2", "h3", "tr"):
            self.parts.append("\n")
        if tag == "a":
            attrs = dict(attrs)
            self.current_href = attrs.get("href")
            self.current_link = []
    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in ("script", "style", "noscript") and self.skip:
            self.skip -= 1
        if tag == "title":
            self.title_mode = False
        if tag == "a" and self.current_href:
            text = " ".join("".join(self.current_link).split())
            if text:
                self.links.append({"text": text[:160], "url": self.current_href})
            self.current_href = None
            self.current_link = []
    def handle_data(self, data):
        if self.skip:
            return
        if self.title_mode:
            self.title += data
        self.parts.append(data)
        if self.current_href:
            self.current_link.append(data)

req = urllib.request.Request(url, headers={"User-Agent": "MobileAgent/0.1 Termux"})
with urllib.request.urlopen(req, timeout=25) as response:
    raw = response.read(limit + 1)
    final_url = response.geturl()
    status = response.status
truncated = len(raw) > limit
body = raw[:limit].decode("utf-8", "replace")
parser = Extractor()
parser.feed(body)
text = html.unescape(" ".join("\n".join(parser.parts).split()))
title = html.unescape(" ".join(parser.title.split()))[:240]
markdown = ("# " + title + "\n\n" if title else "") + text
print(json.dumps({
    "ok": bool(text or title),
    "source": "termux",
    "url": url,
    "final_url": final_url,
    "status": status,
    "title": title,
    "text": text[:limit],
    "markdown": markdown[:limit],
    "links": parser.links[:40],
    "bytes": len(raw[:limit]),
    "truncated": truncated
}, ensure_ascii=False))
""".trimIndent()
        val terminal = terminalScript(
            JSONObject()
                .put("script", script)
                .put("interpreter", "python")
                .put("timeout", 45)
                .put("wait", true)
                .put("max_output_chars", maxBytes.coerceIn(12000, 50000))
                .put("name", "web-extract")
        )
        val stdout = terminal.optString("stdout")
            .ifBlank { terminal.optJSONObject("output")?.optJSONObject("stdout")?.optString("text") ?: "" }
            .ifBlank { terminal.optJSONObject("result")?.optString("stdout") ?: "" }
            .ifBlank {
                terminal.optJSONObject("result")
                    ?.optJSONObject("output")
                    ?.optJSONObject("stdout")
                    ?.optString("text")
                    ?: ""
            }
        val stderr = terminal.optString("stderr")
            .ifBlank { terminal.optJSONObject("output")?.optJSONObject("stderr")?.optString("text") ?: "" }
            .ifBlank { terminal.optJSONObject("result")?.optString("stderr") ?: "" }
            .ifBlank {
                terminal.optJSONObject("result")
                    ?.optJSONObject("output")
                    ?.optJSONObject("stderr")
                    ?.optString("text")
                    ?: ""
            }
        if (stdout.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("source", "termux")
                .put("url", url)
                .put("terminal", terminal)
                .put("stderr", stderr.take(2000))
                .put("error", if (stderr.isBlank()) "Termux script returned no stdout." else stderr.take(500))
        }
        val parsed = JSONObject(stdout.trim().lineSequence().last())
        val links = parsed.optJSONArray("links") ?: JSONArray()
        val content = NativeWebPageExtractor.contentForMode(mode, parsed.optString("title"), parsed.optString("text"), parsed.optString("markdown"), links)
        return parsed
            .put("content", content)
            .put("mode", mode)
            .put("terminal_task_id", terminal.optString("task_id", ""))
    }

    private fun webFetchText(url: String, maxBytes: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 MobileAgent/0.1")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyBytes = readBytesLimited(stream, maxBytes + 1)
        val truncated = bodyBytes.size > maxBytes
        val visibleBytes = if (truncated) bodyBytes.copyOf(maxBytes) else bodyBytes
        val body = visibleBytes.toString(Charsets.UTF_8)
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${body.take(300)}")
        }
        return JSONObject()
            .put("url", url)
            .put("final_url", connection.url.toString())
            .put("status", code)
            .put("bytes", visibleBytes.size)
            .put("truncated", truncated)
            .put("body", body)
    }

    fun get(arguments: JSONObject): JSONObject {
        val url = arguments.optString("url").trim()
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(1024, 500000)
        val headers = arguments.optJSONObject("headers") ?: JSONObject()
        return httpRequest("GET", url, null, "", headers, maxBytes)
    }

    fun post(arguments: JSONObject): JSONObject {
        val url = arguments.optString("url").trim()
        val body = arguments.optString("body", "")
        val contentType = arguments.optString("content_type", "application/json; charset=utf-8")
        val maxBytes = arguments.optInt("max_bytes", 200000).coerceIn(1024, 500000)
        val headers = arguments.optJSONObject("headers") ?: JSONObject()
        return httpRequest("POST", url, body, contentType, headers, maxBytes)
    }

    private fun httpRequest(
        method: String,
        url: String,
        body: String?,
        contentType: String,
        headers: JSONObject,
        maxBytes: Int
    ): JSONObject {
        if (url.isBlank()) throw IllegalArgumentException("url is required")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val startedAt = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "MobileAgent/0.1 Android")
        val headerNames = headers.keys()
        while (headerNames.hasNext()) {
            val name = headerNames.next()
            if (name.equals("Authorization", ignoreCase = true)) {
                connection.setRequestProperty(name, headers.optString(name))
            } else if (name.isNotBlank()) {
                connection.setRequestProperty(name, headers.optString(name))
            }
        }
        if (method == "POST") {
            connection.doOutput = true
            if (contentType.isNotBlank()) connection.setRequestProperty("Content-Type", contentType)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body ?: "")
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyBytes = readBytesLimited(stream, maxBytes + 1)
        val truncated = bodyBytes.size > maxBytes
        val visibleBytes = if (truncated) bodyBytes.copyOf(maxBytes) else bodyBytes
        val responseBody = visibleBytes.toString(Charsets.UTF_8)
        return JSONObject()
            .put("ok", code in 200..299)
            .put("method", method)
            .put("url", url)
            .put("status", code)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("headers", responseHeaders(connection))
            .put("bytes", visibleBytes.size)
            .put("truncated", truncated)
            .put("body", responseBody)
    }

    private fun readBytesLimited(stream: java.io.InputStream?, limit: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < limit) {
                val read = input.read(buffer, 0, (limit - total).coerceAtMost(buffer.size))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            return output.toByteArray()
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): JSONObject {
        val result = JSONObject()
        connection.headerFields.forEach { (name, values) ->
            if (name != null && values != null) {
                result.put(name, values.joinToString(", "))
            }
        }
        return result
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 Mobile Agent")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}: ${body.take(300)}")
        }
        return body
    }

    private fun extractDuckDuckGoUrl(rawUrl: String): String {
        val marker = "uddg="
        val index = rawUrl.indexOf(marker)
        if (index < 0) return rawUrl
        val encoded = rawUrl.substring(index + marker.length).substringBefore("&")
        return URLDecoder.decode(encoded, "UTF-8")
    }

}
