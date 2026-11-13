package dev.lumen.feature.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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

sealed interface PlayerStatus {
    data object Loading : PlayerStatus
    data object Playing : PlayerStatus
    data class Failed(val error: LoadError) : PlayerStatus
}

data class PlayerUiState(
    val login: String,
    val stream: LiveStream? = null,
    /** Qualités vidéo proposées, de la meilleure à la plus faible. */
    val variants: List<Variant> = emptyList(),
    /** `null` = Auto (adaptatif sur la playlist maître). */
    val quality: String? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    val canZap: Boolean = false,
)

/** Chaîne voisine dans la liste, en boucle ; `null` si [current] n'y figure pas ou est seule. */
fun zapTarget(logins: List<String>, current: String, delta: Int): String? {
    val index = logins.indexOf(current)
    if (index < 0 || logins.size < 2) return null
    return logins[Math.floorMod(index + delta, logins.size)]
}

private const val LIVE_OFFSET_MS = 5_000L

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState(login = checkNotNull(savedState["login"])))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }

    private var masterUrl: String? = null
    private var job: Job? = null
    /** Une relance automatique par incident (jeton expiré, coupure courte), pas de boucle. */
    private var retried = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retried = false
                    _state.update { it.copy(status = PlayerStatus.Playing) }
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        viewModelScope.launch {
            catalog.followed.collect { list ->
                _state.update { s -> s.copy(canZap = zapTarget(list.map { it.login }, s.login, 1) != null) }
            }
        }
        load(_state.value.login)
    }

    fun zap(delta: Int) {
        val next = zapTarget(catalog.followed.value.map { it.login }, _state.value.login, delta) ?: return
        retried = false
        load(next)
    }

    fun retry() {
        retried = false
        load(_state.value.login)
    }

    fun selectQuality(name: String?) {
        _state.update { it.copy(quality = name) }
        play()
    }

    fun onStop() = player.stop()

    /** Retour au premier plan : on reprend au direct, pas là où l'image s'était figée. */
    fun onStart() {
        if (masterUrl == null || _state.value.status is PlayerStatus.Failed) return
        player.prepare()
        player.seekToDefaultPosition()
        player.play()
    }

    private fun load(login: String) {
        job?.cancel()
        player.stop()
        masterUrl = null
        val known = catalog.followed.value.firstOrNull { it.login == login }
        _state.update {
            it.copy(
                login = login,
                stream = known,
                variants = emptyList(),
                status = PlayerStatus.Loading,
                canZap = zapTarget(catalog.followed.value.map { s -> s.login }, login, 1) != null,
            )
        }
        job = viewModelScope.launch {
            launch {
                loadCatching { catalog.liveStream(login) }.getOrNull()?.let { info ->
                    _state.update { if (it.login == login) it.copy(stream = info) else it }
                }
            }
            loadCatching { catalog.livePlayback(login) }
                .onSuccess { playback ->
                    masterUrl = playback.masterUrl
                    val variants = playback.variants.filter { !it.audioOnly }
                    _state.update { s ->
                        // La qualité choisie suit le zapping si la chaîne suivante la propose.
                        s.copy(variants = variants, quality = s.quality?.takeIf { q -> variants.any { it.name == q } })
                    }
                    play()
                }
                .onFailure { e -> _state.update { it.copy(status = PlayerStatus.Failed(e.toLoadError())) } }
        }
    }

    private fun play() {
        val state = _state.value
        val url = state.variants.firstOrNull { it.name == state.quality }?.url ?: masterUrl ?: return
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_OFFSET_MS)
                    // Rattrapage du direct imperceptible plutôt qu'un saut.
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun handleError(error: PlaybackException) {
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }
            // 404/403 en cours de lecture : live terminé ou jeton expiré. Un nouveau jeton tranche.
            !retried -> {
                retried = true
                load(_state.value.login)
            }
            else -> {
                val io = error.errorCode in 2000..2999
                _state.update { it.copy(status = PlayerStatus.Failed(if (io) LoadError.Network else LoadError.Other)) }
            }
        }
    }

    override fun onCleared() {
        player.release()
    }
}
