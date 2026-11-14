package dev.lumen.feature.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.Account
import dev.lumen.data.DEFAULT_HOME_ROWS
import dev.lumen.data.HomeRow
import dev.lumen.data.moved
import dev.lumen.data.toggled
import dev.lumen.core.AccountState
import dev.lumen.core.Category
import dev.lumen.core.LiveStream
import dev.lumen.designsystem.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Parcours à la télécommande seule : focus initial, rangée, saut de rangée, retour, OK. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class HomeDpadTest {
    @get:Rule
    val rule = createComposeRule()

    private fun stream(i: Int) = LiveStream(
        channelId = "$i", login = "chaine$i", displayName = "Chaine$i", avatarUrl = "",
        title = "Titre $i", categoryId = "1", categoryName = "Jeu", viewers = 100u,
        startedAt = 0, thumbnailUrl = "",
    )

    private val state = HomeUiState(
        followed = Section.Ready(emptyList()),
        popular = Section.Ready((0..5).map(::stream)),
        categories = Section.Ready((0..5).map { Category("$it", "Catégorie$it", 10u, "") }),
    )

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun remoteOnlyNavigation() {
        var opened: String? = null
        rule.setContent {
            LumenTheme {
                HomeScreen(state, onOpenStream = { opened = it }, onOpenChannel = {}, onOpenCategory = { _, _ -> }, onReconnect = {}, onRetry = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Chaine0").assertIsFocused()

        press(Key.DirectionRight)
        rule.onNodeWithText("Chaine1").assertIsFocused()

        press(Key.DirectionDown)
        rule.onNodeWithText("Chaine1").assertIsNotFocused()

        // La rangée du dessus reprend la carte quittée, pas la première.
        press(Key.DirectionUp)
        rule.onNodeWithText("Chaine1").assertIsFocused()

        press(Key.DirectionCenter)
        assertEquals("chaine1", opened)
    }

    private fun show(
        state: HomeUiState,
        onHide: (String, String) -> Unit = { _, _ -> },
        onOpen: (String) -> Unit = {},
        onFavorite: (String, String) -> Unit = { _, _ -> },
        onVideo: (String) -> Unit = {},
    ) {
        rule.setContent {
            LumenTheme {
                HomeScreen(
                    state, onOpenStream = onOpen, onOpenChannel = {}, onOpenCategory = { _, _ -> }, onReconnect = {}, onRetry = {},
                    onHideCategory = onHide, onToggleFavorite = onFavorite, onOpenVideo = onVideo,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun hiddenCategoryLeavesTheRowAndItsStreamsLeavePopular() {
        val popular = (0..3).map(::stream).mapIndexed { i, s -> s.copy(categoryId = if (i % 2 == 0) "jc" else "1") }
        show(state.copy(popular = Section.Ready(popular), hiddenCategories = setOf("2", "jc")))
        rule.onNodeWithText("Chaine0").assertDoesNotExist()
        rule.onNodeWithText("Chaine1").assertExists()
        rule.onNodeWithText("Catégorie2").assertDoesNotExist()
        rule.onNodeWithText("Catégorie3").assertExists()
    }

    private fun longPressOk() {
        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
    }

    private fun pressInDialog(key: Key) {
        rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun longPressOnACategoryOffersHomeThenAsksBeforeHiding() {
        var hidden: Pair<String, String>? = null
        show(state.copy(popular = Section.Ready(emptyList())), onHide = { id, name -> hidden = id to name })
        rule.onNodeWithText("Catégorie0").assertIsFocused()
        longPressOk()
        // Menu : « Ajouter à l'accueil » d'abord, puis « Masquer ».
        pressInDialog(Key.DirectionRight)
        pressInDialog(Key.DirectionCenter)
        // Confirmation : « Annuler » a le focus, un OK réflexe ne masque rien.
        pressInDialog(Key.DirectionRight)
        pressInDialog(Key.DirectionCenter)
        assertEquals("0" to "Catégorie0", hidden)
    }

    @Test
    fun longPressOnACategoryAddsItToHome() {
        var favorite: Pair<String, String>? = null
        show(state.copy(popular = Section.Ready(emptyList())), onFavorite = { id, name -> favorite = id to name })
        longPressOk()
        pressInDialog(Key.DirectionCenter)
        assertEquals("0" to "Catégorie0", favorite)
    }

    @Test
    fun rowsFollowTheChosenOrderAndHiddenRowsDisappear() {
        val rows = DEFAULT_HOME_ROWS.moved(HomeRow.Categories, -(HomeRow.entries.size - 1)).toggled(HomeRow.Popular)
        show(state.copy(rows = rows))
        // Catégories en tête : la première carte focalisée est une catégorie, et Populaires a disparu.
        rule.onNodeWithText("Catégorie0").assertIsFocused()
        rule.onNodeWithText("Chaine0").assertDoesNotExist()
    }

    @Test
    fun favoriteCategoryGetsItsOwnRow() {
        show(
            state.copy(
                popular = Section.Ready(emptyList()),
                favorites = listOf(FavoriteRow("9", "Échecs", listOf(stream(42)))),
            ),
        )
        rule.onNodeWithText("Chaine42").assertIsFocused()
    }

    @Test
    fun continueWatchingOpensTheVideo() {
        val video = dev.lumen.core.Video(
            id = "v1", title = "Rediffusion", channelId = "1", channelLogin = "a", channelName = "A",
            durationS = 3600u, publishedAt = 0, views = 1u, thumbnailUrl = "", positionS = 600u, finished = false,
        )
        var opened: String? = null
        show(state.copy(continueWatching = listOf(video)), onVideo = { opened = it })
        rule.onNodeWithText("Rediffusion").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("v1", opened)
    }

    @Test
    fun recentRowSkipsChannelsAlreadyInFollowed() {
        val account = Account(id = "42", login = "moi", displayName = "Moi", avatarUrl = "", state = AccountState.OK)
        show(
            state.copy(
                account = account,
                followed = Section.Ready(listOf(stream(1))),
                recommended = Section.Ready(emptyList()),
                popular = Section.Ready(emptyList()),
                recent = listOf(stream(1), stream(7)),
            ),
        )
        // Suivis d'abord, puis « Regardé récemment » sans la chaîne déjà dans les suivis.
        rule.onNodeWithText("Chaine1").assertIsFocused()
        rule.onAllNodesWithText("Chaine1").assertCountEquals(1)
        press(Key.DirectionDown)
        rule.onNodeWithText("Chaine7").assertIsFocused()
    }
}
