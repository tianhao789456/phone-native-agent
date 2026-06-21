package com.mobileagent.host

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainMessageRenderer(
    private val activity: Activity,
    private val messages: LinearLayout,
    private val scrollView: ScrollView,
    private val detailsDialogController: MainDetailsDialogController
) {
    fun render(role: String, text: String, detail: String? = null) {
        val bubble = TextView(activity)
        bubble.text = "$role\n$text"
        bubble.textSize = 15f
        bubble.setTextColor(Color.rgb(25, 29, 35))
        bubble.setPadding(18, 14, 18, 14)
        val bg = when (role) {
            "我" -> Color.rgb(219, 235, 255)
            "助手" -> Color.WHITE
            "工具" -> Color.rgb(235, 245, 238)
            "错误" -> Color.rgb(255, 232, 232)
            else -> Color.rgb(232, 236, 240)
        }
        bubble.setBackgroundColor(bg)
        if (detail != null) {
            bubble.setOnClickListener {
                detailsDialogController.showScrollable("$role details", detail)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 14)
        messages.addView(bubble, params)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
