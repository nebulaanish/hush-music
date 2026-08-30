package io.github.nebulaanish.hush

import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * ponytail: VideoView plays the downloaded m4a as happily as the mp4 — audio just renders
 * as a black frame — so one screen covers both with the framework's own transport controls.
 */
class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra("uri")?.let(android.net.Uri::parse)
            ?: run { finish(); return }

        val video = VideoView(this)
        setContentView(video)
        MediaController(this).also {
            it.setAnchorView(video)
            video.setMediaController(it)
        }
        video.setVideoURI(uri)
        video.setOnPreparedListener { video.start() }
    }
}
