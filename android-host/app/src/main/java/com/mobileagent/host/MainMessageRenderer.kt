package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainMessageRenderer(
    private val activity: Activity,
    private val messages: LinearLayout,
    private val scrollView: ScrollView,
    private val detailsDialogController: MainDetailsDialogController
) {
    fun render(role: String, text: String, detail: String? = null) {
        val wrapper = LinearLayout(activity)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.gravity = gravityForRole(role)

        val label = TextView(activity)
        label.text = role
        label.textSize = 12f
        label.setTextColor(Color.rgb(100, 116, 139))
        label.setPadding(4, 0, 4, 4)
        label.gravity = gravityForRole(role)

        val bubble = TextView(activity)
        val visibleText = "$role\n$text"
        bubble.text = text
        bubble.textSize = if (role == "工具") 14f else 15f
        bubble.setTextColor(Color.rgb(15, 23, 42))
        bubble.setPadding(18, 14, 18, 14)
        bubble.background = roundedBackground(backgroundForRole(role), 18f, strokeForRole(role))
        if (role == "错误") bubble.typeface = Typeface.DEFAULT_BOLD
        if (detail != null) {
            bubble.setOnClickListener {
                detailsDialogController.showScrollable("$role 详情", detail)
            }
        }
        bubble.setOnLongClickListener {
            showMessageActions(role, visibleText, detail)
            true
        }

        wrapper.addView(
            label,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        wrapper.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { width = (activity.resources.displayMetrics.widthPixels * 0.86f).toInt() }
        )

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 14)
        messages.addView(wrapper, params)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun gravityForRole(role: String): Int {
        return if (role == "我") Gravity.END else Gravity.START
    }

    private fun backgroundForRole(role: String): Int {
        return when (role) {
            "我" -> Color.rgb(219, 234, 254)
            "助手" -> Color.WHITE
            "工具" -> Color.rgb(240, 253, 244)
            "错误" -> Color.rgb(254, 242, 242)
            "系统" -> Color.rgb(241, 245, 249)
            else -> Color.rgb(248, 250, 252)
        }
    }

    private fun strokeForRole(role: String): Int? {
        return when (role) {
            "工具" -> Color.rgb(187, 247, 208)
            "错误" -> Color.rgb(254, 202, 202)
            else -> Color.rgb(226, 232, 240)
        }
    }

    private fun roundedBackground(color: Int, radius: Float, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) setStroke(1, strokeColor)
        }
    }

    private fun showMessageActions(role: String, visibleText: String, detail: String?) {
        val labels = mutableListOf("复制消息", "分享消息")
        if (!detail.isNullOrBlank()) {
            labels.add("复制详情")
            labels.add("查看详情")
        }
        AlertDialog.Builder(activity)
            .setTitle("$role 操作")
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "复制消息" -> copyText("Mobile Agent 消息", visibleText)
                    "分享消息" -> shareText(visibleText)
                    "复制详情" -> copyText("Mobile Agent 详情", detail.orEmpty())
                    "查看详情" -> detailsDialogController.showScrollable("$role 详情", detail.orEmpty())
                }
            }
            .show()
    }

    private fun copyText(label: String, text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(intent, "分享消息"))
    }
}
