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
            "ssh.md" to ssh(),
            "memory.md" to memory(),
            "memory-v2.md" to memoryV2(),
            "skills.md" to skills(),
            "plugin-authoring.md" to pluginAuthoring(),
            "task-loop-v2.md" to taskLoopV2(),
            "troubleshooting.md" to troubleshooting()
        )
    }

    private fun readme(): String {
        return """
            # 手机 Agent 官方文档

            这是 Android 应用内置的官方文档。它会同步到应用私有工作区：

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
            # 应用本地命令

            在应用聊天框里输入这些命令，不会发送给模型，会由应用本地执行。

            - `-new`：开启新会话。
            - `-status`：刷新运行状态。
            - `-tools`：显示当前内置工具名。
            - `-docs`：显示官方文档目录。
            - `-config`：打开配置窗口。
            - `-panel`：打开操作面板。
            - `-reconnect`：重连和自检核心、桥接、终端后端。
            - `-logs`：显示系统日志。
            - `-failures`：显示最近失败分析。
            - `-rounds 50`：设置工具调用轮数上限。
            - `-perm safe|ask|danger|developer`：切换权限模式。
            - `-terminal on|off|status|http://127.0.0.1:8787`：配置终端接口。
            - `-clear`：清空当前显示。
            - `-key sk-...`：保存模型 API Key 到应用私有配置。
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
        lines.add("完整机器可读索引请调用 `tool_registry`。需要某个工具参数时，再调用 `tool_info` 读取单个 schema。下面是当前 APK 内置工具摘要。")
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
            - 应用工具：`terminal_run`、`terminal_script`、`terminal_task_status`、`terminal_task_cancel`

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

            MCP 是应用连接远程电脑工具的桥。常见用途是让手机 Agent 通过 Tailscale 或同一内网连接 Windows MCP，再调用电脑侧工具。

            ## Agent 使用流程

            当用户要求操作电脑、Windows、桌面、远程电脑、文件、PowerShell、截图或 MCP 时，按这个顺序执行：

            1. 调用 `pc_bridge_status`，确认 SSH/MCP 总状态。
            2. 如果 MCP 离线、502、超时，或手机直连电脑 HTTP 不稳定，但 SSH 可能可用，调用 `pc_bridge_recover`。优先使用 SSH 隧道：手机 `127.0.0.1:18000` -> 电脑 `127.0.0.1:8000`。
            3. 调用 `mcp_servers`，查看已配置的电脑 MCP server id。只有一个时通常使用 `default`。
            4. 调用 `mcp_status`，带上 `server`，确认该 MCP 是否已配置、认证是否存在、远程端点是否可达。
            5. 调用 `mcp_tools`，带上 `server`，先读取远程服务器实际暴露的工具名、描述和参数名索引。
            6. 需要调用某个远程工具前，调用 `mcp_tool_info`，带上同一个 `server`，读取该工具的完整 schema。
            7. 如需执行远程工具，先用 `toolset_request` 申请 `mcp` 组或显式申请 `mcp_call`。
            8. 调用 `mcp_call`，带上同一个 `server`；`tool` 必须使用 `mcp_tools` 返回的精确工具名，`arguments` 必须符合 `mcp_tool_info` 返回的 schema。
            9. 远程动作执行后，用另一个读取、观察或状态工具验证结果。

            ## 常见 Windows MCP 工具

            远程服务器可能提供这些工具，具体以 `mcp_tools` 返回为准：

            - `PowerShell`：运行 Windows PowerShell 命令。
            - `FileSystem`：读写或列出电脑文件。
            - `Snapshot` / `Screenshot`：观察电脑界面。
            - `Click` / `Type` / `Scroll`：操作电脑界面。

            ## 注意

            - 应用本地命令 `-mcp status` 和 `-mcp tools` 是给用户手动检查用的，不会发送给模型。
            - Agent 自己应该使用 `mcp_servers`、`mcp_status`、`mcp_tools`、`mcp_tool_info`、`toolset_request` 和 `mcp_call`。
            - `mcp_servers`、`mcp_status`、`mcp_tools` 和 `mcp_tool_info` 是只读发现工具；`mcp_call` 属于高风险远程执行工具，需要权限模式和确认链路允许。
            - 电脑侧可以配置多个 MCP server，例如 `windows-system`、`windows-files`、`browser-mcp`。不同 server 里的工具名可能重复，所以调用时必须显式传 `server`。
            - 如果 Windows MCP token 或地址变化，使用 `mcp_configure` 更新对应 `server` 配置，不要让用户反复手动复制配置。
            - `mcp_configure` 支持 `id/server/name/type/base_url/auth_token/set_active/verify`，旧的单 MCP 配置会作为 `default` 自动兼容。
            - 手机到电脑 `8000` 直连失败时，不代表 Windows MCP 不可用；SSH 能连时优先通过 `pc_bridge_recover` 建隧道再测。
        """.trimIndent()
    }

    private fun ssh(): String {
        return """
            # SSH 远程电脑桥

            SSH 是手机 Agent 连接电脑后端的稳定命令通道。它适合：

            - 连接 Windows / Linux / macOS
            - 运行 PowerShell、bash、python、git、scp、sftp
            - 查看日志、重启服务、修复 MCP
            - 给 MCP 建本地隧道，绕过手机直连电脑 HTTP 的网络问题
            - 传文件：手机 -> 电脑、电脑 -> 手机

            ## 推荐流程

            1. 先在电脑上启用 OpenSSH Server。
            2. 手机上配置 Tailscale 或同一内网地址。
            3. 在 App 配置 SSH 主机、端口、用户名、私钥路径。
            4. 先用 `pc_bridge_status` 看 SSH/MCP 总状态。
            5. 手机不在同一局域网时，先用 `tailscale_preflight` 检查电脑 Tailscale 地址是否能返回 SSH banner；如果它打开了 Tailscale，提示用户连接 VPN 后重试。
            6. MCP 离线或 502 但 SSH 可能可用时，优先用 `pc_bridge_recover` 自动诊断/修复，不要让用户手打命令。默认优先建立 `127.0.0.1:18000 -> 127.0.0.1:8000` 的 SSH 隧道。
            7. 有多个地址时用 `ssh_select_host` 自动选择能返回 SSH banner 的地址。
            8. Tailscale 地址 TCP 能连但没有 SSH banner 时，用 `tailscale_ssh_diagnose` 从手机侧和 Windows 侧同时查。
            9. 用 `ssh_connect` 建立会话；需要转发电脑 HTTP 服务时用 `ssh_forward_start`。
            10. 用 `ssh_run` 执行命令；普通传文件用 `file_push` / `file_pull`。
            11. 手机文件要交给电脑处理再回传时，优先用 `pc_file_workflow`。

            ## 建议

            - Windows 远程优先用 PowerShell shell。
            - 私钥放在手机工作区或共享存储的受控目录里。
            - 失败时先看 `ssh_status` 和系统日志，不要盲目重试。
            - SSH 是控制底座，不替代 MCP；MCP 负责前台 GUI 工具。
            - MCP 直连失败时，SSH 隧道是首选恢复路径。隧道建立后 MCP 地址应变成 `http://127.0.0.1:18000/mcp`。
            - 远程出门场景不能依赖 LAN 地址；Tailscale 预检成功后优先使用 `100.x.x.x` 地址。
            - 没有 MCP 启动命令时，`pc_bridge_recover` 会先完成诊断并返回 `diagnosed_restart_command_required`；拿到明确启动命令后再传入 `restart_command`。
            - `pc_file_workflow` 支持 `{remote_path}` 和 `{local_path}` 占位符，可上传手机文件、运行电脑命令、再把结果拉回手机。

            ## 快速排障

            - Agent 应优先调用 `ssh_diagnose`，不要让用户手敲 `nc`。
            - 正常 SSH banner 类似 `SSH-2.0-OpenSSH_for_Windows_9.5`。
            - 如果 TCP 能连但 banner 为空，优先排查 Tailscale/VPN 路由或 Windows 防火墙，不要先怀疑密钥。
            - 如果 `tailscale_preflight` 返回 `tailscale_not_connected_or_unreachable`，先连接手机 Tailscale，再重试；这不是密钥错误。
            - 如果 `ssh_connect` 成功但 `ssh_status` 显示断开，先调用 `ssh_status` 看 `last_error`，再 `ssh_connect` 重连。
            - 如果要推送 `shared_storage:/Download/...` 或微信导出的共享存储文件，先用 `storage_permission_status` 检查；必要时调用 `storage_permission_open_settings` 打开授权页。
            - MCP 502 时，用 `pc_bridge_recover` 看电脑本机端口、MCP 进程、Tailscale Serve/Funnel 和 HTTP probe；不要只看手机上的 HTTP 错误。SSH 可用时让它自动建隧道并同步 token。
        """.trimIndent()
    }

    private fun memory(): String {
        return """
            # Mobile Agent Memory V1

            Memory V1 让应用记住稳定的用户事实和可复用执行经验，不引入向量数据库、知识图谱、本地模型、复杂权限或完整 Learning Mode。

            ## 存储位置

            记忆存储在应用私有工作区：

            - `memory/user_profile.json`
            - `memory/experience_log.json`
            - `memory/task_reflections.jsonl`

            ## 两层记忆

            UserMemory 记录用户和环境：

            - 语言和回答风格偏好
            - 位置或时区提示
            - 常用 App、端点和工作区
            - 用户偏好和不要做的规则
            - 任务历史和最终答案

            ExperienceLog 记录可复用操作经验：

            - 成功导航
            - 失败路径
            - UI 知识
            - 时机和等待策略
            - 工具参数经验

            ## 使用规则

            - 用户问个人偏好、环境、常用路径时，先查 UserMemory。
            - 重复手机任务、SSH、MCP、终端任务时，先查 ExperienceLog 和 Procedure。
            - 工具任务完成后，把稳定事实和可复用经验写入记忆。
            - 不要逐字保存 API Key、密码、私钥等秘密。
        """.trimIndent()
    }

    private fun memoryV2(): String {
        return """
            # Memory V2、Procedure 与 Learning

            Memory V2 把一次性日志整理成可复用技能：

            - Learning trace：记录观察、动作、结果。
            - Experience：从成功/失败中提炼经验。
            - Procedure：把经验生成可读的 Markdown 操作流程。
            - Plugin：把稳定流程封装成可运行 manifest。

            ## 推荐顺序

            1. 先用 `procedure_search` 查已有流程。
            2. 再用 `experience_search` 查经验。
            3. 没有可用流程时，按 plan -> act -> verify -> retry 执行。
            4. 成功后用 `procedure_generate` 生成或更新流程。
            5. 如果用户明确要求“一键跑”“做成技能”，再创建插件。

            ## Procedure 与 Plugin 的区别

            - Procedure 是给模型读的操作说明，灵活，适合变化较大的任务。
            - Plugin 是可运行的固定工作流，适合稳定、重复、工具链明确的任务。

            两者可以配合：先从经验生成 Procedure，再把稳定部分做成 Plugin。
        """.trimIndent()
    }

    private fun skills(): String {
        return """
            # Mobile Agent Skills

            Skills 是项目级可复用能力包装方式，用来处理重复任务。

            当前不需要一开始就做复杂插件运行时，因为核心构件已经存在：

            - Procedure：从经验和记忆生成的可复用流程。
            - Experience：持久执行经验。
            - Learning Mode：轻量轨迹，可生成新流程或经验。
            - MCP bridge：连接 Windows 或其他桌面服务器的远程工具层。
            - Plugin：把稳定工具链封装成可运行 manifest。

            ## 推荐 skill 形态

            一个 skill 应该：

            - 面向具体任务。
            - 知道该用哪些工具。
            - 能被模型用短上下文读懂。
            - 基于证据或 procedure，而不只是散文说明。
            - 足够短，保持缓存友好。

            ## 适合做 skill 的例子

            - 点外卖。
            - 发消息。
            - 搜索文件。
            - 通过 MCP 启动桌面任务。
            - 重复一个已知手机流程。
            - 通过 SSH 在电脑上跑固定脚本。

            ## Agent 顺序

            当任务已有 skill 或 procedure 时，先搜索 Procedure，再查 Experience，再查 UserMemory，然后一步一步执行并验证。

            当没有 skill 时，先完成真实任务；如果成功轨迹有复用价值，再把它提炼成 Procedure 或 Plugin。

            插件型技能请阅读 `docs/official/plugin-authoring.md`。
        """.trimIndent()
    }

    private fun pluginAuthoring(): String {
        return """
            # 插件与技能创建

            插件是手机 Agent 的项目内自扩展层。它不替代原生工具，而是把已经存在的原生工具封装成可复用工作流，让下次不用重新想一遍。

            ## 什么时候创建插件

            用户说这些意思时，可以创建插件：

            - “以后这个流程固定下来”
            - “给我做成一个技能”
            - “下次一键跑这个电脑脚本”
            - “把 SSH/MCP/手机操作封装一下”
            - “某个 App 的固定操作流程”

            不要为一次性闲聊创建插件。先确认任务有复用价值，或者用户明确要求保存成技能/插件。

            ## 插件能封装什么

            当前插件是 manifest 工作流型插件，可以封装：

            - 手机只读观察：`accessibility_snapshot_v2`、`host_screen_find`、`host_wait_for_text`
            - 手机动作：开发者模式下可通过正常权限门执行点击、输入、滑动等工具
            - 终端脚本：开发者模式下可封装 `terminal_run`、`terminal_script`
            - SSH 电脑控制：`ssh_status`、`ssh_connect`、`ssh_run`、`pc_file_workflow`
            - MCP 电脑工具：先 `pc_bridge_status` / `pc_bridge_recover`，再 `mcp_tools` / `mcp_call`
            - 记忆与经验：`experience_search`、`procedure_search`、`procedure_generate`

            插件工作流不能递归调用 `plugin_run`，也不能调用不存在的工具。

            ## 创建流程

            1. 用 `docs_read` 阅读本文，并用 `tool_registry` 确认当前工具名；需要参数时再用 `tool_info` 读取单个 schema。
            2. 如果是已有经验，先用 `procedure_search` 和 `experience_search` 查找可复用步骤。
            3. 设计一个短 manifest：id、name、description、workflows。
            4. 调用 `plugin_create` 写入插件。
            5. 调用 `plugin_validate`，必须 `ok=true`。
            6. 调用 `plugin_test`，必须 `ok=true`。
            7. 需要执行时调用 `plugin_run`。
            8. 执行后读 `plugin_reports` / `plugin_report_read`，把失败经验写入 memory 或 experience。

            ## Manifest 模板

            ```json
            {
              "id": "pc-health-check",
              "name": "电脑健康检查",
              "version": "0.1.0",
              "description": "检查 SSH、MCP 和电脑命令执行能力。",
              "enabled": true,
              "tools": [],
              "workflows": [
                {
                  "name": "run",
                  "description": "检查手机到电脑的桥接状态并运行一条 PowerShell 验证命令。",
                  "steps": [
                    {
                      "tool": "pc_bridge_status",
                      "arguments": {
                        "diagnose_ssh": true
                      },
                      "stop_on_failure": false
                    },
                    {
                      "tool": "ssh_run",
                      "arguments": {
                        "command": "Write-Output mobile-agent-pc-ok; hostname",
                        "shell": "powershell",
                        "timeout_ms": 30000
                      },
                      "stop_on_failure": true
                    }
                  ]
                }
              ]
            }
            ```

            ## 手机任务插件建议

            手机 App 流程不要只写点击坐标。优先：

            1. `accessibility_snapshot_v2` 看当前 App 和屏幕。
            2. `host_screen_find` 找文字、按钮、输入框。
            3. 动作后用 `host_wait_for_text` 或再次 `accessibility_snapshot_v2` 验证。
            4. 失败时停止并报告，而不是反复点同一个位置。

            ## SSH/电脑任务插件建议

            电脑任务优先走 SSH，因为 SSH 能跑 PowerShell、传文件、修复 MCP、启动服务。

            推荐顺序：

            1. `pc_bridge_status`
            2. 必要时 `pc_bridge_recover`
            3. 简单命令用 `ssh_run`
            4. 手机文件交给电脑处理用 `pc_file_workflow`
            5. 需要 GUI/Windows 原生工具时再用 MCP

            ## 命名规则

            - `id` 使用小写英文、数字、点、下划线、短横线。
            - 一个插件只负责一个稳定场景。
            - workflow 名称优先用 `run`、`check`、`repair`、`export` 这类短名字。
            - 描述要写清楚触发场景，方便 Agent 以后搜索和选择。

            ## 最低验收

            一个插件只有在这些都满足时，才算创建完成：

            - `plugin_create.ok=true`
            - `plugin_validate.ok=true`
            - `plugin_test.ok=true`
            - 如果用户要求实测，`plugin_run.ok=true`
            - 最终回复里说明插件 id、workflow 名、报告路径和验证结果
        """.trimIndent()
    }

    private fun taskLoopV2(): String {
        return """
            # Task Loop V2

            Task Loop V2 让手机 Agent 更接近桌面 Agent 的执行闭环。

            ## Runtime 字段

            每个工具步骤记录：

            - `closed_loop`：步骤阶段和状态。
            - `evidence`：从工具输出、验证或观察里提取的简短证据。
            - `verification_state`：是否还有待验证项，以及下一步必须做什么。

            最终 `task_loop` 记录：

            - `evidence`：本轮任务证据列表。
            - `completion_review`：原生侧最终复核，判断任务是否能算已验证完成。

            ## Agent 规则

            1. 每个工具结果都要读 `task_loop_v2_instruction`。
            2. 如果手机动作创建了待验证项，必须用 `after_observe`、`accessibility_snapshot_v2`、`host_wait_for_text` 或其他只读观察验证后，才能声称完成。
            3. 工具失败时要改变参数或策略，不要盲目重复同一个失败调用。
            4. 如果 `completion_review.status=needs_attention`，先报告阻塞点或执行所需验证，不要直接说完成。
            5. 最终答复引用工具输出、验证字段、`task_loop.evidence`、任务报告或任务产物里的具体证据。

            ## Failure Fuse

            原生核心会按工具和错误摘要追踪重复失败。如果同一失败模式重复太多次，循环会停止并返回结构化 blocker，而不是继续烧轮次。

            ## 目标

            有用的 Agent 不是“模型 + 工具”这么简单，它需要稳定闭环：

            ```text
            plan -> act -> observe -> verify -> retry/change strategy -> final evidence
            ```
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

            ## 模型没反应或不知道在干什么

            看应用顶部运行事件，或调用 `/events?after=0&limit=50`。

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

            打开应用操作面板，点“无障碍设置”，确认 `Mobile Agent Host` 已启用。

            ## 共享存储权限 401

            如果通知里出现：

            ```text
            FileUtils Error: working directory ... is not readable. Permission Denied
            ```

            这通常是 Android 共享存储权限或工作目录权限问题，不是模型、MCP 或 API 问题。

            处理顺序：

            1. 调用 `storage_permission_status` 看当前权限。
            2. 必要时调用 `storage_permission_open_settings` 打开授权页。
            3. 优先使用应用私有工作区；只有用户明确要求处理 Download/微信文件时再访问共享存储。
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
