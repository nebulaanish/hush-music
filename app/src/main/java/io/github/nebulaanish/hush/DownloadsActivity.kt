package io.github.nebulaanish.hush

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

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
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("uri", items[position].uri.toString())
            )
        }
        toggle.check(R.id.tab_music)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Library.list(this, showingMusic)
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            items.map { it.title }
        )
        empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
