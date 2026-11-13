package dev.lumen.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable

private const val PREFETCH = "#EXT-X-TWITCH-PREFETCH:"
/** Cible au plus près du direct ; ExoPlayer l'allonge de lui-même après une coupure. */
private const val LOW_LATENCY_OFFSET_MS = 2_000L

/**
 * Faible latence (expérimental) : Twitch annonce en fin de playlist les segments en cours
 * d'encodage (`#EXT-X-TWITCH-PREFETCH`). Les déclarer comme segments normaux rapproche le bord
 * du direct d'autant ; le CDN les sert au fil de l'encodage.
 */
internal fun rewritePrefetch(playlist: String): String {
    if (PREFETCH !in playlist) return playlist
    var duration = "2.000"
    return playlist.lineSequence().joinToString("\n") { line ->
        when {
            line.startsWith("#EXTINF:") -> line.also { duration = line.removePrefix("#EXTINF:").substringBefore(',') }
            // Même durée que le segment précédent : Twitch découpe à intervalle fixe.
            line.startsWith(PREFETCH) -> "#EXTINF:$duration,live\n${line.removePrefix(PREFETCH)}"
            else -> line
        }
    }
}

@OptIn(UnstableApi::class)
private class PrefetchParserFactory : HlsPlaylistParserFactory {
    private val default = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser() = wrap(default.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ) = wrap(default.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser { uri, input ->
        val text = input.bufferedReader().readText()
        parser.parse(uri, rewritePrefetch(text).byteInputStream())
    }
}

/** Source d'un live ; en faible latence, les playlists passent par [rewritePrefetch]. */
@OptIn(UnstableApi::class)
internal fun liveMediaSource(context: Context, url: String, lowLatency: Boolean): MediaSource {
    val factory = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
    if (!lowLatency) return factory.createMediaSource(liveMediaItem(url))
    factory.setPlaylistParserFactory(PrefetchParserFactory())
    return factory.createMediaSource(liveMediaItem(url, LOW_LATENCY_OFFSET_MS))
}
