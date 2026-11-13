package dev.lumen.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lumen.designsystem.LumenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LumenApp
        setContent {
            LumenTheme {
                val viewModel: HelloViewModel = viewModel(factory = HelloViewModel.factory(app.coreGateway))
                val state = viewModel.state.collectAsStateWithLifecycle()
                HelloScreen(state = state.value, onRetry = viewModel::runDiagnostics)
            }
        }
    }
}
