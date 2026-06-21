package com.mobileagent.host

import org.json.JSONObject

class NativeToolExecutor(
    private val permissionMode: () -> String,
    private val terminalPowerMode: () -> Boolean,
    private val screenActionTools: Set<String>,
    private val terminalDelegationTools: Set<String>,
    private val planningExecute: (String, JSONObject, JSONObject) -> JSONObject,
    private val memoryExecute: (String, JSONObject) -> JSONObject,
    private val diagnosticsExecute: (String, JSONObject, String?) -> JSONObject,
    private val terminalExecute: (String, JSONObject) -> JSONObject,
    private val webExecute: (String, JSONObject, Boolean) -> JSONObject,
    private val workspaceExecute: (String, JSONObject) -> JSONObject,
    private val skillExecute: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val pluginExecute: (String, JSONObject, Boolean, JSONObject, String?) -> JSONObject,
    private val phoneExecute: (String, JSONObject) -> JSONObject,
    private val mcpExecute: (String, JSONObject) -> JSONObject,
    private val pcBridgeExecute: (String, JSONObject) -> JSONObject,
    private val sshExecute: (String, JSONObject) -> JSONObject,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun execute(
        name: String,
        arguments: JSONObject,
        actionsApproved: Boolean,
        taskPlan: JSONObject,
        sessionId: String?
    ): JSONObject {
        permissionGate(name, actionsApproved)?.let { return it }
        return try {
            val result = when (name) {
                in NativePlanningToolDispatcher.TOOL_NAMES -> planningExecute(name, arguments, taskPlan)
                in NativeMemoryToolDispatcher.TOOL_NAMES -> memoryExecute(name, arguments)
                in NativeDiagnosticsToolDispatcher.TOOL_NAMES -> diagnosticsExecute(name, arguments, sessionId)
                in NativeTerminalToolDispatcher.TOOL_NAMES -> terminalExecute(name, arguments)
                in NativeWebToolDispatcher.TOOL_NAMES -> webExecute(name, arguments, actionsApproved)
                in NativeWorkspaceToolDispatcher.TOOL_NAMES -> workspaceExecute(name, arguments)
                in NativeSkillToolDispatcher.TOOL_NAMES -> skillExecute(name, arguments, actionsApproved, taskPlan, sessionId)
                in NativePluginToolDispatcher.TOOL_NAMES -> pluginExecute(name, arguments, actionsApproved, taskPlan, sessionId)
                in NativePhoneToolDispatcher.TOOL_NAMES -> phoneExecute(name, arguments)
                in NativeMcpToolDispatcher.TOOL_NAMES -> mcpExecute(name, arguments)
                in NativePcBridgeToolDispatcher.TOOL_NAMES -> pcBridgeExecute(name, arguments)
                in NativeSshToolDispatcher.TOOL_NAMES -> sshExecute(name, arguments)
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }
            JSONObject().put("ok", true).put("result", result)
        } catch (exc: Exception) {
            log(
                "error",
                "tool",
                "tool execution failed",
                JSONObject()
                    .put("tool", name)
                    .put("error_type", exc.javaClass.simpleName)
                    .put("error", exc.message ?: "")
                    .put("arguments", arguments.toString().take(1000))
            )
            JSONObject().put("ok", false).put("error", "${exc.javaClass.simpleName}: ${exc.message}")
        }
    }

    private fun permissionGate(name: String, actionsApproved: Boolean): JSONObject? {
        val mode = permissionMode()
        val descriptor = NativeToolRegistry.descriptor(name)
        val toolMetadata = descriptor?.metadata() ?: JSONObject().put("name", name).put("registered", false)
        val access = descriptor?.access ?: NativeToolAccess.READ_ONLY
        if (mode == AgentRuntimeConfig.MODE_DEVELOPER) return null
        if (access == NativeToolAccess.READ_ONLY) return null

        if (access == NativeToolAccess.SCREEN_ACTION || name in screenActionTools) {
            if (mode == AgentRuntimeConfig.MODE_SAFE) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "安全模式不允许直接操作屏幕，请切换到 ask/danger/developer 模式。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "屏幕操作需要确认后执行：$name")
            }
            return null
        }

        if (access == NativeToolAccess.TERMINAL_DELEGATION || name in terminalDelegationTools) {
            if (!terminalPowerMode()) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_permission", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "终端/电脑委托工具需要 danger 或 developer 模式。")
            }
            if (!actionsApproved) {
                return JSONObject()
                    .put("ok", false)
                    .put("needs_confirmation", true)
                    .put("permission_mode", mode)
                    .put("tool", toolMetadata)
                    .put("error", "终端/电脑委托工具需要确认后执行：$name")
            }
        }
        return null
    }
}
