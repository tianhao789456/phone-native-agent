package com.mobileagent.host

import org.json.JSONObject

object SshBridgeDiagnostics {
    fun hint(status: String): String {
        return when (status) {
            "ssh_banner_ok" -> "TCP and SSH banner are reachable. If ssh_connect still fails, check username, key, authorized_keys, and OpenSSH logs."
            "tcp_connected_no_ssh_banner" -> "TCP connected but no SSH banner was read. Check Tailscale/VPN route, firewall interception, or whether the target port is really OpenSSH."
            else -> "TCP connection failed. Check host address, network reachability, Tailscale/LAN connectivity, Windows firewall, and sshd service state."
        }
    }

    fun parseCandidateHosts(arguments: JSONObject, fallbackHost: String): List<String> {
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
        fallbackHost.takeIf { it.isNotBlank() }?.let { values.add(it) }
        return values.toList()
    }

    fun formatException(exc: Throwable): String {
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
