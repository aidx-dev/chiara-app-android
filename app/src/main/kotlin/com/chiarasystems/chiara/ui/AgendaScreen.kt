package com.chiarasystems.chiara.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.GlassIconButton
import com.chiarasystems.chiara.designsystem.GlassTextButton
import com.chiarasystems.chiara.designsystem.LargeTitle
import com.chiarasystems.chiara.designsystem.OfflineBanner
import com.chiarasystems.chiara.designsystem.ErrorNote
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.Radius
import com.chiarasystems.chiara.designsystem.SectionTitle
import com.chiarasystems.chiara.designsystem.StatusBadge
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.DayBriefing
import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.VisitType
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.launch

private const val MONTH_EXPANDED_KEY = "agenda.monthExpanded"

/** Monday of each row shown: the selected day's week, or every week touching its month. */
fun weeksAround(day: LocalDate, wholeMonth: Boolean): List<LocalDate> {
    if (!wholeMonth) return listOf(Dates.startOfWeek(day))
    val first = Dates.startOfMonth(day)
    val last = first.withDayOfMonth(first.lengthOfMonth())
    val weeks = mutableListOf<LocalDate>()
    var week = Dates.startOfWeek(first)
    while (!week.isAfter(last)) { weeks += week; week = week.plusDays(7) }
    return weeks
}

