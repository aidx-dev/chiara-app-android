package com.chiarasystems.chiara.ai

import com.chiarasystems.chiara.network.ApiError
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One piece of the transcript: a sentence or so, with where it sits in the audio and, when the recogniser told the voices
 * apart, who said it (0, 1, 2… as the recogniser numbered them).
 */
@Serializable
data class TranscriptSegment(val start: Double, val end: Double, val text: String, val speaker: Int? = null)

sealed class CloudTranscriberError : Exception() {
    /** This user has no OpenRouter key (a `local` subscription, or none created in chiara-admin). */
    data object NotAvailable : CloudTranscriberError()
    /** OpenRouter answered with this HTTP status, after the retries. */
    data class Rejected(val status: Int) : CloudTranscriberError()
    /** The answer could not be read. */
    data object Unreadable : CloudTranscriberError()
}

/** The app's audio as 16-bit mono PCM, read by stretches (on Android: the recording decoded to a temp PCM file). */
interface PcmSource {
    val sampleRate: Int
    val frames: Long
    /** Samples [from, from+count) (fewer at the end). */
    fun read(from: Long, count: Int): ShortArray
}

class FrameRange(val start: Long, val end: Long) {
    val length: Long get() = end - start
    override fun equals(other: Any?) = other is FrameRange && other.start == start && other.end == end
    override fun hashCode() = (start * 31 + end).hashCode()
    override fun toString() = "$start..<$end"
}

/** Reads the app's audio in stretches, to send a long one in parts. */
object AudioChunker {
    /** [range] in [count] stretches of about the same length, each cut at the quietest moment near its end. */
    fun split(source: PcmSource, range: FrameRange, count: Int): List<FrameRange> {
        if (count <= 1) return listOf(range)
        val rate = source.sampleRate
        val search = 15L * rate
        val cuts = mutableListOf(range.start)
        for (i in 1 until count) {
            val target = range.start + range.length * i / count
            val from = maxOf(cuts.last() + 1, target - search)
            val to = minOf(range.end - 1, target + search)
            if (from >= to) { cuts += target; continue }
            val samples = source.read(from, (to - from).toInt())
            cuts += from + quietest(samples, rate / 10)
        }
        cuts += range.end
        return cuts.zipWithNext().filter { it.first < it.second }.map { FrameRange(it.first, it.second) }
    }

    /** Where in [samples] the quietest [window] begins (its middle). */
    fun quietest(samples: ShortArray, window: Int): Int {
        if (window <= 0 || samples.size <= window) return samples.size / 2
        var best = 0
        var bestEnergy = Double.MAX_VALUE
        var start = 0
        while (start + window <= samples.size) {
            var energy = 0.0
            for (i in start until start + window) { val v = samples[i] / 32768.0; energy += v * v }
            if (energy < bestEnergy) { bestEnergy = energy; best = start }
            start += window / 2
        }
        return best + window / 2
    }

    /** A stretch as a WAV file (mono, 16-bit, at the source's rate): lossless, and a format MAI-Transcribe takes. */
    fun wav(source: PcmSource, range: FrameRange): ByteArray {
        val bytes = (range.length * 2).toInt()
        val out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(source.sampleRate).putInt(source.sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(bytes)
        var pos = range.start
        val step = 16_384
        while (pos < range.end) {
            val chunk = source.read(pos, minOf(step.toLong(), range.end - pos).toInt())
            if (chunk.isEmpty()) break
            for (s in chunk) out.putShort(s)
            pos += chunk.size
        }
        return out.array()
    }
}

/**
 * Speech-to-text in the cloud: Microsoft's MAI-Transcribe 2 through OpenRouter. The audio LEAVES the device, so it is
 * only used when the doctor picks it. Requests ask for zero data retention and no data collection.
 *
 * Android sends WAV (a format MAI-Transcribe takes) instead of iOS's FLAC: the platform has no FLAC muxer and WAV needs no
 * encoder. 16 kHz mono 16-bit is 32 KB/s, so a request holds about 9 minutes; a longer recording goes in stretches of that
 * length, cut at a quiet moment. A stretch that times out (or comes out too big) is split in two and each half sent again.
 */
class CloudTranscriber(
    /** The user's OpenRouter key, null when they have none. */
    private val key: suspend () -> String?,
    private val sender: HttpSender = OkHttpSender(),
    var maxRequestBytes: Int = 18_000_000,
    /** What the audio takes per second as sent, to plan the stretches before encoding. */
    var estimatedBytesPerSecond: Double = 32_000.0,
    /** A stretch shorter than this is not split further after a timeout. */
    var minSplitSeconds: Double = 120.0,
    /** Attempts per request for transient answers. */
    var attempts: Int = 3,
    private val retryDelay: suspend (Int) -> Unit = { delay((1000L shl it)) },
) {
    /** The sentences of the audio with their times. [progress] is told how many seconds are transcribed. */
    suspend fun transcribe(
        source: PcmSource, languageCode: String, diarize: Boolean = false, progress: ((Double) -> Unit)? = null,
    ): List<TranscriptSegment> {
        val k = try {
            key()?.takeIf { it.isNotEmpty() } ?: throw CloudTranscriberError.NotAvailable
        } catch (e: CloudTranscriberError) { throw e } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: ApiError) { if (e.isConnectivity) throw e else throw CloudTranscriberError.NotAvailable
        } catch (e: Exception) { throw CloudTranscriberError.NotAvailable }
        val seconds = source.frames.toDouble() / source.sampleRate
        val parts = maxOf(1, Math.ceil(seconds * estimatedBytesPerSecond / maxRequestBytes).toInt())
        val out = mutableListOf<TranscriptSegment>()
        for (range in AudioChunker.split(source, FrameRange(0, source.frames), parts)) {
            out += transcribe(source, range, k, languageCode, diarize, progress)
        }
        return out
    }

