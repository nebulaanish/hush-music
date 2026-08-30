package io.github.nebulaanish.hush

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

const val MUSIC_URL = "https://music.youtube.com/"
const val VIDEO_URL = "https://m.youtube.com/"

private val BLOCKED_HOSTS = listOf(
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "google-analytics.com",
    "googletagservices.com",
    "googletagmanager.com",
    "adservice.google.com"
)

private val JS = """
(function () {
  if (window.__hushInit) return;
  window.__hushInit = true;

  // Keep the page "visible" so YouTube doesn't pause when we go to the background.
  Object.defineProperty(document, 'hidden', { get: function () { return false; } });
  Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; } });
  Object.defineProperty(document, 'webkitHidden', { get: function () { return false; } });
  document.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
  window.addEventListener('blur', function (e) { e.stopImmediatePropagation(); }, true);

  // Capture the handlers YouTube registers, so our notification buttons drive the
  // site's own next/previous logic instead of guessing at CSS selectors.
  var handlers = {};
  if (navigator.mediaSession && navigator.mediaSession.setActionHandler) {
    var original = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
    navigator.mediaSession.setActionHandler = function (action, fn) {
      handlers[action] = fn;
      original(action, fn);
    };
  }

  // Carousel arrows share the "next"/"previous" labels with the player, so prefer the
  // player bar, and otherwise take the LAST match: the player sits late in the DOM.
  function pick(scope, want) {
    var all = scope.querySelectorAll('button, tp-yt-paper-icon-button, [role="button"]');
    var found = null;
    for (var i = 0; i < all.length; i++) {
      var l = (all[i].getAttribute('aria-label') || '').trim().toLowerCase();
      if (l === want || l.indexOf(want + ' ') === 0) found = all[i];
    }
    return found;
  }

  window.__hush = function (action, arg) {
    var v = window.__hushVideo();
    if (action === 'seek') { if (v) v.currentTime = arg; return; }
    if (action === 'play' && v) { v.play(); return; }
    if (action === 'pause' && v) { v.pause(); return; }
    if (handlers[action]) { handlers[action](); return; }
    var want = action === 'nexttrack' ? 'next' : 'previous';
    var bar = document.querySelector('ytmusic-player-bar, #player-control-container, .player-controls');
    var hit = (bar && pick(bar, want)) || pick(document, want);
    if (hit) hit.click();
  };

  // The page can hold several <video> elements; the interesting one is whichever
  // is actually running, not necessarily the first in the DOM.
  window.__hushVideo = function () {
    var all = document.querySelectorAll('video');
    for (var i = 0; i < all.length; i++) if (!all[i].paused && !all[i].ended) return all[i];
    return all.length ? all[0] : null;
  };

  function text(sel) {
    var e = document.querySelector(sel);
    return e ? (e.textContent || '').trim() : '';
  }

  // Report player state to Android once a second; cheap and always in sync.
  setInterval(function () {
    var v = window.__hushVideo();
    var m = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
    var art = (m && m.artwork && m.artwork.length) ? m.artwork[m.artwork.length - 1].src : '';
    if (!art) {
      var img = document.querySelector('ytmusic-player-bar img, .ytp-cued-thumbnail-overlay-image');
      if (img) art = img.src || '';
    }
    Hush.state(
      !!(v && !v.paused && !v.ended),
      (m && m.title) || text('ytmusic-player-bar .title') || '',
      (m && m.artist) || text('ytmusic-player-bar .byline') || '',
      art,
      v ? Math.floor(v.currentTime * 1000) : 0,
      (v && isFinite(v.duration)) ? Math.floor(v.duration * 1000) : 0
    );
  }, 1000);

  // Ad skipping.
  setInterval(function () {
    var skip = document.querySelector(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-overlay-close-button');
    if (skip) skip.click();

    var adShowing = document.querySelector('.ad-showing, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout');
    var v = document.querySelector('video');
    if (adShowing && v && isFinite(v.duration) && v.duration > 0) {
      v.currentTime = v.duration;
      v.play();
    }

    ['#player-ads', '.ytp-ad-module', 'ytmusic-mealbar-promo-renderer',
     'ytd-promoted-sparkles-web-renderer', 'ad-slot-renderer', '.ytp-ad-overlay-slot']
      .forEach(function (sel) {
        document.querySelectorAll(sel).forEach(function (el) { el.remove(); });
      });
  }, 500);
})();
"""

