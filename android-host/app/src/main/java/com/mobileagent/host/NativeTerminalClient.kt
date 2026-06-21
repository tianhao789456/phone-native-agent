package com.mobileagent.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NativeTerminalClient(
    private val context: Context,
    private val runtimeConfig: AgentRuntimeConfig,
    private val openApp: (String) -> JSONObject,
    private val terminalPowerMode: () -> Boolean,
    private val log: (String, String, String, JSONObject?) -> Unit
) {
    fun status(): JSONObject {
        val config = runtimeConfig.terminalConfigJson()
        if (!config.optBoolean("enabled")) {
            return JSONObject()
                .put("available", false)
                .put("configured", false)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("note", "终端工具后端未启用；普通聊天不依赖 Termux。")
        }
        val health = runCatching { getJson(terminalUrl("/health"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS) }
        val terminal = runCatching { getJson(terminalUrl("/terminal/status"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2) }
        terminal.onSuccess { terminalStatus ->
            val output = JSONObject()
                .put("available", terminalStatus.optBoolean("available", false))
                .put("configured", true)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("status", if (terminalStatus.optBoolean("available", false)) "ok" else "backend_unavailable")
                .put("backend", terminalStatus)
            health.onSuccess { output.put("health", it) }
            health.onFailure { output.put("health_error", "${it.javaClass.simpleName}: ${it.message}") }
            return output
        }
        health.onSuccess { status ->
            return JSONObject()
                .put("available", false)
                .put("configured", true)
                .put("mode", "optional_tool_backend")
                .put("config", config)
                .put("status", "partial_backend")
                .put("health", status)
                .put("terminal_status_error", terminal.exceptionOrNull()?.message ?: "")
        }
        val exc = terminal.exceptionOrNull() ?: health.exceptionOrNull()
        return JSONObject()
            .put("available", false)
            .put("configured", true)
            .put("mode", "optional_tool_backend")
            .put("config", config)
            .put("status", classifyConnectionError(exc?.message ?: "unknown"))
            .put("error", "${exc?.javaClass?.simpleName ?: "Error"}: ${exc?.message ?: "unknown"}")
    }

    fun diagnose(): JSONObject {
        log("info", "terminal", "diagnose terminal requested", null)
        val config = runtimeConfig.terminalConfigJson()
        val result = JSONObject()
            .put("configured", config.optBoolean("enabled"))
            .put("base_url", config.optString("base_url"))
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("run_command_permission", termuxRunCommandPermission())
            .put("repair_actions", JSONArray())

        if (!runtimeConfig.terminalEnabled()) {
            return result
                .put("ok", false)
                .put("status", "disabled")
                .put("summary", "终端工具后端未启用。")
                .put("repair_actions", JSONArray().put("启用终端接口：http://127.0.0.1:8787"))
        }
        if (!terminalPowerMode()) {
            result.put("repair_actions", result.getJSONArray("repair_actions").put("切换到 danger 或 developer 模式后再执行终端工具。"))
        }

        val terminalStatus = runCatching { getJson(terminalUrl("/terminal/status"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2) }
        terminalStatus.onSuccess { status ->
            return result
                .put("ok", status.optBoolean("ok", true))
                .put("status", if (status.optBoolean("available", false)) "ok" else "backend_unavailable")
                .put("summary", "终端后端可达。")
                .put("backend", status)
        }

        val health = runCatching { getJson(terminalUrl("/health"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2) }
        health.onSuccess { status ->
            return result
                .put("ok", false)
                .put("status", "partial_backend")
                .put("summary", "后端 /health 可达，但 /terminal/status 不可用，可能是后端版本旧或启动脚本未更新。")
                .put("health", status)
                .put("terminal_status_error", terminalStatus.exceptionOrNull()?.message ?: "")
                .put("repair_actions", result.getJSONArray("repair_actions").put("重启 Termux 后端，并确认已同步新版 http_server.py。"))
        }

        val error = terminalStatus.exceptionOrNull()
        val message = "${error?.javaClass?.simpleName ?: "Error"}: ${error?.message ?: "unknown"}"
        return result
            .put("ok", false)
            .put("status", classifyConnectionError(message))
            .put("summary", "终端后端不可达：$message")
            .put("error", message)
            .put(
                "repair_actions",
                result.getJSONArray("repair_actions")
                    .put("尝试 recover_terminal_backend 启动 Termux HTTP 后端。")
                    .put("如果恢复失败，打开 Termux 并运行 sh scripts/start-http-termux.sh。")
                    .put("确认 Termux 允许外部 RUN_COMMAND：~/.termux/termux.properties 中 allow-external-apps=true。")
            )
    }

    fun recover(arguments: JSONObject): JSONObject {
        log(
            "warn",
            "terminal",
            "recover terminal backend requested",
            JSONObject()
                .put("use_run_command", arguments.optBoolean("use_run_command", false))
                .put("open_termux", arguments.optBoolean("open_termux", true))
        )
        val useRunCommand = arguments.optBoolean("use_run_command", false)
        val openTermux = arguments.optBoolean("open_termux", true)
        val waitMs = arguments.optInt("wait_ms", 2500).coerceIn(500, 10000)
        val forceRestart = arguments.optBoolean("force_restart", true)
        runtimeConfig.setTerminalConfig(true, AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL)

        val before = diagnose()
        val actions = JSONArray().put("enabled_terminal_config")
        val errors = JSONArray()
        val runCommandPermission = termuxRunCommandPermission()
        if (before.optString("status") == "ok" && !forceRestart) {
            return JSONObject()
                .put("ok", true)
                .put("before", before)
                .put("after", before)
                .put("actions", actions.put("skipped_recovery_backend_already_ok"))
                .put("errors", errors)
                .put("run_command_permission", runCommandPermission)
                .put("note", "终端后端已经可达；未强制重启，避免重复通知。")
        }

        if (useRunCommand) {
            runCatching { startTermuxHttpWithRunCommand(forceRestart) }
                .onSuccess { component ->
                    actions.put(if (forceRestart) "sent_termux_run_command_restart" else "sent_termux_run_command")
                    actions.put("run_command_component=${component ?: "none"}")
                }
                .onFailure { error -> errors.put("${error.javaClass.simpleName}: ${error.message}") }
            if (!runCommandPermission.optBoolean("granted", false)) {
                errors.put("missing_permission: $TERMUX_RUN_COMMAND_PERMISSION")
            }
            if (!runCommandPermission.optBoolean("termux_installed", false)) {
                errors.put("termux_not_installed_or_not_visible")
            }
        }
        if (openTermux) {
            val opened = runCatching { openApp("com.termux") }
            if (opened.isSuccess) actions.put("opened_termux") else errors.put("open_termux_failed: ${opened.exceptionOrNull()?.message}")
        }

        Thread.sleep(waitMs.toLong())
        val after = diagnose()
        val note = if (useRunCommand) {
            "已请求 Termux RUN_COMMAND ${if (forceRestart) "重启" else "启动"}后端；如果被拒绝，检查 allow-external-apps=true。"
        } else {
            "RUN_COMMAND 未启用；本次只打开 Termux 并重新检测后端。"
        }
        return JSONObject()
            .put("ok", after.optString("status") == "ok")
            .put("before", before)
            .put("after", after)
            .put("actions", actions)
            .put("errors", errors)
            .put("run_command_permission", runCommandPermission)
            .put("note", note)
    }

    fun tools(): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        return getJson(terminalUrl("/tools"), AgentRuntimeConfig.TERMINAL_TIMEOUT_MS)
            .put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    fun chat(message: String): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        if (message.isBlank()) throw IllegalArgumentException("message is required")
        return postJsonNoAuth(
            terminalUrl("/chat"),
            JSONObject().put("message", message),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 40
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    fun run(command: String, cwd: String, timeout: Int): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        if (command.isBlank()) throw IllegalArgumentException("command is required")
        val safeTimeout = timeout.coerceIn(1, 600)
        val payload = JSONObject().put("command", command).put("timeout", safeTimeout)
        if (cwd.isNotBlank()) payload.put("cwd", cwd)
        return postJsonNoAuth(
            terminalUrl("/terminal/run"),
            payload,
            (safeTimeout * 1000 + AgentRuntimeConfig.TERMINAL_TIMEOUT_MS).coerceAtMost(610000)
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    fun script(arguments: JSONObject): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        val script = arguments.optString("script")
        if (script.isBlank()) throw IllegalArgumentException("script is required")
        val safeTimeout = arguments.optInt("timeout", 60).coerceIn(1, 600)
        val payload = JSONObject()
            .put("script", script)
            .put("timeout", safeTimeout)
            .put("interpreter", arguments.optString("interpreter", "sh"))
            .put("wait", arguments.optBoolean("wait", true))
            .put("max_output_chars", arguments.optInt("max_output_chars", 12000).coerceIn(1000, 50000))
            .put("name", arguments.optString("name", "script"))
        val cwd = arguments.optString("cwd", "")
        if (cwd.isNotBlank()) payload.put("cwd", cwd)
        val timeoutMs = if (payload.optBoolean("wait", true)) {
            (safeTimeout * 1000 + AgentRuntimeConfig.TERMINAL_TIMEOUT_MS).coerceAtMost(610000)
        } else {
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 2
        }
        return postJsonNoAuth(terminalUrl("/terminal/script"), payload, timeoutMs)
            .put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    fun taskStatus(taskId: String, maxOutputChars: Int): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        if (taskId.isBlank()) throw IllegalArgumentException("task_id is required")
        val result = getJson(
            terminalUrl("/terminal/tasks/${encodePathSegment(taskId)}"),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 4
        )
        return result
            .put("endpoint", runtimeConfig.terminalBaseUrl())
            .put("requested_max_output_chars", maxOutputChars.coerceIn(1000, 50000))
    }

    fun taskCancel(taskId: String): JSONObject {
        if (!runtimeConfig.terminalEnabled()) return disabledBackend()
        if (taskId.isBlank()) throw IllegalArgumentException("task_id is required")
        return postJsonNoAuth(
            terminalUrl("/terminal/tasks/${encodePathSegment(taskId)}/cancel"),
            JSONObject(),
            AgentRuntimeConfig.TERMINAL_TIMEOUT_MS * 4
        ).put("endpoint", runtimeConfig.terminalBaseUrl())
    }

    private fun termuxRunCommandPermission(): JSONObject {
        val termuxInstalled = runCatching {
            context.packageManager.getPackageInfo("com.termux", 0)
        }.isSuccess
        val permissionState = context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION)
        return JSONObject()
            .put("termux_installed", termuxInstalled)
            .put("permission", TERMUX_RUN_COMMAND_PERMISSION)
            .put("granted", permissionState == PackageManager.PERMISSION_GRANTED)
    }

    private fun startTermuxHttpWithRunCommand(forceRestart: Boolean): ComponentName? {
        val command = "cd /sdcard/Download/mobile-agent 2>/dev/null && sh scripts/start-http-termux.sh"
        val intent = Intent()
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        return context.startService(intent)
    }

    private fun disabledBackend(): JSONObject {
        return JSONObject()
            .put("available", false)
            .put("error", "终端工具后端未启用。")
            .put("config", runtimeConfig.terminalConfigJson())
    }

    private fun terminalUrl(path: String): String {
        val base = runtimeConfig.terminalBaseUrl().trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return base + suffix
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun classifyConnectionError(message: String): String {
        return when {
            message.contains("Connection refused", ignoreCase = true) -> "connection_refused"
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) -> "timeout"
            message.contains("No route", ignoreCase = true) -> "network_unreachable"
            else -> "offline"
        }
    }

    private fun getJson(url: String, timeoutMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (connection.responseCode !in 200..299) throw IllegalStateException(json.optString("error", "HTTP ${connection.responseCode}"))
        return json
    }

    private fun postJsonNoAuth(url: String, payload: JSONObject, timeoutMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer -> writer.write(payload.toString()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (connection.responseCode !in 200..299) throw IllegalStateException(json.optString("error", "HTTP ${connection.responseCode}"))
        return json
    }

    companion object {
        private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    }
}
