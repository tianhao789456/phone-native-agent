package com.mobileagent.host

class MainLocalCommandRunner(private val actions: Actions) {
    interface Actions {
        fun beforeCommand()
        fun startNewSession()
        fun addSystem(text: String)
        fun addError(text: String)
        fun refreshStatus()
        fun showConfigDialog()
        fun showActionPanel()
        fun runReconnect()
        fun showSystemLogs()
        fun compactContextNow()
        fun showLatestFailureAnalysis()
        fun clearDisplay()
        fun localToolsSummary(): String
        fun officialDocsSummary(): String
        fun setApiKey(key: String)
        fun setPermissionMode(value: String)
        fun setTerminal(value: String)
        fun setMcp(value: String)
        fun setSsh(value: String)
        fun setMaxToolRounds(value: String)
    }

    fun run(command: String) {
        actions.beforeCommand()
        when (command) {
            "new" -> actions.startNewSession()
            "status" -> {
                actions.addSystem("已刷新运行状态")
                actions.refreshStatus()
            }
            "tools" -> actions.addSystem(actions.localToolsSummary())
            "docs" -> actions.addSystem(actions.officialDocsSummary())
            "config" -> actions.showConfigDialog()
            "panel" -> actions.showActionPanel()
            "reconnect" -> actions.runReconnect()
            "logs" -> actions.showSystemLogs()
            "compact" -> actions.compactContextNow()
            "failures" -> actions.showLatestFailureAnalysis()
            "help" -> actions.addSystem(HELP_TEXT)
            "clear" -> actions.clearDisplay()
            else -> runParameterized(command)
        }
    }

    private fun runParameterized(command: String) {
        when {
            command.startsWith("key:") -> {
                val key = command.removePrefix("key:").trim()
                if (!MainLocalCommandParser.looksLikeApiKey(key)) {
                    actions.addError("API Key 格式不正确，请输入 sk-...")
                } else {
                    actions.setApiKey(key)
                }
            }
            command.startsWith("perm:") -> actions.setPermissionMode(command.removePrefix("perm:").trim())
            command.startsWith("terminal:") -> actions.setTerminal(command.removePrefix("terminal:").trim())
            command.startsWith("mcp:") -> actions.setMcp(command.removePrefix("mcp:").trim())
            command.startsWith("ssh:") -> actions.setSsh(command.removePrefix("ssh:").trim())
            command.startsWith("rounds:") -> actions.setMaxToolRounds(command.removePrefix("rounds:").trim())
        }
    }

    companion object {
        const val HELP_TEXT =
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
                "-ssh on|off|status|connect|disconnect|<host>  配置 SSH 连接\n" +
                "-clear  清空当前显示\n" +
                "-key sk-...  保存模型 API Key\n" +
                "-help  显示帮助"
    }
}
