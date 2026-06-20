package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val coreBaseUrl = "http://127.0.0.1:8787"
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var detailStatusText: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var composer: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var nativeCore: NativeAgentCore
    private var sessionId: String? = null
    private var lastToolTrace: JSONArray? = null
    private var lastTaskLoop: JSONObject? = null
    private var statusRefreshActive = false
    private var sending = false
    private var sendingStartedAt = 0L
    private var lastEventSeq = 0L
    private val pendingMessages = ArrayDeque<String>()
    private val sendingStatusRunnable = object : Runnable {
        override fun run() {
            if (!sending) return
            val seconds = ((System.currentTimeMillis() - sendingStartedAt) / 1000).coerceAtLeast(0)
            statusText.text = "核心处理中... ${seconds}s"
            refreshLiveEvents(seconds)
            ui.postDelayed(this, 1000)
        }
    }
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            if (!statusRefreshActive) return
            refreshStatus()
            ui.postDelayed(this, 3000)
        }
    }
    private data class SavedMessage(val role: String, val text: String, val detail: String? = null)
    private val savedMessages = mutableListOf<SavedMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        HostBridgeServer.start(this)
        AgentKeepAliveService.start(this)
        nativeCore = NativeAgentCore(this)
        AgentLogStore.record(this, "info", "ui", "main activity created")
        loadState()
        buildUi()
        refreshComposerState()
        if (savedMessages.isEmpty()) {
            addMessage("系统", "Mobile Agent 已就绪")
        } else {
            savedMessages.forEach { item -> addMessage(item.role, item.text, persist = false, detail = item.detail) }
        }
        startStatusRefresh()
    }

    override fun onResume() {
        super.onResume()
        startStatusRefresh()
    }

    override fun onPause() {
        statusRefreshActive = false
        ui.removeCallbacks(statusRefreshRunnable)
        super.onPause()
    }

    private fun startStatusRefresh() {
        statusRefreshActive = true
        ui.removeCallbacks(statusRefreshRunnable)
        statusRefreshRunnable.run()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(247, 248, 250))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(28, statusBarHeight() + 18, 28, 18)
        header.setBackgroundColor(Color.rgb(21, 25, 31))

        val title = TextView(this)
        title.text = "手机 Agent"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.gravity = Gravity.CENTER_VERTICAL

        statusText = TextView(this)
        statusText.text = "正在检查中..."
        statusText.setTextColor(Color.rgb(184, 194, 204))
        statusText.textSize = 13f
        statusText.setPadding(0, 6, 0, 0)

        detailStatusText = TextView(this)
        detailStatusText.text = "模型 - | 连接 - | 缓存 - | 权限 -"
        detailStatusText.setTextColor(Color.rgb(148, 160, 172))
        detailStatusText.textSize = 12f
        detailStatusText.setPadding(0, 4, 0, 0)

        val panelButton = Button(this)
        panelButton.text = "操作面板"
        panelButton.setOnClickListener { showActionPanel() }
        stopButton = Button(this)
        stopButton.text = "停止"
        stopButton.isEnabled = false
        stopButton.setOnClickListener { requestStopCurrentTask() }

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.gravity = Gravity.CENTER_VERTICAL
        actionRow.setPadding(0, 10, 0, 0)
        actionRow.addView(
            panelButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionRow.addView(
            stopButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        header.addView(title)
        header.addView(statusText)
        header.addView(detailStatusText)
        header.addView(actionRow)
        root.addView(header)

        scrollView = ScrollView(this)
        scrollView.isFillViewport = true
        messages = LinearLayout(this)
        messages.orientation = LinearLayout.VERTICAL
        messages.setPadding(20, 18, 20, 18)
        scrollView.addView(messages)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        composer = LinearLayout(this)
        composer.orientation = LinearLayout.HORIZONTAL
        composer.gravity = Gravity.CENTER_VERTICAL
        composer.setPadding(16, 12, 16, 16)
        composer.setBackgroundColor(Color.WHITE)

        input = EditText(this)
        input.hint = "输入消息"
        input.minLines = 1
        input.maxLines = 3
        input.setSingleLine(false)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setBackgroundColor(Color.rgb(241, 243, 245))
        input.setPadding(18, 10, 18, 10)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        sendButton = Button(this)
        sendButton.text = "发"
        sendButton.setOnClickListener { sendMessage() }

        composer.addView(input, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        composer.addView(sendButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(composer)

        setContentView(root)
        keepComposerAboveKeyboard(root)
        refreshComposerState()
    }

    private fun refreshStatus() {
        if (sending) return
        thread(name = "mobile-agent-status") {
            val core = runCatching { nativeCore.status(sessionId) }
            val host = AccessibilityState.status(this@MainActivity)
            ui.post {
                val coreLabel = if (core.isSuccess && core.getOrNull()?.optBoolean("ok") == true) {
                    "核心在线"
                } else {
                    "核心离线"
                }
                val accessibilityLabel = when {
                    host.optBoolean("connected") -> "无障碍已连接"
                    host.optBoolean("enabled") -> "无障碍待连接"
                    else -> "无障碍未连接"
                }
                statusText.text = "$coreLabel | 基座桥接在线 | $accessibilityLabel"
                statusText.text = "${statusText.text} | ${terminalHeaderLabel(core.getOrNull())}"
                statusText.text = "${statusText.text} | ${mcpHeaderLabel(core.getOrNull())}"
                detailStatusText.text = summarizeStatus(core.getOrNull())
            }
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        hideKeyboard()

        normalizeLocalCommand(text)?.let { command ->
            runLocalCommand(command)
            return
        }

        if (sending) {
            queueMessage(text)
            return
        }

        sendTextWithConfirmation(text)
    }

    private fun queueMessage(text: String) {
        pendingMessages.addLast(text)
        addMessage("系统", "任务执行中，已为你把消息加入队列，当前还有 ${pendingMessages.size} 条")
        refreshComposerState()
    }

    private fun processQueuedMessageIfAny() {
        if (pendingMessages.isEmpty()) return
        val next = pendingMessages.removeFirst()
        sendTextWithConfirmation(next)
    }

    private fun sendTextWithConfirmation(text: String) {
        if (nativeCore.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER) {
            sendConfirmedMessage(text, actionsApproved = true)
            return
        }

        if (needsActionConfirmation(text)) {
            AlertDialog.Builder(this)
                .setTitle("确认执行手机操作")
                .setMessage("下一条指令可能触发自动化或其他 APP 操作。请确认是否继续。\n$text")
                .setNegativeButton("取消", null)
                .setPositiveButton("发送") { _, _ -> sendConfirmedMessage(text, actionsApproved = true) }
                .show()
            return
        }

        sendConfirmedMessage(text, actionsApproved = false)
    }

    private fun requestStopCurrentTask() {
        if (!sending) {
            addMessage("系统", if (pendingMessages.isEmpty()) "当前未在运行中，暂无可停止的执行。" else "当前仍有 ${pendingMessages.size} 条待发送消息。")
            return
        }
        runCatching { nativeCore.requestStop(sessionId) }
            .onSuccess { response ->
                if (response.optBoolean("ok")) {
                    addMessage("系统", "已提交停止请求，任务会在下一个安全点终止。")
                } else {
                    addMessage("错误", response.optString("error", "停止请求失败"))
                }
            }
        .onFailure { exc ->
                addMessage("错误", exc.message ?: exc.javaClass.simpleName)
            }
    }
    private fun normalizeLocalCommand(text: String): String? {
        val raw = text.trim().trimStart('\ufeff')
        val normalized = raw.lowercase()
        if (normalized.startsWith("-key ") || normalized.startsWith("/key ")) {
            return "key:${raw.substringAfter(' ').trim()}"
        }
        if (looksLikeApiKey(raw)) {
            return "key:$raw"
        }
        if (normalized.startsWith("-perm ") || normalized.startsWith("/perm ") || normalized.startsWith("perm ")) {
            return "perm:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-terminal ") || normalized.startsWith("/terminal ") || normalized.startsWith("terminal ")) {
            return "terminal:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-termux ") || normalized.startsWith("/termux ") || normalized.startsWith("termux ")) {
            return "terminal:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-mcp ") || normalized.startsWith("/mcp ") || normalized.startsWith("mcp ")) {
            return "mcp:${raw.substringAfter(' ').trim()}"
        }
        if (normalized.startsWith("-rounds ") || normalized.startsWith("/rounds ") || normalized.startsWith("rounds ")) {
            return "rounds:${raw.substringAfter(' ').trim()}"
        }
        return when (normalized) {
            "new", "-new", "--new", "/new", "-new-session", "--new-session", "/new-session" -> "new"
            "status", "-status", "--status", "/status" -> "status"
            "tools", "-tools", "--tools", "/tools" -> "tools"
            "docs", "-docs", "--docs", "/docs", "doc", "-doc", "/doc", "官方文档", "-官方文档", "/官方文档" -> "docs"
            "config", "-config", "--config", "/config", "settings", "-settings", "/settings" -> "config"
            "panel", "-panel", "--panel", "/panel", "menu", "-menu", "/menu" -> "panel"
            "reconnect", "-reconnect", "--reconnect", "/reconnect", "health", "-health", "/health" -> "reconnect"
            "logs", "-logs", "--logs", "/logs", "log", "-log", "/log" -> "logs"
            "compact", "-compact", "--compact", "/compact",
            "\u538b\u7f29", "-\u538b\u7f29", "/\u538b\u7f29",
            "\u538b\u7f29\u4e0a\u4e0b\u6587", "-\u538b\u7f29\u4e0a\u4e0b\u6587", "/\u538b\u7f29\u4e0a\u4e0b\u6587" -> "compact"
            "failures", "-failures", "--failures", "/failures",
            "failure", "-failure", "/failure",
            "失败分析", "-失败分析", "/失败分析" -> "failures"
            "help", "-help", "--help", "/help", "?" -> "help"
            "clear", "-clear", "--clear", "/clear" -> "clear"
            else -> null
        }
    }

    private fun runLocalCommand(command: String) {
        input.setText("")
        hideKeyboard()
        when (command) {
            "new" -> startNewSession()
            "status" -> {
                addMessage("系统", "已刷新运行状态")
                refreshStatus()
            }
            "tools" -> addMessage("系统", localToolsSummary())
            "docs" -> addMessage("系统", officialDocsSummary())
            "config" -> showConfigDialog()
            "panel" -> showActionPanel()
            "reconnect" -> runReconnect()
            "logs" -> showSystemLogs()
            "compact" -> compactContextNow()
            "failures" -> showLatestFailureAnalysis()
                "help" -> addMessage(
                    "系统",
                    "本地命令：\n" +
                        "-new  开启新会话\n" +
                        "-status  刷新状态\n" +
                        "-tools  查看内置工具\n" +
                        "-docs  查看官方文档\n" +
                        "-config  打开配置\n" +
                        "-panel  操作面板\n" +
                        "-reconnect  重连/自检\n" +
                        "-logs  系统日志\n" +
                        "-failures  最近失败分析\n" +
                        "-compact  压缩上下文\n" +
                        "-rounds 50  设置工具轮数\n" +
                        "-perm safe|ask|danger|developer  切换权限模式\n" +
                        "-terminal on|off|status|recover|health|http://127.0.0.1:8787  配置终端接口\n" +
                        "-mcp on|off|status|tools [关键词]|token <token>|<baseUrl>\n" +
                        "      配置并检测 Windows MCP/远程 MCP\n" +
                        "-clear  清空当前显示\n" +
                        "-key sk-...  保存模型 API Key\n" +
                        "-help  显示帮助"
                )
                "clear" -> {
                    savedMessages.clear()
                    messages.removeAllViews()
                    addMessage("系统", "已清空当前显示")
            }
            else -> {
                if (command.startsWith("key:")) {
                    val key = command.removePrefix("key:").trim()
                    if (!looksLikeApiKey(key)) {
                        addMessage("错误", "API Key 格式不正确，请输入 sk-...")
                    } else {
                        nativeCore.setApiKey(key)
                        addMessage("系统", "API Key 已保存到 APP ")
                        refreshStatus()
                    }
                } else if (command.startsWith("perm:")) {
                    setPermissionModeFromCommand(command.removePrefix("perm:").trim())
                } else if (command.startsWith("terminal:")) {
                    setTerminalFromCommand(command.removePrefix("terminal:").trim())
                } else if (command.startsWith("mcp:")) {
                    setMcpFromCommand(command.removePrefix("mcp:").trim())
                } else if (command.startsWith("rounds:")) {
                    setMaxToolRoundsFromCommand(command.removePrefix("rounds:").trim())
                }
            }
        }
    }

    private fun localToolsSummary(): String {
        val tools = nativeCore.status(sessionId).optJSONArray("tools") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (index in 0 until tools.length()) {
            lines.add("- ${tools.optString(index)}")
        }
        return "当前内置工具：\n" + lines.joinToString("\n") + "\n\n" + configSummary()
    }

    private fun officialDocsSummary(): String {
        val docs = nativeCore.docsIndexForUi().optJSONArray("documents") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (index in 0 until docs.length()) {
            val item = docs.optJSONObject(index) ?: continue
            lines.add("- ${item.optString("path")} - ${item.optString("title")}")
        }
        return "官方文档：\n" +
            lines.joinToString("\n") +
            "\\n\\nAgent 可用工具：docs_index / docs_read / docs_search / docs_sync\\n文件内容从 APP 工作目录加载，路径为 docs/official/"
    }

    private fun configSummary(): String {
        val status = nativeCore.status(sessionId)
        val terminal = status.optJSONObject("terminal") ?: JSONObject()
        val terminalText = if (terminal.optBoolean("enabled")) {
            "终端 ${terminal.optString("base_url", "-")}"
        } else {
            "终端未启用"
        }
        val mcp = status.optJSONObject("mcp") ?: JSONObject()
        val mcpText = if (mcp.optBoolean("enabled")) {
            val tokenText = if (mcp.optBoolean("has_auth_token", false)) "有token" else "无token"
            "MCP ${mcp.optString("base_url", "-")} ($tokenText)"
        } else {
            "MCP未启用"
        }
        val maxToolRounds = status.optJSONObject("config")?.optInt("max_tool_rounds", 30) ?: 30
        return "当前配置：权限 ${permissionLabel(status.optString("permission_mode", "safe"))} | 工具轮数 $maxToolRounds | $terminalText | $mcpText"
    }

    private fun setMaxToolRoundsFromCommand(value: String) {
        try {
            val rounds = value.toInt()
            nativeCore.setMaxToolRounds(rounds)
            addMessage("系统", "工具调用上限设置为 ${rounds.coerceIn(1, AgentRuntimeConfig.MAX_TOOL_ROUNDS_LIMIT)}")
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", "参数不合法: rounds 取值范围 1-${AgentRuntimeConfig.MAX_TOOL_ROUNDS_LIMIT}")
        }
    }

    private fun setPermissionModeFromCommand(mode: String) {
        try {
            val normalized = when (mode.lowercase()) {
                "safe", "safe_mode", "安全" -> AgentRuntimeConfig.MODE_SAFE
                "ask", "confirm", "确认", "确认动作" -> AgentRuntimeConfig.MODE_ASK
                "danger", "high", "高风险" -> AgentRuntimeConfig.MODE_DANGER
                "developer", "dev", "developer", "开发者", "开发模式" -> AgentRuntimeConfig.MODE_DEVELOPER
                else -> mode.lowercase()
            }
            if (isHighPowerMode(normalized) && nativeCore.permissionMode() != normalized) {
                confirmHighPowerMode(normalized) { applyPermissionMode(normalized) }
            } else {
                applyPermissionMode(normalized)
            }
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    private fun applyPermissionMode(mode: String) {
        nativeCore.setPermissionMode(mode)
        addMessage("系统", "权限模式设置为 ${permissionLabel(mode)}")
        refreshStatus()
    }

    private fun setTerminalFromCommand(value: String) {
        try {
            val current = nativeCore.config().optJSONObject("terminal") ?: JSONObject()
            when (value.lowercase()) {
                "on", "enable", "enabled", "开启", "open" -> {
                    nativeCore.setTerminalConfig(true, current.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL))
                    addMessage("系统", "终端接口已开启")
                }
                "off", "disable", "disabled", "关闭", "close" -> {
                    nativeCore.setTerminalConfig(false, current.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL))
                    addMessage("系统", "终端接口已关闭")
                }
                "status" -> showTerminalStatus()
                "health" -> showTerminalHealth(autoRecover = false)
                "recover" -> showTerminalHealth(autoRecover = true)
                else -> {
                    nativeCore.setTerminalConfig(true, value)
                    addMessage("系统", "终端服务地址设置为 $value")
                }
            }
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    private fun setMcpFromCommand(value: String) {
        try {
            val tokens = value.trim().split(Regex("\\s+"), limit = 2)
            val config = nativeCore.config()
            val currentMcp = config.optJSONObject("mcp") ?: JSONObject()
            val currentUrl = currentMcp.optString("base_url", AgentRuntimeConfig.DEFAULT_MCP_BASE_URL)
            val currentToken = nativeCore.mcpAuthToken()
            if (tokens.isEmpty() || value.isBlank()) {
                addMessage("系统", "用法：mcp on|off|status|tools [关键词]|token <token>|<baseUrl>")
                return
            }
            val first = tokens[0].lowercase()
            val rest = if (tokens.size > 1) tokens[1] else ""
            when (first) {
                "on", "enable", "enabled", "开启", "open" -> {
                    nativeCore.setMcpConfig(true, currentUrl, currentToken)
                    addMessage("系统", "MCP 已开启")
                }
                "off", "disable", "disabled", "关闭", "close" -> {
                    nativeCore.setMcpConfig(false, currentUrl, currentToken)
                    addMessage("系统", "MCP 已关闭")
                }
                "status" -> {
                    showMcpStatus()
                    return
                }
                "tools" -> {
                    showMcpTools(rest)
                    return
                }
                "token" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 token，示例：mcp token myToken123")
                    } else {
                        nativeCore.setMcpConfig(currentMcp.optBoolean("enabled"), currentUrl, rest)
                        addMessage("系统", "MCP token 已更新")
                    }
                }
                "url", "base", "base_url", "endpoint", "endpoint_url", "server", "address" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 MCP 地址，示例：mcp url http://192.168.1.10:8931")
                    } else {
                        nativeCore.setMcpConfig(currentMcp.optBoolean("enabled", true), rest, currentToken)
                        addMessage("系统", "MCP 地址已设置为 $rest")
                    }
                }
                else -> {
                    val normalized = first.lowercase()
                    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                        nativeCore.setMcpConfig(currentMcp.optBoolean("enabled", true), value, currentToken)
                        addMessage("系统", "MCP 地址已设置为 $value")
                    } else {
                        addMessage(
                            "错误",
                            "不识别的 mcp 命令：$value\n示例：mcp on | mcp off | mcp status | mcp tools 微信 | mcp token xxx | mcp http://192.168.1.10:8931"
                        )
                    }
                }
            }
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    private fun showMcpStatus() {
        addMessage("系统", "正在检查 MCP 连接...")
        thread(name = "mobile-agent-mcp-status") {
            val result = runCatching { nativeCore.mcpStatusForUi() }
            ui.post {
                result
                    .onSuccess { addMessage("系统", "MCP 状态：\n${it.toString(2)}") }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }

    private fun showMcpTools(search: String = "") {
        addMessage("系统", "正在读取 MCP 工具...")
        thread(name = "mobile-agent-mcp-tools") {
            val result = runCatching { nativeCore.mcpToolsForUi(search.trim()) }
            ui.post {
                result
                    .onSuccess { tools ->
                        val toolCount = tools.optInt("tool_count", tools.optJSONArray("tools")?.length() ?: 0)
                        val list = tools.optJSONArray("tools") ?: JSONArray()
                        val lines = mutableListOf<String>()
                        for (index in 0 until list.length()) {
                            val item = list.optJSONObject(index) ?: continue
                            lines.add("- ${item.optString("name")} ${item.optString("description")}".trim())
                        }
                        addMessage("系统", "MCP 工具列表 (${toolCount})：\n" + lines.joinToString("\n"))
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }

    private fun showTerminalStatus() {
        addMessage("系统", "正在检查终端连接...")
        thread(name = "mobile-agent-terminal-status") {
            val result = runCatching { nativeCore.terminalStatusForUi() }
            ui.post {
                result
                    .onSuccess { addMessage("系统", "终端接口状：\n${it.toString(2)}") }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }

    private fun showTerminalHealth(autoRecover: Boolean) {
        addMessage("系统", if (autoRecover) "正在检测终端并尝试恢复..." else "正在检测终端健康...")
        thread(name = "mobile-agent-terminal-health") {
            val result = runCatching { nativeCore.terminalHealthForUi(autoRecover) }
            ui.post {
                result
                    .onSuccess { resultJson ->
                        val state = resultJson.optString("state", "-")
                        addMessage("系统", "终端 $state", detail = resultJson.toString(2))
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }
    private fun showSystemLogs() {
        addMessage("系统", "正在读取系统日志...")
        thread(name = "mobile-agent-system-logs") {
            val result = runCatching { nativeCore.systemLogsForUi(80) }
            ui.post {
                result
                    .onSuccess { addMessage("系统", "系统日志 ${it.optJSONObject("summary")?.optInt("entries", 0) ?: 0} 条", detail = it.toString(2)) }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun compactContextNow() {
        addMessage("\u7cfb\u7edf", "\u6b63\u5728\u538b\u7f29\u4e0a\u4e0b\u6587...")
        thread(name = "mobile-agent-context-compact") {
            val result = runCatching { nativeCore.compactCurrentSessionForUi(sessionId) }
            ui.post {
                result
                    .onSuccess { compact ->
                        val summary = if (compact.optBoolean("compacted", false)) {
                            "\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff1a${compact.optInt("before_messages", 0)} \u6761 -> ${compact.optInt("after_messages", 0)} \u6761\uff0c${formatTokenK(compact.optLong("before_estimated_tokens", 0L))} -> ${formatTokenK(compact.optLong("after_estimated_tokens", 0L))}\u3002"
                        } else {
                            "\u6682\u65f6\u4e0d\u9700\u8981\u538b\u7f29\uff1a${compact.optString("reason", "unknown")}\u3002"
                        }
                        addMessage("\u7cfb\u7edf", summary, detail = compact.toString(2))
                        refreshStatus()
                    }
                    .onFailure { addMessage("\u9519\u8bef", it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun showLatestFailureAnalysis() {
        addMessage("系统", "正在读取最近失败分析...")
        thread(name = "mobile-agent-failure-analysis") {
            val result = runCatching { nativeCore.latestFailureAnalysisForUi(30000) }
            ui.post {
                result
                    .onSuccess { analysis ->
                        if (!analysis.optBoolean("ok", false)) {
                            addMessage("系统", analysis.optString("error", "未找到故障分析"))
                            return@onSuccess
                        }
                        val path = analysis.optString("path", "-")
                        val task = analysis.optJSONObject("task") ?: JSONObject()
                        val title = task.optString("title", "未命名故障任务")
                        val content = analysis.optString("content", "")
                        val truncated = if (analysis.optBoolean("truncated", false)) "\\n\\n... 内容被截断" else ""
                        val detail = buildString {
                            append("任务: ").append(title).append("\n")
                            append("路径: ").append(path).append("\n")
                            append("大小: ").append(analysis.optInt("size_bytes", 0)).append(" bytes\n\n")
                            append(content)
                            append(truncated)
                        }
                        addMessage("工具", "最近失败分析：$title\n$path", detail = detail)
                        showDetailsScrollable("故障分析: $title", detail)
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun showActionPanel() {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(28, 18, 28, 8)

        fun addPanelButton(label: String, action: () -> Unit) {
            val button = Button(this)
            button.text = label
            button.setOnClickListener { action() }
            panel.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("操作面板")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .create()

        addPanelButton("重连 / 自检") {
            dialog.dismiss()
            runReconnect()
        }
        addPanelButton("无障碍设置") {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPanelButton("配置") {
            dialog.dismiss()
            showConfigDialog()
        }
        addPanelButton("终端状") {
            dialog.dismiss()
            showTerminalStatus()
        }
        addPanelButton("MCP 状态") {
            dialog.dismiss()
            showMcpStatus()
        }
        addPanelButton("MCP 工具") {
            dialog.dismiss()
            showMcpTools("")
        }
        addPanelButton("系统日志") {
            dialog.dismiss()
            showSystemLogs()
        }
        addPanelButton("失败分析") {
            dialog.dismiss()
            showLatestFailureAnalysis()
        }
        addPanelButton("工具列表") {
            dialog.dismiss()
            addMessage("系统", localToolsSummary())
        }

        addPanelButton("官方文档") {
            dialog.dismiss()
            addMessage("系统", officialDocsSummary())
        }

        addPanelButton("记忆/经验") {
            dialog.dismiss()
            showMemoryExperiencePanel()
        }

        addPanelButton("开始学习") {
            dialog.dismiss()
            showLearningStartDialog()
        }

        addPanelButton("结束学习") {
            dialog.dismiss()
            stopLearningMode()
        }

        addPanelButton("重试失败步骤") {
            dialog.dismiss()
            retryLastFailedStep()
        }
        addPanelButton("停止后台任务") {
            dialog.dismiss()
            cancelRunningTerminalTasks()
        }
        addPanelButton("继续处理失败") {
            dialog.dismiss()
            continueFailedTask()
        }

        dialog.show()
    }

    private fun showMemoryExperiencePanel() {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(28, 18, 28, 8)

        fun addButton(label: String, action: () -> Unit) {
            val button = Button(this)
            button.text = label
            button.setOnClickListener { action() }
            panel.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("记忆/经验")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .create()

        addButton("查看摘要") {
            dialog.dismiss()
            runMemoryToolForDialog("memory_summary", JSONObject(), "记忆摘要") { formatMemorySummary(it) }
        }
        addButton("搜索经验") {
            dialog.dismiss()
            showSingleInputDialog("搜索经验", "关键词 / app / tool_scope") { query ->
                runMemoryToolForDialog("experience_search", JSONObject().put("query", query).put("limit", 20), "经验搜索") { formatExperienceMatches(it) }
            }
        }
        addButton("调整置信度") {
            dialog.dismiss()
            showExperienceConfidenceDialog()
        }
        addButton("删除经验") {
            dialog.dismiss()
            showSingleInputDialog("删除经验", "输入经验 id") { value ->
                runMemoryToolForDialog("experience_delete", JSONObject().put("id", value.trim().toIntOrNull() ?: -1), "删除经验") { it.toString(2) }
            }
        }
        addButton("压缩经验") {
            dialog.dismiss()
            showExperienceCompactDialog()
        }
        addButton("生成 Procedure") {
            dialog.dismiss()
            showProcedureGenerateDialog()
        }
        addButton("Procedure 列表") {
            dialog.dismiss()
            runMemoryToolForDialog("procedure_list", JSONObject().put("limit", 50), "Procedure 列表") { formatProcedureList(it) }
        }

        dialog.show()
    }

    private fun showLearningStartDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 8)
        val label = EditText(this)
        label.hint = "学习名称"
        label.setText("manual-demo")
        val app = EditText(this)
        app.hint = "app/package，可留空"
        val scope = EditText(this)
        scope.hint = "tool_scope"
        scope.setText("phone")
        layout.addView(label)
        layout.addView(app)
        layout.addView(scope)
        AlertDialog.Builder(this)
            .setTitle("开始学习")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                val args = JSONObject()
                    .put("label", label.text.toString().ifBlank { "manual-demo" })
                    .put("app", app.text.toString().ifBlank { "unknown" })
                    .put("tool_scope", scope.text.toString().ifBlank { "phone" })
                runMemoryToolForDialog("learning_start", args, "开始学习") { it.toString(2) }
            }
            .show()
    }

    private fun stopLearningMode() {
        runMemoryToolForDialog("learning_stop", JSONObject(), "结束学习") { it.toString(2) }
    }

    private fun showExperienceConfidenceDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 8)
        val id = EditText(this)
        id.hint = "经验 id"
        val confidence = EditText(this)
        confidence.hint = "high / medium / low"
        confidence.setText("high")
        layout.addView(id)
        layout.addView(confidence)
        AlertDialog.Builder(this)
            .setTitle("调整置信度")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val args = JSONObject()
                    .put("id", id.text.toString().trim().toIntOrNull() ?: -1)
                    .put("confidence", confidence.text.toString().trim().ifBlank { "medium" })
                runMemoryToolForDialog("experience_update", args, "调整置信度") { it.toString(2) }
            }
            .show()
    }

    private fun showExperienceCompactDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 8)
        val app = EditText(this)
        app.hint = "app，可留空"
        val scope = EditText(this)
        scope.hint = "tool_scope，可留空"
        val target = EditText(this)
        target.hint = "保留数量"
        target.setText("8")
        layout.addView(app)
        layout.addView(scope)
        layout.addView(target)
        AlertDialog.Builder(this)
            .setTitle("压缩经验")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("压缩") { _, _ ->
                val args = JSONObject()
                    .put("app", app.text.toString())
                    .put("tool_scope", scope.text.toString())
                    .put("target", target.text.toString().trim().toIntOrNull() ?: 8)
                runMemoryToolForDialog("experience_compact", args, "压缩经验") { it.toString(2) }
            }
            .show()
    }

    private fun showProcedureGenerateDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 8)
        val app = EditText(this)
        app.hint = "app"
        app.setText("general")
        val scope = EditText(this)
        scope.hint = "tool_scope"
        scope.setText("windows_mcp")
        layout.addView(app)
        layout.addView(scope)
        AlertDialog.Builder(this)
            .setTitle("生成 Procedure")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val args = JSONObject()
                    .put("app", app.text.toString().ifBlank { "general" })
                    .put("tool_scope", scope.text.toString())
                runMemoryToolForDialog("procedure_generate", args, "生成 Procedure") { it.toString(2) }
            }
            .show()
    }

    private fun showSingleInputDialog(title: String, hint: String, onSubmit: (String) -> Unit) {
        val edit = EditText(this)
        edit.hint = hint
        edit.setSingleLine(false)
        edit.setPadding(28, 18, 28, 18)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onSubmit(edit.text.toString()) }
            .show()
    }

    private fun runMemoryToolForDialog(tool: String, args: JSONObject, title: String, formatter: (JSONObject) -> String) {
        addMessage("系统", "正在执行：$tool")
        thread(name = "mobile-agent-memory-ui") {
            val result = runCatching { nativeCore.executeNativeToolForDiagnostics(tool, args, true) }
            ui.post {
                result
                    .onSuccess {
                        val payload = it.optJSONObject("result") ?: it
                        val text = formatter(payload)
                        addMessage("工具", "$title\n${text.take(1200)}", detail = it.toString(2))
                        showDetailsScrollable(title, text)
                        refreshStatus()
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun formatMemorySummary(result: JSONObject): String {
        val lines = mutableListOf<String>()
        lines.add("路径: ${result.optJSONObject("paths")?.optString("root", "-") ?: "-"}")
        val profile = result.optJSONObject("profile") ?: JSONObject()
        lines.add("UserMemory")
        lines.add(profile.optJSONObject("profile")?.toString(2) ?: "{}")
        lines.add("")
        lines.add("ExperienceLog")
        lines.add(result.optJSONObject("experience_stats")?.toString(2) ?: "{}")
        val groups = result.optJSONObject("experience_groups") ?: JSONObject()
        groups.keys().forEach { key ->
            val arr = groups.optJSONArray(key) ?: JSONArray()
            lines.add("")
            lines.add("[$key] ${arr.length()} 条")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                lines.add("id=${item.optInt("id")} confidence=${item.optString("confidence")} reinforced=${item.optInt("reinforced")} last_seen=${item.optLong("last_seen_ms")} type=${item.optString("lesson_type")}")
                lines.add(item.optString("description").take(500))
            }
        }
        lines.add("")
        lines.add("Procedures")
        lines.add(formatProcedureList(result))
        return lines.joinToString("\n")
    }

    private fun formatExperienceMatches(result: JSONObject): String {
        val arr = result.optJSONArray("matches") ?: JSONArray()
        if (arr.length() == 0) return "没有匹配经验\n\n${result.toString(2)}"
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            lines.add("id=${item.optInt("id")} app=${item.optString("app")} tool_scope=${item.optString("tool_scope")} type=${item.optString("lesson_type")} confidence=${item.optString("confidence")} reinforced=${item.optInt("reinforced")} last_seen=${item.optLong("last_seen_ms")}")
            lines.add(item.optString("description").take(700))
            lines.add("")
        }
        return lines.joinToString("\n")
    }

    private fun formatProcedureList(result: JSONObject): String {
        val arr = result.optJSONArray("procedures") ?: result.optJSONArray("matches") ?: JSONArray()
        if (arr.length() == 0) return "暂无 Procedure"
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            lines.add("${i + 1}. ${item.optString("path")} app=${item.optString("app")} tool_scope=${item.optString("tool_scope")} bytes=${item.optLong("bytes")}")
            if (item.has("preview")) lines.add(item.optString("preview").take(500))
        }
        return lines.joinToString("\n")
    }

    private fun retryLastFailedStep() {
        val target = findLastFailedTraceItem()
        if (target == null) {
            addMessage("系统", "没有可重试的失败工具步骤")
            return
        }
        val tool = target.optString("tool", "")
        val args = target.optJSONObject("arguments") ?: JSONObject()
        AlertDialog.Builder(this)
            .setTitle("重试失败步骤")
            .setMessage("将重新执行工具：$tool\n\n参数：${formatJsonPreview(args, 800)}")
            .setNegativeButton("取消", null)
            .setPositiveButton("重试") { _, _ -> runDiagnosticToolRetry(tool, args) }
            .show()
    }

    private fun cancelRunningTerminalTasks() {
        val taskIds = findRunningTerminalTaskIds()
        if (taskIds.isEmpty()) {
            addMessage("系统", "当前没有进行中的后台任务")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("停止后台任务")
            .setMessage("将尝试停止 ${taskIds.size} 个终端任务：\n${taskIds.joinToString("\n")}")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止") { _, _ ->
                thread(name = "mobile-agent-cancel-tasks") {
                    val trace = JSONArray()
                    for (taskId in taskIds) {
                        val args = JSONObject().put("task_id", taskId)
                        val output = nativeCore.executeNativeToolForDiagnostics("terminal_task_cancel", args, true)
                        trace.put(toolTraceItem("terminal_task_cancel", args, output, trace.length() + 1))
                    }
                    ui.post {
                        lastToolTrace = JSONArray(trace.toString())
                        lastTaskLoop = diagnosticLoop(trace)
                val summary = summarizeToolTrace(trace) ?: "任务完成后将继续执行下一个"
                        addMessage("工具", summary, detail = buildToolDetail(lastTaskLoop, trace))
                        refreshStatus()
                    }
                }
            }
            .show()
    }

    private fun continueFailedTask() {
        val target = findLastFailedTraceItem()
        if (target == null) {
            addMessage("系统", "没有找到可继续的失败任务步骤")
            return
        }
        val tool = target.optString("tool", "tool")
        val summary = target.optJSONObject("output")?.let { toolStepBrief(it) } ?: target.optString("state", "failed")
        sendConfirmedMessage(
            "检测到上一个失败的工具步骤，正在恢复 ${tool}，请确认参数：$summary。系统将继续执行后续动作，若失败会再次展示验证结果。",
            actionsApproved = false
        )
    }

    private fun runDiagnosticToolRetry(tool: String, args: JSONObject) {
        addMessage("系统", "正在重试工具 $tool")
        thread(name = "mobile-agent-tool-retry") {
            val output = nativeCore.executeNativeToolForDiagnostics(tool, JSONObject(args.toString()), true)
            val trace = JSONArray().put(toolTraceItem(tool, args, output, 1))
            ui.post {
                lastToolTrace = JSONArray(trace.toString())
                lastTaskLoop = diagnosticLoop(trace)
                val summary = summarizeToolTrace(trace) ?: "工具执行完成"
                addMessage("工具", summary, detail = buildToolDetail(lastTaskLoop, trace))
                refreshStatus()
            }
        }
    }

    private fun findLastFailedTraceItem(): JSONObject? {
        val trace = lastToolTrace ?: return null
        for (index in trace.length() - 1 downTo 0) {
            val item = trace.optJSONObject(index) ?: continue
            val output = item.optJSONObject("output") ?: JSONObject()
            if (uiToolState(output) != "success") return JSONObject(item.toString())
        }
        return null
    }

    private fun findRunningTerminalTaskIds(): List<String> {
        val trace = lastToolTrace ?: return emptyList()
        val taskIds = linkedSetOf<String>()
        for (index in 0 until trace.length()) {
            val output = trace.optJSONObject(index)?.optJSONObject("output") ?: continue
            val result = output.optJSONObject("result") ?: continue
            val status = result.optString("status", "").lowercase()
            val taskId = result.optString("task_id", "")
            if (taskId.isNotBlank() && status == "running") taskIds.add(taskId)
        }
        return taskIds.toList()
    }

    private fun toolTraceItem(tool: String, args: JSONObject, output: JSONObject, step: Int): JSONObject {
        return JSONObject()
            .put("step", step)
            .put("round", 0)
            .put("tool", tool)
            .put("arguments", JSONObject(args.toString()))
            .put("output", JSONObject(output.toString()))
            .put("state", uiToolState(output))
            .put("duration_ms", 0)
            .put("created_at", System.currentTimeMillis() / 1000.0)
    }

    private fun diagnosticLoop(trace: JSONArray): JSONObject {
        var failed = 0
        for (index in 0 until trace.length()) {
            if (trace.optJSONObject(index)?.optString("state") != "success") failed += 1
        }
        return JSONObject()
            .put("status", if (failed == 0) "completed" else "completed_with_failures")
            .put("rounds", 0)
            .put("max_rounds", 0)
            .put("steps", trace.length())
            .put("failed_steps", failed)
            .put("plan", JSONObject().put("status", "diagnostic").put("goal", "manual tool control"))
            .put("trace", trace)
    }

    private fun uiToolState(output: JSONObject): String {
        val verification = output.optJSONObject("verification")
        val result = output.optJSONObject("result")
        return when {
            verification?.optBoolean("required", false) == true && !verification.optBoolean("ok", false) -> "failed"
            result?.has("ok") == true && !result.optBoolean("ok", true) -> "failed"
            result?.has("available") == true && !result.optBoolean("available", true) -> "failed"
            output.optBoolean("ok", false) -> "success"
            output.optBoolean("needs_confirmation", false) -> "needs_confirmation"
            output.optBoolean("needs_permission", false) -> "needs_permission"
            else -> "failed"
        }
    }

    private fun toolStepBrief(output: JSONObject): String {
        val verification = output.optJSONObject("verification")
        val verificationSummary = verification?.optString("summary", "") ?: ""
        if (verificationSummary.isNotBlank()) return verificationSummary.take(240)
        val error = output.optString("error", "")
        if (error.isNotBlank()) return error.take(240)
        val result = output.optJSONObject("result")
        val nestedError = result?.optString("error", "") ?: ""
        if (nestedError.isNotBlank()) return nestedError.take(240)
        return output.toString().take(240)
    }

    private fun runReconnect() {
        addMessage("系统", "正在重连...")
        thread(name = "mobile-agent-reconnect") {
            val result = runCatching { nativeCore.reconnectForUi() }
            ui.post {
                result
                    .onSuccess {
                        addMessage("系统", reconnectSummary(it), detail = it.toString(2))
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }

    private fun reconnectSummary(result: JSONObject): String {
        val after = result.optJSONObject("after") ?: JSONObject()
        val terminal = after.optJSONObject("terminal") ?: JSONObject()
        val accessibility = after.optJSONObject("accessibility") ?: JSONObject()
        val terminalText = when (terminal.optString("status")) {
            "ok" -> "终端正常"
            "disabled" -> "终端未启用"
            "" -> "终端未知"
            else -> "终端${terminal.optString("status")}"
        }
        val accessibilityText = if (accessibility.optBoolean("connected", false)) {
            "无障碍已连接"
        } else {
            "无障碍未连接"
        }
        val recovery = result.optJSONObject("recovery") ?: JSONObject()
        val recoveryText = when {
            recovery.optBoolean("skipped", false) -> "未执行"
            recovery.optBoolean("ok", false) -> "恢复成功"
            recovery.length() > 0 -> "重连中"
            else -> "未执行"
        }
        return "重连完成: $terminalText | $accessibilityText | $recoveryText"
    }

    private fun startNewSession() {
        sessionId = nativeCore.newSession()
        pendingMessages.clear()
        refreshComposerState()
        savedMessages.clear()
        messages.removeAllViews()
        addMessage("系统", "已创建新会话")
        refreshStatus()
    }

    private fun sendConfirmedMessage(text: String, actionsApproved: Boolean) {
        addMessage("我", text)
        setSending(true)

        thread(name = "mobile-agent-chat") {
            val result = runCatching { nativeCore.chat(text, sessionId, actionsApproved) }
            ui.post {
                setSending(false)
                result
                    .onSuccess { response ->
                        val nextSessionId = response.optString("session_id", sessionId.orEmpty())
                        if (nextSessionId.isNotBlank()) {
                            sessionId = nextSessionId
                            saveState()
                        }
                        response.optJSONArray("tool_trace")?.let { trace ->
                            summarizeToolTrace(trace)?.let { summary ->
                                val loop = response.optJSONObject("task_loop")
                                lastToolTrace = JSONArray(trace.toString())
                                lastTaskLoop = loop?.let { JSONObject(it.toString()) }
                                val loopSummary = summarizeTaskLoop(loop)
                                val visibleSummary = listOfNotNull(loopSummary, summary).joinToString("\n")
                                val detail = buildToolDetail(loop, trace)
                                addMessage("工具", visibleSummary, detail = detail)
                            }
                            if (!actionsApproved) {
                                findConfirmationNeeded(trace)?.let { pending ->
                                    showToolConfirmationDialog(text, pending)
                                    refreshStatus()
                                    return@onSuccess
                                }
                            }
                        }
                        val answer = response.optString("message", response.optString("reply", response.toString()))
                        addMessage("助手", answer.ifBlank { response.toString() })
                        refreshStatus()
                        processQueuedMessageIfAny()
                    }
                    .onFailure { exc ->
                        addMessage("错误", exc.message ?: exc.javaClass.simpleName)
                        refreshStatus()
                        processQueuedMessageIfAny()
                    }
            }
        }
    }

    private fun findConfirmationNeeded(trace: JSONArray): JSONObject? {
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            val output = item.optJSONObject("output") ?: JSONObject()
            if (output.optBoolean("needs_confirmation", false) || item.optString("state") == "needs_confirmation") {
                return item
            }
        }
        return null
    }

    private fun showToolConfirmationDialog(originalText: String, traceItem: JSONObject) {
        val tool = traceItem.optString("tool", "tool")
        val output = traceItem.optJSONObject("output") ?: JSONObject()
        val detail = buildString {
            append("工具: ").append(tool).append("\n")
            append("状态: ").append(output.optString("state", "unknown")).append("\n")
            append("权限: ").append(output.optString("permission_mode", nativeCore.permissionMode())).append("\n\n")
            append("本次请求:\n").append(originalText).append("\n\n")
            append("参数:\n").append(formatJsonPreview(traceItem.optJSONObject("arguments") ?: JSONObject(), 1600)).append("\n\n")
            output.optString("error", "").takeIf { it.isNotBlank() }?.let {
                append("原因:\n").append(it).append("\n\n")
            }
            append("确认动作: 该动作触发确认后自动继续执行。")
        }
        AlertDialog.Builder(this)
            .setTitle("确认工具调用")
            .setMessage(detail)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行") { _, _ -> sendConfirmedMessage(originalText, actionsApproved = true) }
            .show()
    }

    private fun needsActionConfirmation(text: String): Boolean {
        val lower = text.lowercase()
        val actionWords = listOf(
            "click", "tap", "input", "type", "send", "delete", "install", "uninstall",
            "open app", "back", "home", "scroll", "press", "choose", "close", "exit",
            "run", "execute", "command", "shell", "terminal", "script", "terminal_run",
            "terminal_script", "terminal_task", "task", "cancel", "interrupt", "stop",
            "recover", "repair", "restart", "reconnect", "recover_terminal_backend",
            "look", "next", "input", "send", "delete", "restore", "upload", "open", "back", "return", "swipe",
            "continue", "choose", "close", "cancel", "scroll", "retry", "run", "execute", "config", "terminal", "session",
            "任务", "取消", "中断", "停止", "修复", "恢复", "重启", "重连", "自愈"
        )
        return actionWords.any { lower.contains(it) }
    }

    private fun postChat(message: String): JSONObject {
        val payload = JSONObject()
            .put("message", message)
        sessionId?.let { payload.put("session_id", it) }
        return postJson("$coreBaseUrl/chat", payload)
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun postJson(url: String, payload: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000
        connection.readTimeout = 120000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.ifBlank { "{}" })
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(json.optString("error", "HTTP ${connection.responseCode}"))
        }
        return json
    }

    private fun addMessage(role: String, text: String, persist: Boolean = true, detail: String? = null) {
        val safeText = if (looksLikeApiKey(text)) "[API Key 已隐藏]" else text
        val bubble = TextView(this)
        bubble.text = "$role\n$safeText"
        bubble.textSize = 15f
        bubble.setTextColor(Color.rgb(25, 29, 35))
        bubble.setPadding(18, 14, 18, 14)
        val bg = when (role) {
            "我" -> Color.rgb(219, 235, 255)
            "助手" -> Color.WHITE
            "工具" -> Color.rgb(235, 245, 238)
            "错误" -> Color.rgb(255, 232, 232)
            else -> Color.rgb(232, 236, 240)
        }
        bubble.setBackgroundColor(bg)
        if (detail != null) {
            bubble.setOnClickListener {
                showDetailsScrollable("$role details", detail)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 14)
        messages.addView(bubble, params)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        if (persist) {
            savedMessages.add(SavedMessage(role, safeText, detail))
            while (savedMessages.size > 80) savedMessages.removeAt(0)
            saveState()
        }
    }

    private fun setSending(sending: Boolean) {
        this.sending = sending
        sendButton.isEnabled = true
        input.isEnabled = true
        refreshComposerState()
        if (sending) {
            sendingStartedAt = System.currentTimeMillis()
            lastEventSeq = nativeCore.eventsForUi(0L, 1).optLong("last_seq", lastEventSeq)
            statusText.text = "核心处理中... 0s"
            ui.removeCallbacks(sendingStatusRunnable)
            ui.postDelayed(sendingStatusRunnable, 1000)
        } else {
            ui.removeCallbacks(sendingStatusRunnable)
            refreshStatus()
        }
    }

    private fun refreshComposerState() {
        stopButton.isEnabled = sending
        sendButton.text = if (sending) {
            if (pendingMessages.isEmpty()) "发送中"
            else "发送 (${pendingMessages.size})"
        } else if (pendingMessages.isNotEmpty()) {
            "发送 (${pendingMessages.size})"
        } else {
            "发送"
        }
    }

    private fun refreshLiveEvents(seconds: Long) {
        val events = nativeCore.eventsForUi(lastEventSeq, 20)
        val list = events.optJSONArray("events") ?: JSONArray()
        if (list.length() == 0) {
            detailStatusText.text = "running ${seconds}s | waiting event"
            return
        }
        val latest = list.optJSONObject(list.length() - 1) ?: return
        lastEventSeq = latest.optLong("seq", lastEventSeq)
        val message = latest.optString("message", latest.optString("type", "event"))
        val details = latest.optJSONObject("details") ?: JSONObject()
        val extra = when (latest.optString("type")) {
            "model_started" -> "messages ${details.optInt("messages", 0)}"
            "model_finished" -> "tools ${details.optInt("tool_calls", 0)} | ${details.optLong("duration_ms", 0)}ms"
            "tool_started" -> "step ${details.optInt("step", 0)}"
            "tool_finished" -> "${details.optString("state", "-")} | ${details.optLong("duration_ms", 0)}ms"
            "model_failed" -> details.optString("error", "").take(80)
            else -> ""
        }
        detailStatusText.text = listOf("running ${seconds}s", message, extra).filter { it.isNotBlank() }.joinToString(" | ")
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun keepComposerAboveKeyboard(root: LinearLayout) {
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val keyboardHeight = (root.rootView.height - visible.bottom).coerceAtLeast(0)
            composer.translationY = if (keyboardHeight > root.rootView.height * 0.15) {
                -keyboardHeight.toFloat()
            } else {
                0f
            }
        }
    }

    private fun summarizeStatus(status: JSONObject?): String {
        if (status == null || !status.optBoolean("ok")) return "\u6a21\u578b - | \u4e0a\u4e0b\u6587 - | \u7f13\u5b58 - | \u6743\u9650 - | \u7ec8\u7aef - | MCP -"
        val model = status.optString("model", "-")
        val permission = status.optString("permission_mode", "safe")
        val context = status.optJSONObject("context")
        val tokens = context?.optInt("estimated_tokens", 0) ?: 0
        val window = context?.optInt("model_window", 0) ?: 0
        val usedPercent = context?.optDouble("window_used_percent", Double.NaN) ?: Double.NaN
        val messages = context?.optInt("messages", 0) ?: 0
        val compacted = context?.optBoolean("compacted", false) == true
        val usage = status.optJSONObject("usage")
        val latestHit = usage?.optLong("prompt_cache_hit_tokens", 0L) ?: 0L
        val latestMiss = usage?.optLong("prompt_cache_miss_tokens", 0L) ?: 0L
        val sessionUsage = usage?.optJSONObject("session")
        val sessionHit = sessionUsage?.optLong("prompt_cache_hit_tokens", 0L) ?: 0L
        val sessionMiss = sessionUsage?.optLong("prompt_cache_miss_tokens", 0L) ?: 0L
        val cache = "\u672c\u6b21 ${cacheSummary(latestHit, latestMiss)} | \u4f1a\u8bdd\u5747\u503c ${cacheSummary(sessionHit, sessionMiss)}"
        val terminalLabel = terminalRuntimeLabel(status.optJSONObject("terminal_runtime"), status.optJSONObject("terminal"))
        val mcpLabel = mcpRuntimeLabel(status.optJSONObject("mcp_runtime"), status.optJSONObject("mcp"))
        val rawContextText = if (window > 0 && !usedPercent.isNaN()) {
            "${formatTokenK(tokens.toLong())}/${formatTokenK(window.toLong())}(${usedPercent.toInt()}%)"
        } else {
            "${formatTokenK(tokens.toLong())}/${messages}msg"
        }
        val contextText = rawContextText + if (compacted) " \u5df2\u538b\u7f29" else " \u672a\u538b\u7f29"
        return "\u6a21\u578b $model | \u4e0a\u4e0b\u6587 $contextText | \u7f13\u5b58 $cache | \u6743\u9650 ${permissionLabel(permission)} | \u7ec8\u7aef $terminalLabel | MCP $mcpLabel"
    }

    private fun terminalHeaderLabel(status: JSONObject?): String {
        return "\u7ec8\u7aef ${terminalRuntimeLabel(status?.optJSONObject("terminal_runtime"), status?.optJSONObject("terminal"))}"
    }

    private fun mcpHeaderLabel(status: JSONObject?): String {
        return "MCP ${mcpRuntimeLabel(status?.optJSONObject("mcp_runtime"), status?.optJSONObject("mcp"))}"
    }

    private fun terminalRuntimeLabel(runtime: JSONObject?, config: JSONObject?): String {
        if (config?.optBoolean("enabled") != true) return "\u5173"
        return when (runtime?.optString("state", "")) {
            "ok" -> "\u6b63\u5e38"
            "recovered" -> "\u5df2\u6062\u590d"
            "recovering" -> "\u6062\u590d\u4e2d"
            "checking" -> "\u68c0\u67e5\u4e2d"
            "failed" -> "\u5931\u8d25"
            "circuit_open" -> "\u7194\u65ad"
            "partial_backend" -> "\u90e8\u5206\u53ef\u7528"
            "backend_unavailable" -> "\u540e\u7aef\u4e0d\u53ef\u7528"
            "timeout" -> "\u8d85\u65f6"
            "connection_refused" -> "\u8fde\u63a5\u88ab\u62d2"
            "offline" -> "\u79bb\u7ebf"
            else -> "\u672a\u77e5"
        }
    }

    private fun mcpRuntimeLabel(runtime: JSONObject?, config: JSONObject?): String {
        if (config?.optBoolean("enabled") != true) return "\u5173"
        return when (runtime?.optString("state", "")) {
            "ok" -> "\u6b63\u5e38"
            "connecting", "checking" -> "\u6b63\u5728\u8fde\u63a5"
            "offline" -> "\u79bb\u7ebf"
            "partial_backend" -> "\u90e8\u5206\u53ef\u7528"
            else -> "\u79bb\u7ebf"
        }
    }
    private fun formatTokenK(value: Long): String {
        return when {
            value >= 1_000_000L -> {
                val oneDecimal = value / 100_000L / 10.0
                if (value % 1_000_000L == 0L) "${value / 1_000_000L}m" else "${oneDecimal}m"
            }
            value >= 1000L -> {
                val oneDecimal = value / 100L / 10.0
                if (value % 1000L == 0L) "${value / 1000L}k" else "${oneDecimal}k"
            }
            else -> "${value}t"
        }
    }

    private fun cacheSummary(hit: Long, miss: Long): String {
        val total = hit + miss
        if (total <= 0L) return "-"
        val rate = hit.toDouble() / total.toDouble()
        return "${(rate * 100).toInt()}% \u547d\u4e2d${formatTokenK(hit)}/\u8f93\u5165${formatTokenK(total)}"
    }

    private fun permissionLabel(mode: String): String {
        return when (mode) {
            AgentRuntimeConfig.MODE_DANGER -> "\u6700\u9ad8"
            AgentRuntimeConfig.MODE_DEVELOPER -> "\u5f00\u53d1\u8005"
            AgentRuntimeConfig.MODE_ASK -> "\u786e\u8ba4"
            else -> "\u5b89\u5168"
        }
    }

    private fun showConfigDialog() {
        val config = nativeCore.config()
        val currentMode = config.optString("permission_mode", AgentRuntimeConfig.MODE_SAFE)
        val modes = config.optJSONArray("permission_modes") ?: JSONArray()
        val terminal = config.optJSONObject("terminal") ?: JSONObject()
        val mcp = config.optJSONObject("mcp") ?: JSONObject()

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 0)

        val modeTitle = TextView(this)
        modeTitle.text = "权限模式"
        modeTitle.textSize = 16f
        modeTitle.setTextColor(Color.rgb(25, 29, 35))
        layout.addView(modeTitle)

        val modeGroup = RadioGroup(this)
        modeGroup.orientation = RadioGroup.VERTICAL
        val modeById = mutableMapOf<Int, String>()
        for (index in 0 until modes.length()) {
            val item = modes.optJSONObject(index) ?: continue
            val id = 1000 + index
            val mode = item.optString("id")
            modeById[id] = mode
            val radio = RadioButton(this)
            radio.id = id
            radio.text = "${item.optString("label")}：${item.optString("description")}"
            radio.textSize = 14f
            radio.setTextColor(Color.rgb(25, 29, 35))
            radio.setPadding(0, 6, 0, 6)
            modeGroup.addView(radio)
            if (mode == currentMode) modeGroup.check(id)
        }
        layout.addView(modeGroup)

        val terminalTitle = TextView(this)
        terminalTitle.text = "终端接口"
        terminalTitle.textSize = 16f
        terminalTitle.setTextColor(Color.rgb(25, 29, 35))
        terminalTitle.setPadding(0, 18, 0, 4)
        layout.addView(terminalTitle)

        val terminalEnabled = CheckBox(this)
        terminalEnabled.text = "启用 Termux/终端工具后端"
        terminalEnabled.isChecked = terminal.optBoolean("enabled")
        layout.addView(terminalEnabled)

        val terminalUrl = EditText(this)
        terminalUrl.hint = AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL
        terminalUrl.setSingleLine(true)
        terminalUrl.setText(terminal.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL))
        terminalUrl.setPadding(12, 8, 12, 8)
        layout.addView(terminalUrl)

        val hint = TextView(this)
        hint.text = "终端服务地址示例：127.0.0.1:8787，非本机不建议配置"
        hint.textSize = 12f
        hint.setTextColor(Color.rgb(110, 120, 132))
        hint.setPadding(0, 8, 0, 0)
        layout.addView(hint)

        val mcpTitle = TextView(this)
        mcpTitle.text = "Windows MCP"
        mcpTitle.textSize = 16f
        mcpTitle.setTextColor(Color.rgb(25, 29, 35))
        mcpTitle.setPadding(0, 18, 0, 4)
        layout.addView(mcpTitle)

        val mcpEnabled = CheckBox(this)
        mcpEnabled.text = "启用 Windows MCP / MCP-Server 工具桥接"
        mcpEnabled.isChecked = mcp.optBoolean("enabled")
        layout.addView(mcpEnabled)

        val mcpUrl = EditText(this)
        mcpUrl.hint = AgentRuntimeConfig.DEFAULT_MCP_BASE_URL
        mcpUrl.setSingleLine(true)
        mcpUrl.setText(mcp.optString("base_url", AgentRuntimeConfig.DEFAULT_MCP_BASE_URL))
        mcpUrl.setPadding(12, 8, 12, 8)
        layout.addView(mcpUrl)

        val mcpToken = EditText(this)
        mcpToken.hint = "可选：填写 Bearer Token 或 MCP 鉴权 token"
        mcpToken.setSingleLine(true)
        mcpToken.setPadding(12, 8, 12, 8)
        layout.addView(mcpToken)

        val mcpHint = TextView(this)
        mcpHint.text = "地址示例：http://192.168.1.10:8931/mcp/，不填写 token 表示不变更已保存 token"
        mcpHint.textSize = 12f
        mcpHint.setTextColor(Color.rgb(110, 120, 132))
        mcpHint.setPadding(0, 8, 0, 0)
        layout.addView(mcpHint)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Agent 配置")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedMode = modeById[modeGroup.checkedRadioButtonId] ?: currentMode
                val apply = {
                    try {
                        nativeCore.setPermissionMode(selectedMode)
                        nativeCore.setTerminalConfig(terminalEnabled.isChecked, terminalUrl.text.toString())
                        val tokenValue = mcpToken.text.toString().trim()
                        nativeCore.setMcpConfig(
                            mcpEnabled.isChecked,
                            mcpUrl.text.toString(),
                            if (tokenValue.isBlank()) nativeCore.mcpAuthToken() else tokenValue
                        )
                        addMessage("系统", "配置已应用\n${configSummary()}")
                        refreshStatus()
                        dialog.dismiss()
                    } catch (exc: Exception) {
                        showDetails("配置错误", exc.message ?: exc.javaClass.simpleName)
                    }
                }
                if (isHighPowerMode(selectedMode) && currentMode != selectedMode) {
                    confirmHighPowerMode(selectedMode) { apply() }
                } else {
                    apply()
                }
            }
        }
        dialog.show()
    }

    private fun isHighPowerMode(mode: String): Boolean {
        return mode == AgentRuntimeConfig.MODE_DANGER || mode == AgentRuntimeConfig.MODE_DEVELOPER
    }

    private fun confirmHighPowerMode(mode: String, onConfirmed: () -> Unit) {
        val label = permissionLabel(mode)
        AlertDialog.Builder(this)
            .setTitle("确认$label")
            .setMessage("$label 模式较高，请确认是本人且可接受其影响后才继续。确认后系统将按该模式执行指令。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> onConfirmed() }
            .show()
    }

    private fun showDetails(title: String, detail: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showDetailsScrollable(title: String, detail: String) {
        val text = TextView(this)
        text.text = detail
        text.textSize = 13f
        text.setTextColor(Color.rgb(25, 29, 35))
        text.setPadding(28, 20, 28, 20)
        text.setTextIsSelectable(true)
        val container = ScrollView(this)
        container.addView(text)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun buildToolDetail(loop: JSONObject?, trace: JSONArray): String {
        val parts = mutableListOf<String>()
        if (loop != null) {
            parts.add(
                listOf(
                    "任务循环",
                    "状态: ${loop.optString("status", "-")}",
                    "轮次: ${loop.optInt("rounds", 0)}/${loop.optInt("max_rounds", 0)}",
                    "步骤: ${loop.optInt("steps", 0)}",
                    "失败: ${loop.optInt("failed_steps", 0)}"
                ).joinToString("\n")
            )
            loop.optJSONObject("plan")?.let { plan ->
                parts.add(
                    listOf(
                        "计划",
                        "目标: ${plan.optString("goal", "-")}",
                        "状态: ${plan.optString("status", "-")}",
                        compactPlanSteps(plan.optJSONArray("steps"))
                    ).filter { it.isNotBlank() }.joinToString("\n")
                )
            }
        }
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            parts.add(formatToolTraceItem(item, index + 1))
        }
        parts.add(
            "原始 JSON\n" + JSONObject()
                .put("task_loop", loop ?: JSONObject())
                .put("tool_trace", trace)
                .toString(2)
        )
        return parts.joinToString("\n\n---\n\n")
    }

    private fun compactPlanSteps(steps: JSONArray?): String {
        if (steps == null || steps.length() == 0) return ""
        val lines = mutableListOf("步骤:")
        for (index in 0 until steps.length().coerceAtMost(12)) {
            val step = steps.optJSONObject(index) ?: continue
            lines.add("- ${step.optString("id", "${index + 1}")}: ${step.optString("status", "-")} ${step.optString("title", "").take(80)}")
        }
        if (steps.length() > 12) lines.add("- ... 还有 ${steps.length() - 12} 步")
        return lines.joinToString("\n")
    }

    private fun formatToolTraceItem(item: JSONObject, fallbackStep: Int): String {
        val output = item.optJSONObject("output") ?: JSONObject()
        val result = output.optJSONObject("result") ?: JSONObject()
        val lines = mutableListOf<String>()
        val step = item.optInt("step", fallbackStep)
        lines.add("工具 #$step ${item.optString("tool", "tool")}")
        lines.add("状态: ${item.optString("state", "-")} | 耗时: ${item.optLong("duration_ms", 0)}ms")
        lines.add("参数:\n${formatJsonPreview(item.optJSONObject("arguments") ?: JSONObject(), 1200)}")
        output.optString("error", "").takeIf { it.isNotBlank() }?.let { lines.add("错误: $it") }
        output.optJSONObject("verification")?.let { verification ->
            lines.add(
                listOf(
                    "验证",
                    "required=${verification.optBoolean("required", false)} ok=${verification.optBoolean("ok", false)} status=${verification.optString("status", "-")}",
                    verification.optString("summary", "")
                ).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
        output.optJSONObject("auto_recovery")?.let { recovery ->
            lines.add("自动恢复:\n${formatRecoverySummary(recovery)}")
        }
        output.optJSONObject("verification_recovery")?.let { recovery ->
            lines.add("验证恢复:\n${formatRecoverySummary(recovery)}")
        }
        appendOutputPreview(lines, "stdout", result)
        appendOutputPreview(lines, "stderr", result)
        if (result.length() > 0) {
            lines.add("结果摘要:\n${formatJsonPreview(result, 1600)}")
        }
        return lines.joinToString("\n")
    }

    private fun formatRecoverySummary(recovery: JSONObject): String {
        val lines = mutableListOf<String>()
        lines.add("type=${recovery.optString("type", "-")} status=${recovery.optString("status", "-")}")
        recovery.optString("strategy", "").takeIf { it.isNotBlank() }?.let { lines.add("strategy=$it") }
        recovery.optJSONObject("diagnosis")?.let { diagnosis ->
            lines.add("diagnosis=${diagnosis.optString("status", "-")} ${diagnosis.optString("summary", "")}".trim())
        }
        recovery.optJSONObject("retry_verification")?.let { verification ->
            lines.add("retry_verification ok=${verification.optBoolean("ok", false)} status=${verification.optString("status", "-")}")
        }
        return lines.joinToString("\n")
    }

    private fun appendOutputPreview(lines: MutableList<String>, key: String, result: JSONObject) {
        val folded = result.optJSONObject("output")?.optJSONObject(key)
        val text = folded?.optString("text", "")?.ifBlank { result.optString(key, "") } ?: result.optString(key, "")
        if (text.isBlank()) return
        val truncated = folded?.optBoolean("truncated", false) ?: (text.length > 2000)
        val chars = folded?.optInt("chars", text.length) ?: text.length
        val preview = text.take(2000)
        val suffix = if (truncated || text.length > preview.length) "\n... output truncated, chars=$chars" else ""
        lines.add("$key:\n$preview$suffix")
    }

    private fun formatJsonPreview(value: JSONObject, maxChars: Int): String {
        val text = runCatching { value.toString(2) }.getOrElse { value.toString() }
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n... JSON truncated, chars=${text.length}"
    }

    private fun summarizeToolTrace(trace: JSONArray?): String? {
        if (trace == null || trace.length() == 0) return null
        val lines = mutableListOf<String>()
        for (index in 0 until trace.length()) {
            val item = trace.optJSONObject(index) ?: continue
            val tool = item.optString("tool", "tool")
            val step = item.optInt("step", index + 1)
            val output = item.optJSONObject("output")
            val ok = output?.optBoolean("ok", false) == true
            val verification = output?.optJSONObject("verification")
            val verificationText = when {
                verification?.optBoolean("required", false) == true && verification.optBoolean("ok", false) -> " | 先前检查成功"
                verification?.optBoolean("required", false) == true -> " | 验证失败"
                else -> ""
            }
            val autoRecovery = output?.optJSONObject("auto_recovery")
            val recoveryText = when (autoRecovery?.optString("status", "")) {
                "recovered" -> " | 自动恢复成功"
                "retry_failed" -> " | 重试失败"
                "recovery_failed" -> " | 自动恢复失败"
                else -> ""
            }
            val state = when {
                ok -> "成功"
                output?.optBoolean("needs_confirmation") == true -> "待确认"
                output?.optBoolean("needs_permission") == true -> "待授权"
                else -> "失败"
            }
            val verificationRecovery = output?.optJSONObject("verification_recovery")
            val verificationRecoveryText = when (verificationRecovery?.optString("status", "")) {
                "recovered" -> " | 验证恢复成功"
                "retry_failed" -> " | 验证重试失败"
                "recovery_failed" -> " | 验证恢复失败"
                "not_attempted" -> " | 未尝试"
                else -> ""
            }
            lines.add("#$step $state $tool$verificationText$recoveryText$verificationRecoveryText")
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    private fun summarizeTaskLoop(loop: JSONObject?): String? {
        if (loop == null) return null
        val state = when (loop.optString("status")) {
            "completed" -> "完成"
            "completed_with_failures" -> "完成但有失败"
            "max_rounds_reached" -> "达到轮数上限"
            "no_tools" -> "未找到工具"
            else -> loop.optString("status", "未知")
        }
        val plan = loop.optJSONObject("plan")
        val planText = summarizePlan(plan)
        val base = "任务循环 $state | 步骤 ${loop.optInt("steps", 0)} | 失败 ${loop.optInt("failed_steps", 0)} | 轮次 ${loop.optInt("rounds", 0)}/${loop.optInt("max_rounds", 0)}"
        return if (planText == null) base else "$base\n$planText"
    }

    private fun summarizePlan(plan: JSONObject?): String? {
        if (plan == null) return null
        val steps = plan.optJSONArray("steps") ?: return null
        if (steps.length() == 0) return null
        val status = when (plan.optString("status")) {
            "not_started" -> "未开始"
            "in_progress" -> "进行中"
            "blocked" -> "受阻"
            "completed" -> "完成"
            "failed" -> "失败"
            "cancelled" -> "取消"
            else -> plan.optString("status", "未知")
        }
        var completed = 0
        var failed = 0
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            when (step.optString("status")) {
                "completed" -> completed += 1
                "failed", "blocked" -> failed += 1
            }
        }
        val goal = plan.optString("goal", "").take(40)
        val goalText = if (goal.isBlank()) "" else " | $goal"
        return "计划 $status | ${completed}/${steps.length()} 完成 | 异常 $failed$goalText"
    }

    private fun loadState() {
        val prefs = getSharedPreferences("mobile-agent", Context.MODE_PRIVATE)
        sessionId = prefs.getString("session_id", null)
        val raw = prefs.getString("messages", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            var removedSecret = false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = localizeRole(item.optString("role"))
                val text = localizeSavedText(item.optString("text"))
                val detail = item.optString("detail").ifBlank { null }
                if (looksLikeApiKey(text)) {
                    removedSecret = true
                    continue
                }
                if (role.isNotBlank() && text.isNotBlank()) {
                    savedMessages.add(SavedMessage(role, text, detail))
                }
            }
            if (removedSecret) {
                saveState()
            }
        }
    }

    private fun saveState() {
        val array = JSONArray()
        savedMessages.forEach { item ->
            val json = JSONObject().put("role", item.role).put("text", item.text)
            item.detail?.let { json.put("detail", it) }
            array.put(json)
        }
        getSharedPreferences("mobile-agent", Context.MODE_PRIVATE)
            .edit()
            .putString("session_id", sessionId)
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

    private fun looksLikeApiKey(text: String): Boolean {
        val value = text.trim()
        if (value.contains(" ") || value.contains("\n") || value.length < 12) return false
        return value.startsWith("sk-") || value.startsWith("sk_") || value.lowercase().startsWith("sk-")
    }
}



