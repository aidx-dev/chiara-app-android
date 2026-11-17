package com.chiarasystems.chiara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.chiarasystems.chiara.designsystem.ChiaraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChiaraTheme { Text("Chiara") } } // TODO(MIGRATION Phase 3): RootView / MainShell
    }
}
