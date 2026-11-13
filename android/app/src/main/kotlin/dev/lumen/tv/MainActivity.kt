package dev.lumen.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.lumen.data.SettingsRepository
import dev.lumen.designsystem.AccentColor
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            settings.state.collect { LumenColors.accent = AccentColor.of(it.accent) }
        }
        setContent {
            LumenTheme { LumenNavHost() }
        }
    }
}
