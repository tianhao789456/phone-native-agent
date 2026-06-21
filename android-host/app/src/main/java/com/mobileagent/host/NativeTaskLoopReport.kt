package com.mobileagent.host

import org.json.JSONArray
import org.json.JSONObject

object NativeTaskLoopReport {
    fun build(
        status: String,
        rounds: Int,
        maxRounds: Int,
        steps: Int,
        failedSteps: Int,
        taskPlan: JSONObject,
        trace: JSONArray,
        evidence: JSONArray,
        completionReview: JSONObject,
        stopRequested: Boolean,
        loopGuardBlocker: JSONObject?,
        userStopBlocker: JSONObject?
    ): JSONObject {
        val report = JSONObject()
            .put("status", status)
            .put("rounds", rounds)
            .put("max_rounds", maxRounds)
            .put("steps", steps)
            .put("failed_steps", failedSteps)
            .put("plan", JSONObject(taskPlan.toString()))
            .put("trace", trace)
            .put("evidence", evidence)
            .put("completion_review", completionReview)
            .put("stop_requested", stopRequested)
        loopGuardBlocker?.let { report.put("blocker", it) }
        userStopBlocker?.let { report.put("blocker", it) }
        return report
    }
}
