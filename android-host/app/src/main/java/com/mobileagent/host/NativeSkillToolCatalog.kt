package com.mobileagent.host

object NativeSkillToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
            name = "skill_list",
            description = "List a compact progressive index of reusable skills. Skills are backed by plugin workflows and generated procedures; use skill_read for one full skill before execution.",
            category = "skills",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = NativeToolSchema.props(
                "query" to NativeToolSchema.stringProp(""),
                "source" to NativeToolSchema.stringProp("all"),
                "include_details" to NativeToolSchema.boolProp(false),
                "limit" to NativeToolSchema.intProp(50)
            )
        ),
        NativeToolDescriptor(
            name = "skill_read",
            description = "Read one reusable skill by id. Returns plugin workflow details or procedure content without listing every skill in the prompt.",
            category = "skills",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.LOW,
            properties = NativeToolSchema.props(
                "id" to NativeToolSchema.stringProp(),
                "max_bytes" to NativeToolSchema.intProp(40000)
            ),
            required = NativeToolSchema.req("id")
        ),
        NativeToolDescriptor(
            name = "skill_run",
            description = "Run one executable skill. Plugin-backed skills execute through plugin_run; procedure-backed skills load the procedure and return a follow_procedure plan for the agent to execute step by step.",
            category = "skills",
            access = NativeToolAccess.READ_ONLY,
            risk = NativeToolRisk.MEDIUM,
            properties = NativeToolSchema.props(
                "id" to NativeToolSchema.stringProp(),
                "arguments" to org.json.JSONObject().put("type", "object"),
                "max_steps" to NativeToolSchema.intProp(20)
            ),
            required = NativeToolSchema.req("id")
        )
    )
}
