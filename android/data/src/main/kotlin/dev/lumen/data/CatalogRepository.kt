package dev.lumen.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.CategoryPage
import dev.lumen.core.Channel
import dev.lumen.core.CoreException
import dev.lumen.core.LiveStream
import dev.lumen.core.Playback
import dev.lumen.core.ReplayComment
import dev.lumen.core.StreamPage
import dev.lumen.core.Video
import dev.lumen.core.VideoPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Raison donnée par Twitch quand elle refuse la lecture (« réservé aux abonnés… »). */
val Throwable.unavailableDetail: String?
    get() = (this as? CoreException.Unavailable)?.detail

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
    suspend fun categoryStreams(categoryId: String, cursor: String?): StreamPage
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
    suspend fun saveVodProgress(id: String, positionS: UInt, durationS: UInt)
    suspend fun vodChat(id: String, offsetS: UInt): List<ReplayComment>
}

/** Le cœur fait tout le réseau sur son runtime tokio : aucun appel ne bloque le thread appelant. */
@Singleton
internal class RustCatalogRepository @Inject constructor(
    private val coreHolder: CoreHolder,
) : CatalogRepository {
    private val _followed = MutableStateFlow<List<LiveStream>>(emptyList())
    override val followed: StateFlow<List<LiveStream>> = _followed.asStateFlow()

    override suspend fun followedStreams(): List<LiveStream> = try {
        coreHolder.get().followedStreams().also { _followed.value = it }
    } catch (e: CoreException.NotSignedIn) {
        _followed.value = emptyList()
        throw e
    }

    override suspend fun topStreams(cursor: String?) = coreHolder.get().topStreams(cursor)

    override suspend fun categoryStreams(categoryId: String, cursor: String?) =
        coreHolder.get().categoryStreams(categoryId, cursor)

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
    override suspend fun saveVodProgress(id: String, positionS: UInt, durationS: UInt) {
        val finished = withContext(Dispatchers.IO) { coreHolder.get().saveVodProgress(id, positionS, durationS) }
        _vodProgress.update { it + (id to VodProgress(positionS, finished)) }
    }

    override suspend fun vodChat(id: String, offsetS: UInt) = coreHolder.get().vodChat(id, offsetS)
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
