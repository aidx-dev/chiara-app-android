package com.chiarasystems.chiara.recording

import android.content.Context
import com.chiarasystems.chiara.ai.CloudTranscriber
import com.chiarasystems.chiara.ai.LocalAsr
import com.chiarasystems.chiara.ai.LocalModelUnavailable
import com.chiarasystems.chiara.ai.PcmSource
import com.chiarasystems.chiara.ai.TranscriptSegment
import com.chiarasystems.chiara.ai.TranscriptionModel
import com.chiarasystems.chiara.ai.Transcribers
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Placeholder until the on-device recognisers (sherpa-onnx, Phase 6) are bundled. */
object NoLocalAsr : LocalAsr {
    override suspend fun transcribe(source: PcmSource, model: TranscriptionModel, languageCode: String, progress: (Double) -> Unit): List<TranscriptSegment> =
        throw LocalModelUnavailable()
}

/** Decodes the recording once to PCM and hands it to the recogniser of the chosen [TranscriptionModel]. */
class AppTranscribers(private val context: Context, private val cloud: CloudTranscriber, private val local: () -> LocalAsr) : Transcribers {
    override suspend fun transcribe(model: TranscriptionModel, audio: File, languageCode: String, diarize: Boolean, progress: (Double) -> Unit): List<TranscriptSegment> {
        val source = withContext(Dispatchers.IO) { AudioDecoder.toPcmSource(audio, context.cacheDir) }
        try {
            return when (model) {
                TranscriptionModel.Mai -> cloud.transcribe(source, languageCode, diarize) { progress(it) }
                else -> local().transcribe(source, model, languageCode) { progress(it) }
            }
        } finally { source.close() }
    }
}
