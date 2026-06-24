package com.silence.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SilenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val callChannel = NotificationChannel(
            CHANNEL_CALL,
            getString(R.string.channel_call_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_call_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(callChannel)
    }

    companion object {
        const val CHANNEL_CALL = "call_service"
    }
}
