# 手机端自测反馈测试计划（v1）

## 目标

保证手机端也能读懂并展示自测结果，而不是只把执行日志丢出来。

## 先区分两类验证

### PC 侧验证

PC 侧验证不需要安装 Android App。

它只验证 Python Agent 核心、CLI 命令、HTTP 路由、协议拼装和自测结果结构是否稳定。当前已覆盖：

- `agent.run_self_test()`
- CLI `/self-test`
- HTTP `GET /self-test`
- `status / summary / checks / recommendations` 返回结构

对应测试：

- `tests/test_self_test.py`
- `tests/test_agent_protocol.py`
- `tests/test_http.py`
- `tests/test_cli.py`

### 手机真机验证

手机端验证必须安装最新 APK，并开启必要权限。

需要验证这些内容时，必须走真机：

- App UI 是否能展示 `/self-test` 结果
- Android Host bridge 是否能连通
- 无障碍工具是否能返回屏幕结构
- native tool 是否能调用
- 失败建议是否能在手机端显示给用户

前置条件：

- 安装最新 APK
- 开启无障碍服务
- 授予通知、存储等必要权限
- 启动 Android Host / Python Host 对应链路
- 如需验证 host bridge，打开 `include_host_bridge_check`

## 手机端验证步骤

1. 安装最新 APK 并启动手机端 Agent。
2. 在手机端发送 `/self-test`，或请求 `GET /self-test`。
3. 校验返回至少包含：
   - `status`（`ok` / `warn` / `error`）
   - `summary.total / ok / warn / error`
   - `checks`
   - `recommendations`
4. 检查关键 check 名：
   - `python_runtime`
   - `agent_config`
   - `session_store`
   - `tool_registry`
   - `critical_tool:get_time`
5. 如果启用 host bridge 检查，还应包含：
   - `host_bridge`

## 预期行为

- `status=ok` 时，`recommendations` 可以为空。
- `status=warn/error` 时，应给出对应建议，并在手机界面展示。
- host bridge 默认不强制检查，避免 PC 单测或无手机环境下误报。

## 回归用例

- `tool_registry` 缺少 `get_time` 时，应出现 `critical_tool:get_time` 告警。
- API key 为空时，`agent_config` 应为 `error`，整体 `status=error`。
- 默认不检查 `host_bridge`；启用 `include_host_bridge_check` 后，应返回 `host_bridge` check。
