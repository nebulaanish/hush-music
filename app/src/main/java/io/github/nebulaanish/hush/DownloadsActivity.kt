package io.github.nebulaanish.hush

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/** Rows carry the cover art; a bare list of filenames reads as broken. */
private class ArtAdapter(
    private val ctx: android.content.Context,
    private val items: List<LibraryItem>
) : android.widget.BaseAdapter() {

    private val cache = HashMap<String, android.graphics.Bitmap?>()
    private val density = ctx.resources.displayMetrics.density

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            addView(android.widget.ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt())
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            })
            addView(TextView(ctx).apply {
                textSize = 16f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((12 * density).toInt(), 0, 0, 0)
            })
        }
        val image = row.getChildAt(0) as android.widget.ImageView
        val label = row.getChildAt(1) as TextView
        val item = items[position]
        label.text = item.title

        val key = item.uri.toString()
        image.tag = key
        // A blank square reads as broken, so anything without cover art gets the app glyph.
        fun show(bmp: android.graphics.Bitmap?) {
            if (bmp != null) image.setImageBitmap(bmp) else image.setImageResource(R.drawable.ic_music)
        }
        show(cache[key])
        if (!cache.containsKey(key)) {
            Thread {
                val bmp = Library.thumbnail(ctx, item.uri, (52 * density).toInt())
                image.post {
                    cache[key] = bmp
                    if (image.tag == key) show(bmp)
                }
            }.start()
        }
        return row
    }
}

/** The downloaded library: music on one side, videos on the other. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<LibraryItem> = emptyList()
    private var showingMusic = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val toggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(MaterialButton(this@DownloadsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.tab_music
                setText(R.string.tab_music)
            })
            addView(MaterialButton(this@DownloadsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.tab_video
                setText(R.string.downloads_videos)
            })
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    showingMusic = id == R.id.tab_music
                    refresh()
                }
            }
        }

        list = ListView(this)
        empty = TextView(this).apply {
            setText(R.string.no_downloads)
            setPadding(pad, pad, pad, pad)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad, pad, 0)
            addView(toggle)
            addView(empty)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        list.setOnItemClickListener { _, _, position, _ ->
            LocalPlayer.play(this, items, position)
            // Audio needs no screen: it plays into the notification like everything else.
            if (!showingMusic) startActivity(Intent(this, PlayerActivity::class.java))
        }
        toggle.check(R.id.tab_music)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Library.list(this, showingMusic)
        list.adapter = ArtAdapter(this, items)
        empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
