# Changelog

## 0.2.0-alpha - 2026-06-21

Preview release for phone-led PC control and extensible tool loading.

- Added native SSH profile management, diagnostics, command execution, local forwarding, and SFTP file transfer.
- Added phone-to-PC bridge recovery for Tailscale/LAN SSH plus Windows MCP endpoint repair.
- Added multi-MCP server registry primitives with per-server status, tool discovery, tool detail, and calls.
- Added progressive loading for native tools, plugins, skills, and MCP tools to reduce prompt bloat.
- Added task interruption, context compaction, cache display improvements, and simplified Chinese output normalization.
- Added Android UI surfaces for SSH, MCP, permissions, storage access, and task control.
- Added `docs/ssh-pc-control.md` for Tailscale + SSH + Windows MCP workflows.

## 0.1.0 - 2026-06-21

Initial public-ready prototype.

- Added Python agent core with CLI and HTTP hosts.
- Added Kotlin Android Host App with local tool bridge and Chinese UI.
- Added persistent sessions, task workspaces, traces, and reports.
- Added managed Plan-Act-Verify-Retry loop with evidence and completion review.
- Added phone, workspace, Termux, plugin, and bridge-oriented tools.
- Added plugin validation/test/run report workflow.
- Added Python test coverage for core, CLI, HTTP, plugins, phone tools, Termux API, and host bridge.
