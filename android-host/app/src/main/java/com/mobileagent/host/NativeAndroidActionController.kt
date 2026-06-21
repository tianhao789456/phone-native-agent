package com.mobileagent.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

class NativeAndroidActionController(private val context: Context) {
    fun openApp(packageName: String): JSONObject {
        if (packageName.isBlank() || packageName.contains("/") || packageName.contains(" ")) {
            throw IllegalArgumentException("package must be an Android package name")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject()
                .put("ok", false)
                .put("package", packageName)
                .put("error", "No launch intent for package: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("action", "open_app")
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 500))
    }

    fun openUrl(url: String): JSONObject {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            throw IllegalArgumentException("url is required")
        }
        if (!cleanUrl.startsWith("https://", ignoreCase = true) && !cleanUrl.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("url", cleanUrl)
            .put("action", "open_url")
            .put("after_observe", AccessibilityState.observeAfterAction(delayMs = 800))
    }
}
