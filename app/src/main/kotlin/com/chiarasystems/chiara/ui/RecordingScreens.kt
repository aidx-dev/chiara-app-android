package com.chiarasystems.chiara.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.ai.GeneratedReport
import com.chiarasystems.chiara.ai.PipelineStage
import com.chiarasystems.chiara.ai.Recording
import com.chiarasystems.chiara.ai.RecordingStatus
import com.chiarasystems.chiara.ai.RecordingWorkflow
import com.chiarasystems.chiara.ai.ReportTemplate
import com.chiarasystems.chiara.ai.ReportTemplates
import com.chiarasystems.chiara.ai.ConsultationPipeline
import com.chiarasystems.chiara.ai.Translate
import com.chiarasystems.chiara.ai.TranscriptionModel
import com.chiarasystems.chiara.data.AppLanguage
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.GroupedList
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.LocalTranslator
import com.chiarasystems.chiara.designsystem.MenuOption
import com.chiarasystems.chiara.designsystem.MenuPicker
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.PrimaryButton
import com.chiarasystems.chiara.designsystem.GlassPillButton
import com.chiarasystems.chiara.designsystem.SectionTitle
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.AudioLanguageResolver
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import com.chiarasystems.chiara.recording.AudioDecoder
import com.chiarasystems.chiara.recording.RecordingService
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUDIO_LANGUAGE_KEY = "recording.audioLanguage"
private const val MODEL_KEY = "recording.transcriptionModel"

/** The language people speak in the audio, remembered between recordings (not necessarily the app's). */
private fun lastAudioLanguage(app: AppContainer) = app.prefs.getString(AUDIO_LANGUAGE_KEY) ?: app.settings.code

/** "4:05", or "1:02:09" past an hour. */
fun clock(seconds: Double): String {
    val total = seconds.toInt()
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun speakerName(speaker: Int) = tr("Hablante %d", speaker + 1)

@Composable
private fun Warning(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = Palette.warningFg, modifier = Modifier.size(16.dp))
        Text(text, Modifier.padding(start = 6.dp), fontSize = 14.sp, color = Palette.warningFg)
    }
}

@Composable
private fun Experimental(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Science, null, tint = Palette.warningFg, modifier = Modifier.size(14.dp))
        Text(tr("Función experimental"), Modifier.padding(start = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.warningFg)
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.weight(1f).padding(end = 12.dp), fontSize = 16.sp, color = Palette.text)
        Switch(checked, onChange)
    }
}

/** "Idioma del audio" picker: es / en / de / fr, shown in their own language. */
@Composable
fun AudioLanguageRow(code: String, onChange: (String) -> Unit) {
    MenuPicker(
        tr("Idioma del audio"), code, AppLanguage.supported.map { MenuOption(it.raw, it.nativeName.orEmpty()) }, onChange,
    )
}

// --- Record ---------------------------------------------------------------------------------------------------------

