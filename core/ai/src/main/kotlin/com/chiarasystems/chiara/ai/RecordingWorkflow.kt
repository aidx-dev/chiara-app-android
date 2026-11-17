package com.chiarasystems.chiara.ai

import com.chiarasystems.chiara.network.ApiError
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runs one recogniser over a recording's audio file. The cloud one ([TranscriptionModel.Mai]) throws [CloudTranscriberError]. */
fun interface Transcribers {
    suspend fun transcribe(model: TranscriptionModel, audio: java.io.File, languageCode: String, diarize: Boolean, progress: (Double) -> Unit): List<TranscriptSegment>
}

/** Translates a catalog key (the Spanish text) with its arguments. */
fun interface Translate { fun tr(key: String, args: List<Any>): String }

/** An on-device recogniser (Phase 6: sherpa-onnx). */
interface LocalAsr {
    suspend fun transcribe(source: PcmSource, model: TranscriptionModel, languageCode: String, progress: (Double) -> Unit): List<TranscriptSegment>
}

/** The on-device recogniser asked for is not installed (or this device cannot run it). */
class LocalModelUnavailable : Exception("local model unavailable")

sealed class WorkflowError : Exception() {
    data object NoAudio : WorkflowError()
    data object NothingRecognised : WorkflowError()
}

/** What reviewing the live transcript in the cloud came to. */
sealed interface ReviewOutcome {
    /** The transcript is MAI-Transcribe's now. */
    data object Done : ReviewOutcome
    /** No cloud AI for this account (or no audio): the live transcript stays, nothing to retry. */
    data object Unavailable : ReviewOutcome
    /** It could not be done now; the live transcript stays and it is tried again. The message is for the person. */
    data class Failed(val message: String) : ReviewOutcome
}

/** What writing the report came to. */
sealed interface ReportOutcome {
    data object Done : ReportOutcome
    /** It cannot be written as things are (no cloud AI, key refused, no spend left, nothing clinical): retrying would fail the same way. */
    data class Refused(val message: String) : ReportOutcome
    /** It could not be written now (network, models busy): it is tried again. */
    data class Failed(val message: String) : ReportOutcome
}

