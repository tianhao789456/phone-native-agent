# Mobile Agent 模块化重构计划

## 目标

这次重构的目标不是换目录，而是把“会继续长大的职责”拆成可测试、可替换、可在手机端自维护的模块。

核心原则：

- 不改功能语义，先拆边界。
- 每拆一个边界，必须有 PC 侧测试兜底。
- Android 真机能力必须用 APK 构建和手机验证确认。
- 避免一次性搬空大文件，按可回滚的小阶段推进。

## 当前主要问题

### 1. Android 原生核心过大

`NativeAgentCore.kt` 已经超过 5000 行，承担了太多职责：

- 会话管理
- chat loop
- plan/act/verify/retry
- memory extraction
- tool dispatch
- permission gate
- terminal recovery
- MCP client
- SSH/PC bridge
- web search/page extract
- context compaction
- health check

这会导致后续每加一个工具或恢复策略，都要碰核心文件。

### 2. MainActivity UI 控制器过大

`MainActivity.kt` 同时做了：

- 布局构建
- 命令解析
- 网络请求
- 状态栏刷新
- MCP/SSH/Terminal 面板
- memory 面板
- tool trace 格式化
- 配置弹窗
- 本地状态持久化

UI 后续继续中文化、工具详情、停止/追加指令时，会越来越难测。

### 3. 工具注册是单体清单

`NativeToolRegistry.kt` 和 Python `phone_tools.py` 都有“所有工具塞一个文件”的趋势。工具数量继续增加后，工具分组、渐进式加载、权限声明、插件/skill/MCP 混合接入都会变难。

### 4. Host 层和执行层混在一起

Python `http_server.py` 原来同时负责 HTTP 路由和终端任务执行。这个已开始拆分，终端任务执行已移动到 `mobile_agent/hosts/terminal_tasks.py`。

## 推荐目标结构

### Android Core

建议从 `NativeAgentCore.kt` 拆出：

- `NativeChatEngine.kt`：chat 主循环和模型调用编排。
- `NativeTaskLoop.kt`：plan/act/verify/retry 状态机。
- `NativeToolDispatcher.kt`：工具名到实现的分发。
- `NativePermissionGate.kt`：safe/ask/danger/developer 判定。
- `NativeVerification.kt`：工具结果验证、失败分类。
- `NativeRecovery.kt`：terminal/MCP/SSH 自动恢复和熔断。
- `NativeContextManager.kt`：token 估算、压缩、cache usage。
- `NativeWebTools.kt`：web_search、http_get/http_post、page_extract。
- `NativeMcpClient.kt`：MCP status/tools/info/call/session。
- `PcBridgeTools.kt`：SSH、Tailscale、PC MCP 修复流。

### Android UI

建议从 `MainActivity.kt` 拆出：

- `ChatScreenController.kt`：发送、停止、追加指令、消息列表。
- `CommandRouter.kt`：`/status`、`/self-test`、`/mcp` 等本地命令。
- `StatusHeaderController.kt`：模型、token、cache、权限、terminal/MCP 状态。
- `ToolTraceFormatter.kt`：工具详情、失败摘要、折叠输出。
- `ConfigDialogController.kt`：配置弹窗和高权限确认。
- `MemoryPanelController.kt`：学习模式、经验库、procedure 生成。
- `HttpJsonClient.kt`：GET/POST JSON 请求封装。

### Python Host

建议继续拆：

- `hosts/terminal_tasks.py`：已完成，终端脚本任务管理。
- `hosts/http_server.py`：只保留 HTTP 路由和请求/响应。
- `hosts/cli_commands.py`：普通 CLI 命令处理。
- `hosts/cli_tui.py`：TUI 绘制和输入循环。
- `hosts/cli_format.py`：工具结果、上下文、self-test 格式化。
- `phone_tools/` 包：
  - `core_tools.py`
  - `workspace_tools.py`
  - `host_bridge_tools.py`
  - `termux_tools.py`
  - `android_input_tools.py`
  - `registry.py`

## 分阶段执行

### Phase 1：PC 侧稳定边界

已完成：

- `Agent` 消息协议拆到 `core/agent_protocol.py`。
- 自测逻辑拆到 `core/self_test.py`。
- HTTP 终端任务拆到 `hosts/terminal_tasks.py`。
- 补充对应单测。

下一步：

- 拆 `phone_tools.py` 工具分组。
- 拆 CLI 格式化和 TUI。
- 清理 CLI 源码里的历史乱码字符串。

### Phase 2：Android 工具层拆分

先拆最独立的工具域，避免影响 chat loop：

- web/http/page extract
- MCP client
- PC bridge/SSH/Tailscale
- permission gate
- verification/recovery

每拆一个域，补源码契约测试，并跑 Android 构建。

### Phase 3：Android 任务循环拆分

再拆核心状态机：

- plan state
- tool execution step
- verification state
- retry/fuse/stop
- final report

这一阶段需要真机验证，因为它影响实际任务能力。

### Phase 4：UI 拆分

最后拆 `MainActivity.kt`：

- 本地命令解析
- 状态栏
- tool trace 详情
- memory 面板
- config 面板

UI 拆分必须配合真机截图/操作验证。

## 验收标准

每个阶段必须满足：

- `python -m pytest -q` 通过。
- Android 源码契约测试通过。
- 涉及 Kotlin 行为时，至少跑一次 Gradle 构建。
- 涉及手机功能时，安装 APK 后真机验证。
- 不删除旧工具名；重复工具先标记 legacy，再逐步降权或隐藏。
