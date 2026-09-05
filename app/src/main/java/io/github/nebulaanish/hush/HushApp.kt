package io.github.nebulaanish.hush

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class HushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("Hush", "app onCreate")
        // Unpacks the bundled Python and yt-dlp on first run; cheap on later launches.
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("Hush", "yt-dlp init failed", e)
            return
        }
        Analytics.init(this)
        Log.d("Hush", "init ok")
        updateExtractorDaily()
    }

    /**
     * YouTube breaks extraction often enough that a stale yt-dlp simply cannot download.
     * Checking once a day in the background is what keeps this working without new builds.
     */
    private fun updateExtractorDaily() {
        val prefs = getSharedPreferences("hush", MODE_PRIVATE)
        val last = prefs.getLong("ytdlpUpdated", 0L)
        Log.d("Hush", "update check, last=$last")
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        Downloads.updateExtractor(this) { ok ->
            if (ok) prefs.edit().putLong("ytdlpUpdated", System.currentTimeMillis()).apply()
        }
    }
}
