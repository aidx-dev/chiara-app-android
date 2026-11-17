package com.chiarasystems.chiara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.chiarasystems.chiara.Biometrics
import com.chiarasystems.chiara.data.AuthStore
import com.chiarasystems.chiara.designsystem.ChiaraMonogram
import com.chiarasystems.chiara.designsystem.GlassPillButton
import com.chiarasystems.chiara.designsystem.Hairline
import com.chiarasystems.chiara.designsystem.Palette
import com.chiarasystems.chiara.designsystem.PrimaryButton
import com.chiarasystems.chiara.designsystem.Radius
import com.chiarasystems.chiara.designsystem.glass
import com.chiarasystems.chiara.designsystem.tr
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(auth: AuthStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val working by auth.isWorking.collectAsState()
    val message by auth.message.collectAsState()
    val notice by auth.notice.collectAsState()
    var email by remember { mutableStateOf(auth.lastEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val biometricsAvailable = remember { Biometrics.available(context) && auth.hasStoredSession }
    val reason = tr("Abrir tu sesión de Chiara")
    val cancel = tr("Cancelar")

    fun submit() { focus.clearFocus(); scope.launch { auth.signIn(email, password) } }
    fun clearNotes() { auth.clearMessage() }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        LoginBackground()
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 480.dp).fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))
            Box(Modifier.size(96.dp).glass(CircleShape), contentAlignment = Alignment.Center) { ChiaraMonogram(size = 52.dp) }
            Text(
                "Chiara", Modifier.padding(top = 18.dp), fontSize = 38.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp, color = Palette.text,
            )
            Text(tr("Inicia sesión con tu cuenta de la clínica"), Modifier.padding(top = 10.dp), fontSize = 15.sp, color = Palette.textMuted)

            Column(
                Modifier.padding(top = 36.dp).fillMaxWidth()
                    .background(Palette.surface.copy(alpha = 0.8f), RoundedCornerShape(Radius.card.dp))
                    .border(0.5.dp, Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(Radius.card.dp)),
            ) {
                CredentialRow(
                    icon = { Icon(Icons.Filled.Mail, null, tint = Palette.textFaint, modifier = Modifier.size(22.dp)) },
                    value = email, onChange = { email = it; clearNotes() }, placeholder = tr("Correo electrónico"),
                    keyboardType = KeyboardType.Email, ime = ImeAction.Next, onAction = { focus.moveFocus(FocusDirection.Down) },
                )
                Hairline(inset = 50.dp)
                CredentialRow(
                    icon = { Icon(Icons.Filled.Lock, null, tint = Palette.textFaint, modifier = Modifier.size(22.dp)) },
                    value = password, onChange = { password = it; clearNotes() }, placeholder = tr("Contraseña"),
                    keyboardType = KeyboardType.Password, ime = ImeAction.Go, onAction = { submit() },
                    transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (showPassword) tr("Ocultar contraseña") else tr("Mostrar contraseña"),
                            tint = Palette.textFaint,
                            modifier = Modifier.size(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { showPassword = !showPassword }.padding(10.dp),
                        )
                    },
                )
            }

            message?.let {
                Text(it.text(), Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), fontSize = 13.sp, color = Palette.destructive)
            }
            notice?.let {
                Text(it.text(), Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), fontSize = 13.sp, color = Palette.infoFg)
            }

            PrimaryButton(
                if (working) tr("Iniciando sesión…") else tr("Iniciar sesión"), onClick = ::submit,
                modifier = Modifier.padding(top = 22.dp), enabled = !working,
            )
            if (biometricsAvailable) {
                GlassPillButton(
                    tr("Entrar con Face ID"), enabled = !working, modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            auth.signInWithBiometrics { Biometrics.authenticate(context as FragmentActivity, reason, cancel) }
                        }
                    },
                )
            }
            Text(
                tr("¿Olvidaste tu contraseña?"),
                Modifier.padding(top = 20.dp, bottom = 90.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { scope.launch { auth.requestPasswordReset(email) } },
                fontSize = 15.sp, color = Palette.primary,
            )
        }
    }
}

@Composable
private fun CredentialRow(
    icon: @Composable () -> Unit,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    ime: ImeAction,
    onAction: () -> Unit,
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        TextField(
            value, onChange, Modifier.weight(1f), singleLine = true, visualTransformation = transformation,
            placeholder = { Text(placeholder, fontSize = 17.sp, color = Palette.textFaint) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Palette.text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ime, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onGo = { onAction() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = Palette.primary,
            ),
        )
        trailing?.invoke()
    }
}

/** `#f3f3f6` with three soft blobs (radial fades: a blur modifier would clip them to squares). */
@Composable
fun LoginBackground() {
    val p = Palette
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        fun blob(color: androidx.compose.ui.graphics.Color, cx: Float, cy: Float, r: Float) {
            val c = Offset(cx.dp.toPx(), cy.dp.toPx())
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = c, radius = r.dp.toPx()), r.dp.toPx(), c)
        }
        blob(p.chart3, 20f, 60f, 230f)
        blob(p.chart2.copy(alpha = 0.18f), 360f, 300f, 250f)
        blob(p.infoBg, 60f, 700f, 220f)
    }
}
