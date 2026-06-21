package com.mobileagent.host

import org.json.JSONObject

object NativePhoneToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor("host_status", "Return Android Host App and Accessibility state.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW),
        NativeToolDescriptor(
                    name = "accessibility_snapshot_v2",
                    description = "Return a structured Accessibility UI tree with stable node ids, paths, bounds, state flags, and per-node action lists. Prefer this over screenshot guessing before phone UI actions.",
                    category = "phone",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "max_nodes" to NativeToolSchema.intProp(120),
                        "max_depth" to NativeToolSchema.intProp(12),
                        "include_uninteresting" to NativeToolSchema.boolProp(false),
                        "visible_only" to NativeToolSchema.boolProp(true),
                        "max_text_chars" to NativeToolSchema.intProp(300)
                    )
                ),
        NativeToolDescriptor(
                    name = "host_screen_find",
                    description = "Find screen nodes by visible text, content description, view id, or class name.",
                    category = "phone",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("query" to NativeToolSchema.stringProp(), "contains" to NativeToolSchema.boolProp(true), "max_nodes" to NativeToolSchema.intProp(20)),
                    required = NativeToolSchema.req("query")
                ),
        NativeToolDescriptor("host_current_app", "Return the package name and root node summary for the current foreground app.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW),
        NativeToolDescriptor("host_open_app", "Open an installed Android app by package name.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("package" to NativeToolSchema.stringProp()), NativeToolSchema.req("package")),
        NativeToolDescriptor("host_click_text", "Click visible text or content description through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("text" to NativeToolSchema.stringProp(), "contains" to NativeToolSchema.boolProp(true)), NativeToolSchema.req("text")),
        NativeToolDescriptor("host_click_view_id", "Click an Android view resource id through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("view_id" to NativeToolSchema.stringProp()), NativeToolSchema.req("view_id")),
        NativeToolDescriptor("host_click_index", "Click a node by index from accessibility_snapshot_v2 through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("index" to NativeToolSchema.intProp()), NativeToolSchema.req("index")),
        NativeToolDescriptor("host_long_press_text", "Long-press visible text or content description through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("text" to NativeToolSchema.stringProp(), "contains" to NativeToolSchema.boolProp(true)), NativeToolSchema.req("text")),
        NativeToolDescriptor("host_long_press_index", "Long-press a node by index from accessibility_snapshot_v2 through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("index" to NativeToolSchema.intProp()), NativeToolSchema.req("index")),
        NativeToolDescriptor("host_input_text", "Set text in the focused or first editable field through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("text" to NativeToolSchema.stringProp()), NativeToolSchema.req("text")),
        NativeToolDescriptor("host_clear_text", "Clear the focused or first editable field through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM),
        NativeToolDescriptor("host_press_key", "Perform a supported native global key action through Accessibility. Supports back, home, recents, notifications, quick_settings, and power_dialog.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("key" to NativeToolSchema.stringProp()), NativeToolSchema.req("key")),
        NativeToolDescriptor("host_scroll", "Scroll the current page through the Accessibility backend.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("direction" to NativeToolSchema.stringProp("forward"), "text" to NativeToolSchema.stringProp(""), "view_id" to NativeToolSchema.stringProp(""))),
        NativeToolDescriptor("host_swipe_coords", "Swipe from one screen coordinate to another through an Accessibility gesture.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("x1" to NativeToolSchema.intProp(), "y1" to NativeToolSchema.intProp(), "x2" to NativeToolSchema.intProp(), "y2" to NativeToolSchema.intProp(), "duration_ms" to NativeToolSchema.intProp(450)), NativeToolSchema.req("x1", "y1", "x2", "y2")),
        NativeToolDescriptor("host_wait_ms", "Wait for a fixed number of milliseconds, then observe the current screen.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, NativeToolSchema.props("ms" to NativeToolSchema.intProp(1000))),
        NativeToolDescriptor("host_wait_for_text", "Wait until visible text or content description appears on screen, polling Accessibility until timeout.", "phone", NativeToolAccess.READ_ONLY, NativeToolRisk.LOW, NativeToolSchema.props("text" to NativeToolSchema.stringProp(), "timeout_ms" to NativeToolSchema.intProp(5000), "contains" to NativeToolSchema.boolProp(true)), NativeToolSchema.req("text")),
        NativeToolDescriptor("host_open_url", "Open a URL with Android ACTION_VIEW. This launches the user's browser or matching app.", "phone", NativeToolAccess.SCREEN_ACTION, NativeToolRisk.MEDIUM, NativeToolSchema.props("url" to NativeToolSchema.stringProp()), NativeToolSchema.req("url")),
        NativeToolDescriptor(
                    name = "intent_open",
                    description = "Open an Android Intent with optional action, data URI, MIME type, package/class, categories, extras, and chooser title. Use this for deep links and direct app entry points before falling back to UI clicking.",
                    category = "phone",
                    access = NativeToolAccess.SCREEN_ACTION,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "action" to NativeToolSchema.stringProp("android.intent.action.VIEW"),
                        "data" to NativeToolSchema.stringProp(""),
                        "type" to NativeToolSchema.stringProp(""),
                        "mime_type" to NativeToolSchema.stringProp(""),
                        "package" to NativeToolSchema.stringProp(""),
                        "class" to NativeToolSchema.stringProp(""),
                        "categories" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                        "extras" to JSONObject().put("type", "object"),
                        "chooser_title" to NativeToolSchema.stringProp("")
                    )
                ),
        NativeToolDescriptor(
                    name = "share_file",
                    description = "Share a workspace/shared-storage file through Android ACTION_SEND using FileProvider content URI grants. Supports optional text, MIME type, target package, and chooser title.",
                    category = "phone",
                    access = NativeToolAccess.SCREEN_ACTION,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "path" to NativeToolSchema.stringProp(),
                        "mime_type" to NativeToolSchema.stringProp(""),
                        "text" to NativeToolSchema.stringProp(""),
                        "title" to NativeToolSchema.stringProp("Share file"),
                        "package" to NativeToolSchema.stringProp("")
                    ),
                    required = NativeToolSchema.req("path")
                ),
        NativeToolDescriptor(
                    name = "open_file_with",
                    description = "Open a workspace/shared-storage file through Android ACTION_VIEW using FileProvider content URI grants. Supports MIME type, target package, and chooser title.",
                    category = "phone",
                    access = NativeToolAccess.SCREEN_ACTION,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "path" to NativeToolSchema.stringProp(),
                        "mime_type" to NativeToolSchema.stringProp(""),
                        "package" to NativeToolSchema.stringProp(""),
                        "chooser_title" to NativeToolSchema.stringProp("")
                    ),
                    required = NativeToolSchema.req("path")
                )
    )
}
