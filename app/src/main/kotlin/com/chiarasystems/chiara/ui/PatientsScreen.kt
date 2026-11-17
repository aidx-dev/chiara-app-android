package com.chiarasystems.chiara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Group
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
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.ErrorNote
import com.chiarasystems.chiara.designsystem.GlassIconButton
import com.chiarasystems.chiara.designsystem.GlassSearchField
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.LargeTitle
import com.chiarasystems.chiara.designsystem.OfflineBanner
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import kotlinx.coroutines.launch

/** Shape of row [index] of [count] inside one rounded white card made of separate lazy items. */
fun cardRowShape(index: Int, count: Int, radius: Int = 22) = RoundedCornerShape(
    topStart = if (index == 0) radius.dp else 0.dp, topEnd = if (index == 0) radius.dp else 0.dp,
    bottomStart = if (index == count - 1) radius.dp else 0.dp, bottomEnd = if (index == count - 1) radius.dp else 0.dp,
)

@Composable
fun patientListSubtitle(p: Patient): String {
    val parts = mutableListOf<String>()
    p.documentId?.nilIfEmpty?.let { parts += "${tr("DNI")} $it" }
    p.age()?.let { parts += tr("%d años", it) }
    return parts.joinToString(" · ")
}

@Composable
fun patientProfileSubtitle(p: Patient): String {
    val parts = mutableListOf<String>()
    p.age()?.let { parts += tr("%d años", it) }
    when (p.sexKind) {
        Patient.SexKind.Female -> parts += tr("Mujer")
        Patient.SexKind.Male -> parts += tr("Hombre")
        Patient.SexKind.Other -> parts += tr("Otro")
        null -> Unit
    }
    p.insuranceCompany?.nilIfEmpty?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
fun PatientsScreen(app: AppContainer) {
    val store = app.patients
    val patients by store.patients.collectAsState()
    val results by store.searchResults.collectAsState()
    val loading by store.isLoading.collectAsState()
    val loadError by store.loadError.collectAsState()
    val serving by app.client.isServingCache.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val searching = query.trimmed.isNotEmpty()
    val visible = if (searching) results else patients

    PullToRefreshBox(loading, { scope.launch { store.load() } }, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
        ) {
            item {
                LargeTitle(
                    tr("Pacientes"),
                    subtitle = if (searching) tr("%d resultados", visible.size) else tr("%d pacientes", patients.size),
                ) { GlassIconButton(Icons.Filled.PersonAdd, tr("Nuevo paciente"), onClick = { app.router.show(ActiveSheet.NewPatient) }) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    GlassSearchField(query, { query = it; store.search(it) }, tr("Buscar por nombre o DNI"))
                    if (serving) OfflineBanner()
                    loadError?.let { ErrorNote(it.userMessage()) }
                    Row(
                        Modifier.fillMaxWidth().surface().clickable(role = androidx.compose.ui.semantics.Role.Button) { app.router.show(ActiveSheet.NewPatient) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(40.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PersonAdd, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
                        }
                        Text(tr("Nuevo paciente"), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
                    }
                }
            }
            if (loading && patients.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (visible.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().surface().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Group, null, tint = Palette.textFaint, modifier = Modifier.size(32.dp))
                        Text(if (searching) tr("Sin resultados") else tr("Aún no hay pacientes"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                        if (searching) Text(tr("Prueba con otro nombre o DNI."), fontSize = 15.sp, color = Palette.textFaint)
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, p -> p.id }) { i, p ->
                    Column(Modifier.fillMaxWidth().background(Palette.surface, cardRowShape(i, visible.size))) {
                        if (i > 0) Hairline(inset = 68.dp)
                        PatientRow(p, Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { app.router.push(Route.Patient(p.id)) })
                    }
                }
            }
        }
    }
}

@Composable
fun PatientRow(p: Patient, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(p, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(p.fullName.ifEmpty { tr("Sin nombre") }, fontSize = 17.sp, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(patientListSubtitle(p), fontSize = 13.sp, color = Palette.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Palette.chevron, modifier = Modifier.size(18.dp))
    }
}
