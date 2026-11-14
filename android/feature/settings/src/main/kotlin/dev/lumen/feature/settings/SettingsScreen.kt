package dev.lumen.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.data.AppSettings
import dev.lumen.data.CrashLog
import dev.lumen.data.SettingsRepository
import dev.lumen.data.messageRes
import dev.lumen.designsystem.AccentColor
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.requestWhenAttached
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val crashLog: CrashLog,
) : ViewModel() {
    val state = repo.state

    /** Résultat de la dernière action sur le journal de crash, affiché sous les boutons. */
    private val _crashStatus = MutableStateFlow<CrashStatus?>(null)
    val crashStatus = _crashStatus.asStateFlow()

    fun update(change: AppSettings.() -> AppSettings) = repo.update(change)

    fun exportCrashLog() = viewModelScope.launch(Dispatchers.IO) {
        _crashStatus.value = when {
            !crashLog.hasEntries() -> CrashStatus.Empty
            else -> runCatching { CrashStatus.Exported(crashLog.exportToDownloads()) }.getOrElse { CrashStatus.Failed }
        }
    }

    fun clearCrashLog() = viewModelScope.launch(Dispatchers.IO) {
        crashLog.clear()
        _crashStatus.value = CrashStatus.Cleared
    }
}

sealed interface CrashStatus {
    data object Empty : CrashStatus
    data object Cleared : CrashStatus
    data object Failed : CrashStatus
    data class Exported(val name: String) : CrashStatus
}

/** 0 = Auto. */
private val QUALITIES = listOf(0, 1080, 720, 480)

