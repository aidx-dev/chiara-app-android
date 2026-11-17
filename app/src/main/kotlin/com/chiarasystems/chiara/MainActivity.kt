package com.chiarasystems.chiara

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.chiarasystems.chiara.data.AppearanceMode
import com.chiarasystems.chiara.designsystem.ChiaraTheme
import com.chiarasystems.chiara.designsystem.LocalTranslator
import com.chiarasystems.chiara.ui.RootScreen

class ChiaraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** FragmentActivity (not just ComponentActivity): `BiometricPrompt` needs one. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = (application as ChiaraApplication).container
        setContent {
            val appearance by app.settings.appearance.collectAsState()
            val language by app.settings.language.collectAsState()
            val dark = when (appearance) {
                AppearanceMode.System -> isSystemInDarkTheme()
                AppearanceMode.Light -> false
                AppearanceMode.Dark -> true
            }
            val translator = app.translator(app.settings.code.also { language })
            CompositionLocalProvider(LocalTranslator provides translator) {
                ChiaraTheme(dark) { RootScreen(app) }
            }
        }
    }
}
