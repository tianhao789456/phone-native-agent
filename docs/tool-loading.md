# Tool Loading Contract

Mobile Agent uses progressive tool loading to keep the model prefix stable and small.

## Model-visible tools

The model does not receive every native tool schema by default. It receives:

- `tool_catalog`: lists available tools by name, category, and short description.
- `tool_info`: returns full schemas for requested tools.
- Small stable default tools, currently `get_time` and `device_info`.

After the model calls `tool_info(names=[...])`, the requested tools are included in the next model step for that run.

## Full registry

The full registry is still available to the app, CLI, HTTP API, tests, and direct execution:

- `ToolRegistry.openai_tools()` returns every registered tool schema.
- `ToolRegistry.list_metadata()` returns full metadata for UI/API display.
- `ToolRegistry.execute(name, arguments)` can execute any registered tool.

Only the model-facing list is thin by default through `ToolRegistry.model_tools(...)`.

## Categories

Tool categories are stable strings used by the catalog and future UI grouping:

- `system`
- `core`
- `workspace`
- `network`
- `host_bridge`
- `termux`
- `shell`
- `android_input`
- `plugin`
- `skill`
- `mcp`

Built-in phone toolkit categories are inferred from their module names. Plugin, skill, and MCP tools should pass an explicit category when registered.

## Extension rule

New tool providers should register concise descriptions first. Large examples, long usage notes, and provider-specific documentation should be returned through a detail tool or external docs command, not injected into the default model-visible tool list.
