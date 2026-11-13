package dev.lumen.feature.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.Category
import dev.lumen.core.LiveStream
import dev.lumen.designsystem.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parcours à la télécommande seule : focus initial, rangée, saut de rangée, retour, OK. */
@RunWith(AndroidJUnit4::class)
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
                HomeScreen(state, onOpenStream = { opened = it }, onOpenCategory = { _, _ -> }, onReconnect = {}, onRetry = {})
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
}
