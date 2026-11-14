package dev.lumen.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.CategoryPage
import dev.lumen.core.Channel
import dev.lumen.core.ChannelAbout
import dev.lumen.core.ChannelActivity
import dev.lumen.core.Chapter
import dev.lumen.core.Clip
import dev.lumen.core.ClipQuality
import dev.lumen.core.CoreException
import dev.lumen.core.LiveStream
import dev.lumen.core.Playback
import dev.lumen.core.Raid
import dev.lumen.core.RaidListener
import dev.lumen.core.ReplayComment
import dev.lumen.core.SearchResults
import dev.lumen.core.StreamPage
import dev.lumen.core.Video
import dev.lumen.core.VideoPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Une erreur par état d'écran distinct : l'UI n'a pas à connaître les variantes du cœur. */
enum class LoadError { Network, NotConfigured, NotSignedIn, Offline, Unavailable, TwitchChanged, Other }

fun Throwable.toLoadError(): LoadError = when (this) {
    is CoreException.Network -> LoadError.Network
    is CoreException.NotConfigured -> LoadError.NotConfigured
    is CoreException.NotSignedIn, is CoreException.ReauthRequired -> LoadError.NotSignedIn
    is CoreException.Offline -> LoadError.Offline
    is CoreException.Unavailable -> LoadError.Unavailable
    is CoreException.GqlChanged -> LoadError.TwitchChanged
    else -> LoadError.Other
}

/** Raison donnée par Twitch quand elle refuse la lecture, traduite quand on la connaît. */
fun Throwable.unavailableDetail(context: Context): String? = when (val detail = (this as? CoreException.Unavailable)?.detail) {
    "Manifest is restricted" -> context.getString(R.string.error_subscribers_only)
    "kids_profile" -> context.getString(R.string.error_kids_profile)
    else -> detail?.ifEmpty { null }
}

/** Reprise d'une VOD : secondes vues et fin atteinte. */
data class VodProgress(val positionS: UInt, val finished: Boolean)

/** Applique les reprises enregistrées pendant la session (la liste a pu être chargée avant). */
fun Video.withProgress(progress: Map<String, VodProgress>): Video =
    progress[id]?.let { copy(positionS = it.positionS, finished = it.finished) } ?: this

/** Exécute [block] et convertit tout échec (y compris un .so absent) en [LoadError]. */
suspend fun <T> loadCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

interface CatalogRepository {
    /** Derniers lives suivis connus : sert au zapping gauche/droite dans le lecteur. */
    val followed: StateFlow<List<LiveStream>>

    suspend fun followedStreams(): List<LiveStream>
    suspend fun topStreams(cursor: String?): StreamPage
    /** [language] : code ISO 639-1 (`fr`), `null` pour toutes les langues. */
    suspend fun categoryStreams(categoryId: String, cursor: String?, language: String?): StreamPage
    /** Vide en invité. */
    suspend fun recommendedStreams(): List<LiveStream>
    suspend fun search(query: String): SearchResults
    suspend fun topCategories(cursor: String?): CategoryPage
    suspend fun liveStream(login: String): LiveStream?
    suspend fun livePlayback(login: String): Playback

    /** Reprises enregistrées depuis le lancement : les écrans déjà chargés les appliquent. */
    val vodProgress: StateFlow<Map<String, VodProgress>>

    suspend fun followedChannels(): List<Channel>
    suspend fun channel(login: String): Channel?
    suspend fun channelVideos(channelId: String, cursor: String?): VideoPage
    suspend fun video(id: String): Video?
    suspend fun vodPlayback(id: String): Playback
    /** Renvoie `true` si la VOD est considérée comme terminée. */
    suspend fun saveVodProgress(id: String, positionS: UInt, durationS: UInt): Boolean
    suspend fun vodChat(id: String, offsetS: UInt): List<ReplayComment>
    suspend fun vodChapters(id: String): List<Chapter>
    suspend fun channelActivity(login: String): ChannelActivity

    suspend fun channelAbout(login: String): ChannelAbout?
    suspend fun channelClips(login: String): List<Clip>
    /** [language] : code ISO 639-1, `null` pour toutes. */
    suspend fun categoryClips(categoryId: String, language: String?): List<Clip>
    /** Clips de la semaine des chaînes suivies ; lève `NotSignedIn` en invité. */
    suspend fun followedClips(): List<Clip>
    suspend fun clipPlayback(slug: String): List<ClipQuality>

    /** Raids lancés par [broadcasterId] tant que le flux est collecté ; rien en invité. */
    fun raids(broadcasterId: String): Flow<Raid>
}

