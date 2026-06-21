package com.mobileagent.host

import org.json.JSONObject

class NativeTerminalToolDispatcher(
    private val status: () -> JSONObject,
    private val tools: () -> JSONObject,
    private val chat: (String) -> JSONObject,
    private val run: (String, String, Int) -> JSONObject,
    private val script: (JSONObject) -> JSONObject,
    private val taskStatus: (String, Int) -> JSONObject,
    private val taskCancel: (String) -> JSONObject,
    private val recover: (JSONObject) -> JSONObject,
    private val diagnose: () -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "termux_status" -> status()
            "termux_tools" -> tools()
            "termux_chat" -> chat(arguments.optString("message"))
            "terminal_run" -> run(
                arguments.optString("command"),
                arguments.optString("cwd", ""),
                arguments.optInt("timeout", 60)
            )
            "terminal_script" -> script(arguments)
            "terminal_task_status" -> taskStatus(
                arguments.optString("task_id"),
                arguments.optInt("max_output_chars", 12000)
            )
            "terminal_task_cancel" -> taskCancel(arguments.optString("task_id"))
            "recover_terminal_backend" -> recover(arguments)
            "diagnose_terminal" -> diagnose()
            else -> throw IllegalArgumentException("Terminal dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "termux_status",
            "termux_tools",
            "termux_chat",
            "terminal_run",
            "terminal_script",
            "terminal_task_status",
            "terminal_task_cancel",
            "recover_terminal_backend",
            "diagnose_terminal"
        )
    }
}
