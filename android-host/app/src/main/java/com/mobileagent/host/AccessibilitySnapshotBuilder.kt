package com.mobileagent.host

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object AccessibilitySnapshotBuilder {
    fun snapshot(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        maxDepth: Int,
        includeUninteresting: Boolean,
        visibleOnly: Boolean,
        maxTextChars: Int
    ): JSONObject {
        var emitted = 0
        val actions = JSONArray()

        fun build(node: AccessibilityNodeInfo, path: String, depth: Int): JSONObject? {
            if (emitted >= maxNodes || depth > maxDepth) return null
            val visible = node.isVisibleToUser
            if (visibleOnly && !visible) return null
            val interesting = includeUninteresting || AccessibilityNodeFormatter.isInteresting(node) || depth == 0
            var id = -1
            var summary: JSONObject? = null
            if (interesting) {
                id = emitted
                emitted += 1
                summary = AccessibilityNodeFormatter.summaryV2(node, id, path, depth, maxTextChars.coerceIn(40, 4000))
            }
            val children = JSONArray()
            for (index in 0 until node.childCount) {
                if (emitted >= maxNodes) break
                node.getChild(index)?.let { child ->
                    build(child, "$path.$index", depth + 1)?.let { children.put(it) }
                }
            }
            if (!interesting && children.length() == 0 && depth != 0) return null
            if (summary == null) {
                if (emitted >= maxNodes) return null
                id = emitted
                emitted += 1
                summary = AccessibilityNodeFormatter.summaryV2(node, id, path, depth, maxTextChars.coerceIn(40, 4000))
            }
            val nodeSummary = summary
            nodeSummary.put("children", children)
            val agentActions = nodeSummary.optJSONArray("agent_actions") ?: JSONArray()
            if (agentActions.length() > 0) {
                actions.put(
                    JSONObject()
                        .put("id", id)
                        .put("path", path)
                        .put("selector", nodeSummary.optString("selector"))
                        .put("actions", agentActions)
                        .put("text", nodeSummary.optString("text"))
                        .put("content_desc", nodeSummary.optString("content_desc"))
                        .put("view_id", nodeSummary.optString("view_id"))
                        .put("bounds", nodeSummary.optJSONObject("bounds") ?: JSONObject())
                )
            }
            return nodeSummary
        }

        val tree = build(root, "0", 0) ?: JSONObject()
        return JSONObject()
            .put("ok", true)
            .put("version", "accessibility_snapshot_v2")
            .put("package", root.packageName?.toString().orEmpty())
            .put("class", root.className?.toString().orEmpty())
            .put(
                "limits",
                JSONObject()
                    .put("max_nodes", maxNodes)
                    .put("max_depth", maxDepth)
                    .put("visible_only", visibleOnly)
                    .put("max_text_chars", maxTextChars.coerceIn(40, 4000))
            )
            .put("node_count", emitted)
            .put("truncated", emitted >= maxNodes)
            .put("tree", tree)
            .put("actionable", actions)
            .put("actionable_count", actions.length())
    }
}
