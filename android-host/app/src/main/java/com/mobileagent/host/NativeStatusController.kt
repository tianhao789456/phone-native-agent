package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeStatusController(
    private val runtimeConfig: AgentRuntimeConfig,
    private val profile: NativeAgentProfile,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val mcpClient: NativeMcpClient,
    private val sshBridge: SshBridge,
    private val toolsets: NativeToolsetController,
    private val sessionStore: NativeSessionStore,
    private val contextManager: NativeContextManager,
    private val docsController: NativeDocsController,
    private val logSummary: () -> JSONObject,
    private val apiKey: () -> String?
) {
    fun status(sessionId: String?): JSONObject {
        val activeSessionId = sessionId ?: sessionStore.currentSessionId()
        val activeToolset = toolsets.resolve(activeSessionId)
        val messages = if (activeSessionId == null) JSONArray() else sessionStore.loadMessages(activeSessionId)
        return JSONObject()
            .put("ok", true)
            .put("runtime", "android-native")
            .put("model", profile.MODEL)
            .put("permission_mode", runtimeConfig.permissionMode())
            .put("permission_modes", runtimeConfig.permissionModesJson())
            .put("terminal", runtimeConfig.terminalConfigJson())
            .put("terminal_runtime", terminalRecovery.runtimeStatus(autoRecover = true, force = false))
            .put("mcp", runtimeConfig.mcpConfigJson())
            .put("mcp_runtime", mcpClient.runtimeStatus(force = false))
            .put("ssh", runtimeConfig.sshConfigJson())
            .put("ssh_runtime", sshBridge.status())
            .put("config", runtimeConfig.configJson())
            .put("tools", JSONArray(activeToolset.toList()))
            .put("tool_registry", NativeToolRegistry.indexMetadata(activeToolset))
            .put(
                "toolset",
                JSONObject()
                    .put("active", JSONArray(activeToolset.toList()))
                    .put("available_groups", NativeToolRegistry.availableGroups())
                    .put("baseline", JSONArray(NativeToolRegistry.baselineTools().toList()))
            )
            .put("docs", docsController.index())
            .put(
                "session",
                JSONObject()
                    .put("id", activeSessionId)
                    .put("messages", messages.length())
                    .put("traces", 0)
                    .put("updated_at", sessionStore.updatedAt())
            )
            .put("context", contextManager.contextStats(messages))
            .put("usage", contextManager.latestUsage(activeSessionId))
            .put("logs", logSummary())
            .put("api_key_set", !apiKey().isNullOrBlank())
    }
}
