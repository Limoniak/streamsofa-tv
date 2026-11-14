package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.lumen.core.ChatMessage
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached

/** Messages proposés dans la liste : ceux des spectateurs, le plus récent en tête. */
internal fun ChatUiState.browsable(max: Int = 60): List<ChatMessage> =
    lines.filter { it.system == null && it.userId.isNotEmpty() }.takeLast(max).asReversed()

/** Messages de [userId] dans [messages], le plus récent en tête. */
internal fun List<ChatMessage>.from(userId: String): List<ChatMessage> = filter { it.userId == userId }

/**
 * Le chat n'est pas sélectionnable à la télécommande : cette liste, figée à l'ouverture, l'est.
 * OK sur un message ouvre la fiche de son auteur (badges, ses messages récents) ; Retour revient à la liste.
 */
@Composable
fun ChatBrowser(messages: List<ChatMessage>, onDismiss: () -> Unit) {
    var user by remember { mutableStateOf<ChatMessage?>(null) }
    val listFocus = remember { FocusRequester() }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(user) { if (user == null) listFocus.requestWhenAttached() }
    Dialog(
        onDismissRequest = { if (user != null) user = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .heightIn(max = 480.dp)
                .background(LumenColors.Surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val selected = user
            if (selected == null) {
                Text(stringResource(R.string.player_chat_browse), style = MaterialTheme.typography.headlineSmall)
                if (messages.isEmpty()) {
                    Text(stringResource(R.string.chat_browse_empty), color = LumenColors.OnSurfaceMuted)
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(messages, key = { it.id }) { message ->
                        ListItem(
                            selected = false,
                            onClick = {
                                lastOpened = message.id
                                user = message
                            },
                            headlineContent = {
                                Text(message.displayName, color = Color(message.color.toInt()), fontWeight = FontWeight.Bold, maxLines = 1)
                            },
                            supportingContent = { Text(message.plainText(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            // Retour de la fiche : le focus revient sur le message ouvert, sinon sur le plus récent.
                            modifier = if (message.id == (lastOpened ?: messages.first().id)) Modifier.focusRequester(listFocus) else Modifier,
                        )
                    }
                }
            } else {
                UserCard(selected, messages.from(selected.userId))
            }
        }
    }
}

@Composable
private fun UserCard(user: ChatMessage, messages: List<ChatMessage>) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        user.badges.forEach { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(22.dp)) }
        Text(user.displayName, style = MaterialTheme.typography.headlineSmall, color = Color(user.color.toInt()))
        if (!user.displayName.equals(user.login, ignoreCase = true)) {
            Text("(${user.login})", style = MaterialTheme.typography.titleMedium, color = LumenColors.OnSurfaceMuted)
        }
    }
    Text(pluralStringResource(R.plurals.chat_user_messages, messages.size, messages.size), style = MaterialTheme.typography.titleSmall, color = LumenColors.Accent)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(messages, key = { it.id }) { message ->
            // Focalisables pour faire défiler une longue liste à la télécommande.
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text(message.plainText(), maxLines = 3, overflow = TextOverflow.Ellipsis) },
                modifier = if (message == messages.first()) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}
