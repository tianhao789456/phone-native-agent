package com.mobileagent.host

import org.json.JSONObject

class NativeLoopGuardState(
    private val initialRetryBudget: Int = 2,
    private val repeatedFailureLimit: Int = 3
) {
    private val retryBudget = mutableMapOf<String, Int>()
    private val failurePatterns = mutableMapOf<String, Int>()

    fun retriesLeft(name: String, arguments: JSONObject, state: String): Int {
        val retryKey = "$name:${arguments.toString().take(160)}"
        return if (state == "success") {
            retryBudget.remove(retryKey)
            initialRetryBudget
        } else {
            val next = (retryBudget[retryKey] ?: initialRetryBudget) - 1
            retryBudget[retryKey] = next.coerceAtLeast(0)
            next.coerceAtLeast(0)
        }
    }

    fun failureCount(failureKey: String): Int {
        val count = (failurePatterns[failureKey] ?: 0) + 1
        failurePatterns[failureKey] = count
        return count
    }

    fun isRepeatedFailure(count: Int): Boolean {
        return count >= repeatedFailureLimit
    }
}
