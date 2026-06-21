package com.mobileagent.host

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

class SshBridge(
    private val context: android.content.Context,
    private val runtimeConfig: AgentRuntimeConfig,
    private val workspace: MobileWorkspace,
    private val log: (String, String, String, org.json.JSONObject) -> Unit
) {
    companion object {
        private val lock = Any()
        @Volatile private var session: Session? = null
        @Volatile private var lastError: String = ""
        @Volatile private var lastConnectedAtMs: Long = 0L
        @Volatile private var lastCommandAtMs: Long = 0L
        @Volatile private var lastRemoteHome: String = ""
        @Volatile private var lastFingerprint: String = ""
        @Volatile private var forwardedLocalPort: Int = 0
        @Volatile private var forwardedRemoteHost: String = ""
        @Volatile private var forwardedRemotePort: Int = 0
    }

    fun status(): org.json.JSONObject {
        synchronized(lock) {
            cleanupStaleSession()
            val connected = session?.isConnected == true
            return org.json.JSONObject()
                .put("configured", runtimeConfig.sshEnabled())
                .put("enabled", runtimeConfig.sshEnabled())
                .put("connected", connected)
                .put("status", when {
                    !runtimeConfig.sshEnabled() -> "disabled"
                    connected -> "connected"
                    lastError.isNotBlank() -> "error"
                    else -> "offline"
                })
                .put("host", runtimeConfig.sshHost())
                .put("port", runtimeConfig.sshPort())
                .put("user", runtimeConfig.sshUser())
                .put("key_path", runtimeConfig.sshKeyPath())
                .put("has_passphrase", runtimeConfig.sshPassphrase().isNotBlank())
                .put("connect_timeout_ms", runtimeConfig.sshConnectTimeoutMs())
                .put("command_timeout_ms", runtimeConfig.sshCommandTimeoutMs())
                .put("last_error", lastError)
                .put("last_connected_at_ms", lastConnectedAtMs)
                .put("last_command_at_ms", lastCommandAtMs)
                .put("remote_home", lastRemoteHome)
                .put("fingerprint", lastFingerprint)
                .put("forwarding", forwardingStatusLocked())
                .put("backend", "jsch")
        }
    }

    fun connect(overrides: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        synchronized(lock) {
            applyOverrides(overrides)
            cleanupSessionLocked()

            val host = runtimeConfig.sshHost()
            val user = runtimeConfig.sshUser()
            val port = runtimeConfig.sshPort()
            if (host.isBlank() || user.isBlank()) return fail("ssh host/user is not configured")
            val keyPath = runtimeConfig.sshKeyPath()
            if (keyPath.isBlank()) return fail("ssh key path is required")
            val identityFile = resolveIdentityFile(keyPath) ?: return fail("ssh key file not found: $keyPath")

            val jsch = JSch()
            val passphrase = runtimeConfig.sshPassphrase()
            runCatching {
                if (passphrase.isBlank()) {
                    jsch.addIdentity(identityFile.absolutePath)
                } else {
                    jsch.addIdentity(identityFile.absolutePath, passphrase)
                }
            }.getOrElse {
                return fail("load identity failed: ${formatException(it)}")
            }

            val connected = runCatching {
                val sshSession = jsch.getSession(user, host, port)
                sshSession.setConfig(clientConfig())
                sshSession.timeout = runtimeConfig.sshConnectTimeoutMs()
                sshSession.connect(runtimeConfig.sshConnectTimeoutMs())
                sshSession
            }.getOrElse { exc ->
                cleanupSessionLocked()
                return fail("ssh connect failed: ${formatException(exc)}")
            }

            session = connected
            lastError = ""
            lastConnectedAtMs = System.currentTimeMillis()
            lastFingerprint = runCatching { connected.hostKey?.getFingerPrint(jsch) ?: "" }.getOrDefault("")
            log(
                "info",
                "ssh",
                "ssh connected",
                org.json.JSONObject()
                    .put("host", host)
                    .put("port", port)
                    .put("user", user)
                    .put("backend", "jsch")
            )
            return org.json.JSONObject()
                .put("ok", true)
                .put("connected", true)
                .put("status", "connected")
                .put("host", host)
                .put("port", port)
                .put("user", user)
                .put("remote_home", lastRemoteHome)
                .put("fingerprint", lastFingerprint)
                .put("connect_timeout_ms", runtimeConfig.sshConnectTimeoutMs())
                .put("command_timeout_ms", runtimeConfig.sshCommandTimeoutMs())
                .put("backend", "jsch")
        }
    }

    fun disconnect(): org.json.JSONObject {
        synchronized(lock) {
            val hadSession = session?.isConnected == true
            cleanupSessionLocked()
            lastError = ""
            log("info", "ssh", "ssh disconnected", org.json.JSONObject().put("had_session", hadSession))
            return org.json.JSONObject()
                .put("ok", true)
                .put("connected", false)
                .put("status", "disconnected")
        }
    }

    fun forwardStatus(): org.json.JSONObject {
        synchronized(lock) {
            cleanupStaleSession()
            return forwardingStatusLocked()
        }
    }

    fun startLocalForward(arguments: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        synchronized(lock) {
            val current = ensureSessionLocked() ?: return fail(lastError.ifBlank { "ssh is not connected" })
            val localPort = arguments.optInt("local_port", 18000).coerceIn(1024, 65535)
            val remoteHost = arguments.optString("remote_host", "127.0.0.1").ifBlank { "127.0.0.1" }
            val remotePort = arguments.optInt("remote_port", 8000).coerceIn(1, 65535)

            if (forwardedLocalPort == localPort &&
                forwardedRemoteHost == remoteHost &&
                forwardedRemotePort == remotePort
            ) {
                return org.json.JSONObject()
                    .put("ok", true)
                    .put("status", "already_forwarding")
                    .put("forwarding", forwardingStatusLocked())
            }

            clearForwardLocked(current)
            return runCatching {
                current.setPortForwardingL("127.0.0.1", localPort, remoteHost, remotePort)
                forwardedLocalPort = localPort
                forwardedRemoteHost = remoteHost
                forwardedRemotePort = remotePort
                lastError = ""
                val status = forwardingStatusLocked()
                log("info", "ssh", "ssh local port forwarding started", status)
                org.json.JSONObject()
                    .put("ok", true)
                    .put("status", "forwarding")
                    .put("forwarding", status)
            }.getOrElse {
                fail("ssh local forwarding failed: ${formatException(it)}")
            }
        }
    }

    fun stopLocalForward(): org.json.JSONObject {
        synchronized(lock) {
            val current = session
            val hadForward = forwardedLocalPort > 0
            if (current?.isConnected == true) {
                clearForwardLocked(current)
            } else {
                forwardedLocalPort = 0
                forwardedRemoteHost = ""
                forwardedRemotePort = 0
            }
            return org.json.JSONObject()
                .put("ok", true)
                .put("status", if (hadForward) "stopped" else "not_forwarding")
                .put("forwarding", forwardingStatusLocked())
        }
    }

    fun diagnose(arguments: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        val host = arguments.optString("host", runtimeConfig.sshHost()).trim()
        val port = arguments.optInt("port", runtimeConfig.sshPort()).coerceIn(1, 65535)
        val timeoutMs = arguments.optInt("timeout_ms", runtimeConfig.sshConnectTimeoutMs()).coerceIn(1_000, 30_000)
        if (host.isBlank()) return fail("ssh diagnose failed: host is required")

        val startedAt = System.currentTimeMillis()
        var tcpConnected = false
        var banner = ""
        var error = ""
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                tcpConnected = true
                val buffer = ByteArray(256)
                val read = socket.getInputStream().read(buffer)
                if (read > 0) {
                    banner = String(buffer, 0, read, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty().trim()
                }
            }
        } catch (exc: Exception) {
            error = formatException(exc)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val bannerOk = banner.startsWith("SSH-2.0")
        val status = when {
            bannerOk -> "ssh_banner_ok"
            tcpConnected -> "tcp_connected_no_ssh_banner"
            else -> "tcp_connect_failed"
        }
        val ok = bannerOk
        val result = org.json.JSONObject()
            .put("ok", ok)
            .put("status", status)
            .put("host", host)
            .put("port", port)
            .put("timeout_ms", timeoutMs)
            .put("elapsed_ms", elapsedMs)
            .put("tcp_connected", tcpConnected)
            .put("ssh_banner_ok", bannerOk)
            .put("banner", banner)
            .put("error", error)
            .put("hint", diagnoseHint(status))
        log(
            if (ok) "info" else "warn",
            "ssh",
            "ssh diagnose completed",
            org.json.JSONObject()
                .put("host", host)
                .put("port", port)
                .put("status", status)
                .put("elapsed_ms", elapsedMs)
                .put("error", error.take(300))
        )
        return result
    }

    fun selectHost(arguments: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        val port = arguments.optInt("port", runtimeConfig.sshPort()).coerceIn(1, 65535)
        val timeoutMs = arguments.optInt("timeout_ms", 6_000).coerceIn(1_000, 30_000)
        val apply = arguments.optBoolean("apply", true)
        val candidates = parseCandidateHosts(arguments)
        if (candidates.isEmpty()) return fail("ssh select host failed: candidates are required")

        val results = org.json.JSONArray()
        var selected: org.json.JSONObject? = null
        candidates.forEach { host ->
            val diagnostic = diagnose(
                org.json.JSONObject()
                    .put("host", host)
                    .put("port", port)
                    .put("timeout_ms", timeoutMs)
            )
            results.put(diagnostic)
            if (selected == null && diagnostic.optString("status") == "ssh_banner_ok") {
                selected = diagnostic
            }
        }

        val chosen = selected
        if (apply && chosen != null) {
            runtimeConfig.setSshConfig(
                enabled = true,
                host = chosen.optString("host"),
                port = port,
                user = runtimeConfig.sshUser(),
                keyPath = runtimeConfig.sshKeyPath(),
                passphrase = runtimeConfig.sshPassphrase(),
                connectTimeoutMs = runtimeConfig.sshConnectTimeoutMs(),
                commandTimeoutMs = runtimeConfig.sshCommandTimeoutMs()
            )
        }

        val ok = chosen != null
        return org.json.JSONObject()
            .put("ok", ok)
            .put("status", if (ok) "selected" else "no_ssh_banner")
            .put("selected_host", chosen?.optString("host") ?: "")
            .put("selected_port", if (ok) port else 0)
            .put("applied", ok && apply)
            .put("results", results)
            .put(
                "hint",
                if (ok) "Selected the first candidate that returned an SSH banner."
                else "No candidate returned an SSH banner. Check LAN/Tailscale routing, firewall, and sshd service."
            )
    }

    fun run(
        command: String,
        cwd: String = "",
        shell: String = "powershell",
        timeoutMs: Int = runtimeConfig.sshCommandTimeoutMs()
    ): org.json.JSONObject {
        if (command.isBlank()) return fail("command is required")
        if (!runtimeConfig.sshEnabled()) return fail("ssh is disabled")
        val current = synchronized(lock) {
            ensureSessionLocked() ?: return fail(lastError.ifBlank { "ssh is not connected" })
        }

        val remoteCommand = buildRemoteCommand(command, cwd, shell)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var returnCode = -1
        var timedOut = false
        val channel = runCatching { current.openChannel("exec") as ChannelExec }
            .getOrElse { return fail("open exec channel failed: ${formatException(it)}") }
        try {
            channel.setCommand(remoteCommand)
            channel.setInputStream(null)
            channel.setErrStream(stderr)
            val input = channel.inputStream
            channel.connect(timeoutMs)
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                while (input.available() > 0) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    stdout.write(buffer, 0, read)
                }
                Thread.sleep(50)
            }
            while (input.available() > 0) {
                val read = input.read(buffer)
                if (read <= 0) break
                stdout.write(buffer, 0, read)
            }
            timedOut = !channel.isClosed
            returnCode = if (timedOut) -1 else channel.exitStatus
        } catch (exc: Exception) {
            lastError = "ssh command failed: ${formatException(exc)}"
            return fail(lastError)
        } finally {
            runCatching { channel.disconnect() }
        }

        lastCommandAtMs = System.currentTimeMillis()
        val outText = String(stdout.toByteArray(), Charsets.UTF_8)
        val errText = String(stderr.toByteArray(), Charsets.UTF_8)
        val result = org.json.JSONObject()
            .put("host", runtimeConfig.sshHost())
            .put("user", runtimeConfig.sshUser())
            .put("command", command)
            .put("cwd", cwd)
            .put("shell", shell)
            .put("remote_command", remoteCommand)
            .put("returncode", returnCode)
            .put("timed_out", timedOut)
            .put("stdout", outText)
            .put("stderr", errText)
            .put("stdout_bytes", stdout.size())
            .put("stderr_bytes", stderr.size())
            .put("truncated", false)
        val ok = !timedOut && returnCode == 0
        if (ok) {
            lastError = ""
            log("info", "ssh", "ssh command completed", org.json.JSONObject().put("command", command).put("stdout", outText.take(1200)))
        } else {
            lastError = errText.ifBlank { if (timedOut) "ssh command timed out" else "ssh command failed with exit $returnCode" }
            log("warn", "ssh", "ssh command failed", org.json.JSONObject().put("command", command).put("returncode", returnCode).put("timed_out", timedOut).put("stderr", errText.take(1200)))
        }
        return org.json.JSONObject()
            .put("ok", ok)
            .put("connected", current.isConnected)
            .put("status", if (ok) "ok" else if (timedOut) "timeout" else "command_failed")
            .put("result", result)
            .put("error", if (ok) "" else lastError)
    }

    fun push(localPath: String, remotePath: String, overwrite: Boolean = true): org.json.JSONObject {
        val current = synchronized(lock) {
            if (localPath.isBlank() || remotePath.isBlank()) return fail("local_path and remote_path are required")
            ensureSessionLocked() ?: return fail(lastError.ifBlank { "ssh is not connected" })
        }
        val localFile = workspace.resolvePath(localPath)
        if (!localFile.exists() || !localFile.isFile) return fail("local file not found: $localPath")
        val sftp = openSftp(current) ?: return fail(lastError.ifBlank { "open sftp channel failed" })
        try {
            ensureRemoteDirectory(sftp, remotePath)
            if (!overwrite && remoteExists(sftp, remotePath)) return fail("remote file exists and overwrite is false: $remotePath")
            sftp.put(localFile.absolutePath, remotePath, if (overwrite) ChannelSftp.OVERWRITE else ChannelSftp.RESUME)
            val stat = runCatching { sftp.stat(remotePath) }.getOrNull()
            val result = org.json.JSONObject()
                .put("local_path", localFile.canonicalPath)
                .put("remote_path", remotePath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pushed", org.json.JSONObject().put("local_path", localFile.canonicalPath).put("remote_path", remotePath))
            return org.json.JSONObject().put("ok", true).put("status", "ok").put("result", result)
        } catch (exc: Exception) {
            lastError = exc.message ?: exc.javaClass.simpleName
            return fail("file push failed: ${formatException(exc)}")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    fun pull(remotePath: String, localPath: String = "", overwrite: Boolean = true): org.json.JSONObject {
        val current = synchronized(lock) {
            if (remotePath.isBlank()) return fail("remote_path is required")
            ensureSessionLocked() ?: return fail(lastError.ifBlank { "ssh is not connected" })
        }
        val sftp = openSftp(current) ?: return fail(lastError.ifBlank { "open sftp channel failed" })
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
            val result = org.json.JSONObject()
                .put("remote_path", remotePath)
                .put("local_path", localFile.canonicalPath)
                .put("bytes", localFile.length())
                .put("overwrite", overwrite)
                .put("remote_size", stat?.size ?: -1)
            log("info", "ssh", "ssh file pulled", org.json.JSONObject().put("remote_path", remotePath).put("local_path", localFile.canonicalPath))
            return org.json.JSONObject().put("ok", true).put("status", "ok").put("result", result)
        } catch (exc: Exception) {
            lastError = exc.message ?: exc.javaClass.simpleName
            return fail("file pull failed: ${formatException(exc)}")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    private fun ensureSessionLocked(): Session? {
        cleanupStaleSession()
        val current = session
        if (current?.isConnected == true) return current
        val result = connect()
        return if (result.optBoolean("ok", false)) session else null
    }

    private fun cleanupStaleSession() {
        val current = session
        if (current != null && !current.isConnected) cleanupSessionLocked()
    }

    private fun cleanupSessionLocked() {
        runCatching { session?.let { clearForwardLocked(it) } }
        runCatching { session?.disconnect() }
        session = null
        lastRemoteHome = ""
    }

    private fun clearForwardLocked(current: Session) {
        if (forwardedLocalPort > 0) {
            runCatching { current.delPortForwardingL(forwardedLocalPort) }
        }
        forwardedLocalPort = 0
        forwardedRemoteHost = ""
        forwardedRemotePort = 0
    }

    private fun forwardingStatusLocked(): org.json.JSONObject {
        val active = session?.isConnected == true && forwardedLocalPort > 0
        return org.json.JSONObject()
            .put("active", active)
            .put("local_host", if (active) "127.0.0.1" else "")
            .put("local_port", if (active) forwardedLocalPort else 0)
            .put("remote_host", if (active) forwardedRemoteHost else "")
            .put("remote_port", if (active) forwardedRemotePort else 0)
            .put("local_url", if (active) "http://127.0.0.1:$forwardedLocalPort" else "")
    }

    private fun applyOverrides(overrides: org.json.JSONObject) {
        if (!overrides.has("host") && !overrides.has("user") && !overrides.has("port") &&
            !overrides.has("key_path") && !overrides.has("passphrase") &&
            !overrides.has("connect_timeout_ms") && !overrides.has("command_timeout_ms") &&
            !overrides.has("enabled")
        ) return
        runtimeConfig.setSshConfig(
            enabled = overrides.optBoolean("enabled", runtimeConfig.sshEnabled()),
            host = overrides.optString("host", runtimeConfig.sshHost()),
            port = overrides.optInt("port", runtimeConfig.sshPort()),
            user = overrides.optString("user", runtimeConfig.sshUser()),
            keyPath = overrides.optString("key_path", runtimeConfig.sshKeyPath()),
            passphrase = overrides.optString("passphrase", runtimeConfig.sshPassphrase()),
            connectTimeoutMs = overrides.optInt("connect_timeout_ms", runtimeConfig.sshConnectTimeoutMs()),
            commandTimeoutMs = overrides.optInt("command_timeout_ms", runtimeConfig.sshCommandTimeoutMs())
        )
    }

    private fun clientConfig(): Properties {
        return Properties().apply {
            setProperty("StrictHostKeyChecking", "no")
            setProperty("PreferredAuthentications", "publickey")
            setProperty("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-ed25519")
            setProperty("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            setProperty("kex", "curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256")
        }
    }

    private fun resolveIdentityFile(path: String): File? {
        val candidate = runCatching { workspace.resolvePath(path) }.getOrNull() ?: File(path)
        return if (candidate.exists() && candidate.isFile) candidate else null
    }

    private fun openSftp(session: Session): ChannelSftp? {
        return runCatching {
            (session.openChannel("sftp") as ChannelSftp).also { it.connect(runtimeConfig.sshConnectTimeoutMs()) }
        }.onFailure {
            lastError = "open sftp channel failed: ${formatException(it)}"
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

    private fun buildRemoteCommand(command: String, cwd: String, shell: String): String {
        val withCwd = if (cwd.isBlank()) {
            command.trim()
        } else {
            when (shell.lowercase()) {
                "powershell", "pwsh" -> "Set-Location -LiteralPath '${cwd.replace("'", "''")}'; ${command.trim()}"
                "cmd" -> "cd /d \"${cwd.replace("\"", "\\\"")}\" && ${command.trim()}"
                "bash", "sh" -> "cd -- '${cwd.replace("'", "'\"'\"'")}' && ${command.trim()}"
                else -> "cd -- '${cwd.replace("'", "'\"'\"'")}' && ${command.trim()}"
            }
        }
        return when (shell.lowercase()) {
            "powershell", "pwsh" -> {
                val script = "\$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $withCwd"
                "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ${encodePowerShell(script)}"
            }
            "cmd" -> "cmd /d /s /c \"${withCwd.replace("\"", "\\\"")}\""
            "bash" -> "bash -lc '${withCwd.replace("'", "'\"'\"'")}'"
            "sh" -> "sh -lc '${withCwd.replace("'", "'\"'\"'")}'"
            else -> withCwd
        }
    }

    private fun encodePowerShell(script: String): String {
        return java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    }

    private fun fail(message: String): org.json.JSONObject {
        lastError = message
        log("error", "ssh", message, org.json.JSONObject())
        return org.json.JSONObject()
            .put("ok", false)
            .put("status", "error")
            .put("error", message)
            .put("result", org.json.JSONObject())
    }

    private fun diagnoseHint(status: String): String {
        return when (status) {
            "ssh_banner_ok" -> "TCP and SSH banner are reachable. If ssh_connect still fails, check username, key, authorized_keys, and OpenSSH logs."
            "tcp_connected_no_ssh_banner" -> "TCP connected but no SSH banner was read. Check Tailscale/VPN route, firewall interception, or whether the target port is really OpenSSH."
            else -> "TCP connection failed. Check host address, network reachability, Tailscale/LAN connectivity, Windows firewall, and sshd service state."
        }
    }

    private fun parseCandidateHosts(arguments: org.json.JSONObject): List<String> {
        val values = linkedSetOf<String>()
        val array = arguments.optJSONArray("hosts") ?: arguments.optJSONArray("candidates")
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let { values.add(it) }
            }
        }
        arguments.optString("host", "").split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { values.add(it) }
        runtimeConfig.sshHost().takeIf { it.isNotBlank() }?.let { values.add(it) }
        return values.toList()
    }

    private fun formatException(exc: Throwable): String {
        val cause = exc.cause
        val rootCause = generateSequence(cause) { it.cause }.lastOrNull()
        return buildString {
            append(exc.javaClass.simpleName)
            if (!exc.message.isNullOrBlank()) append(": ").append(exc.message)
            if (cause != null && cause !== exc) {
                append(" | cause=").append(cause.javaClass.simpleName)
                if (!cause.message.isNullOrBlank()) append(": ").append(cause.message)
            }
            if (rootCause != null && rootCause !== cause) {
                append(" | root=").append(rootCause.javaClass.simpleName)
                if (!rootCause.message.isNullOrBlank()) append(": ").append(rootCause.message)
            }
        }
    }
}
