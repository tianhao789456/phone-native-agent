package com.mobileagent.host

import org.json.JSONObject

class NativeMemoryToolDispatcher(
    private val memory: MobileMemoryStore,
    private val learningStop: (JSONObject) -> JSONObject
) {
    fun canHandle(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "memory_query" -> memory.query(
                arguments.optString("question"),
                arguments.optInt("limit", 5)
            )
            "memory_search" -> memory.searchMemory(
                arguments.optString("query"),
                arguments.optInt("limit", 8)
            )
            "memory_summary" -> memory.summary(
                arguments.optString("query", ""),
                arguments.optInt("limit", 80)
            )
            "memory_write" -> memory.writeMemory(arguments)
            "memory_update" -> memory.updateMemory(arguments)
            "memory_delete" -> memory.deleteMemory(arguments)
            "experience_search" -> memory.searchExperience(arguments)
            "experience_record" -> memory.recordExperience(arguments)
            "experience_update" -> memory.updateExperience(arguments)
            "experience_delete" -> memory.deleteExperience(arguments)
            "experience_compact" -> memory.compactExperience(arguments)
            "procedure_search" -> memory.procedureSearch(arguments)
            "procedure_generate" -> memory.procedureGenerate(arguments)
            "procedure_read" -> memory.procedureRead(arguments)
            "procedure_list" -> memory.procedureList(arguments)
            "learning_start" -> memory.learningStart(arguments)
            "learning_record" -> memory.learningRecord(arguments)
            "learning_stop" -> learningStop(arguments)
            "learning_status" -> memory.learningStatus()
            else -> throw IllegalArgumentException("Memory dispatcher cannot handle tool: $name")
        }
    }

    companion object {
        val TOOL_NAMES = setOf(
            "memory_query",
            "memory_search",
            "memory_summary",
            "memory_write",
            "memory_update",
            "memory_delete",
            "experience_search",
            "experience_record",
            "experience_update",
            "experience_delete",
            "experience_compact",
            "procedure_search",
            "procedure_generate",
            "procedure_read",
            "procedure_list",
            "learning_start",
            "learning_record",
            "learning_stop",
            "learning_status"
        )
    }
}
