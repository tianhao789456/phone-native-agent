package com.mobileagent.host

import android.content.Context
import org.json.JSONObject

class NativePhoneToolDispatcher(
    private val context: Context,
    private val intentTools: AndroidIntentTools,
    private val openApp: (String) -> JSONObject,
    private val openUrl: (String) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "host_status" -> AccessibilityState.status(context)
            "host_observe" -> AccessibilityState.snapshotV2(
                arguments.optInt("max_nodes", 40),
                arguments.optInt("max_depth", 12),
                arguments.optBoolean("include_uninteresting", false),
                arguments.optBoolean("visible_only", true),
                arguments.optInt("max_text_chars", 300)
            )
            "host_screen_dump" -> AccessibilityState.snapshotV2(
                arguments.optInt("max_nodes", 80),
                arguments.optInt("max_depth", 12),
                arguments.optBoolean("include_uninteresting", false),
                arguments.optBoolean("visible_only", true),
                arguments.optInt("max_text_chars", 300)
            )
            "accessibility_snapshot_v2" -> AccessibilityState.snapshotV2(
                arguments.optInt("max_nodes", 120),
                arguments.optInt("max_depth", 12),
                arguments.optBoolean("include_uninteresting", false),
                arguments.optBoolean("visible_only", true),
                arguments.optInt("max_text_chars", 300)
            )
            "host_screen_find" -> AccessibilityState.find(
                arguments.optString("query"),
                arguments.optBoolean("contains", true),
                arguments.optInt("max_nodes", 20)
            )
            "host_current_app" -> AccessibilityState.currentApp()
            "host_open_app" -> openApp(arguments.optString("package"))
            "host_click_text" -> AccessibilityState.clickText(
                arguments.optString("text"),
                arguments.optBoolean("contains", true)
            )
            "host_click_view_id" -> AccessibilityState.clickViewId(arguments.optString("view_id"))
            "host_click_index" -> AccessibilityState.clickIndex(arguments.optInt("index", -1))
            "host_long_press_text" -> AccessibilityState.longPressText(
                arguments.optString("text"),
                arguments.optBoolean("contains", true)
            )
            "host_long_press_index" -> AccessibilityState.longPressIndex(arguments.optInt("index", -1))
            "host_input_text" -> AccessibilityState.inputText(arguments.optString("text"))
            "host_clear_text" -> AccessibilityState.clearText()
            "host_back" -> AccessibilityState.pressKey("back")
            "host_home" -> AccessibilityState.pressKey("home")
            "host_press_key" -> AccessibilityState.pressKey(arguments.optString("key"))
            "host_scroll" -> AccessibilityState.scroll(
                arguments.optString("direction", "forward"),
                arguments.optString("text", ""),
                arguments.optString("view_id", "")
            )
            "host_swipe_coords" -> AccessibilityState.swipeCoords(
                arguments.optInt("x1"),
                arguments.optInt("y1"),
                arguments.optInt("x2"),
                arguments.optInt("y2"),
                arguments.optLong("duration_ms", 450L)
            )
            "host_wait_ms" -> AccessibilityState.waitMs(arguments.optLong("ms", 1000L))
            "host_wait_for_text" -> AccessibilityState.waitForText(
                arguments.optString("text"),
                arguments.optLong("timeout_ms", 5000L),
                arguments.optBoolean("contains", true)
            )
            "host_open_url" -> openUrl(arguments.optString("url"))
            "intent_open" -> intentTools.intentOpen(arguments)
            "share_file" -> intentTools.shareFile(arguments)
            "open_file_with" -> intentTools.openFileWith(arguments)
            else -> throw IllegalArgumentException("Phone dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "host_status",
            "host_observe",
            "host_screen_dump",
            "accessibility_snapshot_v2",
            "host_screen_find",
            "host_current_app",
            "host_open_app",
            "host_click_text",
            "host_click_view_id",
            "host_click_index",
            "host_long_press_text",
            "host_long_press_index",
            "host_input_text",
            "host_clear_text",
            "host_back",
            "host_home",
            "host_press_key",
            "host_scroll",
            "host_swipe_coords",
            "host_wait_ms",
            "host_wait_for_text",
            "host_open_url",
            "intent_open",
            "share_file",
            "open_file_with"
        )
    }
}
