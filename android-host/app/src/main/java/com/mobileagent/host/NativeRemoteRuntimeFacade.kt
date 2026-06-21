package com.mobileagent.host

import android.content.Context
import org.json.JSONObject

class NativeRemoteRuntimeFacade(
    private val context: Context,
    private val runtimeConfig: AgentRuntimeConfig,
    private val terminalClient: NativeTerminalClient,
    private val terminalRecovery: NativeTerminalRecoveryController,
    private val mcpClient: NativeMcpClient,
    private val sshBridge: SshBridge,
    private val storageAccess: NativeStorageAccess,
    private val healthCoordinator: NativeHealthCoordinator,
    private val log: (String, String, String, JSONObject?) -> Unit
) {
    fun terminalStatus(): JSONObject = terminalClient.status()

    fun terminalHealthForUi(autoRecover: Boolean = false, force: Boolean = true): JSONObject {
        return terminalRecovery.runtimeStatus(autoRecover = autoRecover, force = force)
    }

    fun terminalTools(): JSONObject = terminalClient.tools()

    fun terminalChat(message: String): JSONObject = terminalClient.chat(message)

    fun terminalRun(command: String, cwd: String, timeout: Int): JSONObject {
        return terminalClient.run(command, cwd, timeout)
    }

    fun terminalScript(arguments: JSONObject): JSONObject = terminalClient.script(arguments)

    fun terminalTaskStatus(taskId: String, maxOutputChars: Int): JSONObject {
        return terminalClient.taskStatus(taskId, maxOutputChars)
    }

    fun terminalTaskCancel(taskId: String): JSONObject = terminalClient.taskCancel(taskId)

    fun terminalPowerMode(): Boolean {
        val mode = runtimeConfig.permissionMode()
        return mode == AgentRuntimeConfig.MODE_DANGER || mode == AgentRuntimeConfig.MODE_DEVELOPER
    }

    fun recoverTerminalBackend(arguments: JSONObject): JSONObject = terminalClient.recover(arguments)

    fun diagnoseTerminal(): JSONObject = terminalClient.diagnose()

    fun mcpAuthToken(): String = runtimeConfig.mcpAuthToken()

    fun mcpStatusForUi(): JSONObject = mcpClient.status()

    fun mcpToolsForUi(search: String = ""): JSONObject = mcpClient.tools(search, includeSchema = true)

    fun sshStatusForUi(): JSONObject = sshBridge.status()

    fun sshConnectForUi(arguments: JSONObject = JSONObject()): JSONObject = sshBridge.connect(arguments)

    fun sshDiagnoseForUi(arguments: JSONObject = JSONObject()): JSONObject = sshBridge.diagnose(arguments)

    fun sshSelectHostForUi(arguments: JSONObject = JSONObject()): JSONObject = sshBridge.selectHost(arguments)

    fun sshDisconnectForUi(): JSONObject = sshBridge.disconnect()

    fun sshPassphraseForUi(): String = runtimeConfig.sshPassphrase()

    fun sshRun(arguments: JSONObject): JSONObject {
        val command = arguments.optString("command")
        val cwd = arguments.optString("cwd", "")
        val shell = arguments.optString("shell", "powershell")
        val timeout = arguments.optInt("timeout_ms", runtimeConfig.sshCommandTimeoutMs())
        return sshBridge.run(command, cwd, shell, timeout)
    }

    fun sshFilePush(arguments: JSONObject): JSONObject {
        return sshBridge.push(
            arguments.optString("local_path"),
            arguments.optString("remote_path"),
            arguments.optBoolean("overwrite", true)
        )
    }

    fun sshFilePull(arguments: JSONObject): JSONObject {
        return sshBridge.pull(
            arguments.optString("remote_path"),
            arguments.optString("local_path", ""),
            arguments.optBoolean("overwrite", true)
        )
    }

    fun storagePermissionStatusForUi(): JSONObject = storageAccess.status()

    fun openStoragePermissionSettingsForUi(): JSONObject = storageAccess.openSettings()

    fun reconnectForUi(): JSONObject {
        log("info", "core", "manual reconnect requested", null)
        HostBridgeServer.start(context)
        val before = healthCoordinator.selfHealthCheck()
        val terminalBefore = before.optJSONObject("terminal") ?: JSONObject()
        var recovery: JSONObject? = null
        if (runtimeConfig.terminalEnabled() && terminalBefore.optString("status") != "ok") {
            recovery = recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", false)
                    .put("open_termux", true)
                    .put("wait_ms", 3000)
            )
        }
        val after = healthCoordinator.selfHealthCheck()
        return JSONObject()
            .put("ok", after.optBoolean("ok", false))
            .put("action", "reconnect")
            .put("before", before)
            .put("recovery", recovery ?: JSONObject().put("skipped", true))
            .put("after", after)
    }
}
