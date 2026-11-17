package dev.lumen.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La carte à la télécommande : OK clique au relâchement, OK maintenu ouvre les options sans cliquer. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class LumenCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(longClick: Boolean = true) {
        val first = FocusRequester()
        rule.setContent {
            LumenTheme {
                Row {
                    LumenCard(
                        onClick = { events += "click:a" },
                        onLongClick = if (longClick) ({ events += "long:a" }) else null,
                        modifier = Modifier.size(100.dp).focusRequester(first).testTag("a"),
                    ) {}
                    LumenCard(onClick = { events += "click:b" }, modifier = Modifier.size(100.dp).testTag("b")) {}
                }
            }
        }
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
    }

    @Test
    fun okClicksOnRelease() {
        show()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), events)
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("click:a"), events)
    }

    @Test
    fun holdingOkOpensOptionsWithoutClicking() {
        show()
        // 600 ms : au-delà du délai de répétition, la touche maintenue se répète.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter, 600) }
        rule.waitForIdle()
        assertEquals(listOf("long:a"), events)
    }

    @Test
    fun holdingOkWithoutOptionsStillClicksOnce() {
        show(longClick = false)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter, 600) }
        rule.waitForIdle()
        assertEquals(listOf("click:a"), events)
    }

    @Test
    fun focusMovesBetweenCardsAndAccessibilityClickWorks() {
        show()
        rule.onNodeWithTag("a").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("b").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag("a").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("click:b", "click:a"), events)
    }
}
