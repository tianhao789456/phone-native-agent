package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
        fun showCommandCatalog()
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
        panel.setPadding(dp(16), dp(10), dp(16), dp(12))

        val scrollView = ScrollView(activity)
        scrollView.isFillViewport = false
        scrollView.addView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("操作面板")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()

        fun addSection(title: String) {
            val view = TextView(activity)
            view.text = title
            view.textSize = 13f
            view.typeface = Typeface.DEFAULT_BOLD
            view.setTextColor(Color.rgb(71, 85, 105))
            view.setPadding(0, dp(14), 0, dp(4))
            panel.addView(view)
        }

        fun addPanelButton(label: String, action: () -> Unit) {
            val button = Button(activity)
            button.text = label
            button.textSize = 14f
            button.isAllCaps = false
            button.setOnClickListener {
                dialog.dismiss()
                action()
            }
            panel.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                ).apply { setMargins(0, 0, 0, dp(8)) }
            )
        }

        addSection("常用")
        addPanelButton("重连 / 自检") { actions.reconnect() }
        addPanelButton("配置") { actions.showConfig() }
        addPanelButton("失败分析") { actions.showLatestFailureAnalysis() }
        addPanelButton("继续处理失败") { actions.continueFailedTask() }

        addSection("连接")
        addPanelButton("无障碍设置") { actions.openAccessibilitySettings() }
        addPanelButton("终端状态") { actions.showTerminalStatus() }
        addPanelButton("MCP 状态") { actions.showMcpStatus() }
        addPanelButton("MCP 工具") { actions.showMcpTools() }

        addSection("能力")
        addPanelButton("工具列表") { actions.showLocalTools() }
        addPanelButton("官方文档") { actions.showOfficialDocs() }
        addPanelButton("命令大全") { actions.showCommandCatalog() }
        addPanelButton("记忆/经验") { actions.showMemoryExperience() }
        addPanelButton("开始学习") { actions.startLearning() }
        addPanelButton("结束学习") { actions.stopLearning() }

        addSection("维护")
        addPanelButton("重试失败步骤") { actions.retryLastFailedStep() }
        addPanelButton("停止后台任务") { actions.cancelRunningTerminalTasks() }
        addPanelButton("系统日志") { actions.showSystemLogs() }

        dialog.setOnShowListener {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            scrollView.layoutParams = scrollView.layoutParams.apply {
                height = maxHeight
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
