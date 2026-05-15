package com.penguin.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper

class GmePlayer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FADE_MS = 8_000

        init { System.loadLibrary("gme_jni") }
    }

    interface Listener {
        fun onPositionChanged(posMs: Int, durationMs: Int)
        fun onCompletion()
        fun onError(message: String)
    }

    data class TrackInfo(val name: String, val durationMs: Int)

    var listener: Listener? = null
    var isPlaying: Boolean = false
        private set

    val currentPosition: Int get() = if (nativeHandle != 0L) nativeTell(nativeHandle) else 0

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var pauseRequested: Boolean = false
    private var renderThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var currentDurationMs: Int = 0
    private val uiHandler = Handler(Looper.getMainLooper())

    fun openFile(uri: Uri): List<TrackInfo>? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return null

        val handle = nativeOpen(bytes)
        if (handle == 0L) return null
        nativeHandle = handle

        val count = nativeGetTrackCount(handle)
        if (count <= 0) { nativeClose(handle); nativeHandle = 0L; return null }

        return (0 until count).map { i ->
            val name = nativeGetTrackName(handle, i).ifEmpty { "Track ${i + 1}" }
            val len = nativeGetTrackLength(handle, i)
            TrackInfo(name, len)
        }
    }

    fun playTrack(trackIndex: Int, durationMs: Int) {
        val handle = nativeHandle
        if (handle == 0L) return
        if (!nativeStartTrack(handle, trackIndex)) {
            listener?.onError("Failed to start track $trackIndex")
            return
        }
        currentDurationMs = durationMs
        val fadeStartMs = (durationMs - FADE_MS).coerceAtLeast(0)
        nativeSetFade(handle, fadeStartMs)

        stopRequested = false
        pauseRequested = false
        isPlaying = true
        startRenderThread(handle)
    }

    fun pause() {
        pauseRequested = true
        isPlaying = false
        audioTrack?.pause()
    }

    fun resume() {
        pauseRequested = false
        isPlaying = true
        audioTrack?.play()
    }

    fun seekTo(msec: Int) {
        val handle = nativeHandle
        if (handle != 0L) nativeSeek(handle, msec)
    }

    fun release() {
        stopRequested = true
        isPlaying = false
        audioTrack?.pause()
        renderThread?.join(2000)
        renderThread = null
        val handle = nativeHandle
        if (handle != 0L) {
            nativeClose(handle)
            nativeHandle = 0L
        }
    }

    @Suppress("DEPRECATION")
    private fun startRenderThread(handle: Long) {
        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val at = AudioTrack(
            AudioManager.STREAM_MUSIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            bufSize, AudioTrack.MODE_STREAM
        )
        audioTrack = at
        at.play()

        val buf = ShortArray(bufSize / 2)
        var tickCount = 0
        val dur = currentDurationMs

        renderThread = Thread {
            try {
                while (!stopRequested) {
                    if (pauseRequested) { Thread.sleep(30); continue }
                    if (nativeTrackEnded(handle)) {
                        uiHandler.post { listener?.onCompletion() }
                        break
                    }
                    if (!nativeRender(handle, buf)) {
                        uiHandler.post { listener?.onCompletion() }
                        break
                    }
                    at.write(buf, 0, buf.size)
                    tickCount++
                    if (tickCount >= 10) {
                        tickCount = 0
                        val pos = nativeTell(handle)
                        uiHandler.post { listener?.onPositionChanged(pos, dur) }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                at.stop()
                at.release()
                audioTrack = null
            }
        }.also { it.start() }
    }

    private external fun nativeOpen(data: ByteArray): Long
    private external fun nativeGetTrackCount(handle: Long): Int
    private external fun nativeGetTrackName(handle: Long, track: Int): String
    private external fun nativeGetTrackLength(handle: Long, track: Int): Int
    private external fun nativeStartTrack(handle: Long, track: Int): Boolean
    private external fun nativeRender(handle: Long, buf: ShortArray): Boolean
    private external fun nativeSetFade(handle: Long, startMs: Int)
    private external fun nativeSeek(handle: Long, msec: Int)
    private external fun nativeTell(handle: Long): Int
    private external fun nativeTrackEnded(handle: Long): Boolean
    private external fun nativeClose(handle: Long)
}
