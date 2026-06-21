package com.mobileagent.host

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.FileOutputStream

class SshBridgeFileTransfer(
    private val workspace: MobileWorkspace,
    private val connectTimeoutMs: () -> Int,
    private val log: (String, String, String, JSONObject) -> Unit,
    private val fail: (String) -> JSONObject,
    private val setLastError: (String) -> Unit
) {
    fun push(current: Session, localPath: String, remotePath: String, overwrite: Boolean = true): JSONObject {
        val localFile = workspace.resolvePath(localPath)
        if (!localFile.exists() || !localFile.isFile) return fail("local file not found: $localPath")
        val sftp = openSftp(current) ?: return fail("open sftp channel failed")
        try {
            ensureRemoteDirectory(sftp, remotePath)
            if (!overwrite && remoteExists(sftp, remotePath)) return fail("remote file exists and overwrite is false: $remotePath")
            sftp.put(localFile.absolutePath, remotePath, if (overwrite) ChannelSftp.OVERWRITE else ChannelSftp.RESUME)
            val stat = runCatching { sftp.stat(remotePath) }.getOrNull()
            val result = JSONObject()
                .put("local_path", localFile.canonicalPath)
                .put("remote_path", remotePath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pushed", JSONObject().put("local_path", localFile.canonicalPath).put("remote_path", remotePath))
            return JSONObject().put("ok", true).put("status", "ok").put("result", result)
        } catch (exc: Exception) {
            setLastError(exc.message ?: exc.javaClass.simpleName)
            return fail("file push failed: ${SshBridgeDiagnostics.formatException(exc)}")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    fun pull(current: Session, remotePath: String, localPath: String = "", overwrite: Boolean = true): JSONObject {
        val sftp = openSftp(current) ?: return fail("open sftp channel failed")
        try {
            val targetPath = if (localPath.isBlank()) {
                "workspace:/ssh/${remotePath.substringAfterLast('/', "download.txt")}"
            } else {
                localPath
            }
            val localFile = workspace.resolvePath(targetPath)
            if (localFile.exists() && localFile.isFile && !overwrite) return fail("local file exists and overwrite is false: $targetPath")
            localFile.parentFile?.mkdirs()
            if (localFile.exists() && overwrite) runCatching { localFile.delete() }
            FileOutputStream(localFile).use { output -> sftp.get(remotePath, output) }
            val stat = runCatching { sftp.stat(remotePath) }.getOrNull()
            val result = JSONObject()
                .put("remote_path", remotePath)
                .put("local_path", localFile.canonicalPath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pulled", JSONObject().put("remote_path", remotePath).put("local_path", localFile.canonicalPath))
            return JSONObject().put("ok", true).put("status", "ok").put("result", result)
        } catch (exc: Exception) {
            setLastError(exc.message ?: exc.javaClass.simpleName)
            return fail("file pull failed: ${SshBridgeDiagnostics.formatException(exc)}")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    private fun openSftp(session: Session): ChannelSftp? {
        return runCatching {
            (session.openChannel("sftp") as ChannelSftp).also { it.connect(connectTimeoutMs()) }
        }.onFailure {
            setLastError("open sftp channel failed: ${SshBridgeDiagnostics.formatException(it)}")
        }.getOrNull()
    }

    private fun remoteExists(sftp: ChannelSftp, remotePath: String): Boolean {
        return runCatching {
            sftp.stat(remotePath)
            true
        }.getOrElse { false }
    }

    private fun ensureRemoteDirectory(sftp: ChannelSftp, remotePath: String) {
        val normalized = remotePath.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isBlank()) return
        var current = if (normalized.startsWith("/")) "/" else ""
        parent.split('/').filter { it.isNotBlank() }.forEach { part ->
            current = if (current.isBlank() || current == "/") "$current$part" else "$current/$part"
            if (!remoteExists(sftp, current)) {
                runCatching { sftp.mkdir(current) }
            }
        }
    }
}
