package com.mobileagent.host

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        root.setBackgroundColor(SURFACE)

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(activity, 20), statusBarHeight(activity) + dp(activity, 12), dp(activity, 20), dp(activity, 12))
        header.background = roundedBackground(HEADER, 0f)

        val titleRow = LinearLayout(activity)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(activity)
        title.text = "手机 Agent"
        title.setTextColor(Color.WHITE)
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER_VERTICAL

        val panelButton = compactButton(activity, "面板")
        panelButton.setOnClickListener { onPanelClick() }

        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(
            panelButton,
            LinearLayout.LayoutParams(dp(activity, 76), dp(activity, 42))
        )

        val statusText = TextView(activity)
        statusText.text = "正在检查..."
        statusText.setTextColor(Color.rgb(226, 232, 240))
        statusText.textSize = 14f
        statusText.setPadding(0, dp(activity, 8), 0, 0)

        val detailStatusText = TextView(activity)
        detailStatusText.text = "模型 - | 缓存 - | 权限 -"
        detailStatusText.setTextColor(Color.rgb(148, 163, 184))
        detailStatusText.textSize = 12f
        detailStatusText.setPadding(0, dp(activity, 4), 0, 0)
        detailStatusText.maxLines = 2

        val stopButton = Button(activity)
        stopButton.text = "停止"
        stopButton.textSize = 14f
        stopButton.isAllCaps = false
        stopButton.isEnabled = false
        stopButton.setOnClickListener { onStopClick() }

        header.addView(titleRow)
        header.addView(statusText)
        header.addView(detailStatusText)
        header.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 44)
            ).apply { setMargins(0, dp(activity, 8), 0, 0) }
        )
        root.addView(header)

        val scrollView = ScrollView(activity)
        scrollView.isFillViewport = true
        val messages = LinearLayout(activity)
        messages.orientation = LinearLayout.VERTICAL
        messages.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
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
        composer.gravity = Gravity.BOTTOM
        composer.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 10))
        composer.setBackgroundColor(Color.WHITE)

        val input = EditText(activity)
        input.hint = "输入消息"
        input.minLines = 1
        input.maxLines = 4
        input.setSingleLine(false)
        input.textSize = 15f
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.background = roundedBackground(Color.rgb(241, 245, 249), 18f)
        input.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
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
        sendButton.textSize = 15f
        sendButton.isAllCaps = false
        sendButton.setOnClickListener { onSendClick() }

        composer.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins(0, 0, dp(activity, 8), 0) }
        )
        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(activity, 76), LinearLayout.LayoutParams.WRAP_CONTENT)
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

    private fun compactButton(activity: Activity, text: String): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
        }
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun statusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else 0
    }

    private val HEADER = Color.rgb(15, 23, 42)
    private val SURFACE = Color.rgb(248, 250, 252)
}