@Composable
fun RecordConsultationSheet(app: AppContainer, patient: Patient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = app.recorder
    val state by recorder.state.collectAsState()
    val elapsed by recorder.elapsed.collectAsState()
    val level by recorder.level.collectAsState()
    val failure by recorder.failure.collectAsState()
    val initialLanguage = remember { AudioLanguageResolver.resolve(patient?.language, lastAudioLanguage(app)).code }
    var language by remember { mutableStateOf(initialLanguage) }
    var consent by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val active = state != com.chiarasystems.chiara.recording.ConsultationRecorder.State.Idle
    val noPatient = tr("Sin paciente")
    val saved = tr("Consulta guardada.")
    val savedReviewing = tr("Consulta guardada. Se está transcribiendo…")
    val noMic = tr("No se pudo abrir el micrófono. Comprueba el permiso de la app.")

    fun begin() {
        val rec = app.recordings.create(patient?.id, patient?.fullName ?: noPatient)
        val file = app.recordings.audioFile(rec)
        if (file == null || !recorder.start(file)) { app.recordings.delete(rec); problem = noMic; return }
        rec.languageCode = language
        if (language != initialLanguage) app.prefs.putString(AUDIO_LANGUAGE_KEY, language)
        app.recordings.save(rec)
        app.activeRecordingId = rec.id
        RecordingService.start(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) begin() else problem = noMic }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start() {
        problem = null
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun finish() {
        val id = app.activeRecordingId ?: return
        val rec = app.recordings.recording(id)
        val seconds = recorder.stop()
        RecordingService.stop(context)
        app.activeRecordingId = null
        if (rec == null) { app.router.dismissSheet(); return }
        rec.durationSeconds = seconds
        // Without a live transcript the audio is transcribed afterwards, by itself.
        rec.status = RecordingStatus.Imported
        scope.launch {
            // With a cloud AI key the transcript is written straight by MAI-Transcribe 2; without it, on the device.
            val cloud = runCatching { app.aiConfig().apiKey?.isNotEmpty() == true }.getOrDefault(false)
            rec.transcriptionModel = if (cloud) TranscriptionModel.Mai else TranscriptionModel.Fast
            app.recordings.save(rec)
            app.pipeline.enqueue(rec)
            app.toast.show(if (app.prefs.getString(AppContainer.AUTO_PROCESS_KEY) != "false") savedReviewing else saved)
            app.router.dismissSheet()
        }
    }

    fun discard() {
        recorder.stop()
        RecordingService.stop(context)
        app.activeRecordingId?.let { id -> app.recordings.recording(id)?.let(app.recordings::delete) }
        app.activeRecordingId = null
        app.router.dismissSheet()
    }

    AppSheet(
        tr("Grabar consulta"), onClose = { if (active) confirmDiscard = true else app.router.dismissSheet() },
        dismissible = !active,
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (patient != null) Text(patient.fullName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            if (!active) {
                Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsentRow(consent, { consent = it }, tr("El paciente ha dado su consentimiento para grabar la consulta y transcribirla."))
                    AudioLanguageRow(language) { language = it }
                    if (language == initialLanguage && AudioLanguageResolver.codeFromPatientLanguage(patient?.language) == language) {
                        Text(tr("Es el idioma de la ficha del paciente."), fontSize = 12.sp, color = Palette.textFaint)
                    }
                    Text(
                        tr("Al terminar, el audio se transcribe y la transcripción se guarda solo aquí. Si tu cuenta tiene IA en la nube, el audio se envía a MAI-Transcribe 2 (Microsoft, a través de OpenRouter, sin que lo guarden). Puedes bloquear la pantalla: la grabación y el procesado continúan."),
                        fontSize = 13.sp, color = Palette.textMuted,
                    )
                    Experimental()
                }
            } else {
                Column(Modifier.fillMaxWidth().surface().padding(16.dp)) {
                    Text(tr("Grabando… El texto aparecerá al terminar."), fontSize = 16.sp, color = Palette.textFaint)
                }
            }
            problem?.let { Text(it, fontSize = 14.sp, color = Palette.destructive) }
            if (failure != null) Text(tr("La grabación se ha interrumpido."), fontSize = 14.sp, color = Palette.destructive)
        }
        Column(Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.94f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (active) {
                Text(clock(elapsed), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = Palette.text)
                Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.field, CircleShape)) {
                    Box(Modifier.fillMaxWidth(maxOf(0.02f, level)).height(6.dp).background(Palette.primary, CircleShape))
                }
                if (state == com.chiarasystems.chiara.recording.ConsultationRecorder.State.Paused) Text(tr("En pausa"), fontSize = 14.sp, color = Palette.warningFg)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        val paused = state == com.chiarasystems.chiara.recording.ConsultationRecorder.State.Paused
                        GlassPillButton(if (paused) tr("Reanudar") else tr("Pausar"), onClick = { if (paused) recorder.resume() else recorder.pause() })
                    }
                    Box(Modifier.weight(1f)) { PrimaryButton(tr("Terminar"), onClick = ::finish, tint = Palette.destructive) }
                }
            } else {
                PrimaryButton(tr("Empezar a grabar"), onClick = ::start, enabled = consent)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(tr("¿Descartar la grabación?")) },
            confirmButton = { TextButton({ confirmDiscard = false; discard() }) { Text(tr("Descartar"), color = Palette.destructive) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(tr("Seguir grabando")) } },
        )
    }
}

