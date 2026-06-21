package com.mobileagent.host

data class MainCommandInfo(
    val command: String,
    val title: String,
    val description: String,
)

object MainCommandCatalog {
    val commands: List<MainCommandInfo> = listOf(
        MainCommandInfo("-new", "新会话", "开启一段干净的新对话。"),
        MainCommandInfo("-status", "刷新状态", "刷新核心、无障碍、终端、MCP、缓存等运行状态。"),
        MainCommandInfo("-tools", "工具列表", "查看手机 Agent 当前内置工具概览。"),
        MainCommandInfo("-docs", "官方文档", "查看应用内置官方文档目录。"),
        MainCommandInfo("-commands", "命令大全", "显示本地命令帮助。"),
        MainCommandInfo("-config", "配置", "打开模型、权限、终端、MCP、SSH 等配置。"),
        MainCommandInfo("-panel", "操作面板", "打开常用操作入口。"),
        MainCommandInfo("-reconnect", "重连 / 自检", "检查核心链路并尝试恢复终端、桥接等运行状态。"),
        MainCommandInfo("-logs", "系统日志", "查看最近系统日志摘要。"),
        MainCommandInfo("-failures", "失败分析", "查看最近一次失败任务的分析。"),
        MainCommandInfo("-compact", "压缩上下文", "手动触发当前会话上下文压缩。"),
        MainCommandInfo("-clear", "清空显示", "清空当前屏幕上的聊天显示，不删除底层能力。"),
        MainCommandInfo("-rounds 50", "工具轮数", "设置任务循环最多允许的工具调用轮数。"),
        MainCommandInfo("-perm developer", "权限模式", "切换权限模式，可改成 safe、ask、danger、developer。"),
        MainCommandInfo("-terminal status", "终端状态", "检查 Termux HTTP 后端是否可用。"),
        MainCommandInfo("-terminal health", "终端健康检查", "检测终端后端健康状态。"),
        MainCommandInfo("-terminal recover", "恢复终端", "尝试恢复 Termux HTTP 后端。"),
        MainCommandInfo("-terminal on", "启用终端", "启用手机端终端工具后端。"),
        MainCommandInfo("-terminal off", "关闭终端", "关闭手机端终端工具后端。"),
        MainCommandInfo("-terminal http://127.0.0.1:8787", "终端地址", "设置 Termux HTTP 后端地址。"),
        MainCommandInfo("-mcp status", "MCP 状态", "检查当前 MCP 服务是否可用。"),
        MainCommandInfo("-mcp tools", "MCP 工具", "读取当前 MCP 暴露的工具概览。"),
        MainCommandInfo("-mcp tools PowerShell", "搜索 MCP 工具", "按关键词过滤 MCP 工具。"),
        MainCommandInfo("-mcp on", "启用 MCP", "启用远程 MCP 工具桥。"),
        MainCommandInfo("-mcp off", "关闭 MCP", "关闭远程 MCP 工具桥。"),
        MainCommandInfo("-mcp token <token>", "设置 MCP Token", "更新 MCP 鉴权 token，输入前替换尖括号内容。"),
        MainCommandInfo("-mcp http://127.0.0.1:18000/mcp", "设置 MCP 地址", "更新 MCP 服务地址，常用于 SSH 隧道。"),
        MainCommandInfo("-ssh status", "SSH 状态", "查看当前 SSH 连接配置和状态。"),
        MainCommandInfo("-ssh connect", "连接 SSH", "按当前配置连接电脑 SSH。"),
        MainCommandInfo("-ssh diagnose", "SSH 诊断", "诊断 SSH 网络、端口、banner 或认证问题。"),
        MainCommandInfo("-ssh select 100.113.120.40 192.168.1.3", "选择 SSH 地址", "从候选地址中自动选择可用主机。"),
        MainCommandInfo("-ssh disconnect", "断开 SSH", "断开当前 SSH 会话。"),
        MainCommandInfo("-ssh on", "启用 SSH", "启用 SSH 远程电脑桥。"),
        MainCommandInfo("-ssh off", "关闭 SSH", "关闭 SSH 远程电脑桥。"),
        MainCommandInfo("-ssh 100.113.120.40", "设置 SSH 主机", "设置电脑 SSH 主机地址。"),
        MainCommandInfo("-key sk-...", "保存 API Key", "保存模型 API Key 到应用私有配置。"),
        MainCommandInfo("-help", "帮助", "显示本地命令帮助。"),
    )

    fun helpText(): String {
        return buildString {
            appendLine("本地命令：")
            commands.forEach { item ->
                appendLine("${item.command}  ${item.title}：${item.description}")
            }
        }.trimEnd()
    }

    fun docsText(): String {
        return buildString {
            appendLine("# 应用本地命令")
            appendLine()
            appendLine("在应用聊天框里输入这些命令，不会发送给模型，会由应用本地执行。")
            appendLine()
            commands.forEach { item ->
                appendLine("- `${item.command}`：${item.title}。${item.description}")
            }
            appendLine()
            appendLine("说明：")
            appendLine()
            appendLine("- 操作面板里的“命令大全”可以一键复制或填入输入框。")
            appendLine("- 本地命令适合人直接操作。")
            appendLine("- Agent 需要了解能力时，应优先使用 `docs_index`、`docs_read`、`tool_registry`。")
        }.trimEnd()
    }
}
