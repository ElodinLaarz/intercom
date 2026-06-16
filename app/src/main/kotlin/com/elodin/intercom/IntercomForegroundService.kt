package com.elodin.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class IntercomForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification(), FOREGROUND_TYPES)
        Log.i(TAG, "FOREGROUND active")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Intercom link",
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = "Keeps the intercom audio link active"
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val intent =
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_intercom_notification)
            .setContentTitle("Intercom active")
            .setContentText("Voice link is running")
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val CHANNEL_ID = "intercom_link"
        private const val NOTIFICATION_ID = 37
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, IntercomForegroundService::class.java),
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "FOREGROUND start failed: ${e.message}")
            } catch (e: SecurityException) {
                Log.e(TAG, "FOREGROUND start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, IntercomForegroundService::class.java),
            )
        }
    }
}
