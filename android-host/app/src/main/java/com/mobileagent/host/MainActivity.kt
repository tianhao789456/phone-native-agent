package com.mobileagent.host

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
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
    private lateinit var localCommandRunner: MainLocalCommandRunner
    private lateinit var memoryDialogController: MainMemoryDialogController
    private lateinit var runtimeDialogController: MainRuntimeDialogController
    private lateinit var configDialogController: MainConfigDialogController
    private lateinit var actionPanelController: MainActionPanelController
    private lateinit var settingsCommandController: MainSettingsCommandController
    private lateinit var failureController: MainFailureController
    private lateinit var detailsDialogController: MainDetailsDialogController
    private lateinit var conversationController: MainConversationController
    private lateinit var statusController: MainStatusController
    private lateinit var taskController: MainTaskController
    private lateinit var messageRenderer: MainMessageRenderer
    private lateinit var toolDialogRunner: MainToolDialogRunner
    private var sessionId: String? = null
    private var lastToolTrace: JSONArray? = null
    private var lastTaskLoop: JSONObject? = null
    private var sending = false
    private var sendingStartedAt = 0L
    private val sendingStatusRunnable = object : Runnable {
        override fun run() {
            if (!sending) return
            val seconds = ((System.currentTimeMillis() - sendingStartedAt) / 1000).coerceAtLeast(0)
            statusText.text = "核心处理中... ${seconds}s"
            taskController.refreshLiveEvents(seconds)
            ui.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        HostBridgeServer.start(this)
        AgentKeepAliveService.start(this)
        nativeCore = NativeAgentCore(this)
        sessionId = getSharedPreferences("mobile-agent", Context.MODE_PRIVATE).getString("session_id", null)
        conversationController = MainConversationController(
            prefs = getSharedPreferences("mobile-agent", Context.MODE_PRIVATE),
            getSessionId = { sessionId }
        )
        detailsDialogController = MainDetailsDialogController(this)
        configDialogController = createConfigDialogController()
        toolDialogRunner = createToolDialogRunner()
        localCommandRunner = createLocalCommandRunner()
        memoryDialogController = createMemoryDialogController()
        runtimeDialogController = createRuntimeDialogController()
        actionPanelController = createActionPanelController()
        settingsCommandController = createSettingsCommandController()
        failureController = createFailureController()
        taskController = createTaskController()
        AgentLogStore.record(this, "info", "ui", "main activity created")
        ensureTermuxRunCommandPermission()
        buildUi()
        statusController = MainStatusController(
            ui = ui,
            statusText = statusText,
            detailStatusText = detailStatusText,
            coreStatus = { nativeCore.status(sessionId) },
            accessibilityStatus = { AccessibilityState.status(this@MainActivity) }
        )
        refreshComposerState()
        val restoredMessages = conversationController.loadState()
        if (restoredMessages.isEmpty()) {
            addMessage("系统", "Mobile Agent 已就绪")
        } else {
            restoredMessages.forEach { item ->
                renderMessage(item.role, item.text, item.detail)
            }
        }
        startStatusRefresh()
    }

    override fun onResume() {
        super.onResume()
        startStatusRefresh()
    }

    override fun onPause() {
        statusController.stop()
        super.onPause()
    }

    private fun startStatusRefresh() {
        statusController.start()
    }

    private fun ensureTermuxRunCommandPermission() {
        val permission = "com.termux.permission.RUN_COMMAND"
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(permission), TERMUX_RUN_COMMAND_PERMISSION_REQUEST) }
            .onFailure { error -> AgentLogStore.record(this, "warn", "ui", "request Termux RUN_COMMAND permission failed: ${error.message}") }
    }

    private fun createLocalCommandRunner(): MainLocalCommandRunner {
        return MainLocalCommandRunner(object : MainLocalCommandRunner.Actions {
            override fun beforeCommand() {
                input.setText("")
                hideKeyboard()
            }

            override fun startNewSession() = this@MainActivity.startNewSession()
            override fun addSystem(text: String) = addMessage("系统", text)
            override fun addError(text: String) = addMessage("错误", text)
            override fun refreshStatus() = this@MainActivity.refreshStatus()
            override fun showConfigDialog() = this@MainActivity.showConfigDialog()
            override fun showActionPanel() = this@MainActivity.showActionPanel()
            override fun runReconnect() = this@MainActivity.runReconnect()
            override fun showSystemLogs() = this@MainActivity.showSystemLogs()
            override fun compactContextNow() = this@MainActivity.compactContextNow()
            override fun showLatestFailureAnalysis() = this@MainActivity.showLatestFailureAnalysis()

            override fun clearDisplay() {
                conversationController.clear()
                messages.removeAllViews()
                addMessage("系统", "已清空当前显示")
            }

            override fun localToolsSummary(): String {
                return MainPanelSummaryFormatter.localToolsSummary(nativeCore.status(sessionId))
            }

            override fun officialDocsSummary(): String {
                return MainPanelSummaryFormatter.officialDocsSummary(nativeCore.docsIndexForUi())
            }

            override fun setApiKey(key: String) {
                nativeCore.setApiKey(key)
                addMessage("系统", "API Key 已保存到应用")
                refreshStatus()
            }

            override fun setPermissionMode(value: String) = setPermissionModeFromCommand(value)
            override fun setTerminal(value: String) = setTerminalFromCommand(value)
            override fun setMcp(value: String) = setMcpFromCommand(value)
            override fun setSsh(value: String) = setSshFromCommand(value)
            override fun setMaxToolRounds(value: String) = setMaxToolRoundsFromCommand(value)
        })
    }

    private fun createMemoryDialogController(): MainMemoryDialogController {
        return MainMemoryDialogController(
            activity = this,
            runMemoryTool = { tool, args, title, formatter ->
                toolDialogRunner.run(tool, args, title, formatter, "mobile-agent-memory-ui")
            },
            runDocsTool = { tool, args, title, formatter ->
                toolDialogRunner.run(tool, args, title, formatter, "mobile-agent-docs-ui")
            },
        )
    }

    private fun createRuntimeDialogController(): MainRuntimeDialogController {
        return MainRuntimeDialogController(
            core = nativeCore,
            ui = ui,
            addMessage = { role, text, detail -> addMessage(role, text, detail = detail) },
            refreshStatus = { refreshStatus() },
        )
    }

    private fun createToolDialogRunner(): MainToolDialogRunner {
        return MainToolDialogRunner(
            core = nativeCore,
            ui = ui,
            addMessage = { role, text, detail -> addMessage(role, text, detail = detail) },
            showScrollable = { title, detail -> detailsDialogController.showScrollable(title, detail) },
            refreshStatus = { refreshStatus() }
        )
    }

    private fun createConfigDialogController(): MainConfigDialogController {
        return MainConfigDialogController(
            activity = this,
            core = nativeCore,
            getSessionId = { sessionId },
            addMessage = { role, text, detail -> addMessage(role, text, detail = detail) },
            refreshStatus = { refreshStatus() },
            showError = { title, detail -> detailsDialogController.show(title, detail) }
        )
    }

    private fun createActionPanelController(): MainActionPanelController {
        return MainActionPanelController(this, object : MainActionPanelController.Actions {
            override fun reconnect() = runReconnect()
            override fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            override fun showConfig() = showConfigDialog()
            override fun showTerminalStatus() = this@MainActivity.showTerminalStatus()
            override fun showMcpStatus() = this@MainActivity.showMcpStatus()
            override fun showMcpTools() = this@MainActivity.showMcpTools("")
            override fun showSystemLogs() = this@MainActivity.showSystemLogs()
            override fun showLatestFailureAnalysis() = this@MainActivity.showLatestFailureAnalysis()
            override fun showLocalTools() {
                addMessage("系统", MainPanelSummaryFormatter.localToolsSummary(nativeCore.status(sessionId)))
            }
            override fun showOfficialDocs() {
                addMessage("系统", MainPanelSummaryFormatter.officialDocsSummary(nativeCore.docsIndexForUi()))
            }
            override fun showMemoryExperience() = showMemoryExperiencePanel()
            override fun startLearning() = showLearningStartDialog()
            override fun stopLearning() = stopLearningMode()
            override fun retryLastFailedStep() = this@MainActivity.failureController.retryLastFailedStep()
            override fun cancelRunningTerminalTasks() = this@MainActivity.failureController.cancelRunningTerminalTasks()
            override fun continueFailedTask() = this@MainActivity.failureController.continueFailedTask()
        })
    }

    private fun createSettingsCommandController(): MainSettingsCommandController {
        return MainSettingsCommandController(
            core = nativeCore,
            addMessage = { role, text -> addMessage(role, text) },
            refreshStatus = { refreshStatus() },
            confirmHighPowerMode = { mode, onConfirmed -> configDialogController.confirmHighPowerMode(mode, onConfirmed) },
            showTerminalStatus = { showTerminalStatus() },
        showTerminalHealth = { autoRecover -> showTerminalHealth(autoRecover) },
            showMcpStatus = { showMcpStatus() },
            showMcpTools = { search -> showMcpTools(search) },
            showSshStatus = { showSshStatus() },
            showSshConnect = { showSshConnect() },
            showSshDiagnose = { showSshDiagnose() },
            showSshSelectHost = { candidates -> showSshSelectHost(candidates) },
            showSshDisconnect = { showSshDisconnect() },
        )
    }

    private fun createFailureController(): MainFailureController {
        return MainFailureController(
            activity = this,
            core = nativeCore,
            ui = ui,
            addMessage = { role, text, detail -> addMessage(role, text, detail = detail) },
            refreshStatus = { refreshStatus() },
            getLastToolTrace = { lastToolTrace },
            setLastToolTrace = { lastToolTrace = it },
            setLastTaskLoop = { lastTaskLoop = it },
            sendConfirmedMessage = { text, actionsApproved -> taskController.sendConfirmedMessage(text, actionsApproved) },
            showDetailsScrollable = { title, detail -> detailsDialogController.showScrollable(title, detail) }
        )
    }

    private fun createTaskController(): MainTaskController {
        return MainTaskController(
            activity = this,
            core = nativeCore,
            ui = ui,
            isSending = { sending },
            setSending = { setSending(it) },
            getSessionId = { sessionId },
            updateSessionId = { sessionId = it },
            onComposerStateChanged = { refreshComposerState() },
            saveState = { conversationController.saveState() },
            addMessage = { role, text, detail -> addMessage(role, text, detail = detail) },
            onRefreshStatus = { refreshStatus() },
            setLastToolTrace = { lastToolTrace = it },
            setLastTaskLoop = { lastTaskLoop = it },
            onLiveStatus = { text -> detailStatusText.text = text }
        )
    }

    private fun buildUi() {
        val views = MainLayoutBuilder.build(
            activity = this,
            onPanelClick = { showActionPanel() },
            onStopClick = { taskController.requestStopCurrentTask() },
            onSendClick = { onSendPressed() }
        )
        statusText = views.statusText
        detailStatusText = views.detailStatusText
        messages = views.messages
        scrollView = views.scrollView
        composer = views.composer
        input = views.input
        sendButton = views.sendButton
        stopButton = views.stopButton
        messageRenderer = MainMessageRenderer(this, messages, scrollView, detailsDialogController)
        setContentView(views.root)
        MainLayoutBuilder.keepComposerAboveKeyboard(views.root, composer)
        refreshComposerState()
    }

    private fun refreshStatus() {
        if (sending) return
        statusController.refresh()
    }

    private fun onSendPressed() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        hideKeyboard()

        MainLocalCommandParser.normalize(text)?.let { command ->
            runLocalCommand(command)
            return
        }

        taskController.sendUserMessage(text)
    }

    private fun runLocalCommand(command: String) {
        localCommandRunner.run(command)
    }

    private fun setMaxToolRoundsFromCommand(value: String) {
        settingsCommandController.setMaxToolRoundsFromCommand(value)
    }

    private fun setPermissionModeFromCommand(mode: String) {
        settingsCommandController.setPermissionModeFromCommand(mode)
    }

    private fun setTerminalFromCommand(value: String) {
        settingsCommandController.setTerminalFromCommand(value)
    }

    private fun setMcpFromCommand(value: String) {
        settingsCommandController.setMcpFromCommand(value)
    }

    private fun setSshFromCommand(value: String) {
        settingsCommandController.setSshFromCommand(value)
    }

    private fun showMcpStatus() {
        runtimeDialogController.showMcpStatus()
    }

    private fun showMcpTools(search: String = "") {
        runtimeDialogController.showMcpTools(search)
    }

    private fun showSshStatus() {
        runtimeDialogController.showSshStatus()
    }

    private fun showSshConnect() {
        runtimeDialogController.showSshConnect()
    }

    private fun showSshDiagnose() {
        runtimeDialogController.showSshDiagnose()
    }

    private fun showSshSelectHost(candidates: String = "") {
        runtimeDialogController.showSshSelectHost(candidates)
    }

    private fun showSshDisconnect() {
        runtimeDialogController.showSshDisconnect()
    }

    private fun showTerminalStatus() {
        runtimeDialogController.showTerminalStatus()
    }

    private fun showTerminalHealth(autoRecover: Boolean) {
        runtimeDialogController.showTerminalHealth(autoRecover)
    }
    private fun showSystemLogs() {
        runtimeDialogController.showSystemLogs()
    }

    private fun compactContextNow() {
        addMessage("\u7cfb\u7edf", "\u6b63\u5728\u538b\u7f29\u4e0a\u4e0b\u6587...")
        thread(name = "mobile-agent-context-compact") {
            val result = runCatching { nativeCore.compactCurrentSessionForUi(sessionId) }
            ui.post {
                result
                    .onSuccess { compact ->
                        val summary = if (compact.optBoolean("compacted", false)) {
                            "\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff1a${compact.optInt("before_messages", 0)} \u6761 -> ${compact.optInt("after_messages", 0)} \u6761\uff0c${MainStatusFormatter.formatTokenK(compact.optLong("before_estimated_tokens", 0L))} -> ${MainStatusFormatter.formatTokenK(compact.optLong("after_estimated_tokens", 0L))}\u3002"
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
        failureController.showLatestFailureAnalysis()
    }

    private fun showActionPanel() {
        actionPanelController.show()
    }

    private fun showMemoryExperiencePanel() {
        memoryDialogController.showMemoryExperiencePanel()
    }

    private fun showLearningStartDialog() {
        memoryDialogController.showLearningStartDialog()
    }

    private fun stopLearningMode() {
        memoryDialogController.stopLearningMode()
    }

    private fun runReconnect() {
        addMessage("系统", "正在重连...")
        thread(name = "mobile-agent-reconnect") {
            val result = runCatching { nativeCore.reconnectForUi() }
            ui.post {
                result
                    .onSuccess {
                        addMessage("系统", DiagnosticTraceFormatter.reconnectSummary(it), detail = it.toString(2))
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName) }
                refreshStatus()
            }
        }
    }

    private fun startNewSession() {
        sessionId = nativeCore.newSession()
        taskController.clearForNewSession()
        refreshComposerState()
        conversationController.clear()
        messages.removeAllViews()
        addMessage("系统", "已创建新会话")
        refreshStatus()
    }

    private fun addMessage(role: String, text: String, persist: Boolean = true, detail: String? = null) {
        val item = conversationController.addMessage(role, text, detail, persist)
        renderMessage(item.role, item.text, item.detail)
    }

    private fun renderMessage(role: String, text: String, detail: String? = null) {
        messageRenderer.render(role, text, detail)
    }

    private fun setSending(sending: Boolean) {
        this.sending = sending
        sendButton.isEnabled = true
        input.isEnabled = true
        refreshComposerState()
        if (sending) {
            sendingStartedAt = System.currentTimeMillis()
            taskController.startLiveEventTracking()
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
            if (taskController.pendingCount() == 0) "追加"
            else "追加 (${taskController.pendingCount()})"
        } else if (taskController.pendingCount() > 0) {
            "发送 (${taskController.pendingCount()})"
        } else {
            "发送"
        }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun showConfigDialog() {
        configDialogController.show()
    }

    private companion object {
        private const val TERMUX_RUN_COMMAND_PERMISSION_REQUEST = 4201
    }
}



