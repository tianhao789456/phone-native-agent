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
                .put("summary", "工具执行失败，已跳过自动验证。")
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
                .put("summary", "$name 不需要自动验证。")
        }
    }

    private fun verifyWorkspaceWrite(arguments: JSONObject, output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val path = result.optString("path", arguments.optString("path"))
        val expected = arguments.optString("content")
        if (path.isBlank()) {
            return verificationFailed("workspace_write", "write_file 没有返回路径。")
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
            .put("summary", if (ok) "已回读 $path，确认 write_file 写入成功。" else "write_file 回读验证失败：$path。")
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
        if (path.isBlank()) return verificationFailed("workspace_restore", "workspace_restore 没有返回路径。")
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
            .put("summary", if (ok) "已确认 workspace_restore 状态：$path。" else "workspace_restore 状态验证失败：$path。")
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
                    "已验证网页提取结果：来源 ${result.optString("source", "unknown")}，内容 ${content.length} 字符。"
                } else {
                    "web_extract 没有返回可用标题或正文。"
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
            .put("summary", if (ok) "终端命令验证通过：returncode=0。" else "终端命令验证失败：returncode=$returnCode timed_out=$timedOut。")
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
                .put("summary", "终端脚本正在后台运行；请用 terminal_task_status 继续检查。")
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
            .put("summary", if (ok) "终端脚本验证通过。" else "终端脚本验证失败：status=$status returncode=$returnCode timed_out=$timedOut。")
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
            .put("summary", if (ok) "终端任务状态验证通过。" else "终端任务状态异常：status=$status returncode=$returnCode。")
            .put("evidence", JSONObject().put("status", status).put("returncode", returnCode).put("task_id", result.optString("task_id", "")))
    }

    private fun verifySshConnect(output: JSONObject): JSONObject {
        val result = output.optJSONObject("result") ?: JSONObject()
        val ok = output.optBoolean("ok", false) && result.optBoolean("connected", false)
        return JSONObject()
            .put("required", true)
            .put("ok", ok)
            .put("status", if (ok) "verified" else "connect_failed")
            .put("summary", if (ok) "SSH 连接验证通过。" else "SSH 连接没有建立成功。")
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
            .put("summary", if (ok) "SSH 命令验证通过：returncode=0。" else "SSH 命令验证失败：returncode=$returnCode timed_out=$timedOut。")
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
            .put("summary", if (ok) "$toolName 文件传输验证通过。" else "$toolName 文件传输失败。")
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
