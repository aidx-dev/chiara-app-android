package com.chiarasystems.chiara.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.data.AuthPhase
import com.chiarasystems.chiara.designsystem.AssistantButton
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.glass
import com.chiarasystems.chiara.designsystem.tr

@Composable
fun RootScreen(app: AppContainer) {
    val phase by app.auth.phase.collectAsState()
    LaunchedEffect(phase) { if (phase == AuthPhase.SignedOut) app.clearSessionState() }
    Crossfade(phase, Modifier.fillMaxSize().background(Palette.background), label = "root") { p ->
        when (p) {
            AuthPhase.SignedOut -> LoginScreen(app.auth)
            AuthPhase.SignedIn -> MainShell(app)
        }
    }
}

private data class TabItem(val tab: AppTab, val title: String, val icon: ImageVector)

@Composable
fun MainShell(app: AppContainer) {
    val router = app.router
    val tab by router.tab.collectAsState()
    val toast by app.toast.toast.collectAsState()
    val path by router.path(tab).collectAsState()
    LaunchedEffect(Unit) { app.patients.load() }
    BackHandler(enabled = path.isNotEmpty()) { router.pop() }

    val items = listOf(
        TabItem(AppTab.Agenda, tr("Agenda"), Icons.Outlined.CalendarMonth),
        TabItem(AppTab.Patients, tr("Pacientes"), Icons.Outlined.Group),
        TabItem(AppTab.Reports, tr("Informes"), Icons.Outlined.Description),
        TabItem(AppTab.Settings, tr("Ajustes"), Icons.Outlined.Settings),
    )

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TabContent(app, tab)
            }
            TabBar(items, tab) { router.select(it) }
        }
        // Assistant button; adding appointments/patients lives in each screen's top bar.
        AssistantButton(
            onClick = { router.show(com.chiarasystems.chiara.data.ActiveSheet.Assistant) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)) {
            AnimatedVisibility(toast != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                toast?.let { t ->
                    Row(
                        Modifier.glass(CircleShape).padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.message, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                        t.actionTitle?.let {
                            Text(it, Modifier.clickable { app.toast.performAction() }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.primary)
                        }
                    }
                }
            }
        }
        Sheets(app)
    }
}

@Composable
private fun TabBar(items: List<TabItem>, selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.94f)).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { item ->
            val on = item.tab == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(item.tab) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, null, Modifier.size(26.dp), tint = if (on) Palette.primary else Palette.textMuted)
                Text(item.title, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) Palette.primary else Palette.textMuted)
            }
        }
    }
}
