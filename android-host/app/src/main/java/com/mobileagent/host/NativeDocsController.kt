package com.mobileagent.host

import android.content.Context
import org.json.JSONObject

class NativeDocsController(
    private val context: Context,
    private val workspace: MobileWorkspace,
    private val log: (String, String, String, JSONObject?) -> Unit
) {
    fun index(): JSONObject = MobileAgentDocs.index(context)

    fun read(arguments: JSONObject): JSONObject {
        return MobileAgentDocs.read(
            context,
            arguments.optString("path"),
            arguments.optInt("max_bytes", 40000)
        )
    }

    fun search(arguments: JSONObject): JSONObject {
        return MobileAgentDocs.search(
            context,
            arguments.optString("query"),
            arguments.optInt("max_matches", 30)
        )
    }

    fun sync(): JSONObject = MobileAgentDocs.syncToWorkspace(context, workspace)

    fun syncOnce() {
        if (officialDocsSynced) return
        synchronized(NativeDocsController::class.java) {
            if (officialDocsSynced) return
            val result = runCatching { MobileAgentDocs.syncToWorkspace(context, workspace) }
            result.onSuccess {
                log(
                    "info",
                    "docs",
                    "official docs synced",
                    JSONObject()
                        .put("written_count", it.optInt("written_count", 0))
                        .put("root", it.optString("root", "docs/official"))
                )
            }.onFailure {
                log(
                    "warn",
                    "docs",
                    "official docs sync failed",
                    JSONObject()
                        .put("error_type", it.javaClass.simpleName)
                        .put("error", it.message ?: "")
                )
            }
            officialDocsSynced = true
        }
    }

    companion object {
        @Volatile
        private var officialDocsSynced = false
    }
}
