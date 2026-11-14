package dev.lumen.feature.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.ChannelActivity
import dev.lumen.core.PickerEmote
import dev.lumen.core.AccountState
import dev.lumen.core.CoreException
import dev.lumen.core.LiveStream
import dev.lumen.core.Raid
import dev.lumen.core.Variant
import dev.lumen.core.Video
import dev.lumen.data.AccountsRepository
import dev.lumen.data.CatalogRepository
import dev.lumen.data.ChatRepository
import dev.lumen.data.LoadError
import dev.lumen.data.QUALITY_AUTO
import dev.lumen.data.SettingsRepository
import dev.lumen.data.loadCatching
import dev.lumen.data.messageRes
import dev.lumen.data.toLoadError
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Raid en cours : la chaîne cible s'ouvre à la fin du compte à rebours, sauf annulation. */
    val raid: Raid? = null,
    /** La chaîne propose un flux audio seul. */
    val canAudioOnly: Boolean = false,
    val audioOnly: Boolean = false,
    /** Minuterie de mise en veille choisie, en minutes ; 0 = aucune. */
    val sleepMinutes: Int = 0,
    /** Lecture arrêtée par la minuterie. */
    val sleeping: Boolean = false,
    /** Chaîne hors ligne (live terminé) : suites proposées à la place d'une simple erreur. */
    val ended: Ended? = null,
)

/** Suites proposées quand le live s'arrête ; chacune est absente si elle n'existe pas. */
data class Ended(
    /** Raid lancé avant la fin, que l'utilisateur avait choisi de ne pas suivre. */
    val raid: Raid? = null,
    /** Prochain live de la liste de zapping. */
    val next: LiveStream? = null,
    /** Dernière rediffusion de la chaîne. */
    val lastVideo: Video? = null,
)

/** Durées proposées par la minuterie, dans l'ordre où OK les fait défiler. */
val SLEEP_OPTIONS = listOf(15, 30, 60, 90)

/** Chaîne voisine dans la liste, en boucle ; hors liste, la première (la dernière en reculant). `null` s'il n'y a rien d'autre. */
fun zapTarget(logins: List<String>, current: String, delta: Int): String? {
    if (logins.none { it != current }) return null
    val index = logins.indexOf(current)
    if (index < 0) return if (delta > 0) logins.first() else logins.last()
    return logins[Math.floorMod(index + delta, logins.size)]
}

private const val LIVE_OFFSET_MS = 5_000L
const val RAID_DELAY_S = 10

/** Élément de lecture d'un live, partagé par le lecteur et le multi-view. */
/**
 * Tampon d'un live : la première image dès 1,5 s de vidéo reçue (2,5 s par défaut), 3 s après une coupure (5 s).
 * Un live n'a de toute façon que quelques secondes d'avance à charger ; le reste des réglages est inchangé.
 */
@OptIn(UnstableApi::class)
internal fun liveLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        LIVE_START_BUFFER_MS,
        LIVE_REBUFFER_MS,
    )
    .build()

/** Lecteur d'un live (lecteur simple et tuiles du multi-view), avec le tampon de [liveLoadControl]. */
@OptIn(UnstableApi::class)
internal fun newLivePlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context).setLoadControl(liveLoadControl()).build().apply { playWhenReady = true }

private const val LIVE_START_BUFFER_MS = 1_500
private const val LIVE_REBUFFER_MS = 3_000

internal fun liveMediaItem(url: String, targetOffsetMs: Long = LIVE_OFFSET_MS): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMimeType(MimeTypes.APPLICATION_M3U8)
    .setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(targetOffsetMs)
            // Rattrapage du direct imperceptible plutôt qu'un saut.
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.03f)
            .build(),
    )
    .build()

/**
 * Ce que la télécommande du lecteur déclenche et lit : le ViewModel en production, un faux dans
 * les tests de l'écran (aucun ExoPlayer ni réseau n'y est nécessaire).
 */
interface PlayerActions {
    /** Profil enfants : ni chat, ni connexion IRC. */
    val chatAllowed: Boolean
    val chatByDefault: Boolean
    val chatTimestamps: Boolean
    val chatLook: ChatLook
    /** 1 : pas de multi-view sur cet appareil. */
    val maxTiles: Int

