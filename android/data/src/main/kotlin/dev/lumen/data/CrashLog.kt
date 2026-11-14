package dev.lumen.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Taille maximale du journal : au-delà, on garde la fin (crashs les plus récents). */
private const val MAX_BYTES = 256 * 1024

/**
 * Journal de crash local, sur demande de l'utilisateur (Paramètres) : rien n'est envoyé,
 * l'utilisateur l'exporte dans Téléchargements pour le joindre à un ticket.
 */
@Singleton
class CrashLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val file get() = File(context.filesDir, "crash.log")

    /** À appeler une fois au démarrage ; n'écrit que si l'option est active au moment du crash. */
    fun install(versionName: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // shortcut: un crash avant la lecture des préférences (premières ms) n'est pas journalisé.
            if (settings.state.value.crashLog) runCatching { append(versionName, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun append(versionName: String, thread: Thread, error: Throwable) {
        val entry = buildString {
            appendLine("=== ${LocalDateTime.now()} · StreamSofa $versionName · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine(error.stackTraceToString())
        }
        val text = (if (file.exists()) file.readText() else "") + entry
        file.writeText(if (text.length > MAX_BYTES) text.takeLast(MAX_BYTES) else text)
    }

    fun hasEntries(): Boolean = file.exists() && file.length() > 0

    fun clear() {
        file.delete()
    }

    /** Copie dans Téléchargements (API 29+, sans permission) ; renvoie le nom du fichier. Appel hors thread principal. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun exportToDownloads(): String {
        val name = "streamsofa-crash-${System.currentTimeMillis() / 1000}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { out -> file.inputStream().use { it.copyTo(checkNotNull(out)) } }
        return name
    }

    companion object {
        val canExport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
