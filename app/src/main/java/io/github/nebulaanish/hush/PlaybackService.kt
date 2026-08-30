package io.github.nebulaanish.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.URL

private const val CHANNEL = "playback"
private const val NOTIF_ID = 1

/**
 * One MediaSession for everything that plays: the WebView pages and the downloaded files
 * both feed it. That is what makes downloads background and appear on the lock screen the
 * same way streaming does, rather than being a second-class player.
 */
class PlaybackService : Service() {

    companion object {
        private var instance: PlaybackService? = null

        /** Reported by the web players, whose artwork is a URL. */
        fun update(playing: Boolean, title: String, artist: String, art: String, pos: Int, dur: Int) {
            instance?.publishWeb(playing, title, artist, art, pos, dur)
        }

        /** Reported by [LocalPlayer], whose artwork is already decoded from the file. */
        fun updateLocal(playing: Boolean, title: String, artist: String, art: Bitmap?, pos: Int, dur: Int) {
            instance?.publishLocal(playing, title, artist, art, pos, dur)
        }
    }

    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())

    private var playing = false
    private var title = ""
    private var artist = ""
    private var pos = 0
    private var dur = 0
    private var artKey = ""
    private var artBmp: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        session = MediaSession(this, "hush").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = dispatch("play", 0)
                override fun onPause() = dispatch("pause", 0)
                override fun onSkipToNext() = dispatch("nexttrack", 0)
                override fun onSkipToPrevious() = dispatch("previoustrack", 0)
                override fun onStop() = dispatch("pause", 0)
                override fun onSeekTo(p: Long) = dispatch("seek", (p / 1000).toInt())
            })
            isActive = true
        }
    }

    /** Whichever source is actually playing gets the button press. */
    private fun dispatch(action: String, arg: Int) {
        if (LocalPlayer.isActive) LocalPlayer.command(action, arg) else Players.command(action, arg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.takeIf { it != "start" }?.let { dispatch(it, 0) }
        startForeground(NOTIF_ID, build())
        return START_STICKY
    }

    private fun publishWeb(playing: Boolean, title: String, artist: String, art: String, pos: Int, dur: Int) {
        if (LocalPlayer.isActive) return    // a downloaded file is playing; ignore the pages
        fetchArt(art)
        render(playing, title, artist, pos, dur)
    }

    private fun publishLocal(playing: Boolean, title: String, artist: String, art: Bitmap?, pos: Int, dur: Int) {
        if (artKey != "local:$title") {
            artKey = "local:$title"
            artBmp = art
        }
        render(playing, title, artist, pos, dur)
    }

    private fun render(playing: Boolean, title: String, artist: String, pos: Int, dur: Int) {
        val changed = playing != this.playing || title != this.title || dur != this.dur
        this.playing = playing
        this.title = title
        this.artist = artist
        this.pos = pos
        this.dur = dur

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos.toLong(), 1f
                )
                .build()
        )
        if (changed) setMetadata()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
    }

    private fun setMetadata() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur.toLong())
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBmp)
                .build()
        )
    }

    /** Album art for the lock screen. One in-flight fetch, keyed on the URL we last saw. */
    private fun fetchArt(url: String) {
        if (url == artKey) return
        artKey = url
        artBmp = null
        if (!url.startsWith("https://")) return
        Thread {
            val bmp = runCatching { URL(url).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
            main.post {
                if (url == artKey && bmp != null) {
                    artBmp = bmp
                    setMetadata()
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                }
            }
        }.start()
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(): Notification = Notification.Builder(this, CHANNEL)
        .setStyle(
            Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        .setSmallIcon(R.drawable.ic_music)
        .setContentTitle(title.ifEmpty { getString(R.string.app_name) })
        .setContentText(artist.ifEmpty { getString(R.string.playing_background) })
        .setLargeIcon(artBmp)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(playing)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(action(android.R.drawable.ic_media_previous, "Previous", "previoustrack"))
        .addAction(
            if (playing) action(android.R.drawable.ic_media_pause, "Pause", "pause")
            else action(android.R.drawable.ic_media_play, "Play", "play")
        )
        .addAction(action(android.R.drawable.ic_media_next, "Next", "nexttrack"))
        .build()

    /** Swiping the app out of Recents is the quit gesture: stop everything. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LocalPlayer.stop()
        Players.releaseAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        instance = null
        super.onDestroy()
    }
}
