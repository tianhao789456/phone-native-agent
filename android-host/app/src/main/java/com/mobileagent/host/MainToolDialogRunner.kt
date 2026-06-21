package com.mobileagent.host

import android.os.Handler
import org.json.JSONObject
import kotlin.concurrent.thread

class MainToolDialogRunner(
    private val core: NativeAgentCore,
    private val ui: Handler,
    private val addMessage: (role: String, text: String, detail: String?) -> Unit,
    private val showScrollable: (title: String, detail: String) -> Unit,
    private val refreshStatus: () -> Unit
) {
    fun run(
        tool: String,
        args: JSONObject,
        title: String,
        formatter: (JSONObject) -> String,
        threadName: String
    ) {
        addMessage("系统", "正在执行：$tool", null)
        thread(name = threadName) {
            val result = runCatching { core.executeNativeToolForDiagnostics(tool, args, true) }
            ui.post {
                result
                    .onSuccess {
                        val payload = it.optJSONObject("result") ?: it
                        val text = formatter(payload)
                        addMessage("工具", "$title\n${text.take(1200)}", it.toString(2))
                        showScrollable(title, text)
                        refreshStatus()
                    }
                    .onFailure { addMessage("错误", it.message ?: it.javaClass.simpleName, null) }
            }
        }
    }
}
