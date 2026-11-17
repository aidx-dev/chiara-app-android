package com.chiarasystems.chiara.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.data.AssistantAction
import com.chiarasystems.chiara.data.AssistantMessage
import com.chiarasystems.chiara.data.AssistantOutcome
import com.chiarasystems.chiara.data.AssistantProposal
import com.chiarasystems.chiara.data.PatientProposal
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.PrimaryButton
import com.chiarasystems.chiara.designsystem.SectionLabel
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.GlowState
import com.chiarasystems.chiara.designsystem.glass
import com.chiarasystems.chiara.designsystem.glowBorder
import androidx.compose.ui.focus.onFocusChanged
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.DictationVocabulary
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "Cita para Antonio el lunes de 11 a 12",
    "Enséñame la agenda de mañana",
    "Abre la ficha de María",
    "¿Qué medicación toma María?",
)

/** Chat-like sheet where a sentence becomes an agenda action. The assistant proposes, the user confirms. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantSheet(app: AppContainer) {
    val assistant = app.assistant
    val state by assistant.state.collectAsState()
    val patients by app.patients.patients.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictation = remember { app.newDictation() }
    val listening by dictation.isListening.collectAsState()
    val preparing by dictation.isPreparing.collectAsState()
    val heard by dictation.transcript.collectAsState()
    val dictationError by dictation.error.collectAsState()
    var text by remember { mutableStateOf("") }
    var typedBefore by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val undoText = tr("Deshacer")
    val undone = tr("Cambio deshecho")
    val reason = assistant.unavailableReason

    LaunchedEffect(Unit) { assistant.prepare() }
    DisposableEffect(Unit) { onDispose { dictation.cancel(); assistant.reset() } }
    LaunchedEffect(heard) { if (heard.isNotEmpty()) text = listOf(typedBefore, heard).map { it.trimmed }.filter { it.isNotEmpty() }.joinToString(" ") }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.proposal, state.candidates, state.slotChoices, state.patientProposal) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(state, assistant.action) {
        assistant.takeAction()?.let { a ->
            when (a) {
                is AssistantAction.ShowAgenda -> { app.router.selectDay(a.day); app.router.select(AppTab.Agenda); app.router.dismissSheet() }
                is AssistantAction.OpenPatient -> { app.router.select(AppTab.Patients); app.router.push(Route.Patient(a.id)); app.router.dismissSheet() }
            }
        }
    }

    fun finish(outcome: AssistantOutcome) {
        outcome.day?.let { d -> app.router.selectDay(d); if (app.router.path(app.router.tab.value).value.isEmpty()) app.router.select(AppTab.Agenda) }
        app.toast.show(outcome.message, undoText) {
            app.appScope.launch {
                try { outcome.undo(); outcome.day?.let(app.router::selectDay); app.toast.show(undone) }
                catch (e: Exception) { app.toast.show(e.message.orEmpty()) }
            }
        }
        app.router.dismissSheet()
    }

    fun confirm() = scope.launch {
        errorMessage = ""
        try { assistant.confirm()?.let(::finish) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { errorMessage = e.message.orEmpty() }
    }
    fun confirmPatient() = scope.launch {
        errorMessage = ""
        try { assistant.confirmPatientChange()?.let(::finish) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { errorMessage = e.message.orEmpty() }
    }
    fun submit(message: String) {
        if (message.trimmed.isEmpty() || state.isWorking || reason != null) return
        dictation.cancel(); text = ""; errorMessage = ""
        scope.launch { assistant.send(message) }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { typedBefore = text; dictation.vocabulary = DictationVocabulary.forPatients(patients, VisitType.entries.map { it.raw }); dictation.start(app.settings.code) }
    }
    fun toggleDictation() {
        if (listening) { dictation.stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            typedBefore = text
            dictation.vocabulary = DictationVocabulary.forPatients(patients, VisitType.entries.map { it.raw })
            dictation.start(app.settings.code)
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    AppSheet("Chiara", onClose = { app.router.dismissSheet() }) {
        Column(Modifier.weight(1f, fill = false).heightIn(min = 280.dp).verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tr("¿Qué necesitas?"), Modifier.padding(top = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    if (reason != null) Text(reason, fontSize = 15.sp, color = Palette.textMuted)
                    else {
                        Text(tr("Pídeme una cita, la agenda de un día o pregúntame por un paciente y sus informes."), fontSize = 15.sp, color = Palette.textMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EXAMPLES.forEach { ex ->
                                val label = tr(ex)
                                Text(label, Modifier.background(Palette.accent, CircleShape).clickable { submit(label) }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
                            }
                        }
                    }
                }
            }
            state.messages.filter { it.text.isNotEmpty() }.forEach { Bubble(it) }
            if (state.candidates.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().surface(20)) {
                    state.candidates.forEachIndexed { i, p ->
                        if (i > 0) Hairline(inset = 16.dp)
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { assistant.choose(p) } }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(p, size = 32.dp)
                            Text(p.fullName, Modifier.weight(1f), fontSize = 16.sp, color = Palette.text)
                            Text(p.documentId.orEmpty(), fontSize = 13.sp, color = Palette.textFaint)
                        }
                    }
                }
            }
            if (state.appointmentChoices.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().surface(20)) {
                    state.appointmentChoices.forEachIndexed { i, a ->
                        if (i > 0) Hairline(inset = 16.dp)
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { assistant.choose(a) } }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.CalendarMonth, null, tint = Palette.primary, modifier = Modifier.size(24.dp))
                            Text(assistant.label(a), Modifier.weight(1f), fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Palette.text)
                            a.visit.type?.let { Text(tr(it.raw), fontSize = 13.sp, color = Palette.textFaint) }
                        }
                    }
                }
            }
            if (state.slotChoices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.slotChoices.forEach { slot ->
                        Box(Modifier.height(38.dp).background(Palette.accent, CircleShape).clickable { scope.launch { assistant.choose(slot) } }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(assistant.label(slot, state.slotDuration), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
                        }
                    }
                }
            }
            state.patientProposal?.let { PatientChangeCard(it, state.isWorking, ::confirmPatient) { assistant.dismissProposal() } }
            state.proposal?.let { p -> ProposalCard(app, p, state.isWorking, ::confirm, assistant::pick) { assistant.dismissProposal() } }
            if (state.canCreatePatient) {
                Row(Modifier.background(Palette.accent, CircleShape).clickable { app.router.show(ActiveSheet.NewPatient) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PersonAdd, null, tint = Palette.primary, modifier = Modifier.size(18.dp))
                    Text(tr("Crear paciente"), Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
                }
            }
            if (state.isWorking) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    state.activity?.let { Text(it, Modifier.padding(start = 8.dp), fontSize = 14.sp, color = Palette.textMuted) }
                }
            }
            (errorMessage.nilIfEmpty ?: dictationError?.let { tr("No he podido usar el dictado.") })?.let { Text(it, fontSize = 13.sp, color = Palette.destructive) }
        }

        // Input bar: the field floats with the dictation button beside it.
        val hasText = text.trimmed.isNotEmpty()
        val canSend = hasText && !state.isWorking && reason == null
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).glass(RoundedCornerShape(24.dp))
                    // The outline only shows while the field is in use.
                    .glowBorder(RoundedCornerShape(24.dp), if (state.isWorking || listening) GlowState.Active else GlowState.Focused, visible = focused || listening || state.isWorking),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(start = 18.dp, top = 13.dp, bottom = 13.dp)) {
                    if (text.isEmpty()) Text(if (listening) tr("Escuchando…") else tr("Escribe o dicta una petición…"), fontSize = 17.sp, color = Palette.textFaint)
                    BasicTextField(
                        text, { text = it }, enabled = reason == null, maxLines = 4,
                        textStyle = TextStyle(fontSize = 17.sp, color = Palette.text), cursorBrush = SolidColor(Palette.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = { submit(text) }), modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    )
                }
                if (hasText) {
                    Box(Modifier.padding(end = 6.dp, bottom = 6.dp).size(36.dp).background(if (canSend) Palette.primary else Palette.disabled, CircleShape).clickable(enabled = canSend) { submit(text) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Send, tr("Enviar"), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!hasText || listening) {
                Box(
                    Modifier.size(48.dp).let { if (listening) it.background(Palette.primary, CircleShape) else it.glass(CircleShape) }
                        .clickable(enabled = reason == null && !state.isWorking) { toggleDictation() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (listening) Icons.Filled.GraphicEq else Icons.Filled.Mic, if (listening) tr("Dejar de dictar") else tr("Dictar"), tint = if (listening) Color.White else Palette.primary)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: AssistantMessage) {
    val mine = m.role == AssistantMessage.Role.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp).background(if (mine) Palette.primary else Palette.surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.displayText, fontSize = 16.sp, color = if (mine) Color.White else Palette.text)
            // Clinical answers written by the model are to be checked against the record.
            if (m.generated) {
                if (m.unsupported.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Palette.destructive, modifier = Modifier.size(14.dp))
                        Text(tr("Revisa estos datos, no aparecen en la ficha: %s", m.unsupported.joinToString(", ")), Modifier.padding(start = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.destructive)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Palette.textFaint, modifier = Modifier.size(12.dp))
                    Text(tr("Respuesta generada por IA. Compruébala en la ficha."), Modifier.padding(start = 6.dp), fontSize = 12.sp, color = Palette.textFaint)
                }
            }
        }
    }
}

@Composable
private fun Detail(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
        Text(value, Modifier.padding(start = 10.dp), fontSize = 16.sp, color = if (muted) Palette.textMuted else Palette.text)
    }
}

@Composable
private fun PatientChangeCard(p: PatientProposal, working: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().surface(22).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel(if (p.field == PatientProposal.Field.Phone) tr("CAMBIAR TELÉFONO") else tr("CAMBIAR CORREO"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(p.patient, size = 40.dp)
            Text(p.patient.fullName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Detail(Icons.Filled.Schedule, tr("Antes: %s", p.oldValue.ifEmpty { "—" }), muted = true)
            Detail(if (p.field == PatientProposal.Field.Phone) Icons.Filled.Mic else Icons.Filled.Schedule, p.newValue)
        }
        PrimaryButton(tr("Confirmar"), onConfirm, enabled = !working)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text(tr("Cancelar"), Modifier.clickable(onClick = onCancel), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.textMuted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalCard(app: AppContainer, p: AssistantProposal, working: Boolean, onConfirm: () -> Unit, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val cal = calFormat()
    val assistant = app.assistant
    val heading = when (p.change) {
        AssistantProposal.Change.Create -> null
        AssistantProposal.Change.Move -> tr("MOVER CITA")
        AssistantProposal.Change.Cancel -> tr("CANCELAR CITA")
        AssistantProposal.Change.Confirm -> tr("CONFIRMAR CITA")
        AssistantProposal.Change.Note -> tr("AÑADIR NOTA")
    }
    Column(Modifier.fillMaxWidth().surface(22).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        heading?.let { SectionLabel(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(p.patient, size = 40.dp)
            Column {
                Text(p.patient.fullName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Text(tr(p.type.raw), fontSize = 14.sp, color = Palette.textMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.original?.let { o -> if (p.change == AssistantProposal.Change.Move) Detail(Icons.Filled.Schedule, tr("Antes: %s", assistant.label(o)), muted = true) }
            Detail(Icons.Filled.CalendarMonth, cal.longDay(p.day))
            Detail(Icons.Filled.Schedule, "${Dates.hhmm(p.start)}–${Dates.hhmm(p.start + p.duration)} · ${tr("%d min", p.duration)}")
            if (p.reason.isNotEmpty()) Detail(Icons.Filled.Mic, p.reason)
        }
        p.issue?.let { Text(assistant.message(it), fontSize = 14.sp, color = Palette.destructive) }
        if (p.suggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(tr("Horas libres ese día"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.suggestions.forEach { start ->
                        Box(Modifier.height(38.dp).background(Palette.accent, CircleShape).clickable { onPick(start) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text("${Dates.hhmm(start)}–${Dates.hhmm(start + p.duration)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
                        }
                    }
                }
            }
        }
        val cancel = p.change == AssistantProposal.Change.Cancel
        PrimaryButton(if (cancel) tr("Cancelar cita") else tr("Confirmar"), onConfirm, enabled = p.isValid && !working, tint = if (cancel) Palette.destructive else Palette.primary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (p.change == AssistantProposal.Change.Create) Text(tr("Editar"), Modifier.clickable { app.router.show(ActiveSheet.Booking(p.patient.id, p.day)) }, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
            Box(Modifier.weight(1f))
            Text(if (cancel) tr("No cancelar") else tr("Cancelar"), Modifier.clickable(onClick = onCancel), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.textMuted)
        }
    }
}
