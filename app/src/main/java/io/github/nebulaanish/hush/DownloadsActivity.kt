package io.github.nebulaanish.hush

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/** The downloaded library: music on one side, videos on the other. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var banner: TextView
    private lateinit var nowPlaying: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArt: ImageView
    private lateinit var nowToggle: MaterialButton
    private lateinit var deleteBar: MaterialButton

    private val adapter = Adapter()
    private val main = Handler(Looper.getMainLooper())
    private var showingMusic = true
    private var items: List<LibraryItem> = emptyList()
    private val selected = LinkedHashSet<Int>()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    clearSelection()
                    refresh()
                }
            }
        }

        banner = TextView(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            setBackgroundColor(0x22FFFFFF)
        }
        deleteBar = MaterialButton(this).apply {
            visibility = View.GONE
            setOnClickListener { confirmDelete(selected.mapNotNull { items.getOrNull(it) }) }
        }
        empty = TextView(this).apply {
            setText(R.string.no_downloads)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
        }
        buildNowPlaying()

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(16), dp(16), dp(16), 0)
            addView(toggle)
            addView(deleteBar)
            addView(banner)
            addView(empty)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nowPlaying)
        })

        attachSwipeToDelete()
        toggle.check(R.id.tab_music)
    }

    private fun buildNowPlaying() {
        nowArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        nowTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowToggle = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            addView(nowArt)
            addView(nowTitle)
            addView(nowToggle)
            setOnClickListener {
                if (!showingMusic) startActivity(Intent(this@DownloadsActivity, PlayerActivity::class.java))
            }
        }
        nowToggle.setOnClickListener {
            LocalPlayer.toggle()
            syncBars()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
    }

    /** One timer drives both the download banner and the now-playing bar. */
    private val poll = object : Runnable {
        override fun run() {
            syncBars()
            main.postDelayed(this, 1000)
        }
    }

    private fun syncBars() {
        val dlTitle = DownloadService.activeTitle
        if (dlTitle == null) {
            if (banner.visibility == View.VISIBLE) {
                banner.visibility = View.GONE
                refresh()          // a finished download belongs in the list
            }
        } else {
            val counter = if (DownloadService.activeTotal > 1) {
                " (${DownloadService.activeIndex}/${DownloadService.activeTotal})"
            } else ""
            banner.text = getString(R.string.downloading) + counter +
                "  ${DownloadService.activePercent}%  ·  $dlTitle"
            banner.visibility = View.VISIBLE
        }

        val title = LocalPlayer.nowPlayingTitle
        if (title == null) {
            nowPlaying.visibility = View.GONE
        } else {
            nowPlaying.visibility = View.VISIBLE
            nowTitle.text = title
            val art = LocalPlayer.nowPlayingArt
            if (art != null) nowArt.setImageBitmap(art) else nowArt.setImageResource(R.drawable.ic_music)
            nowToggle.setText(if (LocalPlayer.isPlaying) R.string.pause else R.string.play)
        }
    }

    private fun refresh() {
        items = Library.list(this, showingMusic)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun clearSelection() {
        selected.clear()
        deleteBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(position: Int) {
        if (!selected.remove(position)) selected.add(position)
        deleteBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        deleteBar.text = getString(R.string.delete_n, selected.size)
        adapter.notifyItemChanged(position)
    }

    private fun confirmDelete(targets: List<LibraryItem>) {
        if (targets.isEmpty()) return
        val message = if (targets.size == 1) {
            getString(R.string.delete_one, targets[0].title)
        } else {
            getString(R.string.delete_many, targets.size)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .setPositiveButton(R.string.delete) { _, _ ->
                targets.forEach { item ->
                    android.util.Log.d("HushDL", "trashing ${item.title} -> ${item.uri}")
                    runCatching {
                        // Trashed, not destroyed: recoverable from the system's trash for
                        // 30 days. A delete bug must not be able to lose a file for good.
                        contentResolver.update(
                            item.uri,
                            android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 1)
                            },
                            null, null
                        )
                    }.onFailure { android.util.Log.e("HushDL", "trash failed for ${item.title}", it) }
                }
                clearSelection()
                refresh()
            }
            .show()
    }

    /** Swipe a row aside to delete it; nothing goes away without confirming. */
    private fun attachSwipeToDelete() {
        val background = ColorDrawable(Color.parseColor("#B3312121"))
        val icon = getDrawable(android.R.drawable.ic_menu_delete)
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val item = items.getOrNull(holder.bindingAdapterPosition) ?: return
                confirmDelete(listOf(item))
            }

            override fun onChildDraw(
                c: Canvas, r: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val v = holder.itemView
                background.setBounds(v.left, v.top, v.right, v.bottom)
                background.draw(c)
                icon?.let {
                    val size = dp(24)
                    val top = v.top + (v.height - size) / 2
                    val right = v.right - dp(20)
                    it.setBounds(right - size, top, right, top + size)
                    it.draw(c)
                }
                super.onChildDraw(c, r, holder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        private val cache = HashMap<String, android.graphics.Bitmap?>()

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val image = row.getChildAt(0) as ImageView
            val label = row.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@DownloadsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(ImageView(this@DownloadsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                })
                addView(TextView(this@DownloadsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 16f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, 0, 0)
                })
            }
            return Holder(row)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.label.text = item.title
            holder.row.setBackgroundColor(if (position in selected) 0x33FFFFFF else Color.TRANSPARENT)

            val key = item.uri.toString()
            holder.image.tag = key
            fun show(bmp: android.graphics.Bitmap?) {
                if (bmp != null) holder.image.setImageBitmap(bmp)
                else holder.image.setImageResource(R.drawable.ic_music)
            }
            show(cache[key])
            if (!cache.containsKey(key)) {
                Thread {
                    val bmp = Library.thumbnail(this@DownloadsActivity, item.uri, dp(52))
                    holder.image.post {
                        cache[key] = bmp
                        if (holder.image.tag == key) show(bmp)
                    }
                }.start()
            }

            holder.row.setOnClickListener {
                if (selected.isNotEmpty()) {
                    toggleSelection(position)
                } else {
                    LocalPlayer.play(this@DownloadsActivity, items, position)
                    syncBars()
                    if (!showingMusic) {
                        startActivity(Intent(this@DownloadsActivity, PlayerActivity::class.java))
                    }
                }
            }
            holder.row.setOnLongClickListener {
                toggleSelection(position)
                true
            }
        }
    }
}
