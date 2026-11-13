package dev.lumen.tv

import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets

@Composable
fun HelloScreen(state: HelloUiState, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    // Un écran TV doit toujours avoir un élément focalisé.
    LaunchedEffect(Unit) { retryFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical),
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                text = "Lumen",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Jalon 1 · Kotlin ⇄ Rust",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            when (state) {
                HelloUiState.Loading -> StatusLine(null, "Autotest du cœur Rust…")
                is HelloUiState.Failed -> StatusLine(false, "Cœur Rust indisponible : ${state.message}")
                is HelloUiState.Ready -> {
                    val d = state.diagnostics
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusLine(true, "Cœur Rust ${d.coreVersion} chargé (${Build.SUPPORTED_ABIS.first()})")
                        StatusLine(d.asyncOk, "Appels asynchrones (tokio) · ${d.elapsedMs} ms")
                        StatusLine(d.secretStoreOk, "Stockage chiffré (Android Keystore)")
                        StatusLine(
                            d.clientIdConfigured,
                            if (d.clientIdConfigured) "Client ID Twitch configuré"
                            else "Client ID Twitch absent (twitch.clientId dans local.properties)",
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text("Relancer l'autotest")
            }
        }

        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** [ok] : `null` = en cours, `true` = réussi, `false` = échec. */
@Composable
private fun StatusLine(ok: Boolean?, text: String) {
    val dot: Color = when (ok) {
        null -> LumenColors.OnSurfaceMuted
        true -> LumenColors.Success
        false -> LumenColors.Error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Spacer(Modifier.width(16.dp))
        Text(text = text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
