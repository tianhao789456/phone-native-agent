# Android Host App

Native Android base for Mobile Agent.

Initial responsibilities:

- expose a local HTTP bridge for Termux CLI/TUI at `127.0.0.1:8790`
- report host health, permission state, and available backends
- own Android permission entry points
- provide an AccessibilityService backend for UI observation and actions
- reserve Shizuku and native Android backends for later high-permission tools

Bridge contract:

- `GET /health`
- `GET /status`
- `GET /tools`
- `POST /tools/call` with `{"tool":"name","arguments":{}}`

The Python side already speaks this contract through `mobile_agent.host_bridge.HostBridgeClient`.