/** What can be done to a saved recording: transcribe it from its audio, and write its report. Results go to the store. */
class RecordingWorkflow(
    val recording: Recording,
    private val store: RecordingStore,
    private val transcribers: Transcribers,
    private val chat: OpenRouterChat,
    private val translate: Translate = Translate { key, args -> if (args.isEmpty()) key else String.format(key, *args.toTypedArray()) },
    private val appLanguage: () -> String = { "es" },
) {
    private val _busy = MutableStateFlow<String?>(null)
    /** What is running now, for the progress line; null when idle. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    var problem: String? = null
        private set
    /** The error the last step ended with, for whoever must tell a failure worth retrying from one that is not. */
    var lastError: Throwable? = null
        private set
    /** Told how far a long step is, 0..1. */
    var onProgress: ((Double) -> Unit)? = null

    private fun tr(key: String, vararg args: Any) = translate.tr(key, args.toList())
    val hasAudio: Boolean get() = store.hasAudio(recording)
    private val audioLanguage: String get() = recording.languageCode ?: appLanguage()

    /** Transcribes the audio with [model] (or the recording's own) and keeps the model used. [diarize] null keeps what was asked. */
    suspend fun transcribeFromAudio(model: TranscriptionModel? = null, diarize: Boolean? = null) {
        val file = store.audioFile(recording)?.takeIf { hasAudio } ?: return
        val m = model ?: recording.transcriptionModel
        val d = m.canDiarize && (diarize ?: recording.diarize)
        run(tr("Transcribiendo el audio con %s…", m.label)) {
            val segments = transcribers.transcribe(m, file, audioLanguage, d) { seconds ->
                _busy.value = tr("Transcribiendo el audio… %d min", (seconds / 60).toInt())
                onProgress?.invoke(seconds / maxOf(recording.durationSeconds, 1.0))
            }
            if (segments.isEmpty()) throw WorkflowError.NothingRecognised
            recording.transcriptionModel = m
            recording.diarize = d
            recording.segments = segments
            recording.status = RecordingStatus.Transcribed
            // A failure of the automatic queue no longer applies once it is transcribed by hand.
            if (recording.pipelineStage == PipelineStage.Failed) { recording.pipelineStage = PipelineStage.Done; recording.pipelineMessage = null }
        }
    }

    /** Writes the transcript again from the audio with MAI-Transcribe 2, replacing the live one only when it worked. */
    suspend fun reviewWithCloud(): ReviewOutcome {
        val file = store.audioFile(recording)?.takeIf { hasAudio } ?: return ReviewOutcome.Unavailable
        var outcome: ReviewOutcome = ReviewOutcome.Failed(tr("No he podido revisar la transcripción."))
        run(tr("Revisando la transcripción…")) {
            val segments = try {
                transcribers.transcribe(TranscriptionModel.Mai, file, audioLanguage, recording.diarize) { seconds ->
                    onProgress?.invoke(seconds / maxOf(recording.durationSeconds, 1.0))
                }
            } catch (e: CloudTranscriberError.NotAvailable) { outcome = ReviewOutcome.Unavailable; return@run }
            if (segments.isEmpty()) throw WorkflowError.NothingRecognised
            recording.segments = segments
            recording.transcriptionModel = TranscriptionModel.Mai
            recording.status = RecordingStatus.Transcribed
            outcome = ReviewOutcome.Done
        }
        val p = problem
        return if (outcome is ReviewOutcome.Failed && p != null) ReviewOutcome.Failed(p) else outcome
    }

    /** Writes the report with the template, language and sex the recording asked for. Run by the queue, in the background. */
    suspend fun generatePendingReport(progressLine: ((String) -> Unit)? = null): ReportOutcome {
        val template = ReportTemplates.resolve(recording.reportTemplateId)
        generateReport(template, recording.reportLanguage ?: "Español", recording.reportPatientSex, progressLine)
        if (lastError == null && recording.report != null) return ReportOutcome.Done
        val message = problem ?: tr("No he podido redactar el informe.")
        return when (val e = lastError) {
            is ChatError.NotAvailable, is ReportGeneratorError.NoContent -> ReportOutcome.Refused(message)
            is ChatError.Rejected -> if (e.status in setOf(401, 402, 403)) ReportOutcome.Refused(message) else ReportOutcome.Failed(message)
            else -> ReportOutcome.Failed(message)
        }
    }

    suspend fun generateReport(template: ReportTemplate, language: String, patientSex: String?, progressLine: ((String) -> Unit)? = null) {
        if (recording.segments.isEmpty()) return
        run(tr("Preparando el informe…")) {
            val target = chat.target()
            val generator = ReportGenerator({ system, user, maxTokens -> chat.complete(system, user, maxTokens, target) })
            val report = generator.generate(recording.reportLines { tr("Hablante %d", it) }, template, language, patientSex) { step ->
                val line = when (step) {
                    is ReportProgress.Summarizing -> if (step.of > 1) tr("Resumiendo la consulta… (%d de %d)", step.block, step.of) else tr("Resumiendo la consulta…")
                    is ReportProgress.Writing -> tr("Redactando el informe… (%d de %d)", step.section, step.of)
                    is ReportProgress.Reviewing -> tr("Revisando el informe… (%d de %d)", step.section, step.of)
                }
                _busy.value = line
                progressLine?.invoke(line)
                onProgress?.invoke(step.fraction)
            }
            recording.report = report
            recording.reportTemplateId = template.id
        }
    }

    private suspend fun run(label: String, work: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = label
        problem = null
        lastError = null
        try {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { lastError = e; throw e }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            problem = describe(e)
        } finally {
            _busy.value = null
            store.save(recording)
        }
    }

    /** The message for the person, by kind of failure. */
    private fun describe(e: Throwable): String = when {
        e is WorkflowError.NoAudio -> tr("El audio de esta grabación ya no está.")
        e is WorkflowError.NothingRecognised -> tr("No se ha reconocido ninguna palabra en el audio.")
        e is LocalModelUnavailable -> tr("El reconocimiento de voz del dispositivo no está disponible. Usa la transcripción en la nube.")
        e is CloudTranscriberError.NotAvailable -> tr("Tu cuenta no tiene transcripción en la nube. Pide a tu administrador una clave de IA o usa otro motor.")
        e is CloudTranscriberError.Rejected && (e.status == 401 || e.status == 403) -> tr("OpenRouter no ha aceptado tu clave de IA. Pide a tu administrador que la revise.")
        e is CloudTranscriberError.Rejected && e.status == 402 -> tr("Se ha agotado el saldo de IA de tu cuenta. Pide a tu administrador que lo amplíe.")
        e is CloudTranscriberError.Rejected -> tr("El servicio de transcripción en la nube no ha respondido (%d). Inténtalo más tarde.", e.status)
        e is ChatError.NotAvailable -> tr("Tu cuenta no tiene IA en la nube para redactar informes. Pide a tu administrador una clave de IA.")
        e is ChatError.Rejected && (e.status == 401 || e.status == 403) -> tr("OpenRouter no ha aceptado tu clave de IA. Pide a tu administrador que la revise.")
        e is ChatError.Rejected && e.status == 402 -> tr("Se ha agotado el saldo de IA de tu cuenta. Pide a tu administrador que lo amplíe.")
        e is ChatError.Rejected -> tr("Ningún modelo de IA ha podido redactar el informe (%d). Inténtalo más tarde.", e.status)
        e is ChatError.Unreadable -> tr("La IA ha devuelto una respuesta que no se puede leer. Inténtalo de nuevo.")
        e is ReportGeneratorError.NoContent -> tr("La transcripción no tiene información clínica suficiente para redactar un informe.")
        e is ApiError && e.isConnectivity -> tr("Sin conexión a internet. Hace falta conexión para usar la IA en la nube.")
        e is IOException -> tr("Sin conexión a internet. Hace falta conexión para usar la IA en la nube.")
        else -> tr("No he podido completar la operación.")
    }
}
