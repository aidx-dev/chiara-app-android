package com.chiarasystems.chiara.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.chiarasystems.chiara.ai.PcmSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val APP_SAMPLE_RATE = 16_000

/** Streaming conversion of interleaved PCM16 at any rate/channels to 16 kHz mono (linear interpolation). */
class MonoResampler(private val inRate: Int, private val channels: Int) {
    private val ratio = inRate.toDouble() / APP_SAMPLE_RATE
    private var position = 0.0   // in input frames, relative to the first frame of `carry`
    private var carry = ShortArray(0)

    fun process(interleaved: ShortArray, count: Int): ShortArray {
        val frames = count / channels
        val mono = ShortArray(carry.size + frames)
        carry.copyInto(mono)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            mono[carry.size + f] = (sum / channels).toShort()
        }
        if (inRate == APP_SAMPLE_RATE) { carry = ShortArray(0); position = 0.0; return mono }
        val out = ArrayList<Short>((mono.size / ratio).toInt() + 2)
        while (position + 1 < mono.size) {
            val i = position.toInt()
            val frac = position - i
            out += (mono[i] * (1 - frac) + mono[i + 1] * frac).toInt().toShort()
            position += ratio
        }
        val keepFrom = position.toInt().coerceAtMost(mono.size)
        carry = mono.copyOfRange(keepFrom, mono.size)
        position -= keepFrom
        return out.toShortArray()
    }
}

/** Encodes 16 kHz mono PCM16 to AAC-LC in an ADTS stream (`.aac`): crash-safe, a frame at a time, no container to finalize. */
class AacAdtsWriter(file: File, bitRate: Int = 32_000) {
    private val out = FileOutputStream(file)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L
    private var finished = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, APP_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int = pcm.size) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(); continue }
            val buffer = codec.getInputBuffer(index)!!.apply { clear() }
            val n = minOf(count - offset, buffer.capacity() / 2)
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
            val ptsUs = samplesIn * 1_000_000L / APP_SAMPLE_RATE
            codec.queueInputBuffer(index, 0, n * 2, ptsUs, 0)
            samplesIn += n
            offset += n
            drain()
        }
    }

    private fun drain(untilEnd: Boolean = false) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                index >= 0 -> {
                    val data = codec.getOutputBuffer(index)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val frame = ByteArray(info.size)
                        data.position(info.offset); data.get(frame, 0, info.size)
                        out.write(adtsHeader(info.size + 7)); out.write(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                else -> Unit
            }
        }
    }

    /** AAC-LC, 16 kHz (index 8), mono. */
    private fun adtsHeader(length: Int) = byteArrayOf(
        0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (8 shl 2) or 0).toByte(),
        (((1 and 3) shl 6) or (length shr 11)).toByte(), ((length shr 3) and 0xFF).toByte(),
        (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte(),
    )

    fun finish() {
        if (finished) return
        finished = true
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000L / APP_SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(untilEnd = true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { out.flush(); out.fd.sync() }; out.close()
        }
    }
}

object AudioDecoder {
    class Failure(message: String) : IOException(message)

    /** Decodes any audio the platform reads into 16 kHz mono PCM16 chunks; returns the seconds decoded. */
    fun decode(setup: (MediaExtractor) -> Unit, sink: (ShortArray) -> Unit): Double {
        val extractor = MediaExtractor()
        try {
            setup(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Failure("not audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = MonoResampler(rate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var total = 0L
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o >= 0 -> {
                            if (info.size > 0) {
                                val data = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                                data.position(info.offset); data.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                data.asShortBuffer().get(shorts)
                                val mono = resampler.process(shorts, shorts.size)
                                total += mono.size
                                sink(mono)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            val r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); val c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (r != rate || c != channels) { rate = r; channels = c; resampler = MonoResampler(rate, channels) }
                        }
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            return total.toDouble() / APP_SAMPLE_RATE
        } finally { extractor.release() }
    }

    /** The app's recording ([file]) decoded to a temp raw PCM file, readable by stretches. Delete with [FilePcmSource.close]. */
    fun toPcmSource(file: File, tempDir: File): FilePcmSource {
        val temp = File.createTempFile("pcm-", ".raw", tempDir)
        try {
            FileOutputStream(temp).use { out ->
                val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                decode({ it.setDataSource(file.path) }) { pcm ->
                    var i = 0
                    while (i < pcm.size) {
                        bytes.clear()
                        val n = minOf(pcm.size - i, bytes.capacity() / 2)
                        for (k in 0 until n) bytes.putShort(pcm[i + k])
                        out.write(bytes.array(), 0, n * 2)
                        i += n
                    }
                }
            }
        } catch (e: Throwable) { temp.delete(); throw e }
        return FilePcmSource(temp)
    }

    /** Converts an audio file picked by the user (any format the platform decodes) into the app's AAC at [destination]. */
    fun importInto(context: Context, uri: Uri, destination: File): Double {
        val writer = AacAdtsWriter(destination)
        try {
            val seconds = decode({ it.setDataSource(context, uri, null) }) { writer.write(it) }
            if (seconds <= 0.0) throw Failure("empty")
            return seconds
        } catch (e: Throwable) { destination.delete(); throw e } finally { writer.finish() }
    }
}

/** Raw PCM16 mono 16 kHz file read through random access. */
class FilePcmSource(private val file: File) : PcmSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val sampleRate = APP_SAMPLE_RATE
    override val frames: Long = file.length() / 2

    @Synchronized override fun read(from: Long, count: Int): ShortArray {
        val n = minOf(count.toLong(), frames - from).toInt().coerceAtLeast(0)
        if (n == 0) return ShortArray(0)
        val bytes = ByteArray(n * 2)
        raf.seek(from * 2)
        raf.readFully(bytes)
        val out = ShortArray(n)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    override fun close() { runCatching { raf.close() }; file.delete() }
}
