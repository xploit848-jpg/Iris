package com.aetherai.iris.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class IrisForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "iris_foreground"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "IRIS", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        builder.setContentTitle("IRIS")
        builder.setContentText("Listening")
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
