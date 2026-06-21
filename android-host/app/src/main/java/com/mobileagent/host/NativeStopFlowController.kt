package com.mobileagent.host

import org.json.JSONObject

class NativeStopFlowController(
    private val stopController: NativeStopController
) {
    fun isRequested(sessionId: String): Boolean = stopController.isRequested(sessionId)

    fun clear(sessionId: String) = stopController.clear(sessionId)

    fun buildUserStopBlock(sessionId: String, phase: String, modelResponse: NativeModelResponse? = null): JSONObject {
        return stopController.block(sessionId, phase, modelResponse)
    }

    fun userStopFinalText(blocker: JSONObject): String {
        return "已按你的要求停止任务。\n阶段: ${blocker.optString("phase", "unknown")}\n你可以继续发送新指令，Agent 会按新的要求接着处理。"
    }

    fun loopGuardFinalText(blocker: JSONObject): String {
        val tool = blocker.optString("tool", "-")
        val state = stateLabel(blocker.optString("state", "-"))
        val reason = reasonLabel(blocker.optString("reason", ""))
        return buildString {
            append("任务已暂停，避免继续重复失败。\n")
            append("工具: ").append(tool).append("\n")
            append("状态: ").append(state).append("\n")
            append("原因: ").append(reason).append("\n")
            append("摘要: ").append(blocker.optString("summary", "暂无摘要")).append("\n\n")
            append("建议: 可以补充一句新的指令，换一种路径继续；也可以打开“失败分析”查看 stdout/stderr 和具体命令。")
        }
    }

    private fun stateLabel(state: String): String {
        return when (state) {
            "success" -> "成功"
            "failed", "failure" -> "失败"
            "needs_confirmation" -> "等待确认"
            "needs_permission" -> "等待授权"
            else -> state.ifBlank { "未知" }
        }
    }

    private fun reasonLabel(reason: String): String {
        return when (reason) {
            "repeated_failure_pattern" -> "同类失败重复出现"
            "retry_budget_exhausted" -> "重试次数已用完"
            else -> reason.ifBlank { "任务闭环保护触发" }
        }
    }
}
