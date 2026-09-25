package com.github.aar0u.sidecar.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Background daemon service to track Android task lifecycle.
 * Ensures clean teardown of child processes when the user swipes away the app from Recents.
 */
class SidecarDaemonService : Service() {

    companion object {
        private const val TAG = "SidecarDaemon"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (swiped away from Recents). Terminating all child service processes...")
        ProcessManager.stopAll()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Daemon service destroyed. Cleaning up running processes...")
        ProcessManager.stopAll()
    }
}
