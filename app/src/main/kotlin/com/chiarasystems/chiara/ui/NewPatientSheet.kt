package com.chiarasystems.chiara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiarasystems.chiara.AppContainer
import com.chiarasystems.chiara.data.AppTab
import com.chiarasystems.chiara.data.Route
import com.chiarasystems.chiara.designsystem.GroupedList
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.Radius
import com.chiarasystems.chiara.designsystem.SectionLabel
import com.chiarasystems.chiara.designsystem.calFormat
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.UuidV7
import com.chiarasystems.chiara.model.PatientDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** Bottom sheet with the iOS sheet chrome: inline title, close on the left, confirm on the right. */
@Composable
fun AppSheet(
    title: String, onClose: () -> Unit, confirmLabel: String? = null, confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {}, skipPartially: Boolean = true, content: @Composable () -> Unit,
) {
    val state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartially)
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = state, containerColor = Palette.background,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(tr("Cerrar"), color = Palette.primary, fontSize = 17.sp) }
                Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (confirmLabel != null) {
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(confirmLabel, color = if (confirmEnabled) Palette.primary else Palette.disabled, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else Box(Modifier.padding(horizontal = 24.dp))
            }
            content()
        }
    }
}

/** Label above a borderless text field, inside a [GroupedList] row. */
@Composable
fun FormField(
    label: String, value: String, onChange: (String) -> Unit, prompt: String,
    keyboard: KeyboardType = KeyboardType.Text, capitalization: KeyboardCapitalization = KeyboardCapitalization.Words, autoCorrect: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(label, fontSize = 13.sp, color = Palette.textFaint)
        TextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(prompt, fontSize = 17.sp, color = Palette.disabled) },
            textStyle = TextStyle(fontSize = 17.sp, color = Palette.text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = capitalization, autoCorrectEnabled = autoCorrect),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Palette.primary,
            ),
        )
    }
}

/** Segmented control: one pill per option. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(36.dp).background(Palette.field, CircleShape).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(30.dp).background(if (on) Palette.surface else Color.Transparent, CircleShape).clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = Palette.text) }
        }
    }
}

@Composable
fun NewPatientSheet(app: AppContainer) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(PatientDraft()) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val reservedId = remember { UuidV7.generate().toString() } // one id per sheet: saving twice reuses it
    var showDate by remember { mutableStateOf(false) }
    val cal = calFormat()
    val needNames = tr("Nombre y apellidos son obligatorios.")
    val badEmail = tr("El correo no tiene un formato válido.")
    val created = tr("Paciente creado")
    val duplicate = tr("Ya existe un paciente con ese DNI.")
    val errorText: (Throwable) -> String = { it.message.orEmpty() }

    fun edit(block: PatientDraft.() -> PatientDraft) { draft = draft.block(); error = "" }

    fun save() {
        if (saving) return
        if (!draft.canSave) { error = needNames; return }
        if (!draft.emailIsValid) { error = badEmail; return }
        saving = true
        scope.launch {
            try {
                val p = app.patients.create(draft, reservedId)
                app.router.dismissSheet()
                app.router.select(AppTab.Patients)
                app.router.push(Route.Patient(p.id))
                app.toast.show(created)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is com.chiarasystems.chiara.data.DuplicateDocumentException) duplicate else e.message.orEmpty()
            } finally { saving = false }
        }
    }

    AppSheet(tr("Nuevo paciente"), onClose = { if (!saving) app.router.dismissSheet() }, confirmLabel = tr("Guardar"), confirmEnabled = draft.canSave && !saving, onConfirm = ::save) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), fontSize = 13.sp, color = Palette.destructive)

            SectionLabel(tr("IDENTIDAD"), Modifier.padding(top = 6.dp, bottom = 8.dp))
            GroupedList {
                row { FormField(tr("Nombre"), draft.firstName, { edit { copy(firstName = it) } }, tr("Nombre")) }
                row { FormField(tr("Apellidos"), draft.lastName, { edit { copy(lastName = it) } }, tr("Apellidos")) }
                row {
                    FormField(tr("DNI"), draft.documentId, { edit { copy(documentId = it) } }, tr("Ej. 12345678Z"), capitalization = KeyboardCapitalization.Characters, autoCorrect = false)
                }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Text(tr("Fecha de nacimiento"), fontSize = 13.sp, color = Palette.textFaint)
                        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                            val dob = draft.dateOfBirth
                            if (dob != null) {
                                Text(cal.birthDate(dob), Modifier.clickable { showDate = true }, fontSize = 17.sp, color = Palette.primary)
                                Box(Modifier.weight(1f))
                                Text(tr("Quitar fecha"), Modifier.clickable { edit { copy(dateOfBirth = null) } }, fontSize = 15.sp, color = Palette.textFaint)
                            } else {
                                Text(tr("Añadir fecha"), Modifier.clickable { edit { copy(dateOfBirth = LocalDate.of(1990, 1, 1)) }; showDate = true }, fontSize = 17.sp, color = Palette.primary)
                            }
                        }
                    }
                }
            }

            SectionLabel(tr("SEXO"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            Segmented(
                listOf(PatientDraft.Sex.Female to tr("Mujer"), PatientDraft.Sex.Male to tr("Hombre"), PatientDraft.Sex.Other to tr("Otro")),
                draft.sex, { edit { copy(sex = it) } },
            )

            SectionLabel(tr("CONTACTO"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            GroupedList {
                row { FormField(tr("Teléfono"), draft.phone, { edit { copy(phone = it) } }, tr("600 000 000"), KeyboardType.Phone, KeyboardCapitalization.None, false) }
                row { FormField(tr("Correo"), draft.email, { edit { copy(email = it) } }, tr("nombre@correo.es"), KeyboardType.Email, KeyboardCapitalization.None, false) }
            }

            SectionLabel(tr("COBERTURA"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            GroupedList {
                row { FormField(tr("Aseguradora"), draft.insuranceCompany, { edit { copy(insuranceCompany = it) } }, tr("Ej. Sanitas, Adeslas…")) }
                row { FormField(tr("Clínica"), draft.referringClinic, { edit { copy(referringClinic = it) } }, tr("Ej. clínica del Dr. García")) }
            }
        }
    }

    if (showDate) {
        val initial = draft.dateOfBirth ?: LocalDate.of(1990, 1, 1)
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!d.isAfter(LocalDate.now())) edit { copy(dateOfBirth = d) }
                    }
                    showDate = false
                }) { Text(tr("Aceptar")) }
            },
            dismissButton = { TextButton({ showDate = false }) { Text(tr("Cancelar")) } },
        ) { DatePicker(state) }
    }
}
