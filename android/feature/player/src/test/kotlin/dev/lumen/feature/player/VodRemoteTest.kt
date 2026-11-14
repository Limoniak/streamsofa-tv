package dev.lumen.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.Video
import dev.lumen.data.LoadError
import dev.lumen.designsystem.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.lumen.data.R as DataR

/** Le lecteur VOD à la télécommande seule, sans ViewModel ni ExoPlayer ([FakeVod]), sous Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class VodRemoteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class FakeVod(override val chatAllowed: Boolean = true) : VodActions {
        override val chatByDefault = false
        override val chatLook = ChatLook()
        val calls = mutableListOf<String>()
        override fun retry() { calls += "retry" }
        override fun togglePause() { calls += "pause" }
        override fun cycleSpeed() { calls += "speed" }
        override fun seekBy(deltaMs: Long) { calls += "seek:$deltaMs" }
        override fun setChatVisible(visible: Boolean) { calls += "chat:$visible" }
    }

    private val video = Video(
        id = "v1", title = "Rediffusion du soir", channelId = "1", channelLogin = "alice", channelName = "Alice",
        durationS = 3600u, publishedAt = 0, views = 1u, thumbnailUrl = "", positionS = 0u, finished = false,
    )
    private var state by mutableStateOf(VodUiState(id = "v1", video = video, status = PlayerStatus.Playing))
    private var clock by mutableStateOf(VodClock(positionMs = 60_000, durationMs = 3_600_000))

    private fun show(actions: FakeVod) {
        rule.setContent { LumenTheme { VodPlayerScreen(state, clock, ChatUiState(), actions) {} } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    /** Le chat est d'abord signalé masqué, à l'ouverture : on ne garde que les touches. */
    private fun FakeVod.keys() = calls.drop(if (calls.firstOrNull() == "chat:false") 1 else 0)

    @Test
    fun okPausesAndArrowsSeekTenSeconds() {
        val actions = FakeVod()
        show(actions)
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        press(Key.MediaPlayPause)
        assertEquals(listOf("pause", "seek:-10000", "seek:10000", "pause"), actions.keys())
    }

    @Test
    fun holdingRightSeeksOneMinuteAtATime() {
        val actions = FakeVod()
        show(actions)
        // Touche maintenue : les répétitions avancent d'une minute.
        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(1_500)
            keyUp(Key.DirectionRight)
        }
        rule.waitForIdle()
        val seeks = actions.keys()
        assertEquals("seek:10000", seeks.first())
        assertTrue(seeks.toString(), seeks.size > 1 && seeks.drop(1).all { it == "seek:60000" })
    }

    @Test
    fun upCyclesSpeedAndDownTogglesTheChat() {
        val actions = FakeVod()
        show(actions)
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals(listOf("speed", "chat:true", "chat:false"), actions.keys())
    }

    @Test
    fun kidsProfileNeverLoadsTheChat() {
        val actions = FakeVod(chatAllowed = false)
        show(actions)
        press(Key.DirectionDown)
        assertTrue(actions.calls.none { it == "chat:true" })
    }

    @Test
    fun infoStaysWhilePausedAndHidesWhilePlaying() {
        show(FakeVod())
        rule.onNodeWithText(video.title).assertExists()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithText(video.title).assertDoesNotExist()

        clock = clock.copy(paused = true)
        press(Key.DirectionCenter)
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText(text(R.string.vod_paused)).assertExists()
        rule.onNodeWithText(video.title).assertExists()
    }

    @Test
    fun speedIsShownOnlyWhenNotNormal() {
        show(FakeVod())
        rule.onNodeWithText(text(R.string.vod_speed, "1.5")).assertDoesNotExist()
        state = state.copy(speed = 1.5f)
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.vod_speed, "1.5")).assertExists()
    }

    @Test
    fun failedPlaybackOkRetriesAndShowsTwitchReason() {
        state = state.copy(status = PlayerStatus.Failed(LoadError.Unavailable), detail = "Réservée aux abonnés")
        val actions = FakeVod()
        show(actions)
        rule.onNodeWithText("Réservée aux abonnés").assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("retry"), actions.keys())
    }

    @Test
    fun failedWithoutDetailShowsTheGenericError() {
        state = state.copy(status = PlayerStatus.Failed(LoadError.Network))
        show(FakeVod())
        rule.onNodeWithText(text(DataR.string.error_network)).assertExists()
    }
}
