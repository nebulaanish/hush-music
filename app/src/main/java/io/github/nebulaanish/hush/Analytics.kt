package io.github.nebulaanish.hush

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

// ponytail: thin wrapper so call sites stay one-liners.
object Analytics {

    private lateinit var fa: FirebaseAnalytics

    fun init(ctx: Context) {
        fa = FirebaseAnalytics.getInstance(ctx)
    }

    fun playbackSource(source: String) {
        fa.logEvent("playback_source", Bundle().apply {
            putString("source", source) // "music_tab", "video_tab", "local_file"
        })
    }

    fun downloadStarted(audioOnly: Boolean) {
        fa.logEvent("download_started", Bundle().apply {
            putString("kind", if (audioOnly) "audio" else "video")
        })
    }

    fun downloadCompleted(audioOnly: Boolean) {
        fa.logEvent("download_completed", Bundle().apply {
            putString("kind", if (audioOnly) "audio" else "video")
        })
    }

    fun downloadFailed(audioOnly: Boolean) {
        fa.logEvent("download_failed", Bundle().apply {
            putString("kind", if (audioOnly) "audio" else "video")
        })
    }
}
