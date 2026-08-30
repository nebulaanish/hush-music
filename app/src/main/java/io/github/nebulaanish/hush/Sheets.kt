package io.github.nebulaanish.hush

import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

object Sheets {

    /** The floating button's menu: the two tabs, plus the download entries. */
    fun showMenu(activity: MainActivity) {
        val dialog = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (18 * density).toInt())
        }

        fun row(label: String, selected: Boolean = false, onClick: () -> Unit) {
            root.addView(TextView(activity).apply {
                text = if (selected) "•  $label" else "    $label"
                textSize = 16f
                setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            })
        }

        val onMusic = activity.isMusicTab()
        row(activity.getString(R.string.tab_music), onMusic) { activity.showTab(true) }
        row(activity.getString(R.string.tab_video), !onMusic) { activity.showTab(false) }
        root.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt())
                .apply { topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt() }
            alpha = 0.15f
            setBackgroundColor(0xFF888888.toInt())
        })
        row(activity.getString(R.string.download_this)) { showDownload(activity) }
        row(activity.getString(R.string.downloads)) {
            activity.startActivity(Intent(activity, DownloadsActivity::class.java))
        }

        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Resolves what the current page actually offers before asking anything, so the choice
     * presented is the real one: a single track, a playlist, or both.
     */
    fun showDownload(activity: MainActivity) {
        val url = activity.currentUrl()
        if (url.isNullOrBlank()) {
            Toast.makeText(activity, R.string.nothing_to_download, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_download, null)
        dialog.setContentView(view)
        dialog.show()

        view.findViewById<TextView>(R.id.sheet_title).setText(R.string.resolving)
        Thread {
            val resolved = Resolver.resolve(url)
            activity.runOnUiThread {
                if (dialog.isShowing) bind(activity, dialog, view, resolved, activity.isMusicTab())
            }
        }.start()
    }

    private fun bind(
        activity: MainActivity,
        dialog: BottomSheetDialog,
        view: View,
        resolved: Resolved,
        defaultAudio: Boolean
    ) {
        val title = view.findViewById<TextView>(R.id.sheet_title)
        val confirm = view.findViewById<MaterialButton>(R.id.confirm)
        val scopeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.scope_group)
        val playlistButton = view.findViewById<MaterialButton>(R.id.scope_playlist)
        val kindGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.kind_group)
        val scroll = view.findViewById<View>(R.id.list_scroll)
        val container = view.findViewById<LinearLayout>(R.id.list_container)
        val selectAll = view.findViewById<MaterialButton>(R.id.select_all)
        view.findViewById<View>(R.id.sheet_spinner).visibility = View.GONE

        if (resolved.isEmpty) {
            title.setText(R.string.nothing_to_download)
            return
        }

        kindGroup.check(if (defaultAudio) R.id.kind_audio else R.id.kind_video)

        val checks = ArrayList<CheckBox>()
        resolved.playlist.forEach { track ->
            container.addView(CheckBox(activity).apply {
                text = track.title
                isChecked = true          // a playlist you opened is one you meant
                tag = track
                setOnCheckedChangeListener { _, _ -> updateConfirm(activity, confirm, checks, true) }
                checks += this
            })
        }

        fun applyScope(playlistMode: Boolean) {
            scroll.visibility = if (playlistMode) View.VISIBLE else View.GONE
            selectAll.visibility = if (playlistMode) View.VISIBLE else View.GONE
            title.text = if (playlistMode) {
                resolved.playlistTitle ?: activity.getString(R.string.downloads)
            } else {
                resolved.track?.title.orEmpty()
            }
            updateConfirm(activity, confirm, checks, playlistMode)
        }

        val startInPlaylistMode = resolved.track == null
        if (resolved.hasChoice) {
            scopeGroup.visibility = View.VISIBLE
            playlistButton.text = activity.getString(R.string.scope_playlist, resolved.playlist.size)
            // Default to the single track: a watch URL usually means "this one", and it is
            // the safe reading when the alternative is dozens of files.
            scopeGroup.check(R.id.scope_track)
            scopeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) applyScope(checkedId == R.id.scope_playlist)
            }
        }
        applyScope(startInPlaylistMode)

        selectAll.setOnClickListener {
            val turnOn = checks.any { !it.isChecked }
            checks.forEach { it.isChecked = turnOn }
            selectAll.setText(if (turnOn) R.string.select_none else R.string.select_all)
        }

        confirm.setOnClickListener {
            val audio = kindGroup.checkedButtonId == R.id.kind_audio
            val playlistMode = scroll.visibility == View.VISIBLE
            val items = if (playlistMode) {
                checks.filter { it.isChecked }.map { (it.tag as Track).let { t -> DownloadItem(t.id, t.title, audio) } }
            } else {
                resolved.track?.let { listOf(DownloadItem(it.id, it.title, audio)) }.orEmpty()
            }
            DownloadService.enqueue(activity, items)
            Toast.makeText(activity, activity.getString(R.string.queued, items.size), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun updateConfirm(
        activity: MainActivity,
        confirm: MaterialButton,
        checks: List<CheckBox>,
        playlistMode: Boolean
    ) {
        val n = if (playlistMode) checks.count { it.isChecked } else 1
        confirm.isEnabled = n > 0
        confirm.text = if (playlistMode) {
            activity.getString(R.string.download_n, n)
        } else {
            activity.getString(R.string.download_this)
        }
    }
}
