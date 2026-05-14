package com.penguin.player

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Serializable

data class AudioFile(
    val name: String,
    val format: String,
    val size: String,
    val trackName: String = name.substringBeforeLast(".").replace("_", " "),
    val lastModified: Long = 0L,
    val createdAt: Long? = null,
    val trackNumber: Int? = null
) : Serializable

enum class SortOrder {
    NAME_ASC, NAME_DESC,
    TRACK_NAME_ASC, TRACK_NAME_DESC,
    TRACK_NUM_ASC, TRACK_NUM_DESC,
    SIZE_ASC, SIZE_DESC,
    FORMAT,
    MODIFIED_DESC, MODIFIED_ASC,
    CREATED_DESC, CREATED_ASC
}

class FileListActivity : AppCompatActivity() {

    private val allFiles = mutableListOf(
        AudioFile("beethoven_symphony_no9.flac",    "FLAC", "42.3 MB",  "Beethoven - Symphony No.9",         1736899200000L, 1717200000000L, 1),
        AudioFile("mozart_piano_concerto_no21.flac","FLAC", "56.1 MB",  "Mozart - Piano Concerto No.21",      1740009600000L, 1719792000000L, 2),
        AudioFile("bach_cello_suite_no1.wav",       "WAV",  "78.4 MB",  "Bach - Cello Suite No.1",            1732924800000L, 1701388800000L, 3),
        AudioFile("chopin_nocturne_op9.mp3",        "MP3",  "5.2 MB",   "Chopin - Nocturne Op.9 No.2",        1741132800000L, 1722470400000L, 4),
        AudioFile("debussy_clair_de_lune.flac",     "FLAC", "18.7 MB",  "Debussy - Clair de Lune",            1724112000000L, 1696118400000L, 5),
        AudioFile("bach_goldberg_variations.alac",  "ALAC", "234.5 MB", "Bach - Goldberg Variations",         1743465600000L, 1704067200000L, 6),
        AudioFile("beethoven_moonlight_sonata.mp3", "MP3",  "12.1 MB",  "Beethoven - Moonlight Sonata",       1733788800000L, 1706745600000L, 7),
        AudioFile("wagner_ring_cycle_part1.wma",    "WMA",  "312.0 MB", "Wagner - Ring Cycle Part 1",         1728950400000L, 1693526400000L, 8),
        AudioFile("schubert_winterreise.flac",      "FLAC", "89.3 MB",  "Schubert - Winterreise",             1746057600000L, 1714521600000L, 9),
        AudioFile("brahms_symphony_no4.flac",       "FLAC", "67.8 MB",  "Brahms - Symphony No.4",             1727222400000L, 1698796800000L, 10),
    )

    private val displayFiles = mutableListOf<AudioFile>()
    private val selectedIndices = mutableSetOf<Int>()
    private var currentSort = SortOrder.NAME_ASC

