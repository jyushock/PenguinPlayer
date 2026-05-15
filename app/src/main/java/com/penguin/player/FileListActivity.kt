package com.penguin.player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.Serializable
import java.util.zip.ZipInputStream

data class AudioFile(
    val name: String,
    val format: String,
    val trackName: String = name.substringBeforeLast(".").replace("_", " "),
    val uri: String = "",
    val lastModified: Long = 0L,
    val durationMs: Long = 0L,
    val trackNumber: Int? = null,
    val gmeTrackIndex: Int = -1,
    val parentFileName: String = ""
) : Serializable

data class FolderItem(val name: String, val docId: String)

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

    private var rootUri: Uri? = null
    private var currentFolderDocId: String? = null
    private var currentFolderName: String? = null
    private val folderStack = ArrayDeque<FolderItem>()

    private val allFiles = mutableListOf<AudioFile>()
    private val subFolders = mutableListOf<FolderItem>()
    private val displayFiles = mutableListOf<AudioFile>()
    private val selectedIndices = mutableSetOf<Int>()
    private val durationMap = mutableMapOf<String, String>()
    private var currentSort = SortOrder.NAME_ASC
    private var loadToken = 0

    private val gmeExtensions = setOf("nsf", "nsfe", "spc", "gbs", "vgm", "vgz", "gym", "hes", "kss", "sap", "ay")
    private val zipExtensions = setOf("zip")
    private val audioExtensions = setOf("flac", "mp3", "wav", "m4a", "aac", "wma", "ogg", "opus") + gmeExtensions + zipExtensions
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var browserAdapter: BrowserAdapter
    private lateinit var btnAddSelected: MaterialButton
    private lateinit var tvSortOrder: TextView
    private lateinit var scrollBreadcrumb: HorizontalScrollView
    private lateinit var layoutBreadcrumb: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarFileList)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnAddSelected = findViewById(R.id.btnAddSelected)
        tvSortOrder = findViewById(R.id.tvSortOrder)
        scrollBreadcrumb = findViewById(R.id.scrollBreadcrumb)
        layoutBreadcrumb = findViewById(R.id.layoutBreadcrumb)

        currentSort = loadSortOrder()
        tvSortOrder.text = sortLabel(currentSort)
        tvSortOrder.setOnClickListener { showSortDialog() }

        browserAdapter = BrowserAdapter(
            folders = subFolders,
            files = displayFiles,
            selectedIndices = selectedIndices,
            durationMap = durationMap,
            onFolderClick = { folder -> navigateTo(folder) },
            onFolderAddClick = { folder ->
                val files = getFolderAudioFiles(folder.docId)
                if (files.isNotEmpty()) returnFiles(ArrayList(files), autoPlay = true)
            },
            onFileClick = { fileIndex ->
                val file = displayFiles[fileIndex]
                when (file.name.substringAfterLast(".").lowercase()) {
                    in gmeExtensions -> openGmeFile(file)
                    in zipExtensions -> openZipFile(file)
                    else             -> toggleSelection(fileIndex)
                }
            },
            onFileLongClick = { fileIndex -> confirmDeleteFile(fileIndex) }
        )

        val rvFiles = findViewById<RecyclerView>(R.id.rvFiles)
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rvFiles.adapter = browserAdapter

        val btnAddAll = findViewById<MaterialButton>(R.id.btnAddAll)
        btnAddAll.setOnClickListener {
            returnFiles(ArrayList(displayFiles), autoPlay = true)
        }
        btnAddAll.setOnLongClickListener {
            if (displayFiles.isEmpty()) return@setOnLongClickListener true
            returnFiles(ArrayList(displayFiles), appendOnly = true)
            true
        }
        btnAddSelected.setOnClickListener {
            if (selectedIndices.isEmpty()) return@setOnClickListener
            val selected = selectedIndices.sorted().map { displayFiles[it] }
            returnFiles(ArrayList(selected), autoPlay = true)
        }
        btnAddSelected.setOnLongClickListener {
            if (selectedIndices.isEmpty()) return@setOnLongClickListener true
            val selected = selectedIndices.sorted().map { displayFiles[it] }
            returnFiles(ArrayList(selected), appendOnly = true)
            true
        }

        val uriString = intent.getStringExtra("folder_uri")
        if (uriString != null) {
            rootUri = Uri.parse(uriString)
            val treeUri = rootUri!!
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDisplay = getDisplayPath(treeUri)
            currentFolderDocId = rootDocId
            currentFolderName = rootDisplay
            restoreFolderState()
            loadCurrentFolder()
        } else {
            updateBreadcrumb()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_file_browser, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_navigate_up)?.isVisible = folderStack.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_navigate_up   -> { navigateUp(); true }
        R.id.action_navigate_root -> { navigateToRoot(); true }
        else -> super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!navigateUp()) {
            setResult(RESULT_CANCELED)
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // --- Folder navigation ---

    private fun navigateTo(folder: FolderItem) {
        if (currentFolderDocId != null) {
            folderStack.addLast(FolderItem(currentFolderName ?: "", currentFolderDocId!!))
        }
        currentFolderDocId = folder.docId
        currentFolderName = folder.name
        loadCurrentFolder()
    }

    private fun navigateUp(): Boolean {
        if (folderStack.isEmpty()) return false
        val prev = folderStack.removeLast()
        currentFolderDocId = prev.docId
        currentFolderName = prev.name
        loadCurrentFolder()
        return true
    }

    private fun loadCurrentFolder() {
        loadToken++
        val token = loadToken

        saveFolderState()
        allFiles.clear()
        subFolders.clear()
        durationMap.clear()
        selectedIndices.clear()
        updateBreadcrumb()

        val treeUri = rootUri ?: return
        val docId = currentFolderDocId ?: return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        try {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx  = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIdx) ?: continue
                    val name       = cursor.getString(nameIdx) ?: continue
                    val mime       = cursor.getString(mimeIdx) ?: continue
                    val lastMod    = cursor.getLong(modIdx)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subFolders.add(FolderItem(name, childDocId))
                    } else {
                        val ext = name.substringAfterLast(".").lowercase()
                        if (ext !in audioExtensions) continue
                        val format = if (ext == "m4a") "ALAC" else ext.uppercase()
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId).toString()
                        allFiles.add(AudioFile(
                            name = name,
                            format = format,
                            uri = fileUri,
                            lastModified = lastMod
                        ))
                    }
                }
            }
        } catch (_: Exception) { }

        subFolders.sortWith { x, y -> naturalCompare(x.name, y.name) }
        applySort()
        browserAdapter.notifyDataSetChanged()
        updateAddSelectedButton()
        loadDurationsAsync(token)
    }

    private fun getFolderAudioFiles(docId: String): List<AudioFile> {
        val treeUri = rootUri ?: return emptyList()
        val result = mutableListOf<AudioFile>()
        scanRecursive(treeUri, docId, result)
        return result.sortedBy { it.name }
    }

    private fun scanRecursive(treeUri: Uri, docId: String, result: MutableList<AudioFile>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx  = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIdx) ?: continue
                    val name       = cursor.getString(nameIdx) ?: continue
                    val mime       = cursor.getString(mimeIdx) ?: continue
                    val lastMod    = cursor.getLong(modIdx)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanRecursive(treeUri, childDocId, result)
                    } else {
                        val ext = name.substringAfterLast(".").lowercase()
                        if (ext !in audioExtensions) continue
                        val format = if (ext == "m4a") "ALAC" else ext.uppercase()
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId).toString()
                        result.add(AudioFile(name = name, format = format, uri = fileUri, lastModified = lastMod))
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun loadDurationsAsync(token: Int) {
        val snapshot = displayFiles.map { it.uri to it.name }
        Thread {
            snapshot.forEachIndexed { displayIndex, (uri, name) ->
                if (token != loadToken) return@Thread
                val ext = name.substringAfterLast(".").lowercase()
                if (ext in gmeExtensions || ext in zipExtensions) return@forEachIndexed
                var ms = 0L
                var trackNum: Int? = null
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this, Uri.parse(uri))
                    ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    trackNum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.substringBefore('/')?.trim()?.toIntOrNull()
                    retriever.release()
                } catch (_: Exception) { }
                val formatted = if (ms > 0) formatDuration(ms) else ""
                uiHandler.post {
                    if (token != loadToken) return@post
                    durationMap[uri] = formatted
                    val allIdx = allFiles.indexOfFirst { it.uri == uri }
                    if (allIdx >= 0) allFiles[allIdx] = allFiles[allIdx].copy(durationMs = ms, trackNumber = trackNum)
                    browserAdapter.notifyItemChanged(subFolders.size + displayIndex)
                }
            }
        }.start()
    }

    private fun openGmeFile(file: AudioFile) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(getString(R.string.gme_loading))
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        Thread {
            val player = GmePlayer(this)
            val tracks = try { player.openFile(Uri.parse(file.uri)) } catch (_: Exception) { null }
            player.release()
            uiHandler.post {
                dialog.dismiss()
                if (tracks == null) {
                    android.widget.Toast.makeText(this, getString(R.string.gme_open_error), android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                showGmeTrackDialog(file, tracks)
            }
        }.start()
    }

    private fun showGmeTrackDialog(file: AudioFile, tracks: List<GmePlayer.TrackInfo>) {
        val checked = BooleanArray(tracks.size) { true }
        val items = tracks.mapIndexed { i, t ->
            val dur = if (t.durationMs > 0) " (${formatDuration(t.durationMs.toLong())})" else ""
            "${t.name}$dur"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(getString(R.string.gme_add)) { _, _ ->
                val selected = ArrayList<AudioFile>()
                tracks.forEachIndexed { i, trackInfo ->
                    if (checked[i]) {
                        selected.add(AudioFile(
                            name = trackInfo.name,
                            format = file.format,
                            trackName = trackInfo.name,
                            uri = file.uri,
                            lastModified = file.lastModified,
                            durationMs = trackInfo.durationMs.toLong(),
                            gmeTrackIndex = i,
                            parentFileName = file.name
                        ))
                    }
                }
                if (selected.isNotEmpty()) returnFiles(selected)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // --- ZIP support ---

    private fun openZipFile(file: AudioFile) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(getString(R.string.zip_loading))
            .setCancelable(false)
            .show()

        Thread {
            val uri = Uri.parse(file.uri)
            val entries = mutableListOf<String>()
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val ext = entry.name.substringAfterLast(".", "").lowercase()
                                if (ext in audioExtensions && ext !in zipExtensions) {
                                    entries.add(entry.name)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {
                uiHandler.post {
                    dialog.dismiss()
                    android.widget.Toast.makeText(this, getString(R.string.zip_open_error), android.widget.Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            uiHandler.post {
                dialog.dismiss()
                if (entries.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.zip_no_audio), android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                showZipTrackDialog(file, entries)
            }
        }.start()
    }

    private fun showZipTrackDialog(zipFile: AudioFile, entries: List<String>) {
        val checked = BooleanArray(entries.size) { true }
        val displayNames = entries.map { it.substringAfterLast('/') }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(zipFile.name)
            .setMultiChoiceItems(displayNames, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(getString(R.string.gme_add)) { _, _ ->
                val selected = entries.filterIndexed { i, _ -> checked[i] }
                if (selected.isNotEmpty()) extractAndReturnZipEntries(zipFile, selected)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun extractAndReturnZipEntries(zipFile: AudioFile, entries: List<String>) {
        val total = entries.size
        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.zip_extracting))
            .setMessage("0 / $total")
            .setCancelable(false)
            .show()

        Thread {
            val cacheRoot = File(cacheDir, "penguin_zip_cache").also { it.mkdirs() }
            val results = mutableListOf<AudioFile>()
            val needed = entries.toHashSet()
            val safeZipBase = zipFile.name.substringBeforeLast('.')
            var processed = 0

            try {
                contentResolver.openInputStream(Uri.parse(zipFile.uri))?.use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && processed < total) {
                            if (!entry.isDirectory && entry.name in needed) {
                                val fileName   = entry.name.substringAfterLast('/')
                                val safeEntry  = entry.name.replace('/', '_')
                                val destFile   = File(cacheRoot, "${safeZipBase}_${safeEntry}")
                                if (!destFile.exists()) {
                                    destFile.outputStream().use { out -> zip.copyTo(out) }
                                }
                                val ext = fileName.substringAfterLast('.', "").uppercase()
                                results.add(AudioFile(
                                    name           = fileName,
                                    format         = if (ext.lowercase() == "m4a") "ALAC" else ext,
                                    trackName      = fileName.substringBeforeLast('.'),
                                    uri            = android.net.Uri.fromFile(destFile).toString(),
                                    parentFileName = zipFile.name
                                ))
                                processed++
                                val p = processed
                                uiHandler.post { progressDialog.setMessage("$p / $total") }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {
                uiHandler.post {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(this, getString(R.string.zip_extract_error), android.widget.Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            uiHandler.post {
                progressDialog.dismiss()
                if (results.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.zip_extract_error), android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                returnFiles(ArrayList(results))
            }
        }.start()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun updateBreadcrumb() {
        layoutBreadcrumb.removeAllViews()
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.text_secondary)
        val dp4 = (4 * resources.displayMetrics.density).toInt()

        fun addText(label: String, color: Int, onClick: (() -> Unit)? = null) {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 12f
            tv.setTextColor(color)
            tv.setPadding(dp4, dp4, dp4, dp4)
            if (onClick != null) {
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = obtainStyledAttributes(attrs)
                tv.background = ta.getDrawable(0)
                ta.recycle()
                tv.setOnClickListener { onClick() }
            }
            layoutBreadcrumb.addView(tv)
        }

        if (rootUri == null) {
            addText(getString(R.string.no_folder_set), secondaryColor)
            return
        }

        if (folderStack.isEmpty()) {
            addText(currentFolderName ?: "", secondaryColor)
        } else {
            folderStack.forEachIndexed { i, item ->
                if (i > 0) addText(" / ", secondaryColor)
                addText(item.name, primaryColor) { navigateToBreadcrumb(i) }
            }
            addText(" / ", secondaryColor)
            addText(currentFolderName ?: "", secondaryColor)
        }

        scrollBreadcrumb.post { scrollBreadcrumb.fullScroll(View.FOCUS_RIGHT) }
        invalidateOptionsMenu()
    }

    private fun navigateToBreadcrumb(stackIndex: Int) {
        val target = folderStack[stackIndex]
        while (folderStack.size > stackIndex) folderStack.removeLast()
        currentFolderDocId = target.docId
        currentFolderName = target.name
        loadCurrentFolder()
    }

    private fun navigateToRoot() {
        if (folderStack.isEmpty()) return
        val root = folderStack.first()
        folderStack.clear()
        currentFolderDocId = root.docId
        currentFolderName = root.name
        loadCurrentFolder()
    }

    private fun saveFolderState() {
        val folderUri = rootUri?.toString() ?: return
        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        val edit = prefs.edit()
        edit.putString("browser_root_uri", folderUri)
        edit.putString("browser_current_doc_id", currentFolderDocId)
        edit.putString("browser_current_name", currentFolderName)
        edit.putInt("browser_stack_size", folderStack.size)
        folderStack.forEachIndexed { i, item ->
            edit.putString("browser_stack_doc_$i", item.docId)
            edit.putString("browser_stack_name_$i", item.name)
        }
        edit.apply()
    }

    private fun restoreFolderState() {
        val folderUri = rootUri?.toString() ?: return
        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        if (prefs.getString("browser_root_uri", null) != folderUri) return
        val savedDocId = prefs.getString("browser_current_doc_id", null) ?: return
        val savedName = prefs.getString("browser_current_name", null) ?: return
        val stackSize = prefs.getInt("browser_stack_size", 0)
        for (i in 0 until stackSize) {
            val docId = prefs.getString("browser_stack_doc_$i", null) ?: break
            val name = prefs.getString("browser_stack_name_$i", null) ?: break
            folderStack.addLast(FolderItem(name, docId))
        }
        currentFolderDocId = savedDocId
        currentFolderName = savedName
    }

    private fun getDisplayPath(uri: Uri): String {
        val lastSegment = uri.lastPathSegment ?: return uri.toString()
        return if (lastSegment.contains(":")) "/${lastSegment.substringAfter(":")}"
        else lastSegment
    }

    // --- Selection ---

    private fun toggleSelection(fileIndex: Int) {
        if (selectedIndices.contains(fileIndex)) selectedIndices.remove(fileIndex)
        else selectedIndices.add(fileIndex)
        browserAdapter.notifyItemChanged(subFolders.size + fileIndex)
        updateAddSelectedButton()
    }

    private fun updateAddSelectedButton() {
        val count = selectedIndices.size
        btnAddSelected.alpha = if (count > 0) 1.0f else 0.4f
        btnAddSelected.text = if (count > 0) "追加（${count}曲）" else getString(R.string.add_selected_zero)
    }

    // --- Sort ---

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i; var jb = j
                while (ia < a.length && a[ia].isDigit()) ia++
                while (jb < b.length && b[jb].isDigit()) jb++
                val na = a.substring(i, ia).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, jb).trimStart('0').ifEmpty { "0" }
                val cmp = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ia; j = jb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun applySort() {
        val sorted = when (currentSort) {
            SortOrder.NAME_ASC         -> allFiles.sortedWith { x, y -> naturalCompare(x.name, y.name) }
            SortOrder.NAME_DESC        -> allFiles.sortedWith { x, y -> naturalCompare(y.name, x.name) }
            SortOrder.TRACK_NAME_ASC   -> allFiles.sortedWith { x, y -> naturalCompare(x.trackName, y.trackName) }
            SortOrder.TRACK_NAME_DESC  -> allFiles.sortedWith { x, y -> naturalCompare(y.trackName, x.trackName) }
            SortOrder.TRACK_NUM_ASC    -> allFiles.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            SortOrder.TRACK_NUM_DESC   -> allFiles.sortedByDescending { it.trackNumber ?: 0 }
            SortOrder.SIZE_ASC         -> allFiles.sortedBy { it.durationMs }
            SortOrder.SIZE_DESC        -> allFiles.sortedByDescending { it.durationMs }
            SortOrder.FORMAT           -> allFiles.sortedBy { it.format }
            SortOrder.MODIFIED_DESC    -> allFiles.sortedByDescending { it.lastModified }
            SortOrder.MODIFIED_ASC     -> allFiles.sortedBy { it.lastModified }
            SortOrder.CREATED_DESC     -> allFiles.sortedByDescending { it.lastModified }
            SortOrder.CREATED_ASC      -> allFiles.sortedBy { it.lastModified }
        }
        displayFiles.clear()
        displayFiles.addAll(sorted)
    }

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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sort_title))
            .setSingleChoiceItems(options, currentSort.ordinal) { dialog, which ->
                currentSort = SortOrder.values()[which]
                saveSortOrder()
                applySort()
                browserAdapter.notifyDataSetChanged()
                tvSortOrder.text = options[which]
                updateAddSelectedButton()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadSortOrder(): SortOrder {
        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        return SortOrder.values().getOrElse(prefs.getInt("sort_order", 0)) { SortOrder.NAME_ASC }
    }

    private fun saveSortOrder() {
        getSharedPreferences("penguin_player", MODE_PRIVATE)
            .edit().putInt("sort_order", currentSort.ordinal).apply()
    }

    // --- File deletion ---

    private fun confirmDeleteFile(fileIndex: Int) {
        val file = displayFiles.getOrNull(fileIndex) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_file))
            .setMessage(getString(R.string.delete_confirm_msg))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteFile(file) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteFile(file: AudioFile) {
        try {
            val deleted = DocumentsContract.deleteDocument(contentResolver, Uri.parse(file.uri))
            if (deleted) {
                getSharedPreferences("penguin_resume", MODE_PRIVATE)
                    .edit().remove("pos_${file.uri.hashCode()}").apply()
                allFiles.remove(file)
                displayFiles.remove(file)
                durationMap.remove(file.uri)
                selectedIndices.clear()
                browserAdapter.notifyDataSetChanged()
                updateAddSelectedButton()
            }
        } catch (e: SecurityException) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage("削除権限がありません。メニューの「フォルダ設定」でフォルダを再選択してください。")
                .setPositiveButton(getString(R.string.confirm_ok), null)
                .show()
        }
    }

    private fun returnFiles(files: ArrayList<AudioFile>, autoPlay: Boolean = false, appendOnly: Boolean = false) {
        val intent = Intent()
        intent.putExtra("files", files)
        intent.putExtra("auto_play", autoPlay)
        intent.putExtra("append_only", appendOnly)
        setResult(RESULT_OK, intent)
        finish()
    }
}

class BrowserAdapter(
    private val folders: List<FolderItem>,
    private val files: MutableList<AudioFile>,
    private val selectedIndices: Set<Int>,
    private val durationMap: Map<String, String>,
    private val onFolderClick: (FolderItem) -> Unit,
    private val onFolderAddClick: (FolderItem) -> Unit,
    private val onFileClick: (Int) -> Unit,
    private val onFileLongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_FILE = 1
    }

    override fun getItemViewType(position: Int) =
        if (position < folders.size) TYPE_FOLDER else TYPE_FILE

    override fun getItemCount() = folders.size + files.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER)
            FolderViewHolder(inflater.inflate(R.layout.item_folder, parent, false))
        else
            FileViewHolder(inflater.inflate(R.layout.item_file, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < folders.size) {
            val folder = folders[position]
            (holder as FolderViewHolder).bind(folder)
            holder.itemView.setOnClickListener { onFolderClick(folder) }
            holder.btnFolderAdd.setOnClickListener { onFolderAddClick(folder) }
        } else {
            val fileIndex = position - folders.size
            val file = files[fileIndex]
            val duration = durationMap[file.uri] ?: ""
            (holder as FileViewHolder).bind(file, duration, selectedIndices.contains(fileIndex))
            holder.itemView.setOnClickListener { onFileClick(fileIndex) }
            holder.itemView.setOnLongClickListener { onFileLongClick(fileIndex); true }
        }
    }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val btnFolderAdd: ImageButton = view.findViewById(R.id.btnFolderAdd)
        fun bind(folder: FolderItem) { tvFolderName.text = folder.name }
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        private val tvFileFormat: TextView = view.findViewById(R.id.tvFileFormat)
        private val tvFileDuration: TextView = view.findViewById(R.id.tvFileDuration)
        private val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
        private val gmeExts = setOf("nsf", "nsfe", "spc", "gbs", "vgm", "vgz", "gym", "hes", "kss", "sap", "ay")
        fun bind(file: AudioFile, duration: String, selected: Boolean) {
            tvFileName.text = file.name
            tvFileFormat.text = file.format
            tvFileDuration.text = duration
            val ext = file.name.substringAfterLast(".").lowercase()
            val noCheckbox = ext in gmeExts || ext == "zip"
            cbSelected.visibility = if (noCheckbox) View.GONE else View.VISIBLE
            cbSelected.isChecked = selected
        }
    }
}
