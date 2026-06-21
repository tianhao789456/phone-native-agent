package com.mobileagent.host

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.ScrollView
import android.widget.TextView

class MainDetailsDialogController(
    private val activity: Activity,
) {
    fun show(title: String, detail: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("确定", null)
            .show()
    }

    fun showScrollable(title: String, detail: String) {
        val text = TextView(activity)
        text.text = detail
        text.textSize = 13f
        text.setTextColor(Color.rgb(25, 29, 35))
        text.setPadding(28, 20, 28, 20)
        text.setTextIsSelectable(true)
        val container = ScrollView(activity)
        container.addView(text)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定", null)
            .show()
    }
}
