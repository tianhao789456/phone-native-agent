package com.mobileagent.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection

class AndroidIntentTools(
    private val context: Context,
    private val workspace: MobileWorkspace
) {
    fun intentOpen(arguments: JSONObject): JSONObject {
        val action = arguments.optString("action", Intent.ACTION_VIEW).ifBlank { Intent.ACTION_VIEW }
        val data = arguments.optString("data", "").trim()
        val type = arguments.optString("type", arguments.optString("mime_type", "")).trim()
        val packageName = arguments.optString("package", "").trim()
        val className = arguments.optString("class", arguments.optString("class_name", "")).trim()
        val categories = arguments.optJSONArray("categories") ?: JSONArray()
        val extras = arguments.optJSONObject("extras") ?: JSONObject()
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            data.isNotBlank() && type.isNotBlank() -> intent.setDataAndType(Uri.parse(data), type)
            data.isNotBlank() -> intent.data = Uri.parse(data)
            type.isNotBlank() -> intent.type = type
        }
        if (packageName.isNotBlank() && className.isNotBlank()) {
            intent.setClassName(packageName, className)
        } else if (packageName.isNotBlank()) {
            intent.setPackage(packageName)
        }
        for (index in 0 until categories.length()) {
            categories.optString(index, "").takeIf { it.isNotBlank() }?.let { intent.addCategory(it) }
        }
        extras.keys().forEach { key ->
            val value = extras.opt(key)
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value?.toString() ?: "")
            }
        }
        val chooserTitle = arguments.optString("chooser_title", "").trim()
        val started = runCatching {
            if (chooserTitle.isNotBlank()) {
                context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                context.startActivity(intent)
            }
            true
        }.getOrElse { throw IllegalArgumentException("No activity handled intent: ${it.message}") }
        return JSONObject()
            .put("ok", started)
            .put("action", "intent_open")
            .put("intent_action", action)
            .put("data", data)
            .put("type", type)
            .put("package", packageName)
            .put("class", className)
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 800))
    }

    fun shareFile(arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        if (path.isBlank()) throw IllegalArgumentException("path is required")
        val file = workspace.resolvePath(path)
        if (!file.isFile) throw IllegalArgumentException("path is not a readable file: $path")
        val mimeType = arguments.optString("mime_type", "").trim().ifBlank { guessMimeType(file) }
        val text = arguments.optString("text", "").trim()
        val title = arguments.optString("title", "Share file").ifBlank { "Share file" }
        val packageName = arguments.optString("package", "").trim()
        val uri = contentUriForFile(file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (packageName.isNotBlank()) intent.setPackage(packageName)
        if (packageName.isNotBlank()) {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject()
            .put("ok", true)
            .put("action", "share_file")
            .put("path", path)
            .put("resolved_path", file.absolutePath)
            .put("uri", uri.toString())
            .put("mime_type", mimeType)
            .put("package", packageName)
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 800))
    }

    fun openFileWith(arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        if (path.isBlank()) throw IllegalArgumentException("path is required")
        val file = workspace.resolvePath(path)
        if (!file.isFile) throw IllegalArgumentException("path is not a readable file: $path")
        val mimeType = arguments.optString("mime_type", "").trim().ifBlank { guessMimeType(file) }
        val packageName = arguments.optString("package", "").trim()
        val chooserTitle = arguments.optString("chooser_title", "").trim()
        val uri = contentUriForFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageName.isNotBlank()) intent.setPackage(packageName)
        if (packageName.isNotBlank()) {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (chooserTitle.isNotBlank()) {
            context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(intent)
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "open_file_with")
            .put("path", path)
            .put("resolved_path", file.absolutePath)
            .put("uri", uri.toString())
            .put("mime_type", mimeType)
            .put("package", packageName)
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 800))
    }

    private fun contentUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun guessMimeType(file: File): String {
        return URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }
}