    fun zap(delta: Int)
    /** Chaîne choisie dans la liste des lives. */
    fun open(login: String)
    fun retry()
    fun followRaid()
    fun cancelRaid()
    fun selectQuality(name: String?)
    fun toggleAudioOnly()
    fun cycleSleepTimer()
    fun sendChat(text: String, replyTo: String? = null)
    /** Lives proposés (liste ◀, multi-view) : les suivis en live, sinon les plus regardés. */
    suspend fun liveList(): List<LiveStream>
    /** Chaînes à ajouter au multi-view. */
    suspend fun multiCandidates(): List<LiveStream>
    /** Sondage et prédiction de la chaîne affichée. */
    suspend fun channelActivity(): ChannelActivity
    suspend fun chatEmotes(): List<PickerEmote>
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val chatRepo: ChatRepository,
    accounts: AccountsRepository,
    private val settings: SettingsRepository,
) : ViewModel(), PlayerActions {
    override val chatAllowed: Boolean get() = !settings.state.value.kids
    override val chatByDefault: Boolean get() = settings.state.value.chatByDefault && chatAllowed
    override val chatTimestamps: Boolean get() = settings.state.value.chatTimestamps
    override val chatLook: ChatLook get() = settings.state.value.chatLook()
    /** Une fois une qualité choisie à la main (Auto compris), le réglage par défaut ne s'applique plus. */
    private var qualityChosen = false

    private val _state = MutableStateFlow(PlayerUiState(login = checkNotNull(savedState["login"])))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()
    private var chatJob: Job? = null
    private var chatLogin: String? = null

    /** L'envoi exige un compte actif et valide ; en invité le chat reste en lecture. */
    /** Login du compte actif : ses citations sont mises en valeur dans le chat. */
    val myLogin: StateFlow<String?> = accounts.snapshot
        .map { snap -> snap?.accounts?.firstOrNull { it.id == snap.activeId }?.login }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val canSend: StateFlow<Boolean> = accounts.snapshot
        .map { snap -> snap?.accounts?.any { it.id == snap.activeId && it.state == AccountState.OK } == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val player: ExoPlayer = newLivePlayer(context)

    /** Lives populaires, pour zapper sans suivis en live (invité). */
    private var popular: List<String> = emptyList()

    private var raidJob: Job? = null
    /** Dernier raid de la chaîne affichée, gardé même annulé : il est reproposé si le live s'arrête. */
    private var lastRaid: Raid? = null
    private var followJob: Job? = null

    private val prefetch = PlaybackCache()
    private var prefetchJob: Job? = null

    private var masterUrl: String? = null
    private var audioUrl: String? = null
    private var sleepJob: Job? = null
    private var job: Job? = null
    /** Une relance automatique par incident (jeton expiré, coupure courte), pas de boucle. */
    private var retried = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retried = false
                    _state.update { it.copy(status = PlayerStatus.Playing) }
                    // Historique du profil (« Regardé récemment » sur l'accueil) ; sans écriture si déjà en tête.
                    val login = _state.value.login
                    settings.update { withRecentChannel(login) }
                    // L'image d'abord : les voisines ne lui disputent pas la bande passante du démarrage.
                    prefetchNeighbours()
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        viewModelScope.launch {
            catalog.followed.collect { refreshCanZap() }
        }
        if (catalog.followed.value.isEmpty()) viewModelScope.launch {
            popular = loadCatching { catalog.topStreams(null).items }.getOrDefault(emptyList()).map { it.login }
            refreshCanZap()
        }
        // EventSub ne connaît la chaîne que par son id : on suit celui du live affiché.
        viewModelScope.launch {
            _state.mapNotNull { it.stream?.channelId }.distinctUntilChanged().collect { id ->
                raidJob?.cancel()
                raidJob = launch { catalog.raids(id).catch { }.collect(::onRaid) }
            }
        }
        load(_state.value.login)
    }

    private fun onRaid(raid: Raid) {
        lastRaid = raid
        _state.update { it.copy(raid = raid) }
        followJob?.cancel()
        followJob = viewModelScope.launch {
            delay(RAID_DELAY_S * 1000L)
            followRaid()
        }
    }

    override fun followRaid() {
        val raid = _state.value.raid ?: return
        followJob?.cancel()
        retried = false
        load(raid.toLogin)
    }

    override fun cancelRaid() {
        followJob?.cancel()
        _state.update { it.copy(raid = null) }
    }

    private fun zapLogins(): List<String> = catalog.followed.value.map { it.login }.ifEmpty { popular }

    private fun refreshCanZap() = _state.update { it.copy(canZap = zapTarget(zapLogins(), it.login, 1) != null) }

    override fun zap(delta: Int) {
        val next = zapTarget(zapLogins(), _state.value.login, delta) ?: return
        retried = false
        load(next)
    }

    override fun open(login: String) {
        if (login == _state.value.login) return
        retried = false
        load(login)
    }

    override fun retry() {
        retried = false
        load(_state.value.login)
    }

    /** Son seul : moins de données, et la lecture continue quand l'écran se met en veille. */
    override fun toggleAudioOnly() {
        setAudioOnly(!_state.value.audioOnly)
        play()
    }

    private fun setAudioOnly(on: Boolean) {
        if (on == _state.value.audioOnly) return
        _state.update { it.copy(audioOnly = on) }
        player.setWakeMode(if (on) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
        // Démarré au premier plan : Android refuse un service de premier plan lancé depuis l'arrière-plan.
        if (on) AudioOnlyService.start(context, _state.value.stream?.displayName ?: _state.value.login) else AudioOnlyService.stop(context)
    }

    /** Passe à la durée suivante de [SLEEP_OPTIONS], puis revient à « aucune ». */
    override fun cycleSleepTimer() {
        val next = SLEEP_OPTIONS.firstOrNull { it > _state.value.sleepMinutes } ?: 0
        sleepJob?.cancel()
        _state.update { it.copy(sleepMinutes = next) }
        if (next > 0) sleepJob = viewModelScope.launch {
            delay(next * 60_000L)
            setAudioOnly(false)
            player.stop()
            _state.update { it.copy(sleepMinutes = 0, sleeping = true) }
        }
    }

    override val maxTiles: Int = DecoderProbe.maxStreams(context)

    override suspend fun liveList(): List<LiveStream> =
        catalog.followed.value.ifEmpty { loadCatching { catalog.topStreams(null).items }.getOrDefault(emptyList()) }

    override suspend fun channelActivity(): ChannelActivity = catalog.channelActivity(_state.value.login)

    override suspend fun multiCandidates(): List<LiveStream> = liveList().filter { it.login != _state.value.login }

    override fun selectQuality(name: String?) {
        qualityChosen = true
        _state.update { it.copy(quality = name) }
        val login = _state.value.login
        settings.update { copy(channelQuality = channelQuality + (login to (name ?: QUALITY_AUTO))) }
        play()
    }

    /** En son seul, la lecture continue en arrière-plan (écran de veille, écran éteint). */
    fun onStop() {
        if (!_state.value.audioOnly) player.stop()
    }

    /** Retour au premier plan : on reprend au direct, pas là où l'image s'était figée. */
    fun onStart() {
        if (masterUrl == null || _state.value.status is PlayerStatus.Failed || _state.value.sleeping || player.isPlaying) return
        player.prepare()
        player.seekToDefaultPosition()
        player.play()
    }

    override suspend fun chatEmotes(): List<PickerEmote> = _state.value.stream?.channelId?.let { chatRepo.emotes(it) }.orEmpty()

    override fun sendChat(text: String, replyTo: String?) {
        val broadcasterId = _state.value.stream?.channelId ?: return
        viewModelScope.launch {
            val failure = try {
                chatRepo.send(broadcasterId, text, replyTo)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreException.ChatRejected) {
                e.detail
            } catch (e: Throwable) {
                context.getString(e.toLoadError().messageRes)
            }
            // Le message envoyé revient par le salon ; seul un refus s'affiche localement.
            if (failure != null) _chat.update { it.copy(lines = it.lines + systemLine(failure)) }
        }
    }

    /** Une relance du flux (jeton expiré) ne doit pas couper le chat : on ne rejoint qu'au changement de chaîne. */
    private fun joinChat(login: String) {
        if (login == chatLogin || !chatAllowed) return
        chatLogin = login
        chatJob?.cancel()
        _chat.value = ChatUiState()
        chatJob = viewModelScope.launch {
            chatRepo.events(login)
                .catch { e -> _chat.update { it.copy(lines = it.lines + systemLine(context.getString(e.toLoadError().messageRes))) } }
                .collect { batch -> _chat.update { it.applyEvents(batch) } }
        }
    }

    /** Prépare le jeton des chaînes de ▲ et ▼, puis le renouvelle tant qu'on reste sur celle-ci. */
    private fun prefetchNeighbours() {
        if (prefetchJob?.isActive == true) return
        val current = _state.value.login
        prefetchJob = viewModelScope.launch {
            while (true) {
                val neighbours = zapNeighbours(zapLogins(), current)
                prefetch.retain(neighbours.toSet())
                for (login in neighbours) {
                    if (prefetch.isFresh(login)) continue
                    // Une voisine hors ligne ou en erreur est simplement chargée normalement au zap.
                    loadCatching {
                        coroutineScope {
                            val stream = async { loadCatching { catalog.liveStream(login) }.getOrNull() }
                            prefetch.put(login, catalog.livePlayback(login), stream.await())
                        }
                    }
                }
                delay(prefetch.nextRefreshInMs())
            }
        }
    }

    private fun load(login: String) {
        if (login != _state.value.login) lastRaid = null
        joinChat(login)
        job?.cancel()
        prefetchJob?.cancel()
        // Un jeton préparé ne sert qu'à l'ouverture : une relance (jeton refusé) en redemande un neuf.
        val ready = if (retried) null else prefetch.take(login)
        player.stop()
        masterUrl = null
        audioUrl = null
        val known = ready?.stream ?: catalog.followed.value.firstOrNull { it.login == login }
        followJob?.cancel()
        _state.update {
            it.copy(
                raid = null,
                sleeping = false,
                ended = null,
                login = login,
                stream = known,
                variants = emptyList(),
                status = PlayerStatus.Loading,
                canZap = zapTarget(zapLogins(), login, 1) != null,
            )
        }
        job = viewModelScope.launch {
            if (ready?.stream == null) launch {
                loadCatching { catalog.liveStream(login) }.getOrNull()?.let { info ->
                    _state.update { if (it.login == login) it.copy(stream = info) else it }
                }
            }
            (ready?.let { Result.success(it.playback) } ?: loadCatching { catalog.livePlayback(login) })
                .onSuccess { playback ->
                    masterUrl = playback.masterUrl
                    audioUrl = playback.variants.firstOrNull { it.audioOnly }?.url
                    // Sans flux audio seul sur la nouvelle chaîne, on revient à l'image.
                    if (audioUrl == null) setAudioOnly(false)
                    val variants = playback.variants.filter { !it.audioOnly }
                    val maxQuality = settings.state.value.maxQuality
                    val default = if (qualityChosen || maxQuality == 0) null else pickVariant(variants, maxQuality)?.name
                    val remembered = settings.state.value.channelQuality[login]
                    _state.update { s ->
                        val quality = when {
                            remembered == QUALITY_AUTO -> null
                            variants.any { it.name == remembered } -> remembered
                            // Sinon la qualité choisie suit le zapping si la chaîne suivante la propose.
                            else -> s.quality?.takeIf { q -> variants.any { it.name == q } } ?: default
                        }
                        s.copy(variants = variants, quality = quality, canAudioOnly = audioUrl != null)
                    }
                    play()
                }
                .onFailure { e ->
                    val error = e.toLoadError()
                    _state.update { it.copy(status = PlayerStatus.Failed(error)) }
                    if (error == LoadError.Offline) _state.update { it.copy(ended = endedChoices(login)) }
                }
        }
    }

    /** Les trois suites sont cherchées en parallèle ; une qui échoue est simplement absente. */
    private suspend fun endedChoices(login: String): Ended = coroutineScope {
        val next = async { liveList().firstOrNull { it.login != login } }
        val lastVideo = async {
            loadCatching {
                val channelId = _state.value.stream?.channelId ?: catalog.channel(login)?.id ?: return@loadCatching null
                catalog.channelVideos(channelId, null).items.firstOrNull()
            }.getOrNull()
        }
        Ended(raid = lastRaid, next = next.await(), lastVideo = lastVideo.await())
    }

    @OptIn(UnstableApi::class)
    private fun play() {
        val state = _state.value
        val url = (if (state.audioOnly) audioUrl else null)
            ?: state.variants.firstOrNull { it.name == state.quality }?.url ?: masterUrl ?: return
        player.setMediaSource(liveMediaSource(context, url, settings.state.value.lowLatency))
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
        AudioOnlyService.stop(context)
        player.release()
    }
}
