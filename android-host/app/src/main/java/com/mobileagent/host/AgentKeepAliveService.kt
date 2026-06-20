package com.mobileagent.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class AgentKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            HostBridgeServer.start(this@AgentKeepAliveService)
            handler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AgentLogStore.record(this, "info", "keepalive", "keepalive service created")
        HostBridgeServer.start(this)
        startForeground(NOTIFICATION_ID, notification())
        handler.post(keepAliveRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentLogStore.record(this, "info", "keepalive", "keepalive service started")
        HostBridgeServer.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        AgentLogStore.record(this, "warn", "keepalive", "keepalive service destroyed")
        handler.removeCallbacks(keepAliveRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Mobile Agent 保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持手机 Agent 基座桥接与本地服务可用"
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("手机 Agent")
            .setContentText("基座保活中，桥接服务保持可用")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mobile-agent-keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val KEEP_ALIVE_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, AgentKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
