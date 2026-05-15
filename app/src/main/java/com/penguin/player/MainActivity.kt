package com.penguin.player

import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
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
    val uri: String = "",
    var isPlaying: Boolean = false
)

enum class PlaybackMode { NORMAL, REPEAT_ONE, REPEAT_ALL, SHUFFLE }

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTIF_CHANNEL_ID = "penguin_playback"
        private const val NOTIF_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.penguin.player.PLAY_PAUSE"
        private const val ACTION_PREV       = "com.penguin.player.PREV"
        private const val ACTION_NEXT       = "com.penguin.player.NEXT"
    }

    private var playbackMode = PlaybackMode.NORMAL
    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<TrackItem>()

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIndex = -1
    private var isSeekBarDragging = false
    private var seekSaveTick = 0

    private lateinit var mediaSession: MediaSessionCompat
    private val notifManager by lazy { getSystemService(NotificationManager::class.java)!! }
    private var playbackSpeed = 1.0f
    private lateinit var tvSpeed: TextView
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private val audioMgr by lazy { getSystemService(AudioManager::class.java)!! }
    private var audioFocusReq: AudioFocusRequest? = null

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_PREV       -> playPrev()
                ACTION_NEXT       -> playNext()
            }
        }
    }

    private lateinit var fabPlayPause: FloatingActionButton
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var rvPlaylist: RecyclerView

    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying && !isSeekBarDragging) {
                val pos = mp.currentPosition
                seekBar.progress = pos
                tvCurrentTime.text = formatTime(pos)
                seekSaveTick++
                if (seekSaveTick >= 20) {   // 10秒ごとに保存
                    seekSaveTick = 0
                    runCatching {
                        saveResumeIfLong(
                            tracks.getOrNull(currentTrackIndex)?.uri ?: "",
                            pos, mp.duration
                        )
                    }
                }
            }
            seekHandler.postDelayed(this, 500)
        }
    }

    private val fileListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("UNCHECKED_CAST")
            val files = result.data?.getSerializableExtra("files") as? ArrayList<AudioFile>
            val autoPlay = result.data?.getBooleanExtra("auto_play", false) ?: false
            if (files != null) {
                if (autoPlay) {
                    stopPlayback()
                    currentTrackIndex = -1
                    tracks.clear()
                    tvTrackTitle.text = ""
                    tvFileName.text = ""
                    tvTotalTime.text = "0:00"
                }
                val startIndex = tracks.size
                files.forEach { audioFile ->
                    tracks.add(TrackItem(audioFile.trackName, audioFile.format, audioFile.uri))
                }
                adapter.notifyDataSetChanged()
                savePlaylist()
                if (tracks.isNotEmpty()) play(startIndex)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
                .edit().putString("folder_uri", uri.toString()).apply()
            launchFileList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        tvTrackTitle  = findViewById(R.id.tvTrackTitle)
        tvFileName    = findViewById(R.id.tvFileName)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime   = findViewById(R.id.tvTotalTime)
        seekBar       = findViewById(R.id.seekBar)
        fabPlayPause  = findViewById(R.id.fabPlayPause)

        loadPlaylist()
        loadPlaybackMode()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { isSeekBarDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeekBarDragging = false
                mediaPlayer?.let { mp ->
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        mp.seekTo(sb.progress.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
                    } else {
                        mp.seekTo(sb.progress)
                    }
                }
            }
        })

        findViewById<View>(R.id.btnSelectStorage).setOnClickListener {
            val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
            if (prefs.getString("folder_uri", null) != null) launchFileList()
            else folderPickerLauncher.launch(null)
        }

        fabPlayPause.setOnClickListener { togglePlayPause() }

        findViewById<ImageButton>(R.id.btnSkipPrev).setOnClickListener { playPrev() }
        findViewById<ImageButton>(R.id.btnSkipNext).setOnClickListener { playNext() }
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            mediaPlayer?.let { it.seekTo(maxOf(0, it.currentPosition - 10000)) }
        }
        findViewById<ImageButton>(R.id.btnFastForward).setOnClickListener {
            mediaPlayer?.let { it.seekTo(minOf(it.duration, it.currentPosition + 10000)) }
        }

        setupPlaybackModeButtons()

        tvSpeed = findViewById(R.id.tvPlaybackSpeed)
        tvSpeed.setOnClickListener { showSpeedDialog() }

        createNotificationChannel()
        setupMediaSession()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE); addAction(ACTION_PREV); addAction(ACTION_NEXT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, mediaControlReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        adapter = TrackAdapter(tracks) { index -> play(index) }
        rvPlaylist = findViewById(R.id.rvPlaylist)
        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = adapter
        setupPlaylistTouchHelper(rvPlaylist)
    }

    // --- Earphone button handling ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("PenguinDbg", "onKeyDown keyCode=$keyCode repeatCount=${event.repeatCount} source=${event.source}")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount > 0) return true
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount > 0) return true
                event.startTracking()
                audioMgr.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                event.startTracking()
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                playNext()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                playPrev()
                true
            }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_folder_settings  -> { folderPickerLauncher.launch(null); true }
        R.id.action_sort_playlist    -> { showPlaylistSortDialog(); true }
        R.id.action_clear_playlist   -> { confirmClearPlaylist(); true }
        R.id.action_sleep_timer      -> { showSleepTimerDialog(); true }
        R.id.action_resume_settings  -> { showResumeSettings(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        savePlaylist()
        savePlaybackMode()
        saveCurrentResumePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        seekHandler.removeCallbacks(seekRunnable)
        saveCurrentResumePosition()
        mediaPlayer?.release()
        mediaPlayer = null
        unregisterReceiver(mediaControlReceiver)
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        cancelNotification()
        if (::mediaSession.isInitialized) mediaSession.release()
    }

    private fun saveCurrentResumePosition() {
        val mp = mediaPlayer ?: return
        val uri = tracks.getOrNull(currentTrackIndex)?.uri ?: return
        runCatching {
            saveResumeIfLong(uri, mp.currentPosition, mp.duration)
        }
    }

    // --- Playback ---

    private fun play(index: Int) {
        if (index !in tracks.indices) return

        val oldIndex = currentTrackIndex
        tracks.getOrNull(oldIndex)?.isPlaying = false
        if (oldIndex in tracks.indices) adapter.notifyItemChanged(oldIndex)

        currentTrackIndex = index
        tracks[currentTrackIndex].isPlaying = true
        adapter.notifyItemChanged(currentTrackIndex)
        rvPlaylist.post { rvPlaylist.smoothScrollToPosition(currentTrackIndex) }

        val track = tracks[currentTrackIndex]
        tvTrackTitle.text = track.name
        tvFileName.text = track.info

        seekHandler.removeCallbacks(seekRunnable)
        seekSaveTick = 0

        // 前のトラックの再生位置を保存してから解放
        val oldMp = mediaPlayer
        if (oldMp != null && oldIndex in tracks.indices) {
            runCatching {
                saveResumeIfLong(
                    tracks[oldIndex].uri,
                    oldMp.currentPosition,
                    oldMp.duration
                )
            }
        }
        oldMp?.release()
        mediaPlayer = null

        if (track.uri.isEmpty()) return

        requestAudioFocus()

        mediaPlayer = MediaPlayer().also { mp ->
            mp.setDataSource(this, Uri.parse(track.uri))
            mp.prepareAsync()
            mp.setOnPreparedListener {
                it.start()
                fabPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                tvTotalTime.text = formatTime(it.duration)
                seekBar.max = it.duration

                // リジューム位置の復元
                val thresholdMs = resumeThresholdMin * 60_000L
                val savedPos = if (it.duration >= thresholdMs) getResumePosition(track.uri) else 0
                if (savedPos in 1 until it.duration) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        it.seekTo(savedPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        it.seekTo(savedPos)
                    }
                    seekBar.progress = savedPos
                    tvCurrentTime.text = formatTime(savedPos)
                } else {
                    seekBar.progress = 0
                    tvCurrentTime.text = "0:00"
                }
                if (Build.VERSION.SDK_INT >= 23 && playbackSpeed != 1.0f) {
                    runCatching { it.playbackParams = android.media.PlaybackParams().setSpeed(playbackSpeed) }
                }
                seekHandler.post(seekRunnable)
                updateNotification()
            }
            mp.setOnCompletionListener {
                seekHandler.removeCallbacks(seekRunnable)
                clearResumePosition(track.uri)
                playNext(fromCompletion = true)
            }
            mp.setOnErrorListener { _, _, _ -> false }
        }
    }

    private fun playNext(fromCompletion: Boolean = false) {
        if (tracks.isEmpty()) return
        val next = when (playbackMode) {
            PlaybackMode.REPEAT_ONE -> currentTrackIndex
            PlaybackMode.SHUFFLE    -> (tracks.indices - currentTrackIndex).randomOrNull() ?: 0
            else -> {
                val n = (currentTrackIndex + 1) % tracks.size
                if (fromCompletion && playbackMode == PlaybackMode.NORMAL && currentTrackIndex == tracks.size - 1) {
                    stopPlayback(); return
                }
                n
            }
        }
        play(next)
    }

    private fun playPrev() {
        if (tracks.isEmpty()) return
        val prev = when (playbackMode) {
            PlaybackMode.SHUFFLE -> (tracks.indices - currentTrackIndex).randomOrNull() ?: 0
            else -> (currentTrackIndex - 1 + tracks.size) % tracks.size
        }
        play(prev)
    }

    private fun stopPlayback() {
        seekHandler.removeCallbacks(seekRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
        fabPlayPause.setImageResource(android.R.drawable.ic_media_play)
        tracks.getOrNull(currentTrackIndex)?.isPlaying = false
        if (currentTrackIndex in tracks.indices) adapter.notifyItemChanged(currentTrackIndex)
        seekBar.progress = 0
        tvCurrentTime.text = "0:00"
        cancelNotification()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusReq == null) {
                audioFocusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.pause()
                            AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.let { if (!it.isPlaying) it.start() }
                        }
                    }
                    .build()
            }
            audioMgr.requestAudioFocus(audioFocusReq!!)
        } else {
            @Suppress("DEPRECATION")
            audioMgr.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusReq?.let { audioMgr.abandonAudioFocusRequest(it) }
            audioFocusReq = null
        } else {
            @Suppress("DEPRECATION")
            audioMgr.abandonAudioFocus(null)
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // --- Resume playback ---

    private val resumeThresholdMin: Int
        get() = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
            .getInt("resume_threshold_min", 15)

    private fun saveResumeIfLong(uri: String, position: Int, duration: Int) {
        if (uri.isEmpty() || duration <= 0) return
        if (duration < resumeThresholdMin * 60_000L) return
        if (position >= duration - 5_000) {
            clearResumePosition(uri)
        } else {
            getSharedPreferences("penguin_resume", Context.MODE_PRIVATE)
                .edit().putInt("pos_${uri.hashCode()}", position).apply()
        }
    }

    private fun getResumePosition(uri: String): Int =
        getSharedPreferences("penguin_resume", Context.MODE_PRIVATE)
            .getInt("pos_${uri.hashCode()}", 0)

    private fun clearResumePosition(uri: String) {
        if (uri.isEmpty()) return
        getSharedPreferences("penguin_resume", Context.MODE_PRIVATE)
            .edit().remove("pos_${uri.hashCode()}").apply()
    }

    private fun showResumeSettings() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(resumeThresholdMin.toString())
            selectAll()
            setPadding(60, 40, 60, 20)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.resume_settings_title))
            .setMessage(getString(R.string.resume_settings_msg))
            .setView(input)
            .setPositiveButton(getString(R.string.confirm_ok)) { _, _ ->
                val min = input.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 15
                getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
                    .edit().putInt("resume_threshold_min", min).apply()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // --- Play/Pause toggle ---

    private fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp != null) {
            if (mp.isPlaying) {
                mp.pause()
                fabPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekHandler.removeCallbacks(seekRunnable)
            } else {
                mp.start()
                fabPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                seekHandler.post(seekRunnable)
            }
            updateNotification()
        } else if (tracks.isNotEmpty()) {
            val idx = if (currentTrackIndex in tracks.indices) currentTrackIndex else 0
            play(idx)
        }
    }

    // --- MediaSession & Notification ---

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "PenguinPlayer").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                     MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                private var clickCount = 0
                private val hhHandler = Handler(Looper.getMainLooper())

                override fun onPlay()            = run { if (mediaPlayer?.isPlaying != true) togglePlayPause() }
                override fun onPause()           = run { if (mediaPlayer?.isPlaying == true) togglePlayPause() }
                override fun onSkipToNext()      = playNext()
                override fun onSkipToPrevious()  = playPrev()

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val ev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false
                    Log.d("PenguinDbg", "onMediaButtonEvent keyCode=${ev.keyCode} action=${ev.action} repeatCount=${ev.repeatCount} source=${ev.source}")
                    if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true

                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            togglePlayPause()
                        }
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            // Wired earphone: multi-click detection
                            clickCount++
                            hhHandler.removeCallbacksAndMessages(null)
                            hhHandler.postDelayed({
                                when (clickCount) {
                                    1, 2 -> togglePlayPause()
                                    else -> playPrev()
                                }
                                clickCount = 0
                            }, 500L)
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT     -> playNext()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playPrev()
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "再生中", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "PenguinPlayer 再生コントロール" }
            notifManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (!::mediaSession.isInitialized) return
        val track = tracks.getOrNull(currentTrackIndex) ?: run { cancelNotification(); return }
        val isPlaying = mediaPlayer?.isPlaying == true

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.info)
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    mediaPlayer?.currentPosition?.toLong() ?: 0L,
                    playbackSpeed
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )

        val flags = PendingIntent.FLAG_IMMUTABLE
        val prevPI = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PREV).setPackage(packageName), flags)
        val ppPI   = PendingIntent.getBroadcast(this, 1, Intent(ACTION_PLAY_PAUSE).setPackage(packageName), flags)
        val nextPI = PendingIntent.getBroadcast(this, 2, Intent(ACTION_NEXT).setPackage(packageName), flags)
        val tapPI  = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track.name)
            .setContentText(track.info)
            .setContentIntent(tapPI)
            .addAction(R.drawable.ic_skip_previous, "前の曲", prevPI)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "一時停止" else "再生", ppPI
            )
            .addAction(R.drawable.ic_skip_next, "次の曲", nextPI)
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .build()

        notifManager.notify(NOTIF_ID, notification)
    }

    private fun cancelNotification() {
        if (::mediaSession.isInitialized) notifManager.cancel(NOTIF_ID)
    }

    // --- Playback speed ---

    private fun showSpeedDialog() {
        val labels  = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
        val values  = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val current = values.indexOfFirst { it == playbackSpeed }.takeIf { it >= 0 } ?: 2
        MaterialAlertDialogBuilder(this)
            .setTitle("再生速度")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                setPlaybackSpeed(values[which])
                tvSpeed.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        if (Build.VERSION.SDK_INT >= 23) {
            mediaPlayer?.let {
                runCatching { it.playbackParams = android.media.PlaybackParams().setSpeed(speed) }
            }
        }
    }

    // --- Sleep timer ---

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            getString(R.string.sleep_timer_off), "15分", "30分", "60分", "90分"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sleep_timer))
            .setItems(options) { _, which ->
                setSleepTimer(when (which) { 1 -> 15L; 2 -> 30L; 3 -> 60L; 4 -> 90L; else -> 0L })
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setSleepTimer(minutes: Long) {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) {
            Toast.makeText(this, "スリープタイマーをOFFにしました", Toast.LENGTH_SHORT).show()
            return
        }
        val r = Runnable { stopPlayback() }
        sleepRunnable = r
        sleepHandler.postDelayed(r, minutes * 60_000L)
        Toast.makeText(this, "${minutes}分後に停止します", Toast.LENGTH_SHORT).show()
    }

    // --- Playlist sort ---

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

    private fun showPlaylistSortDialog() {
        if (tracks.isEmpty()) return
        val options = arrayOf(
            getString(R.string.sort_track_name_asc),
            getString(R.string.sort_track_name_desc),
            getString(R.string.sort_format)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sort_title))
            .setItems(options) { _, which -> sortPlaylist(which) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sortPlaylist(mode: Int) {
        val playingUri = tracks.getOrNull(currentTrackIndex)?.uri
        when (mode) {
            0 -> tracks.sortWith { a, b -> naturalCompare(a.name, b.name) }
            1 -> tracks.sortWith { a, b -> naturalCompare(b.name, a.name) }
            2 -> tracks.sortWith { a, b -> a.info.compareTo(b.info) }
        }
        currentTrackIndex = if (playingUri != null)
            tracks.indexOfFirst { it.uri == playingUri } else -1
        adapter.notifyDataSetChanged()
        savePlaylist()
    }

    private fun launchFileList() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val uriString = prefs.getString("folder_uri", null)
        val intent = Intent(this, FileListActivity::class.java)
        if (uriString != null) intent.putExtra("folder_uri", uriString)
        fileListLauncher.launch(intent)
    }

    // --- Playlist clear ---

    private fun confirmClearPlaylist() {
        if (tracks.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.playlist_clear))
            .setMessage(getString(R.string.playlist_clear_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                stopPlayback()
                currentTrackIndex = -1
                tracks.clear()
                adapter.notifyDataSetChanged()
                savePlaylist()
                tvTrackTitle.text = ""
                tvFileName.text = ""
                tvTotalTime.text = "0:00"
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // --- Playlist persistence ---

    private fun loadPlaylist() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val count = prefs.getInt("playlist_count", -1)
        for (i in 0 until count) {
            val name    = prefs.getString("track_name_$i", null) ?: continue
            val info    = prefs.getString("track_info_$i", "") ?: ""
            val uri     = prefs.getString("track_uri_$i", "") ?: ""
            val playing = prefs.getBoolean("track_playing_$i", false)
            tracks.add(TrackItem(name, info, uri, playing))
        }
    }

    private fun savePlaylist() {
        val prefs = getSharedPreferences("penguin_player", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        edit.putInt("playlist_count", tracks.size)
        tracks.forEachIndexed { i, track ->
            edit.putString("track_name_$i", track.name)
            edit.putString("track_info_$i", track.info)
            edit.putString("track_uri_$i", track.uri)
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
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                currentTrackIndex = when {
                    from == currentTrackIndex -> to
                    from < to && currentTrackIndex in (from + 1)..to -> currentTrackIndex - 1
                    from > to && currentTrackIndex in to until from  -> currentTrackIndex + 1
                    else -> currentTrackIndex
                }
                tracks.add(to, tracks.removeAt(from))
                adapter.notifyItemMoved(from, to)
                savePlaylist()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                if (pos == currentTrackIndex) stopPlayback()
                if (pos < currentTrackIndex) currentTrackIndex--
                tracks.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                savePlaylist()
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

class TrackAdapter(
    private val tracks: MutableList<TrackItem>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

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
        holder.itemView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = tracks.size
}
