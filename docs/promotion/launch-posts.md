# Mobile Agent Promotion Drafts

Repo: https://github.com/tianhao789456/phone-native-agent

Core angle:

Mobile Agent is a phone-resident AI agent prototype. The phone is not just an ADB target. The Android app is the agent surface: it can observe UI through AccessibilityService, run a Plan / Act / Verify / Retry loop, use native tools, keep task evidence, call Termux when available, and connect to a PC through SSH/MCP when desktop capability is needed.

## 中文短帖

我做了一个手机原生 AI Agent 原型：Mobile Agent。

它不是“电脑通过 ADB 控手机”，而是把 Agent 执行面尽量放到手机侧：

- Android App 自己承载对话、状态、工具入口和任务循环
- 通过 AccessibilityService 看屏幕、点 UI、验证结果
- 支持 Plan / Act / Verify / Retry，不是工具返回 ok 就算完成
- 支持记忆、经验、Skill、插件和渐进式工具加载
- 支持 SSH/Tailscale 连接电脑，跑 PowerShell、传文件、修复 MCP
- 支持 MCP 扩展，让手机 Agent 调用桌面工具能力

现在还是 alpha，但已经能跑实际链路，不是空壳 demo。

GitHub: https://github.com/tianhao789456/phone-native-agent

## 中文长帖

最近在做一个手机原生 AI Agent：Mobile Agent。

我一开始想解决的问题不是“怎么用电脑控制手机”，而是“手机能不能自己成为 Agent 的运行面”。所以这个项目的方向是：Android Host App 负责对话、状态、工具入口、屏幕观察、动作执行和任务轨迹；电脑、Termux、MCP 都只是扩展能力，不是主控。

目前已经做进去的东西：

- Android Host App：中文界面、状态栏、操作面板、无障碍工具入口
- Plan / Act / Verify / Retry：每步工具调用后保留证据、验证结果、失败分析和重试预算
- Accessibility 快照：结构化 UI 树、元素索引和动作列表，减少纯截图盲点
- Intent / 文件动作：打开 URL、打开文件、分享文件等 Android 原生能力
- 记忆和经验：用户资料、经验、procedure、学习记录的接口
- Skill / 插件：把固定流程封装成可复用能力
- SSH PC Bridge：手机通过 LAN 或 Tailscale 连接电脑，执行 PowerShell、传文件、恢复 MCP
- 多 MCP 预留：手机本地 MCP、桌面 MCP、Desktop Control MCP 都按扩展方向预留

它还不是成熟产品，但已经不只是 demo。这个方向我觉得有价值：手机 Agent 不应该永远只是被电脑或云端遥控的对象，它应该能自己观察、自己执行、自己修复，并在需要的时候调用电脑能力。

欢迎试用、提 issue 或 fork。

GitHub: https://github.com/tianhao789456/phone-native-agent

## X / Twitter

I am building Mobile Agent, a phone-resident AI agent prototype.

The phone is not just an ADB target. The Android app is the agent surface:

- AccessibilityService UI observation/actions
- Plan / Act / Verify / Retry loop
- memory, skills, plugins
- SSH/Tailscale PC bridge
- MCP desktop tool extension

Still alpha, but it already runs real phone + PC control workflows.

https://github.com/tianhao789456/phone-native-agent

## Zhihu

标题建议：

- 我做了一个手机原生 AI Agent：不靠 ADB，把执行面放到手机上
- 手机 Agent 应该自己操作自己，而不是永远被电脑遥控
- Mobile Agent：一个 Android 端 AI Agent 原型

正文建议：

我最近在做一个手机原生 AI Agent 原型，叫 Mobile Agent。

和常见的 ADB 控手机方案不太一样，这个项目尝试把 Agent 的主要执行面放到手机侧。Android App 自己负责对话、状态、工具入口、无障碍观察、动作执行和任务轨迹。Termux、SSH、MCP、电脑端工具都是扩展能力，而不是主控。

目前已经实现了 Plan / Act / Verify / Retry 执行闭环、Accessibility 结构化快照、Intent 文件动作、记忆/经验接口、Skill/插件机制，以及通过 SSH/Tailscale 控制电脑和恢复 MCP 的链路。

项目还处在 alpha 阶段，但已经不是空壳 demo。欢迎感兴趣的人试试、提 issue 或 fork。

GitHub: https://github.com/tianhao789456/phone-native-agent

## V2EX

标题建议：

做了一个手机原生 AI Agent 原型：让 Android App 自己承载 Agent 执行面

正文：

最近做了一个实验项目 Mobile Agent，方向是让手机自己承担 Agent 的执行面，而不是只做电脑 ADB 控制下的被动目标。

主要特性：

- Android Host App 承载对话、状态、工具入口和任务循环
- AccessibilityService 观察 UI 和执行动作
- Plan / Act / Verify / Retry 闭环，工具调用后有验证和失败分析
- 支持记忆、经验、Skill、插件和渐进式工具加载
- 支持 SSH/Tailscale 连接电脑，执行命令、传文件、恢复 MCP
- 预留多 MCP 扩展，后续可以把桌面能力接到手机 Agent 上

项目还在 alpha，更多是给对手机 Agent、Android 自动化、MCP、桌面桥接感兴趣的人看方向和实现。

GitHub: https://github.com/tianhao789456/phone-native-agent

## Reddit

Title:

Mobile Agent: a phone-resident AI agent prototype for Android

Body:

I am building Mobile Agent, an experimental phone-resident AI agent prototype for Android.

The main idea is that the phone should not only be a passive ADB target. The Android app itself acts as the agent surface: chat UI, status, native tools, accessibility observation/actions, task traces, and a Plan / Act / Verify / Retry loop.

Current features include:

- AccessibilityService based UI observation and actions
- structured UI snapshots with element indices
- Android intent/file actions
- memory, experience, skills, and plugin interfaces
- progressive tool loading
- SSH/Tailscale PC bridge for PowerShell commands and file transfer
- MCP extension support for desktop tools

It is still alpha, but it already runs real phone + PC workflows.

GitHub: https://github.com/tianhao789456/phone-native-agent

