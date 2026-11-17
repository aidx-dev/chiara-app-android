package com.chiarasystems.chiara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.ActiveSheet
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Palette

/** Root of the selected tab, or the top of its back stack (patient / report detail). */
@Composable
fun TabContent(app: AppContainer, tab: AppTab) {
    val path by app.router.path(tab).collectAsState()
    when (val top = path.lastOrNull()) {
        is Route.Patient -> PatientDetailScreen(app, top.id)
        is Route.Report -> ReportDetailScreen(app, top.id)
        null -> when (tab) {
            AppTab.Agenda -> AgendaScreen(app)
            AppTab.Patients -> PatientsScreen(app)
            AppTab.Reports -> ReportsScreen(app)
            AppTab.Settings -> Placeholder("Ajustes")
        }
    }
}

@Composable
fun Sheets(app: AppContainer) {
    val sheet by app.router.sheet.collectAsState()
    when (sheet) {
        ActiveSheet.NewPatient -> NewPatientSheet(app)
        // Booking and Assistant sheets arrive with their screens.
        else -> Unit
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.textMuted) }
}
