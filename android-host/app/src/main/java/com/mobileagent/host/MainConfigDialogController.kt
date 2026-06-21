package com.mobileagent.host

import android.app.AlertDialog
import android.app.Activity

class MainConfigDialogController(
    private val activity: Activity,
    private val core: NativeAgentCore,
    private val getSessionId: () -> String?,
    private val addMessage: (role: String, text: String, detail: String?) -> Unit,
    private val refreshStatus: () -> Unit,
    private val showError: (title: String, detail: String) -> Unit
) {
    fun show() {
        val config = core.config()
        val currentMode = config.optString("permission_mode", AgentRuntimeConfig.MODE_SAFE)
        val storageStatus = core.storagePermissionStatusForUi()
        val form = MainConfigDialogBuilder.build(
            context = activity,
            config = config,
            storageStatus = storageStatus,
            sshPassphraseForUi = core.sshPassphraseForUi(),
            openStorageSettings = {
                val result = runCatching { core.openStoragePermissionSettingsForUi() }
                result.onSuccess { addMessage("系统", "文件访问授权页已打开：\n${it.toString(2)}", null) }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName, null) }
            },
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Agent 配置")
            .setView(form.layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedMode = form.modeById[form.modeGroup.checkedRadioButtonId] ?: currentMode
                val apply = {
                    try {
                        core.setPermissionMode(selectedMode)
                        core.setTerminalConfig(form.terminalEnabled.isChecked, form.terminalUrl.text.toString())
                        val tokenValue = form.mcpToken.text.toString().trim()
                        core.setMcpConfig(
                            form.mcpEnabled.isChecked,
                            form.mcpUrl.text.toString(),
                            if (tokenValue.isBlank()) core.mcpAuthToken() else tokenValue
                        )
                        core.setSshConfig(
                            form.sshEnabled.isChecked,
                            form.sshHost.text.toString(),
                            form.sshPort.text.toString().toIntOrNull() ?: 22,
                            form.sshUser.text.toString(),
                            form.sshKeyPath.text.toString(),
                            form.sshPassphrase.text.toString()
                        )
                        addMessage("系统", "配置已应用\n${configSummary()}", null)
                        refreshStatus()
                        dialog.dismiss()
                    } catch (exc: Exception) {
                        showError("配置错误", exc.message ?: exc.javaClass.simpleName)
                    }
                }
                if (MainLocalCommandParser.isHighPowerMode(selectedMode) && currentMode != selectedMode) {
                    confirmHighPowerMode(selectedMode) { apply() }
                } else {
                    apply()
                }
            }
        }
        dialog.show()
    }

    fun confirmHighPowerMode(mode: String, onConfirmed: () -> Unit) {
        val label = MainStatusFormatter.permissionLabel(mode)
        AlertDialog.Builder(activity)
            .setTitle("确认$label")
            .setMessage("$label 模式较高，请确认是本人且可接受其影响后才继续。确认后系统将按该模式执行指令。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> onConfirmed() }
            .show()
    }

    private fun configSummary(): String {
        val status = core.status(getSessionId())
        return MainStatusFormatter.configSummary(status)
    }
}
