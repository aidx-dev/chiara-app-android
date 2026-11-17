package com.chiarasystems.chiara.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.ReportPdf
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.Avatar
import com.chiarasystems.chiara.designsystem.GlassIconButton
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.LocalTranslator
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.Radius
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.surface
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.model.ReportSection
import com.chiarasystems.chiara.model.nilIfEmpty
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inline top bar for pushed screens: glass back button, centred title, trailing actions. */
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart)) { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr("Atrás"), onBack) }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Box(Modifier.align(Alignment.CenterEnd)) { actions() }
    }
}

@Composable
fun ReportDetailScreen(app: AppContainer, reportId: String) {
    val reports by app.reports.reports.collectAsState()
    val patients by app.patients.patients.collectAsState()
    val branding by app.auth.branding.collectAsState()
    val user by app.auth.user.collectAsState()
    val report = reports.firstOrNull { it.id == reportId }
    val patient = report?.let { r -> patients.firstOrNull { it.id == r.patientId } }
    val context = LocalContext.current
    val translator = LocalTranslator.current
    val cal = calFormat()
    var pdf by remember { mutableStateOf<File?>(null) }
    val noTitle = tr("Informe de consulta")

    LaunchedEffect(report?.id, translator.code) {
        if (report != null) pdf = withContext(Dispatchers.Default) { ReportPdf.make(context, report, patient, user, branding, translator, cal) }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(tr("Informe"), onBack = { app.router.pop() }) {
            val file = pdf
            if (file != null) {
                GlassIconButton(Icons.Filled.Share, tr("Compartir PDF"), onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, report?.title ?: noTitle)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                })
            }
        }
        if (report == null) {
            Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 60.dp)) {
            Text(tr("INFORME DE CONSULTA"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.72.sp, color = Palette.textMuted)
            Text(report.displayTitle(), Modifier.padding(top = 4.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text)

            val date = report.date?.toLocalDate()
            Row(Modifier.padding(top = 14.dp)) {
                if (patient != null) {
                    Row(
                        Modifier.background(Palette.surface, CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { app.router.push(Route.Patient(patient.id)) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Avatar(patient, size = 32.dp)
                        Column {
                            Text(patient.fullName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
                            Text(date?.let { cal.longDayYear(it) }.orEmpty(), fontSize = 12.sp, color = Palette.textMuted)
                        }
                    }
                } else {
                    Text(date?.let { cal.longDayYear(it) }.orEmpty(), fontSize = 13.sp, color = Palette.textMuted)
                }
            }
            Row(Modifier.padding(top = 10.dp).background(Palette.chip, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Palette.textMuted, modifier = Modifier.size(12.dp))
                Text(tr("Solo lectura"), Modifier.padding(start = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.textMuted)
            }

            Column(Modifier.padding(top = 18.dp).fillMaxWidth().surface(Radius.cardLarge).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                report.sections.forEach { SectionBlock(it) }
                val doctor = branding.doctorName?.nilIfEmpty ?: user?.fullName
                val license = branding.doctorLicense?.nilIfEmpty
                val clinic = branding.clinicName?.nilIfEmpty ?: user?.clinicName
                if (doctor != null || clinic != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Hairline(modifier = Modifier.padding(bottom = 10.dp))
                        doctor?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text) }
                        license?.let { Text(tr("Colegiado %s", it), fontSize = 13.sp, color = Palette.textMuted) }
                        clinic?.let { Text(it, fontSize = 13.sp, color = Palette.textMuted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(section: ReportSection) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(section.title).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = Palette.primary)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val b = section.body) {
                    is ReportSection.Body.Text -> BodyText(b.text)
                    is ReportSection.Body.Diagnoses -> {
                        if (b.text.isNotEmpty()) BodyText(b.text)
                        b.codes.forEach { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.code, Modifier.background(Palette.accent, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary)
                                Text(c.label, fontSize = 16.sp, color = Palette.text)
                            }
                        }
                    }
                    is ReportSection.Body.Medication -> b.lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Medication, null, tint = Palette.infoFg, modifier = Modifier.size(20.dp))
                            Column {
                                Text(line.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                                if (line.dose.isNotEmpty()) Text(line.dose, fontSize = 15.sp, color = Palette.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyText(text: String) = Text(text, fontSize = 16.sp, lineHeight = 24.sp, color = Palette.text)