    private lateinit var fileAdapter: FileAdapter
    private lateinit var btnAddSelected: MaterialButton
    private lateinit var tvSortOrder: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        val initialFolder = prefs.getString("initial_folder", "/sdcard/Music") ?: "/sdcard/Music"
        findViewById<TextView>(R.id.tvCurrentPath).text = initialFolder

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarFileList)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnAddSelected = findViewById(R.id.btnAddSelected)
        tvSortOrder = findViewById(R.id.tvSortOrder)

        currentSort = loadSortOrder()
        applySort()
        tvSortOrder.text = sortLabel(currentSort)

        fileAdapter = FileAdapter(displayFiles, selectedIndices) { position ->
            toggleSelection(position)
        }

        val rvFiles = findViewById<RecyclerView>(R.id.rvFiles)
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rvFiles.adapter = fileAdapter

        setupFileTouchHelper(rvFiles)

        tvSortOrder.setOnClickListener { showSortDialog() }

        findViewById<MaterialButton>(R.id.btnAddAll).setOnClickListener {
            returnFiles(ArrayList(displayFiles))
        }

        btnAddSelected.setOnClickListener {
            if (selectedIndices.isEmpty()) return@setOnClickListener
            val selected = selectedIndices.sorted().map { displayFiles[it] }
            returnFiles(ArrayList(selected))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun toggleSelection(position: Int) {
        if (selectedIndices.contains(position)) selectedIndices.remove(position)
        else selectedIndices.add(position)
        fileAdapter.notifyItemChanged(position)
        updateAddSelectedButton()
    }

    private fun updateAddSelectedButton() {
        val count = selectedIndices.size
        btnAddSelected.alpha = if (count > 0) 1.0f else 0.4f
        btnAddSelected.text = if (count > 0) "追加（${count}曲）" else getString(R.string.add_selected_zero)
    }

    private fun applySort() {
        val sorted = when (currentSort) {
            SortOrder.NAME_ASC         -> allFiles.sortedBy { it.name }
            SortOrder.NAME_DESC        -> allFiles.sortedByDescending { it.name }
            SortOrder.TRACK_NAME_ASC   -> allFiles.sortedBy { it.trackName }
            SortOrder.TRACK_NAME_DESC  -> allFiles.sortedByDescending { it.trackName }
            SortOrder.TRACK_NUM_ASC    -> allFiles.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            SortOrder.TRACK_NUM_DESC   -> allFiles.sortedByDescending { it.trackNumber ?: 0 }
            SortOrder.SIZE_ASC         -> allFiles.sortedBy { parseSizeMB(it.size) }
            SortOrder.SIZE_DESC        -> allFiles.sortedByDescending { parseSizeMB(it.size) }
            SortOrder.FORMAT           -> allFiles.sortedBy { it.format }
            SortOrder.MODIFIED_DESC    -> allFiles.sortedByDescending { it.lastModified }
            SortOrder.MODIFIED_ASC     -> allFiles.sortedBy { it.lastModified }
            SortOrder.CREATED_DESC     -> allFiles.sortedByDescending { it.createdAt ?: 0L }
            SortOrder.CREATED_ASC      -> allFiles.sortedBy { it.createdAt ?: 0L }
        }
        displayFiles.clear()
        displayFiles.addAll(sorted)
        selectedIndices.clear()
    }

    private fun parseSizeMB(size: String): Float =
        size.replace(" MB", "").toFloatOrNull() ?: 0f

    private fun sortLabel(order: SortOrder): String = when (order) {
        SortOrder.NAME_ASC        -> getString(R.string.sort_name_asc)
        SortOrder.NAME_DESC       -> getString(R.string.sort_name_desc)
        SortOrder.TRACK_NAME_ASC  -> getString(R.string.sort_track_name_asc)
        SortOrder.TRACK_NAME_DESC -> getString(R.string.sort_track_name_desc)
        SortOrder.TRACK_NUM_ASC   -> getString(R.string.sort_track_num_asc)
        SortOrder.TRACK_NUM_DESC  -> getString(R.string.sort_track_num_desc)
        SortOrder.SIZE_ASC        -> getString(R.string.sort_size_asc)
        SortOrder.SIZE_DESC       -> getString(R.string.sort_size_desc)
        SortOrder.FORMAT          -> getString(R.string.sort_format)
        SortOrder.MODIFIED_DESC   -> getString(R.string.sort_modified_desc)
        SortOrder.MODIFIED_ASC    -> getString(R.string.sort_modified_asc)
        SortOrder.CREATED_DESC    -> getString(R.string.sort_created_desc)
        SortOrder.CREATED_ASC     -> getString(R.string.sort_created_asc)
    }

    private fun showSortDialog() {
        val options = SortOrder.values().map { sortLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sort_title))
            .setSingleChoiceItems(options, currentSort.ordinal) { dialog, which ->
                currentSort = SortOrder.values()[which]
                saveSortOrder()
                applySort()
                fileAdapter.notifyDataSetChanged()
                tvSortOrder.text = options[which]
                updateAddSelectedButton()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadSortOrder(): SortOrder {
        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        val ordinal = prefs.getInt("sort_order", SortOrder.NAME_ASC.ordinal)
        return SortOrder.values().getOrElse(ordinal) { SortOrder.NAME_ASC }
    }

    private fun saveSortOrder() {
        getSharedPreferences("penguin_player", MODE_PRIVATE)
            .edit().putInt("sort_order", currentSort.ordinal).apply()
    }

    private fun returnFiles(files: ArrayList<AudioFile>) {
        setResult(RESULT_OK, Intent().putExtra("files", files))
        finish()
    }

    private fun setupFileTouchHelper(recyclerView: RecyclerView) {
        val paint = Paint()
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val file = displayFiles[pos]
                MaterialAlertDialogBuilder(this@FileListActivity)
                    .setTitle(getString(R.string.delete_file))
                    .setMessage("「${file.name}」\n\n${getString(R.string.delete_confirm_msg)}")
                    .setPositiveButton(R.string.delete) { _, _ ->
                        allFiles.remove(file)
                        displayFiles.removeAt(pos)
                        selectedIndices.clear()
                        fileAdapter.notifyItemRemoved(pos)
                        updateAddSelectedButton()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        fileAdapter.notifyItemChanged(pos)
                    }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val v = vh.itemView
                    paint.color = Color.parseColor("#E53935")
                    c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
                    ContextCompat.getDrawable(rv.context, android.R.drawable.ic_menu_delete)?.let { icon ->
                        icon.setTint(Color.WHITE)
                        val sz = 44; val top = v.top + (v.height - sz) / 2; val left = v.right - sz - 20
                        icon.setBounds(left, top, left + sz, top + sz)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }
}

class FileAdapter(
    private val files: MutableList<AudioFile>,
    private val selectedIndices: Set<Int>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileFormat: TextView = view.findViewById(R.id.tvFileFormat)
        val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvFileName.text = file.name
        holder.tvFileFormat.text = file.format
        holder.tvFileSize.text = file.size
        holder.cbSelected.isChecked = selectedIndices.contains(position)
        holder.itemView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = files.size
}
