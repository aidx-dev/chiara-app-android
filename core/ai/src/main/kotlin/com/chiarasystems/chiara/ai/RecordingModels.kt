package com.chiarasystems.chiara.ai

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingStatus {
    /** Being captured now. */
    Recording,
    /** An imported audio file that has not been transcribed yet. */
    Imported,
    /** The app was closed or crashed while recording; the audio saved so far can still be transcribed. */
    Interrupted,
    Transcribed,
}

/** Where a recording is in the automatic transcription that runs after it is recorded or imported. */
enum class PipelineStage {
    Queued, Transcribing,
    /** A recording's live transcript being written again from its audio by MAI-Transcribe 2. */
    Reviewing,
    /** The report being written from the transcript. */
    WritingReport,
    Done, Failed;

    /** Waiting or running: not finished, not failed. */
    val isActive: Boolean get() = this != Done && this != Failed

    /**
     * Translation key (Spanish source text) of what to show under a recording while it is processed or when it failed;
     * null when there is nothing to say. [message] wins for Queued and Failed when present.
     */
    fun statusLineKey(message: String?): String? = when (this) {
        Queued -> message ?: "En cola…"
        Transcribing -> "Transcribiendo…"
        Reviewing -> "Revisando la transcripción…"
        WritingReport -> "Redactando el informe…"
        Failed -> message ?: "No se ha podido transcribir."
        Done -> null
    }
}

/**
 * The recogniser a recording's audio is transcribed with. [Fast] and [Parakeet] run on the device; [Mai] (MAI-Transcribe 2)
 * runs in the cloud and the audio leaves it.
 */
enum class TranscriptionModel(val raw: String, val label: String, val detailKey: String) {
    Fast("fast", "Rápido", "Rápido. Reconocedor ligero del dispositivo."),
    Parakeet("parakeet", "Parakeet v3", "Más preciso. La primera vez descarga unos 500 MB."),
    Mai("mai", "MAI-Transcribe 2", "En la nube: el audio se envía a Microsoft (Azure) a través de OpenRouter, sin que lo guarden. Necesita conexión.");

    /** The audio leaves the device to be transcribed. */
    val isCloud: Boolean get() = this == Mai
    /** Can tell the voices apart while it transcribes. */
    val canDiarize: Boolean get() = this == Mai

    companion object { fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Fast }
}

/** A consultation recorded on the device. Local only, never sent to Supabase. */
class Recording(
    val id: String,
    /** The signed-in user it belongs to: each user only sees their own recordings. */
    var ownerId: String?,
    var patientId: String?,
    var patientName: String,
    var startedAtMillis: Long,
    /** File name inside the store's audio directory; null once the audio is deleted. */
    var audioFileName: String?,
) {
    var durationSeconds: Double = 0.0
    var status: RecordingStatus = RecordingStatus.Recording
    var segments: List<TranscriptSegment> = emptyList()
    /** Null for a recording made with the app; the file's name for an imported one. */
    var importedFileName: String? = null
    /** Language the people speak in the audio ("es", "en"…). Null: the app's. */
    var languageCode: String? = null
    var pipelineStage: PipelineStage? = null
    var pipelineMessage: String? = null
    /** How many times it failed; it is retried on its own a few times, not forever. */
    var pipelineAttempts: Int = 0
    var transcriptionModel: TranscriptionModel = TranscriptionModel.Fast
    var diarize: Boolean = false
    /** Recorded with the app: its live transcript is to be written again from the audio by MAI-Transcribe 2. */
    var cloudReviewPending: Boolean = false
    var report: GeneratedReport? = null
    var reportTemplateId: String? = null
    /** A report was asked for and is waiting in the queue. */
    var reportPending: Boolean = false
    var reportLanguage: String? = null
    var reportPatientSex: String? = null
    var reportProblem: String? = null

    val isImported: Boolean get() = importedFileName != null
    val transcriptText: String get() = segments.joinToString(" ") { it.text }
    val hasSpeakers: Boolean get() = segments.any { it.speaker != null }

    /** The transcript as the report reads it: one sentence a line, with who said it when the voices were told apart. */
    fun reportLines(speakerLabel: (Int) -> String = { "Hablante $it" }): List<String> =
        segments.map { s -> (s.speaker?.let { speakerLabel(it + 1) + ": " } ?: "") + s.text }
}

/**
 * Recordings are mutated in place, so a list of the same instances is `equals` to the previous one and a StateFlow would not
 * emit. This wrapper is never equal to anything: every save publishes, and the UI redraws.
 */
class FreshList<T>(private val items: List<T>) : List<T> by items {
    override fun equals(other: Any?) = false
    override fun hashCode() = System.identityHashCode(this)
}

/** The local database of recorded consultations plus the audio files beside it. Implemented with Room in the app. */
interface RecordingStore {
    /** The signed-in user's recordings, newest first. */
    val recordings: StateFlow<List<Recording>>
    val ownerId: String?
    fun setOwner(id: String?)
    fun recording(id: String): Recording?
    fun recordings(patientId: String): List<Recording> = recordings.value.filter { it.patientId == patientId }
    fun create(patientId: String?, patientName: String): Recording
    fun createImported(patientId: String?, patientName: String, fileName: String, dateMillis: Long): Recording
    fun save(recording: Recording)
    fun audioFile(recording: Recording): File?
    fun hasAudio(recording: Recording): Boolean = audioFile(recording)?.exists() == true
    /** Deletes only the audio, keeping the transcript. */
    fun deleteAudio(recording: Recording)
    fun delete(recording: Recording)
    /** All of the signed-in user's recordings, with their audio. Other users' are left alone. */
    fun deleteAll()
}

/** Rows in memory with the audio in [directory]; for tests and as the fallback when the database cannot be opened. */
class InMemoryRecordingStore(private val directory: File, private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() }) : RecordingStore {
    private val rows = mutableListOf<Recording>()
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    override val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()
    override var ownerId: String? = null
        private set

    init { directory.mkdirs() }

    /** Switches to a user. Recordings made before ownership existed belong to the first user who signs in. */
    override fun setOwner(id: String?) {
        ownerId = id
        if (id != null) rows.filter { it.ownerId == null }.forEach { it.ownerId = id }
        reload()
    }

    private fun reload() { _recordings.value = FreshList(rows.filter { ownerId != null && it.ownerId == ownerId }.sortedByDescending { it.startedAtMillis }) }
    override fun recording(id: String) = _recordings.value.firstOrNull { it.id == id }

    override fun create(patientId: String?, patientName: String): Recording =
        Recording(idGenerator(), ownerId, patientId, patientName, System.currentTimeMillis(), "${idGenerator()}.m4a").also { rows += it; reload() }

    override fun createImported(patientId: String?, patientName: String, fileName: String, dateMillis: Long): Recording =
        Recording(idGenerator(), ownerId, patientId, patientName, dateMillis, "${idGenerator()}.m4a").also {
            it.importedFileName = fileName; it.status = RecordingStatus.Imported; rows += it; reload()
        }

    override fun save(recording: Recording) { reload() }
    override fun audioFile(recording: Recording) = recording.audioFileName?.let { File(directory, it) }
    override fun deleteAudio(recording: Recording) { audioFile(recording)?.delete(); recording.audioFileName = null; save(recording) }
    override fun delete(recording: Recording) { audioFile(recording)?.delete(); rows.remove(recording); reload() }
    override fun deleteAll() { recordings.value.toList().forEach { audioFile(it)?.delete(); rows.remove(it) }; reload() }
}
