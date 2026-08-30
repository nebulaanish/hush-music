package io.github.nebulaanish.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue

private const val CHANNEL = "downloads"
private const val NOTIF_ID = 2

data class DownloadItem(val id: String, val title: String, val audioOnly: Boolean)

/**
 * Runs the download queue one item at a time in a foreground service, so a long batch
 * survives leaving the app and one failure does not abandon the rest.
 */
class DownloadService : Service() {

    companion object {
        private val queue = ConcurrentLinkedQueue<DownloadItem>()

        fun enqueue(ctx: Context, items: List<DownloadItem>) {
            if (items.isEmpty()) return
            queue.addAll(items)
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        val pending: Int get() = queue.size
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var done = 0
    @Volatile private var total = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        total = maxOf(total, done + queue.size)
        startForeground(NOTIF_ID, build("Starting…", 0))
        if (worker == null) {
            worker = Thread { drain() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun drain() {
        while (true) {
            val item = queue.poll() ?: break
            notify(item.title, 0)
            Downloads.downloadNow(this, Resolver.watchUrl(item.id), item.audioOnly) { p ->
                notify(item.title, p.toInt())
            }
            done++
        }
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(title: String, percent: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, percent))
    }

    private fun build(title: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val counter = if (total > 1) " (${done + 1}/$total)" else ""
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.downloading) + counter)
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .build()
    }
}
