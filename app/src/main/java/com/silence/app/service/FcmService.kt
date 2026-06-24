package com.silence.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.silence.app.MainActivity
import com.silence.app.R
import com.silence.app.SilenceApplication

/**
 * Handles incoming FCM data messages for call signaling.
 *
 * When the callee is offline (no WebSocket to the relay), the
 * signaling server sends an FCM data message to wake the device.
 *
 * Flow:
 *   1. Server sends: {"room":"abc123","from":"alice_fp","type":"incoming_call"}
 *   2. FcmService receives it → shows high-priority notification
 *   3. User taps notification → MainActivity opens with room + caller info
 *   4. MainActivity connects WebSocket, joins room, WebRTC proceeds
 */
class FcmService : FirebaseMessagingService() {

    companion object {
        const val ACTION_INCOMING_CALL = "com.silence.app.ACTION_INCOMING_CALL"
        const val EXTRA_ROOM = "room"
        const val EXTRA_FROM_FP = "from_fingerprint"
        private var tokenCallback: ((String) -> Unit)? = null

        /** Register a callback to receive the FCM device token once it's available. */
        fun onToken(cb: (String) -> Unit) {
            tokenCallback = cb
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Deliver the token to whoever is waiting for it (SignalingClient)
        tokenCallback?.invoke(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val type = data["type"] ?: return

        if (type == "incoming_call") {
            val room = data["room"] ?: return
            val fromFp = data["from"] ?: ""

            showIncomingCallNotification(room, fromFp)
        }
    }

    private fun showIncomingCallNotification(room: String, callerFingerprint: String) {
        // Full-screen intent for incoming call (opens directly on lock screen)
        val callIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_INCOMING_CALL
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_FROM_FP, callerFingerprint)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, SilenceApplication.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle("Incoming Call")
            .setContentText("Call from $callerFingerprint")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(3001, notification)
    }
}
