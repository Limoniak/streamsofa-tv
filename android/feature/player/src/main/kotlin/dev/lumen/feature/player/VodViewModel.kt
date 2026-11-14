package dev.lumen.feature.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.Chapter
import dev.lumen.core.ChatEvent
import dev.lumen.core.ChatState
import dev.lumen.core.Video
import dev.lumen.data.CatalogRepository
import dev.lumen.data.SettingsRepository
import dev.lumen.data.WatchNext
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.toLoadError
import dev.lumen.data.unavailableDetail
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VodUiState(
    val id: String,
    val video: Video? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    /** Raison donnée par Twitch quand elle refuse la lecture (VOD réservée aux abonnés…). */
    val detail: String? = null,
    /** Changements de jeu, dans l'ordre ; vide si Twitch n'en a pas. */
    val chapters: List<Chapter> = emptyList(),
    val speed: Float = 1f,
)

/** Vitesses proposées par ▲, dans l'ordre du cycle. */
internal val VOD_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

internal fun nextSpeed(current: Float) = VOD_SPEEDS[(VOD_SPEEDS.indexOf(current) + 1) % VOD_SPEEDS.size]

/** Chapitre en cours à [positionMs] (le dernier commencé). */
internal fun List<Chapter>.at(positionMs: Long): Chapter? = lastOrNull { it.positionMs.toLong() <= positionMs }

/** Position de lecture pour la barre de progression. */
data class VodClock(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Pause voulue par l'utilisateur ; une mise en mémoire tampon n'en est pas une. */
    val paused: Boolean = false,
)

private const val SAVE_EVERY_MS = 15_000L
private const val CLOCK_TICK_MS = 250L
/** Reprise un peu avant l'endroit quitté, pour retrouver le fil. */
private const val RESUME_REWIND_S = 5u
/** Après un saut, les messages des dernières secondes s'affichent tout de suite, pour le contexte. */
private const val CHAT_CONTEXT_S = 30u

/** Ce que la télécommande du lecteur VOD déclenche : le ViewModel, ou un faux dans les tests. */
interface VodActions {
    /** Profil enfants : pas de replay du chat. */
    val chatAllowed: Boolean
    val chatByDefault: Boolean
    val chatLook: ChatLook

    fun retry()
    fun togglePause()
    /** Vitesse suivante de [VOD_SPEEDS]. */
    fun cycleSpeed()
    fun seekBy(deltaMs: Long)
    fun setChatVisible(visible: Boolean)
}

@HiltViewModel
class VodViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    settings: SettingsRepository,
    private val watchNext: WatchNext,
) : ViewModel(), VodActions {
    override val chatAllowed = !settings.state.value.kids
    override val chatByDefault = settings.state.value.chatByDefault && chatAllowed
    override val chatLook = settings.state.value.chatLook()
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(VodUiState(id))
    val state: StateFlow<VodUiState> = _state.asStateFlow()
    private val _clock = MutableStateFlow(VodClock())
    val clock: StateFlow<VodClock> = _clock.asStateFlow()
    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }

    private var loadJob: Job? = null
    private var chatJob: Job? = null
    private val replay = ReplayBuffer()
    private val noChatText = context.getString(R.string.vod_no_chat)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) _state.update { it.copy(status = PlayerStatus.Playing) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val io = error.errorCode in 2000..2999
                _state.update { it.copy(status = PlayerStatus.Failed(if (io) LoadError.Network else LoadError.Other)) }
            }
        })
        viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                _clock.value = VodClock(player.currentPosition, duration, paused = !player.playWhenReady)
                if (player.isPlaying) sinceSave += CLOCK_TICK_MS
                if (sinceSave >= SAVE_EVERY_MS) {
                    sinceSave = 0
                    saveProgress()
                }
                delay(CLOCK_TICK_MS)
            }
        }
        load()
    }

    override fun retry() = load()

    override fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    override fun cycleSpeed() {
        val speed = nextSpeed(_state.value.speed)
        player.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    override fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
        if (chatJob?.isActive == true) startChat()
    }

    /** Le chat n'est chargé que lorsqu'il est affiché : pas de requêtes pour rien. */
    override fun setChatVisible(visible: Boolean) {
        if (visible) startChat() else chatJob?.cancel()
    }

    fun onStop() {
        saveProgress(publish = true)
        player.pause()
    }

    private fun load() {
        loadJob?.cancel()
        player.stop()
        _state.update { it.copy(status = PlayerStatus.Loading, detail = null) }
        loadJob = viewModelScope.launch {
            val video = async { loadCatching { catalog.video(id) }.getOrNull() }
            // Facultatifs : sans chapitres, la VOD se lit normalement.
            launch { loadCatching { catalog.vodChapters(id) }.onSuccess { c -> _state.update { it.copy(chapters = c) } } }
            loadCatching { catalog.vodPlayback(id) }
                .onSuccess { playback ->
                    val info = video.await()
                    _state.update { it.copy(video = info ?: it.video) }
                    val resume = info?.takeIf { !it.finished && it.positionS > RESUME_REWIND_S * 2u }
                        ?.let { (it.positionS - RESUME_REWIND_S).toLong() * 1000 } ?: 0L
                    // L'archive d'un live en cours est une playlist « EVENT » qui grandit : ExoPlayer la traite comme
                    // un direct. Pas de rattrapage (vitesse figée à 1×), et départ explicite (0 = début du live).
                    val item = MediaItem.Builder()
                        .setUri(playback.masterUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
                        .build()
                    // Une relance après erreur repart de là où l'on en était.
                    val start = player.currentPosition.takeIf { it > 0 } ?: resume
                    player.setMediaItem(item, start)
                    player.prepare()
                    player.play()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(video = video.await() ?: it.video, status = PlayerStatus.Failed(e.toLoadError()), detail = e.unavailableDetail(context))
                    }
                }
        }
    }

    private fun startChat() {
        chatJob?.cancel()
        _chat.value = ChatUiState()
        replay.reset(positionS().let { if (it > CHAT_CONTEXT_S) it - CHAT_CONTEXT_S else 0u })
        chatJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                val position = positionS()
                if (replay.needsMore(position)) {
                    val offset = replay.nextOffset
                    loadCatching { catalog.vodChat(id, offset) }
                        .onSuccess { replay.add(it) }
                        // Chat illisible : on le traite comme absent, la vidéo continue.
                        .onFailure { replay.add(emptyList()) }
                    if (first && replay.ended) _chat.update { it.copy(lines = listOf(systemLine(noChatText))) }
                }
                val events = replay.due(position).map { ChatEvent.Message(it) }
                if (first || events.isNotEmpty()) _chat.update { it.applyEvents(events + ChatEvent.State(ChatState.JOINED)) }
                first = false
                delay(CLOCK_TICK_MS)
            }
        }
    }

    private fun positionS(): UInt = (player.currentPosition / 1000).coerceAtLeast(0).toUInt()

    /** [publish] : aussi dans « Continuer à regarder » du launcher, seulement en quittant. */
    private fun saveProgress(scope: CoroutineScope = viewModelScope, publish: Boolean = false) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val (position, total) = positionS() to (duration / 1000).toUInt()
        val video = _state.value.video
        scope.launch {
            loadCatching { catalog.saveVodProgress(id, position, total) }.onSuccess { finished ->
                if (publish && video != null) watchNext.publish(video, position, total, finished)
            }
        }
    }

    override fun onCleared() {
        // `viewModelScope` est déjà annulé : la dernière position part sur une portée à part.
        saveProgress(CoroutineScope(Dispatchers.IO), publish = true)
        player.release()
    }
}