/** Le cœur fait tout le réseau sur son runtime tokio : aucun appel ne bloque le thread appelant. */
@Singleton
internal class RustCatalogRepository @Inject constructor(
    private val coreHolder: CoreHolder,
    private val launcher: LauncherChannel,
    private val settings: SettingsRepository,
) : CatalogRepository {
    /** Ordre de Twitch ; [followed] le présente épinglées d'abord. */
    private var raw: List<LiveStream> = emptyList()
    private val _followed = MutableStateFlow<List<LiveStream>>(emptyList())
    override val followed: StateFlow<List<LiveStream>> = _followed.asStateFlow()

    init {
        // Épingler ou désépingler réordonne tout de suite l'accueil, le zapping et la liste des lives.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.state.map { it.pinnedChannels }.distinctUntilChanged().collect { pinned ->
                _followed.value = raw.pinnedFirst(pinned)
            }
        }
    }

    override suspend fun followedStreams(): List<LiveStream> = try {
        coreHolder.get().followedStreams().let { streams ->
            raw = streams
            streams.pinnedFirst(settings.state.value.pinnedChannels).also {
                _followed.value = it
                launcher.publish(it)
            }
        }
    } catch (e: CoreException.NotSignedIn) {
        raw = emptyList()
        _followed.value = emptyList()
        launcher.publish(emptyList())
        throw e
    }

    override suspend fun topStreams(cursor: String?) = coreHolder.get().topStreams(cursor)

    override suspend fun categoryStreams(categoryId: String, cursor: String?, language: String?) =
        coreHolder.get().categoryStreams(categoryId, cursor, language)

    override suspend fun recommendedStreams() = coreHolder.get().recommendedStreams()

    override suspend fun search(query: String) = coreHolder.get().search(query)

    override suspend fun topCategories(cursor: String?) = coreHolder.get().topCategories(cursor)

    override suspend fun liveStream(login: String) = coreHolder.get().liveStream(login)

    override suspend fun livePlayback(login: String) = coreHolder.get().livePlayback(login)

    private val _vodProgress = MutableStateFlow<Map<String, VodProgress>>(emptyMap())
    override val vodProgress: StateFlow<Map<String, VodProgress>> = _vodProgress.asStateFlow()

    override suspend fun followedChannels() = coreHolder.get().followedChannels()

    override suspend fun channel(login: String) = coreHolder.get().channel(login)

    override suspend fun channelVideos(channelId: String, cursor: String?) =
        coreHolder.get().channelVideos(channelId, cursor)

    override suspend fun video(id: String) = coreHolder.get().video(id)

    override suspend fun vodPlayback(id: String) = coreHolder.get().vodPlayback(id)

    // Écriture SQLite synchrone côté Rust : hors du thread principal.
    override suspend fun saveVodProgress(id: String, positionS: UInt, durationS: UInt): Boolean {
        val finished = withContext(Dispatchers.IO) { coreHolder.get().saveVodProgress(id, positionS, durationS) }
        _vodProgress.update { it + (id to VodProgress(positionS, finished)) }
        return finished
    }

    override suspend fun vodChat(id: String, offsetS: UInt) = coreHolder.get().vodChat(id, offsetS)

    override suspend fun vodChapters(id: String) = coreHolder.get().vodChapters(id)

    override suspend fun channelActivity(login: String) = coreHolder.get().channelActivity(login)

    override suspend fun channelAbout(login: String) = coreHolder.get().channelAbout(login)

    override suspend fun channelClips(login: String) = coreHolder.get().channelClips(login)

    override suspend fun categoryClips(categoryId: String, language: String?) = coreHolder.get().categoryClips(categoryId, language)

    override suspend fun followedClips() = coreHolder.get().followedClips()

    override suspend fun clipPlayback(slug: String) = coreHolder.get().clipPlayback(slug)

    override fun raids(broadcasterId: String): Flow<Raid> = callbackFlow {
        val watch = coreHolder.get().watchRaids(
            broadcasterId,
            object : RaidListener {
                override fun onRaid(raid: Raid) {
                    trySend(raid)
                }
            },
        )
        // Libérer l'objet Rust ferme la socket EventSub.
        awaitClose { watch.destroy() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CatalogModule {
    @Binds
    abstract fun catalogRepository(impl: RustCatalogRepository): CatalogRepository
}

val LoadError.messageRes: Int
    get() = when (this) {
        LoadError.Network -> R.string.error_network
        LoadError.NotConfigured -> R.string.error_not_configured
        LoadError.NotSignedIn -> R.string.error_not_signed_in
        LoadError.Offline -> R.string.error_offline
        LoadError.Unavailable -> R.string.error_unavailable
        LoadError.TwitchChanged -> R.string.error_twitch_changed
        LoadError.Other -> R.string.error_other
    }