/**
 * The players belong to the application, not to the Activity.
 *
 * Android destroys a backgrounded Activity whenever it wants the memory back. When the
 * WebViews lived in the Activity, that took the audio down with it — which is why
 * background playback failed intermittently, and why it failed more often on the YouTube
 * tab, where video decoding makes the Activity a fatter target.
 */
@SuppressLint("StaticFieldLeak")   // application context only; no Activity is retained here
object Players {

    private val main = Handler(Looper.getMainLooper())

    var musicView: WebView? = null
        private set
    var videoView: WebView? = null
        private set

    /** The Activity currently displaying the players, or null while there isn't one. */
    var host: MainActivity? = null

    /** Whichever WebView last reported audible playback — the one the controls drive. */
    private var playingIn: WebView? = null

    fun music(ctx: Context): WebView = musicView ?: create(ctx, MUSIC_URL).also { musicView = it }

    /** Created on first use: not loading YouTube until it is asked for halves idle memory. */
    fun video(ctx: Context): WebView = videoView ?: create(ctx, VIDEO_URL).also { videoView = it }

    /** Runs a media action against the playing page. Safe with no Activity alive. */
    fun command(action: String, arg: Int) {
        main.post {
            (playingIn ?: musicView)?.evaluateJavascript("window.__hush('$action', $arg)", null)
        }
    }

    /** Only on an explicit quit (swiping the task away). */
    fun releaseAll() {
        main.post {
            listOfNotNull(musicView, videoView).forEach {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.loadUrl("about:blank")
                it.destroy()
            }
            musicView = null
            videoView = null
            playingIn = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context, url: String): WebView {
        val w = BackgroundWebView(ctx.applicationContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Google refuses sign-in from a UA containing "wv" (WebView marker).
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(w), "Hush")

        // Without a WebChromeClient the page's fullscreen request goes nowhere,
        // which is why the fullscreen button appeared to do nothing.
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val activity = host
                if (activity == null) callback.onCustomViewHidden()
                else activity.enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                host?.exitFullscreen()
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val h = request.url.host ?: return null
                return if (BLOCKED_HOSTS.any { h == it || h.endsWith(".$it") }) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(JS, null)
                if (view === musicView) host?.onFirstPageLoaded()
            }
        }
        w.loadUrl(url)
        return w
    }

    /**
     * Chromium ties the renderer's page visibility to the window's. Once it believes the
     * page is hidden it suspends video decoding — audio-only pages survive that, video does
     * not, which is exactly why YouTube stopped in the background while YouTube Music kept
     * playing. Spoofing document.hidden in JS cannot reach this; it is below the page.
     *
     * ponytail: the renderer therefore never idles while the app is alive. That is the
     * feature, not an oversight — swipe the app away from Recents to actually stop it.
     */
    private class BackgroundWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(VISIBLE)
        }
    }

    /**
     * The only method exposed to page JS. It is write-only into our own notification,
     * so a hostile frame can at worst put wrong text on the lock screen.
     */
    private class Bridge(private val web: WebView) {
        @JavascriptInterface
        fun state(playing: Boolean, title: String, artist: String, art: String, pos: Int, dur: Int) {
            // Both tabs report every second. Without this guard the idle tab's "paused"
            // lands a second after the playing tab's "playing" and wipes out the session.
            // Starting a page takes over from a downloaded file; two at once is never wanted.
            if (playing && LocalPlayer.isActive) main.post { LocalPlayer.stop() }
            if (playing) playingIn = web else if (playingIn !== web) return
            PlaybackService.update(playing, title, artist, art, pos, dur)
        }
    }
}
