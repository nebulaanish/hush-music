package io.github.nebulaanish.hush

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

/**
 * A surface for whatever [LocalPlayer] is playing. It owns no player of its own, so
 * closing this screen leaves the audio running.
 */
class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = SurfaceView(this)
        setContentView(view)
        view.holder.addCallback(this)
        view.setOnClickListener { LocalPlayer.toggle() }
        title = LocalPlayer.current?.title.orEmpty()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        LocalPlayer.surface = holder
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Detach only. The player keeps going, which is the whole point.
        LocalPlayer.surface = null
    }
}
