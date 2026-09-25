package com.github.aar0u.sidecar.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.aar0u.sidecar.MainActivity
import com.github.aar0u.sidecar.R
import com.github.aar0u.sidecar.model.ServiceConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Background daemon & Foreground Service controller.
 * 1. Hosts Foreground Service notification for Mode 2 (keepAlive=true) services (e.g. oktv)
 *    to prevent Android LMK / background battery killer from terminating them.
 * 2. Provides quick notification action to Stop background services.
 * 3. Handles onTaskRemoved (swiped away from Recents) to ensure clean process teardown.
 */
class SidecarDaemonService : Service() {

    companion object {
        private const val TAG = "SidecarDaemon"
        private const val CHANNEL_ID = "sidecar_service_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_FOREGROUND = "com.github.aar0u.sidecar.ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.github.aar0u.sidecar.ACTION_STOP_FOREGROUND"
        const val ACTION_STOP_SERVICE = "com.github.aar0u.sidecar.ACTION_STOP_SERVICE"

        const val EXTRA_SERVICE_ID = "extra_service_id"
        const val EXTRA_SERVICE_NAME = "extra_service_name"
        const val EXTRA_SERVICE_PORT = "extra_service_port"

        fun startForegroundForService(context: Context, service: ServiceConfig) {
            val intent = Intent(context, SidecarDaemonService::class.java).apply {
                action = ACTION_START_FOREGROUND
                putExtra(EXTRA_SERVICE_ID, service.id)
                putExtra(EXTRA_SERVICE_NAME, service.name)
                putExtra(EXTRA_SERVICE_PORT, service.port)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startForegroundService call FAILED (${e.javaClass.name}): ${e.message}", e)
            }
        }

        fun stopForegroundForService(context: Context, serviceId: String) {
            val intent = Intent(context, SidecarDaemonService::class.java).apply {
                action = ACTION_STOP_FOREGROUND
                putExtra(EXTRA_SERVICE_ID, serviceId)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop foreground for $serviceId: ${e.message}")
            }
        }
    }

    private val activeServices = ConcurrentHashMap<String, Pair<String, Int>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID) ?: return START_NOT_STICKY
                val serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME) ?: serviceId
                val port = intent.getIntExtra(EXTRA_SERVICE_PORT, 0)
                activeServices[serviceId] = Pair(serviceName, port)
                updateNotification()
            }
            ACTION_STOP_FOREGROUND -> {
                val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID)
                if (serviceId != null) {
                    activeServices.remove(serviceId)
                }
                if (activeServices.isEmpty()) {
                    removeForeground()
                } else {
                    updateNotification()
                }
            }
            ACTION_STOP_SERVICE -> {
                val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID)
                if (serviceId != null) {
                    Log.i(TAG, "Notification stop action clicked for service: $serviceId")
                    ProcessManager.stop(serviceId)
                    activeServices.remove(serviceId)
                }
                if (activeServices.isEmpty()) {
                    removeForeground()
                } else {
                    updateNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        if (activeServices.isEmpty()) {
            removeForeground()
            return
        }

        val primary = activeServices.entries.first()
        val title = if (activeServices.size == 1) {
            "Sidecar: ${primary.value.first}"
        } else {
            "Sidecar: ${activeServices.size} services running"
        }
        val text = "Running in background (Port: ${primary.value.second})"

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingMainIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop Action button for notification
        val stopIntent = Intent(this, SidecarDaemonService::class.java).apply {
            action = ACTION_STOP_SERVICE
            putExtra(EXTRA_SERVICE_ID, primary.key)
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingMainIntent)
            .addAction(0, "停止 (Stop)", pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "startForeground succeeded for ${activeServices.keys}")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground FAILED (${e.javaClass.name}): ${e.message}", e)
        }
    }

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sidecar Background Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status notification for background services"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (activeServices.isEmpty()) {
            Log.i(TAG, "Task removed (swiped away from Recents). No keepAlive services active, terminating all child processes...")
            ProcessManager.stopAll()
            removeForeground()
            stopSelf()
        } else {
            Log.i(TAG, "Task removed (swiped away from Recents). Keeping ${activeServices.size} keepAlive service(s) running in background.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Daemon service destroyed. Cleaning up running processes...")
        ProcessManager.stopAll()
        activeServices.clear()
        removeForeground()
    }
}