// --- Import ---------------------------------------------------------------------------------------------------------

@Composable
fun ImportAudioSheet(app: AppContainer, patient: Patient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialLanguage = remember { AudioLanguageResolver.resolve(patient?.language, lastAudioLanguage(app)).code }
    var language by remember { mutableStateOf(initialLanguage) }
    var consent by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(TranscriptionModel.from(app.prefs.getString(MODEL_KEY))) }
    var diarize by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val noPatient = tr("Sin paciente")
    val imported = tr("Audio importado. Se está transcribiendo; te avisaremos al terminar.")
    val importedOff = tr("Audio importado.")
    val unreadable = tr("No he podido leer el audio de este archivo. Prueba con otro formato (m4a, mp3, wav).")

    fun process(uri: Uri) {
        problem = null
        scope.launch {
            working = true
            val (name, date) = withContext(Dispatchers.IO) {
                var n = "audio"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) n = c.getString(0) ?: n }
                n to System.currentTimeMillis()
            }
            val rec = app.recordings.createImported(patient?.id, patient?.fullName ?: noPatient, name, date)
            rec.languageCode = language
            if (language != initialLanguage) app.prefs.putString(AUDIO_LANGUAGE_KEY, language)
            rec.transcriptionModel = model
            rec.diarize = model.canDiarize && diarize
            if (!model.isCloud) app.prefs.putString(MODEL_KEY, model.raw)
            val destination = app.recordings.audioFile(rec)!!
            try {
                rec.durationSeconds = withContext(Dispatchers.IO) { AudioDecoder.importInto(context, uri, destination) }
                app.recordings.save(rec)
            } catch (e: kotlinx.coroutines.CancellationException) {
                app.recordings.delete(rec); throw e
            } catch (e: Exception) {
                app.recordings.delete(rec); working = false; problem = unreadable; return@launch
            }
            working = false
            app.pipeline.enqueue(rec)
            app.toast.show(if (app.prefs.getString(AppContainer.AUTO_PROCESS_KEY) != "false") imported else importedOff)
            app.router.dismissSheet()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) process(uri) }

    AppSheet(tr("Importar audio"), onClose = { if (!working) app.router.dismissSheet() }, dismissible = !working) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(patient?.fullName ?: noPatient, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            if (working) {
                Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Preparando el audio…"), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.text)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsentRow(
                        consent, { consent = it },
                        if (model.isCloud) tr("El paciente ha dado su consentimiento para que esta grabación se transcriba en la nube.") else tr("El paciente ha dado su consentimiento para procesar esta grabación."),
                    )
                    AudioLanguageRow(language) { language = it }
                    if (language == initialLanguage && AudioLanguageResolver.codeFromPatientLanguage(patient?.language) == language) {
                        Text(tr("Es el idioma de la ficha del paciente."), fontSize = 12.sp, color = Palette.textFaint)
                    }
                    TranscriptionModelRow(model) { model = it; if (it.isCloud) consent = false }
                    if (model.canDiarize) SpeakersToggle(diarize) { diarize = it }
                    if (model.isCloud) Warning(tr("El audio de esta consulta saldrá del dispositivo para transcribirse."))
                    Text(
                        if (model.isCloud) tr("Se admiten archivos de audio y de vídeo (solo se usa el sonido). La conversión se hace en este dispositivo; el audio convertido se envía para transcribirlo y el resultado se guarda solo aquí.")
                        else tr("Se admiten archivos de audio y de vídeo (solo se usa el sonido). La conversión y la transcripción se hacen en este dispositivo; nada se envía a ningún servidor."),
                        fontSize = 13.sp, color = Palette.textMuted,
                    )
                    Experimental()
                }
            }
            problem?.let { Text(it, fontSize = 14.sp, color = Palette.destructive) }
        }
        if (!working) {
            Box(Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.94f)).padding(16.dp)) {
                PrimaryButton(tr("Elegir archivo de audio"), onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = consent)
            }
        }
    }
}

