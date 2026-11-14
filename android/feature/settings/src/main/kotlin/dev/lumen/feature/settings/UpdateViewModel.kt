package dev.lumen.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.AppUpdate
import dev.lumen.data.LoadError
import dev.lumen.data.SettingsRepository
import dev.lumen.data.plainNotes
import dev.lumen.data.UpdateRepository
import dev.lumen.data.loadCatching
import dev.lumen.data.toLoadError
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate) : UpdateState
    data class Failed(val error: LoadError) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val currentVersion get() = updates.currentVersion

    /**
     * Proposition automatique, une fois par lancement. Jamais sur une build de debug : sa signature
     * diffère de celle des releases, Android refuserait la mise à jour.
     */
    private val _whatsNew = MutableStateFlow<String?>(null)
    /** Notes de la version installée, une fois, au premier lancement après une mise à jour. */
    val whatsNew: StateFlow<String?> = _whatsNew.asStateFlow()

    fun dismissWhatsNew() {
        _whatsNew.value = null
    }

    /** Version installée différente de la dernière vue : on retient la nouvelle et on montre ses notes (pas à la première installation). */
    private suspend fun showWhatsNew() {
        val current = updates.currentVersion
        val seen = settings.awaitLoaded().lastSeenVersion
        if (seen == current) return
        settings.update { copy(lastSeenVersion = current) }
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        // Rien de retenu encore : une première installation ne montre rien, une mise à jour depuis une version d'avant cette option, si.
        val updated = seen.isNotEmpty() || (info != null && info.lastUpdateTime > info.firstInstallTime)
        if (!updated) return
        _whatsNew.value = loadCatching { updates.releaseNotes() }.getOrNull()?.let(::plainNotes)
    }

    fun checkOnLaunch() {
        viewModelScope.launch { showWhatsNew() }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable || updates.promptedThisLaunch) return
        updates.promptedThisLaunch = true
        viewModelScope.launch {
            if (!settings.awaitLoaded().checkUpdates) return@launch
            loadCatching { updates.check() }.getOrNull()?.let { _state.value = UpdateState.Available(it) }
        }
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = loadCatching { updates.check() }.fold(
                onSuccess = { it?.let(UpdateState::Available) ?: UpdateState.UpToDate },
                onFailure = { UpdateState.Failed(it.toLoadError()) },
            )
        }
    }

    fun install() {
        val update = (_state.value as? UpdateState.Available)?.update ?: return
        _state.value = UpdateState.Downloading(update)
        viewModelScope.launch {
            loadCatching { updates.download(update) }
                .onSuccess { file ->
                    _state.value = UpdateState.Available(update)
                    launchInstaller(file)
                }
                .onFailure { _state.value = UpdateState.Failed(it.toLoadError()) }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    // L'installeur système vérifie la signature et, au premier usage, renvoie vers l'autorisation
    // « sources inconnues » de l'app.
    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { _state.value = UpdateState.Failed(LoadError.Other) }
    }
}

