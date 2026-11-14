package com.rsmoviespot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VideoRenderService : Service() {

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Rendering video…"
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(message, progress)
                )
            }

            ACTION_COMPLETE -> {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Video render complete", 100)
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }

            ACTION_FAIL -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Video render failed"
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(message, 0)
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }

            else -> startAsForeground(
                buildNotification("Preparing video render…", 0)
            )
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
    }

    private fun buildNotification(message: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("RS Movie Spot")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video rendering",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress notifications for video generation"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.rsmoviespot.service.action.START"
        const val ACTION_PROGRESS = "com.rsmoviespot.service.action.PROGRESS"
        const val ACTION_COMPLETE = "com.rsmoviespot.service.action.COMPLETE"
        const val ACTION_FAIL = "com.rsmoviespot.service.action.FAIL"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_MESSAGE = "extra_message"

        private const val CHANNEL_ID = "video_render_channel"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateProgress(context: Context, progress: Int, message: String) {
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_PROGRESS)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_MESSAGE, message)
            context.startService(intent)
        }

        fun complete(context: Context) {
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_COMPLETE)
            context.startService(intent)
        }

        fun fail(context: Context, message: String) {
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_FAIL)
                .putExtra(EXTRA_MESSAGE, message)
            context.startService(intent)
        }
    }
}
