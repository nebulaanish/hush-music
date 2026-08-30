package dev.politechie.hush

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

private const val TAG = "HushDL"

/**
 * Downloads through the bundled yt-dlp. Nothing here touches the WebView: the only input
 * is a watch URL, so YouTube redesigning their pages cannot break downloading.
 */
object Downloads {

    private val main = Handler(Looper.getMainLooper())

    fun musicDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "Music").apply { mkdirs() }

    fun videoDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "Videos").apply { mkdirs() }

    /**
     * @param audioOnly true for the Music tab: keeps the m4a stream as-is rather than
     *   transcoding to mp3, which would cost CPU to produce a worse file.
     */
    fun start(ctx: Context, url: String, audioOnly: Boolean, onDone: (File?) -> Unit = {}) {
        val app = ctx.applicationContext
        val dir = if (audioOnly) musicDir(app) else videoDir(app)

        Log.d(TAG, "download requested: $url audioOnly=$audioOnly")
        Thread {
            Log.d(TAG, "download thread entered")
            val request = YoutubeDLRequest(url).apply {
                addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
                addOption("--no-playlist")
                addOption("--no-mtime")
                if (audioOnly) {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                } else {
                    // ponytail: capped at 1080p. 4K on a phone costs gigabytes for
                    // detail the screen cannot show. Raise it if that ever matters.
                    addOption(
                        "-f",
                        "bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[height<=1080]/best"
                    )
                    addOption("--merge-output-format", "mp4")
                }
            }
            val result = runCatching {
                YoutubeDL.getInstance().execute(request) { progress, eta, line ->
                    Log.d(TAG, "progress=$progress eta=$eta  $line")
                }
            }
            // Newest file wins rather than diffing the directory: yt-dlp reports
            // "has already been downloaded" and adds nothing when the file is present,
            // which a diff reads as failure. --no-mtime keeps mtime as download time.
            val file = dir.listFiles()
                ?.filter { it.isFile }
                ?.maxByOrNull { it.lastModified() }
                ?.takeIf { result.isSuccess }

            result.onFailure { Log.e(TAG, "download failed: $url", it) }
            result.onSuccess { Log.d(TAG, "download finished -> $file (exit ${it.exitCode})") }

            main.post {
                Toast.makeText(
                    app,
                    if (file != null) "Saved ${file.name}" else "Download failed",
                    Toast.LENGTH_LONG
                ).show()
                onDone(file)
            }
        }.start()
    }

    /**
     * Pulls a newer yt-dlp without shipping an APK — the whole reason for this dependency.
     * The version bundled in the library is months old on arrival and YouTube breaks it
     * regularly, so this is not optional maintenance, it is how downloads keep working.
     */
    fun updateExtractor(ctx: Context, onDone: (Boolean) -> Unit = {}) {
        Log.d(TAG, "extractor update requested")
        Thread {
            Log.d(TAG, "update thread entered")
            val ok = runCatching {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            }.onSuccess { Log.d(TAG, "yt-dlp update: $it") }
                .onFailure { Log.e(TAG, "yt-dlp update failed", it) }
                .isSuccess
            main.post { onDone(ok) }
        }.start()
    }
}
