package com.mobileagent.host

import org.json.JSONArray

class NativeToolGroups(private val descriptors: List<NativeToolDescriptor>) {
    val allToolNames: Set<String> = descriptors.map { it.name }.toSet()

    private val toolsByGroup: Map<String, Set<String>> = mapOf(
        "baseline" to linkedSetOf(
            "get_time",
            "toolset_request",
            "tool_registry",
            "tool_info",
            "task_plan_update",
            "task_plan_status",
            "docs_index",
            "docs_read",
            "docs_search",
            "self_health_check",
            "memory_search",
            "experience_search",
            "procedure_search",
            "skill_list",
            "mcp_servers"
        ),
        "planning" to toolsInCategory("planning"),
        "diagnostics" to toolsInCategory("diagnostics"),
        "web" to toolsInCategory("web"),
        "workspace" to toolsInCategory("workspace"),
        "skills" to toolsInCategory("skills"),
        "plugins" to toolsInCategory("plugins"),
        "phone" to toolsInCategory("phone"),
        "ssh" to toolsInCategory("ssh"),
        "terminal" to toolsInCategory("terminal"),
        "mcp" to toolsInCategory("mcp"),
        "memory" to toolsInCategory("memory"),
        "recovery" to toolsInCategory("recovery")
    )

    private val allGroups = toolsByGroup.keys.toSortedSet()

    fun baselineTools(): Set<String> = toolsByGroup["baseline"] ?: linkedSetOf("get_time", "toolset_request")

    fun availableGroups(): JSONArray {
        val array = JSONArray()
        allGroups.forEach { array.put(it) }
        return array
    }

    fun toolsForGroups(groups: Set<String>): Set<String> {
        val normalized = groups.map { it.lowercase() }.toSet()
        if (normalized.isEmpty()) return baselineTools()
        if (normalized.contains("all")) return allToolNames

        val selected = linkedSetOf<String>()
        normalized.forEach { group ->
            toolsByGroup[group]?.let { selected.addAll(it) }
        }
        return if (selected.isNotEmpty()) normalizeTools(selected) else baselineTools()
    }

    fun normalizeTools(toolNames: Set<String>): Set<String> {
        val resolved = linkedSetOf<String>()
        toolNames.forEach { name ->
            if (name in allToolNames) resolved.add(name)
        }
        if (resolved.isEmpty()) return baselineTools()
        resolved.add("toolset_request")
        resolved.add("tool_registry")
        resolved.add("tool_info")
        return resolved
    }

    private fun toolsInCategory(category: String): Set<String> {
        return descriptors.filter { it.category == category }.mapTo(linkedSetOf()) { it.name }
    }
}