@Composable
fun TranscriptionModelRow(model: TranscriptionModel, onChange: (TranscriptionModel) -> Unit) {
    Column {
        MenuPicker(
            tr("Reconocedor"), model,
            TranscriptionModel.entries.map { MenuOption(it, if (it.isCloud) tr("%s (nube)", it.label) else it.label) }, onChange,
        )
        Text(tr(model.detailKey), Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Palette.textFaint)
    }
}

@Composable
fun SpeakersToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        ConsentRow(on, onChange, tr("Distinguir quién habla"))
        Text(tr("Etiqueta cada frase con su hablante (Hablante 1, Hablante 2…). Los números pueden cambiar entre tramos de una grabación larga."), fontSize = 12.sp, color = Palette.textFaint)
    }
}

// --- List & rows ----------------------------------------------------------------------------------------------------

@Composable
fun recordingLength(seconds: Double) = if (seconds < 60) tr("%d s", seconds.toInt()) else tr("%d min", (seconds / 60).toInt())

@Composable
fun RecordingRow(r: Recording, modifier: Modifier = Modifier, chevron: Boolean = false) {
    val cal = calFormat()
    val date = Instant.ofEpochMilli(r.startedAtMillis).atZone(ZoneId.systemDefault())
    val line = r.pipelineStage?.statusLineKey(r.pipelineMessage)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.patientName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.text)
            Text("${cal.shortDate(date.toLocalDate())}, ${"%02d:%02d".format(date.hour, date.minute)} · ${recordingLength(r.durationSeconds)}", fontSize = 14.sp, color = Palette.textMuted)
            if (line != null) Text(tr(line), fontSize = 13.sp, color = if (r.pipelineStage == PipelineStage.Failed) Palette.warningFg else Palette.textMuted)
        }
        when {
            r.pipelineStage?.isActive == true -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            r.pipelineStage == PipelineStage.Failed || r.status == RecordingStatus.Interrupted -> Icon(Icons.Filled.Warning, null, tint = Palette.warningFg)
            r.isImported -> Icon(Icons.Outlined.FileDownload, null, tint = Palette.textFaint)
        }
    }
}

