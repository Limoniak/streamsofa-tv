package dev.lumen.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.ChannelActivity
import dev.lumen.core.LiveStream
import dev.lumen.core.PickerEmote
import dev.lumen.core.Raid
import dev.lumen.core.Variant
import dev.lumen.core.Video
import dev.lumen.data.LoadError
import dev.lumen.designsystem.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.lumen.data.R as DataR

/**
 * Le lecteur live à la télécommande seule : zapping, liste des lives, chat, barre d'actions,
 * raid, écran de fin. L'écran est monté sans ViewModel ni ExoPlayer ([FakeActions]), sous Robolectric.
 */
@RunWith(AndroidJUnit4::class)
// Écran de télé 1080p : la barre d'actions et la colonne de chat tiennent côte à côte.
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class PlayerRemoteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun stream(login: String) = LiveStream(
        channelId = "id-$login", login = login, displayName = login.replaceFirstChar(Char::uppercase), avatarUrl = "",
        title = "Titre de $login", categoryId = "1", categoryName = "Jeu", viewers = 100u,
        startedAt = 0, thumbnailUrl = "",
    )

    private fun variant(name: String) = Variant(
        name = name, url = "https://example.invalid/$name.m3u8", bandwidth = 1uL, width = 1280u, height = 720u,
        frameRate = 30f, source = false, audioOnly = false,
    )

    private class FakeActions(
        override val chatAllowed: Boolean = true,
        override val chatByDefault: Boolean = false,
        override val maxTiles: Int = 1,
        private val lives: List<LiveStream> = emptyList(),
    ) : PlayerActions {
        override val chatTimestamps = false
        override val chatLook = ChatLook()
        val calls = mutableListOf<String>()

        override fun zap(delta: Int) { calls += "zap:$delta" }
        override fun open(login: String) { calls += "open:$login" }
        override fun retry() { calls += "retry" }
        override fun followRaid() { calls += "followRaid" }
        override fun cancelRaid() { calls += "cancelRaid" }
        override fun selectQuality(name: String?) { calls += "quality:$name" }
        override fun toggleAudioOnly() { calls += "audioOnly" }
        override fun cycleSleepTimer() { calls += "sleep" }
        override fun sendChat(text: String, replyTo: String?) { calls += "send:$text" }
        override suspend fun liveList() = lives
        override suspend fun multiCandidates() = lives
        override suspend fun channelActivity() = ChannelActivity(poll = null, prediction = null)
        override suspend fun chatEmotes() = emptyList<PickerEmote>()
    }

    private val alice = stream("alice")
    private val live = PlayerUiState(
        login = "alice", stream = alice, status = PlayerStatus.Playing, canZap = true,
        variants = listOf(variant("1080p60"), variant("720p")),
    )
    private var state by mutableStateOf(live)
    private var openedChannel: String? = null
    private var openedVod: String? = null

    private fun show(actions: FakeActions, canSend: Boolean = false) {
        rule.setContent {
            LumenTheme {
                PlayerScreen(
                    state = state,
                    chat = ChatUiState(),
                    canSend = canSend,
                    myLogin = null,
                    actions = actions,
                    onMultiView = {},
                    onOpenChannel = { openedChannel = it },
                    onOpenVod = { openedVod = it },
                    video = {},
                    stats = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
    private fun node(id: Int, vararg args: Any) = rule.onNodeWithText(text(id, *args))

    @Test
    fun upAndDownZapBetweenChannels() {
        val actions = FakeActions()
        show(actions)
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertEquals(listOf("zap:1", "zap:-1"), actions.calls)
    }

    @Test
    fun withoutNeighbourUpShowsTheKeyHelpInsteadOfZapping() {
        state = live.copy(canZap = false)
        val actions = FakeActions()
        show(actions)
        // Le panneau d'infos affiché à l'ouverture se referme seul.
        rule.mainClock.advanceTimeBy(6_000)
        node(R.string.player_keys).assertDoesNotExist()

        press(Key.DirectionUp)
        node(R.string.player_keys).assertExists()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun infoPanelHidesAfterAFewSeconds() {
        show(FakeActions())
        node(R.string.player_keys_zap).assertExists()
        rule.mainClock.advanceTimeBy(6_000)
        node(R.string.player_keys_zap).assertDoesNotExist()
    }

    @Test
    fun okOpensTheActionBarAndBackClosesIt() {
        val actions = FakeActions()
        show(actions)
        press(Key.DirectionCenter)
        // Le premier bouton prend le focus.
        node(R.string.player_chat_show).assertIsFocused()

        // Dans la barre, ▲ ▼ ne zappent pas.
        press(Key.DirectionUp)
        assertEquals(emptyList<String>(), actions.calls)

        back()
        node(R.string.player_chat_show).assertDoesNotExist()
        // La télécommande revient au lecteur.
        press(Key.DirectionUp)
        assertEquals(listOf("zap:1"), actions.calls)
    }

    @Test
    fun actionBarReachesQualityAndChannelPage() {
        val actions = FakeActions()
        show(actions)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        node(R.string.player_quality, text(R.string.player_quality_auto)).assertIsFocused()

        // OK déroule les qualités, le focus va sur celle en cours (Auto).
        press(Key.DirectionCenter)
        rule.onNodeWithText("720p").assertExists()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("quality:720p"), actions.calls)

        // ▲ remonte sur « Qualité » ; puis Mise en veille, Page de la chaîne.
        press(Key.DirectionUp)
        repeat(2) { press(Key.DirectionRight) }
        node(R.string.player_channel).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("alice", openedChannel)
    }

    @Test
    fun leftOpensTheLiveListOnTheCurrentChannel() {
        val actions = FakeActions(lives = listOf(stream("zoe"), alice, stream("bob")))
        show(actions)
        press(Key.DirectionLeft)
        rule.onNodeWithText("Alice").assertIsFocused()

        press(Key.DirectionDown)
        rule.onNodeWithText("Bob").assertIsFocused()
        // La liste garde ▲ ▼ pour elle : aucun zapping par-dessous.
        assertEquals(emptyList<String>(), actions.calls)

        press(Key.DirectionCenter)
        assertEquals(listOf("open:bob"), actions.calls)
        rule.onNodeWithText("Bob").assertDoesNotExist()
    }

    @Test
    fun leftOrBackClosesTheLiveList() {
        val actions = FakeActions(lives = listOf(alice, stream("bob")))
        show(actions)
        press(Key.DirectionLeft)
        rule.onNodeWithText("Bob").assertExists()
        press(Key.DirectionLeft)
        rule.onNodeWithText("Bob").assertDoesNotExist()

        press(Key.DirectionLeft)
        rule.onNodeWithText("Bob").assertExists()
        back()
        rule.onNodeWithText("Bob").assertDoesNotExist()

        // Liste fermée : ▲ zappe de nouveau.
        press(Key.DirectionUp)
        assertEquals(listOf("zap:1"), actions.calls)
    }

    @Test
    fun guestRightTogglesTheChat() {
        show(FakeActions())
        node(R.string.chat_connecting).assertDoesNotExist()
        press(Key.DirectionRight)
        node(R.string.chat_connecting).assertExists()
        // Lecture seule : pas de bouton « Écrire ».
        node(R.string.player_write).assertDoesNotExist()
        press(Key.DirectionRight)
        node(R.string.chat_connecting).assertDoesNotExist()
    }

    @Test
    fun signedInRightGoesToWriteThenHidesTheChat() {
        val actions = FakeActions()
        show(actions, canSend = true)
        press(Key.DirectionRight)
        node(R.string.chat_connecting).assertExists()
        press(Key.DirectionRight)
        node(R.string.player_write).assertIsFocused()

        // Sur « Écrire », ▲ ▼ ne zappent pas.
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertEquals(emptyList<String>(), actions.calls)

        // ◀ revient à la vidéo, chat toujours affiché.
        press(Key.DirectionLeft)
        node(R.string.chat_connecting).assertExists()
        press(Key.DirectionUp)
        assertEquals(listOf("zap:1"), actions.calls)

        // ▶ ▶ : « Écrire », puis le chat disparaît.
        press(Key.DirectionRight)
        node(R.string.player_write).assertIsFocused()
        press(Key.DirectionRight)
        node(R.string.chat_connecting).assertDoesNotExist()
        press(Key.DirectionDown)
        assertEquals(listOf("zap:1", "zap:-1"), actions.calls)
    }

    @Test
    fun okOnWriteOpensTheComposer() {
        show(FakeActions(chatByDefault = true), canSend = true)
        press(Key.DirectionRight)
        node(R.string.player_write).assertIsFocused()
        press(Key.DirectionCenter)
        node(R.string.chat_send).assertExists()
    }

    @Test
    fun kidsProfileHasNoChat() {
        show(FakeActions(chatAllowed = false))
        press(Key.DirectionRight)
        node(R.string.chat_connecting).assertDoesNotExist()

        press(Key.DirectionCenter)
        node(R.string.player_chat_show).assertDoesNotExist()
        // Le premier bouton restant prend le focus.
        node(R.string.player_quality, text(R.string.player_quality_auto)).assertIsFocused()
    }

    @Test
    fun multiViewOnlyWhenTheDeviceAllowsIt() {
        show(FakeActions(maxTiles = 1))
        press(Key.DirectionCenter)
        node(R.string.player_multi).assertDoesNotExist()
    }

    @Test
    fun multiViewButtonOpensThePicker() {
        show(FakeActions(maxTiles = 4, lives = listOf(stream("bob"))))
        press(Key.DirectionCenter)
        node(R.string.player_multi).assertExists()
        // Chat, Qualité, Mise en veille, Multi-view.
        repeat(3) { press(Key.DirectionRight) }
        node(R.string.player_multi).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText(rule.activity.resources.getQuantityString(R.plurals.multi_pick_title, 3, 3)).assertExists()
    }

    @Test
    fun raidOkFollowsAndBackStays() {
        state = live.copy(raid = Raid(toLogin = "bob", toName = "Bob", viewers = 42u))
        val actions = FakeActions()
        show(actions)
        press(Key.DirectionCenter)
        assertEquals(listOf("followRaid"), actions.calls)
        back()
        assertEquals(listOf("followRaid", "cancelRaid"), actions.calls)
    }

    @Test
    fun failedPlaybackOkRetries() {
        state = live.copy(status = PlayerStatus.Failed(LoadError.Network))
        val actions = FakeActions()
        show(actions)
        node(DataR.string.error_network).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("retry"), actions.calls)
    }

    @Test
    fun sleepTimerStopOkResumes() {
        state = live.copy(sleeping = true)
        val actions = FakeActions()
        show(actions)
        node(R.string.player_slept).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("retry"), actions.calls)
    }

    @Test
    fun endedScreenOffersWhatComesNext() {
        val video = Video(
            id = "v1", title = "Rediffusion", channelId = "id-alice", channelLogin = "alice", channelName = "Alice",
            durationS = 60u, publishedAt = 0, views = 1u, thumbnailUrl = "", positionS = 0u, finished = false,
        )
        state = live.copy(
            status = PlayerStatus.Failed(LoadError.Offline),
            ended = Ended(next = stream("bob"), lastVideo = video),
        )
        val actions = FakeActions()
        show(actions)
        node(R.string.player_ended, "Alice").assertExists()
        node(R.string.player_ended_next, "Bob").assertIsFocused()

        // ◀ ▶ passent d'une suite à l'autre ; ▲ ▼ zappent toujours.
        press(Key.DirectionRight)
        node(R.string.player_ended_video).assertIsFocused()
        press(Key.DirectionUp)
        assertEquals(listOf("zap:1"), actions.calls)

        press(Key.DirectionCenter)
        assertEquals("v1", openedVod)

        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf("zap:1", "open:bob"), actions.calls)
    }

    @Test
    fun talkBackAnnouncesTheChannel() {
        show(FakeActions())
        rule.onNodeWithContentDescription("Alice, Titre de alice").assertIsFocused()
        state = live.copy(login = "bob", stream = stream("bob"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Bob, Titre de bob").assertExists()
    }

    @Test
    fun releasingOkDoesNotPressTheFirstActionButton() {
        show(FakeActions())
        // Comme une vraie télécommande : le relâchement arrive après l'ouverture de la barre et le déplacement du focus.
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).onFirst().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.waitForIdle()
        // La barre est ouverte, le chat ne s'est pas affiché tout seul.
        node(R.string.player_chat_show).assertIsFocused()
        node(R.string.chat_connecting).assertDoesNotExist()
    }

    @Test
    fun fromTheStartOpensTheArchivedVideo() {
        state = live.copy(archiveId = "a1")
        show(FakeActions())
        press(Key.DirectionCenter)
        // Chat, Qualité, Mise en veille, Depuis le début.
        repeat(3) { press(Key.DirectionRight) }
        node(R.string.player_from_start).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("a1", openedVod)
    }

    @Test
    fun noFromTheStartWithoutArchive() {
        show(FakeActions())
        press(Key.DirectionCenter)
        node(R.string.player_from_start).assertDoesNotExist()
    }
}
