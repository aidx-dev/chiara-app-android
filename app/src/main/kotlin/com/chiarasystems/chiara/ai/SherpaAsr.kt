package com.chiarasystems.chiara.ai

import com.chiarasystems.chiara.ai.FrameRange
import com.chiarasystems.chiara.ai.AudioChunker
import com.chiarasystems.chiara.ai.LocalAsr
import com.chiarasystems.chiara.ai.LocalModelUnavailable
import com.chiarasystems.chiara.ai.PcmSource
import com.chiarasystems.chiara.ai.TokenSegmenter
import com.chiarasystems.chiara.ai.TranscriptSegment
import com.chiarasystems.chiara.ai.TranscriptionModel
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device speech recognition with sherpa-onnx running a NeMo transducer (Parakeet TDT 0.6B v3 int8): the audio never leaves
 * the phone. The model files are installed by the person in Settings (about 500 MB). The recording is decoded in stretches of
 * about 25 s cut at quiet moments (the model is trained on short utterances) and the recogniser is freed afterwards: it takes
 * hundreds of megabytes.
 */
class SherpaAsr(private val models: LocalModels, private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) : LocalAsr {
    private val lock = Mutex()

    private fun createRecognizer(): OfflineRecognizer {
        if (!models.asrInstalled.value) throw LocalModelUnavailable()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.asrEncoder.path, decoder = models.asrDecoder.path, joiner = models.asrJoiner.path,
                ),
                tokens = models.asrTokens.path, numThreads = threads, modelType = "nemo_transducer",
            ),
        )
        return OfflineRecognizer(config = config)
    }

    override suspend fun transcribe(source: PcmSource, model: TranscriptionModel, languageCode: String, progress: (Double) -> Unit): List<TranscriptSegment> =
        lock.withLock {
            withContext(Dispatchers.Default) {
                val recognizer = try { createRecognizer() } catch (e: UnsatisfiedLinkError) { throw LocalModelUnavailable() }
                try {
                    val seconds = source.frames.toDouble() / source.sampleRate
                    val parts = maxOf(1, Math.ceil(seconds / CHUNK_SECONDS).toInt())
                    val out = mutableListOf<TranscriptSegment>()
                    val ranges = AudioChunker.split(source, FrameRange(0, source.frames), parts)
                    ranges.forEachIndexed { i, range ->
                        currentCoroutineContext().ensureActive()
                        out += decode(recognizer, source, range)
                        progress((i + 1).toDouble() / ranges.size)
                    }
                    out
                } finally { recognizer.release() }
            }
        }

    private fun decode(recognizer: OfflineRecognizer, source: PcmSource, range: FrameRange): List<TranscriptSegment> {
        val offset = range.start.toDouble() / source.sampleRate
        val samples = source.read(range.start, range.length.toInt())
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(floats, source.sampleRate)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return TokenSegmenter.segments(r.text, r.tokens.toList(), r.timestamps.toList(), offset, range.length.toDouble() / source.sampleRate)
        } finally { stream.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SECONDS = 25.0
    }
}
