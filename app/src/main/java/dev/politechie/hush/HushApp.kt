package dev.politechie.hush

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class HushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpacks the bundled Python and yt-dlp on first run; cheap on later launches.
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("Hush", "yt-dlp init failed", e)
        }
    }
}
