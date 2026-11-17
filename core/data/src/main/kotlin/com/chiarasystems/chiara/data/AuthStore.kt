package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.model.ClinicBranding
import com.chiarasystems.chiara.model.PatientDraft
import com.chiarasystems.chiara.model.UserProfile
import com.chiarasystems.chiara.model.trimmed
import com.chiarasystems.chiara.network.ApiError
import com.chiarasystems.chiara.network.SecureStore
import com.chiarasystems.chiara.network.Session
import com.chiarasystems.chiara.network.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Messages the login screen can show. The UI maps each to a localised string resource. */
sealed interface AuthMessage {
    data object EnterCredentials : AuthMessage            // "Introduce tu correo y tu contraseña."
    data object InvalidEmail : AuthMessage                // "El correo no tiene un formato válido."
    data object EnableBiometricsFirst : AuthMessage       // "Inicia sesión con tu contraseña una vez para activar Face ID."
    data object BiometricFailed : AuthMessage             // "No se pudo verificar tu identidad."
    data object StoredSessionExpired : AuthMessage        // "Tu sesión ha caducado. Inicia sesión con tu contraseña."
    data object SessionLost : AuthMessage                 // "Tu sesión ha caducado. Vuelve a iniciar sesión."
    data object EnterEmailToRecover : AuthMessage         // "Escribe tu correo para recuperar la contraseña."
    data class Failure(val error: Throwable) : AuthMessage
}

sealed interface AuthNotice {
    data object PasswordResetSent : AuthNotice // "Si la cuenta existe, recibirás un correo para restablecer la contraseña."
}

enum class AuthPhase { SignedOut, SignedIn }

/** Result of the platform biometric prompt (BiometricPrompt on Android). */
enum class BiometricOutcome { Success, Cancelled, Failed }

/** Port of iOS `AuthStore`. Face ID becomes a caller-supplied [BiometricOutcome] check. */
class AuthStore(val client: SupabaseClient, private val secureStore: SecureStore) {
    private val _phase = MutableStateFlow(AuthPhase.SignedOut)
    val phase: StateFlow<AuthPhase> = _phase.asStateFlow()

    private val _user = MutableStateFlow<UserProfile?>(null)
    val user: StateFlow<UserProfile?> = _user.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _message = MutableStateFlow<AuthMessage?>(null)
    val message: StateFlow<AuthMessage?> = _message.asStateFlow()

    private val _notice = MutableStateFlow<AuthNotice?>(null)
    val notice: StateFlow<AuthNotice?> = _notice.asStateFlow()

    /** Clinic branding for the PDF footer (doctor, license number, clinic name). */
    private val _branding = MutableStateFlow(ClinicBranding())
    val branding: StateFlow<ClinicBranding> = _branding.asStateFlow()

    init {
        client.onSessionLost = { sessionLost() }
    }

    val hasStoredSession: Boolean get() = secureStore.get(SecureStore.REFRESH_TOKEN) != null
    val lastEmail: String get() = secureStore.get(SecureStore.LAST_EMAIL).orEmpty()

    fun clearMessage() { _message.value = null }

    // --- Sign in -------------------------------------------------------------------------------------------------

    suspend fun signIn(email: String, password: String) {
        _message.value = null
        val mail = email.trimmed
        if (mail.isEmpty() || password.isEmpty()) { _message.value = AuthMessage.EnterCredentials; return }
        if (!PatientDraft.EMAIL.matches(mail)) { _message.value = AuthMessage.InvalidEmail; return }
        _isWorking.value = true
        try {
            val session = client.signIn(mail, password)
            secureStore.set(mail, SecureStore.LAST_EMAIL)
            finishSignIn(session, mail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = AuthMessage.Failure(e)
        } finally {
            _isWorking.value = false
        }
    }

    /** Re-opens the stored session after the platform biometric check [verify] succeeds. */
    suspend fun signInWithBiometrics(verify: suspend () -> BiometricOutcome) {
        _message.value = null
        val token = secureStore.get(SecureStore.REFRESH_TOKEN)
        if (token == null) { _message.value = AuthMessage.EnableBiometricsFirst; return }
        when (verify()) {
            BiometricOutcome.Cancelled -> return
            BiometricOutcome.Failed -> { _message.value = AuthMessage.BiometricFailed; return }
            BiometricOutcome.Success -> Unit
        }
        _isWorking.value = true
        try {
            val session = client.restore(token)
            finishSignIn(session, lastEmail)
        } catch (e: ApiError.InvalidCredentials) {
            // The refresh token was revoked or expired.
            secureStore.remove(SecureStore.REFRESH_TOKEN)
            _message.value = AuthMessage.StoredSessionExpired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = AuthMessage.Failure(e)
        } finally {
            _isWorking.value = false
        }
    }

    /** GoTrue `recover`: always answers 2xx, whether or not the account exists. */
    suspend fun requestPasswordReset(email: String) {
        val mail = email.trimmed
        if (!PatientDraft.EMAIL.matches(mail)) { _message.value = AuthMessage.EnterEmailToRecover; return }
        try {
            client.recoverPassword(mail)
            _message.value = null
            _notice.value = AuthNotice.PasswordResetSent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = AuthMessage.Failure(e)
        }
    }

    suspend fun signOut() {
        client.signOut()
        _user.value = null
        _phase.value = AuthPhase.SignedOut
    }

    private fun sessionLost() {
        if (_phase.value != AuthPhase.SignedIn) return
        _user.value = null
        _phase.value = AuthPhase.SignedOut
        _message.value = AuthMessage.SessionLost
    }

    // --- Profile -------------------------------------------------------------------------------------------------

    @Serializable
    private data class ProfileRow(
        @SerialName("clinic_id") val clinicId: String? = null,
        val role: String? = null,
        @SerialName("full_name") val fullName: String? = null,
        val specialties: List<String>? = null,
    )

    private suspend fun finishSignIn(session: Session, fallbackEmail: String) {
        // `profiles` is what the desktop app reads at sign-in. The JWT claims are the fallback.
        val row = runCatching { client.decode<List<ProfileRow>>(client.get("profiles?user_id=eq.${session.userId}&select=clinic_id,role,full_name,specialties")) }
            .getOrNull()?.firstOrNull()
        val claims = client.claims
        val clinicId = row?.clinicId ?: claims.clinicId
        if (clinicId == null) {
            client.signOut()
            throw ApiError.Server(
                403,
                "La cuenta existe pero no está asignada a ninguna clínica. Pide a quien administre la clínica que te dé de alta.",
            )
        }

        // Cosmetic lookups; a failure only loses the clinic name.
        val branding = loadBranding()
        var clinicName = branding.clinicName
        if (clinicName == null) {
            clinicName = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(client.get("clinics?id=eq.$clinicId&select=name")) as JsonArray)
                    .firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

        _branding.value = branding
        _user.value = UserProfile(
            id = session.userId,
            email = session.email ?: fallbackEmail,
            fullName = row?.fullName ?: branding.doctorName,
            role = row?.role ?: claims.clinicRole ?: "doctor",
            clinicId = clinicId,
            clinicName = clinicName,
            specialties = row?.specialties ?: emptyList(),
        )
        _phase.value = AuthPhase.SignedIn
    }

    private suspend fun loadBranding(): ClinicBranding =
        runCatching { client.decode<ClinicBranding?>(client.rpc("get_clinic_branding")) }.getOrNull() ?: ClinicBranding()
}
