package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

class NativeTaskMemoryCoordinator(
    private val workspace: MobileWorkspace,
    private val memory: MobileMemoryStore,
    private val stepEvaluator: NativeLoopStepEvaluator,
    private val modelClient: NativeModelClient,
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun taskTitleForRun(taskPlan: JSONObject, userMessage: String): String {
        val goal = taskPlan.optString("goal", "").trim()
        return goal.ifBlank { userMessage.trim() }.ifBlank { "tool task" }.take(120)
    }

    fun taskGoalForRun(taskPlan: JSONObject, userMessage: String): String {
        val goal = taskPlan.optString("goal", "").trim()
        return goal.ifBlank { userMessage.trim() }.take(2000)
    }

    fun persistTaskLoopRun(
        title: String,
        goal: String,
        sessionId: String,
        runId: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray
    ): JSONObject {
        return runCatching {
            workspace.taskRecordRun(
                title = title,
                goal = goal,
                sessionId = sessionId,
                runId = runId,
                finalMessage = finalText,
                taskLoop = taskLoop,
                toolTrace = toolTrace
            )
        }.onSuccess { record ->
            log(
                "info",
                "task",
                "task loop report persisted",
                JSONObject()
                    .put("run_id", runId)
                    .put("task", record.optJSONObject("task")?.optString("path", ""))
                    .put("reports", record.optJSONObject("reports") ?: JSONObject())
            )
        }.onFailure { error ->
            log(
                "error",
                "task",
                "task loop report persistence failed",
                JSONObject()
                    .put("run_id", runId)
                    .put("error", error.message ?: error.toString())
            )
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("error", error.message ?: error.toString())
        }
    }

    fun recordTaskMemory(
        apiKey: String,
        userMessage: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray,
        runId: String
    ): JSONObject {
        return runCatching {
            val toolsUsed = JSONArray()
            val appsUsed = JSONArray()
            val toolExperiences = JSONArray()
            val seenTools = linkedSetOf<String>()
            for (index in 0 until toolTrace.length()) {
                val step = toolTrace.optJSONObject(index) ?: continue
                val tool = step.optString("tool")
                if (tool.isNotBlank()) seenTools.add(tool)
                val state = step.optString("state")
                val output = step.optJSONObject("output") ?: JSONObject()
                val summary = stepEvaluator.summary(output).ifBlank { output.toString().take(300) }
                memory.heuristicExperienceFromTool(tool, state, summary, userMessage)?.let { lesson ->
                    val recorded = memory.recordExperience(lesson)
                    toolExperiences.put(recorded)
                }
                val result = output.optJSONObject("result") ?: JSONObject()
                val app = result.optString("package").ifBlank { result.optString("app") }
                if (app.isNotBlank()) appsUsed.put(app)
            }
            seenTools.forEach { toolsUsed.put(it) }
            val taskHistory = memory.recordTask(
                task = userMessage,
                status = taskLoop.optString("status", "unknown"),
                finalAnswer = finalText,
                toolsUsed = toolsUsed,
                appsUsed = appsUsed,
                runId = runId
            )
            val reflection = JSONObject()
                .put("run_id", runId)
                .put("task", userMessage.take(1000))
                .put("status", taskLoop.optString("status"))
                .put("final_answer", finalText.take(2000))
                .put("tools_used", toolsUsed)
                .put("experience_candidates", toolExperiences.length())
            val reflectionRecord = memory.appendReflection(reflection)
            val extracted = extractMemoryWithModel(apiKey, userMessage, finalText, taskLoop, toolTrace)
            JSONObject()
                .put("ok", true)
                .put("task_history", taskHistory)
                .put("tool_experiences", toolExperiences)
                .put("reflection", reflectionRecord)
                .put("model_extraction", extracted)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "automatic memory recording failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject().put("ok", false).put("error", error.message ?: error.toString())
        }
    }

    fun learningStopWithModel(arguments: JSONObject, apiKeyProvider: () -> String?): JSONObject {
        val stopped = memory.learningStop(arguments)
        if (!stopped.optBoolean("ok", false)) return stopped
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return JSONObject(stopped.toString())
                .put("model_extraction", JSONObject().put("ok", true).put("skipped", true).put("reason", "api_key_missing"))
        }
        val extraction = runCatching {
            val summaryPath = stopped.optString("summary", "")
            val summaryContent = if (summaryPath.isNotBlank()) {
                workspace.read(summaryPath, 20000).optString("content", "")
            } else {
                stopped.toString(2)
            }
            val prompt = """
                Extract reusable Mobile Agent learning from this demo trace.
                Return only JSON:
                {
                  "experiences": [{"app":"...","tool_scope":"phone|windows_mcp|termux|","lesson_type":"successful_navigation|failed_approach|ui_knowledge|timing|environment|general","description":"...","confidence":"low|medium|high"}],
                  "procedures": [{"app":"...","tool_scope":"..."}]
                }
                Keep descriptions reusable and short. Do not store secrets.

                Learning stop result:
                ${stopped.toString(2).take(4000)}

                Demo summary:
                ${summaryContent.take(8000)}
            """.trimIndent()
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You extract compact reusable agent memory from learning traces. Output strict JSON only."))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val response = modelClient.request(key, messages, setOf("get_time"))
            val parsed = parseJsonObjectFromText(response.content)
            val recorded = JSONArray()
            val experiences = parsed.optJSONArray("experiences") ?: JSONArray()
            for (index in 0 until experiences.length()) {
                val item = experiences.optJSONObject(index) ?: continue
                recorded.put(
                    memory.recordExperience(
                        JSONObject()
                            .put("app", item.optString("app", arguments.optString("app", "unknown")))
                            .put("tool_scope", item.optString("tool_scope", arguments.optString("tool_scope", "phone")))
                            .put("lesson_type", item.optString("lesson_type", "ui_knowledge"))
                            .put("description", item.optString("description", ""))
                            .put("source_task", summaryContent.take(500))
                            .put("confidence", item.optString("confidence", "low"))
                    )
                )
            }
            val procedures = JSONArray()
            val requestedProcedures = parsed.optJSONArray("procedures") ?: JSONArray()
            for (index in 0 until requestedProcedures.length()) {
                val item = requestedProcedures.optJSONObject(index) ?: continue
                procedures.put(
                    memory.procedureGenerate(
                        JSONObject()
                            .put("app", item.optString("app", arguments.optString("app", "unknown")))
                            .put("tool_scope", item.optString("tool_scope", arguments.optString("tool_scope", "phone")))
                    )
                )
            }
            JSONObject()
                .put("ok", true)
                .put("experiences_written", recorded.length())
                .put("procedures_generated", procedures.length())
                .put("experiences", recorded)
                .put("procedures", procedures)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "learning model extraction failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("error_type", error.javaClass.simpleName)
                .put("error", error.message ?: "")
        }
        return JSONObject(stopped.toString()).put("model_extraction", extraction)
    }

    private fun extractMemoryWithModel(
        apiKey: String,
        userMessage: String,
        finalText: String,
        taskLoop: JSONObject,
        toolTrace: JSONArray
    ): JSONObject {
        if (toolTrace.length() == 0) return JSONObject().put("ok", true).put("skipped", true).put("reason", "no tool trace")
        return runCatching {
            val traceSummary = summarizeTraceForMemory(toolTrace)
            val prompt = """
                Extract durable memory from this Mobile Agent task.
                Return only a JSON object with optional arrays:
                {
                  "memory": [{"type":"preference|environment|do_not_do|insight","key":"...","value":"...","confidence":"low|medium|high"}],
                  "experiences": [{"app":"general","tool_scope":"windows_mcp|phone|termux|","task_type":"...","lesson_type":"successful_navigation|failed_approach|ui_knowledge|timing|environment|general","description":"...","confidence":"low|medium|high"}],
                  "reflection": {"what_worked":"...","what_failed":"...","next_time_hint":"..."}
                }
                Store only reusable facts or lessons. Do not store secrets verbatim.

                Task: ${userMessage.take(1200)}
                Status: ${taskLoop.optString("status")}
                Final answer: ${finalText.take(1500)}
                Trace:
                $traceSummary
            """.trimIndent()
            val extractionMessages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You extract compact durable memory for a phone-native agent. Output strict JSON only."))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val response = modelClient.request(apiKey, extractionMessages, setOf("get_time"))
            val parsed = parseJsonObjectFromText(response.content)
            val memoryItems = JSONArray()
            val memories = parsed.optJSONArray("memory") ?: JSONArray()
            for (index in 0 until memories.length()) {
                val item = memories.optJSONObject(index) ?: continue
                val written = memory.writeMemory(
                    JSONObject()
                        .put("type", item.optString("type", "insight"))
                        .put("key", item.optString("key", "task_insight"))
                        .put("value", item.optString("value", item.optString("text", "")))
                        .put("confidence", item.optString("confidence", "medium"))
                        .put("source", "model_extraction")
                )
                memoryItems.put(written)
            }
            val experienceItems = JSONArray()
            val experiences = parsed.optJSONArray("experiences") ?: JSONArray()
            for (index in 0 until experiences.length()) {
                val item = experiences.optJSONObject(index) ?: continue
                val recorded = memory.recordExperience(
                    JSONObject()
                        .put("app", item.optString("app", "general"))
                        .put("tool_scope", item.optString("tool_scope", item.optString("scope", "")))
                        .put("task_type", item.optString("task_type", ""))
                        .put("lesson_type", item.optString("lesson_type", "general"))
                        .put("description", item.optString("description", item.optString("text", "")))
                        .put("source_task", userMessage)
                        .put("confidence", item.optString("confidence", "medium"))
                )
                experienceItems.put(recorded)
            }
            val reflectionObj = parsed.optJSONObject("reflection") ?: JSONObject()
            val reflection = if (reflectionObj.length() > 0) {
                memory.appendReflection(
                    JSONObject(reflectionObj.toString())
                        .put("task", userMessage.take(1000))
                        .put("status", taskLoop.optString("status"))
                        .put("source", "model_extraction")
                )
            } else {
                JSONObject().put("skipped", true)
            }
            JSONObject()
                .put("ok", true)
                .put("memory_written", memoryItems.length())
                .put("experiences_written", experienceItems.length())
                .put("reflection", reflection)
        }.getOrElse { error ->
            log(
                "warn",
                "memory",
                "model memory extraction failed",
                JSONObject().put("error_type", error.javaClass.simpleName).put("error", error.message ?: "")
            )
            JSONObject()
                .put("ok", false)
                .put("error_type", error.javaClass.simpleName)
                .put("error", error.message ?: "")
        }
    }

    private fun summarizeTraceForMemory(toolTrace: JSONArray): String {
        val lines = mutableListOf<String>()
        for (index in 0 until toolTrace.length()) {
            val step = toolTrace.optJSONObject(index) ?: continue
            val output = step.optJSONObject("output") ?: JSONObject()
            lines.add(
                "- tool=${step.optString("tool")} state=${step.optString("state")} summary=${stepEvaluator.summary(output).take(500)}"
            )
        }
        return lines.takeLast(30).joinToString("\n")
    }

    private fun parseJsonObjectFromText(text: String): JSONObject {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(text.substring(start, end + 1)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
