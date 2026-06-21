package com.mobileagent.host

import org.json.JSONObject

object NativePluginToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "plugin_info",
                    description = "Return the Android APP native plugin workspace root and plugin counts. This is the foundation for phone-local self-extension.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                ),
        NativeToolDescriptor(
                    name = "plugin_list",
                    description = "List a compact index of plugins from the Android APP native plugin workspace. Disabled plugins are included by default. Use plugin_read for one full manifest.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props(
                        "include_disabled" to NativeToolSchema.boolProp(true),
                        "include_details" to NativeToolSchema.boolProp(false)
                    )
                ),
        NativeToolDescriptor(
                    name = "plugin_create",
                    description = "Create or update a plugin manifest in the Android APP native plugin workspace. Use this to stage new tools, skills, or workflows without editing the main APP source.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props(
                        "manifest" to JSONObject().put("type", "object"),
                        "overwrite" to NativeToolSchema.boolProp(false)
                    ),
                    required = NativeToolSchema.req("manifest")
                ),
        NativeToolDescriptor(
                    name = "plugin_read",
                    description = "Read one plugin manifest by id from the Android APP native plugin workspace.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "plugin_reports",
                    description = "List saved validation, test, and workflow run reports for one plugin.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp(), "limit" to NativeToolSchema.intProp(50)),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "plugin_report_read",
                    description = "Read one saved plugin report from the plugin reports folder. Accepts paths returned by plugin_validate, plugin_test, plugin_run, or plugin_reports.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp(), "report" to NativeToolSchema.stringProp(), "max_bytes" to NativeToolSchema.intProp(200000)),
                    required = NativeToolSchema.req("id", "report")
                ),
        NativeToolDescriptor(
                    name = "plugin_validate",
                    description = "Validate one plugin manifest and save a validation report in the plugin reports folder. This checks ids, required fields, arrays, and duplicate tool/workflow names without executing plugin code.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "plugin_test",
                    description = "Run non-executing plugin checks and save a test report in the plugin reports folder. This is the first safe test layer before dynamic plugin execution exists.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp()),
                    required = NativeToolSchema.req("id")
                ),
        NativeToolDescriptor(
                    name = "plugin_run",
                    description = "Run a plugin workflow through the safe adapter. In normal modes workflow steps can call existing read-only native tools. In developer mode, screen actions and terminal delegation may run through the normal permission gate; recursive plugin calls and unknown tools remain blocked.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp(), "workflow" to NativeToolSchema.stringProp(), "max_steps" to NativeToolSchema.intProp(20)),
                    required = NativeToolSchema.req("id", "workflow")
                ),
        NativeToolDescriptor(
                    name = "plugin_set_enabled",
                    description = "Enable or disable one plugin manifest. This lets the agent quarantine a bad plugin without deleting it.",
                    category = "plugins",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.MEDIUM,
                    properties = NativeToolSchema.props("id" to NativeToolSchema.stringProp(), "enabled" to NativeToolSchema.boolProp(true)),
                    required = NativeToolSchema.req("id", "enabled")
                )
    )
}
