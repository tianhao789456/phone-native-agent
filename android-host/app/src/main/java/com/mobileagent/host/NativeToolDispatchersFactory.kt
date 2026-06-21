package com.mobileagent.host

import android.content.Context
import org.json.JSONObject

data class NativeToolDispatchers(
    val executor: NativeToolExecutor
)

object NativeToolDispatchersFactory {
    fun create(
        context: Context,
        runtimeConfig: AgentRuntimeConfig,
        workspace: MobileWorkspace,
        plugins: MobilePluginRegistry,
        memory: MobileMemoryStore,
        androidIntentTools: AndroidIntentTools,
        sshBridge: SshBridge,
        mcpClient: NativeMcpClient,
        terminalClient: NativeTerminalClient,
        pcBridgeController: NativePcBridgeController,
        storageAccess: NativeStorageAccess,
        taskPlanController: NativeTaskPlanController,
        taskMemoryCoordinator: NativeTaskMemoryCoordinator,
        toolsets: NativeToolsetController,
        healthCoordinator: NativeHealthCoordinator,
        screenActionTools: Set<String>,
        terminalDelegationTools: Set<String>,
        executeToolWithAutoRecovery: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
        evaluateState: (JSONObject) -> String,
        openApp: (String) -> JSONObject,
        openUrl: (String) -> JSONObject,
        terminalPowerMode: () -> Boolean,
        terminalStatus: () -> JSONObject,
        terminalTools: () -> JSONObject,
        terminalChat: (String) -> JSONObject,
        terminalRun: (String, String, Int) -> JSONObject,
        terminalScript: (JSONObject) -> JSONObject,
        terminalTaskStatus: (String, Int) -> JSONObject,
        terminalTaskCancel: (String) -> JSONObject,
        recoverTerminalBackend: (JSONObject) -> JSONObject,
        diagnoseTerminal: () -> JSONObject,
        systemLogs: (JSONObject) -> JSONObject,
        docsIndex: () -> JSONObject,
        docsRead: (JSONObject) -> JSONObject,
        docsSearch: (JSONObject) -> JSONObject,
        docsSync: () -> JSONObject,
        apiKey: () -> String?,
        log: (String, String, String, JSONObject) -> Unit
    ): NativeToolDispatchers {
        val logRequired: (String, String, String, JSONObject) -> Unit = log
        val logOptional: (String, String, String, JSONObject?) -> Unit = { level, component, message, details ->
            log(level, component, message, details ?: JSONObject())
        }

        val pluginWorkflowController = NativePluginWorkflowController(
            plugins = plugins,
            executePluginTool = executeToolWithAutoRecovery,
            isPluginToolAllowed = { name ->
                if (name.isBlank()) return@NativePluginWorkflowController false
                if (name == "plugin_run") return@NativePluginWorkflowController false
                if (name.startsWith("plugin_")) return@NativePluginWorkflowController false
                val descriptor = NativeToolRegistry.descriptor(name)
                descriptor?.access == NativeToolAccess.READ_ONLY ||
                    runtimeConfig.permissionMode() == AgentRuntimeConfig.MODE_DEVELOPER
            },
            getDescriptor = { name -> NativeToolRegistry.descriptor(name) },
            evaluateState = evaluateState,
            log = logRequired
        )

        val pluginToolDispatcher = NativePluginToolDispatcher(plugins) { arguments, actionsApproved, taskPlan, sessionId ->
            pluginWorkflowController.runPluginWorkflow(arguments, actionsApproved, taskPlan, sessionId)
        }
        val skillToolDispatcher = NativeSkillToolDispatcher(
            skills = MobileSkillRegistry(plugins, memory),
            runPluginWorkflow = { arguments, actionsApproved, taskPlan, sessionId ->
                pluginWorkflowController.runPluginWorkflow(arguments, actionsApproved, taskPlan, sessionId)
            }
        )
        val phoneToolDispatcher = NativePhoneToolDispatcher(
            context = context,
            intentTools = androidIntentTools,
            openApp = openApp,
            openUrl = openUrl
        )
        val sshToolDispatcher = NativeSshToolDispatcher(
            sshBridge = sshBridge,
            connect = { arguments -> sshBridge.connect(arguments) },
            run = { arguments ->
                sshBridge.run(
                    arguments.optString("command"),
                    arguments.optString("cwd", ""),
                    arguments.optString("shell", "powershell"),
                    arguments.optInt("timeout_ms", runtimeConfig.sshCommandTimeoutMs())
                )
            },
            push = { arguments ->
                sshBridge.push(
                    arguments.optString("local_path"),
                    arguments.optString("remote_path"),
                    arguments.optBoolean("overwrite", true)
                )
            },
            pull = { arguments ->
                sshBridge.pull(
                    arguments.optString("remote_path"),
                    arguments.optString("local_path", ""),
                    arguments.optBoolean("overwrite", true)
                )
            },
            pcFileWorkflow = { arguments -> pcBridgeController.fileWorkflow(arguments) },
            storagePermissionStatus = { storageAccess.status() },
            openStoragePermissionSettings = { storageAccess.openSettings() }
        )
        val mcpToolDispatcher = NativeMcpToolDispatcher(
            servers = { mcpClient.servers() },
            status = { server -> mcpClient.status(server) },
            tools = { search, includeSchema, server -> mcpClient.tools(search, includeSchema, server) },
            toolInfo = { tool, server -> mcpClient.toolInfo(tool, server) },
            call = { tool, callArguments, timeoutMs, server -> mcpClient.call(tool, callArguments, timeoutMs, server) },
            configure = { arguments -> mcpClient.configure(arguments) }
        )
        val terminalToolDispatcher = NativeTerminalToolDispatcher(
            status = terminalStatus,
            tools = terminalTools,
            chat = terminalChat,
            run = terminalRun,
            script = terminalScript,
            taskStatus = terminalTaskStatus,
            taskCancel = terminalTaskCancel,
            recover = recoverTerminalBackend,
            diagnose = diagnoseTerminal
        )
        val planningToolDispatcher = NativePlanningToolDispatcher(
            workspace = workspace,
            updatePlan = { plan, arguments -> taskPlanController.updateTaskPlan(plan, arguments) }
        )
        val workspaceToolDispatcher = NativeWorkspaceToolDispatcher(workspace)
        val memoryToolDispatcher = NativeMemoryToolDispatcher(
            memory = memory,
            learningStop = { arguments -> taskMemoryCoordinator.learningStopWithModel(arguments) { apiKey() } }
        )
        val webClient = NativeWebClient(
            runtimeConfig = runtimeConfig,
            terminalScript = terminalScript,
            log = logOptional
        )
        val webToolDispatcher = NativeWebToolDispatcher(
            search = { query, maxResults -> webClient.search(query, maxResults) },
            extract = { arguments, actionsApproved -> webClient.extract(arguments, actionsApproved) },
            get = { arguments -> webClient.get(arguments) },
            post = { arguments -> webClient.post(arguments) }
        )
        val pcBridgeToolDispatcher = NativePcBridgeToolDispatcher(
            healthCheck = { arguments -> pcBridgeController.healthCheck(arguments) },
            status = { arguments -> pcBridgeController.status(arguments) },
            recover = { arguments -> pcBridgeController.recover(arguments) },
            tailscalePreflight = { arguments -> pcBridgeController.tailscalePreflight(arguments) },
            tailscaleSshDiagnose = { arguments -> pcBridgeController.tailscaleSshDiagnose(arguments) }
        )
        val diagnosticsToolDispatcher = NativeDiagnosticsToolDispatcher(
            toolsetRequest = { sessionId, arguments -> toolsets.request(sessionId, arguments) },
            toolRegistry = { arguments, sessionId -> toolsets.registry(arguments, sessionId) },
            toolInfo = { arguments -> toolsets.info(arguments) },
            docsIndex = docsIndex,
            docsRead = docsRead,
            docsSearch = docsSearch,
            docsSync = docsSync,
            systemLogs = systemLogs,
            selfHealthCheck = { healthCoordinator.selfHealthCheck() }
        )

        return NativeToolDispatchers(
            executor = NativeToolExecutor(
                permissionMode = { runtimeConfig.permissionMode() },
                terminalPowerMode = terminalPowerMode,
                screenActionTools = screenActionTools,
                terminalDelegationTools = terminalDelegationTools,
                planningExecute = { name, arguments, taskPlan -> planningToolDispatcher.execute(name, arguments, taskPlan) },
                memoryExecute = { name, arguments -> memoryToolDispatcher.execute(name, arguments) },
                diagnosticsExecute = { name, arguments, sessionId -> diagnosticsToolDispatcher.execute(name, arguments, sessionId) },
                terminalExecute = { name, arguments -> terminalToolDispatcher.execute(name, arguments) },
                webExecute = { name, arguments, actionsApproved -> webToolDispatcher.execute(name, arguments, actionsApproved) },
                workspaceExecute = { name, arguments -> workspaceToolDispatcher.execute(name, arguments) },
                skillExecute = { name, arguments, actionsApproved, taskPlan, sessionId ->
                    skillToolDispatcher.execute(name, arguments, actionsApproved, taskPlan, sessionId)
                },
                pluginExecute = { name, arguments, actionsApproved, taskPlan, sessionId ->
                    pluginToolDispatcher.execute(name, arguments, actionsApproved, taskPlan, sessionId)
                },
                phoneExecute = { name, arguments -> phoneToolDispatcher.execute(name, arguments) },
                mcpExecute = { name, arguments -> mcpToolDispatcher.execute(name, arguments) },
                pcBridgeExecute = { name, arguments -> pcBridgeToolDispatcher.execute(name, arguments) },
                sshExecute = { name, arguments -> sshToolDispatcher.execute(name, arguments) },
                log = logRequired
            )
        )
    }
}
