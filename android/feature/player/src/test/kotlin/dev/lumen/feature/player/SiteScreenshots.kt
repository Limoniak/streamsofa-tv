package dev.lumen.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.ChannelActivity
import dev.lumen.core.ChatFragment
import dev.lumen.core.ChatMessage
import dev.lumen.core.HypeTrain
import dev.lumen.core.LiveStream
import dev.lumen.core.PickerEmote
import dev.lumen.core.PinnedMessage
import dev.lumen.designsystem.LumenTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures du site (docs/screenshots), dessinées avec l'interface réelle : la vidéo est remplacée par
 * l'image d'aperçu du live. Ne tourne qu'à la demande, jamais en CI :
 *   ./gradlew :feature:player:testDebugUnitTest --tests '*SiteScreenshots*' \
 *     -Dstreamsofa.shots.in=<dossier des aperçus> -Dstreamsofa.shots.out=<dossier de sortie>
 * Le dossier d'entrée contient `<login>.jpg` (1280×720) et `streams.json` : [[login, nom, catégorie, spectateurs], …].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SiteScreenshots {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val input = System.getProperty("streamsofa.shots.in")?.let(::File)
    private val output = System.getProperty("streamsofa.shots.out")?.let(::File)

    private fun streams(): List<LiveStream> {
        val json = org.json.JSONArray(File(input, "streams.json").readText())
        return (0 until json.length()).map { i ->
            val row = json.getJSONArray(i)
            LiveStream(
                channelId = "id$i", login = row.getString(0), displayName = row.getString(1), avatarUrl = "",
                title = row.optString(4), categoryId = "", categoryName = row.getString(2), viewers = row.getInt(3).toUInt(),
                startedAt = 0, thumbnailUrl = "",
            )
        }
    }

    private fun frame(login: String) = BitmapFactory.decodeFile(File(input, "$login.jpg").path).asImageBitmap()

    private fun line(i: Int, name: String, color: Long, text: String) = ChatMessage(
        id = "m$i", userId = "u$name", login = name.lowercase(), displayName = name, color = color.toUInt(),
        fragments = listOf(ChatFragment.Text(text)), action = false, system = null, timestampMs = 0, badges = emptyList(), replyTo = null,
    )

    private val chat = ChatUiState(
        lines = listOf(
            line(1, "pixelfox", 0xFFFF7F50, "that play was insane"),
            line(2, "Mira_tv", 0xFF8A2BE2, "multi-view on the TV is so good"),
            line(3, "dougie", 0xFF1E90FF, "GG"),
            line(4, "nightowl", 0xFF2E8B57, "who's winning the other match?"),
            line(5, "Kaelith", 0xFFFFB38A, "watching all four from the couch"),
            line(6, "sofa_king", 0xFFB79CFF, "chat follows the tile with sound, nice"),
            line(7, "rex", 0xFFDAA520, "LET'S GO"),
            line(8, "LunaBee", 0xFFFF69B4, "clean"),
        ),
        state = dev.lumen.core.ChatState.JOINED,
    )

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun multiView() {
        assumeTrue(input != null && output != null)
        val tiles = streams().filter { it.login in listOf("caedrel", "jynxzi", "ow_esports", "tubbo") }
        val state = MultiUiState(tiles.map { TileUiState(it.login, it) }, focused = 0, chatVisible = true)
        val frames = tiles.associate { it.login to frame(it.login) }
        val actions = object : MultiActions {
            override fun focus(index: Int) = Unit
            override fun remove(login: String) = true
            override fun replace(old: String, new: String) = Unit
            override fun retry(login: String) = Unit
            override suspend fun replacements() = emptyList<LiveStream>()
            override val chatAllowed = true
            override fun toggleChat() = Unit
        }
        rule.setContent {
            LumenTheme {
                MultiViewScreen(state, actions, onOpenSingle = {}, chat = chat, chatLook = ChatLook()) { login ->
                    frames[login]?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        save("multiview.png")
    }

    @Test
    fun playerWithChatExtras() {
        assumeTrue(input != null && output != null)
        val stream = streams().first { it.login == "caedrel" }
        val image = frame(stream.login)
        val actions = object : PlayerActions {
            override val chatAllowed = true
            override val chatByDefault = true
            override val chatTimestamps = false
            override val chatLook = ChatLook()
            override val maxTiles = 4
            override fun zap(delta: Int) = Unit
            override fun open(login: String) = Unit
            override fun retry() = Unit
            override fun followRaid() = Unit
            override fun cancelRaid() = Unit
            override fun openNowLive() = Unit
            override fun dismissNowLive() = Unit
            override fun selectQuality(name: String?) = Unit
            override fun toggleAudioOnly() = Unit
            override fun cycleSleepTimer() = Unit
            override fun sendChat(text: String, replyTo: String?) = Unit
            override suspend fun liveList() = emptyList<LiveStream>()
            override suspend fun multiCandidates() = emptyList<LiveStream>()
            override suspend fun channelActivity() = ChannelActivity(
                poll = null, prediction = null,
                pinned = PinnedMessage(sender = "Moderator", text = "Sample pinned message: welcome to everyone watching from the couch!"),
                hypeTrain = HypeTrain(level = 2u, progress = 1180u, goal = 1600u),
            )
            override suspend fun chatEmotes() = emptyList<PickerEmote>()
        }
        val state = PlayerUiState(
            login = stream.login, stream = stream, status = PlayerStatus.Playing, canZap = true, archiveId = "1",
        )
        rule.setContent {
            LumenTheme {
                PlayerScreen(
                    state = state, chat = chat.copy(room = RoomModes(slowS = 3)), canSend = false, myLogin = null, actions = actions,
                    onMultiView = {}, onOpenChannel = {}, onOpenVod = {},
                    video = { Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) },
                    stats = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        save("player-chat.png")
    }
}
