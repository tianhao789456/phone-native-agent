package com.mobileagent.host

import org.json.JSONObject

class MainSettingsCommandController(
    private val core: NativeAgentCore,
    private val addMessage: (role: String, text: String) -> Unit,
    private val refreshStatus: () -> Unit,
    private val confirmHighPowerMode: (mode: String, onConfirmed: () -> Unit) -> Unit,
    private val showTerminalStatus: () -> Unit,
    private val showTerminalHealth: (autoRecover: Boolean) -> Unit,
    private val showMcpStatus: () -> Unit,
    private val showMcpTools: (search: String) -> Unit,
    private val showSshStatus: () -> Unit,
    private val showSshConnect: () -> Unit,
    private val showSshDiagnose: () -> Unit,
    private val showSshSelectHost: (candidates: String) -> Unit,
    private val showSshDisconnect: () -> Unit,
) {
    fun setMaxToolRoundsFromCommand(value: String) {
        try {
            val rounds = value.toInt()
            core.setMaxToolRounds(rounds)
            addMessage("系统", "工具调用上限设置为 ${rounds.coerceIn(1, AgentRuntimeConfig.MAX_TOOL_ROUNDS_LIMIT)}")
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", "参数不合法: rounds 取值范围 1-${AgentRuntimeConfig.MAX_TOOL_ROUNDS_LIMIT}")
        }
    }

    fun setPermissionModeFromCommand(mode: String) {
        try {
            val normalized = MainLocalCommandParser.normalizePermissionMode(mode)
            if (MainLocalCommandParser.isHighPowerMode(normalized) && core.permissionMode() != normalized) {
                confirmHighPowerMode(normalized) { applyPermissionMode(normalized) }
            } else {
                applyPermissionMode(normalized)
            }
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    fun setTerminalFromCommand(value: String) {
        try {
            val current = core.config().optJSONObject("terminal") ?: JSONObject()
            when (value.lowercase()) {
                "on", "enable", "enabled", "开启", "open" -> {
                    core.setTerminalConfig(true, current.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL))
                    addMessage("系统", "终端接口已开启")
                }
                "off", "disable", "disabled", "关闭", "close" -> {
                    core.setTerminalConfig(false, current.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL))
                    addMessage("系统", "终端接口已关闭")
                }
                "status" -> showTerminalStatus()
                "health" -> showTerminalHealth(false)
                "recover" -> showTerminalHealth(true)
                else -> {
                    core.setTerminalConfig(true, value)
                    addMessage("系统", "终端服务地址设置为 $value")
                }
            }
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    fun setMcpFromCommand(value: String) {
        try {
            val tokens = value.trim().split(Regex("\\s+"), limit = 2)
            val config = core.config()
            val currentMcp = config.optJSONObject("mcp") ?: JSONObject()
            val currentUrl = currentMcp.optString("base_url", AgentRuntimeConfig.DEFAULT_MCP_BASE_URL)
            val currentToken = core.mcpAuthToken()
            if (tokens.isEmpty() || value.isBlank()) {
                addMessage("系统", "用法：mcp on|off|status|tools [关键词]|token <token>|<baseUrl>")
                return
            }
            val first = tokens[0].lowercase()
            val rest = if (tokens.size > 1) tokens[1] else ""
            when (first) {
                "on", "enable", "enabled", "开启", "open" -> {
                    core.setMcpConfig(true, currentUrl, currentToken)
                    addMessage("系统", "MCP 已开启")
                }
                "off", "disable", "disabled", "关闭", "close" -> {
                    core.setMcpConfig(false, currentUrl, currentToken)
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
                        core.setMcpConfig(currentMcp.optBoolean("enabled"), currentUrl, rest)
                        addMessage("系统", "MCP token 已更新")
                    }
                }
                "url", "base", "base_url", "endpoint", "endpoint_url", "server", "address" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 MCP 地址，示例：mcp url http://192.168.1.10:8931")
                    } else {
                        core.setMcpConfig(currentMcp.optBoolean("enabled", true), rest, currentToken)
                        addMessage("系统", "MCP 地址已设置为 $rest")
                    }
                }
                else -> {
                    if (first.startsWith("http://") || first.startsWith("https://")) {
                        core.setMcpConfig(currentMcp.optBoolean("enabled", true), value, currentToken)
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

    fun setSshFromCommand(value: String) {
        try {
            val tokens = value.trim().split(Regex("\\s+"), limit = 2)
            val current = core.config().optJSONObject("ssh") ?: JSONObject()
            var enabled = current.optBoolean("enabled", false)
            var host = current.optString("host", "")
            var port = current.optInt("port", 22)
            var user = current.optString("user", "")
            var keyPath = current.optString("key_path", "")
            val passphrase = core.sshPassphraseForUi()
            if (tokens.isEmpty() || value.isBlank()) {
                addMessage("系统", "用法：ssh on|off|status|diagnose|select <hosts>|connect|disconnect|host <value>|user <value>|port <value>|key <path>")
                return
            }
            val first = tokens[0].lowercase()
            val rest = if (tokens.size > 1) tokens[1] else ""
            when (first) {
                "on", "enable", "enabled", "开启", "open" -> {
                    enabled = true
                    addMessage("系统", "SSH 配置已启用")
                }
                "off", "disable", "disabled", "关闭", "close" -> {
                    enabled = false
                    addMessage("系统", "SSH 配置已关闭")
                }
                "status" -> {
                    showSshStatus()
                    return
                }
                "diagnose", "diag", "test" -> {
                    showSshDiagnose()
                    return
                }
                "select", "auto", "pick" -> {
                    showSshSelectHost(rest)
                    return
                }
                "connect" -> {
                    showSshConnect()
                    return
                }
                "disconnect" -> {
                    showSshDisconnect()
                    return
                }
                "host", "url", "server", "address" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 SSH 主机，示例：ssh host 100.64.0.10")
                        return
                    }
                    host = rest
                    enabled = true
                    addMessage("系统", "SSH 主机已设置为 $rest")
                }
                "user" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 SSH 用户名，示例：ssh user tianhao")
                        return
                    }
                    user = rest
                    enabled = true
                    addMessage("系统", "SSH 用户已设置为 $rest")
                }
                "port" -> {
                    val parsed = rest.toIntOrNull()
                    if (parsed == null) {
                        addMessage("错误", "请输入 SSH 端口，示例：ssh port 22")
                        return
                    }
                    port = parsed
                    enabled = true
                    addMessage("系统", "SSH 端口已设置为 $parsed")
                }
                "key", "key_path", "identity" -> {
                    if (rest.isBlank()) {
                        addMessage("错误", "请输入 SSH 私钥路径，示例：ssh key shared_storage:/keys/id_ed25519")
                        return
                    }
                    keyPath = rest
                    enabled = true
                    addMessage("系统", "SSH 私钥路径已设置为 $rest")
                }
                else -> {
                    host = first
                    enabled = true
                    addMessage("系统", "SSH 主机已设置为 $first")
                }
            }
            core.setSshConfig(enabled, host, port, user, keyPath, passphrase)
            refreshStatus()
        } catch (exc: Exception) {
            addMessage("错误", exc.message ?: exc.javaClass.simpleName)
        }
    }

    private fun applyPermissionMode(mode: String) {
        core.setPermissionMode(mode)
        addMessage("系统", "权限模式设置为 ${MainStatusFormatter.permissionLabel(mode)}")
        refreshStatus()
    }
}
