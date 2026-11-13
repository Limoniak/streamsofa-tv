package dev.lumen.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.Core
import dev.lumen.core.CoreConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Valeurs de build fournies par le module `app`. */
data class CoreSettings(val twitchClientId: String, val appVersion: String)

/**
 * Instance unique du cœur Rust, créée à la première demande sur un thread d'E/S :
 * charger le .so et ouvrir la base ne doit jamais retarder la première image.
 */
@Singleton
class CoreHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: CoreSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val core = scope.async(start = CoroutineStart.LAZY) {
        Core(
            CoreConfig(
                clientId = settings.twitchClientId,
                dataDir = context.filesDir.absolutePath,
                appVersion = settings.appVersion,
            ),
            KeystoreSecretStore(context),
        )
    }

    suspend fun get(): Core = core.await()
}
