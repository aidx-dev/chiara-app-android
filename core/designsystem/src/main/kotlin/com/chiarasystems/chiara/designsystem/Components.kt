package com.chiarasystems.chiara.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Patient
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

// --- Surfaces ----------------------------------------------------------------------------------------------------

/** White clinical surface (lists, cards, reports). Glass is reserved for the navigation/control layer. */
@Composable
fun Modifier.surface(radius: Int = Radius.card): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this.shadow(1.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.05f))
        .background(Palette.surface, shape)
}

/** The blurred backdrop of the navigation layer: the screen content is the haze source, glass elements sample it. */
val LocalHazeState = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * "Glass" for the navigation layer (tab bar, toast, floating buttons): the content behind it, blurred, under a translucent
 * tint and a hairline edge. Where no [LocalHazeState] is provided (controls inside the scrolling content itself) it is the
 * tint and the edge alone.
 */
@Composable
fun Modifier.glass(shape: Shape = CircleShape): Modifier {
    val p = Palette
    val haze = LocalHazeState.current
    val tint = if (p.dark) Color(0xFF2A2A2E) else Color.White
    val edge = if (p.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val base = this.shadow(8.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.12f))
    val surface = if (haze != null) {
        base.clip(shape).hazeChild(haze, HazeStyle(backgroundColor = p.background, tint = HazeTint(tint.copy(alpha = 0.55f)), blurRadius = 24.dp))
    } else base.background(tint.copy(alpha = 0.82f), shape)
    return surface.border(BorderStroke(0.5.dp, edge), shape)
}

@Composable
fun Hairline(inset: Dp = 0.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(Palette.separator))
}

/** Small section label ("IDENTIDAD", "FECHA"…). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.textMuted)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics { heading() },
        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text,
    )
}

// --- Monogram / avatar / badges ------------------------------------------------------------------------------------

/** Chiara monogram: "C" arc plus a dot (SVG 32×32 of the handoff). */
@Composable
fun ChiaraMonogram(size: Dp = 52.dp, color: Color = Palette.primary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 32f
        // Arc of radius 10 around (16,16): the long way from 40° to 320° (the opening faces right).
        val r = 10f * s
        drawArc(
            color = color, startAngle = 40f, sweepAngle = 280f, useCenter = false,
            topLeft = Offset(16f * s - r, 16f * s - r), size = Size(2 * r, 2 * r),
            style = Stroke(width = 3.2f * s, cap = StrokeCap.Round),
        )
        drawCircle(color, radius = 1.7f * s, center = Offset(22.4f * s, 16f * s))
    }
}

@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Pair<Color, Color> = Palette.avatarTints[0]) {
    Box(modifier.size(size).background(tint.first, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold, color = tint.second)
    }
}

@Composable
fun Avatar(patient: Patient, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    Avatar(patient.initials, modifier, size, Palette.avatarTints[patient.tintIndex % Palette.avatarTints.size])

@Composable
fun AppointmentStatus.label(): String = when (this) {
    AppointmentStatus.Scheduled -> tr("Programada")
    AppointmentStatus.Confirmed -> tr("Confirmada")
    AppointmentStatus.Completed -> tr("Completada")
    AppointmentStatus.Canceled -> tr("Cancelada")
    AppointmentStatus.NoShow -> tr("No se presentó")
}

@Composable
fun AppointmentStatus.foreground(): Color = when (this) {
    AppointmentStatus.Scheduled -> Palette.infoFg
    AppointmentStatus.Confirmed -> Palette.warningFg
    AppointmentStatus.Completed -> Palette.successFg
    AppointmentStatus.Canceled -> Palette.destructive
    AppointmentStatus.NoShow -> Palette.primary
}

@Composable
fun AppointmentStatus.background(): Color = when (this) {
    AppointmentStatus.Scheduled -> Palette.infoBg
    AppointmentStatus.Confirmed -> Palette.warningBg
    AppointmentStatus.Completed -> Palette.successBg
    AppointmentStatus.Canceled -> Palette.destructive.copy(alpha = 0.10f)
    AppointmentStatus.NoShow -> Palette.accent
}

@Composable
fun StatusBadge(status: AppointmentStatus, modifier: Modifier = Modifier) {
    Text(
        status.label(), modifier.background(status.background(), CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = status.foreground(),
    )
}

// --- Buttons -----------------------------------------------------------------------------------------------------

/** Primary pill button (#005bb6), 54 dp. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Palette.primary) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier.fillMaxWidth().height(54.dp).scale(if (pressed) 0.98f else 1f)
            .shadow(if (enabled) 6.dp else 0.dp, CircleShape, ambientColor = tint.copy(alpha = 0.30f), spotColor = tint.copy(alpha = 0.30f))
            .background(if (enabled) tint else Palette.disabled, CircleShape)
            .clip(CircleShape)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}

/** Glass pill button, 54 dp (e.g. "Entrar con huella"). */
@Composable
fun GlassPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.fillMaxWidth().height(54.dp).glass(CircleShape).clip(CircleShape).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.primary) }
}

