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
import dev.lumen.designsystem.uiScale
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lu par la navigation à sa création : la recherche du launcher y devient un lien.
        intent = intent.asDeepLink()
        lifecycleScope.launch {
            settings.state.collect { LumenColors.accent = AccentColor.of(it.accent) }
        }
        setContent {
            val prefs by settings.state.collectAsState()
            LumenTheme(scale = uiScale(prefs.uiSize)) { LumenNavHost() }
        }
    }
}
