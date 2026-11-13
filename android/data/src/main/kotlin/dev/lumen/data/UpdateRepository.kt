package dev.lumen.data

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.AppUpdate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Nouvelle version publiée sur les GitHub Releases du projet. */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreHolder: CoreHolder,
    private val coreSettings: CoreSettings,
    private val settings: SettingsRepository,
) {
    val currentVersion: String get() = coreSettings.appVersion

    /** Une seule proposition automatique par lancement de l'app. */
    var promptedThisLaunch = false

    suspend fun check(): AppUpdate? =
        coreHolder.get().checkUpdate(coreSettings.appVersion, Build.SUPPORTED_ABIS.toList(), settings.awaitLoaded().betaUpdates)

    /** APK téléchargé dans le cache (`updates/`), partagé à l'installeur par le FileProvider. */
    suspend fun download(update: AppUpdate): File {
        val file = File(context.cacheDir, "updates/lumen.apk")
        coreHolder.get().downloadUpdate(update.apkUrl, file.absolutePath)
        return file
    }
}
