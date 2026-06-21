package com.mobileagent.host

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URLDecoder
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import kotlin.concurrent.thread

object HostBridgeServer {
    private const val PORT = 8790

    @Volatile
    private var started = false

    @Volatile
    private var appContext: Context? = null

    fun start(context: Context? = null) {
        context?.let { appContext = it.applicationContext }
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            AgentLogStore.record(appContext, "info", "bridge", "host bridge starting")
            thread(name = "mobile-agent-host-bridge", isDaemon = true) {
                runServer()
            }
        }
    }

    private fun runServer() {
        try {
            ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1")).use { server ->
                AgentLogStore.record(appContext, "info", "bridge", "host bridge listening", JSONObject().put("port", PORT))
                while (true) {
                    val socket = server.accept()
                    thread(name = "mobile-agent-host-client", isDaemon = true) {
                        socket.use {
                            try {
                                handle(it)
                            } catch (exc: Throwable) {
                                AgentLogStore.record(
                                    appContext,
                                    "warn",
                                    "bridge",
                                    "host bridge client failed",
                                    JSONObject()
                                        .put("error_type", exc.javaClass.simpleName)
                                        .put("error", exc.message ?: "")
                                )
                                // Clients may disconnect early when probes time out. Keep the bridge alive.
                            }
                        }
                    }
                }
            }
        } catch (exc: Throwable) {
            AgentLogStore.record(
                appContext,
                "error",
                "bridge",
                "host bridge stopped",
                JSONObject()
                    .put("error_type", exc.javaClass.simpleName)
                    .put("error", exc.message ?: "")
            )
            started = false
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        var contentLength = 0

        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
            }
        }

        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            reader.read(chars, 0, contentLength)
            String(chars)
        } else {
            ""
        }

        val response = route(method, path, body)
        writeJson(socket, response)
    }

    private fun route(method: String, path: String, body: String): JSONObject {
        return try {
            val cleanPath = path.substringBefore("?")
            val query = queryParams(path)
            when {
                method == "GET" && cleanPath == "/health" -> JSONObject()
                    .put("ok", true)
                    .put("host", "android-host")
                    .put("version", "0.1.0")

                method == "GET" && cleanPath == "/status" -> JSONObject()
                    .put("ok", true)
                    .put("host", "android-host")
                    .put("bridge", "127.0.0.1:$PORT")
                    .put("permission_mode", runtimeConfig()?.permissionMode() ?: AgentRuntimeConfig.MODE_SAFE)
                    .put("config", runtimeConfig()?.configJson() ?: JSONObject())
                    .put("terminal_runtime", appContext?.let { NativeAgentCore(it).terminalHealthForUi(autoRecover = false, force = false) } ?: JSONObject())
                    .put("accessibility", AccessibilityState.status(appContext))

                method == "GET" && cleanPath == "/config" -> JSONObject()
                    .put("ok", true)
                    .put("config", runtimeConfig()?.configJson() ?: JSONObject())

                method == "GET" && cleanPath == "/logs" -> JSONObject()
                    .put("ok", true)
                    .put("summary", AgentLogStore.summary(appContext))
                    .put("entries", AgentLogStore.recent(appContext, 100, "debug"))

                method == "GET" && cleanPath == "/events" -> AgentEventStore.recent(
                    query.optLong("after", 0L),
                    query.optInt("limit", 100)
                )

                method == "GET" && cleanPath == "/tools" -> JSONObject()
                    .put("ok", true)
                    .put("tools", hostTools())

                method == "GET" && cleanPath == "/native-tools" -> JSONObject()
                    .put("ok", true)
                    .put("tools", NativeToolRegistry.metadata())

                method == "POST" && cleanPath == "/native-tools/call" -> callNativeTool(JSONObject(body.ifBlank { "{}" }))

                method == "POST" && cleanPath == "/tools/call" -> callTool(JSONObject(body.ifBlank { "{}" }))

                else -> JSONObject()
                    .put("ok", false)
                    .put("error", "Not found: $method $cleanPath")
            }
        } catch (exc: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "${exc.javaClass.simpleName}: ${exc.message}")
        }
    }

    private fun queryParams(path: String): JSONObject {
        val result = JSONObject()
        val query = path.substringAfter("?", "")
        if (query.isBlank()) return result
        query.split("&").forEach { part ->
            val key = part.substringBefore("=")
            if (key.isBlank()) return@forEach
            val value = part.substringAfter("=", "")
            result.put(
                URLDecoder.decode(key, "UTF-8"),
                URLDecoder.decode(value, "UTF-8")
            )
        }
        return result
    }

    private fun callTool(request: JSONObject): JSONObject {
        val tool = request.optString("tool")
        val args = request.optJSONObject("arguments") ?: JSONObject()
        val actionsApproved = request.optBoolean("actions_approved", false)
        permissionGate(tool, actionsApproved)?.let { return it }
        return when (tool) {
            "workspace.info" -> workspace()?.info() ?: noHostContext()
            "workspace.list" -> workspace()?.list(
                args.optString("path", "."),
                args.optInt("max_entries", 100)
            ) ?: noHostContext()
            "workspace.read" -> workspace()?.read(
                args.optString("path"),
                args.optInt("max_bytes", 20000)
            ) ?: noHostContext()
            "workspace.write" -> workspace()?.write(
                args.optString("path"),
                args.optString("content"),
                args.optBoolean("overwrite", false)
            ) ?: noHostContext()
            "workspace.history" -> workspace()?.history(
                args.optString("path", ""),
                args.optInt("limit", 50)
            ) ?: noHostContext()
            "workspace.restore" -> workspace()?.restore(args.optString("change_id")) ?: noHostContext()
            "workspace.search" -> workspace()?.search(
                args.optString("query"),
                args.optString("path", "."),
                args.optInt("max_matches", 50),
                args.optInt("max_bytes_per_file", 200000)
            ) ?: noHostContext()
            "accessibility.status" -> AccessibilityState.status(appContext)
            "accessibility.observe" -> AccessibilityState.observe(args.optInt("max_nodes", 40))
            "accessibility.dump" -> AccessibilityState.dump(args.optInt("max_nodes", 80))
            "accessibility.find" -> AccessibilityState.find(
                args.optString("query"),
                args.optBoolean("contains", true),
                args.optInt("max_nodes", 20)
            )
            "accessibility.current_app" -> AccessibilityState.currentApp()
            "android.open_app" -> openApp(args.optString("package"))
            "accessibility.click_text" -> AccessibilityState.clickText(
                args.optString("text"),
                args.optBoolean("contains", true)
            )
            "accessibility.click_view_id" -> AccessibilityState.clickViewId(args.optString("view_id"))
            "accessibility.click_index" -> AccessibilityState.clickIndex(args.optInt("index", -1))
            "accessibility.input_text" -> AccessibilityState.inputText(args.optString("text"))
            "accessibility.back" -> AccessibilityState.back()
            "accessibility.home" -> AccessibilityState.home()
            "accessibility.scroll" -> AccessibilityState.scroll(
                args.optString("direction", "forward"),
                args.optString("text", ""),
                args.optString("view_id", "")
            )
            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown host tool: $tool")
        }
    }

    private fun callNativeTool(request: JSONObject): JSONObject {
        val context = appContext ?: return noHostContext()
        val tool = request.optString("tool")
        val args = request.optJSONObject("arguments") ?: JSONObject()
        val actionsApproved = request.optBoolean("actions_approved", false)
        return NativeAgentCore(context).executeNativeToolForDiagnostics(tool, args, actionsApproved)
    }

    private fun permissionGate(tool: String, actionsApproved: Boolean): JSONObject? {
        if (tool !in actionTools) return null
        val mode = runtimeConfig()?.permissionMode() ?: AgentRuntimeConfig.MODE_SAFE
        if (mode == AgentRuntimeConfig.MODE_DEVELOPER) return null
        if (mode == AgentRuntimeConfig.MODE_SAFE) {
            return JSONObject()
                .put("ok", false)
                .put("needs_permission", true)
                .put("permission_mode", mode)
                .put("error", "当前是安全模式，Host bridge 只允许观察。请在应用配置中切换到“确认操作”或“最高权限”。")
        }
        if (!actionsApproved) {
            return JSONObject()
                .put("ok", false)
                .put("needs_confirmation", true)
                .put("permission_mode", mode)
                .put("error", "Host bridge 动作工具需要调用方传入 actions_approved=true，且该确认必须来自用户当前请求。")
        }
        return null
    }

    private fun runtimeConfig(): AgentRuntimeConfig? {
        return appContext?.let { AgentRuntimeConfig(it) }
    }

    private fun workspace(): MobileWorkspace? {
        return appContext?.let { MobileWorkspace(it) }
    }

    private fun noHostContext(): JSONObject {
        return JSONObject().put("ok", false).put("error", "Host context is not available")
    }

    private fun hostTools(): JSONArray {
        return JSONArray()
            .put(
                JSONObject()
                    .put("name", "workspace.info")
                    .put("description", "Return the Android APP private workspace root and basic status.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.list")
                    .put("description", "List files under the Android APP private workspace.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.read")
                    .put("description", "Read a UTF-8 text file under the Android APP private workspace.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.write")
                    .put("description", "Write a UTF-8 text file under the Android APP private workspace and create a recoverable change record.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.history")
                    .put("description", "List workspace write/restore change records.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.restore")
                    .put("description", "Restore a workspace file to the state before a recorded change id.")
            )
            .put(
                JSONObject()
                    .put("name", "workspace.search")
                    .put("description", "Search UTF-8 text files under the Android APP private workspace.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.status")
                    .put("description", "Return AccessibilityService state.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.observe")
                    .put("description", "Return current foreground app and compact screen node list together.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.dump")
                    .put("description", "Return a compact current screen node list.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.find")
                    .put("description", "Find screen nodes by text, content description, view id, or class name.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.current_app")
                    .put("description", "Return the current foreground package and root node summary.")
            )
            .put(
                JSONObject()
                    .put("name", "android.open_app")
                    .put("description", "Open an installed app by package name.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.click_text")
                    .put("description", "Click the first node whose text or content description matches.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.click_view_id")
                    .put("description", "Click the first node with the exact Android view resource id.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.click_index")
                    .put("description", "Click a node by the index returned from accessibility.dump.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.input_text")
                    .put("description", "Set text on the focused or first editable node.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.back")
                    .put("description", "Perform Android Back.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.home")
                    .put("description", "Perform Android Home.")
            )
            .put(
                JSONObject()
                    .put("name", "accessibility.scroll")
                    .put("description", "Scroll the current page or a matched scrollable node.")
            )
    }

    private fun writeJson(socket: Socket, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun openApp(packageName: String): JSONObject {
        val context = appContext
            ?: return JSONObject().put("ok", false).put("error", "Host context is not available")
        if (packageName.isBlank() || packageName.contains("/") || packageName.contains(" ")) {
            return JSONObject().put("ok", false).put("error", "package must be an Android package name")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject()
                .put("ok", false)
                .put("package", packageName)
                .put("error", "No launch intent for package: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("action", "open_app")
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 500))
    }

    private val actionTools = setOf(
        "android.open_app",
        "accessibility.click_text",
        "accessibility.click_view_id",
        "accessibility.click_index",
        "accessibility.input_text",
        "accessibility.back",
        "accessibility.home",
        "accessibility.scroll"
    )
}
