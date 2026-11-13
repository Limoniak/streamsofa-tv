package dev.lumen.feature.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.LiveStream
import dev.lumen.core.Variant
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.toLoadError
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TileStatus {
    data object Loading : TileStatus
    data object Playing : TileStatus
    data class Failed(val error: LoadError) : TileStatus

    /** Le décodeur a refusé ce flux, même à la qualité la plus faible. */
    data object DecoderBusy : TileStatus
}

data class TileUiState(
    val login: String,
    val stream: LiveStream? = null,
    val quality: String? = null,
    val status: TileStatus = TileStatus.Loading,
)

data class MultiUiState(val tiles: List<TileUiState>, val focused: Int = 0)

/** Codes Media3 4xxx : échec d'initialisation ou de décodage, souvent faute de décodeur libre. */
private fun PlaybackException.isDecoderError() = errorCode in 4000..4999

/**
 * 2 à 4 lives côte à côte : un ExoPlayer par tuile, seule la tuile focalisée a le son.
 * Chaque tuile lit une qualité fixe adaptée à sa taille ; si le décodeur refuse, elle descend
 * d'un cran, puis abandonne sans toucher aux autres.
 */
@HiltViewModel
class MultiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val max = DecoderProbe.maxStreams(context).coerceAtLeast(1)
    private val initial = checkNotNull(savedState.get<String>("logins"))
        .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(max)

    private class Slot(val login: String, val player: ExoPlayer) {
        var variants: List<Variant> = emptyList()
        var current: Variant? = null
        var retried = false
        var job: Job? = null
    }

    private val slots = ArrayList<Slot>()
    private val _state = MutableStateFlow(MultiUiState(initial.map { TileUiState(it) }))
    val state: StateFlow<MultiUiState> = _state.asStateFlow()

    init {
        initial.forEach { login -> slots += newSlot(login) }
        slots.forEach(::load)
        applyVolumes()
    }

    fun player(login: String): ExoPlayer? = slots.firstOrNull { it.login == login }?.player

    fun focus(index: Int) {
        if (index == _state.value.focused || index !in slots.indices) return
        _state.update { it.copy(focused = index) }
        applyVolumes()
    }

    /** `false` s'il ne reste qu'une tuile : l'écran bascule alors en lecteur simple. */
    fun remove(login: String): Boolean {
        if (slots.size <= 1) return false
        val index = slots.indexOfFirst { it.login == login }.takeIf { it >= 0 } ?: return true
        slots.removeAt(index).run {
            job?.cancel()
            player.release()
        }
        _state.update { s ->
            val tiles = s.tiles.filterNot { it.login == login }
            s.copy(tiles = tiles, focused = s.focused.coerceAtMost(tiles.lastIndex))
        }
        // Moins de tuiles : les autres peuvent viser une meilleure qualité (sans recharger le jeton).
        val height = tileHeight(slots.size)
        slots.filter { it.current != null }.forEach { slot ->
            val better = pickVariant(slot.variants, height)
            if (better != null && better != slot.current) play(slot, better)
        }
        applyVolumes()
        return true
    }

    fun retry(login: String) {
        slots.firstOrNull { it.login == login }?.let {
            it.retried = false
            load(it)
        }
    }

    fun onStop() = slots.forEach { it.player.stop() }

    /** Retour au premier plan : chaque tuile reprend au direct. */
    fun onStart() = slots.forEach { slot ->
        if (slot.current == null) return@forEach
        slot.player.prepare()
        slot.player.seekToDefaultPosition()
        slot.player.play()
    }

    private fun newSlot(login: String): Slot {
        val player = ExoPlayer.Builder(context).build().apply { playWhenReady = true }
        val slot = Slot(login, player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    slot.retried = false
                    setStatus(login, TileStatus.Playing)
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(slot, error)
        })
        return slot
    }

    private fun load(slot: Slot) {
        slot.job?.cancel()
        slot.player.stop()
        setStatus(slot.login, TileStatus.Loading)
        slot.job = viewModelScope.launch {
            launch {
                loadCatching { catalog.liveStream(slot.login) }.getOrNull()?.let { info ->
                    updateTile(slot.login) { it.copy(stream = info) }
                }
            }
            loadCatching { catalog.livePlayback(slot.login) }
                .onSuccess { playback ->
                    slot.variants = playback.variants
                    play(slot, pickVariant(playback.variants, tileHeight(slots.size)))
                }
                .onFailure { e -> setStatus(slot.login, TileStatus.Failed(e.toLoadError())) }
        }
    }

    private fun play(slot: Slot, variant: Variant?) {
        if (variant == null) {
            setStatus(slot.login, TileStatus.Failed(LoadError.Other))
            return
        }
        slot.current = variant
        updateTile(slot.login) { it.copy(quality = variant.name) }
        slot.player.setMediaItem(liveMediaItem(variant.url))
        slot.player.prepare()
        slot.player.play()
    }

    private fun handleError(slot: Slot, error: PlaybackException) {
        val current = slot.current
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                slot.player.seekToDefaultPosition()
                slot.player.prepare()
            }
            error.isDecoderError() -> {
                val lower = current?.let { lowerVariant(slot.variants, it) }
                if (lower != null) {
                    play(slot, lower)
                } else {
                    slot.player.stop()
                    setStatus(slot.login, TileStatus.DecoderBusy)
                }
            }
            // Jeton expiré ou live terminé : un nouveau jeton tranche, une seule fois.
            !slot.retried -> {
                slot.retried = true
                load(slot)
            }
            else -> setStatus(slot.login, TileStatus.Failed(if (error.errorCode in 2000..2999) LoadError.Network else LoadError.Other))
        }
    }

    private fun applyVolumes() {
        val focused = _state.value.focused
        slots.forEachIndexed { index, slot -> slot.player.volume = if (index == focused) 1f else 0f }
    }

    private fun setStatus(login: String, status: TileStatus) = updateTile(login) { it.copy(status = status) }

    private fun updateTile(login: String, change: (TileUiState) -> TileUiState) =
        _state.update { s -> s.copy(tiles = s.tiles.map { if (it.login == login) change(it) else it }) }

    override fun onCleared() = slots.forEach { it.player.release() }
}
