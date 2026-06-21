package com.mobileagent.host

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class MainLayoutViews(
    val root: LinearLayout,
    val statusText: TextView,
    val detailStatusText: TextView,
    val messages: LinearLayout,
    val scrollView: ScrollView,
    val composer: LinearLayout,
    val input: EditText,
    val sendButton: Button,
    val stopButton: Button
)

object MainLayoutBuilder {
    fun build(
        activity: Activity,
        onPanelClick: () -> Unit,
        onStopClick: () -> Unit,
        onSendClick: () -> Unit
    ): MainLayoutViews {
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(247, 248, 250))

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(28, statusBarHeight(activity) + 18, 28, 18)
        header.setBackgroundColor(Color.rgb(21, 25, 31))

        val title = TextView(activity)
        title.text = "手机 Agent"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.gravity = Gravity.CENTER_VERTICAL

        val statusText = TextView(activity)
        statusText.text = "正在检查中..."
        statusText.setTextColor(Color.rgb(184, 194, 204))
        statusText.textSize = 13f
        statusText.setPadding(0, 6, 0, 0)

        val detailStatusText = TextView(activity)
        detailStatusText.text = "模型 - | 连接 - | 缓存 - | 权限 -"
        detailStatusText.setTextColor(Color.rgb(148, 160, 172))
        detailStatusText.textSize = 12f
        detailStatusText.setPadding(0, 4, 0, 0)

        val panelButton = Button(activity)
        panelButton.text = "操作面板"
        panelButton.setOnClickListener { onPanelClick() }

        val stopButton = Button(activity)
        stopButton.text = "停止"
        stopButton.isEnabled = false
        stopButton.setOnClickListener { onStopClick() }

        val actionRow = LinearLayout(activity)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.gravity = Gravity.CENTER_VERTICAL
        actionRow.setPadding(0, 10, 0, 0)
        actionRow.addView(
            panelButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionRow.addView(
            stopButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        header.addView(title)
        header.addView(statusText)
        header.addView(detailStatusText)
        header.addView(actionRow)
        root.addView(header)

        val scrollView = ScrollView(activity)
        scrollView.isFillViewport = true
        val messages = LinearLayout(activity)
        messages.orientation = LinearLayout.VERTICAL
        messages.setPadding(20, 18, 20, 18)
        scrollView.addView(messages)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val composer = LinearLayout(activity)
        composer.orientation = LinearLayout.HORIZONTAL
        composer.gravity = Gravity.CENTER_VERTICAL
        composer.setPadding(16, 12, 16, 16)
        composer.setBackgroundColor(Color.WHITE)

        val input = EditText(activity)
        input.hint = "输入消息"
        input.minLines = 1
        input.maxLines = 3
        input.setSingleLine(false)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setBackgroundColor(Color.rgb(241, 243, 245))
        input.setPadding(18, 10, 18, 10)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendClick()
                true
            } else {
                false
            }
        }

        val sendButton = Button(activity)
        sendButton.text = "发送"
        sendButton.setOnClickListener { onSendClick() }

        composer.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(composer)

        return MainLayoutViews(
            root = root,
            statusText = statusText,
            detailStatusText = detailStatusText,
            messages = messages,
            scrollView = scrollView,
            composer = composer,
            input = input,
            sendButton = sendButton,
            stopButton = stopButton
        )
    }

    fun keepComposerAboveKeyboard(root: LinearLayout, composer: LinearLayout) {
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val keyboardHeight = (root.rootView.height - visible.bottom).coerceAtLeast(0)
            composer.translationY = if (keyboardHeight > root.rootView.height * 0.15) {
                -keyboardHeight.toFloat()
            } else {
                0f
            }
        }
    }

    private fun statusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else 0
    }
}
