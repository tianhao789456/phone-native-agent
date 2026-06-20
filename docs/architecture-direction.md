# Mobile Agent Architecture Direction

This document preserves the product and architecture decisions for the phone-native agent. Read it before major work so the direction does not drift after context compression.

## Goal

Build a phone-resident agent that can run on Android as its own native operating surface:

- Chat and planning use a cloud model API.
- Execution, tools, scripts, plugins, sessions, tests, and phone integrations run locally on the phone.
- The computer is only a development accelerator during early construction.
- The final runtime should not require USB, a desktop bridge, or a constantly connected computer.

The long-term target is:

```text
phone edits itself
phone tests itself
phone extends itself
phone controls apps locally
cloud model only provides reasoning/generation
```

## Why We Use The Computer Now

The computer is useful now because its development ecosystem is mature:

- Faster editing and refactoring.
- Faster tests and diagnostics.
- Easier web/GitHub research.
- ADB and screenshots are convenient for early phone debugging.
- Codex can iterate aggressively from this environment.

This is a construction scaffold, not the end-state dependency.

## Final Runtime Shape

```text
Termux Runtime
  - ma / ma tui
  - Python agent core
  - sessions, traces, context compaction
  - plugins and generated scripts
  - tests and self-check scripts
  - cloud model API client

Android Host App
  - AccessibilityService
  - local HTTP/WebSocket API
  - screen tree observation
  - tap/swipe/text/back/open-app actions
  - permission/status UI

Shizuku Extension
  - optional high-permission shell/system APIs
  - explicit user-selected high power mode
```

## Base Layer Decision

The main base should be:

```text
self-owned Android Host App + AccessibilityService
```

Rationale:

- Wireless.
- Phone-native.
- Does not depend on a connected computer.
- Can remain available as a local service.
- Works well for non-multimodal text models by exposing the UI tree as text.
- Provides direct actions such as click, swipe, back, and text input.

The host app should expose a local API to Termux:

```text
Termux ma CLI
  -> localhost HTTP/WebSocket
  -> Android Host App
  -> AccessibilityService
  -> current screen tree and UI actions
```

## Shizuku Role

Shizuku is valuable, but it is not the main base.

Use Shizuku as an optional high-permission extension for:

- high-permission shell
- package/system API operations
- fallback screen dumps
- system settings or app management where appropriate

Do not build the whole product around Shizuku because non-root Shizuku often requires user activation and may need reactivation after reboot. It is a strong extension layer, not the always-on product foundation.

## ADB Role

ADB is useful for development and debugging, but should not be the product runtime foundation.

Allowed during development:

- install/debug APKs
- inspect permissions
- verify `uiautomator dump`
- take screenshots
- recover from broken Termux state

Avoid making product features depend on:

- USB connection
- desktop daemon
- desktop-hosted screen observation
- desktop-controlled phone automation

ADB can remain a fallback/debug tool, not the main base.

## Non-Multimodal App Control

The model may be text-only. That is acceptable.

The control loop should be:

```text
observe screen as structured text
model decides next action
execute action
observe again
```

Observation options:

- AccessibilityService node tree from the Host App.
- Shizuku/ADB `uiautomator dump` fallback.
- OCR or multimodal screenshot later, but not required for the first product-grade loop.

Action tools:

- tap
- swipe
- type text
- back/home/enter key events
- open app by package

The important distinction:

- Observation tells the model what exists.
- Actions only perform movement/input.

## Permission Philosophy

The project should not choose safety by removing capability. It should expose capability behind explicit user choice.

Current stage:

```text
personal prototype
```

At this stage, prioritize completing real agent capability before polishing release-grade safety UX. A personal prototype that cannot operate files, run commands, execute scripts, loop through tasks, and extend itself is not useful enough to evaluate.

Safety should be implemented as visible modes, explicit user choice, and recoverable traces, not as missing functionality.

Default:

```text
safe
```

Safe mode keeps unrestricted shell disabled and prefers scoped tools.

High-permission mode:

```text
danger
```

Danger mode is user-selected. The user accepts the consequences. The UI must make this visible, for example:

```text
v4flash!
perm=danger
```

Rules:

- Build the capability first when it is needed for real use.
- Keep the default mode conservative, but make stronger modes available to the user.
- Do not block the roadmap on release-grade safety polish before the feature exists.
- Do not silently enter danger mode.
- Do not hide high-permission status.
- Dangerous operations should be traceable.
- SMS, contacts, calls, account, payment, and destructive actions need explicit handling before becoming normal tools.

## Self-Extension Direction

The phone should eventually improve itself:

- generate scripts
- run scripts
- edit plugins
- run local tests
- inspect failures
- iterate on its own code

The model can be remote. The runtime and tools should be local.

Useful layers:

- `run_script` for workspace scripts
- `run_shell` only in danger mode
- plugin discovery
- generated task directory
- self-test commands
- explicit permission mode

## Current Known State

Already working:

- Termux CLI `ma`
- `ma tui`
- sessions and auto-resume
- DeepSeek/OpenAI-compatible chat
- tool calling
- context status and cache hit display
- native Termux API tools: battery, sensors, notification, torch, camera
- explicit safe/danger shell permission mode
- workspace script runner

Known limitation:

- Direct `uiautomator dump` from Termux can fail on some Android builds because `app_process` is inaccessible from the Termux sandbox.
- ADB shell can dump the UI tree, which proves the underlying Android capability exists.
- The product-grade solution should be Host App AccessibilityService first, with Shizuku as extension.

## Next Recommended Work

1. Create Android Host App base.
2. Add AccessibilityService.
3. Expose local API:

```text
GET /health
GET /screen
POST /tap
POST /swipe
POST /text
POST /key
POST /open-app
```

4. Add Termux tools that call the Host App API.
5. Show host/accessibility status in `ma tui`.
6. Add Shizuku adapter later as optional high-permission extension.

## Do Not Drift

Avoid turning the product into:

- a desktop remote-control tool
- a proot-only Linux agent
- an ADB-only automation script
- a Shizuku-only shell wrapper

Those can be useful temporary tools, but the product direction is a phone-native agent with a self-owned Android Host App base.
