package dev.lumen.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.LiveStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val CHANNEL_KEY = "followed_live"
private const val MAX_PROGRAMS = 20

/**
 * Rangée « Suivis en live » du launcher Android TV (chaîne d'aperçu). Le launcher classique
 * l'affiche ; Google TV peut l'ignorer pour une app hors Play Store, sans conséquence.
 */
@Singleton
class LauncherChannel @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Mutex()

    /** Remplace le contenu de la rangée ; une liste vide la laisse vide (le launcher la masque). */
    suspend fun publish(streams: List<LiveStream>) = withContext(Dispatchers.IO) {
        lock.withLock {
            // Fournisseur absent (appareil non TV) ou refus : rien d'autre ne dépend de la rangée.
            runCatching {
                val channelId = findChannel() ?: if (streams.isEmpty()) return@runCatching else createChannel()
                val resolver = context.contentResolver
                programIds(channelId).forEach { resolver.delete(ContentUris.withAppendedId(PreviewPrograms.CONTENT_URI, it), null, null) }
                // Minute courante dans l'URL de l'aperçu : le launcher garde sinon l'image en cache.
                val minute = System.currentTimeMillis() / 60_000
                streams.take(MAX_PROGRAMS).forEachIndexed { index, stream ->
                    resolver.insert(
                        PreviewPrograms.CONTENT_URI,
                        ContentValues().apply {
                            put(PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                            put(PreviewPrograms.COLUMN_TYPE, PreviewPrograms.TYPE_CLIP)
                            put(PreviewPrograms.COLUMN_LIVE, 1)
                            put(PreviewPrograms.COLUMN_TITLE, stream.displayName)
                            put(PreviewPrograms.COLUMN_SHORT_DESCRIPTION, listOf(stream.categoryName, stream.title).filter { it.isNotBlank() }.joinToString(" · "))
                            put(PreviewPrograms.COLUMN_POSTER_ART_URI, sizedImageUrl(stream.thumbnailUrl) + "?t=$minute")
                            put(PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, PreviewPrograms.ASPECT_RATIO_16_9)
                            put(PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, stream.login)
                            put(PreviewPrograms.COLUMN_WEIGHT, MAX_PROGRAMS - index)
                            put(PreviewPrograms.COLUMN_INTENT_URI, link("live/${stream.login}").toUri(Intent.URI_INTENT_SCHEME))
                        },
                    )
                }
            }
        }
        Unit
    }

    /** La rangée appartient à l'app : le fournisseur ne rend que nos chaînes. */
    private fun findChannel(): Long? =
        context.contentResolver.query(Channels.CONTENT_URI, arrayOf(Channels._ID, Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == CHANNEL_KEY) return@use cursor.getLong(0)
                }
                null
            }

    private fun createChannel(): Long {
        val values = ContentValues().apply {
            put(Channels.COLUMN_TYPE, Channels.TYPE_PREVIEW)
            put(Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.launcher_channel_name))
            put(Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_KEY)
            context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)?.let {
                put(Channels.COLUMN_APP_LINK_INTENT_URI, it.toUri(Intent.URI_INTENT_SCHEME))
            }
        }
        val id = ContentUris.parseId(checkNotNull(context.contentResolver.insert(Channels.CONTENT_URI, values)))
        runCatching { writeLogo(id) }
        // Première chaîne de l'app : le launcher l'affiche sans demander.
        TvContract.requestChannelBrowsable(context, id)
        return id
    }

    private fun writeLogo(channelId: Long) {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 160, 160)
        icon.draw(Canvas(bitmap))
        context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun programIds(channelId: Long): List<Long> =
        context.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId), arrayOf(PreviewPrograms._ID), null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            .orEmpty()

    private fun link(path: String) = Intent(Intent.ACTION_VIEW, Uri.parse("streamsofa://$path")).setPackage(context.packageName)
}
