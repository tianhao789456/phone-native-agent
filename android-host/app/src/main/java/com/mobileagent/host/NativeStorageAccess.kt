package com.mobileagent.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject

class NativeStorageAccess(
    private val context: Context
) {
    fun status(): JSONObject {
        val allFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            false
        }
        val sharedRoot = Environment.getExternalStorageDirectory()
        return JSONObject()
            .put("ok", true)
            .put("all_files_access", allFilesAccess)
            .put("shared_storage_root", sharedRoot.absolutePath)
            .put("download_alias", "shared_storage:/Download/")
            .put("can_read_download", runCatching { sharedRoot.resolve("Download").canRead() }.getOrDefault(false))
            .put(
                "status",
                if (allFilesAccess) "granted" else "needs_all_files_access"
            )
            .put(
                "hint",
                if (allFilesAccess) "shared_storage:/ paths can be used for Download/WeChat-exported files."
                else "Open Android file access settings for Mobile Agent Host and allow all files access before using shared_storage:/Download files."
            )
    }

    fun openSettings(): JSONObject {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            true
        }
        return status()
            .put("opened_settings", opened)
            .put("settings_action", "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
    }
}
