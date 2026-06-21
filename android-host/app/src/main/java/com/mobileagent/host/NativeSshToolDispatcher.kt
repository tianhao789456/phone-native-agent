package com.mobileagent.host

import org.json.JSONObject

class NativeSshToolDispatcher(
    private val sshBridge: SshBridge,
    private val connect: (JSONObject) -> JSONObject,
    private val run: (JSONObject) -> JSONObject,
    private val push: (JSONObject) -> JSONObject,
    private val pull: (JSONObject) -> JSONObject,
    private val pcFileWorkflow: (JSONObject) -> JSONObject,
    private val storagePermissionStatus: () -> JSONObject,
    private val openStoragePermissionSettings: () -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "ssh_status" -> sshBridge.status()
            "ssh_diagnose" -> sshBridge.diagnose(arguments)
            "ssh_select_host" -> sshBridge.selectHost(arguments)
            "ssh_connect" -> connect(arguments)
            "ssh_run" -> run(arguments)
            "ssh_forward_status" -> sshBridge.forwardStatus()
            "ssh_forward_start" -> sshBridge.startLocalForward(arguments)
            "ssh_forward_stop" -> sshBridge.stopLocalForward()
            "ssh_disconnect" -> sshBridge.disconnect()
            "file_push" -> push(arguments)
            "file_pull" -> pull(arguments)
            "pc_file_workflow" -> pcFileWorkflow(arguments)
            "storage_permission_status" -> storagePermissionStatus()
            "storage_permission_open_settings" -> openStoragePermissionSettings()
            else -> throw IllegalArgumentException("SSH dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "ssh_status",
            "ssh_diagnose",
            "ssh_select_host",
            "ssh_connect",
            "ssh_run",
            "ssh_forward_status",
            "ssh_forward_start",
            "ssh_forward_stop",
            "ssh_disconnect",
            "file_push",
            "file_pull",
            "pc_file_workflow",
            "storage_permission_status",
            "storage_permission_open_settings"
        )
    }
}
