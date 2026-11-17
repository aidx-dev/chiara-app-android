package com.chiarasystems.chiara

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.chiarasystems.chiara.data.BiometricOutcome
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Port of iOS `LAContext` use: the platform biometric prompt that unlocks the stored session. */
object Biometrics {
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(activity: FragmentActivity, title: String, cancel: String): BiometricOutcome =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(BiometricOutcome.Success)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                            code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_CANCELED
                        if (cont.isActive) cont.resume(if (cancelled) BiometricOutcome.Cancelled else BiometricOutcome.Failed)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setNegativeButtonText(cancel)
                .setAllowedAuthenticators(BIOMETRIC_STRONG).build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
