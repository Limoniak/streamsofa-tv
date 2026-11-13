package dev.lumen.tv

import android.content.Context
import dev.lumen.core.Core
import dev.lumen.core.CoreConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoreDiagnostics(
    val coreVersion: String,
    val asyncOk: Boolean,
    val secretStoreOk: Boolean,
    val clientIdConfigured: Boolean,
    val elapsedMs: Long,
)

/** Frontière entre l'UI et le cœur Rust : remplaçable par un faux dans les tests. */
interface CoreGateway {
    suspend fun diagnostics(): CoreDiagnostics
}

class RustCoreGateway(context: Context) : CoreGateway {
    private val appContext = context.applicationContext

    private val core: Core by lazy {
        Core(
            CoreConfig(
                clientId = BuildConfig.TWITCH_CLIENT_ID,
                dataDir = appContext.filesDir.absolutePath,
                appVersion = BuildConfig.VERSION_NAME,
            ),
            KeystoreSecretStore(appContext),
        )
    }

    override suspend fun diagnostics(): CoreDiagnostics = withContext(Dispatchers.Default) {
        val result = core.selfTest()
        CoreDiagnostics(
            coreVersion = result.coreVersion,
            asyncOk = result.asyncOk,
            secretStoreOk = result.secretStoreOk,
            clientIdConfigured = core.hasClientId(),
            elapsedMs = result.elapsedMs.toLong(),
        )
    }
}
