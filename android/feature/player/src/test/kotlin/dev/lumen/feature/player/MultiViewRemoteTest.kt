package dev.lumen.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.LiveStream
import dev.lumen.data.LoadError
import dev.lumen.designsystem.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Le multi-view à la télécommande seule, sans ViewModel ni ExoPlayer ([FakeMulti]), sous Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class MultiViewRemoteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun stream(login: String) = LiveStream(
        channelId = "id-$login", login = login, displayName = login.replaceFirstChar(Char::uppercase), avatarUrl = "",
        title = "", categoryId = "1", categoryName = "Jeu", viewers = 1u, startedAt = 0, thumbnailUrl = "",
    )

    private var state by mutableStateOf(
        MultiUiState(listOf("alice", "bob", "carol", "dave").map { TileUiState(it, stream(it)) }),
    )

    /** Reproduit l'effet des actions sur l'état, comme le ViewModel. */
    private inner class FakeMulti(private val others: List<LiveStream> = emptyList()) : MultiActions {
        private val log = mutableListOf<String>()
        /** Sans le focus initial sur la première tuile, qui n'est pas une touche. */
        val calls: List<String> get() = log.dropWhile { it == "focus:0" }
        override fun focus(index: Int) {
            log += "focus:$index"
            state = state.copy(focused = index)
        }
        override fun remove(login: String): Boolean {
            log += "remove:$login"
            if (state.tiles.size <= 1) return false
            val tiles = state.tiles.filterNot { it.login == login }
            state = state.copy(tiles = tiles, focused = state.focused.coerceAtMost(tiles.lastIndex))
            return true
        }
        override fun replace(old: String, new: String) {
            log += "replace:$old>$new"
            state = state.copy(tiles = state.tiles.map { if (it.login == old) TileUiState(new, stream(new)) else it })
        }
        override fun retry(login: String) { log += "retry:$login" }
        override suspend fun replacements() = others
    }

    private var opened: String? = null

    private fun show(actions: FakeMulti) {
        rule.setContent { LumenTheme { MultiViewScreen(state, actions, onOpenSingle = { opened = it }) {} } }
        rule.waitForIdle()
    }

    /** Fenêtre au premier plan : le menu ou la liste quand ils sont ouverts, l'écran sinon. */
    private fun topRoot() = rule.onAllNodes(isRoot()).onLast()

    private fun press(key: Key) {
        topRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun longPressOk() {
        topRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
    }

    private fun text(id: Int) = rule.activity.getString(id)

    @Test
    fun arrowsMoveTheSoundAcrossTheGrid() {
        val actions = FakeMulti()
        show(actions)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        assertEquals(2, state.focused)
        assertEquals(listOf("focus:1", "focus:3", "focus:2"), actions.calls)
    }

    @Test
    fun okOpensTheFocusedTileFullScreen() {
        show(FakeMulti())
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals("bob", opened)
    }

    @Test
    fun okOnAFailedTileRetriesIt() {
        state = state.copy(tiles = state.tiles.mapIndexed { i, t -> if (i == 0) t.copy(status = TileStatus.Failed(LoadError.Network)) else t })
        val actions = FakeMulti()
        show(actions)
        press(Key.DirectionCenter)
        assertEquals(listOf("retry:alice"), actions.calls)
        assertEquals(null, opened)
    }

    @Test
    fun longPressRemovesWithoutOpeningTheNeighbour() {
        val actions = FakeMulti()
        show(actions)
        longPressOk()
        // Le menu propose d'abord « Remplacer ».
        rule.onNodeWithText(text(R.string.multi_replace)).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("remove:alice"), actions.calls.filter { it.startsWith("remove") })
        assertEquals(3, state.tiles.size)
        // Le relâchement de OK n'a ouvert aucune tuile.
        assertEquals(null, opened)
    }

    @Test
    fun removingTheLastButOneTileOpensTheSinglePlayer() {
        state = MultiUiState(listOf(TileUiState("alice", stream("alice"))))
        show(FakeMulti())
        longPressOk()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals("alice", opened)
    }

    @Test
    fun replaceKeepsTheTileAtItsPlace() {
        val actions = FakeMulti(others = listOf(stream("erin")))
        show(actions)
        press(Key.DirectionRight)
        longPressOk()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Erin").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("replace:bob>erin"), actions.calls.filter { it.startsWith("replace") })
        assertEquals(listOf("alice", "erin", "carol", "dave"), state.tiles.map { it.login })
        // La liste fermée, la télécommande revient sur la tuile qui a le son.
        press(Key.DirectionCenter)
        assertEquals("erin", opened)
    }

    @Test
    fun backClosesTheReplacementList() {
        show(FakeMulti(others = listOf(stream("erin"))))
        longPressOk()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Erin").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Erin").assertDoesNotExist()
    }

    @Test
    fun decoderRefusalIsExplainedOnTheTile() {
        state = state.copy(tiles = state.tiles.mapIndexed { i, t -> if (i == 3) t.copy(status = TileStatus.DecoderBusy) else t })
        show(FakeMulti())
        rule.onNodeWithText(text(R.string.multi_decoder_busy)).assertExists()
    }

    @Test
    fun keyHintDisappearsAfterAFewSeconds() {
        show(FakeMulti())
        rule.onNodeWithText(text(R.string.multi_hint)).assertExists()
        rule.mainClock.advanceTimeBy(6_000)
        rule.onNodeWithText(text(R.string.multi_hint)).assertDoesNotExist()
    }
}
