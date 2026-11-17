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
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Palette

/** Root of the selected tab, or the top of its back stack (patient / report detail). */
@Composable
fun TabContent(app: AppContainer, tab: AppTab) {
    val path by app.router.path(tab).collectAsState()
    when (val top = path.lastOrNull()) {
        is Route.Patient -> Placeholder("Paciente ${top.id}")
        is Route.Report -> Placeholder("Informe ${top.id}")
        null -> when (tab) {
            AppTab.Agenda -> Placeholder("Agenda")
            AppTab.Patients -> Placeholder("Pacientes")
            AppTab.Reports -> Placeholder("Informes")
            AppTab.Settings -> Placeholder("Ajustes")
        }
    }
}

@Composable
fun Sheets(app: AppContainer) {
    // Booking, NewPatient and Assistant sheets arrive with their screens.
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.textMuted) }
}
