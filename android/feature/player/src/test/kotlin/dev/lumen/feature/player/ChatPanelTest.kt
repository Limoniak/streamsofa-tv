package dev.lumen.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.ChatEvent
import dev.lumen.core.ChatFragment
import dev.lumen.core.ChatMessage
import dev.lumen.designsystem.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La colonne de chat reste collée au plus récent, même avec des lignes à clés. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class ChatPanelTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun message(i: Int) = ChatMessage(
        id = "m$i", userId = "u$i", login = "user$i", displayName = "User$i", color = 0xFFFFFFFFu,
        fragments = listOf(ChatFragment.Text("message numéro $i")), action = false, system = null,
        timestampMs = 0, badges = emptyList(), replyTo = null,
    )

    private var chat by mutableStateOf(ChatUiState())

    private fun add(range: IntRange) {
        chat = chat.applyEvents(range.map { ChatEvent.Message(message(it)) })
        rule.waitForIdle()
    }

    @Test
    fun newestMessageStaysVisible() {
        rule.setContent { LumenTheme { ChatPanel(chat, Modifier.width(340.dp).height(300.dp)) } }
        add(0..5)
        rule.onNodeWithText("message numéro 5", substring = true).assertIsDisplayed()
        // Assez de lignes pour dépasser l'écran, puis la limite de 150 : le bas suit toujours.
        repeat(20) { batch -> add(10 + batch * 10..19 + batch * 10) }
        rule.onNodeWithText("message numéro 209", substring = true).assertIsDisplayed()
        rule.onNodeWithText("message numéro 5", substring = true).assertDoesNotExist()
    }

    @Test
    fun duplicateIdsDoNotCrash() {
        rule.setContent { LumenTheme { ChatPanel(chat, Modifier.width(340.dp).height(300.dp)) } }
        add(1..3)
        add(3..3)
        rule.onNodeWithText("message numéro 3", substring = true).assertIsDisplayed()
    }
}
