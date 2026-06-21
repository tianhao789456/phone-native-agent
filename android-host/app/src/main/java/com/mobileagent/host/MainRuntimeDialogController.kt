package com.mobileagent.host

import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainRuntimeDialogController(
    private val core: NativeAgentCore,
    private val ui: Handler,
    private val addMessage: (role: String, text: String, detail: String?) -> Unit,
    private val refreshStatus: () -> Unit,
) {
    fun showMcpStatus() {
        addMessage("系统", "正在检查 MCP 连接...", null)
        runUiTask("mobile-agent-mcp-status", { core.mcpStatusForUi() }) {
            addMessage("系统", "MCP 状态：\n${it.toString(2)}", null)
        }
    }

    fun showMcpTools(search: String = "") {
        addMessage("系统", "正在读取 MCP 工具...", null)
        runUiTask("mobile-agent-mcp-tools", { core.mcpToolsForUi(search.trim()) }) { tools ->
            val toolCount = tools.optInt("tool_count", tools.optJSONArray("tools")?.length() ?: 0)
            val list = tools.optJSONArray("tools") ?: JSONArray()
            val lines = mutableListOf<String>()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                lines.add("- ${item.optString("name")} ${item.optString("description")}".trim())
            }
            addMessage("系统", "MCP 工具列表 (${toolCount})：\n" + lines.joinToString("\n"), null)
        }
    }

    fun showSshStatus() {
        addMessage("系统", "正在检查 SSH 状态...", null)
        runUiTask("mobile-agent-ssh-status", { core.sshStatusForUi() }) {
            addMessage("系统", "SSH 状态：\n${it.toString(2)}", null)
        }
    }

    fun showSshConnect() {
        addMessage("系统", "正在连接 SSH...", null)
        runUiTask("mobile-agent-ssh-connect", { core.sshConnectForUi() }) {
            addMessage("系统", "SSH 连接结果：\n${it.toString(2)}", null)
        }
    }

    fun showSshDiagnose() {
        addMessage("系统", "正在诊断 SSH 网络...", null)
        runUiTask("mobile-agent-ssh-diagnose", { core.sshDiagnoseForUi() }) {
            addMessage("系统", "SSH 诊断结果：\n${it.toString(2)}", null)
        }
    }

    fun showSshSelectHost(candidates: String = "") {
        addMessage("系统", "正在自动选择 SSH 地址...", null)
        runUiTask("mobile-agent-ssh-select", {
            val args = JSONObject()
            if (candidates.isNotBlank()) {
                val array = JSONArray()
                candidates.split(Regex("[,;\\s]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { array.put(it) }
                args.put("hosts", array)
            }
            args.put("apply", true)
            core.sshSelectHostForUi(args)
        }) {
            addMessage("系统", "SSH 地址选择结果：\n${it.toString(2)}", null)
        }
    }

    fun showSshDisconnect() {
        addMessage("系统", "正在断开 SSH...", null)
        runUiTask("mobile-agent-ssh-disconnect", { core.sshDisconnectForUi() }) {
            addMessage("系统", "SSH 已断开：\n${it.toString(2)}", null)
        }
    }

    fun showTerminalStatus() {
        addMessage("系统", "正在检查终端连接...", null)
        runUiTask("mobile-agent-terminal-status", { core.terminalStatusForUi() }) {
            addMessage("系统", "终端接口状态：\n${it.toString(2)}", null)
        }
    }

    fun showTerminalHealth(autoRecover: Boolean) {
        addMessage("系统", if (autoRecover) "正在检测终端并尝试恢复..." else "正在检测终端健康...", null)
        runUiTask("mobile-agent-terminal-health", { core.terminalHealthForUi(autoRecover) }) { resultJson ->
            val state = resultJson.optString("state", "-")
            addMessage("系统", "终端 $state", resultJson.toString(2))
        }
    }

    fun showSystemLogs() {
        addMessage("系统", "正在读取系统日志...", null)
        runUiTask("mobile-agent-system-logs", { core.systemLogsForUi(80) }, refreshAfterSuccess = false) {
            addMessage("系统", "系统日志 ${it.optJSONObject("summary")?.optInt("entries", 0) ?: 0} 条", it.toString(2))
        }
    }

    private fun runUiTask(
        name: String,
        action: () -> JSONObject,
        refreshAfterSuccess: Boolean = true,
        onSuccess: (JSONObject) -> Unit,
    ) {
        thread(name = name) {
            val result = runCatching(action)
            ui.post {
                result
                    .onSuccess {
                        onSuccess(it)
                        if (refreshAfterSuccess) refreshStatus()
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName, null) }
            }
        }
    }
}