/** Round glass button that opens the assistant (the moving outline arrives with the assistant, Phase 6). */
@Composable
fun AssistantButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = tr("Asistente")
    Box(
        modifier.size(48.dp).glass(CircleShape).glowBorder(CircleShape, GlowState.Idle, subtle = true).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { ChiaraMonogram(size = 34.dp) }
}

// --- Rows & groups -----------------------------------------------------------------------------------------------

/** Label + value row for "Datos del paciente". A [link] makes the value tappable (tel:/mailto:). */
@Composable
fun FieldRow(label: String, value: String, modifier: Modifier = Modifier, link: String? = null) {
    val uri = LocalUriHandler.current
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 17.sp, color = Palette.text)
        Spacer(Modifier.weight(1f).size(16.dp))
        Text(
            value, fontSize = 17.sp, textAlign = TextAlign.End,
            color = if (link != null) Palette.primary else Palette.text,
            modifier = if (link != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { runCatching { uri.openUri(link) } } else Modifier,
        )
    }
}

class GroupedListScope { internal val rows = mutableListOf<@Composable () -> Unit>(); fun row(content: @Composable () -> Unit) { rows += content } }

/** White grouped container with hairlines between children. */
@Composable
fun GroupedList(modifier: Modifier = Modifier, content: GroupedListScope.() -> Unit) {
    val rows = GroupedListScope().apply(content).rows
    Column(modifier.fillMaxWidth().surface()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Hairline(inset = 16.dp)
            row()
        }
    }
}

data class MenuOption<T>(val value: T, val label: String)

/** "Title … Choice ⌄" row: the chosen name stays on one line and is cut with "…"; the open menu shows every name in full. */
@Composable
fun <T> MenuPicker(title: String, selection: T, options: List<MenuOption<T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.value == selection }?.label.orEmpty()
    Row(modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, color = Palette.text, maxLines = 1)
        Spacer(Modifier.weight(1f).size(8.dp))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chosen, fontSize = 16.sp, color = Palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                Icon(Icons.Filled.ExpandMore, null, tint = Palette.primary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { o -> DropdownMenuItem({ Text(o.label) }, { onSelect(o.value); open = false }) }
            }
        }
    }
}

// --- Notices -----------------------------------------------------------------------------------------------------

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Palette.warningBg, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SignalWifiOff, null, tint = Palette.warningFg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(tr("Sin conexión · mostrando datos guardados"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.warningFg)
    }
}

@Composable
fun ErrorNote(message: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = Palette.destructive, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(message, fontSize = 13.sp, color = Palette.destructive)
    }
}

/** Glass search field, 44 dp (Patients and Reports). */
@Composable
fun GlassSearchField(text: String, onTextChange: (String) -> Unit, prompt: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Search) {
    Row(
        modifier.fillMaxWidth().height(44.dp).glass(CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.textFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(prompt, fontSize = 17.sp, color = Palette.textFaint)
            BasicTextField(
                text, onTextChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Palette.text),
                cursorBrush = SolidColor(Palette.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotEmpty()) {
            val clear = tr("Borrar búsqueda")
            Icon(
                Icons.Filled.Cancel, clear, tint = Palette.textFaint,
                modifier = Modifier.size(22.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { onTextChange("") },
            )
        }
    }
}

// --- Screen header & toolbar buttons ---------------------------------------------------------------------------------

/** iOS large navigation title with optional subtitle and trailing toolbar actions; scrolls with the content. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, fontSize = 15.sp, color = Palette.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Round glass toolbar button with an icon ("+", back…). */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(44.dp).glass(CircleShape).clip(CircleShape).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Palette.primary, modifier = Modifier.size(22.dp)) }
}

/** Glass capsule text button ("Hoy"). */
@Composable
fun GlassTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.height(44.dp).glass(CircleShape).clip(CircleShape).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.primary) }
}
