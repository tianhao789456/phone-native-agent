package com.mobileagent.host

import org.json.JSONArray

object NativeToolRegistry {
    val descriptors: List<NativeToolDescriptor> = NativeToolCatalog.descriptors

    private val byName = descriptors.associateBy { it.name }
    private val toolGroups = NativeToolGroups(descriptors)

    fun names(): List<String> = descriptors.map { it.name }

    fun schemas(): JSONArray {
        val array = JSONArray()
        descriptors.forEach { array.put(it.schema()) }
        return array
    }

    fun metadata(): JSONArray {
        val array = JSONArray()
        descriptors.forEach { array.put(it.metadata()) }
        return array
    }

    fun metadata(tools: Set<String>): JSONArray {
        val array = JSONArray()
        val normalized = normalizeTools(tools)
        descriptors.forEach { descriptor ->
            if (descriptor.name in normalized) array.put(descriptor.metadata())
        }
        return array
    }

    fun indexMetadata(
        tools: Set<String> = toolGroups.allToolNames,
        category: String = "",
        search: String = "",
        includeSchema: Boolean = false
    ): JSONArray {
        val normalizedCategory = category.trim().lowercase()
        val normalizedSearch = search.trim().lowercase()
        val array = JSONArray()
        normalizeTools(tools).forEach { name ->
            val descriptor = descriptor(name) ?: return@forEach
            if (normalizedCategory.isNotBlank() && descriptor.category.lowercase() != normalizedCategory) return@forEach
            if (normalizedSearch.isNotBlank() &&
                !descriptor.name.lowercase().contains(normalizedSearch) &&
                !descriptor.description.lowercase().contains(normalizedSearch)
            ) return@forEach
            array.put(if (includeSchema) descriptor.metadata() else descriptor.indexMetadata())
        }
        return array
    }

    fun baselineTools(): Set<String> = toolGroups.baselineTools()

    fun availableGroups(): JSONArray = toolGroups.availableGroups()

    fun toolsForGroups(groups: Set<String>): Set<String> = toolGroups.toolsForGroups(groups)

    fun normalizeTools(toolNames: Set<String>): Set<String> = toolGroups.normalizeTools(toolNames)

    fun schemasForTools(toolNames: Set<String>): JSONArray {
        val array = JSONArray()
        val normalized = normalizeTools(toolNames)
        descriptors.forEach { descriptor ->
            if (descriptor.name in normalized) array.put(descriptor.schema())
        }
        return array
    }

    fun descriptor(name: String): NativeToolDescriptor? = byName[name]

    fun access(name: String): NativeToolAccess? = byName[name]?.access

    fun isAutoRecoverable(name: String): Boolean = byName[name]?.autoRecover == true
}
