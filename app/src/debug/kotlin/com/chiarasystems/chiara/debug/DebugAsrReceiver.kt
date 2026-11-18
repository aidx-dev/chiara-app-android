package com.chiarasystems.chiara.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chiarasystems.chiara.ai.LocalModels
import com.chiarasystems.chiara.ai.SherpaAsr
import com.chiarasystems.chiara.ai.PcmSource
import com.chiarasystems.chiara.ai.TranscriptionModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking

/** Debug only: `adb shell am broadcast -a com.chiarasystems.chiara.DEBUG_ASR --es path /data/local/tmp/pk/es.wav` transcribes a 16-bit mono WAV with the installed speech model and logs it (tag ChiaraAsr). */
class DebugAsrReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val pending = goAsync()
        Thread {
            try {
                val bytes = File(path).readBytes()
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val rate = buf.getInt(24)
                val pcm = ShortArray((bytes.size - 44) / 2) { buf.getShort(44 + it * 2) }
                val source = object : PcmSource {
                    override val sampleRate = rate
                    override val frames = pcm.size.toLong()
                    override fun read(from: Long, count: Int) = pcm.copyOfRange(from.toInt(), minOf(pcm.size, from.toInt() + count))
                }
                val started = System.nanoTime()
                val segments = runBlocking { SherpaAsr(LocalModels(context)).transcribe(source, TranscriptionModel.Parakeet, "es") { } }
                val secs = (System.nanoTime() - started) / 1e9
                Log.i("ChiaraAsr", "OK ${pcm.size.toDouble() / rate}s audio in ${"%.1f".format(secs)}s, ${segments.size} segments")
                segments.forEach { Log.i("ChiaraAsr", "[%.2f-%.2f] %s".format(it.start, it.end, it.text)) }
            } catch (e: Throwable) { Log.e("ChiaraAsr", "FAILED", e) } finally { pending.finish() }
        }.start()
    }
}
