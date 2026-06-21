package com.mobileagent.host

import android.content.Context
import org.json.JSONObject

class NativeAgentCore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mobile-agent-core", Context.MODE_PRIVATE)
    private val sessionStore = NativeSessionStore(prefs)
    private val runtimeConfig = AgentRuntimeConfig(context)
    private val workspace = MobileWorkspace(context)
    private val docsController = NativeDocsController(
        context = context,
        workspace = workspace,
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val toolsets = NativeToolsetController(prefs)
    private val storageAccess = NativeStorageAccess(context)
    private val androidActions = NativeAndroidActionController(context)
    private val androidIntentTools = AndroidIntentTools(context, workspace)
    private val plugins = MobilePluginRegistry(context)
    private val memory = MobileMemoryStore(context)
    private val stopController = NativeStopController { level, component, message, details ->
        log(level, component, message, details)
    }
    private val stopFlowController = NativeStopFlowController(stopController)
    private val sshBridge = SshBridge(context, runtimeConfig, workspace) { level, component, message, details ->
        log(level, component, message, details)
    }
    private val profile = NativeAgentProfile
    private val contextManager = NativeContextManager(
        prefs = prefs,
        modelWindowTokens = profile.MODEL_WINDOW_TOKENS
    )
    private val modelClient = NativeModelClient(
        log = { level, component, message, details -> log(level, component, message, details) },
        saveUsage = { usage -> contextManager.saveUsage(usage) }
    )
    private val modelRequester = NativeModelRequester(modelClient)
    private val stepEvaluator = NativeLoopStepEvaluator(
        NativeCoreToolSets.screenActionTools,
        NativeCoreToolSets.verificationTools
    )
    private val taskPlanController = NativeTaskPlanController()
    private val taskMemoryCoordinator = NativeTaskMemoryCoordinator(
        workspace = workspace,
        memory = memory,
        stepEvaluator = stepEvaluator,
        modelClient = modelClient,
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val toolVerifier = NativeToolVerifier(workspace)
    private val terminalClient = NativeTerminalClient(
        context = context,
        runtimeConfig = runtimeConfig,
        openApp = { packageName -> androidActions.openApp(packageName) },
        terminalPowerMode = { terminalPowerMode() },
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val terminalRecovery = NativeTerminalRecoveryController(
        runtimeConfig = runtimeConfig,
        terminalStatus = { terminalClient.status() },
        recoverTerminalBackend = { arguments -> terminalClient.recover(arguments) },
        terminalPowerMode = { terminalPowerMode() },
        outputState = { output -> stepEvaluator.state(output) }
    )
    private val mcpClient = NativeMcpClient(runtimeConfig)
    private val healthCoordinator = NativeHealthCoordinator(
        runtimeConfig = runtimeConfig,
        workspace = workspace,
        profile = profile,
        apiKey = { apiKey() },
        logSummary = { AgentLogStore.summary(context) },
        recentLogs = { limit, minLevel -> AgentLogStore.recent(context, limit, minLevel) },
        accessibilityStatus = { AccessibilityState.status(context) },
        workspaceInfo = { runCatching { workspace.info() }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "") } },
        diagnoseTerminal = { terminalClient.diagnose() },
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val remoteRuntime = NativeRemoteRuntimeFacade(
        context = context,
        runtimeConfig = runtimeConfig,
        terminalClient = terminalClient,
        terminalRecovery = terminalRecovery,
        mcpClient = mcpClient,
        sshBridge = sshBridge,
        storageAccess = storageAccess,
        healthCoordinator = healthCoordinator,
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val statusController = NativeStatusController(
        runtimeConfig = runtimeConfig,
        profile = profile,
        terminalRecovery = terminalRecovery,
        mcpClient = mcpClient,
        sshBridge = sshBridge,
        toolsets = toolsets,
        sessionStore = sessionStore,
        contextManager = contextManager,
        docsController = docsController,
        logSummary = { AgentLogStore.summary(context) },
        apiKey = { apiKey() }
    )
    private val pcBridgeController = NativePcBridgeController(
        context = context,
        runtimeConfig = runtimeConfig,
        sshBridge = sshBridge,
        mcpClient = mcpClient,
        terminalRecovery = terminalRecovery,
        storageAccess = storageAccess,
        setMcpConfig = { enabled, baseUrl, authToken -> setMcpConfig(enabled, baseUrl, authToken) },
        openApp = { packageName -> androidActions.openApp(packageName) },
        terminalRun = { command, cwd, timeout -> remoteRuntime.terminalRun(command, cwd, timeout) },
        terminalScript = { arguments -> remoteRuntime.terminalScript(arguments) }
    )
    private val stepEvents = NativeToolStepEvents { level, component, message, details ->
        log(level, component, message, details)
    }
    private val taskLoopEngine = NativeTaskLoopEngine(
        modelRequester = modelRequester,
        stepEvaluator = stepEvaluator,
        stopController = stopController,
        stepEvents = stepEvents,
        executeToolWithAutoRecovery = { name, arguments, actionsApproved, taskPlan, sessionId ->
            executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, sessionId)
        },
        verifyToolResult = { name, arguments, output ->
            toolExecutionManager.verify(name, arguments, output)
        },
        resolveToolset = { sessionId ->
            if (sessionId == null) emptySet() else toolsets.resolve(sessionId)
        },
        buildUserStopBlock = { sessionId, phase, modelResponse ->
            stopFlowController.buildUserStopBlock(sessionId, phase, modelResponse)
        },
        userStopFinalText = { blocker -> stopFlowController.userStopFinalText(blocker) },
        loopGuardFinalText = { blocker -> stopFlowController.loopGuardFinalText(blocker) }
    )
    private val chatController = NativeChatController(
        runtimeConfig = runtimeConfig,
        systemPrompt = profile.systemPrompt,
        toolsets = toolsets,
        memory = memory,
        contextManager = contextManager,
        taskLoopEngine = taskLoopEngine,
        taskMemoryCoordinator = taskMemoryCoordinator,
        stopFlowController = stopFlowController,
        sessionStore = sessionStore,
        apiKey = { apiKey() },
        syncOfficialDocsOnce = { syncOfficialDocsOnce() },
        normalizeVisibleChinese = { text -> normalizeVisibleChinese(text) },
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val diagnosticToolRunner = NativeDiagnosticToolRunner(
        stepEvaluator = stepEvaluator,
        executeToolDirect = { name, arguments, actionsApproved, taskPlan, sessionId ->
            toolExecutor.execute(name, arguments, actionsApproved, taskPlan, sessionId)
        },
        executeToolWithAutoRecovery = { name, arguments, actionsApproved, taskPlan, sessionId ->
            executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, sessionId)
        }
    )
    private val toolDispatchers = NativeToolDispatchersFactory.create(
        context = context,
        runtimeConfig = runtimeConfig,
        workspace = workspace,
        plugins = plugins,
        memory = memory,
        androidIntentTools = androidIntentTools,
        sshBridge = sshBridge,
        mcpClient = mcpClient,
        terminalClient = terminalClient,
        pcBridgeController = pcBridgeController,
        storageAccess = storageAccess,
        taskPlanController = taskPlanController,
        taskMemoryCoordinator = taskMemoryCoordinator,
        toolsets = toolsets,
        healthCoordinator = healthCoordinator,
        screenActionTools = NativeCoreToolSets.screenActionTools,
        terminalDelegationTools = NativeCoreToolSets.terminalDelegationTools,
        executeToolWithAutoRecovery = { name, arguments, actionsApproved, taskPlan, sessionId ->
            executeToolWithAutoRecovery(name, arguments, actionsApproved, taskPlan, sessionId)
        },
        evaluateState = { output -> stepEvaluator.state(output) },
        openApp = { packageName -> androidActions.openApp(packageName) },
        openUrl = { url -> androidActions.openUrl(url) },
        terminalPowerMode = { remoteRuntime.terminalPowerMode() },
        terminalStatus = { remoteRuntime.terminalStatus() },
        terminalTools = { remoteRuntime.terminalTools() },
        terminalChat = { message -> remoteRuntime.terminalChat(message) },
        terminalRun = { command, cwd, timeout -> remoteRuntime.terminalRun(command, cwd, timeout) },
        terminalScript = { arguments -> remoteRuntime.terminalScript(arguments) },
        terminalTaskStatus = { taskId, maxOutputChars -> remoteRuntime.terminalTaskStatus(taskId, maxOutputChars) },
        terminalTaskCancel = { taskId -> remoteRuntime.terminalTaskCancel(taskId) },
        recoverTerminalBackend = { arguments -> remoteRuntime.recoverTerminalBackend(arguments) },
        diagnoseTerminal = { remoteRuntime.diagnoseTerminal() },
        systemLogs = { arguments -> systemLogs(arguments) },
        docsIndex = { docsController.index() },
        docsRead = { arguments -> docsController.read(arguments) },
        docsSearch = { arguments -> docsController.search(arguments) },
        docsSync = { docsController.sync() },
        apiKey = { apiKey() },
        log = { level, component, message, details -> log(level, component, message, details) }
    )
    private val toolExecutor = toolDispatchers.executor
    private val toolExecutionManager = NativeToolExecutionManager(
        stepEvaluator = stepEvaluator,
        terminalRecovery = terminalRecovery,
        verifyToolResult = { name, arguments, output ->
            toolVerifier.verify(name, arguments, output)
        },
        executeTool = { name, arguments, actionsApproved, taskPlan, sessionId ->
            toolExecutor.execute(name, arguments, actionsApproved, taskPlan, sessionId)
        },
        shouldAutoRecover = { name, output, actionsApproved ->
            terminalRecovery.shouldAutoRecover(name, output, actionsApproved)
        },
        terminalFuseOpen = { name -> terminalRecovery.fuseOpen(name) },
        terminalPowerMode = { remoteRuntime.terminalPowerMode() },
        isAutoRecoverable = { name -> NativeToolRegistry.isAutoRecoverable(name) },
        diagnoseTerminal = { remoteRuntime.diagnoseTerminal() },
        recoverTerminalBackend = { arguments -> remoteRuntime.recoverTerminalBackend(arguments) },
        recoverPcBridge = { arguments -> pcBridgeController.recover(arguments) },
        log = { level, component, message, details -> log(level, component, message, details) }
    )

    private fun executeToolWithAutoRecovery(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        return toolExecutionManager.executeWithAutoRecovery(
            name,
            arguments,
            actionsApproved,
            taskPlan,
            sessionId
        )
    }

    fun setApiKey(apiKey: String) {
        prefs.edit().putString("api_key", apiKey.trim()).apply()
    }

    fun config(): JSONObject {
        return runtimeConfig.configJson()
    }

    fun permissionMode(): String {
        return runtimeConfig.permissionMode()
    }

    fun setPermissionMode(mode: String) {
        runtimeConfig.setPermissionMode(mode)
    }

    fun setTerminalConfig(enabled: Boolean, baseUrl: String) {
        runtimeConfig.setTerminalConfig(enabled, baseUrl)
    }

    fun setMcpConfig(enabled: Boolean, baseUrl: String, authToken: String = "") {
        val beforeEnabled = runtimeConfig.mcpEnabled()
        val beforeBase = runtimeConfig.mcpBaseUrl()
        val beforeToken = runtimeConfig.mcpAuthToken()
        runtimeConfig.setMcpConfig(enabled, baseUrl, authToken)
        val changed = beforeEnabled != enabled ||
                beforeBase != runtimeConfig.mcpBaseUrl() ||
                beforeToken != runtimeConfig.mcpAuthToken()
        if (changed) {
            mcpClient.clearSessions()
        }
    }

    fun setSshConfig(
        enabled: Boolean,
        host: String,
        port: Int,
        user: String,
        keyPath: String = "",
        passphrase: String = "",
        connectTimeoutMs: Int = runtimeConfig.sshConnectTimeoutMs(),
        commandTimeoutMs: Int = runtimeConfig.sshCommandTimeoutMs()
    ) {
        runtimeConfig.setSshConfig(enabled, host, port, user, keyPath, passphrase, connectTimeoutMs, commandTimeoutMs)
    }

    fun setMaxToolRounds(value: Int) {
        runtimeConfig.setMaxToolRounds(value)
    }

    fun terminalStatusForUi(): JSONObject {
        return remoteRuntime.terminalStatus()
    }

    fun terminalHealthForUi(autoRecover: Boolean = false, force: Boolean = true): JSONObject {
        return remoteRuntime.terminalHealthForUi(autoRecover = autoRecover, force = force)
    }

    fun mcpAuthToken(): String {
        return remoteRuntime.mcpAuthToken()
    }

    fun mcpStatusForUi(): JSONObject {
        return remoteRuntime.mcpStatusForUi()
    }

    fun mcpToolsForUi(search: String = ""): JSONObject {
        return remoteRuntime.mcpToolsForUi(search)
    }

    fun sshStatusForUi(): JSONObject {
        return remoteRuntime.sshStatusForUi()
    }

    fun sshConnectForUi(arguments: JSONObject = JSONObject()): JSONObject {
        return remoteRuntime.sshConnectForUi(arguments)
    }

    fun sshDiagnoseForUi(arguments: JSONObject = JSONObject()): JSONObject {
        return remoteRuntime.sshDiagnoseForUi(arguments)
    }

    fun sshSelectHostForUi(arguments: JSONObject = JSONObject()): JSONObject {
        return remoteRuntime.sshSelectHostForUi(arguments)
    }

    fun sshDisconnectForUi(): JSONObject {
        return remoteRuntime.sshDisconnectForUi()
    }

    fun sshPassphraseForUi(): String {
        return remoteRuntime.sshPassphraseForUi()
    }

    fun sshRunForUi(arguments: JSONObject): JSONObject {
        return remoteRuntime.sshRun(arguments)
    }

    fun sshPushForUi(arguments: JSONObject): JSONObject {
        return remoteRuntime.sshFilePush(arguments)
    }

    fun sshPullForUi(arguments: JSONObject): JSONObject {
        return remoteRuntime.sshFilePull(arguments)
    }

    fun storagePermissionStatusForUi(): JSONObject {
        return remoteRuntime.storagePermissionStatusForUi()
    }

    fun openStoragePermissionSettingsForUi(): JSONObject {
        return remoteRuntime.openStoragePermissionSettingsForUi()
    }

    fun systemLogsForUi(limit: Int = 80): JSONObject {
        return systemLogs(JSONObject().put("limit", limit))
    }

    fun latestFailureAnalysisForUi(maxBytes: Int = 30000): JSONObject {
        return workspace.latestFailureAnalysis(maxBytes)
    }

    fun requestStop(sessionHint: String?): JSONObject {
        val sessionId = sessionHint ?: sessionStore.currentSessionId()
            ?: return JSONObject().put("ok", false).put("error", "no active session")
        return stopController.request(sessionId)
    }

    fun compactCurrentSessionForUi(sessionId: String?): JSONObject {
        val activeSessionId = sessionId ?: sessionStore.currentSessionId()
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "no active session")
        val messages = sessionStore.loadMessages(activeSessionId)
        val result = contextManager.compactMessagesIfNeeded(activeSessionId, messages, force = true)
        if (result.optBoolean("compacted", false)) {
            sessionStore.saveMessages(activeSessionId, messages)
        }
        return result
            .put("ok", result.optBoolean("compacted", false))
            .put("context", contextManager.contextStats(messages))
    }

    fun docsIndexForUi(): JSONObject {
        return docsController.index()
    }

    fun eventsForUi(afterSeq: Long = 0L, limit: Int = 50): JSONObject {
        return AgentEventStore.recent(afterSeq, limit)
    }

    fun executeNativeToolForDiagnostics(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean = false
    ): JSONObject {
        return diagnosticToolRunner.execute(name, arguments, actionsApproved)
    }

    fun reconnectForUi(): JSONObject {
        return remoteRuntime.reconnectForUi()
    }

    fun newSession(): String = sessionStore.newSession()

    fun status(sessionId: String?): JSONObject {
        syncOfficialDocsOnce()
        return statusController.status(sessionId)
    }

    fun chat(message: String, requestedSessionId: String?, actionsApproved: Boolean = false): JSONObject {
        return chatController.chat(message, requestedSessionId, actionsApproved)
    }

    private fun systemLogs(arguments: JSONObject): JSONObject {
        val limit = arguments.optInt("limit", 80).coerceIn(1, 200)
        val minLevel = arguments.optString("min_level", "debug")
        return JSONObject()
            .put("ok", true)
            .put("summary", AgentLogStore.summary(context))
            .put("entries", AgentLogStore.recent(context, limit, minLevel))
    }

    private fun terminalPowerMode(): Boolean {
        val mode = runtimeConfig.permissionMode()
        return mode == AgentRuntimeConfig.MODE_DANGER || mode == AgentRuntimeConfig.MODE_DEVELOPER
    }

    private fun apiKey(): String? {
        return prefs.getString("api_key", null)
    }

    private fun normalizeVisibleChinese(text: String): String {
        return text
    }

    private fun syncOfficialDocsOnce() {
        docsController.syncOnce()
    }

    private fun log(level: String, component: String, message: String, details: JSONObject? = null) {
        AgentLogStore.record(context, level, component, message, details)
    }
}

