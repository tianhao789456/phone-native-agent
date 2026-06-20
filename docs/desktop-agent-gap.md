# Desktop Agent Capability Gap

This document records the core desktop-agent capabilities that still need to be migrated or rebuilt for the phone-native agent.

## Current Baseline

The current phone agent can:

- chat through the Android Host App native core
- persist sessions and basic status
- call cloud model APIs
- expose Host App Accessibility tools
- observe the current Android screen as structured text
- execute confirmed screen actions in `ask` mode
- attach `after_observe` to action results
- show model, context, cache, permission, and terminal status in the APP header

This is enough for a real minimal phone-agent loop, but it is still not equivalent to a mature desktop coding/operating agent.

## Missing Desktop-Agent Core Capabilities

### 1. Deeper Task Loop

Desktop agents usually run a managed loop:

```text
understand task -> plan -> observe -> act -> observe -> revise -> continue -> report
```

Current state:

- The phone agent can do `observe -> act -> observe`.
- It does not yet have durable multi-step plan state, step status, retries, or structured failure recovery.

Needed:

- explicit task plan state
- step-by-step execution records
- automatic retry or fallback after failed actions
- stop conditions and final reporting rules

### 2. Native Workspace And File Tools

Desktop agents are useful because they can inspect and modify a project.

Needed in the Android-native layer:

- list directory
- read file
- write file
- search text
- create generated task folders
- keep file backups or patches
- run local tests through a controlled backend

Termux already covers part of this, but the APP-native core does not yet own a full workspace interface.

### 3. Terminal And Script Execution

Desktop agents rely heavily on shell access.

Current state:

- Termux is optional.
- `danger` mode is reserved for higher-power operations.
- Terminal delegation is not yet product-grade in the APP UI.

Needed:

- terminal tool detail UI
- command preview before execution
- output streaming or progressive display
- timeout and cancellation
- long-output folding
- strict permission mode display
- clear separation between workspace-limited scripts and unrestricted shell

### 4. Tool Call Detail And Confirmation UX

Desktop agents make tool execution visible and debuggable.

Needed:

- expandable tool-call cards
- tool name, arguments, result, duration, and error display
- risk level per tool
- action target preview before confirmation
- long result folding
- persistent trace history
- copy/debug affordances for failed tool calls

For phone control, confirmation should show what will happen, for example:

```text
tool: host_click_text
target: 配置
mode: ask
risk: screen action
```

### 5. Subagents And Parallel Tasks

Desktop agents can split work across helper agents.

Needed first as a lightweight local task system:

- `spawn_task(name, prompt)`
- `list_tasks`
- `read_task_result`
- `cancel_task`
- persistent task records
- later: concurrent model calls or Termux workers

The first version does not need true unlimited parallelism, but the architecture should not block it.

### 6. Long-Term Memory And Context Compression

The phone agent currently shows context and cache status, but mature agents need more.

Needed:

- manual context compaction
- automatic context compaction
- persistent project memory
- user preference memory
- tool trace summarization
- failure and recovery notes
- session resume with compressed history

### 7. Plugin And MCP-Like Tool Ecosystem

Current phone tools are mostly built in.

Needed:

- plugin manifest format
- dynamic tool registry
- plugin enable/disable
- permission declarations per plugin
- plugin debugging
- MCP-like bridge later if useful

### 8. Browser, Search, And Research Tools

Desktop agents can inspect the web and repositories.

Needed:

- web search
- HTTP/page fetch
- page summarization
- GitHub search and repository inspection
- download tools
- browser intent/control tools where appropriate

### 9. More Reliable UI Automation

Current selectors are useful but still early.

Needed:

- stable node ids
- compact node detail view
- target re-resolution before acting
- scroll-to-find
- keyboard and dialog state handling
- fallback when text/index/id changes
- action retry rules

### 10. Persistent Phone Base

The phone base should survive normal app switching.

Needed:

- foreground service
- persistent notification
- bridge health monitoring
- clean restart behavior
- Host App and Termux auto-discovery
- no reliance on desktop ADB forwarding for normal use

## Recommended Priority

The current product stage is a personal prototype, not a public release. Prioritize real capability first, then safety hardening and polish.

1. Android-native workspace/file tools.
2. Terminal and script execution loop.
3. Multi-step task loop and failure recovery.
4. Subagent task system.
5. Memory and context compression.
6. Plugin/tool ecosystem.
7. Search/browser/GitHub tools.
8. Persistent foreground service.
9. Tool-call details and confirmation UX.
10. Shizuku extension.

Safety is still required as a control surface, but it should not prevent core features from existing. Use clear modes:

- `safe`: default, conservative, observation and scoped operations.
- `ask`: interactive actions with confirmation.
- `danger`: high-capability personal mode; user accepts consequences.

For this phase, avoid spending disproportionate time on release-grade guardrails before the feature is usable.

## Product Principle

The phone agent should not become a desktop remote-control accessory. The mature target is:

```text
phone-native frontend
phone-native base
phone-local tools and task state
cloud model for reasoning
desktop only as development scaffold
```
