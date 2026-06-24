# Mobile Agent Modular Refactoring Plan

## Goal

The goal of this refactoring is not just to reorganize directories, but to split "responsibilities that keep growing" into testable, replaceable, phone-self-maintainable modules.

Core principles:

- Don't change functional semantics — split boundaries first.
- Every boundary split must have PC-side tests as a safety net.
- Android real-device capabilities must be verified through APK builds and phone testing.
- Avoid moving entire large files at once — proceed in small, rollback-safe stages.

## Current Major Problems

### 1. Android Native Core Is Too Large

`NativeAgentCore.kt` has exceeded 5000 lines and carries too many responsibilities:

- Session management
- Chat loop
- Plan/act/verify/retry
- Memory extraction
- Tool dispatch
- Permission gate
- Terminal recovery
- MCP client
- SSH/PC bridge
- Web search/page extract
- Context compaction
- Health check

This means every new tool or recovery strategy requires touching the core file.

### 2. MainActivity UI Controller Is Too Large

`MainActivity.kt` simultaneously handles:

- Layout construction
- Command parsing
- Network requests
- Status bar refresh
- MCP/SSH/Terminal panels
- Memory panel
- Tool trace formatting
- Config dialog
- Local state persistence

As the UI continues to add Chinese localization, tool details, stop/append commands, it will get harder and harder to test.

### 3. Tool Registration Is a Monolithic List

Both `NativeToolRegistry.kt` and Python's `phone_tools.py` have a "put all tools in one file" tendency. As the number of tools grows, tool grouping, progressive loading, permission declarations, and plugin/skill/MCP hybrid integration will all become harder.

### 4. Host Layer and Execution Layer Are Mixed

Python's `http_server.py` originally handled both HTTP routing and terminal task execution. This has started to be split — terminal task execution has moved to `mobile_agent/hosts/terminal_tasks.py`.

## Recommended Target Structure

### Android Core

Extract from `NativeAgentCore.kt`:

- `NativeChatEngine.kt`: chat main loop and model call orchestration.
- `NativeTaskLoop.kt`: plan/act/verify/retry state machine.
- `NativeToolDispatcher.kt`: tool name to implementation dispatch.
- `NativePermissionGate.kt`: safe/ask/danger/developer determination.
- `NativeVerification.kt`: tool result verification, failure classification.
- `NativeRecovery.kt`: terminal/MCP/SSH auto-recovery and circuit breaker.
- `NativeContextManager.kt`: token estimation, compaction, cache usage.
- `NativeWebTools.kt`: web_search, http_get/http_post, page_extract.
- `NativeMcpClient.kt`: MCP status/tools/info/call/session.
- `PcBridgeTools.kt`: SSH, Tailscale, PC MCP repair flow.

### Android UI

Extract from `MainActivity.kt`:

- `ChatScreenController.kt`: send, stop, append commands, message list.
- `CommandRouter.kt`: `/status`, `/self-test`, `/mcp` and other local commands.
- `StatusHeaderController.kt`: model, token, cache, permission, terminal/MCP status.
- `ToolTraceFormatter.kt`: tool details, failure summaries, collapsible output.
- `ConfigDialogController.kt`: config dialog and high-permission confirmation.
- `MemoryPanelController.kt`: learning mode, experience store, procedure generation.
- `HttpJsonClient.kt`: GET/POST JSON request wrapper.

### Python Host

Continue splitting:

- `hosts/terminal_tasks.py`: completed — terminal script task management.
- `hosts/http_server.py`: keep only HTTP routes and request/response handling.
- `hosts/cli_commands.py`: regular CLI command processing.
- `hosts/cli_tui.py`: TUI rendering and input loop.
- `hosts/cli_format.py`: tool results, context, self-test formatting.
- `phone_tools/` package:
  - `core_tools.py`
  - `workspace_tools.py`
  - `host_bridge_tools.py`
  - `termux_tools.py`
  - `android_input_tools.py`
  - `registry.py`

## Phased Execution

### Phase 1: PC-Side Stable Boundaries

Completed:

- `Agent` message protocol split into `core/agent_protocol.py`.
- Self-test logic split into `core/self_test.py`.
- HTTP terminal tasks split into `hosts/terminal_tasks.py`.
- Corresponding unit tests added.

Next steps:

- Split `phone_tools.py` tool groups.
- Split CLI formatting and TUI.
- Clean up historical garbled strings in CLI source.

### Phase 2: Android Tool Layer Splitting

Split the most independent tool domains first, avoiding impact on the chat loop:

- Web/http/page extract
- MCP client
- PC bridge/SSH/Tailscale
- Permission gate
- Verification/recovery

Each domain split must include source contract tests and a successful Android build.

### Phase 3: Android Task Loop Splitting

Then split the core state machine:

- Plan state
- Tool execution step
- Verification state
- Retry/fuse/stop
- Final report

This phase requires real-device verification because it affects actual task capability.

### Phase 4: UI Splitting

Finally split `MainActivity.kt`:

- Local command parsing
- Status bar
- Tool trace details
- Memory panel
- Config panel

UI splitting must be verified with real-device screenshots/operations.

## Acceptance Criteria

Each phase must satisfy:

- `python -m pytest -q` passes.
- Android source contract tests pass.
- If Kotlin behavior is involved, run at least one Gradle build.
- If phone functionality is involved, install APK and verify on a real device.
- Don't delete old tool names; mark duplicate tools as `legacy` first, then gradually deprecate or hide them.
