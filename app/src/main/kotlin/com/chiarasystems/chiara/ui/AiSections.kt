package com.chiarasystems.chiara.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.AssistantAgentError
import com.chiarasystems.chiara.data.AssistantMessage
import com.chiarasystems.chiara.designsystem.GroupedList
import com.chiarasystems.chiara.designsystem.LocalTranslator
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.SectionLabel
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.AssistantContext
import com.chiarasystems.chiara.domain.GroundingCheck
import com.chiarasystems.chiara.model.Patient
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

/** "Resumir ficha con IA": the record goes to the on-device model only; shown where a model is installed. */
@Composable
fun PatientSummaryCard(app: AppContainer, p: Patient, modifier: Modifier = Modifier) {
    val installed by app.models.llmInstalled.collectAsState()
    if (!installed) return
    val scope = rememberCoroutineScope()
    var summary by remember(p.id) { mutableStateOf("") }
    var summarizing by remember(p.id) { mutableStateOf(false) }
    var problem by remember(p.id) { mutableStateOf<String?>(null) }
    var unsupported by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
    val blocked = tr("La IA del dispositivo ha bloqueado esta respuesta por considerarla sensible. Prueba a preguntarlo de otra forma o consulta la ficha.")
    val failed = tr("No he podido generar el resumen.")
    val code = app.settings.code

    fun run() {
        summary = ""; problem = null; unsupported = emptyList(); summarizing = true
        scope.launch {
            try {
                val record = app.summarizer.summarize(p, AssistantContext(LocalDate.now(), code, Locale.forLanguageTag(code))) { summary = it }
                // Anything the summary states that the record does not is pointed out, not hidden.
                unsupported = GroundingCheck.unsupported(summary, listOf(record))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: AssistantAgentError) { if (e.kind == AssistantAgentError.Kind.BlockedBySystem) { summary = ""; problem = blocked } else problem = failed
            } catch (e: Exception) { problem = failed } finally { summarizing = false }
        }
    }

    if (summary.isEmpty() && !summarizing && problem == null) {
        Row(modifier.fillMaxWidth().height(52.dp).surface(20).clickable { run() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
            Text(tr("Resumir ficha con IA"), Modifier.padding(start = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
        }
    } else {
        Column(modifier.fillMaxWidth().surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Palette.textMuted, modifier = Modifier.size(16.dp))
                Text(tr("Resumen"), Modifier.padding(start = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.textMuted)
            }
            if (summary.isEmpty() && summarizing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("Generando resumen…"), Modifier.padding(start = 8.dp), fontSize = 15.sp, color = Palette.textMuted)
                }
            } else if (summary.isNotEmpty()) {
                Text(AssistantMessage.tidy(summary), fontSize = 16.sp, color = Palette.text)
                Text(tr("Respuesta generada por IA. Compruébala en la ficha."), fontSize = 12.sp, color = Palette.textFaint)
            }
            if (unsupported.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = Palette.destructive, modifier = Modifier.size(16.dp))
                    Text(tr("Revisa estos datos, no aparecen en la ficha: %s", unsupported.joinToString(", ")), Modifier.padding(start = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.destructive)
                }
            }
            problem?.let { Text(it, fontSize = 14.sp, color = Palette.destructive) }
            if (!summarizing) Text(tr("Resumir de nuevo"), Modifier.clickable { run() }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.primary)
        }
    }
}

/** Settings: install, replace or delete the on-device language model. */
@Composable
fun LocalAiSection(app: AppContainer) {
    val installed by app.models.llmInstalled.collectAsState()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true; failed = false
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.llm.release(); app.models.importLlm(uri) }
            failed = !ok; working = false
        }
    }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(tr("IA EN EL DISPOSITIVO"))
        GroupedList {
            row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr("Modelo de lenguaje"), fontSize = 17.sp, color = Palette.text)
                    Text(if (installed) tr("Instalado · %d MB", app.models.llmSizeMb()) else tr("No instalado"), fontSize = 13.sp, color = Palette.textFaint)
                }
            }
            row {
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !working) { picker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (working) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(tr("Copiando el modelo…"), Modifier.padding(start = 8.dp), fontSize = 17.sp, color = Palette.textMuted) }
                    else Text(if (installed) tr("Cambiar el modelo…") else tr("Elegir el archivo del modelo…"), fontSize = 17.sp, color = Palette.primary)
                }
            }
            if (installed) row {
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { confirmDelete = true }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(tr("Borrar el modelo"), fontSize = 17.sp, color = Palette.destructive)
                }
            }
        }
        if (failed) Text(tr("No he podido copiar ese archivo."), Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = Palette.destructive)
        Text(
            tr("El asistente responde preguntas sobre pacientes e informes con un modelo que se ejecuta en este dispositivo (formato MediaPipe .task o .litertlm, por ejemplo Gemma). Nada de lo que lee sale del teléfono. Sin modelo, el asistente solo entiende órdenes directas (agendar, mover, cancelar…)."),
            Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = Palette.textFaint,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("¿Borrar el modelo de IA?")) },
            confirmButton = { TextButton({ confirmDelete = false; app.llm.release(); app.models.deleteLlm() }) { Text(tr("Borrar el modelo"), color = Palette.destructive) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(tr("Cancelar")) } },
        )
    }
}

/** Third-party licences (asset `third_party_notices.txt`). */
@Composable
fun NoticesScreen(app: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = remember { runCatching { context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    Column(Modifier.fillMaxWidth()) {
        DetailTopBar(tr("Licencias de terceros"), onBack = { app.router.pop() })
        Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
            Text(text, fontSize = 13.sp, color = Palette.text, lineHeight = 19.sp)
        }
    }
}
