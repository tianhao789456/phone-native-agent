package com.mobileagent.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object AccessibilityState {
    @Volatile
    var service: AccessibilityService? = null

    fun status(context: Context? = null): JSONObject {
        val connected = service != null
        val configured = context?.let { isServiceConfigured(it.applicationContext) } ?: connected
        return JSONObject()
            .put("ok", true)
            .put("enabled", connected || configured)
            .put("connected", connected)
            .put("configured", configured)
    }

    fun dump(maxNodes: Int = 80): JSONObject {
        val activeService = service
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "AccessibilityService is not enabled")
        val root = activeService.rootInActiveWindow
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "No active window root")

        val nodes = JSONArray()
        AccessibilityTreeSearch.collect(root, nodes, maxNodes, path = "0", depth = 0)
        return JSONObject()
            .put("ok", true)
            .put("nodes", nodes)
    }

    fun snapshotV2(
        maxNodes: Int = 120,
        maxDepth: Int = 12,
        includeUninteresting: Boolean = false,
        visibleOnly: Boolean = true,
        maxTextChars: Int = 300
    ): JSONObject {
        val activeService = service
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "AccessibilityService is not enabled")
        val root = activeService.rootInActiveWindow
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "No active window root")

        return AccessibilitySnapshotBuilder.snapshot(
            root = root,
            maxNodes = maxNodes,
            maxDepth = maxDepth,
            includeUninteresting = includeUninteresting,
            visibleOnly = visibleOnly,
            maxTextChars = maxTextChars
        )
    }

    fun observe(maxNodes: Int = 40): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("current_app", currentApp())
            .put("screen", dump(maxNodes))
    }

    fun currentApp(): JSONObject {
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString().orEmpty())
            .put("class", root.className?.toString().orEmpty())
            .put("root", AccessibilityNodeFormatter.summary(root))
    }

    fun find(query: String, contains: Boolean = true, maxNodes: Int = 20): JSONObject {
        if (query.isBlank()) return error("query is required")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val matches = JSONArray()
        AccessibilityTreeSearch.collectMatches(root, matches, query, contains, maxNodes, path = "0", depth = 0)
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("contains", contains)
            .put("matches", matches)
            .put("count", matches.length())
    }

    fun clickText(text: String, contains: Boolean = true): JSONObject {
        if (text.isBlank()) return error("text is required")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val node = AccessibilityTreeSearch.findNode(root) { item ->
            val nodeText = item.text?.toString().orEmpty()
            val desc = item.contentDescription?.toString().orEmpty()
            AccessibilityTreeSearch.matches(nodeText, text, contains) || AccessibilityTreeSearch.matches(desc, text, contains)
        } ?: return error("No node matched text: $text")
        return clickNode(node)
    }

    fun clickViewId(viewId: String): JSONObject {
        if (viewId.isBlank()) return error("view_id is required")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val node = AccessibilityTreeSearch.findNode(root) { item -> item.viewIdResourceName == viewId }
            ?: return error("No node matched view_id: $viewId")
        return clickNode(node)
    }

    fun clickIndex(index: Int): JSONObject {
        if (index < 0) return error("index must be >= 0")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val node = AccessibilityTreeSearch.interestingNodeByIndex(root, index) ?: return error("No node matched index: $index")
        return clickNode(node)
    }

    fun longPressText(text: String, contains: Boolean = true): JSONObject {
        if (text.isBlank()) return error("text is required")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val node = AccessibilityTreeSearch.findNode(root) { item ->
            val nodeText = item.text?.toString().orEmpty()
            val desc = item.contentDescription?.toString().orEmpty()
            AccessibilityTreeSearch.matches(nodeText, text, contains) || AccessibilityTreeSearch.matches(desc, text, contains)
        } ?: return error("No node matched text: $text")
        return longPressNode(node)
    }

    fun longPressIndex(index: Int): JSONObject {
        if (index < 0) return error("index must be >= 0")
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val node = AccessibilityTreeSearch.interestingNodeByIndex(root, index) ?: return error("No node matched index: $index")
        return longPressNode(node)
    }

    fun inputText(text: String): JSONObject {
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: AccessibilityTreeSearch.findNode(root) { it.isEditable }
        target ?: return error("No focused or editable input node")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return JSONObject()
            .put("ok", ok)
            .put("action", "input_text")
            .put("node", AccessibilityNodeFormatter.summary(target))
            .put("after_observe", observeAfterAction())
    }

    fun clearText(): JSONObject {
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: AccessibilityTreeSearch.findNode(root) { it.isEditable }
        target ?: return error("No focused or editable input node")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return JSONObject()
            .put("ok", ok)
            .put("action", "clear_text")
            .put("node", AccessibilityNodeFormatter.summary(target))
            .put("after_observe", observeAfterAction())
    }

    fun back(): JSONObject {
        return globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "back")
    }

    fun home(): JSONObject {
        return globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "home")
    }

    fun pressKey(key: String): JSONObject {
        return when (key.trim().lowercase()) {
            "back", "keycode_back", "4" -> back()
            "home", "keycode_home", "3" -> home()
            "recents", "recent", "overview", "keycode_app_switch", "187" ->
                globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
            "notifications", "notification", "shade" ->
                globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")
            "quick_settings", "quicksettings" ->
                globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick_settings")
            "power_dialog", "power" ->
                globalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "power_dialog")
            else -> error("Unsupported native key: $key. Supported: back, home, recents, notifications, quick_settings, power_dialog.")
        }
    }

    fun scroll(direction: String = "forward", text: String = "", viewId: String = ""): JSONObject {
        val root = activeRoot() ?: return notEnabledOrNoRoot()
        val target = when {
            viewId.isNotBlank() -> AccessibilityTreeSearch.findNode(root) { it.viewIdResourceName == viewId }
            text.isNotBlank() -> AccessibilityTreeSearch.findNode(root) {
                AccessibilityTreeSearch.matches(it.text?.toString().orEmpty(), text, true) ||
                    AccessibilityTreeSearch.matches(it.contentDescription?.toString().orEmpty(), text, true)
            }
            else -> AccessibilityTreeSearch.findNode(root) { it.isScrollable } ?: root
        } ?: return error("No scroll target found")

        val action = if (direction.equals("backward", ignoreCase = true) || direction.equals("up", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = target.performAction(action)
        return JSONObject()
            .put("ok", ok)
            .put("action", "scroll")
            .put("direction", direction)
            .put("node", AccessibilityNodeFormatter.summary(target))
            .put("after_observe", observeAfterAction())
    }

    fun swipeCoords(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 450): JSONObject {
        val activeService = service ?: return error("AccessibilityService is not enabled")
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 5000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = activeService.dispatchGesture(gesture, null, null)
        return JSONObject()
            .put("ok", ok)
            .put("action", "swipe_coords")
            .put("start", JSONObject().put("x", x1).put("y", y1))
            .put("end", JSONObject().put("x", x2).put("y", y2))
            .put("duration_ms", durationMs.coerceIn(50, 5000))
            .put("after_observe", observeAfterAction(durationMs.coerceIn(50, 5000) + 250))
    }

    fun waitMs(ms: Long): JSONObject {
        val safeMs = ms.coerceIn(0, 30000)
        if (safeMs > 0) {
            try {
                Thread.sleep(safeMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return error("wait interrupted")
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "wait_ms")
            .put("waited_ms", safeMs)
            .put("after_observe", observeAfterAction(0))
    }

    fun waitForText(text: String, timeoutMs: Long = 5000, contains: Boolean = true): JSONObject {
        if (text.isBlank()) return error("text is required")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 60000)
        var last = JSONObject()
        while (System.currentTimeMillis() <= deadline) {
            val found = find(text, contains, 5)
            last = found
            if (found.optBoolean("ok", false) && found.optInt("count", 0) > 0) {
                return JSONObject()
                    .put("ok", true)
                    .put("action", "wait_for_text")
                    .put("text", text)
                    .put("matched", true)
                    .put("matches", found.optJSONArray("matches") ?: JSONArray())
                    .put("after_observe", observeAfterAction(0))
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return error("wait interrupted")
            }
        }
        return JSONObject()
            .put("ok", false)
            .put("action", "wait_for_text")
            .put("text", text)
            .put("matched", false)
            .put("last", last)
            .put("error", "Timed out waiting for text: $text")
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        val activeService = service ?: return null
        return activeService.rootInActiveWindow
    }

    private fun notEnabledOrNoRoot(): JSONObject {
        return if (service == null) {
            error("AccessibilityService is not enabled")
        } else {
            error("No active window root")
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): JSONObject {
        val target = clickableAncestor(node) ?: node
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return JSONObject()
            .put("ok", ok)
            .put("action", "click")
            .put("node", AccessibilityNodeFormatter.summary(target))
            .put("after_observe", observeAfterAction())
    }

    private fun longPressNode(node: AccessibilityNodeInfo): JSONObject {
        val target = longClickableAncestor(node) ?: clickableAncestor(node) ?: node
        val actionOk = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        val ok = if (actionOk) {
            true
        } else {
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            gestureLongPress(bounds.centerX(), bounds.centerY())
        }
        return JSONObject()
            .put("ok", ok)
            .put("action", "long_press")
            .put("node", AccessibilityNodeFormatter.summary(target))
            .put("after_observe", observeAfterAction(650))
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun longClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isLongClickable) return current
            current = current.parent
        }
        return null
    }

    private fun gestureLongPress(x: Int, y: Int): Boolean {
        val activeService = service ?: return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, 650)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return activeService.dispatchGesture(gesture, null, null)
    }

    private fun globalAction(action: Int, name: String): JSONObject {
        val activeService = service ?: return error("AccessibilityService is not enabled")
        return JSONObject()
            .put("ok", activeService.performGlobalAction(action))
            .put("action", name)
            .put("after_observe", observeAfterAction())
    }

    fun observeAfterAction(delayMs: Long = 250, maxNodes: Int = 40): JSONObject {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return observe(maxNodes)
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }

    private fun isServiceConfigured(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!accessibilityEnabled) return false
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val expected = "${context.packageName}/${MobileAgentAccessibilityService::class.java.name}"
            enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }
}
