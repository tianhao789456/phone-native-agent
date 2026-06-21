package com.mobileagent.host

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray

object AccessibilityTreeSearch {
    fun collect(node: AccessibilityNodeInfo, out: JSONArray, maxNodes: Int, path: String, depth: Int) {
        if (out.length() >= maxNodes) return
        if (AccessibilityNodeFormatter.isInteresting(node)) {
            out.put(
                AccessibilityNodeFormatter.summary(node)
                    .put("index", out.length())
                    .put("path", path)
                    .put("depth", depth)
                    .put("selector", AccessibilityNodeFormatter.selectorFor(node, path))
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collect(child, out, maxNodes, path = "$path.$index", depth = depth + 1)
            }
            if (out.length() >= maxNodes) return
        }
    }

    fun collectMatches(
        node: AccessibilityNodeInfo,
        out: JSONArray,
        query: String,
        contains: Boolean,
        maxNodes: Int,
        path: String,
        depth: Int
    ) {
        if (out.length() >= maxNodes) return
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        if (
            matches(text, query, contains) ||
            matches(desc, query, contains) ||
            matches(viewId, query, contains) ||
            matches(node.className?.toString().orEmpty(), query, contains)
        ) {
            out.put(
                AccessibilityNodeFormatter.summary(node)
                    .put("index", out.length())
                    .put("path", path)
                    .put("depth", depth)
                    .put("selector", AccessibilityNodeFormatter.selectorFor(node, path))
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectMatches(child, out, query, contains, maxNodes, path = "$path.$index", depth = depth + 1)
            }
            if (out.length() >= maxNodes) return
        }
    }

    fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val found = node.getChild(index)?.let { child -> findNode(child, predicate) }
            if (found != null) return found
        }
        return null
    }

    fun interestingNodeByIndex(root: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        var current = 0
        return findNode(root) { item ->
            val eligible = AccessibilityNodeFormatter.isInteresting(item)
            if (!eligible) return@findNode false
            if (current == index) return@findNode true
            current += 1
            false
        }
    }

    fun matches(value: String, expected: String, contains: Boolean): Boolean {
        return if (contains) {
            value.contains(expected, ignoreCase = true)
        } else {
            value.equals(expected, ignoreCase = true)
        }
    }
}