private val AccentColor.label
    get() = when (this) {
        AccentColor.Violet -> R.string.accent_violet
        AccentColor.Blue -> R.string.accent_blue
        AccentColor.Green -> R.string.accent_green
        AccentColor.Peach -> R.string.accent_peach
        AccentColor.Pink -> R.string.accent_pink
    }

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel(), updates: UpdateViewModel = hiltViewModel()) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val crashStatus by viewModel.crashStatus.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestWhenAttached() }
    /** Liste en cours d'édition : `true` = mots, `false` = pseudos. */
    var editingWords by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
            .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Section(R.string.settings_playback)
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(chatByDefault = !chatByDefault) } },
            headlineContent = { Text(stringResource(R.string.settings_chat_default)) },
            supportingContent = { Text(stringResource(R.string.settings_chat_default_hint)) },
            trailingContent = { AccentSwitch(settings.chatByDefault) },
            modifier = Modifier.focusRequester(firstFocus),
        )
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(lowLatency = !lowLatency) } },
            headlineContent = { Text(stringResource(R.string.settings_low_latency)) },
            supportingContent = { Text(stringResource(R.string.settings_low_latency_hint)) },
            trailingContent = { AccentSwitch(settings.lowLatency) },
        )
        Label(R.string.settings_quality)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QUALITIES.forEach { height ->
                FilterChip(
                    selected = settings.maxQuality == height,
                    onClick = { viewModel.update { copy(maxQuality = height) } },
                ) {
                    Text(if (height == 0) stringResource(R.string.settings_quality_auto) else "${height}p")
                }
            }
        }

        Section(R.string.settings_chat)
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(thirdPartyEmotes = !thirdPartyEmotes) } },
            headlineContent = { Text(stringResource(R.string.settings_third_party_emotes)) },
            supportingContent = { Text(stringResource(R.string.settings_third_party_emotes_hint)) },
            trailingContent = { AccentSwitch(settings.thirdPartyEmotes) },
        )
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(chatTimestamps = !chatTimestamps) } },
            headlineContent = { Text(stringResource(R.string.settings_chat_timestamps)) },
            supportingContent = { Text(stringResource(R.string.settings_chat_timestamps_hint)) },
            trailingContent = { AccentSwitch(settings.chatTimestamps) },
        )
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(chatOverlay = !chatOverlay) } },
            headlineContent = { Text(stringResource(R.string.settings_chat_overlay)) },
            supportingContent = { Text(stringResource(R.string.settings_chat_overlay_hint)) },
            trailingContent = { AccentSwitch(settings.chatOverlay) },
        )
        Label(R.string.settings_chat_text_size)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.settings_size_small, R.string.settings_size_medium, R.string.settings_size_large).forEachIndexed { index, label ->
                FilterChip(selected = settings.chatTextSize == index, onClick = { viewModel.update { copy(chatTextSize = index) } }) {
                    Text(stringResource(label))
                }
            }
        }
        Label(R.string.settings_chat_width)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.settings_width_narrow, R.string.settings_width_normal, R.string.settings_width_wide).forEachIndexed { index, label ->
                FilterChip(selected = settings.chatWidth == index, onClick = { viewModel.update { copy(chatWidth = index) } }) {
                    Text(stringResource(label))
                }
            }
        }
        ListItem(
            selected = false,
            onClick = { editingWords = true },
            headlineContent = { Text(stringResource(R.string.settings_hidden_words)) },
            supportingContent = { Text(settings.hiddenWords.ifBlank { stringResource(R.string.settings_none) }, maxLines = 1) },
        )
        ListItem(
            selected = false,
            onClick = { editingWords = false },
            headlineContent = { Text(stringResource(R.string.settings_hidden_users)) },
            supportingContent = { Text(settings.hiddenUsers.ifBlank { stringResource(R.string.settings_none) }, maxLines = 1) },
        )
        Text(stringResource(R.string.settings_chat_hint), color = LumenColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)

        Section(R.string.settings_home)
        ListItem(
            selected = false,
            // OK les fait toutes réapparaître : la liste est courte et se refait d'un appui long.
            onClick = { viewModel.update { copy(hiddenCategories = emptyMap()) } },
            headlineContent = { Text(stringResource(R.string.settings_hidden_categories)) },
            supportingContent = {
                Text(
                    settings.hiddenCategories.values.sorted().joinToString(", ").ifEmpty { stringResource(R.string.settings_none) },
                    maxLines = 1,
                )
            },
        )
        Text(stringResource(R.string.settings_hidden_categories_hint), color = LumenColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)

        Section(R.string.settings_theme)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentColor.entries.forEach { accent ->
                FilterChip(
                    selected = settings.accent == accent.key,
                    onClick = { viewModel.update { copy(accent = accent.key) } },
                    leadingIcon = { Box(Modifier.size(16.dp).background(accent.color, CircleShape)) },
                ) {
                    Text(stringResource(accent.label))
                }
            }
        }

        Section(R.string.settings_updates)
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(checkUpdates = !checkUpdates) } },
            headlineContent = { Text(stringResource(R.string.settings_check_updates)) },
            supportingContent = { Text(stringResource(R.string.settings_version, updates.currentVersion)) },
            trailingContent = { AccentSwitch(settings.checkUpdates) },
        )
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(betaUpdates = !betaUpdates) } },
            headlineContent = { Text(stringResource(R.string.settings_beta_updates)) },
            supportingContent = { Text(stringResource(R.string.settings_beta_updates_hint)) },
            trailingContent = { AccentSwitch(settings.betaUpdates) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val s = update) {
                is UpdateState.Available -> Button(onClick = updates::install) {
                    Text(stringResource(R.string.update_install) + " " + s.update.version)
                }
                else -> Button(onClick = updates::check, enabled = s !is UpdateState.Checking && s !is UpdateState.Downloading) {
                    Text(stringResource(R.string.update_check_now))
                }
            }
            val status = when (val s = update) {
                UpdateState.Checking -> stringResource(R.string.update_checking)
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateState.Available -> stringResource(R.string.update_available, s.update.version)
                is UpdateState.Downloading -> stringResource(R.string.update_downloading)
                is UpdateState.Failed -> stringResource(s.error.messageRes)
                UpdateState.Idle -> null
            }
            if (status != null) Text(status, color = LumenColors.OnSurfaceMuted)
        }

        Section(R.string.settings_crash_log)
        ListItem(
            selected = false,
            onClick = { viewModel.update { copy(crashLog = !crashLog) } },
            headlineContent = { Text(stringResource(R.string.settings_crash_log_enable)) },
            supportingContent = { Text(stringResource(R.string.settings_crash_log_hint)) },
            trailingContent = { AccentSwitch(settings.crashLog) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (CrashLog.canExport) {
                Button(onClick = { viewModel.exportCrashLog() }) { Text(stringResource(R.string.settings_crash_log_export)) }
            }
            Button(onClick = { viewModel.clearCrashLog() }) { Text(stringResource(R.string.settings_crash_log_clear)) }
            val status = when (val s = crashStatus) {
                CrashStatus.Empty -> stringResource(R.string.settings_crash_log_empty)
                CrashStatus.Cleared -> stringResource(R.string.settings_crash_log_cleared)
                CrashStatus.Failed -> stringResource(R.string.settings_crash_log_failed)
                is CrashStatus.Exported -> stringResource(R.string.settings_crash_log_exported, s.name)
                null -> null
            }
            if (status != null) Text(status, color = LumenColors.OnSurfaceMuted)
        }
    }

    editingWords?.let { words ->
        ListEditDialog(
            title = if (words) R.string.settings_hidden_words else R.string.settings_hidden_users,
            initial = if (words) settings.hiddenWords else settings.hiddenUsers,
            onSave = { value ->
                viewModel.update { if (words) copy(hiddenWords = value) else copy(hiddenUsers = value) }
                editingWords = null
            },
            onDismiss = { editingWords = null },
        )
    }
}

/** Couleurs explicites : l'interrupteur TV ne suit pas l'accent du thème. */
@Composable
private fun AccentSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = LumenColors.Accent,
            checkedBorderColor = LumenColors.Accent,
            checkedThumbColor = LumenColors.OnAccent,
        ),
    )
}

@Composable
private fun Section(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = LumenColors.Accent,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}
