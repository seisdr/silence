package com.silence.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.silence.app.MainActivity
import com.silence.app.R
import com.silence.app.SilenceApplication
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service for receiving incoming calls.
 *
 * The WebSocket signaling connection is the only persistent resource needed
 * for incoming call notifications. This service keeps the WebSocket alive
 * and shows a persistent notification when the app is backgrounded.
 *
 * Incoming WebRTC calls are relayed from the signaling server through the
 * WebSocket — no SIP registration needed.
 */
@AndroidEntryPoint
class CallService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.silence.app.action.STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_ready)))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stopIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, SilenceApplication.CHANNEL_CALL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_key)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                stopIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