@Composable
fun AgendaScreen(app: AppContainer) {
    val router = app.router
    val day by router.agendaDay.collectAsState()
    val byDay by app.appointments.byDay.collectAsState()
    val loading by app.appointments.isLoading.collectAsState()
    val loadError by app.appointments.loadError.collectAsState()
    val serving by app.client.isServingCache.collectAsState()
    val cal = calFormat()
    val scope = rememberCoroutineScope()
    var monthExpanded by remember { mutableStateOf(app.prefs.getString(MONTH_EXPANDED_KEY) == "true") }
    val today = LocalDate.now()
    val isToday = day == today
    val dayAppointments = remember(byDay, day) { app.appointments.appointments(day) }

    val weeks = remember(day.year, day.monthValue) { weeksAround(day, true) }
    val from = weeks.first()
    val to = weeks.last().plusDays(6)
    suspend fun reload() = app.appointments.load(from, to)
    LaunchedEffect(from, to) { reload() }

    PullToRefreshBox(loading, { scope.launch { reload() } }, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 150.dp)) {
            LargeTitle(
                tr("Agenda"),
                subtitle = cal.longDay(day) + if (isToday) " · " + tr("Hoy") else "",
            ) {
                GlassTextButton(tr("Hoy"), onClick = { router.selectDay(today) }, enabled = !isToday)
                GlassIconButton(Icons.Filled.Add, tr("Nueva cita"), onClick = { router.show(ActiveSheet.Booking(null, day)) })
            }
            if (serving) OfflineBanner(Modifier.padding(bottom = 12.dp))
            loadError?.let { ErrorNote(it.userMessage(), Modifier.padding(bottom = 12.dp)) }

            CalendarCard(app, day, monthExpanded, { monthExpanded = it; app.prefs.putString(MONTH_EXPANDED_KEY, it.toString()) })
            DayBriefingCard(app, day, dayAppointments)

            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    when (dayAppointments.size) { 0 -> tr("Citas"); 1 -> tr("1 cita"); else -> tr("%d citas", dayAppointments.size) },
                    Modifier.weight(1f),
                )
                Text(tr("Agendar"), Modifier.clickable { router.show(ActiveSheet.Booking(null, day)) }.padding(8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
            }
            if (dayAppointments.isEmpty()) {
                EmptyDay { router.show(ActiveSheet.Booking(null, day)) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dayAppointments.forEach { a ->
                        AppointmentRow(a, Modifier.clickable { router.push(Route.Patient(a.patientId)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDay(onBook: () -> Unit) {
    Column(Modifier.fillMaxWidth().surface().padding(horizontal = 20.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = Palette.textFaint, modifier = Modifier.size(32.dp))
        Text(tr("Sin citas este día"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, modifier = Modifier.padding(top = 4.dp))
        Text(tr("Tienes la agenda libre."), fontSize = 15.sp, color = Palette.textFaint)
        Box(Modifier.padding(top = 10.dp).height(40.dp).background(Palette.accent, CircleShape).clip(CircleShape).clickable(onClick = onBook).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(tr("Agendar cita"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
        }
    }
}

// --- Day briefing -------------------------------------------------------------------------------------------------

/** The day at a glance; on request each patient's last report. Hidden on a day with no appointments. */
@Composable
private fun DayBriefingCard(app: AppContainer, day: LocalDate, appointments: List<Appointment>) {
    val latest = remember { mutableStateMapOf<String, Report>() }
    var expanded by remember(day) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cal = calFormat()
    val b = DayBriefing.make(day, appointments, latest.toMap())
    if (b.total == 0) return

    val summary = buildList {
        add(if (b.total == 1) tr("1 cita") else tr("%d citas", b.total))
        if (b.firstVisits > 0) add(if (b.firstVisits == 1) tr("1 primera visita") else tr("%d primeras visitas", b.firstVisits))
        if (b.unconfirmed > 0) add(tr("%d sin confirmar", b.unconfirmed))
    }.joinToString(" · ")
    val free: String? = run {
        if (day.isBefore(LocalDate.now()) || Dates.isSunday(day)) return@run null
        if (b.freeSlots.isEmpty()) return@run tr("Sin huecos libres")
        tr("Huecos libres: %s", b.freeSlots.take(6).joinToString(", ") { Dates.hhmm(it) } + if (b.freeSlots.size > 6) "…" else "")
    }

    Column(Modifier.padding(top = 12.dp).fillMaxWidth().surface().animateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Checklist, null, tint = Palette.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Resumen del día"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.textMuted)
        }
        Text(summary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        free?.let { Text(it, fontSize = 15.sp, color = Palette.textMuted) }
        if (expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                b.lines.forEach { l ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(Dates.hhmm(l.start), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = Palette.text)
                        Column {
                            Text(l.patientName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                            val ctx = l.lastReport?.let { tr("Último informe: %s · %s", it.title ?: tr("Informe de consulta"), cal.shortDate(it.date)) }
                                ?: if (l.isFirstVisit) tr("Primera visita") else tr("Sin informes todavía")
                            Text(ctx, fontSize = 14.sp, color = Palette.textMuted)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).clickable(enabled = !loading) {
            if (expanded) { expanded = false; return@clickable }
            scope.launch {
                loading = true
                try {
                    for (id in appointments.map { it.patientId }.toSet()) {
                        if (id !in latest) app.reports.fetch(id).firstOrNull()?.let { latest[id] = it }
                    }
                    expanded = true
                } finally { loading = false }
            }
        }) {
            Text(if (expanded) tr("Ocultar") else tr("Ver el último informe de cada paciente"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
            if (loading) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
        }
    }
}

// --- Calendar card -------------------------------------------------------------------------------------------------

@Composable
private fun CalendarCard(app: AppContainer, day: LocalDate, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    val byDay by app.appointments.byDay.collectAsState()
    val cal = calFormat()
    val router = app.router
    val today = LocalDate.now()
    val weeks = weeksAround(day, expanded)
    val monthLabel = cal.monthYear(day)
    val shiftLabel = if (expanded) tr("Mes anterior") to tr("Mes siguiente") else tr("Semana anterior") to tr("Semana siguiente")

    fun shift(step: Int) {
        router.selectDay(if (expanded) day.plusMonths(step.toLong()) else day.plusDays(7L * step))
    }

    Column(Modifier.fillMaxWidth().surface(Radius.cardLarge).animateContentSize().padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn36().clickable { onExpanded(!expanded) }, verticalAlignment = Alignment.CenterVertically) {
                Text(monthLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.ExpandMore, tr(if (expanded) "Ver solo la semana" else "Ver el mes completo"), tint = Palette.primary, modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronLeft, shiftLabel.first, tint = Palette.primary, modifier = Modifier.size(36.dp).clickable { shift(-1) }.padding(6.dp))
            Icon(Icons.Filled.ChevronRight, shiftLabel.second, tint = Palette.primary, modifier = Modifier.size(36.dp).clickable { shift(1) }.padding(6.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            val start = Dates.startOfWeek(day)
            for (i in 0 until 7) {
                Text(cal.weekdayShort(start.plusDays(i.toLong())).take(1).uppercase(), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.textFaint, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = week.plusDays(i.toLong())
                    DayCell(d, selected = d == day, isToday = d == today, otherMonth = d.month != day.month, hasAppointments = !byDay[Dates.iso(d)].isNullOrEmpty(), cal.longDay(d), Modifier.weight(1f)) { router.selectDay(d) }
                }
            }
        }
    }
}

private fun Modifier.heightIn36() = this.then(Modifier.height(36.dp))

@Composable
private fun DayCell(day: LocalDate, selected: Boolean, isToday: Boolean, otherMonth: Boolean, hasAppointments: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(48.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).background(if (selected) Palette.primary else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                day.dayOfMonth.toString(), fontSize = 17.sp, fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else if (isToday) Palette.primary else if (otherMonth) Palette.textFaint else Palette.text,
            )
        }
        Box(Modifier.padding(top = 3.dp).size(5.dp).background(if (hasAppointments) (if (selected) Palette.primary else Palette.chart3) else Color.Transparent, CircleShape))
    }
}

// --- Row -----------------------------------------------------------------------------------------------------------

@Composable
fun AppointmentRow(a: Appointment, modifier: Modifier = Modifier) {
    val status = a.statusKey
    val canceled = status == AppointmentStatus.Canceled
    Row(modifier.fillMaxWidth().surface().alpha(if (canceled) 0.55f else 1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(a.timeLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Text(tr("%d min", a.durationMinutes), fontSize = 13.sp, color = Palette.textFaint)
        }
        Box(Modifier.width(0.5.dp).height(36.dp).background(Palette.separator))
        Column(Modifier.weight(1f)) {
            Text(
                a.patientName.ifEmpty { tr("Paciente") }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textDecoration = if (canceled) TextDecoration.LineThrough else null,
            )
            Text(appointmentSubtitle(a), fontSize = 15.sp, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusBadge(status)
    }
}

@Composable
fun appointmentSubtitle(a: Appointment): String {
    val v = a.visit
    val type = v.type
    return if (type != null) { val t = tr(type.raw); if (v.reason.isEmpty()) t else "$t · ${v.reason}" } else v.reason.ifEmpty { tr("Consulta") }
}
