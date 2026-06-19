package com.elodin.intercom.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class MediaShareService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val channelId = CHANNEL_ID
        val channelName = "Media Sharing"
        val channel =
            NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW,
            )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val nb = Notification.Builder(this, channelId)
        nb.setContentTitle("Intercom")
        nb.setContentText("Sharing audio")
        nb.setSmallIcon(android.R.drawable.ic_media_play)
        val notification = nb.build()

        if (!startProjectionForeground(notification)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpManager.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp

        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    MediaProjectionRelay.revoke()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper()),
        )

        Log.i(TAG, "MEDIA projection acquired")
        MediaProjectionRelay.publish(mp)
        return START_NOT_STICKY
    }

    // A stale/already-consumed projection grant (e.g. a redelivered intent after
    // a crash) makes the platform reject the mediaProjection FGS type. Stop
    // gracefully instead of crashing the app.
    private fun startProjectionForeground(notification: Notification): Boolean {
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "MEDIA FGS start rejected (stale projection grant?): ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA FGS start not allowed: ${e.message}")
        }
        return false
    }

    override fun onDestroy() {
        MediaProjectionRelay.clearPending()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val CHANNEL_ID = "media_share"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_RESULT_CODE = "media_result_code"
        const val EXTRA_RESULT_DATA = "media_result_data"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
        ) {
            val intent =
                Intent(context, MediaShareService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaShareService::class.java)
            context.stopService(intent)
        }
    }
}
