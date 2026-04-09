package ru.sosiskibot.luckystar.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import ru.sosiskibot.luckystar.R

class DownloadNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Library downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
    }

    fun asForegroundInfo(title: String, text: String, progress: Int): ForegroundInfo {
        val notification = build(title, text, progress, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    fun notifyCompleted() {
        nm.notify(
            NOTIFICATION_ID,
            build("Библиотека", "Офлайн-библиотека готова", 100, false),
        )
    }

    fun notifyFailed(message: String) {
        nm.notify(
            NOTIFICATION_ID,
            build("Библиотека", "Ошибка загрузки: $message", 0, false),
        )
    }

    private fun build(title: String, text: String, progress: Int, ongoing: Boolean): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "offline_download"
        private const val NOTIFICATION_ID = 9001
    }
}
