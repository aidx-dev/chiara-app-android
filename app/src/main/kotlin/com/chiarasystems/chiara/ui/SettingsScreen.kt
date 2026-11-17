package com.chiarasystems.chiara.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.BuildConfig
import com.chiarasystems.chiara.data.AppLanguage
import com.chiarasystems.chiara.data.AppearanceMode
import com.chiarasystems.chiara.designsystem.FieldRow
import com.chiarasystems.chiara.designsystem.GroupedList
import com.chiarasystems.chiara.designsystem.LargeTitle
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.SectionLabel
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.model.UserProfile
import com.chiarasystems.chiara.model.capitalizedFirst
import kotlinx.coroutines.launch

@Composable
fun UserProfile.roleLabel(): String = when (roleKind) {
    UserProfile.RoleKind.Doctor -> tr("Médico")
    UserProfile.RoleKind.Staff -> tr("Recepción")
    UserProfile.RoleKind.Owner -> tr("Propietario")
    UserProfile.RoleKind.Other -> role.capitalizedFirst
}

@Composable
fun UserProfile.specialtyLabel(): String? = when (specialtyKey) {
    null -> null
    "vascular" -> tr("Angiología y cirugía vascular")
    "dermatology" -> tr("Dermatología")
    "gynecology" -> tr("Ginecología")
    "plasticsurgery" -> tr("Cirugía plástica")
    "psychology" -> tr("Psicología")
    "visitador" -> tr("Visitador")
    else -> specialtyFallback
}

@Composable
fun UserProfile.roleLine(): String = listOfNotNull(roleLabel(), specialtyLabel()).joinToString(" · ")

@Composable
fun SettingsScreen(app: AppContainer) {
    val user by app.auth.user.collectAsState()
    val language by app.settings.language.collectAsState()
    val appearance by app.settings.appearance.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        "${info?.versionName ?: "1.0"} (${info?.longVersionCode ?: 1})"
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 150.dp)) {
        LargeTitle(user?.displayName ?: tr("Ajustes"), subtitle = user?.roleLine())

        user?.let { u ->
            GroupedList {
                row { FieldRow(tr("Correo"), u.email) }
                u.clinicName?.let { c -> row { FieldRow(tr("Clínica"), c) } }
            }
        }

        SectionLabel(tr("IDIOMA"), Modifier.padding(top = 24.dp, bottom = 8.dp))
        GroupedList {
            AppLanguage.entries.forEach { l ->
                row {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { app.settings.setLanguage(l) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(l.nativeName ?: tr("Automático"), fontSize = 17.sp, color = Palette.text)
                            if (l == AppLanguage.System) Text(tr("Idioma del dispositivo"), fontSize = 13.sp, color = Palette.textFaint)
                        }
                        if (language == l) Icon(Icons.Filled.Check, null, tint = Palette.primary)
                    }
                }
            }
        }

        SectionLabel(tr("APARIENCIA"), Modifier.padding(top = 24.dp, bottom = 8.dp))
        Segmented(
            listOf(AppearanceMode.System to tr("Automático"), AppearanceMode.Light to tr("Claro"), AppearanceMode.Dark to tr("Oscuro")),
            appearance, { app.settings.setAppearance(it) },
        )
        Text(tr("Automático sigue la configuración del dispositivo."), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), fontSize = 13.sp, color = Palette.textFaint)

        GroupedList(Modifier.padding(top = 16.dp)) { row { FieldRow(tr("Versión"), version) } }

        Box(
            Modifier.padding(top = 24.dp).fillMaxWidth().surface().clickable { confirmSignOut = true }.heightIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Cerrar sesión"), fontSize = 17.sp, color = Palette.destructive) }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(tr("¿Cerrar sesión?")) },
            confirmButton = { TextButton({ confirmSignOut = false; scope.launch { app.auth.signOut() } }) { Text(tr("Cerrar sesión"), color = Palette.destructive) } },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text(tr("Cancelar")) } },
        )
    }
}