    private suspend fun transcribe(source: PcmSource, range: FrameRange, key: String, languageCode: String, diarize: Boolean, progress: ((Double) -> Unit)?): List<TranscriptSegment> {
        currentCoroutineContext().ensureActive()
        val rate = source.sampleRate.toDouble()
        val start = range.start / rate
        val end = range.end / rate
        val audio = AudioChunker.wav(source, range)
        val splittable = end - start >= minSplitSeconds * 2
        try {
            if (audio.size > maxRequestBytes && splittable) throw CloudTranscriberError.Rejected(413)
            val segments = request(audio, key, languageCode, diarize, start, end)
            progress?.invoke(end)
            return segments
        } catch (e: CloudTranscriberError.Rejected) {
            if ((e.status in TIMEOUT_STATUSES || e.status == 413) && splittable) {
                val out = mutableListOf<TranscriptSegment>()
                for (half in AudioChunker.split(source, range, 2)) out += transcribe(source, half, key, languageCode, diarize, progress)
                return out
            }
            throw e
        }
    }

    /** One request, retried while the answer is transient. Its segments are moved to [start] in the whole recording. */
    suspend fun request(audio: ByteArray, key: String, languageCode: String, diarize: Boolean, start: Double, end: Double): List<TranscriptSegment> {
        val body = body(audio, languageCode, diarize)
        var status = 0
        for (attempt in 0 until attempts) {
            if (attempt > 0) retryDelay(attempt - 1)
            val reply = try { sender.post(ENDPOINT, key, body, 300) } catch (e: SocketTimeoutException) { HttpReply(408, ByteArray(0)) }
            status = reply.status
            if (status == 200) return segments(reply.text, start, end)
            if (status !in TRANSIENT_STATUSES) break
        }
        throw CloudTranscriberError.Rejected(status)
    }

    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
        const val MODEL = "microsoft/mai-transcribe-2"
        /** Statuses that mean the request took too long upstream: worth trying again with less audio. */
        val TIMEOUT_STATUSES = setOf(408, 502, 504, 524)
        val TRANSIENT_STATUSES = setOf(429, 500, 502, 503, 504, 524)

        fun body(audio: ByteArray, languageCode: String, diarize: Boolean): String = buildJsonObject {
            put("model", MODEL)
            put("input_audio", buildJsonObject { put("data", Base64.getEncoder().encodeToString(audio)); put("format", "wav") })
            put("language", languageCode)
            put("response_format", "verbose_json")
            put("timestamp_granularities", JsonArray(listOf(JsonPrimitive("segment"))))
            put("provider", buildJsonObject {
                put("zdr", true); put("data_collection", "deny")
                // Azure's own option, forwarded as is by OpenRouter (`provider.options.<endpoint tag>`).
                if (diarize) put("options", buildJsonObject { put("azure", buildJsonObject { put("diarization", buildJsonObject { put("enabled", true) }) }) })
            })
        }.toString()

        /** The answer's segments, or its whole text as one segment when it has none. */
        fun segments(json: String, start: Double, end: Double): List<TranscriptSegment> {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: throw CloudTranscriberError.Unreadable
            val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val s = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val e = o["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val text = o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                // An index, documented as a number; read a numeric string ("1", "Guest-1") too, just in case.
                val sp = (o["speaker"] as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.contentOrNull?.filter { it.isDigit() }?.toIntOrNull() }
                TranscriptSegment(start + s, start + e, text, sp)
            }
            if (segments.isNotEmpty()) return segments
            val text = root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            return if (text.isEmpty()) emptyList() else listOf(TranscriptSegment(start, end, text))
        }
    }
}
