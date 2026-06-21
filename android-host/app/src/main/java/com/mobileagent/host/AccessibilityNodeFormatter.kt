package com.mobileagent.host

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object AccessibilityNodeFormatter {
    fun isInteresting(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        return text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank() ||
            node.isClickable || node.isEditable || node.isScrollable
    }

    fun selectorFor(node: AccessibilityNodeInfo, path: String): String {
        val viewId = node.viewIdResourceName.orEmpty()
        if (viewId.isNotBlank()) return "id:$viewId"
        val text = node.text?.toString().orEmpty()
        if (text.isNotBlank()) return "text:$text"
        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.isNotBlank()) return "desc:$desc"
        return "path:$path"
    }

    fun summary(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return JSONObject()
            .put("text", node.text?.toString().orEmpty())
            .put("content_desc", node.contentDescription?.toString().orEmpty())
            .put("view_id", node.viewIdResourceName.orEmpty())
            .put("class", node.className?.toString().orEmpty())
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
    }

    fun summaryV2(
        node: AccessibilityNodeInfo,
        id: Int,
        path: String,
        depth: Int,
        maxTextChars: Int
    ): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = compactText(node.text?.toString().orEmpty(), maxTextChars)
        val desc = compactText(node.contentDescription?.toString().orEmpty(), maxTextChars)
        return JSONObject()
            .put("id", id)
            .put("path", path)
            .put("depth", depth)
            .put("selector", selectorFor(node, path))
            .put("text", text.first)
            .put("text_truncated", text.second)
            .put("content_desc", desc.first)
            .put("content_desc_truncated", desc.second)
            .put("view_id", node.viewIdResourceName.orEmpty())
            .put("class", node.className?.toString().orEmpty())
            .put("package", node.packageName?.toString().orEmpty())
            .put("bounds", boundsJson(bounds))
            .put("state", nodeState(node))
            .put("actions", nodeActions(node))
            .put("agent_actions", agentActions(node))
    }

    private fun boundsJson(bounds: Rect): JSONObject {
        return JSONObject()
            .put("left", bounds.left)
            .put("top", bounds.top)
            .put("right", bounds.right)
            .put("bottom", bounds.bottom)
            .put("width", bounds.width())
            .put("height", bounds.height())
            .put("center_x", bounds.centerX())
            .put("center_y", bounds.centerY())
            .put("valid", bounds.width() >= 0 && bounds.height() >= 0)
    }

    private fun nodeState(node: AccessibilityNodeInfo): JSONObject {
        return JSONObject()
            .put("clickable", node.isClickable)
            .put("long_clickable", node.isLongClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("focusable", node.isFocusable)
            .put("focused", node.isFocused)
            .put("selected", node.isSelected)
            .put("checked", node.isChecked)
            .put("enabled", node.isEnabled)
            .put("visible_to_user", node.isVisibleToUser)
    }

    private fun nodeActions(node: AccessibilityNodeInfo): JSONArray {
        val actions = linkedSetOf<String>()
        if (node.isClickable) actions.add("click")
        if (node.isLongClickable) actions.add("long_click")
        if (node.isEditable) actions.add("set_text")
        if (node.isScrollable) {
            actions.add("scroll_forward")
            actions.add("scroll_backward")
        }
        node.actionList.forEach { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> actions.add("click")
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> actions.add("long_click")
                AccessibilityNodeInfo.ACTION_SET_TEXT -> actions.add("set_text")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> actions.add("scroll_forward")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> actions.add("scroll_backward")
                AccessibilityNodeInfo.ACTION_FOCUS -> actions.add("focus")
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> actions.add("clear_focus")
                AccessibilityNodeInfo.ACTION_SELECT -> actions.add("select")
                AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> actions.add("clear_selection")
                AccessibilityNodeInfo.ACTION_EXPAND -> actions.add("expand")
                AccessibilityNodeInfo.ACTION_COLLAPSE -> actions.add("collapse")
                AccessibilityNodeInfo.ACTION_DISMISS -> actions.add("dismiss")
                AccessibilityNodeInfo.ACTION_CUT -> actions.add("cut")
                AccessibilityNodeInfo.ACTION_COPY -> actions.add("copy")
                AccessibilityNodeInfo.ACTION_PASTE -> actions.add("paste")
            }
        }
        val array = JSONArray()
        actions.forEach { array.put(it) }
        return array
    }

    private fun agentActions(node: AccessibilityNodeInfo): JSONArray {
        val actions = linkedSetOf<String>()
        if (!node.isVisibleToUser || !node.isEnabled) return JSONArray()
        if (node.isClickable) actions.add("click")
        if (node.isLongClickable) actions.add("long_click")
        if (node.isEditable) actions.add("set_text")
        if (node.isScrollable) {
            actions.add("scroll_forward")
            actions.add("scroll_backward")
        }
        node.actionList.forEach { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> actions.add("click")
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> actions.add("long_click")
                AccessibilityNodeInfo.ACTION_SET_TEXT -> actions.add("set_text")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> actions.add("scroll_forward")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> actions.add("scroll_backward")
                AccessibilityNodeInfo.ACTION_EXPAND -> actions.add("expand")
                AccessibilityNodeInfo.ACTION_COLLAPSE -> actions.add("collapse")
                AccessibilityNodeInfo.ACTION_DISMISS -> actions.add("dismiss")
            }
        }
        val array = JSONArray()
        actions.forEach { array.put(it) }
        return array
    }

    private fun compactText(value: String, maxChars: Int): Pair<String, Boolean> {
        if (value.length <= maxChars) return value to false
        return value.take(maxChars) to true
    }
}
