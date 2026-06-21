package com.mobileagent.host

import org.json.JSONObject
import java.io.File

class MobileMemoryLearningStore(
    private val demosDir: File,
    private val activeLearningFile: File,
    private val relative: (File) -> String,
    private val paths: () -> JSONObject,
    private val nowIso: () -> String,
    private val nowMs: () -> Long,
    private val readJson: (File, JSONObject) -> JSONObject,
    private val writeJson: (File, JSONObject) -> Unit
) {
    fun start(arguments: JSONObject): JSONObject {
        if (activeLearningFile.isFile) {
            val active = readJson(activeLearningFile, JSONObject())
            return JSONObject().put("ok", true).put("already_active", true).put("session", active).put("paths", paths())
        }
        val label = arguments.optString("label", "manual-demo").trim().ifBlank { "manual-demo" }
        val app = arguments.optString("app", "unknown").trim().ifBlank { "unknown" }
        val sessionId = "${nowMs()}-${MobileMemoryText.slug(label)}"
        val sessionDir = File(demosDir, sessionId)
        sessionDir.mkdirs()
        val session = JSONObject()
            .put("id", sessionId)
            .put("label", label)
            .put("app", app)
            .put("tool_scope", arguments.optString("tool_scope", "phone"))
            .put("description", arguments.optString("description", ""))
            .put("started_at", nowIso())
            .put("started_at_ms", nowMs())
            .put("dir", relative(sessionDir))
            .put("trace", relative(File(sessionDir, "trace.jsonl")))
        writeJson(File(sessionDir, "meta.json"), session)
        writeJson(activeLearningFile, session)
        appendEvent(session, "start", JSONObject().put("summary", "learning started"))
        return JSONObject().put("ok", true).put("started", true).put("session", session).put("paths", paths())
    }

    fun record(arguments: JSONObject): JSONObject {
        val session = activeSession() ?: return error("no active learning session")
        val details = arguments.optJSONObject("details") ?: JSONObject()
        val event = appendEvent(
            session,
            arguments.optString("event_type", arguments.optString("event", "note")),
            JSONObject(details.toString())
                .put("app", arguments.optString("app", session.optString("app", "unknown")))
                .put("summary", arguments.optString("summary", ""))
                .put("screen_summary", arguments.optString("screen_summary", ""))
        )
        return JSONObject().put("ok", true).put("event", event).put("session", session).put("paths", paths())
    }

    fun status(): JSONObject {
        val active = activeSession() ?: JSONObject()
        return JSONObject()
            .put("ok", true)
            .put("active", active.length() > 0)
            .put("session", active)
            .put("paths", paths())
    }

    fun activeSession(): JSONObject? {
        return if (activeLearningFile.isFile) readJson(activeLearningFile, JSONObject()) else null
    }

    fun sessionDir(session: JSONObject): File {
        return File(demosDir, session.optString("id"))
    }

    fun traceFile(session: JSONObject): File {
        return File(sessionDir(session), "trace.jsonl")
    }

    fun clearActive() {
        activeLearningFile.delete()
    }

    fun appendEvent(session: JSONObject, eventType: String, details: JSONObject): JSONObject {
        val sessionDir = sessionDir(session)
        sessionDir.mkdirs()
        val event = JSONObject()
            .put("timestamp", nowIso())
            .put("timestamp_ms", nowMs())
            .put("event_type", eventType)
            .put("app", details.optString("app", session.optString("app", "unknown")))
            .put("summary", details.optString("summary", ""))
            .put("screen_summary", details.optString("screen_summary", ""))
            .put("details", details)
        File(sessionDir, "trace.jsonl").appendText(event.toString() + "\n", Charsets.UTF_8)
        return event
    }

    private fun error(message: String): JSONObject {
        return JSONObject().put("ok", false).put("error", message).put("paths", paths())
    }
}
