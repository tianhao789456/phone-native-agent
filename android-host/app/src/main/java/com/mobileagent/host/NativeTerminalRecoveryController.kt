package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

private data class RecoveryFuse(var failures: Int, var updatedAtMs: Long)
private data class TerminalRuntimeCache(var value: JSONObject, var updatedAtMs: Long)

class NativeTerminalRecoveryController(
    private val runtimeConfig: AgentRuntimeConfig,
    private val terminalStatus: () -> JSONObject,
    private val recoverTerminalBackend: (JSONObject) -> JSONObject,
    private val terminalPowerMode: () -> Boolean,
    private val outputState: (JSONObject) -> String
) {
    private val fuses = mutableMapOf<String, RecoveryFuse>()
    private val runtimeLock = Any()
    private var runtimeBusy = false
    private var runtimeCache: TerminalRuntimeCache? = null

    fun fuseOpen(name: String): JSONObject? {
        val now = System.currentTimeMillis()
        return synchronized(fuses) {
            val fuse = fuses[name] ?: return@synchronized null
            if (now - fuse.updatedAtMs > TERMINAL_RECOVERY_FUSE_WINDOW_MS) {
                fuses.remove(name)
                return@synchronized null
            }
            if (fuse.failures < TERMINAL_RECOVERY_FUSE_FAILURES) return@synchronized null
            JSONObject()
                .put("ok", false)
                .put("reason", "terminal recovery circuit is open after repeated failed recovery attempts")
                .put("failures", fuse.failures)
                .put("window_ms", TERMINAL_RECOVERY_FUSE_WINDOW_MS)
                .put(
                    "next_actions",
                    JSONArray()
                        .put("Run diagnose_terminal or termux_status to inspect the backend state.")
                        .put("Use recover_terminal_backend manually after fixing Termux or endpoint configuration.")
                        .put("Start a new app process or wait for the fuse window before automatic recovery resumes.")
                )
        }
    }

    fun recordOutcome(name: String, recovered: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(fuses) {
            if (recovered) {
                fuses.remove(name)
                return
            }
            val current = fuses[name]
            if (current == null || now - current.updatedAtMs > TERMINAL_RECOVERY_FUSE_WINDOW_MS) {
                fuses[name] = RecoveryFuse(1, now)
            } else {
                current.failures += 1
                current.updatedAtMs = now
            }
        }
    }

    fun shouldAutoRecover(name: String, output: JSONObject, actionsApproved: Boolean): Boolean {
        if (!actionsApproved) return false
        if (!terminalPowerMode()) return false
        if (!NativeToolRegistry.isAutoRecoverable(name)) return false
        val state = outputState(output)
        if (state == "success" || state == "needs_confirmation" || state == "needs_permission") return false
        val result = output.optJSONObject("result")
        val errorText = output.optString("error", "")
            .ifBlank { result?.optString("error", "") ?: "" }
            .lowercase()
        if (errorText.contains("command is required") || errorText.contains("task_id is required")) return false
        return true
    }

    fun runtimeStatus(autoRecover: Boolean, force: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        synchronized(runtimeLock) {
            if (!force && runtimeCache != null && now - runtimeCache!!.updatedAtMs < TERMINAL_RUNTIME_CACHE_MS) {
                return JSONObject(runtimeCache!!.value.toString())
            }
            if (runtimeBusy) {
                return runtimeCache?.value?.let { cached ->
                    JSONObject(cached.toString()).put("monitor", "busy")
                } ?: runtimeBase("checking")
            }
            runtimeBusy = true
        }
        try {
            val status = terminalStatus()
            val runtime = runtimeFromStatus(status)
            if (runtime.optString("state") == "ok" || runtime.optString("state") == "disabled" || !autoRecover) {
                cacheRuntime(runtime)
                return runtime
            }
            if (!terminalPowerMode()) {
                val failed = JSONObject(runtime.toString())
                    .put("state", "failed")
                    .put("recovery_attempted", false)
                    .put("reason", "terminal recovery requires danger or developer permission mode")
                cacheRuntime(failed)
                return failed
            }
            fuseOpen("terminal_monitor")?.let { fuse ->
                val circuit = JSONObject(runtime.toString())
                    .put("state", "circuit_open")
                    .put("recovery_attempted", false)
                    .put("fuse", fuse)
                cacheRuntime(circuit)
                return circuit
            }
            cacheRuntime(JSONObject(runtime.toString()).put("state", "recovering"))
            val recovery = recoverTerminalBackend(
                JSONObject()
                    .put("use_run_command", true)
                    .put("open_termux", false)
                    .put("force_restart", true)
                    .put("wait_ms", 2500)
            )
            val after = runtimeFromStatus(terminalStatus())
            val recovered = after.optString("state") == "ok"
            recordOutcome("terminal_monitor", recovered)
            val output = JSONObject(after.toString())
                .put("state", if (recovered) "recovered" else "failed")
                .put("recovery_attempted", true)
                .put("recovery", recovery)
            cacheRuntime(output)
            return output
        } finally {
            synchronized(runtimeLock) {
                runtimeBusy = false
            }
        }
    }

    private fun runtimeFromStatus(status: JSONObject): JSONObject {
        val state = when {
            !status.optBoolean("configured", false) -> "disabled"
            status.optBoolean("available", false) -> "ok"
            status.optString("status").isNotBlank() -> status.optString("status")
            else -> "offline"
        }
        return runtimeBase(state)
            .put("available", status.optBoolean("available", false))
            .put("status", status.optString("status", state))
            .put("detail", status)
    }

    private fun runtimeBase(state: String): JSONObject {
        return JSONObject()
            .put("state", state)
            .put("configured", runtimeConfig.terminalEnabled())
            .put("base_url", runtimeConfig.terminalBaseUrl())
            .put("checked_at_ms", System.currentTimeMillis())
    }

    private fun cacheRuntime(value: JSONObject) {
        synchronized(runtimeLock) {
            runtimeCache = TerminalRuntimeCache(JSONObject(value.toString()), System.currentTimeMillis())
        }
    }

    companion object {
        private const val TERMINAL_RECOVERY_FUSE_FAILURES = 2
        private const val TERMINAL_RECOVERY_FUSE_WINDOW_MS = 10 * 60 * 1000L
        private const val TERMINAL_RUNTIME_CACHE_MS = 10_000L
    }
}
