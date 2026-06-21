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
        val localFile = resolveLocalPath(localPath) ?: return fail(
            "local path cannot be resolved: $localPath. Use aliases such as shared_storage:/Download/..., files_root:/..., or workspace:/..."
        )
        if (!localFile.exists() || !localFile.isFile) return fail("local file not found: $localPath")
        val normalizedRemotePath = normalizeRemoteSftpPath(remotePath)
        val sftp = openSftp(current) ?: return fail("open sftp channel failed")
        try {
            ensureRemoteDirectory(sftp, normalizedRemotePath)
            if (!overwrite && remoteExists(sftp, normalizedRemotePath)) return fail("remote file exists and overwrite is false: $normalizedRemotePath")
            sftp.put(localFile.absolutePath, normalizedRemotePath, if (overwrite) ChannelSftp.OVERWRITE else ChannelSftp.RESUME)
            val stat = runCatching { sftp.stat(normalizedRemotePath) }.getOrNull()
            val result = JSONObject()
                .put("local_path", localFile.canonicalPath)
                .put("remote_path", normalizedRemotePath)
                .put("requested_remote_path", remotePath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pushed", JSONObject().put("local_path", localFile.canonicalPath).put("remote_path", normalizedRemotePath))
            return JSONObject().put("ok", true).put("status", "ok").put("result", result)
        } catch (exc: Exception) {
            setLastError(exc.message ?: exc.javaClass.simpleName)
            return fail("file push failed: ${SshBridgeDiagnostics.formatException(exc)}")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    fun pull(current: Session, remotePath: String, localPath: String = "", overwrite: Boolean = true): JSONObject {
        val normalizedRemotePath = normalizeRemoteSftpPath(remotePath)
        val sftp = openSftp(current) ?: return fail("open sftp channel failed")
        try {
            val targetPath = if (localPath.isBlank()) {
                "workspace:/ssh/${normalizedRemotePath.substringAfterLast('/', "download.txt")}"
            } else {
                localPath
            }
            val localFile = resolveLocalPath(targetPath) ?: return fail(
                "local path cannot be resolved: $targetPath. Use aliases such as shared_storage:/Download/..., files_root:/..., or workspace:/..."
            )
            if (localFile.exists() && localFile.isFile && !overwrite) return fail("local file exists and overwrite is false: $targetPath")
            localFile.parentFile?.mkdirs()
            if (localFile.exists() && overwrite) runCatching { localFile.delete() }
            FileOutputStream(localFile).use { output -> sftp.get(normalizedRemotePath, output) }
            val stat = runCatching { sftp.stat(normalizedRemotePath) }.getOrNull()
            val result = JSONObject()
                .put("remote_path", normalizedRemotePath)
                .put("requested_remote_path", remotePath)
                .put("local_path", localFile.canonicalPath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pulled", JSONObject().put("remote_path", normalizedRemotePath).put("local_path", localFile.canonicalPath))
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

    private fun resolveLocalPath(path: String): java.io.File? {
        return runCatching { workspace.resolvePath(path) }
            .onFailure { setLastError(it.message ?: it.javaClass.simpleName) }
            .getOrNull()
    }

    private fun normalizeRemoteSftpPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        return if (Regex("^[A-Za-z]:/.*").matches(normalized)) "/$normalized" else normalized
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
