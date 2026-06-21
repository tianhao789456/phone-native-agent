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
            "commands" -> actions.addSystem(MainCommandCatalog.helpText())
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
        val HELP_TEXT: String = MainCommandCatalog.helpText()
    }
}
