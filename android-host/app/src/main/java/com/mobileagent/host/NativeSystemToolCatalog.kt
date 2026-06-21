package com.mobileagent.host

import org.json.JSONObject

object NativeSystemToolCatalog {
    val descriptors: List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
                    name = "get_time",
                    description = "Return current local time and timezone.",
                    category = "system",
                    access = NativeToolAccess.READ_ONLY,
                    risk = NativeToolRisk.LOW
                )
    )
}
