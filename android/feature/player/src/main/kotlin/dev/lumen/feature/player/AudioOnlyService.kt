package dev.lumen.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Service de premier plan sans logique : il garde le processus actif pendant que le lecteur
 * (détenu par le ViewModel) joue le son seul, écran en veille. Sans lui, Android fige l'app.
 */
class AudioOnlyService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.audio_only_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(intent?.getStringExtra(EXTRA_TITLE))
            .setContentText(getString(R.string.audio_only_running))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "audio_only"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            context.startForegroundService(Intent(context, AudioOnlyService::class.java).putExtra(EXTRA_TITLE, title))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioOnlyService::class.java))
        }
    }
}
