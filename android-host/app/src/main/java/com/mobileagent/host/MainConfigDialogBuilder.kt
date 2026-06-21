package com.mobileagent.host

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

data class MainConfigDialogForm(
    val layout: LinearLayout,
    val modeGroup: RadioGroup,
    val modeById: Map<Int, String>,
    val terminalEnabled: CheckBox,
    val terminalUrl: EditText,
    val mcpEnabled: CheckBox,
    val mcpUrl: EditText,
    val mcpToken: EditText,
    val sshEnabled: CheckBox,
    val sshHost: EditText,
    val sshPort: EditText,
    val sshUser: EditText,
    val sshKeyPath: EditText,
    val sshPassphrase: EditText,
)

object MainConfigDialogBuilder {
    fun build(
        context: Context,
        config: JSONObject,
        storageStatus: JSONObject,
        sshPassphraseForUi: String,
        openStorageSettings: () -> Unit,
    ): MainConfigDialogForm {
        val currentMode = config.optString("permission_mode", AgentRuntimeConfig.MODE_SAFE)
        val modes = config.optJSONArray("permission_modes") ?: JSONArray()
        val terminal = config.optJSONObject("terminal") ?: JSONObject()
        val mcp = config.optJSONObject("mcp") ?: JSONObject()
        val sshConfig = config.optJSONObject("ssh") ?: JSONObject()

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(28, 18, 28, 0)

        addSectionTitle(context, layout, "权限模式")
        val modeGroup = RadioGroup(context)
        modeGroup.orientation = RadioGroup.VERTICAL
        val modeById = mutableMapOf<Int, String>()
        for (index in 0 until modes.length()) {
            val item = modes.optJSONObject(index) ?: continue
            val id = 1000 + index
            val mode = item.optString("id")
            modeById[id] = mode
            val radio = RadioButton(context)
            radio.id = id
            radio.text = "${item.optString("label")}：${item.optString("description")}"
            radio.textSize = 14f
            radio.setTextColor(TEXT_COLOR)
            radio.setPadding(0, 6, 0, 6)
            modeGroup.addView(radio)
            if (mode == currentMode) modeGroup.check(id)
        }
        layout.addView(modeGroup)

        addSectionTitle(context, layout, "终端接口", topPadding = 18)
        val terminalEnabled = CheckBox(context)
        terminalEnabled.text = "启用 Termux/终端工具后端"
        terminalEnabled.isChecked = terminal.optBoolean("enabled")
        layout.addView(terminalEnabled)
        val terminalUrl = singleLineField(
            context,
            hint = AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL,
            value = terminal.optString("base_url", AgentRuntimeConfig.DEFAULT_TERMINAL_BASE_URL),
        )
        layout.addView(terminalUrl)
        addHint(context, layout, "终端服务地址示例：http://127.0.0.1:8787。非本机地址请确认网络和权限。")

        addSectionTitle(context, layout, "Windows MCP", topPadding = 18)
        val mcpEnabled = CheckBox(context)
        mcpEnabled.text = "启用 Windows MCP / MCP-Server 工具桥接"
        mcpEnabled.isChecked = mcp.optBoolean("enabled")
        layout.addView(mcpEnabled)
        val mcpUrl = singleLineField(
            context,
            hint = AgentRuntimeConfig.DEFAULT_MCP_BASE_URL,
            value = mcp.optString("base_url", AgentRuntimeConfig.DEFAULT_MCP_BASE_URL),
        )
        layout.addView(mcpUrl)
        val mcpToken = singleLineField(context, hint = "可选：填写 Bearer Token 或 MCP 鉴权 token")
        layout.addView(mcpToken)
        addHint(context, layout, "地址示例：http://192.168.1.10:8931/mcp/。不填写 token 表示保留已保存 token。")

        addSectionTitle(context, layout, "SSH 连接", topPadding = 18)
        val sshEnabled = CheckBox(context)
        sshEnabled.text = "启用原生 SSH 连接"
        sshEnabled.isChecked = sshConfig.optBoolean("enabled")
        layout.addView(sshEnabled)
        val sshHost = singleLineField(context, hint = "SSH 主机 / Tailscale 地址", value = sshConfig.optString("host", ""))
        layout.addView(sshHost)
        val sshPort = singleLineField(
            context,
            hint = "端口，默认 22",
            value = sshConfig.optInt("port", 22).toString(),
            inputType = InputType.TYPE_CLASS_NUMBER,
        )
        layout.addView(sshPort)
        val sshUser = singleLineField(context, hint = "SSH 用户名", value = sshConfig.optString("user", ""))
        layout.addView(sshUser)
        val sshKeyPath = singleLineField(
            context,
            hint = "私钥路径，例如 shared_storage:/keys/id_ed25519",
            value = sshConfig.optString("key_path", ""),
        )
        layout.addView(sshKeyPath)
        val sshPassphrase = singleLineField(context, hint = "可选：私钥口令", value = sshPassphraseForUi)
        layout.addView(sshPassphrase)
        addHint(context, layout, "手机端会优先用 PowerShell 执行远程命令；文件传输通过 SSH/SFTP 完成。")

        addHint(
            context,
            layout,
            if (storageStatus.optBoolean("all_files_access", false)) {
                "文件访问：已允许，可使用 shared_storage:/Download/... 传输下载或微信导出的文件。"
            } else {
                "文件访问：未允许。要传输 shared_storage:/Download/... 文件，请先打开授权页。"
            },
            topPadding = 10,
        )
        val storageButton = Button(context)
        storageButton.text = "打开文件访问授权"
        storageButton.setOnClickListener { openStorageSettings() }
        layout.addView(storageButton)

        return MainConfigDialogForm(
            layout = layout,
            modeGroup = modeGroup,
            modeById = modeById,
            terminalEnabled = terminalEnabled,
            terminalUrl = terminalUrl,
            mcpEnabled = mcpEnabled,
            mcpUrl = mcpUrl,
            mcpToken = mcpToken,
            sshEnabled = sshEnabled,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUser = sshUser,
            sshKeyPath = sshKeyPath,
            sshPassphrase = sshPassphrase,
        )
    }

    private fun addSectionTitle(context: Context, layout: LinearLayout, text: String, topPadding: Int = 0) {
        val title = TextView(context)
        title.text = text
        title.textSize = 16f
        title.setTextColor(TEXT_COLOR)
        title.setPadding(0, topPadding, 0, 4)
        layout.addView(title)
    }

    private fun addHint(context: Context, layout: LinearLayout, text: String, topPadding: Int = 8) {
        val hint = TextView(context)
        hint.text = text
        hint.textSize = 12f
        hint.setTextColor(HINT_COLOR)
        hint.setPadding(0, topPadding, 0, 0)
        layout.addView(hint)
    }

    private fun singleLineField(
        context: Context,
        hint: String,
        value: String = "",
        inputType: Int? = null,
    ): EditText {
        val field = EditText(context)
        field.hint = hint
        field.setSingleLine(true)
        if (inputType != null) field.inputType = inputType
        if (value.isNotEmpty()) field.setText(value)
        field.setPadding(12, 8, 12, 8)
        return field
    }

    private val TEXT_COLOR = Color.rgb(25, 29, 35)
    private val HINT_COLOR = Color.rgb(110, 120, 132)
}
