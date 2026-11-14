package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.core.LiveStream
import dev.lumen.designsystem.Avatar
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached

/** Choix des chaînes à ajouter à celle en cours, jusqu'à [maxExtra]. */
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MultiPicker(
    load: suspend () -> List<LiveStream>,
    maxExtra: Int,
    onLaunch: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var candidates by remember { mutableStateOf<List<LiveStream>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { candidates = load() }
    LaunchedEffect(candidates != null) { if (candidates != null) focus.requestWhenAttached() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(900.dp)
                // Écran TV = 540 dp de haut : les boutons doivent rester visibles sous une longue grille.
                .heightIn(max = 500.dp)
                .background(LumenColors.Surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(pluralStringResource(R.plurals.multi_pick_title, maxExtra, maxExtra), style = MaterialTheme.typography.headlineSmall)
            val list = candidates
            when {
                list == null -> Text(stringResource(R.string.multi_pick_loading), color = LumenColors.OnSurfaceMuted)
                list.isEmpty() -> Text(stringResource(R.string.multi_pick_empty), color = LumenColors.OnSurfaceMuted)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.login }) { stream ->
                        val checked = stream.login in selected
                        FilterChip(
                            selected = checked,
                            onClick = {
                                when {
                                    checked -> selected.remove(stream.login)
                                    selected.size < maxExtra -> selected.add(stream.login)
                                }
                            },
                            modifier = if (stream == list.first()) Modifier.focusRequester(focus) else Modifier,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(stream.avatarUrl, stream.displayName, size = 28.dp)
                                Text(stream.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                // Sélection lisible à distance, pas seulement par une nuance de fond.
                                if (checked) Text("✓", color = LumenColors.Accent, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onLaunch(selected.toList()) }, enabled = selected.isNotEmpty()) {
                    Text(stringResource(R.string.multi_launch, selected.size + 1))
                }
                Button(onClick = onDismiss) { Text(stringResource(R.string.multi_cancel)) }
            }
        }
    }
}
