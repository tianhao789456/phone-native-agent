package com.mobileagent.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MobileAgentDocs {
    private const val ROOT = "docs/official"

    fun index(context: Context): JSONObject {
        val docs = documents(context)
        val items = JSONArray()
        docs.forEach { (path, content) ->
            items.put(
                JSONObject()
                    .put("path", "$ROOT/$path")
                    .put("title", titleOf(content, path))
                    .put("bytes", content.toByteArray(Charsets.UTF_8).size)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("root", ROOT)
            .put("count", items.length())
            .put("documents", items)
    }

    fun read(context: Context, path: String, maxBytes: Int = 40000): JSONObject {
        val normalized = normalizeDocPath(path)
        val content = documents(context)[normalized]
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Unknown official doc: $path")
                .put("root", ROOT)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val limit = maxBytes.coerceIn(1_000, 200_000)
        val slice = bytes.copyOfRange(0, minOf(bytes.size, limit)).toString(Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("path", "$ROOT/$normalized")
            .put("title", titleOf(content, normalized))
            .put("bytes", bytes.size)
            .put("content", slice)
            .put("truncated", bytes.size > limit)
    }

    fun search(context: Context, query: String, maxMatches: Int = 30): JSONObject {
        if (query.isBlank()) {
            return JSONObject().put("ok", false).put("error", "query is required")
        }
        val matches = JSONArray()
        val limit = maxMatches.coerceIn(1, 100)
        documents(context).forEach { (path, content) ->
            if (matches.length() >= limit) return@forEach
            content.lines().forEachIndexed { index, line ->
                if (matches.length() >= limit) return@forEachIndexed
                if (line.contains(query, ignoreCase = true)) {
                    matches.put(
                        JSONObject()
                            .put("path", "$ROOT/$path")
                            .put("line", index + 1)
                            .put("text", line.take(500))
                    )
                }
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put("matches", matches)
    }

    fun syncToWorkspace(context: Context, workspace: MobileWorkspace): JSONObject {
        val written = JSONArray()
        documents(context).forEach { (path, content) ->
            val workspacePath = "$ROOT/$path"
            val existing = workspace.read(workspacePath, 1_000_000)
            if (!existing.optBoolean("ok", false) || existing.optString("content") != content) {
                val result = workspace.write(workspacePath, content, overwrite = true)
                written.put(
                    JSONObject()
                        .put("path", workspacePath)
                        .put("ok", result.optBoolean("ok", false))
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("root", ROOT)
            .put("written", written)
            .put("written_count", written.length())
            .put("index", index(context))
    }

    private fun documents(context: Context): Map<String, String> {
        return linkedMapOf(
            "README.md" to readme(),
            "commands.md" to commands(),
            "tools.md" to tools(),
            "permissions.md" to permissions(context),
            "terminal.md" to terminal(),
            "mcp.md" to mcp(),
            "memory.md" to memory(),
            "memory-v2.md" to memoryV2(),
            "skills.md" to skills(),
            "task-loop-v2.md" to taskLoopV2(),
            "troubleshooting.md" to troubleshooting()
        )
    }

    private fun readme(): String {
        return """
            # 手机 Agent 官方文档

            这是随 Android APP 内置的官方文档。它会同步到 APP 私有工作区：

            `docs/official/`

            Agent 可以用这些工具读取文档：

            - `docs_index`：列出官方文档。
            - `docs_read`：读取某篇文档。
            - `docs_search`：搜索官方文档。
            - `docs_sync`：把内置文档重新同步到工作区。

            常用入口：

            - 本地命令：`-help`、`-docs`、`-tools`、`-status`、`-config`、`-panel`
            - 工具清单：`tool_registry`
            - 自检：`self_health_check`
            - 日志：`system_logs`

            原则：功能优先，默认可见可追踪。当前是个人原型，默认安全模式保守，但开发者模式可用于连续调试。
        """.trimIndent()
    }

    private fun commands(): String {
        return """
            # APP 本地命令

            在 APP 聊天框里输入这些命令，不会发送给模型，会由 APP 本地执行。

            - `-new`：开启新会话。
            - `-status`：刷新运行状态。
            - `-tools`：显示当前内置工具名。
            - `-docs`：显示官方文档目录。
            - `-config`：打开配置窗口。
            - `-panel`：打开操作面板。
            - `-reconnect`：重连/自检核心、桥接、终端后端。
            - `-logs`：显示系统日志。
            - `-failures`：显示最近失败分析。
            - `-rounds 50`：设置工具调用轮数上限。
            - `-perm safe|ask|danger|developer`：切换权限模式。
            - `-terminal on|off|status|http://127.0.0.1:8787`：配置终端接口。
            - `-clear`：清空当前显示。
            - `-key sk-...`：保存模型 API Key 到 APP 私有配置。
            - `-help`：显示帮助。

            说明：

            - 本地命令适合人直接操作。
            - Agent 需要了解能力时，应优先使用 `docs_index`、`docs_read`、`tool_registry`。
        """.trimIndent()
    }

    private fun tools(): String {
        val lines = mutableListOf<String>()
        lines.add("# Native Core 工具清单")
        lines.add("")
        lines.add("完整机器可读清单请调用 `tool_registry`。下面是当前 APK 内置工具摘要。")
        lines.add("")
        NativeToolRegistry.descriptors
            .groupBy { it.category }
            .toSortedMap()
            .forEach { (category, descriptors) ->
                lines.add("## $category")
                lines.add("")
                descriptors.forEach { tool ->
                    lines.add("- `${tool.name}`：${tool.description}")
                    lines.add("  - access=${tool.access.name.lowercase()} risk=${tool.risk.name.lowercase()} auto_recover=${tool.autoRecover}")
                }
                lines.add("")
            }
        return lines.joinToString("\n")
    }

    private fun permissions(context: Context): String {
        val config = AgentRuntimeConfig(context)
        val modes = config.permissionModesJson()
        val lines = mutableListOf<String>()
        lines.add("# 权限模式")
        lines.add("")
        lines.add("当前阶段是个人原型：先保证能力闭环，再做发布级安全细分。权限模式必须可见、可切换、可追踪。")
        lines.add("")
        for (index in 0 until modes.length()) {
            val mode = modes.optJSONObject(index) ?: continue
            lines.add("## `${mode.optString("id")}` - ${mode.optString("label")}")
            lines.add("")
            lines.add(mode.optString("description"))
            lines.add("")
        }
        lines.add("当前配置：`${config.permissionMode()}`")
        return lines.joinToString("\n")
    }

    private fun terminal(): String {
        return """
            # 终端和脚本能力

            终端能力通过可选 Termux HTTP 后端提供：

            - 默认地址：`http://127.0.0.1:8787`
            - 启动脚本：`scripts/start-http-termux.sh`
            - APP 工具：`terminal_run`、`terminal_script`、`terminal_task_status`、`terminal_task_cancel`

            推荐用法：

            - 单条命令用 `terminal_run`。
            - 多行脚本、测试、生成文件、长输出任务用 `terminal_script`。
            - 后台脚本用 `terminal_script` 的 `wait=false`，再用 `terminal_task_status` 查看。
            - 卡住时用 `terminal_task_cancel`。
            - 终端连不上时先用 `diagnose_terminal`，再用 `recover_terminal_backend` 或本地命令 `-reconnect`。

            注意：

            - 终端工具依赖 Termux 后端是否启动。
            - 开发者模式会自动通过本机确认，适合个人调试。
        """.trimIndent()
    }

    private fun mcp(): String {
        return """
            # MCP 远程电脑工具

            MCP 是 APP 连接远程电脑工具的桥。当前常见用途是让手机 Agent 通过 Tailscale 或同一内网连接 Windows MCP，再调用电脑侧工具。

            ## Agent 使用流程

            当用户要求操作电脑、Windows、桌面、远程电脑、文件、PowerShell、截图或 MCP 时，按这个顺序执行：

            1. 调用 `mcp_status`，确认 MCP 是否已配置、认证是否存在、远程端点是否可达。
            2. 调用 `mcp_tools`，读取远程服务器实际暴露的工具名和参数结构。
            3. 如需执行远程工具，先用 `toolset_request` 申请 `mcp` 组或显式申请 `mcp_call`。
            4. 调用 `mcp_call`，其中 `tool` 必须使用 `mcp_tools` 返回的精确工具名，`arguments` 必须符合远程工具 schema。
            5. 远程动作执行后，用另一个读取、观察或状态工具验证结果。

            ## 常见 Windows MCP 工具

            远程服务器可能提供这些工具，具体以 `mcp_tools` 返回为准：

            - `PowerShell`：运行 Windows PowerShell 命令。
            - `FileSystem`：读写或列出电脑文件。
            - `Snapshot` / `Screenshot`：观察电脑界面。
            - `Click` / `Type` / `Scroll`：操作电脑界面。

            ## 注意

            APP 本地命令 `-mcp status` 和 `-mcp tools` 是给用户手动检查用的，不会发送给模型。Agent 自己应使用 `mcp_status`、`mcp_tools`、`toolset_request` 和 `mcp_call`。

            `mcp_status` 和 `mcp_tools` 是只读发现工具；`mcp_call` 属于高风险远程执行工具，需要当前权限模式和确认链路允许后才能执行。
        """.trimIndent()
    }

    private fun memory(): String {
        return """
            # Mobile Agent Memory V1

            Memory V1 lets the APP remember durable user facts and reusable execution lessons without adding a vector database, knowledge graph, local model, complex permissions, or Learning Mode.

            ## Storage

            Memory is stored in the APP private workspace:

            - `memory/user_profile.json`
            - `memory/experience_log.json`
            - `memory/task_reflections.jsonl`

            ## Two memory layers

            UserMemory records who the user is and what the environment looks like:

            - language and answer style preferences
            - location or timezone hints
            - common apps, endpoints, and workspaces
            - user preferences and do-not-do rules
            - task history and final answers

            ExperienceLog records how to do things:

            - `successful_navigation`: what worked before
            - `failed_approach`: what failed and should not be repeated
            - `ui_knowledge`: app layout or selector knowledge
            - `timing`: wait times and loading behavior
            - `environment`: verified runtime facts
            - `general`: reusable advice

            ## Agent workflow

            Before repeated tasks, phone-control tasks, desktop/MCP tasks, terminal tasks, or user-specific questions:

            1. Use `memory_query` when the question may already be answerable from memory.
            2. Use `memory_search` for user preferences, environment facts, and task history.
            3. Use `experience_search` for app, phone, terminal, or Windows MCP execution lessons.
            4. Use only the relevant results. Do not stuff the full memory files into the prompt.

            After tool-based tasks:

            1. Record durable user facts with `memory_write` when they are useful later.
            2. Record reusable success/failure lessons with `experience_record`.
            3. Use `experience_compact` when a scope has too many noisy lessons.

            ## Automatic behavior

            The native core also performs automatic retrieval before model calls and injects a short, fixed-format context block:

            ```text
            ## Relevant Memory
            ...

            ## Relevant Experience
            ...
            ```

            The core records task history and useful tool-derived lessons after tasks. Manual memory tools are still useful when the model knows a fact should be preserved.

            ## Cache note

            DeepSeek context caching benefits from stable request prefixes. Keep permanent instructions in the stable system prompt and keep memory injection short, relevant, and in a fixed format after the stable prompt. Do not rewrite the full system prompt with dynamic memory.

            ## Safety

            Do not store secrets verbatim. API keys, tokens, passwords, payment data, and private chat contents should be omitted or masked.
        """.trimIndent()
    }

    private fun memoryV2(): String {
        return """
            # Mobile Agent Memory V2

            Memory V2 adds three practical layers on top of Memory V1: Procedure, Experience UI, and lightweight Learning Mode. It does not add a local model, vector database, knowledge graph, or screenshot-based 8fps visual recognition.

            ## Storage

            APP private workspace paths:

            - `memory/user_profile.json`
            - `memory/experience_log.json`
            - `memory/task_reflections.jsonl`
            - `memory/procedures/`
            - `memory/demos/`

            ## Procedure

            Procedures are Markdown playbooks generated from ExperienceLog by app or tool scope.

            Tools:

            - `procedure_search`
            - `procedure_generate`
            - `procedure_read`
            - `procedure_list`

            A procedure contains:

            - Scope
            - Pre-observe
            - Standard Steps
            - Verification
            - Failure Handling
            - Source Experience Summary
            - Updated At

            Windows MCP procedures are stored at:

            `memory/procedures/windows_mcp.md`

            ## Agent Order

            For repeated tasks, phone-control tasks, Windows MCP tasks, terminal tasks, and user-specific tasks:

            1. Search relevant Procedure first.
            2. Search ExperienceLog next.
            3. Search UserMemory when user facts or environment facts may matter.
            4. Execute one step at a time and verify with evidence.
            5. Record reusable lessons after useful tool runs.

            ## Experience UI

            The Android operation panel has a `记忆/经验` entry. It can show memory summary, grouped experiences, search lessons, update confidence, delete one lesson, compact lessons, generate procedures, and list procedures.

            ## Learning Mode

            Lightweight Learning Mode records a demo trace under `memory/demos/`.

            Tools:

            - `learning_start`
            - `learning_record`
            - `learning_stop`
            - `learning_status`

            APP panel entries:

            - `开始学习`
            - `结束学习`

            The first version records available app/package, event summaries, screen summaries, and time. It does not do screenshot vision recognition. Stopping a session writes a demo summary and extracts at least one ExperienceLog lesson or Procedure.

            ## DeepSeek Cache

            Keep the fixed system prompt stable. Do not write dynamic memory or procedures into the persistent system prompt. The native core injects a short fixed-format dynamic block after the stable prompt:

            ```text
            [MOBILE_AGENT_RELEVANT_MEMORY_V2]
            ## Relevant Memory
            ...

            ## Relevant Procedure
            ...

            ## Relevant Experience
            ...
            ```
        """.trimIndent()
    }

    private fun skills(): String {
        return """
            # Mobile Agent Skills

            Skills are the project-level way to package reusable behavior for repeated tasks.
            This repository does not need a separate heavyweight plugin runtime for the first useful version.
            It already has the core building blocks:

            - Procedure: reusable playbooks generated from experience and memory.
            - Experience: durable execution lessons.
            - Learning Mode: lightweight traces that can produce new procedures or lessons.
            - MCP bridge: a remote tool layer for Windows or other desktop servers.

            ## Recommended skill shape

            A skill should be:

            - Task scoped
            - Tool aware
            - Readable by the model in a short fixed prefix
            - Backed by evidence or procedures, not only prose
            - Small enough to keep cache-friendly

            ## Good examples

            - Order food
            - Send a message
            - Search a file
            - Start a desktop task through MCP
            - Repeat a known phone workflow

            ## Agent order

            When a skill exists for the task, search Procedure first, then Experience, then UserMemory, then execute one step at a time and verify.
            When no skill exists, create one from the successful trace and keep it short.

            ## Why this matters

            Skills are how Mobile Agent grows without stuffing every past task into the live prompt.
            They are also the cleanest place to document "how to do this again next time" for high-frequency workflows.
        """.trimIndent()
    }

    private fun taskLoopV2(): String {
        return """
            # Task Loop V2

            Task Loop V2 strengthens the native managed loop so Mobile Agent behaves more like a desktop agent.

            ## Runtime Fields

            Every tool step records:

            - `closed_loop`: step phase and status.
            - `evidence`: concise evidence extracted from tool output, verification, or observation.
            - `verification_state`: whether a verification item is still pending and what the next step must do.

            The final `task_loop` records:

            - `evidence`: evidence list for the run.
            - `completion_review`: final native review of whether the task can be treated as verified.

            ## Rules For The Agent

            1. Read `task_loop_v2_instruction` from every tool result.
            2. If a phone action creates a pending verification item, verify with `after_observe`, `host_observe`, `host_wait_for_text`, or another read-only observation before claiming completion.
            3. If a tool fails, change arguments or strategy. Do not repeat the same failed call blindly.
            4. If `completion_review.status=needs_attention`, report the blocker or perform the required verification before saying the job is done.
            5. Cite concrete evidence from tool output, verification, `task_loop.evidence`, task reports, or task artifacts.

            ## Failure Fuse

            The native core now tracks repeated failure patterns by tool and error summary. If the same failure pattern repeats too many times, the loop stops with a structured blocker instead of burning more rounds.

            ## Why This Matters

            A useful agent is not just a model with tools. It needs a closed execution loop:

            ```text
            plan -> act -> observe -> verify -> retry/change strategy -> final evidence
            ```

            Task Loop V2 makes that loop visible and enforceable in runtime data.
        """.trimIndent()
    }

    private fun troubleshooting(): String {
        return """
            # 故障排查

            常用工具：

            - `self_health_check`：检查 API Key、权限模式、无障碍、workspace、终端后端。
            - `system_logs`：读取核心、API、桥接、工具、恢复日志。
            - `diagnose_terminal`：诊断终端后端。
            - `task_failure_latest`：读取最近失败分析。
            - `docs_search`：搜索官方文档。

            常见问题：

            ## 模型没反应或不知道在干什么

            看 APP 顶部运行事件，或调用 `/events?after=0&limit=50`。

            ## 工具明明有但 Agent 不知道

            让 Agent 调用：

            1. `docs_index`
            2. `docs_read`
            3. `tool_registry`

            ## 终端连不上

            先运行 `diagnose_terminal`。如果 Termux 后端没启动，在 Termux 里运行：

            ```bash
            cd /storage/emulated/0/Download/mobile-agent
            sh scripts/start-http-termux.sh
            ```

            ## 无障碍工具不可用

            打开 APP 操作面板，点“无障碍设置”，确认 `Mobile Agent Host` 已启用。
        """.trimIndent()
    }

    private fun normalizeDocPath(path: String): String {
        var value = path.trim().replace('\\', '/').trimStart('/')
        if (value.startsWith("$ROOT/")) value = value.removePrefix("$ROOT/")
        if (value.startsWith("official/")) value = value.removePrefix("official/")
        require(value.isNotBlank()) { "doc path is required" }
        require(!value.contains("..")) { "doc path cannot contain .." }
        return value
    }

    private fun titleOf(content: String, fallback: String): String {
        return content.lineSequence()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?: fallback
    }
}
