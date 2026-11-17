package com.chiarasystems.chiara.ui

import androidx.compose.runtime.Composable
import com.chiarasystems.chiara.data.AuthMessage
import com.chiarasystems.chiara.data.AuthNotice
import com.chiarasystems.chiara.data.DuplicateDocumentException
import com.chiarasystems.chiara.designsystem.tr
import com.chiarasystems.chiara.domain.AgendaRules
import com.chiarasystems.chiara.network.ApiError

/** Maps errors to localised user text (the keys are the Spanish default messages, as in iOS). */
@Composable
fun Throwable.userMessage(): String = when (this) {
    is ApiError.Offline -> tr("No se pudo conectar con el servidor.")
    is ApiError.InvalidCredentials -> tr("Usuario o contraseña incorrectos.")
    is ApiError.SessionExpired -> tr("Tu sesión ha caducado. Vuelve a iniciar sesión.")
    is ApiError.NotSignedIn -> tr("No hay una sesión iniciada.")
    is ApiError.Server -> serverMessage
    is ApiError.Unreadable -> tr("El servidor devolvió una respuesta ilegible (%s).", what)
    is DuplicateDocumentException -> tr("Ya existe un paciente con ese DNI.")
    else -> message ?: toString()
}

@Composable
fun AuthMessage.text(): String = when (this) {
    AuthMessage.EnterCredentials -> tr("Introduce tu correo y tu contraseña.")
    AuthMessage.InvalidEmail -> tr("El correo no tiene un formato válido.")
    AuthMessage.EnableBiometricsFirst -> tr("Inicia sesión con tu contraseña una vez para activar Face ID.")
    AuthMessage.BiometricFailed -> tr("No se pudo verificar tu identidad.")
    AuthMessage.StoredSessionExpired -> tr("Tu sesión ha caducado. Inicia sesión con tu contraseña.")
    AuthMessage.SessionLost -> tr("Tu sesión ha caducado. Vuelve a iniciar sesión.")
    AuthMessage.EnterEmailToRecover -> tr("Escribe tu correo para recuperar la contraseña.")
    is AuthMessage.Failure -> error.userMessage()
}

@Composable
fun AuthNotice.text(): String = when (this) {
    AuthNotice.PasswordResetSent -> tr("Si la cuenta existe, recibirás un correo para restablecer la contraseña.")
}
