package com.mobileagent.host

object SshBridgeCommandBuilder {
    fun build(command: String, cwd: String, shell: String): String {
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
}
