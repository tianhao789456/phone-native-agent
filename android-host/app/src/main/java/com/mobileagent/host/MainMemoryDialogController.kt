package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

class MainMemoryDialogController(
    private val activity: Activity,
    private val runMemoryTool: (tool: String, args: JSONObject, title: String, formatter: (JSONObject) -> String) -> Unit,
    private val runDocsTool: (tool: String, args: JSONObject, title: String, formatter: (JSONObject) -> String) -> Unit,
) {
    fun showMemoryExperiencePanel() {
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(28, 18, 28, 8)

        fun addButton(label: String, action: () -> Unit) {
            val button = Button(activity)
            button.text = label
            button.setOnClickListener { action() }
            panel.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("记忆/经验")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .create()

        addButton("查看摘要") {
            dialog.dismiss()
            runMemoryTool("memory_summary", JSONObject(), "记忆摘要") { MemoryPanelFormatter.formatMemorySummary(it) }
        }
        addButton("搜索经验") {
            dialog.dismiss()
            showSingleInputDialog("搜索经验", "关键词 / app / tool_scope") { query ->
                runMemoryTool(
                    "experience_search",
                    JSONObject().put("query", query).put("limit", 20),
                    "经验搜索"
                ) { MemoryPanelFormatter.formatExperienceMatches(it) }
            }
        }
        addButton("调整置信度") {
            dialog.dismiss()
            showExperienceConfidenceDialog()
        }
        addButton("删除经验") {
            dialog.dismiss()
            showSingleInputDialog("删除经验", "输入经验 id") { value ->
                runMemoryTool(
                    "experience_delete",
                    JSONObject().put("id", value.trim().toIntOrNull() ?: -1),
                    "删除经验"
                ) { it.toString(2) }
            }
        }
        addButton("压缩经验") {
            dialog.dismiss()
            showExperienceCompactDialog()
        }
        addButton("生成 Procedure") {
            dialog.dismiss()
            showProcedureGenerateDialog()
        }
        addButton("Procedure 列表") {
            dialog.dismiss()
            runMemoryTool(
                "procedure_list",
                JSONObject().put("limit", 50),
                "Procedure 列表"
            ) { MemoryPanelFormatter.formatProcedureList(it) }
        }
        addButton("Skills 文档") {
            dialog.dismiss()
            runDocsTool(
                "docs_read",
                JSONObject().put("path", "docs/official/skills.md").put("max_bytes", 20000),
                "Skills 文档"
            ) { payload -> formatDocsRead(payload) }
        }

        dialog.show()
    }

    fun showLearningStartDialog() {
        val layout = verticalForm()
        val label = field("学习名称", "manual-demo")
        val app = field("app/package，可留空")
        val scope = field("tool_scope", "phone")
        layout.addView(label)
        layout.addView(app)
        layout.addView(scope)
        AlertDialog.Builder(activity)
            .setTitle("开始学习")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                val args = JSONObject()
                    .put("label", label.text.toString().ifBlank { "manual-demo" })
                    .put("app", app.text.toString().ifBlank { "unknown" })
                    .put("tool_scope", scope.text.toString().ifBlank { "phone" })
                runMemoryTool("learning_start", args, "开始学习") { it.toString(2) }
            }
            .show()
    }

    fun stopLearningMode() {
        runMemoryTool("learning_stop", JSONObject(), "结束学习") { it.toString(2) }
    }

    private fun showExperienceConfidenceDialog() {
        val layout = verticalForm()
        val id = field("经验 id")
        val confidence = field("high / medium / low", "high")
        layout.addView(id)
        layout.addView(confidence)
        AlertDialog.Builder(activity)
            .setTitle("调整置信度")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val args = JSONObject()
                    .put("id", id.text.toString().trim().toIntOrNull() ?: -1)
                    .put("confidence", confidence.text.toString().trim().ifBlank { "medium" })
                runMemoryTool("experience_update", args, "调整置信度") { it.toString(2) }
            }
            .show()
    }

    private fun showExperienceCompactDialog() {
        val layout = verticalForm()
        val app = field("app，可留空")
        val scope = field("tool_scope，可留空")
        val target = field("保留数量", "8")
        layout.addView(app)
        layout.addView(scope)
        layout.addView(target)
        AlertDialog.Builder(activity)
            .setTitle("压缩经验")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("压缩") { _, _ ->
                val args = JSONObject()
                    .put("app", app.text.toString())
                    .put("tool_scope", scope.text.toString())
                    .put("target", target.text.toString().trim().toIntOrNull() ?: 8)
                runMemoryTool("experience_compact", args, "压缩经验") { it.toString(2) }
            }
            .show()
    }

    private fun showProcedureGenerateDialog() {
        val layout = verticalForm()
        val app = field("app", "general")
        val scope = field("tool_scope", "windows_mcp")
        layout.addView(app)
        layout.addView(scope)
        AlertDialog.Builder(activity)
            .setTitle("生成 Procedure")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val args = JSONObject()
                    .put("app", app.text.toString().ifBlank { "general" })
                    .put("tool_scope", scope.text.toString())
                runMemoryTool("procedure_generate", args, "生成 Procedure") { it.toString(2) }
            }
            .show()
    }

    private fun showSingleInputDialog(title: String, hint: String, onSubmit: (String) -> Unit) {
        val edit = EditText(activity)
        edit.hint = hint
        edit.setSingleLine(false)
        edit.setPadding(28, 18, 28, 18)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onSubmit(edit.text.toString()) }
            .show()
    }

    private fun verticalForm(): LinearLayout {
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 8)
        return layout
    }

    private fun field(hint: String, value: String = ""): EditText {
        val edit = EditText(activity)
        edit.hint = hint
        if (value.isNotEmpty()) edit.setText(value)
        return edit
    }

    private fun formatDocsRead(payload: JSONObject): String {
        val lines = mutableListOf<String>()
        lines.add("路径: ${payload.optString("path", "-")}")
        lines.add("标题: ${payload.optString("title", "-")}")
        lines.add("")
        lines.add(payload.optString("content", ""))
        if (payload.optBoolean("truncated", false)) lines.add("\n... 文档已截断")
        return lines.joinToString("\n")
    }
}
