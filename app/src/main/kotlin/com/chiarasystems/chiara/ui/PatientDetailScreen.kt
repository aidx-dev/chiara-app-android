package com.chiarasystems.chiara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.FieldRow
import com.chiarasystems.chiara.designsystem.GroupedList
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.SectionTitle
import com.chiarasystems.chiara.designsystem.StatusBadge
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.PatientAppointment
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun PatientDetailScreen(app: AppContainer, patientId: String) {
    val revision by app.appointments.revision.collectAsState()
    var patient by remember { mutableStateOf<Patient?>(null) }
    var history by remember { mutableStateOf<List<PatientAppointment>>(emptyList()) }
    var reports by remember { mutableStateOf<List<Report>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cal = calFormat()

    suspend fun load() {
        patient = app.patients.fetchOne(patientId)
        notFound = patient == null
        history = app.appointments.history(patientId)
        reports = app.reports.fetch(patientId)
        loaded = true
    }
    LaunchedEffect(patientId, revision) { load() }

    val next = run {
        val today = LocalDate.now()
        val now = Dates.minutesNow()
        history.filter { (it.statusKey == AppointmentStatus.Scheduled || it.statusKey == AppointmentStatus.Confirmed) }
            .filter { a -> a.day?.let { it.isAfter(today) || (it == today && a.startMinutes >= now) } == true }
            .minWithOrNull(compareBy({ it.date }, { it.startMinutes }))
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(tr("Paciente"), onBack = { app.router.pop() })
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize()) {
            val p = patient
            when {
                p != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 150.dp)) {
                    Header(p)
                    Actions(app, p)
                    SectionTitle(tr("Próxima cita"), Modifier.padding(top = 28.dp, bottom = 10.dp))
                    NextAppointment(app, p, next, loaded)
                    SectionTitle(tr("Informes"), Modifier.padding(top = 28.dp, bottom = 10.dp))
                    ReportsList(app, reports, loaded)
                    SectionTitle(tr("Consultas grabadas"), Modifier.padding(top = 28.dp, bottom = 10.dp))
                    RecordedConsultations(app, p)
                    SectionTitle(tr("Datos del paciente"), Modifier.padding(top = 28.dp, bottom = 10.dp))
                    Details(p)
                }
                notFound -> Column(Modifier.fillMaxSize().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PersonOff, null, tint = Palette.textFaint, modifier = Modifier.size(40.dp))
                    Text(tr("Paciente no encontrado"), Modifier.padding(top = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                }
                else -> Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun Header(p: Patient) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Avatar(p, size = 88.dp)
        Text(p.fullName, Modifier.padding(top = 6.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Palette.text)
        patientProfileSubtitle(p).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 15.sp, color = Palette.textMuted) }
    }
}

@Composable
private fun Actions(app: AppContainer, p: Patient) {
    val uri = LocalUriHandler.current
    val phone = p.phone?.trimmed?.filter { it.isDigit() || it == '+' }?.nilIfEmpty
    val email = p.email?.trimmed?.nilIfEmpty
    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionCard(tr("Llamar"), Icons.Filled.Phone, Modifier.weight(1f), enabled = phone != null) { phone?.let { uri.openUri("tel:$it") } }
        ActionCard(tr("Correo"), Icons.Filled.Email, Modifier.weight(1f), enabled = email != null) { email?.let { uri.openUri("mailto:$it") } }
        ActionCard(tr("Agendar"), Icons.Filled.CalendarMonth, Modifier.weight(1f)) { app.router.show(ActiveSheet.Booking(p.id, app.router.agendaDay.value)) }
    }
}

@Composable
private fun ActionCard(title: String, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.height(64.dp).surface(20).let { if (enabled) it.clickable(onClick = onClick) else it.alpha(0.4f) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Palette.primary, modifier = Modifier.size(22.dp))
        Text(title, Modifier.padding(top = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.text)
    }
}


