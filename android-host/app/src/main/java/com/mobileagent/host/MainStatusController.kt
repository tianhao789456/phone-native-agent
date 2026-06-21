package com.mobileagent.host

import android.os.Handler
import android.widget.TextView
import kotlin.concurrent.thread
import org.json.JSONObject

class MainStatusController(
    private val ui: Handler,
    private val statusText: TextView,
    private val detailStatusText: TextView,
    private val coreStatus: () -> JSONObject?,
    private val accessibilityStatus: () -> JSONObject,
) {
    private var isActive = false
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            refresh()
            ui.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    fun start() {
        isActive = true
        ui.removeCallbacks(statusRefreshRunnable)
        statusRefreshRunnable.run()
    }

    fun stop() {
        isActive = false
        ui.removeCallbacks(statusRefreshRunnable)
    }

    fun refresh() {
        thread(name = "mobile-agent-status") {
            val core = runCatching { coreStatus() }
            val host = accessibilityStatus()
            ui.post {
                val status = core.getOrNull()
                val coreLabel = if (status?.optBoolean("ok") == true) {
                    "核心可用"
                } else {
                    "核心离线"
                }
                val accessibilityLabel = when {
                    host.optBoolean("connected") -> "无障碍已连接"
                    host.optBoolean("enabled") -> "无障碍已启用"
                    else -> "无障碍未启用"
                }
                statusText.text = "状态：$coreLabel | $accessibilityLabel"
                statusText.text = "${statusText.text} | ${MainStatusFormatter.terminalHeaderLabel(status)}"
                statusText.text = "${statusText.text} | ${MainStatusFormatter.mcpHeaderLabel(status)}"
                detailStatusText.text = MainStatusFormatter.summarizeStatus(status)
            }
        }
    }

    companion object {
        private const val STATUS_REFRESH_MS = 3000L
    }
}
