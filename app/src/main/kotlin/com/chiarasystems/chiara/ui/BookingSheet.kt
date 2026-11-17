package com.chiarasystems.chiara.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.PrimaryButton
import com.chiarasystems.chiara.designsystem.Radius
import com.chiarasystems.chiara.designsystem.SectionLabel
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.AgendaRules
import com.chiarasystems.chiara.domain.PatientMatcher
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.model.trimmed
import java.time.LocalDate
import kotlinx.coroutines.launch

private const val MONTHS_AHEAD = 12

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookingSheet(app: AppContainer, initial: ActiveSheet.Booking) {
    val scope = rememberCoroutineScope()
    val patients by app.patients.patients.collectAsState()
    val byDay by app.appointments.byDay.collectAsState()
    val cal = calFormat()
    val today = remember { LocalDate.now() }

    var patientId by remember { mutableStateOf(initial.patientId) }
    var day by remember { mutableStateOf(if (AgendaRules.isBookable(initial.day, today)) initial.day else today) }
    var month by remember { mutableStateOf(day) }
    var start by remember { mutableStateOf<Int?>(null) }
    var duration by remember { mutableStateOf(30) }
    var type by remember { mutableStateOf(VisitType.Review) }
    var reason by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(initial.patientId == null) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var nowMinutes by remember { mutableStateOf(Dates.minutesNow()) }

    val patient = patientId?.let { id -> patients.firstOrNull { it.id == id } }

    // Fresh availability for the month on screen (never before today).
    LaunchedEffect(month.year, month.monthValue) {
        val first = Dates.startOfMonth(month)
        val last = first.plusMonths(1).minusDays(1)
        app.appointments.load(maxOf(first, today), last)
        nowMinutes = Dates.minutesNow()
    }

    @Suppress("UNUSED_EXPRESSION") byDay // recompute availability when the store changes
    fun taken(minute: Int) = AgendaRules.isTaken(day, minute, duration, app.appointments.busyIntervals(day), today, nowMinutes)
    val s = start
    val valid = patient != null && s != null && !taken(s)
    val summary = when {
        patient == null -> tr("Elige un paciente")
        s == null || taken(s) -> tr("Elige una hora disponible")
        else -> "${cal.dayChip(day)} · ${Dates.hhmm(s)}–${Dates.hhmm(s + duration)} · ${tr(type.raw)}"
    }
    val booked = if (s != null) tr("Cita agendada · %s, %s", cal.dayChip(day), Dates.hhmm(s)) else ""

    fun save() {
        val p = patient ?: return
        val st = start ?: return
        if (!valid || saving) return
        saving = true; error = ""
        scope.launch {
            try {
                app.appointments.create(p, day, st, duration, type, reason)
                app.router.selectDay(day)
                if (app.router.path(app.router.tab.value).value.isEmpty()) app.router.select(AppTab.Agenda)
                app.toast.show(booked)
                app.router.dismissSheet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally { saving = false }
        }
    }

    AppSheet(tr("Nueva cita"), onClose = { if (!saving) app.router.dismissSheet() }, confirmLabel = tr("Guardar"), confirmEnabled = valid && !saving, onConfirm = ::save) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // --- Patient
            SectionLabel(tr("PACIENTE"), Modifier.padding(top = 6.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().surface(20).animateContentSize()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { pickerOpen = !pickerOpen }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    patient?.let { Avatar(it, size = 36.dp) }
                    Text(patient?.fullName ?: tr("Seleccionar paciente"), Modifier.weight(1f), fontSize = 17.sp, color = if (patient == null) Palette.primary else Palette.text)
                    Icon(Icons.Filled.ChevronRight, null, tint = Palette.chevron, modifier = Modifier.size(18.dp).rotate(if (pickerOpen) 90f else 0f))
                }
                if (pickerOpen) {
                    Hairline()
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().height(38.dp).background(Palette.field, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, tint = Palette.textFaint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(tr("Buscar paciente"), fontSize = 16.sp, color = Palette.textFaint)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = Palette.text), cursorBrush = SolidColor(Palette.primary), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val q = PatientMatcher.fold(query.trimmed)
                    val options = if (q.isEmpty()) patients else patients.filter { PatientMatcher.fold(it.fullName + " " + (it.documentId ?: "")).contains(q) }
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(options.take(60), key = { it.id }) { o ->
                            Row(
                                Modifier.fillMaxWidth().clickable { patientId = o.id; pickerOpen = false; query = "" }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Avatar(o, size = 32.dp)
                                Text(o.fullName, Modifier.weight(1f), fontSize = 16.sp, color = Palette.text)
                                Text(o.documentId.orEmpty(), fontSize = 13.sp, color = Palette.textFaint)
                            }
                        }
                    }
                }
            }

            // --- Date
            SectionLabel(tr("FECHA"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            MonthCalendar(
                selection = day, month = month, today = today,
                hasAppointments = { app.appointments.hasAppointments(it) },
                onSelect = { d -> day = d; start?.let { if (taken(it)) start = null } },
                onMonth = { month = it },
            )

            // --- Time
            SectionLabel(tr("HORA"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgendaRules.slots.chunked(4).forEach { rowSlots ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowSlots.forEach { minute ->
                            val isTaken = taken(minute)
                            val selected = start == minute && !isTaken
                            Box(
                                Modifier.weight(1f).height(42.dp)
                                    .background(if (selected) Palette.primary else if (isTaken) Palette.field else Palette.surface, RoundedCornerShape(13.dp))
                                    .clickable(enabled = !isTaken) { start = minute },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    Dates.hhmm(minute), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                                    textDecoration = if (isTaken) TextDecoration.LineThrough else null,
                                    color = if (selected) Color.White else if (isTaken) Palette.disabled else Palette.text,
                                )
                            }
                        }
                        repeat(4 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // --- Duration
            SectionLabel(tr("DURACIÓN"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            Segmented(AgendaRules.DURATIONS.map { it to tr("%d min", it) }, duration, { d -> duration = d; start?.let { if (taken(it)) start = null } })

            // --- Visit type
            SectionLabel(tr("TIPO DE VISITA"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VisitType.entries.forEach { t ->
                    val on = type == t
                    Box(
                        Modifier.height(38.dp).background(if (on) Palette.accent else Palette.surface, CircleShape)
                            .border(1.dp, if (on) Palette.primary else Color.Transparent, CircleShape).clickable { type = t }.padding(horizontal = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(tr(t.raw), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) Palette.primary else Palette.text) }
                }
            }

            // --- Reason
            SectionLabel(tr("MOTIVO (OPCIONAL)"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            Box(Modifier.fillMaxWidth().surface(18).padding(horizontal = 14.dp, vertical = 12.dp).heightIn(min = 72.dp)) {
                if (reason.isEmpty()) Text(tr("Ej. revisar analítica"), fontSize = 16.sp, color = Palette.disabled)
                BasicTextField(reason, { reason = it }, textStyle = TextStyle(fontSize = 16.sp, color = Palette.text), cursorBrush = SolidColor(Palette.primary), maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        }
        // --- Footer
        Column(Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.94f)).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error.isNotEmpty()) Text(error, fontSize = 13.sp, color = Palette.destructive)
            Text(summary, fontSize = 13.sp, color = Palette.textMuted)
            PrimaryButton(if (saving) tr("Agendando…") else tr("Agendar cita"), onClick = ::save, enabled = valid && !saving)
        }
    }
}

/** Full month calendar for choosing the appointment day. Past days and Sundays are disabled; a dot marks busy days. */
@Composable
fun MonthCalendar(selection: LocalDate, month: LocalDate, today: LocalDate, hasAppointments: (LocalDate) -> Boolean, onSelect: (LocalDate) -> Unit, onMonth: (LocalDate) -> Unit) {
    val cal = calFormat()
    val first = Dates.startOfMonth(month)
    val firstOfCurrent = Dates.startOfMonth(today)
    val canBack = first.isAfter(firstOfCurrent)
    val canForward = first.isBefore(firstOfCurrent.plusMonths(MONTHS_AHEAD.toLong()))
    val offset = first.dayOfWeek.value - 1 // Monday-first
    val cells: List<LocalDate?> = List(offset) { null } + (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }

    Column(Modifier.fillMaxWidth().surface(Radius.cardLarge).padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cal.monthYear(first), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Icon(Icons.Filled.ChevronLeft, tr("Mes anterior"), tint = if (canBack) Palette.primary else Palette.disabled, modifier = Modifier.size(36.dp).clickable(enabled = canBack) { onMonth(first.minusMonths(1)) }.padding(6.dp))
            Icon(Icons.Filled.ChevronRight, tr("Mes siguiente"), tint = if (canForward) Palette.primary else Palette.disabled, modifier = Modifier.size(36.dp).clickable(enabled = canForward) { onMonth(first.plusMonths(1)) }.padding(6.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            val monday = Dates.startOfWeek(first)
            for (i in 0 until 7) Text(cal.weekdayShort(monday.plusDays(i.toLong())).take(1).uppercase(), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.textFaint, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    if (d == null) Spacer(Modifier.weight(1f).height(46.dp)) else {
                        val selected = d == selection
                        val isToday = d == today
                        val disabled = d.isBefore(today) || Dates.isSunday(d)
                        Column(Modifier.weight(1f).height(46.dp).clickable(enabled = !disabled) { onSelect(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(36.dp).background(if (selected) Palette.primary else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                                Text(d.dayOfMonth.toString(), fontSize = 17.sp, fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal, color = if (selected) Color.White else if (disabled) Palette.disabled else if (isToday) Palette.primary else Palette.text)
                            }
                            Box(Modifier.padding(top = 2.dp).size(5.dp).background(if (hasAppointments(d) && !disabled) (if (selected) Palette.primary else Palette.chart3) else Color.Transparent, CircleShape))
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
