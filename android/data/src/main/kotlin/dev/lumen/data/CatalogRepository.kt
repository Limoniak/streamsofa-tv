package dev.lumen.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.CategoryPage
import dev.lumen.core.CoreException
import dev.lumen.core.LiveStream
import dev.lumen.core.Playback
import dev.lumen.core.StreamPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
