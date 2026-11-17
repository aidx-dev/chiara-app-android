package com.chiarasystems.chiara.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of iOS `ConsultationRecorder`'s audio side: the microphone (16 kHz mono) written to AAC as it comes, with pause,
 * a level meter and audio-focus handling (a phone call pauses it). The file is an ADTS stream, so whatever was written
 * before a crash is playable and transcribable; there is nothing to finalize.
 */
class ConsultationRecorder(private val context: Context) {
    enum class State { Idle, Recording, Paused }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /** Seconds captured so far (paused time excluded). */
    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed.asStateFlow()
    /** Latest input level, 0..1. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    /** Set when the recording stopped by itself (the microphone was taken, the disk is full). */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var record: AudioRecord? = null
    private var writer: AacAdtsWriter? = null
    private var thread: Thread? = null
    @Volatile private var paused = false
    @Volatile private var running = false
    private var frames = 0L
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before calling start()
    fun start(file: File): Boolean {
        if (_state.value != State.Idle) return false
        val minBuffer = AudioRecord.getMinBufferSize(APP_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, APP_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 16_000))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val w = try { AacAdtsWriter(file) } catch (e: Exception) { rec.release(); return false }

        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                // A phone call or another app taking the microphone: pause, and the doctor resumes by hand.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }.build().also { audioManager.requestAudioFocus(it) }

        record = rec; writer = w; frames = 0; paused = false; running = true
        _elapsed.value = 0.0; _failure.value = null
        rec.startRecording()
        _state.value = State.Recording
        thread = Thread({ loop(rec, w) }, "chiara-recorder").also { it.start() }
        return true
    }

    private fun loop(rec: AudioRecord, w: AacAdtsWriter) {
        val buffer = ShortArray(1600) // 100 ms
        try {
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n < 0) { _failure.value = "audio"; break }
                if (n == 0 || paused) continue
                w.write(buffer, n)
                frames += n
                var sum = 0.0
                for (i in 0 until n) { val v = buffer[i] / 32768.0; sum += v * v }
                _level.value = minOf(1f, (sqrt(sum / n) * 4).toFloat())
                _elapsed.value = frames.toDouble() / APP_SAMPLE_RATE
            }
        } catch (e: Exception) {
            _failure.value = e.message ?: "audio"
        }
    }

    fun pause() { if (_state.value == State.Recording) { paused = true; _state.value = State.Paused; _level.value = 0f } }
    fun resume() { if (_state.value == State.Paused) { paused = false; _state.value = State.Recording } }

    /** Stops and closes the file; returns the seconds captured. */
    fun stop(): Double {
        if (_state.value == State.Idle) return _elapsed.value
        running = false
        thread?.join(2000)
        runCatching { record?.stop() }
        record?.release(); record = null
        writer?.finish(); writer = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        _state.value = State.Idle
        _level.value = 0f
        return frames.toDouble() / APP_SAMPLE_RATE
    }
}
