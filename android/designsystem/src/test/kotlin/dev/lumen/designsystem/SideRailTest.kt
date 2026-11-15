package dev.lumen.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La barre latérale à la télécommande : ◀ l'ouvre sur l'onglet courant, ▶ la referme, OK choisit. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SideRailTest {
    @get:Rule
    val rule = createComposeRule()

    private val tabs = listOf("Recherche", "Accueil", "Réglages")
    private val clicked = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            LumenTheme {
                val selected = remember { FocusRequester() }
                ModalSideRail(
                    entry = selected,
                    rail = {
                        tabs.forEach { tab ->
                            SideRailItem(
                                selected = tab == "Accueil",
                                onClick = { clicked += tab },
                                label = tab,
                                modifier = if (tab == "Accueil") Modifier.focusRequester(selected).testTag(tab) else Modifier.testTag(tab),
                            ) {}
                        }
                    },
                ) {
                    val page = remember { FocusRequester() }
                    Box(Modifier.size(200.dp).testTag("page").focusRequester(page).focusable())
                    LaunchedEffect(Unit) { page.requestFocus() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun opensOnTheCurrentTabAndClosesOnRight() {
        show()
        rule.onNodeWithTag("page").assertIsFocused()
        // Repliée : icônes seules, mais chaque entrée garde son libellé pour TalkBack.
        rule.onNodeWithContentDescription("Accueil").assertExists()
        assertEquals(56f, rule.onNodeWithTag("Accueil").fetchSemanticsNode().size.width / 2f)

        press(Key.DirectionLeft)
        rule.onNodeWithTag("Accueil").assertIsFocused()
        assertEquals(256f, rule.onNodeWithTag("Accueil").fetchSemanticsNode().size.width / 2f)

        press(Key.DirectionDown)
        rule.onNodeWithTag("Réglages").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("Réglages"), clicked)

        press(Key.DirectionRight)
        rule.onNodeWithTag("page").assertIsFocused()
        assertEquals(56f, rule.onNodeWithTag("Accueil").fetchSemanticsNode().size.width / 2f)
    }
}
