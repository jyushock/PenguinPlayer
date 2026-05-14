package com.penguin.player

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

data class TrackItem(
    val name: String,
    val info: String,
    var isPlaying: Boolean = false
)

enum class PlaybackMode { NORMAL, REPEAT_ONE, REPEAT_ALL, SHUFFLE }

class MainActivity : AppCompatActivity() {

    private var isPlaying = false
    private var playbackMode = PlaybackMode.NORMAL
    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<TrackItem>()

    private val fileListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("UNCHECKED_CAST")
            val files = result.data?.getSerializableExtra("files") as? ArrayList<AudioFile>
            files?.forEach { audioFile ->
                tracks.add(TrackItem(audioFile.trackName, "${audioFile.format}  •  0:00"))
            }
            adapter.notifyDataSetChanged()
            savePlaylist()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        loadPlaylist()
        loadPlaybackMode()
        checkFirstLaunch()

        findViewById<View>(R.id.btnSelectStorage).setOnClickListener {
            fileListLauncher.launch(Intent(this, FileListActivity::class.java))
        }

        val fabPlayPause = findViewById<FloatingActionButton>(R.id.fabPlayPause)
        fabPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            fabPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        listOf(R.id.btnSkipPrev, R.id.btnRewind, R.id.btnFastForward, R.id.btnSkipNext).forEach { id ->
            findViewById<ImageButton>(id).setOnClickListener { }
        }

        setupPlaybackModeButtons()

        adapter = TrackAdapter(tracks)
        val rvPlaylist = findViewById<RecyclerView>(R.id.rvPlaylist)
        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = adapter
        setupPlaylistTouchHelper(rvPlaylist)
    }

    override fun onPause() {
        super.onPause()
        savePlaylist()
        savePlaybackMode()
    }

    // --- First launch ---

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("first_launch_done", false)) {
            showInitialFolderDialog()
        }
    }

    private fun showInitialFolderDialog() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val editText = EditText(this).apply {
            hint = "/sdcard/Music"
            setPadding(64, 32, 64, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.initial_folder_title))
            .setMessage(getString(R.string.initial_folder_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.confirm_ok)) { _, _ ->
                val path = editText.text.toString().trim()
                prefs.edit()
                    .putString("initial_folder", path.ifEmpty { "/sdcard/Music" })
                    .putBoolean("first_launch_done", true)
                    .apply()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                prefs.edit().putBoolean("first_launch_done", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    // --- Playlist persistence ---

    private fun loadPlaylist() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val count = prefs.getInt("playlist_count", -1)
        if (count < 0) {
            tracks.addAll(listOf(
                TrackItem("Beethoven - Symphony No.9",       "FLAC  •  9:58",  isPlaying = true),
                TrackItem("Mozart - Piano Concerto No.21",   "FLAC  •  24:13"),
                TrackItem("Bach - Cello Suite No.1",         "WAV  •  11:45"),
                TrackItem("Chopin - Nocturne Op.9 No.2",     "MP3  •  4:32"),
                TrackItem("Debussy - Clair de Lune",         "FLAC  •  5:01"),
                TrackItem("Bach - Goldberg Variations",      "ALAC  •  56:20"),
                TrackItem("Beethoven - Moonlight Sonata",    "MP3  •  17:44"),
            ))
        } else {
            for (i in 0 until count) {
                val name = prefs.getString("track_name_$i", null) ?: continue
                val info = prefs.getString("track_info_$i", "") ?: ""
                val playing = prefs.getBoolean("track_playing_$i", false)
                tracks.add(TrackItem(name, info, playing))
            }
        }
    }

    private fun savePlaylist() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        edit.putInt("playlist_count", tracks.size)
        tracks.forEachIndexed { i, track ->
            edit.putString("track_name_$i", track.name)
            edit.putString("track_info_$i", track.info)
            edit.putBoolean("track_playing_$i", track.isPlaying)
        }
        edit.apply()
    }

    // --- Playback mode ---

    private fun loadPlaybackMode() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        playbackMode = PlaybackMode.values()
            .getOrElse(prefs.getInt("playback_mode", 0)) { PlaybackMode.NORMAL }
    }

    private fun savePlaybackMode() {
        getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
            .edit().putInt("playback_mode", playbackMode.ordinal).apply()
    }

    private fun setupPlaybackModeButtons() {
        val btnRepeatOne = findViewById<ImageButton>(R.id.btnRepeatOne)
        val btnRepeatAll = findViewById<ImageButton>(R.id.btnRepeatAll)
        val btnShuffle   = findViewById<ImageButton>(R.id.btnShuffle)

        val active   = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        val inactive = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))

        fun refresh() {
            btnRepeatOne.imageTintList = if (playbackMode == PlaybackMode.REPEAT_ONE) active else inactive
            btnRepeatAll.imageTintList = if (playbackMode == PlaybackMode.REPEAT_ALL) active else inactive
            btnShuffle.imageTintList   = if (playbackMode == PlaybackMode.SHUFFLE)    active else inactive
        }

        btnRepeatOne.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ONE) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ONE
            refresh()
        }
        btnRepeatAll.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ALL) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ALL
            refresh()
        }
        btnShuffle.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.SHUFFLE) PlaybackMode.NORMAL else PlaybackMode.SHUFFLE
            refresh()
        }
        refresh()
    }

    // --- Playlist touch helper ---

    private fun setupPlaylistTouchHelper(recyclerView: RecyclerView) {
        val paint = Paint()

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val item = tracks.removeAt(from)
                tracks.add(to, item)
                adapter.notifyItemMoved(from, to)
                savePlaylist()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    tracks.removeAt(pos)
                    adapter.notifyItemRemoved(pos)
                    savePlaylist()
                }
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val v = vh.itemView
                    paint.color = Color.parseColor("#E53935")
                    c.drawRoundRect(RectF(v.right + dX, v.top + 6f, v.right.toFloat(), v.bottom - 6f), 12f, 12f, paint)
                    ContextCompat.getDrawable(rv.context, android.R.drawable.ic_menu_delete)?.let { icon ->
                        icon.setTint(Color.WHITE)
                        val sz = 44; val top = v.top + (v.height - sz) / 2; val left = v.right - sz - 20
                        icon.setBounds(left, top, left + sz, top + sz)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun isLongPressDragEnabled() = true
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }
}

class TrackAdapter(private val tracks: MutableList<TrackItem>) :
    RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPlaying: ImageView = view.findViewById(R.id.ivPlaying)
        val tvTrackName: TextView = view.findViewById(R.id.tvTrackName)
        val tvTrackInfo: TextView = view.findViewById(R.id.tvTrackInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.tvTrackName.text = track.name
        holder.tvTrackInfo.text = track.info
        holder.ivPlaying.visibility = if (track.isPlaying) View.VISIBLE else View.INVISIBLE
    }

    override fun getItemCount() = tracks.size
}
