package dev.lumen.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.lumen.designsystem.QrCode
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import kotlinx.coroutines.delay

@Composable
fun DeviceLoginRoute(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state is DeviceLoginUiState.Success) {
        // Laisse le temps de lire le message de bienvenue.
        LaunchedEffect(Unit) {
            delay(1_200)
            onDone()
        }
    }
    DeviceLoginScreen(state = state, onRetry = viewModel::start, onBack = onBack)
}

@Composable
fun DeviceLoginScreen(state: DeviceLoginUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(28.dp))

            when (state) {
                DeviceLoginUiState.Requesting -> Muted(stringResource(R.string.login_requesting))

                is DeviceLoginUiState.WaitingForUser -> {
                    Muted(stringResource(R.string.login_step_scan))
                    Text(
                        text = stringResource(R.string.login_url),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Muted(stringResource(R.string.login_step_code))
                    Text(
                        text = state.userCode,
                        style = MaterialTheme.typography.displayLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(20.dp))
                    Muted(stringResource(R.string.login_waiting))
                }

                is DeviceLoginUiState.Success -> Text(
                    text = stringResource(R.string.login_success, state.displayName),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LumenColors.Success,
                )

                is DeviceLoginUiState.Failed -> Text(
                    text = stringResource(
                        when (state.error) {
                            LoginError.Expired -> R.string.login_error_expired
                            LoginError.Network -> R.string.login_error_network
                            LoginError.NotConfigured -> R.string.login_error_not_configured
                            LoginError.Storage -> R.string.login_error_storage
                            LoginError.Other -> R.string.login_error_other
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LumenColors.Error,
                )
            }

            Spacer(Modifier.height(36.dp))
            // Toujours un élément focalisable, quel que soit l'état : le focus ne se perd jamais.
            val focus = remember { FocusRequester() }
            val canRetry = state is DeviceLoginUiState.Failed && state.error != LoginError.NotConfigured
            LaunchedEffect(canRetry) { runCatching { focus.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (canRetry) {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.login_retry))
                    }
                }
                OutlinedButton(onClick = onBack, modifier = if (canRetry) Modifier else Modifier.focusRequester(focus)) {
                    Text(stringResource(R.string.login_back))
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.login_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(modifier = Modifier.width(300.dp), contentAlignment = Alignment.Center) {
            if (state is DeviceLoginUiState.WaitingForUser) QrCode(state.qr.size.toInt(), state.qr.modules)
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
