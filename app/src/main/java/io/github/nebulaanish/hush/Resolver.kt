package io.github.nebulaanish.hush

import android.net.Uri
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

data class Track(val id: String, val title: String, val seconds: Int)

/**
 * What a page offers to download. Either may be present: a watch URL sitting inside a
 * real playlist gives both, and the user picks.
 */
data class Resolved(
    val track: Track?,
    val playlistTitle: String?,
    val playlist: List<Track>
) {
    val hasChoice get() = track != null && playlist.isNotEmpty()
    val isEmpty get() = track == null && playlist.isEmpty()
}

object Resolver {

    private const val TAG = "HushDL"

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /**
     * Enumerates without touching any media — `--flat-playlist` returns ids and titles in
     * about a second. Blocking; call it off the main thread.
     */
    fun resolve(url: String): Resolved {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Resolved(null, null, emptyList())
        val videoId = uri.getQueryParameter("v")
        val listId = uri.getQueryParameter("list")

        // RD… is YouTube's endless autoplay radio, not a playlist anyone chose. Treating it
        // as one would queue an effectively infinite download.
        val realPlaylist = listId != null && !listId.startsWith("RD")

        var entries = emptyList<Track>()
        var playlistTitle: String? = null
        if (realPlaylist) {
            val json = dumpJson("https://www.youtube.com/playlist?list=$listId", flat = true)
            if (json != null) {
                playlistTitle = json.optString("title").takeIf { it.isNotBlank() }
                entries = readEntries(json)
            }
        }

        val track = when {
            videoId == null -> null
            else -> entries.firstOrNull { it.id == videoId }
                ?: dumpJson(watchUrl(videoId), flat = false)?.let {
                    Track(videoId, it.optString("title", videoId), it.optInt("duration", 0))
                }
        }
        return Resolved(track, playlistTitle, entries)
    }

    private fun readEntries(json: JSONObject): List<Track> {
        val arr = json.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<Track>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("id").takeIf { it.isNotBlank() } ?: continue
            out += Track(id, e.optString("title", id), e.optInt("duration", 0))
        }
        return out
    }

    private fun dumpJson(url: String, flat: Boolean): JSONObject? = runCatching {
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-warnings")
            if (flat) addOption("--flat-playlist") else addOption("--no-playlist")
        }
        JSONObject(YoutubeDL.getInstance().execute(request).out)
    }.onFailure { Log.e(TAG, "resolve failed: $url", it) }.getOrNull()
}
