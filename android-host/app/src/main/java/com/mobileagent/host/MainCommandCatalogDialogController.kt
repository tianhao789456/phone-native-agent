package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainCommandCatalogDialogController(
    private val activity: Activity,
    private val fillInput: (String) -> Unit,
    private val addMessage: (String, String) -> Unit,
) {
    fun show() {
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(10), dp(14), dp(12))

        val scrollView = ScrollView(activity)
        scrollView.addView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("命令大全")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()

        MainCommandCatalog.commands.forEach { item ->
            addCommandRow(panel, item, dialog)
        }

        dialog.setOnShowListener {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            scrollView.layoutParams = scrollView.layoutParams.apply { height = maxHeight }
        }
        dialog.show()
    }

    private fun addCommandRow(panel: LinearLayout, item: MainCommandInfo, dialog: AlertDialog) {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.setBackgroundColor(Color.rgb(248, 250, 252))

        val title = TextView(activity)
        title.text = "${item.command}  ${item.title}"
        title.textSize = 15f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.rgb(15, 23, 42))
        card.addView(title)

        val desc = TextView(activity)
        desc.text = item.description
        desc.textSize = 13f
        desc.setTextColor(Color.rgb(71, 85, 105))
        desc.setPadding(0, dp(4), 0, dp(8))
        card.addView(desc)

        val actions = LinearLayout(activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END

        val fillButton = Button(activity)
        fillButton.text = "填入"
        fillButton.textSize = 13f
        fillButton.isAllCaps = false
        fillButton.setOnClickListener {
            dialog.dismiss()
            fillInput(item.command)
        }
        actions.addView(fillButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(0, 0, dp(8), 0) })

        val copyButton = Button(activity)
        copyButton.text = "复制"
        copyButton.textSize = 13f
        copyButton.isAllCaps = false
        copyButton.setOnClickListener {
            copyCommand(item.command)
            addMessage("系统", "已复制命令：${item.command}")
        }
        actions.addView(copyButton, LinearLayout.LayoutParams(0, dp(42), 1f))

        card.addView(actions)
        panel.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        )
    }

    private fun copyCommand(command: String) {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Mobile Agent command", command))
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