@Composable
fun RecordingsListScreen(app: AppContainer) {
    val items by app.recordings.recordings.collectAsState()
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(tr("Consultas grabadas"), onBack = { app.router.pop() }) {
            Icon(Icons.Outlined.FileDownload, tr("Importar audio"), tint = Palette.primary, modifier = Modifier.size(44.dp).clickable { app.router.show(com.chiarasystems.chiara.data.ActiveSheet.ImportAudio(null)) }.padding(10.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (items.isEmpty()) {
                Text(tr("Todavía no hay consultas grabadas."), Modifier.fillMaxWidth().surface().padding(16.dp), color = Palette.textFaint)
            } else {
                Column(Modifier.fillMaxWidth().surface()) {
                    items.forEachIndexed { i, r ->
                        if (i > 0) Hairline(inset = 16.dp)
                        RecordingRow(r, Modifier.clickable { app.router.push(Route.Recording(r.id)) })
                    }
                }
            }
        }
    }
}

// --- Detail ---------------------------------------------------------------------------------------------------------

@Composable
fun RecordingDetailScreen(app: AppContainer, id: String) {
    val items by app.recordings.recordings.collectAsState()
    val recording = items.firstOrNull { it.id == id }
    if (recording == null) { Column(Modifier.fillMaxSize()) { DetailTopBar(tr("Grabación"), { app.router.pop() }) }; return }
    // The store hands out the same object, mutated in place: a tick of the pipeline's state redraws the screen.
    val processingId by app.pipeline.processingId.collectAsState()
    val processingLine by app.pipeline.processingLine.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = processingLine to processingId
    val translator = LocalTranslator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cal = calFormat()
    val patients by app.patients.patients.collectAsState()
    val user by app.auth.user.collectAsState()
    val workflow = remember(recording.id) {
        RecordingWorkflow(recording, app.recordings, app.transcribers, app.chat, Translate { key, args -> translator.tr(key, *args.toTypedArray()) }, { app.settings.code })
    }
    val busy by workflow.busy.collectAsState()
    var version by remember { mutableStateOf(0) } // bumps to redraw after in-place mutations
    @Suppress("UNUSED_VARIABLE") val v = version
    var confirmDelete by remember { mutableStateOf(false) }
    var cloudModel by remember { mutableStateOf<TranscriptionModel?>(null) }
    var cloudDiarize by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    var templateId by remember { mutableStateOf("") }
    val processing = recording.pipelineStage?.isActive == true
    val templates = remember(user) { ReportTemplates.forSpecialties(user?.specialties.orEmpty()) }
    val chosen: ReportTemplate = templates.firstOrNull { it.id == templateId.ifEmpty { recording.reportTemplateId.orEmpty() } } ?: templates[0]
    val date = Instant.ofEpochMilli(recording.startedAtMillis).atZone(ZoneId.systemDefault())
    val head = "${recording.patientName} · ${cal.shortDate(date.toLocalDate())}, ${"%02d:%02d".format(date.hour, date.minute)}"

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, null))
    }

    fun transcribe(model: TranscriptionModel?, diarize: Boolean? = null) {
        scope.launch { workflow.transcribeFromAudio(model, diarize); version++ }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(recording.patientName, onBack = { app.router.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cal.longDayYear(date.toLocalDate()) + ", " + "%02d:%02d".format(date.hour, date.minute), fontSize = 15.sp, color = Palette.textMuted)
                Text(tr("Duración %s", clock(recording.durationSeconds)), fontSize = 15.sp, color = Palette.textMuted)
                recording.importedFileName?.let { Text(tr("Importada de %s", it), fontSize = 13.sp, color = Palette.textMuted) }
                if (recording.segments.isNotEmpty()) Text(tr("Transcrita con %s", recording.transcriptionModel.label), fontSize = 13.sp, color = Palette.textMuted)
                Experimental2()
            }
            // Status
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(it, Modifier.padding(start = 10.dp), fontSize = 15.sp, color = Palette.textMuted) } }
            workflow.problem?.let { Text(it, fontSize = 14.sp, color = Palette.destructive) }
            val stage = recording.pipelineStage
            val stageLine = stage?.statusLineKey(recording.pipelineMessage)
            if (stage != null && stageLine != null && stage != PipelineStage.Done && stage != PipelineStage.WritingReport && !(stage == PipelineStage.Failed && recording.reportPending)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (stage.isActive) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Warning, null, tint = Palette.warningFg, modifier = Modifier.size(18.dp))
                        Text(tr(stageLine), Modifier.padding(start = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (stage.isActive) Palette.textMuted else Palette.warningFg)
                    }
                    Text(
                        if (stage.isActive) tr("Se transcribe sola. Puedes salir de esta pantalla; te avisaremos al terminar.")
                        else if (recording.pipelineAttempts < ConsultationPipeline.MAX_ATTEMPTS) tr("Se reintentará sola al abrir la app.")
                        else tr("No se reintentará sola. Puedes transcribirla desde aquí abajo."),
                        fontSize = 12.sp, color = Palette.textFaint,
                    )
                }
            }
            if (stage == null && recording.status == RecordingStatus.Imported && recording.segments.isEmpty()) Warning(tr("Pendiente de transcribir. Usa «Transcribir desde el audio»."))
            if (stage == null && recording.status == RecordingStatus.Interrupted) Warning(tr("La grabación se interrumpió. Puedes transcribir el audio guardado."))

            // Transcript
            SectionTitle(tr("Transcripción"))
            Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioLanguageRow(recording.languageCode ?: app.settings.code) { code ->
                    recording.languageCode = code; app.prefs.putString(AUDIO_LANGUAGE_KEY, code); app.recordings.save(recording); version++
                }
                if (workflow.hasAudio) Text(tr("Si el idioma no era el correcto, cámbialo y usa «Transcribir de nuevo desde el audio»."), fontSize = 12.sp, color = Palette.textFaint)
            }
            Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recording.segments.isEmpty()) Text(tr("Sin transcripción"), color = Palette.textFaint)
                recording.segments.forEachIndexed { i, s ->
                    Column {
                        val sp = s.speaker
                        if (sp != null && (i == 0 || recording.segments[i - 1].speaker != sp)) {
                            Text(speakerName(sp), Modifier.padding(top = if (i == 0) 0.dp else 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (sp % 2 == 0) Palette.primary else Palette.infoFg)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(clock(s.start), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Palette.textFaint)
                            Text(s.text, fontSize = 16.sp, color = Palette.text)
                        }
                    }
                }
            }
            if (recording.segments.isNotEmpty()) {
                val plain = (listOf(head) + recording.segments.map { s -> "[${clock(s.start)}] " + (s.speaker?.let { translator.tr("Hablante %d", it + 1) + ": " } ?: "") + s.text }).joinToString("\n")
                ShareLine(tr("Copiar o compartir transcripción")) { share(plain) }
            }

            // Report
            SectionTitle(tr("Informe"))
            Column(Modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (templates.size > 1) {
                    MenuPicker(tr("Plantilla"), chosen.id, templates.map { MenuOption(it.id, templateLabel(it)) }, { templateId = it })
                } else Text(templateLabel(chosen), fontSize = 15.sp, color = Palette.textMuted)
                val can = recording.segments.isNotEmpty() && busy == null && !processing
                PrimaryButton(if (recording.report == null) tr("Generar informe") else tr("Generar de nuevo"), onClick = { confirmReport = true }, enabled = can)
                if (recording.segments.isEmpty()) Text(tr("Hace falta la transcripción para redactar el informe."), fontSize = 12.sp, color = Palette.textFaint)
                if (recording.reportPending && processing) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            val line = if (processingId == recording.id && recording.pipelineStage == PipelineStage.WritingReport) processingLine ?: tr("Redactando el informe…")
                            else if (recording.cloudReviewPending) tr("Se redactará al terminar la revisión de la transcripción.") else tr("En cola…")
                            Text(line, Modifier.padding(start = 10.dp), fontSize = 14.sp, color = Palette.textMuted)
                        }
                        Text(tr("Se redacta sola, también con la pantalla bloqueada. Te avisaremos al terminar."), fontSize = 12.sp, color = Palette.textFaint)
                    }
                }
                recording.reportProblem?.takeIf { !processing }?.let { p ->
                    Text(p, fontSize = 14.sp, color = Palette.destructive)
                    if (recording.reportPending && recording.pipelineAttempts < ConsultationPipeline.MAX_ATTEMPTS) Text(tr("Se reintentará sola al abrir la app."), fontSize = 12.sp, color = Palette.textFaint)
                }
            }
            recording.report?.let { report ->
                Column(Modifier.fillMaxWidth().surface()) {
                    report.sections.forEachIndexed { i, sec ->
                        if (i > 0) Hairline(inset = 16.dp)
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sec.title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.textMuted)
                            Text(sec.text, fontSize = 16.sp, color = Palette.text)
                        }
                    }
                }
                Text(tr("Redactado por IA (%s). Revísalo con la transcripción antes de usarlo.", report.models.joinToString(", ")), fontSize = 12.sp, color = Palette.textFaint)
                ShareLine(tr("Copiar o compartir informe")) { share(head + "\n\n" + report.plainText) }
            }

            // Audio actions
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (workflow.hasAudio) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.clickable(enabled = busy == null && !processing) { menu = true }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
                            Text(if (recording.segments.isEmpty()) tr("Transcribir desde el audio") else tr("Transcribir de nuevo desde el audio"), Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            TranscriptionModel.entries.forEach { m ->
                                val name = if (m.isCloud) tr("%s (nube)", m.label) else m.label
                                DropdownMenuItem({ Text(if (m == recording.transcriptionModel && !recording.hasSpeakers) tr("%s (la actual)", name) else name) }, {
                                    menu = false
                                    if (m.isCloud) { cloudDiarize = false; cloudModel = m } else transcribe(m)
                                })
                                if (m.canDiarize) {
                                    val n2 = tr("%s con hablantes (nube)", m.label)
                                    DropdownMenuItem({ Text(if (m == recording.transcriptionModel && recording.hasSpeakers) tr("%s (la actual)", n2) else n2) }, { menu = false; cloudDiarize = true; cloudModel = m })
                                }
                            }
                        }
                    }
                    Row(Modifier.clickable { app.recordings.deleteAudio(recording); version++ }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Delete, null, tint = Palette.destructive, modifier = Modifier.size(20.dp))
                        Text(tr("Borrar solo el audio"), Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.destructive)
                    }
                }
                Row(Modifier.clickable { confirmDelete = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, null, tint = Palette.destructive, modifier = Modifier.size(20.dp))
                    Text(tr("Eliminar grabación"), Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.destructive)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("¿Eliminar esta grabación?")) }, text = { Text(tr("Se borran el audio y la transcripción de este dispositivo.")) },
            confirmButton = { TextButton({ confirmDelete = false; app.recordings.delete(recording); app.router.pop() }) { Text(tr("Eliminar"), color = Palette.destructive) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(tr("Cancelar")) } },
        )
    }
    if (confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text(tr("¿Redactar el informe en la nube?")) },
            text = { Text(tr("El texto de la consulta se enviará a OpenRouter para redactar el informe, sin que lo guarden. Hazlo solo si el paciente lo ha consentido.")) },
            confirmButton = {
                TextButton({
                    confirmReport = false
                    val p = recording.patientId?.let { pid -> patients.firstOrNull { it.id == pid } }
                    val language = p?.language?.trimmed?.nilIfEmpty ?: "Español"
                    app.pipeline.requestReport(recording, chosen, language, p?.sex)
                }) { Text(tr("Enviar y redactar")) }
            },
            dismissButton = { TextButton({ confirmReport = false }) { Text(tr("Cancelar")) } },
        )
    }
    cloudModel?.let { m ->
        AlertDialog(
            onDismissRequest = { cloudModel = null },
            title = { Text(tr("¿Transcribir en la nube?")) },
            text = {
                Text(
                    if (cloudDiarize) tr("El audio de esta consulta se enviará a %s para transcribirlo y distinguir quién habla. Hazlo solo si el paciente lo ha consentido.", m.label)
                    else tr("El audio de esta consulta se enviará a %s para transcribirlo. Hazlo solo si el paciente lo ha consentido.", m.label),
                )
            },
            confirmButton = { TextButton({ cloudModel = null; transcribe(m, cloudDiarize) }) { Text(tr("Enviar y transcribir")) } },
            dismissButton = { TextButton({ cloudModel = null }) { Text(tr("Cancelar")) } },
        )
    }
}

@Composable
private fun Experimental2() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Science, null, tint = Palette.warningFg, modifier = Modifier.size(14.dp))
        Text(tr("Experimental · guardado solo en este dispositivo"), Modifier.padding(start = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.warningFg)
    }
}

@Composable
private fun ShareLine(text: String, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Share, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
        Text(text, Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
    }
}

@Composable
fun templateLabel(t: ReportTemplate): String = when (t.id) {
    "vascular" -> tr("Angiología y cirugía vascular")
    "psychology" -> tr("Psicología")
    "dermatology" -> tr("Dermatología")
    "plastic_surgery" -> tr("Cirugía plástica")
    "gynecology" -> tr("Ginecología")
    "general_medicine" -> tr("Medicina general")
    "internista" -> tr("Medicina interna")
    "nutritionist" -> tr("Nutrición")
    "visitador" -> tr("Visitador")
    else -> t.id.replace("_", " ").replaceFirstChar { it.uppercase() }
}
