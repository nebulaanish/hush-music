package io.github.nebulaanish.hush

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat

/**
 * Plays downloaded files. Application-scoped for the same reason the WebViews are: the
 * screen showing it can be destroyed at any time and the audio has to keep going.
 *
 * Video runs through the same MediaPlayer; detaching the surface when the screen closes
 * leaves the audio playing, which is what makes a downloaded video keep going in the
 * background rather than stopping with its window.
 */
object LocalPlayer {

    private const val TAG = "HushDL"

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var app: Context? = null
    private var queue: List<LibraryItem> = emptyList()
    private var index = 0
    private var art: Bitmap? = null
    private var tagTitle: String? = null
    private var tagArtist: String? = null

    var isActive = false
        private set

    /** Set by [PlayerActivity] while it is on screen. */
    var surface: SurfaceHolder? = null
        set(value) {
            field = value
            mp?.setDisplay(value)
        }

    val current: LibraryItem? get() = queue.getOrNull(index)

    fun play(ctx: Context, items: List<LibraryItem>, startIndex: Int) {
        if (items.isEmpty()) return
        app = ctx.applicationContext
        queue = items
        index = startIndex.coerceIn(items.indices)
        Analytics.playbackSource("local_file")
        Players.command("pause", 0)
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, PlaybackService::class.java).setAction("start")
        )
        open()
    }

    private fun open() {
        val ctx = app ?: return
        val item = current ?: return
        release()
        isActive = true
        art = readArt(ctx, item)
        readTags(ctx, item)
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            runCatching { setDataSource(ctx, item.uri) }
                .onFailure { Log.e(TAG, "cannot open ${item.uri}", it); return }
            setOnPreparedListener {
                setDisplay(surface)
                start()
                report()
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "player error $what/$extra")
                true
            }
            prepareAsync()
        }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            report()
            if (isActive) main.postDelayed(this, 1000)
        }
    }

    private fun report() {
        val player = mp ?: return
        val item = current ?: return
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val dur = runCatching { player.duration }.getOrDefault(0)
        PlaybackService.updateLocal(
            playing,
            tagTitle ?: item.title,
            tagArtist.orEmpty(),
            art,
            pos,
            dur.coerceAtLeast(0)
        )
    }

    fun command(action: String, arg: Int) {
        when (action) {
            "play" -> mp?.start()
            "pause" -> mp?.pause()
            "nexttrack" -> next()
            "previoustrack" -> prev()
            "seek" -> mp?.seekTo(arg * 1000)
        }
        report()
    }

    fun toggle() {
        val player = mp ?: return
        if (player.isPlaying) player.pause() else player.start()
        report()
    }

    fun next() {
        if (index + 1 >= queue.size) { stop(); return }
        index++
        open()
    }

    fun prev() {
        if (index == 0) { mp?.seekTo(0); return }
        index--
        open()
    }

    fun stop() {
        main.removeCallbacks(ticker)
        release()
        isActive = false
        report()
    }

    private fun release() {
        runCatching { mp?.release() }
        mp = null
    }

    val isPlaying: Boolean get() = runCatching { mp?.isPlaying == true }.getOrDefault(false)

    val nowPlayingTitle: String? get() = if (isActive) (tagTitle ?: current?.title) else null

    val nowPlayingArt: Bitmap? get() = if (isActive) art else null

    private fun readTags(ctx: Context, item: LibraryItem) {
        tagTitle = null
        tagArtist = null
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(ctx, item.uri)
                tagTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                tagArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun readArt(ctx: Context, item: LibraryItem): Bitmap? =
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(ctx, item.uri)
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull() ?: Library.thumbnail(ctx, item.uri, 512)
}
