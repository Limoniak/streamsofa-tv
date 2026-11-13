package dev.lumen.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.Video
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rangée « Continuer à regarder » du launcher Android TV. Google TV peut l'ignorer
 * pour une app installée hors Play Store : c'est sans conséquence.
 */
@Singleton
class WatchNext @Inject constructor(@ApplicationContext private val context: Context) {

    /** Ajoute ou met à jour la VOD ; la retire si elle est terminée. */
    suspend fun publish(video: Video, positionS: UInt, durationS: UInt, finished: Boolean) = withContext(Dispatchers.IO) {
        // Fournisseur absent (appareil non TV) ou refus : la reprise dans l'app reste.
        runCatching {
            val resolver = context.contentResolver
            val existing = findRow(video.id)
            if (finished) {
                existing?.let { resolver.delete(ContentUris.withAppendedId(WatchNextPrograms.CONTENT_URI, it.first), null, null) }
                return@runCatching
            }
            // Retirée par l'utilisateur depuis le launcher : on ne la remet pas.
            if (existing?.second == false) return@runCatching
            val values = ContentValues().apply {
                put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_CLIP)
                put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                put(WatchNextPrograms.COLUMN_TITLE, video.title)
                put(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, video.channelName)
                put(WatchNextPrograms.COLUMN_POSTER_ART_URI, sizedImageUrl(video.thumbnailUrl))
                put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
                put(WatchNextPrograms.COLUMN_DURATION_MILLIS, durationS.toLong() * 1000)
                put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionS.toLong() * 1000)
                put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
                put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, video.id)
                put(WatchNextPrograms.COLUMN_INTENT_URI, vodIntent(video.id).toUri(Intent.URI_INTENT_SCHEME))
            }
            if (existing == null) {
                resolver.insert(WatchNextPrograms.CONTENT_URI, values)
            } else {
                resolver.update(ContentUris.withAppendedId(WatchNextPrograms.CONTENT_URI, existing.first), values, null, null)
            }
        }
        Unit
    }

    /** (id de ligne, visible) de la VOD si elle est déjà publiée. Le fournisseur ne rend que nos lignes. */
    private fun findRow(videoId: String): Pair<Long, Boolean>? =
        context.contentResolver.query(
            WatchNextPrograms.CONTENT_URI,
            arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, WatchNextPrograms.COLUMN_BROWSABLE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == videoId) return@use cursor.getLong(0) to (cursor.getInt(2) != 0)
            }
            null
        }

    private fun vodIntent(id: String) = Intent(Intent.ACTION_VIEW, Uri.parse("lumen://vod/$id"))
        .setPackage(context.packageName)

    // Gabarit Twitch `{width}x{height}` ou `%{width}x%{height}` : le launcher veut une URL finale.
    private fun sizedImageUrl(template: String) = template
        .replace("%{width}", "640").replace("%{height}", "360")
        .replace("{width}", "640").replace("{height}", "360")
}
