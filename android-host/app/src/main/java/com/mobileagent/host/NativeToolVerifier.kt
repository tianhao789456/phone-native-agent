package com.mobileagent.host

import org.json.JSONObject

class NativeToolVerifier(
    private val workspace: MobileWorkspace
) {
    fun verify(name: String, arguments: JSONObject, output: JSONObject): JSONObject {
        output.optJSONObject("verification")?.let { return it }
        if (!output.optBoolean("ok", false)) {
            return JSONObject()
                .put("required", false)
                .put("ok", false)
                .put("status", "skipped_tool_failed")
                .put("summary", "Tool failed before verification.")
        }
        return when (name) {
            "write_file" -> verifyWorkspaceWrite(arguments, output)
            "workspace_restore" -> verifyWorkspaceRestore(output)
            "web_extract", "page_extract" -> verifyWebExtract(output)
            "terminal_run" -> verifyTerminalRun(output)
            "terminal_script" -> verifyTerminalScript(arguments, output)
            "terminal_task_status" -> verifyTerminalTaskStatus(output)
            "ssh_connect" -> verifySshConnect(output)
            "ssh_run" -> verifySshRun(output)
            "file_push" -> verifySshTransfer(output, "file_push")
            "file_pull" -> verifySshTransfer(output, "file_pull")
            else -> JSONObject()
                .put("required", false)
                .put("ok", true)
                .put("status", "not_required")
                .put("summary", "No automatic verification required for $name.")
        }
    }

    private fun verifyWorkspaceWrite(arguments: JSONObject, output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val path = result.optString("path", arguments.optString("path"))
        val expected = arguments.optString("content")
        if (path.isBlank()) {
            return verificationFailed("workspace_write", "write_file returned no path")
        }
        val maxBytes = (expected.toByteArray(Charsets.UTF_8).size + 16).coerceAtLeast(1024)
        val readBack = runCatching { workspace.read(path, maxBytes) }
            .getOrElse { return verificationFailed("workspace_write", "${it.javaClass.simpleName}: ${it.message}") }
        val actual = readBack.optString("content")
        val ok = readBack.optBoolean("ok", false) && !readBack.optBoolean("truncated", false) && actual == expected
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "content_mismatch")
            .put("summary", if (ok) "Verified write_file by reading back $path." else "write_file verification failed for $path.")
            .put(
                "evidence",
                JSONObject()
                    .put("path", path)
                    .put("bytes", result.optInt("bytes", -1))
                    .put("read_ok", readBack.optBoolean("ok", false))
            )
    }

    private fun verifyWorkspaceRestore(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val path = result.optString("path")
        if (path.isBlank()) return verificationFailed("workspace_restore", "workspace_restore returned no path")
        val shouldExist = result.optBoolean("restored_exists", true)
        val readBack = runCatching { workspace.read(path, 1024) }.getOrNull()
        val ok = if (shouldExist) {
            readBack?.optBoolean("ok", false) == true
        } else {
            readBack?.optBoolean("ok", false) != true
        }
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "restore_state_mismatch")
            .put("summary", if (ok) "Verified workspace_restore state for $path." else "workspace_restore verification failed for $path.")
            .put(
                "evidence",
                JSONObject()
                    .put("path", path)
                    .put("expected_exists", shouldExist)
                    .put("read_ok", readBack?.optBoolean("ok", false) ?: false)
            )
    }

    private fun verifyWebExtract(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val content = result.optString("content")
            .ifBlank { result.optString("markdown") }
            .ifBlank { result.optString("text") }
        val ok = content.trim().length >= 80 || result.optString("title").isNotBlank()
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "empty_content")
            .put(
                "summary",
                if (ok) {
                    "Verified page extraction from ${result.optString("source", "unknown")} with ${content.length} chars."
                } else {
                    "web_extract returned no usable title or content."
                }
            )
            .put(
                "evidence",
                JSONObject()
                    .put("source", result.optString("source", ""))
                    .put("title", result.optString("title", "").take(160))
                    .put("content_chars", content.length)
                    .put("truncated", result.optBoolean("truncated", false))
            )
    }

    private fun verifyTerminalRun(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val timedOut = result.optBoolean("timed_out", false)
        val hasReturnCode = result.has("returncode")
        val returnCode = result.optInt("returncode", -1)
        val ok = !timedOut && hasReturnCode && returnCode == 0
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "command_failed")
            .put("summary", if (ok) "Verified terminal_run returncode=0." else "terminal_run verification failed: returncode=$returnCode timed_out=$timedOut.")
            .put("evidence", JSONObject().put("returncode", returnCode).put("timed_out", timedOut))
    }

    private fun verifyTerminalScript(arguments: JSONObject, output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val wait = arguments.optBoolean("wait", true)
        if (!wait) {
            return JSONObject()
                .put("required", false)
                .put("ok", true)
                .put("status", "deferred")
                .put("summary", "terminal_script is running in background; verify with terminal_task_status.")
                .put("evidence", JSONObject().put("task_id", result.optString("task_id", "")))
        }
        val status = result.optString("status", "")
        val timedOut = result.optBoolean("timed_out", false)
        val hasReturnCode = result.has("returncode")
        val returnCode = result.optInt("returncode", -1)
        val ok = !timedOut && (!hasReturnCode || returnCode == 0) && status.lowercase() !in setOf("failed", "cancelled", "timeout", "timed_out")
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "script_failed")
            .put("summary", if (ok) "Verified terminal_script completion." else "terminal_script verification failed: status=$status returncode=$returnCode timed_out=$timedOut.")
            .put(
                "evidence",
                JSONObject()
                    .put("status", status)
                    .put("returncode", returnCode)
                    .put("timed_out", timedOut)
                    .put("task_id", result.optString("task_id", ""))
            )
    }

    private fun verifyTerminalTaskStatus(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val status = result.optString("status", "")
        val returnCode = result.optInt("returncode", 0)
        val ok = status.lowercase() !in setOf("failed", "cancelled", "timeout", "timed_out") && returnCode == 0
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "task_unhealthy")
            .put("summary", if (ok) "Verified terminal task status." else "terminal task status is unhealthy: status=$status returncode=$returnCode.")
            .put("evidence", JSONObject().put("status", status).put("returncode", returnCode).put("task_id", result.optString("task_id", "")))
    }

    private fun verifySshConnect(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val ok = output.optBoolean("ok", false) && result.optBoolean("connected", false)
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "connect_failed")
            .put("summary", if (ok) "Verified SSH session is connected." else "ssh_connect did not establish a session.")
            .put("evidence", JSONObject().put("connected", result.optBoolean("connected", false)).put("host", result.optString("host", "")))
    }

    private fun verifySshRun(output: JSONObject): JSONObject {
        val wrapper = output.optJSONObject("result") ?: JSONObject()
        val result = wrapper.optJSONObject("result") ?: wrapper
        val timedOut = result.optBoolean("timed_out", false)
        val returnCode = result.optInt("returncode", -1)
        val ok = output.optBoolean("ok", false) && !timedOut && returnCode == 0
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else if (timedOut) "timeout" else "command_failed")
            .put("summary", if (ok) "Verified ssh_run returncode=0." else "ssh_run verification failed: returncode=$returnCode timed_out=$timedOut.")
            .put("evidence", JSONObject().put("returncode", returnCode).put("timed_out", timedOut))
    }

    private fun verifySshTransfer(output: JSONObject, toolName: String): JSONObject {
        val wrapper = output.optJSONObject("result") ?: JSONObject()
        val result = wrapper.optJSONObject("result") ?: wrapper
        val ok = output.optBoolean("ok", false) && result.optString("remote_path", "").isNotBlank()
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "transfer_failed")
            .put("summary", if (ok) "Verified $toolName transfer." else "$toolName transfer failed.")
            .put(
                "evidence",
                JSONObject()
                    .put("local_path", result.optString("local_path", ""))
                    .put("remote_path", result.optString("remote_path", ""))
                    .put("bytes", result.optLong("bytes", -1))
            )
    }

    private fun verificationFailed(status: String, summary: String): JSONObject {
        return JSONObject()
            .put("required", true)
            .put("ok", false)
            .put("status", status)
            .put("summary", summary)
    }
}
