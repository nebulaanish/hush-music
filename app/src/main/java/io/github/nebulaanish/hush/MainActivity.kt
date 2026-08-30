package io.github.nebulaanish.hush

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.abs

/**
 * Displays the players; it does not own them. Everything that must survive this Activity
 * being destroyed in the background lives in [Players].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var current: WebView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val bars by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.container)
        fab = findViewById(R.id.fab)
        Players.host = this

        current = Players.music(this)
        attach(current)
        // The YouTube tab may already exist from a previous Activity; keep showing what plays.
        Players.videoView?.let { attach(it); it.visibility = View.GONE }
        if (Players.musicView?.url != null) hideSplash()

        // The button does not decide for you: it reopens the same two-option switcher.
        fab.setOnClickListener { Sheets.showMenu(this) }
        fab.setImageResource(R.drawable.ic_menu)
        fab.contentDescription = getString(R.string.switch_tab)
        makeDraggable(fab)
        restoreFabPosition()

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> exitFullscreen()
                current.canGoBack() -> current.goBack()
                else -> moveTaskToBack(true)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        ContextCompat.startForegroundService(
            this, Intent(this, PlaybackService::class.java).setAction("start")
        )
        askBatteryExemption()
        handleTestDownload(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestDownload(intent)
    }

    // TEMPORARY test hook, to be removed once the Downloads UI exists:
    //   adb shell am start -n io.github.nebulaanish.hush/.MainActivity \
    //     -e hush_dl "<url>" -e hush_audio true
    private fun handleTestDownload(intent: Intent?) {
        val url = intent?.getStringExtra("hush_dl") ?: return
        val audio = intent.getStringExtra("hush_audio") == "true"
        Downloads.start(this, url, audio)
    }

    private fun attach(w: WebView) {
        (w.parent as? ViewGroup)?.removeView(w)
        container.addView(
            w, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun currentUrl(): String? = current.url

    fun isMusicTab(): Boolean = current === Players.musicView

    fun showTab(music: Boolean) {
        switchTo(if (music) Players.music(this) else Players.video(this))
    }

    private fun switchTo(target: WebView) {
        if (target.parent == null) attach(target)
        current = target
        Players.musicView?.visibility = if (target === Players.musicView) View.VISIBLE else View.GONE
        Players.videoView?.visibility = if (target === Players.videoView) View.VISIBLE else View.GONE
    }

    /** Called from [Players] when the music page finishes its first load. */
    fun onFirstPageLoaded() = hideSplash()

    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        // Added to the decor view, not our root, so it ignores the system bar insets.
        (window.decorView as ViewGroup).addView(
            view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fab.hide()
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ponytail: video is landscape often enough that this is the right default;
        // drop it if portrait clips start feeling letterboxed.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun exitFullscreen() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        bars.show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fab.show()
    }

    private fun hideSplash() {
        val splash = findViewById<View>(R.id.splash)
        if (splash == null || splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(250)
            .withEndAction { splash.visibility = View.GONE }
    }

    /** Doze stops background audio on a phone that hasn't exempted the app. Ask once. */
    private fun askBatteryExemption() {
        val prefs = getSharedPreferences("hush", MODE_PRIVATE)
        if (prefs.getBoolean("askedBattery", false)) return
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** Applied once the button is actually laid out, otherwise there is no left/top to offset from. */
    private fun restoreFabPosition() {
        val prefs = getSharedPreferences("hush", MODE_PRIVATE)
        if (!prefs.contains("fabX")) return
        fab.post {
            val parent = fab.parent as? View ?: return@post
            if (parent.width == 0 || fab.width == 0) return@post
            fab.x = prefs.getFloat("fabX", fab.x).coerceIn(0f, (parent.width - fab.width).toFloat())
            fab.y = prefs.getFloat("fabY", fab.y).coerceIn(0f, (parent.height - fab.height).toFloat())
        }
    }

    /**
     * Drag to move it out of the way of whatever it happens to be covering; the position
     * survives restarts. A press that never exceeds touch slop is still a tap.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(v: View) {
        val prefs = getSharedPreferences("hush", MODE_PRIVATE)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dX = 0f
        var dY = 0f
        var downX = 0f
        var downY = 0f
        var dragging = false

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - e.rawX
                    dY = view.y - e.rawY
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)
                    ) dragging = true
                    if (dragging) {
                        val parent = view.parent as View
                        view.x = (e.rawX + dX).coerceIn(0f, (parent.width - view.width).toFloat())
                        view.y = (e.rawY + dY).coerceIn(0f, (parent.height - view.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putFloat("fabX", view.x).putFloat("fabY", view.y).apply()
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        android.webkit.CookieManager.getInstance().flush()  // persist the Google login
    }

    override fun onDestroy() {
        // Detach, never destroy: the system may be reclaiming this Activity while audio
        // is still playing, and the players have to outlive it. The service stays up too.
        if (customView != null) exitFullscreen()
        container.removeAllViews()
        if (Players.host === this) Players.host = null
        super.onDestroy()
    }

    // ponytail: deliberately NOT calling webView.onPause()/pauseTimers() in onPause —
    // that is exactly what keeps audio running once the app is backgrounded.
}
