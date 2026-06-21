package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MobileWorkspaceHistory(
    private val historyRoot: File,
    private val resolvePath: (String) -> File,
    private val relativePath: (File) -> String
) {
    fun countChanges(): Int {
        return historyRoot.listFiles()?.count { it.isDirectory } ?: 0
    }

    fun history(path: String = "", limit: Int = 50): JSONObject {
        ensureHistoryRoot()
        val normalizedPath = path.trim().replace('\\', '/')
        val changes = JSONArray()
        val items = historyRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val meta = File(dir, "meta.json")
                if (!meta.isFile) null else runCatching { JSONObject(meta.readText(Charsets.UTF_8)) }.getOrNull()
            }
            ?.filter { normalizedPath.isBlank() || it.optString("path") == normalizedPath }
            ?.sortedByDescending { it.optLong("created_at_ms") }
            ?.take(limit.coerceIn(1, 200))
            ?: emptyList()
        items.forEach { changes.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("path", if (normalizedPath.isBlank()) JSONObject.NULL else normalizedPath)
            .put("count", changes.length())
            .put("changes", changes)
    }

    fun restore(changeId: String): JSONObject {
        if (changeId.isBlank()) return error("change_id is required")
        val changeDir = File(historyRoot, changeId).canonicalFile
        val historyPath = historyRoot.canonicalFile.toPath()
        if (changeDir.toPath() != historyPath && !changeDir.toPath().startsWith(historyPath)) {
            throw SecurityException("change_id escapes history root")
        }
        val metaFile = File(changeDir, "meta.json")
        if (!metaFile.isFile) return error("Change does not exist: $changeId")
        val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
        val path = meta.optString("path")
        val target = resolvePath(path)
        val currentExists = target.exists()
        val currentBytes = if (currentExists && target.isFile) target.readBytes() else ByteArray(0)
        val beforeExists = meta.optBoolean("before_exists", false)
        val beforeFile = File(changeDir, "before.bin")
        if (beforeExists) {
            target.parentFile?.mkdirs()
            target.writeBytes(beforeFile.readBytes())
        } else if (target.exists()) {
            target.delete()
        }
        val restoredExists = target.exists()
        val restoredBytes = if (restoredExists && target.isFile) target.readBytes() else ByteArray(0)
        val restoreChange = recordChange(
            action = "restore",
            path = path,
            beforeExists = currentExists,
            beforeBytes = currentBytes,
            afterExists = restoredExists,
            afterBytes = restoredBytes,
            restoredFrom = changeId
        )
        return JSONObject()
            .put("ok", true)
            .put("restored_change_id", changeId)
            .put("path", path)
            .put("restored_exists", restoredExists)
            .put("change", restoreChange)
    }

    fun recordChange(
        action: String,
        path: String,
        beforeExists: Boolean,
        beforeBytes: ByteArray,
        afterExists: Boolean,
        afterBytes: ByteArray,
        restoredFrom: String = ""
    ): JSONObject {
        ensureHistoryRoot()
        val changeId = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val dir = File(historyRoot, changeId)
        dir.mkdirs()
        File(dir, "before.bin").writeBytes(beforeBytes)
        File(dir, "after.bin").writeBytes(afterBytes)
        val meta = JSONObject()
            .put("id", changeId)
            .put("action", action)
            .put("path", path)
            .put("before_exists", beforeExists)
            .put("before_bytes", beforeBytes.size)
            .put("after_exists", afterExists)
            .put("after_bytes", afterBytes.size)
            .put("created_at_ms", System.currentTimeMillis())
            .put("restored_from", restoredFrom)
        File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
        return meta
    }

    private fun ensureHistoryRoot() {
        historyRoot.mkdirs()
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
