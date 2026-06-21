package com.mobileagent.host

import org.json.JSONObject

class NativeToolStepEvents(
    private val log: (String, String, String, JSONObject) -> Unit
) {
    fun started(name: String, arguments: JSONObject, step: Int, round: Int) {
        AgentEventStore.record(
            "tool_started",
            "开始工具 $name",
            JSONObject()
                .put("tool", name)
                .put("step", step)
                .put("round", round)
                .put("arguments", arguments.toString().take(1200))
        )
    }

    fun finished(name: String, state: String, step: Int, round: Int, durationMs: Long, summary: String) {
        AgentEventStore.record(
            "tool_finished",
            "工具 $name: $state",
            JSONObject()
                .put("tool", name)
                .put("state", state)
                .put("step", step)
                .put("round", round)
                .put("duration_ms", durationMs)
                .put("summary", summary)
        )
    }

    fun warnFailed(name: String, state: String, step: Int, round: Int, summary: String) {
        if (state == "success") return
        log(
            "warn",
            "tool",
            "tool step did not succeed",
            JSONObject()
                .put("tool", name)
                .put("state", state)
                .put("step", step)
                .put("round", round)
                .put("summary", summary)
        )
    }

    fun loopGuardStopped(message: String, blocker: JSONObject) {
        AgentEventStore.record("loop_guard_stopped", message, blocker)
    }
}
