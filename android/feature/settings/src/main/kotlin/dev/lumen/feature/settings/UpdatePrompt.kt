package dev.lumen.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.data.messageRes
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached

/** Proposition d'une nouvelle version, une fois par lancement, au-dessus de l'accueil. */
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.checkOnLaunch() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update = when (val s = state) {
        is UpdateState.Available -> s.update
        is UpdateState.Downloading -> s.update
        else -> null
    }
    val failed = state as? UpdateState.Failed
    if (update == null && failed == null) return

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    Dialog(onDismissRequest = viewModel::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(LumenColors.Surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (update != null) {
                Text(stringResource(R.string.update_available, update.version), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.update_size, "%.1f".format(update.apkSize.toDouble() / 1_000_000)),
                    color = LumenColors.OnSurfaceMuted,
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        text = update.notes.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
            } else if (failed != null) {
                Text(stringResource(failed.error.messageRes), style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val downloading = state is UpdateState.Downloading
                if (update != null) {
                    Button(onClick = viewModel::install, enabled = !downloading, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(if (downloading) R.string.update_downloading else R.string.update_install))
                    }
                }
                Button(
                    onClick = viewModel::dismiss,
                    modifier = if (update == null) Modifier.focusRequester(focus) else Modifier,
                ) { Text(stringResource(R.string.update_later)) }
            }
        }
    }
}
