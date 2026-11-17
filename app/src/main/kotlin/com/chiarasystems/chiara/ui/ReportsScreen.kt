package com.chiarasystems.chiara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.GlassSearchField
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.LargeTitle
import com.chiarasystems.chiara.designsystem.OfflineBanner
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.SectionTitle
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.PatientMatcher
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import kotlinx.coroutines.launch

@Composable
fun Report.displayTitle(): String = title ?: tr("Informe de consulta")

@Composable
fun ReportsScreen(app: AppContainer) {
    val reports by app.reports.reports.collectAsState()
    val loading by app.reports.isLoading.collectAsState()
    val patients by app.patients.patients.collectAsState()
    val serving by app.client.isServingCache.collectAsState()
    val cal = calFormat()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val noTitle = tr("Informe de consulta")

    LaunchedEffect(patients.size) { app.reports.loadAll(patients) }

    fun name(r: Report) = patients.firstOrNull { it.id == r.patientId }?.fullName.orEmpty()
    val q = PatientMatcher.fold(query.trimmed)
    val filtered = if (q.isEmpty()) reports else reports.filter { r ->
        PatientMatcher.fold((listOf(name(r), r.title ?: noTitle) + r.codes.map { "${it.code} ${it.label}" }).joinToString(" ")).contains(q)
    }
    val groups = remember(filtered) {
        val out = mutableListOf<Pair<String, MutableList<Report>>>()
        for (r in filtered) {
            val label = r.date?.toLocalDate()?.let { cal.monthYear(it) }.orEmpty()
            if (out.lastOrNull()?.first == label) out.last().second += r else out += label to mutableListOf(r)
        }
        out
    }

    PullToRefreshBox(loading, { scope.launch { app.reports.loadAll(patients, force = true) } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp)) {
            item { LargeTitle(tr("Informes"), subtitle = tr("Solo lectura")) }
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassSearchField(query, { query = it }, tr("Paciente, diagnóstico o CIE-10"))
                    if (serving) OfflineBanner()
                }
            }
            if (loading && reports.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (groups.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().surface().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Description, null, tint = Palette.textFaint, modifier = Modifier.size(32.dp))
                        Text(if (query.isEmpty()) tr("Sin informes") else tr("Sin resultados"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    }
                }
            } else {
                groups.forEach { (label, items) ->
                    item(key = "h-$label") { SectionTitle(label, Modifier.padding(top = 8.dp, bottom = 10.dp)) }
                    itemsIndexed(items, key = { _, r -> r.id }) { i, r ->
                        Column(Modifier.fillMaxWidth().background(Palette.surface, cardRowShape(i, items.size))) {
                            if (i > 0) Hairline(inset = 68.dp)
                            ReportRow(r, name(r), Modifier.clickable { app.router.push(Route.Report(r.id)) })
                        }
                    }
                    item(key = "s-$label") { Box(Modifier.padding(bottom = 14.dp)) }
                }
            }
        }
    }
}

@Composable
fun ReportRow(report: Report, patientName: String?, modifier: Modifier = Modifier) {
    val cal = calFormat()
    val date = report.date?.toLocalDate()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Description, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    patientName?.nilIfEmpty ?: date?.let { cal.longDay(it) }.orEmpty(), Modifier.weight(1f),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(date?.let { cal.shortDate(it) }.orEmpty(), Modifier.padding(start = 8.dp), fontSize = 13.sp, color = Palette.textFaint)
            }
            Text(report.displayTitle(), fontSize = 15.sp, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (report.codes.isNotEmpty()) {
                Text(report.codes.joinToString(" · ") { it.code }, fontSize = 13.sp, color = Palette.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Palette.chevron, modifier = Modifier.padding(top = 12.dp).size(18.dp))
    }
}
