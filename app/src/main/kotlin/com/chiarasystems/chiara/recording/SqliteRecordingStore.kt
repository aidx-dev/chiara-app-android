package com.chiarasystems.chiara.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chiarasystems.chiara.ai.GeneratedReport
import com.chiarasystems.chiara.ai.PipelineStage
import com.chiarasystems.chiara.ai.Recording
import com.chiarasystems.chiara.ai.RecordingStatus
import com.chiarasystems.chiara.ai.RecordingStore
import com.chiarasystems.chiara.ai.TranscriptSegment
import com.chiarasystems.chiara.ai.TranscriptionModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local database of recorded consultations (SQLite, its own file under `noBackupFilesDir`) plus the audio files beside it.
 * Nothing here goes to Supabase. Each recording belongs to the user who made it: signing out keeps everything on the
 * device, but only the signed-in user's recordings are visible. Port of iOS `RecordingStore`.
 */
class SqliteRecordingStore(context: Context, root: File = File(context.noBackupFilesDir, "RawRecordings")) : RecordingStore {
    private val audioDir = File(root, "Audio").apply { mkdirs() }
    private val helper = object : SQLiteOpenHelper(context, File(root, "recordings.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE recordings (
                    id TEXT PRIMARY KEY, owner_id TEXT, patient_id TEXT, patient_name TEXT NOT NULL, started_at INTEGER NOT NULL,
                    duration REAL NOT NULL, status TEXT NOT NULL, audio_file TEXT, transcript TEXT NOT NULL, imported_name TEXT,
                    language TEXT, stage TEXT, message TEXT, attempts INTEGER NOT NULL, model TEXT NOT NULL, diarize INTEGER NOT NULL,
                    cloud_review INTEGER NOT NULL, report TEXT, report_template TEXT, report_pending INTEGER NOT NULL,
                    report_language TEXT, report_sex TEXT, report_problem TEXT)""",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val segmentsSerializer = ListSerializer(TranscriptSegment.serializer())

    private val all = mutableListOf<Recording>()
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    override val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()
    override var ownerId: String? = null
        private set

    init {
        helper.readableDatabase.rawQuery("SELECT * FROM recordings", null).use { c ->
            while (c.moveToNext()) all += read(c)
        }
        // A row still marked `recording` when the app starts belongs to a session that never finished.
        all.filter { it.status == RecordingStatus.Recording }.forEach { it.status = RecordingStatus.Interrupted; save(it) }
    }

    private fun read(c: android.database.Cursor): Recording {
        fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name))
        fun i(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
        val r = Recording(s("id"), s("owner_id"), s("patient_id"), s("patient_name"), c.getLong(c.getColumnIndexOrThrow("started_at")), s("audio_file"))
        r.durationSeconds = c.getDouble(c.getColumnIndexOrThrow("duration"))
        r.status = runCatching { RecordingStatus.valueOf(s("status")) }.getOrDefault(RecordingStatus.Interrupted)
        r.segments = runCatching { json.decodeFromString(segmentsSerializer, s("transcript")) }.getOrDefault(emptyList())
        r.importedFileName = s("imported_name")
        r.languageCode = s("language")
        r.pipelineStage = s("stage")?.let { runCatching { PipelineStage.valueOf(it) }.getOrNull() }
        r.pipelineMessage = s("message")
        r.pipelineAttempts = i("attempts")
        r.transcriptionModel = TranscriptionModel.from(s("model"))
        r.diarize = i("diarize") != 0
        r.cloudReviewPending = i("cloud_review") != 0
        r.report = s("report")?.let { runCatching { json.decodeFromString(GeneratedReport.serializer(), it) }.getOrNull() }
        r.reportTemplateId = s("report_template")
        r.reportPending = i("report_pending") != 0
        r.reportLanguage = s("report_language")
        r.reportPatientSex = s("report_sex")
        r.reportProblem = s("report_problem")
        return r
    }

    private fun values(r: Recording) = ContentValues().apply {
        put("id", r.id); put("owner_id", r.ownerId); put("patient_id", r.patientId); put("patient_name", r.patientName)
        put("started_at", r.startedAtMillis); put("duration", r.durationSeconds); put("status", r.status.name)
        put("audio_file", r.audioFileName); put("transcript", json.encodeToString(segmentsSerializer, r.segments))
        put("imported_name", r.importedFileName); put("language", r.languageCode); put("stage", r.pipelineStage?.name)
        put("message", r.pipelineMessage); put("attempts", r.pipelineAttempts); put("model", r.transcriptionModel.raw)
        put("diarize", if (r.diarize) 1 else 0); put("cloud_review", if (r.cloudReviewPending) 1 else 0)
        put("report", r.report?.let { json.encodeToString(GeneratedReport.serializer(), it) })
        put("report_template", r.reportTemplateId); put("report_pending", if (r.reportPending) 1 else 0)
        put("report_language", r.reportLanguage); put("report_sex", r.reportPatientSex); put("report_problem", r.reportProblem)
    }

    private fun reload() { _recordings.value = com.chiarasystems.chiara.ai.FreshList(all.filter { ownerId != null && it.ownerId == ownerId }.sortedByDescending { it.startedAtMillis }) }

    /** Recordings made before ownership existed belong to the first user who signs in. */
    @Synchronized override fun setOwner(id: String?) {
        ownerId = id
        if (id != null) all.filter { it.ownerId == null }.forEach { it.ownerId = id; save(it) }
        reload()
    }

    override fun recording(id: String) = _recordings.value.firstOrNull { it.id == id }

    @Synchronized override fun create(patientId: String?, patientName: String): Recording =
        Recording(UUID.randomUUID().toString(), ownerId, patientId, patientName, System.currentTimeMillis(), "${UUID.randomUUID()}.aac").also { insert(it) }

    @Synchronized override fun createImported(patientId: String?, patientName: String, fileName: String, dateMillis: Long): Recording =
        Recording(UUID.randomUUID().toString(), ownerId, patientId, patientName, dateMillis, "${UUID.randomUUID()}.aac")
            .also { it.importedFileName = fileName; it.status = RecordingStatus.Imported; insert(it) }

    private fun insert(r: Recording) {
        all += r
        helper.writableDatabase.insertWithOnConflict("recordings", null, values(r), SQLiteDatabase.CONFLICT_REPLACE)
        reload()
    }

    @Synchronized override fun save(recording: Recording) {
        helper.writableDatabase.insertWithOnConflict("recordings", null, values(recording), SQLiteDatabase.CONFLICT_REPLACE)
        reload()
    }

    override fun audioFile(recording: Recording): File? = recording.audioFileName?.let { File(audioDir, it) }

    override fun deleteAudio(recording: Recording) { audioFile(recording)?.delete(); recording.audioFileName = null; save(recording) }

    @Synchronized override fun delete(recording: Recording) {
        audioFile(recording)?.delete()
        all.remove(recording)
        helper.writableDatabase.delete("recordings", "id = ?", arrayOf(recording.id))
        reload()
    }

    @Synchronized override fun deleteAll() { _recordings.value.toList().forEach { delete(it) } }
}