@Composable
private fun NextAppointment(app: AppContainer, p: Patient, next: PatientAppointment?, loaded: Boolean) {
    val cal = calFormat()
    val day = next?.day
    if (next != null && day != null) {
        Row(Modifier.fillMaxWidth().surface().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.EventAvailable, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${cal.dayChip(day)} · ${Dates.hhmm(next.startMinutes)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Text(tr("%d min", next.durationMinutes), fontSize = 15.sp, color = Palette.textMuted)
            }
            StatusBadge(next.statusKey)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().surface().clickable(enabled = loaded) { app.router.show(ActiveSheet.Booking(p.id, app.router.agendaDay.value)) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (loaded) tr("Sin citas programadas · Agendar") else tr("Cargando…"), Modifier.weight(1f), fontSize = 17.sp, color = if (loaded) Palette.primary else Palette.textFaint)
            if (loaded) Icon(Icons.Filled.Add, null, tint = Palette.primary)
        }
    }
}

@Composable
private fun ReportsList(app: AppContainer, reports: List<Report>, loaded: Boolean) {
    if (reports.isEmpty()) {
        Text(if (loaded) tr("Sin informes todavía") else tr("Cargando…"), Modifier.fillMaxWidth().surface().padding(16.dp), fontSize = 17.sp, color = Palette.textFaint)
    } else {
        Column(Modifier.fillMaxWidth().surface()) {
            reports.forEachIndexed { i, r ->
                if (i > 0) Hairline(inset = 68.dp)
                ReportRow(r, null, Modifier.clickable { app.router.push(Route.Report(r.id)) })
            }
        }
    }
}

@Composable
private fun Details(p: Patient) {
    val cal = calFormat()
    val phone = p.phone?.trimmed?.nilIfEmpty
    val email = p.email?.trimmed?.nilIfEmpty
    GroupedList {
        row { FieldRow(tr("Teléfono"), phone ?: "—", link = phone?.let { "tel:" + it.filter { c -> c.isDigit() || c == '+' } }) }
        row { FieldRow(tr("Correo"), email ?: "—", link = email?.let { "mailto:$it" }) }
        row { FieldRow(tr("Fecha de nacimiento"), p.birthDate?.let(cal::birthDate) ?: "—") }
        row { FieldRow(tr("DNI"), p.documentId?.nilIfEmpty ?: "—") }
        row { FieldRow(tr("Aseguradora"), p.insuranceCompany?.nilIfEmpty ?: "—") }
        row { FieldRow(tr("Clínica"), p.referringClinic?.nilIfEmpty ?: "—") }
    }
}

/** Experimental: record or import a consultation and transcribe it, kept only on this device. */
@Composable
private fun RecordedConsultations(app: AppContainer, p: Patient) {
    val all by app.recordings.recordings.collectAsState()
    val items = all.filter { it.patientId == p.id }
    Column(Modifier.fillMaxWidth().surface()) {
        items.forEach { r ->
            RecordingRow(r, Modifier.clickable { app.router.push(Route.Recording(r.id)) })
            Hairline(inset = 16.dp)
        }
        Row(Modifier.fillMaxWidth().clickable { app.router.show(ActiveSheet.Record(p.id)) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, null, tint = Palette.primary, modifier = Modifier.size(22.dp))
            Text(tr("Grabar consulta"), Modifier.padding(start = 10.dp).weight(1f), fontSize = 17.sp, color = Palette.primary)
            Text(tr("Experimental"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.warningFg)
        }
        Hairline(inset = 16.dp)
        Row(Modifier.fillMaxWidth().clickable { app.router.show(ActiveSheet.ImportAudio(p.id)) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FileDownload, null, tint = Palette.primary, modifier = Modifier.size(22.dp))
            Text(tr("Importar audio"), Modifier.padding(start = 10.dp), fontSize = 17.sp, color = Palette.primary)
        }
    }
}
