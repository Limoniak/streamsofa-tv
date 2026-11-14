package dev.lumen.feature.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.Category
import dev.lumen.core.LiveStream
import dev.lumen.core.Video
import dev.lumen.designsystem.LumenTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capture de l'accueil pour le site, à la demande seulement (voir SiteScreenshots du lecteur). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SiteScreenshots {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val input = System.getProperty("streamsofa.shots.in")?.let(::File)
    private val output = System.getProperty("streamsofa.shots.out")?.let(::File)

    @Test
    fun home() {
        assumeTrue(input != null && output != null)
        val json = org.json.JSONArray(File(input, "streams.json").readText())
        val streams = (0 until json.length()).map { i ->
            val row = json.getJSONArray(i)
            val login = row.getString(0)
            LiveStream(
                channelId = "id$i", login = login, displayName = row.getString(1), avatarUrl = "",
                // Le vrai titre du live, relevé avec l'aperçu : rien d'inventé sur une vraie chaîne.
                title = row.optString(4),
                categoryId = "c$i", categoryName = row.getString(2), viewers = row.getInt(3).toUInt(), startedAt = 0,
                thumbnailUrl = File(input, "$login.jpg").toURI().toString(),
            )
        }
        val video = Video(
            id = "v1", title = "Finals: full broadcast", channelId = "id0", channelLogin = streams[0].login, channelName = streams[0].displayName,
            durationS = 3u * 3600u, publishedAt = 0, views = 120_000u, thumbnailUrl = streams[1].thumbnailUrl, positionS = 4000u, finished = false,
        )
        val state = HomeUiState(
            account = Account(id = "1", login = "me", displayName = "Me", avatarUrl = "", state = AccountState.OK),
            followed = Section.Ready(streams.take(4)),
            continueWatching = listOf(video, video.copy(id = "v2", title = "Season opener", thumbnailUrl = streams[3].thumbnailUrl, positionS = 900u)),
            recent = streams.drop(4),
            popular = Section.Ready(streams.reversed()),
            categories = Section.Ready(emptyList<Category>()),
            recommended = Section.Ready(emptyList()),
        )
        rule.setContent {
            LumenTheme {
                // Le fond prune vient de la coque de l'app (barre latérale), absente ici.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.background(dev.lumen.designsystem.LumenColors.Background),
                ) {
                    HomeScreen(state, onOpenStream = {}, onOpenChannel = {}, onOpenCategory = { _, _ -> }, onReconnect = {}, onRetry = {})
                }
            }
        }
        // Les vignettes arrivent de façon asynchrone (Coil) : on leur laisse le temps.
        repeat(30) {
            Thread.sleep(100)
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(output, "home-rows.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
