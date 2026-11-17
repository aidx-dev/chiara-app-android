package com.chiarasystems.chiara

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.chiarasystems.chiara.data.AuthPhase
import com.chiarasystems.chiara.data.AuthStore
import com.chiarasystems.chiara.designsystem.ChiaraTheme
import com.chiarasystems.chiara.designsystem.LocalTranslator
import com.chiarasystems.chiara.designsystem.PrimaryButton
import com.chiarasystems.chiara.designsystem.StatusBadge
import com.chiarasystems.chiara.designsystem.Translator
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.network.DemoBackend
import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.InMemorySecureStore
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import com.chiarasystems.chiara.ui.LoginScreen
import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose UI tests on the JVM: the login flow against the demo backend and the design-system components. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeUiTest {
    @get:Rule val rule = createComposeRule()

    private val english = Translator(
        "en",
        mapOf(
            "Iniciar sesión" to "Sign in", "Correo electrónico" to "Email address", "Contraseña" to "Password",
            "Introduce tu correo y tu contraseña." to "Enter your email and password.", "Programada" to "Scheduled",
            "Cancelada" to "Canceled",
        ),
    )

    private fun auth(): AuthStore {
        val dir = File.createTempFile("uitest", "").also { it.delete() }
        val store = InMemorySecureStore()
        val client = SupabaseClient(
            SupabaseConfig("http://demo.invalid/", "anon"), store, FileResponseCache(dir),
            OkHttpClient.Builder().addInterceptor(DemoBackend()).build(),
        )
        return AuthStore(client, store)
    }

    @Test fun loginShowsItsFieldsInTheLanguageInUse() {
        rule.setContent { CompositionLocalProvider(LocalTranslator provides english) { ChiaraTheme(false) { LoginScreen(auth()) } } }
        rule.onNodeWithText("Chiara").assertIsDisplayed()
        rule.onNodeWithText("Email address").assertIsDisplayed()
        rule.onNodeWithText("Password").assertIsDisplayed()
        rule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test fun anEmptyFormIsRefusedBeforeAnyRequest() {
        val auth = auth()
        rule.setContent { CompositionLocalProvider(LocalTranslator provides english) { ChiaraTheme(false) { LoginScreen(auth) } } }
        rule.onNodeWithText("Sign in").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter your email and password.").assertIsDisplayed()
        assertEquals(AuthPhase.SignedOut, auth.phase.value)
    }

    @Test fun validCredentialsSignInThroughTheDemoBackend() {
        val auth = auth()
        rule.setContent { CompositionLocalProvider(LocalTranslator provides english) { ChiaraTheme(true) { LoginScreen(auth) } } }
        rule.onNodeWithText("Email address").performTextInput("lucia.ferrer@clinicamediterranea.es")
        rule.onNodeWithText("Password").performTextInput("secret")
        rule.onNodeWithText("Sign in").performClick()
        rule.waitUntil(5_000) { auth.phase.value == AuthPhase.SignedIn }
        assertEquals("Clínica Mediterránea", auth.user.value?.clinicName)
    }

    @Test fun theDesignSystemButtonsAndBadgesBehave() {
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalTranslator provides english) {
                ChiaraTheme(false) {
                    androidx.compose.foundation.layout.Column {
                        PrimaryButton("Go", { clicks++ })
                        PrimaryButton("Off", { clicks += 100 }, enabled = false)
                        StatusBadge(AppointmentStatus.Scheduled)
                        StatusBadge(AppointmentStatus.Canceled)
                    }
                }
            }
        }
        rule.onNodeWithText("Go").assertIsEnabled().performClick()
        rule.onNodeWithText("Off").assertIsNotEnabled()
        rule.onNodeWithText("Scheduled").assertIsDisplayed()
        rule.onNodeWithText("Canceled").assertIsDisplayed()
        assertEquals(1, clicks)
    }
}
