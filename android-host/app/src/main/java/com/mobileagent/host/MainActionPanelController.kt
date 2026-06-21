package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout

class MainActionPanelController(
    private val activity: Activity,
    private val actions: Actions,
) {
    interface Actions {
        fun reconnect()
        fun openAccessibilitySettings()
        fun showConfig()
        fun showTerminalStatus()
        fun showMcpStatus()
        fun showMcpTools()
        fun showSystemLogs()
        fun showLatestFailureAnalysis()
        fun showLocalTools()
        fun showOfficialDocs()
        fun showMemoryExperience()
        fun startLearning()
        fun stopLearning()
        fun retryLastFailedStep()
        fun cancelRunningTerminalTasks()
        fun continueFailedTask()
    }

    fun show() {
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(28, 18, 28, 8)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("操作面板")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .create()

        fun addPanelButton(label: String, action: () -> Unit) {
            val button = Button(activity)
            button.text = label
            button.setOnClickListener {
                dialog.dismiss()
                action()
            }
            panel.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addPanelButton("重连 / 自检") { actions.reconnect() }
        addPanelButton("无障碍设置") { actions.openAccessibilitySettings() }
        addPanelButton("配置") { actions.showConfig() }
        addPanelButton("终端状态") { actions.showTerminalStatus() }
        addPanelButton("MCP 状态") { actions.showMcpStatus() }
        addPanelButton("MCP 工具") { actions.showMcpTools() }
        addPanelButton("系统日志") { actions.showSystemLogs() }
        addPanelButton("失败分析") { actions.showLatestFailureAnalysis() }
        addPanelButton("工具列表") { actions.showLocalTools() }
        addPanelButton("官方文档") { actions.showOfficialDocs() }
        addPanelButton("记忆/经验") { actions.showMemoryExperience() }
        addPanelButton("开始学习") { actions.startLearning() }
        addPanelButton("结束学习") { actions.stopLearning() }
        addPanelButton("重试失败步骤") { actions.retryLastFailedStep() }
        addPanelButton("停止后台任务") { actions.cancelRunningTerminalTasks() }
        addPanelButton("继续处理失败") { actions.continueFailedTask() }

        dialog.show()
    }
}
