package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.lumen.core.ChatFragment
import dev.lumen.core.ChatMessage
import dev.lumen.core.ChatState
import dev.lumen.designsystem.LumenColors

private val ChatTextSize = 17.sp
private val EmoteSize = 26.sp

/** Colonne de chat à droite de la vidéo ; le plus récent en bas, sans défilement manuel. */
@Composable
fun ChatPanel(chat: ChatUiState, modifier: Modifier = Modifier) {
    Box(modifier.background(LumenColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Liste inversée : un nouveau message s'insère en bas sans gérer le défilement.
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Sans clé : la position suit l'index 0, donc le bas. Avec des clés, Compose garderait
            // l'ancien message à l'écran et les nouveaux arriveraient sous le bord.
            items(chat.lines.asReversed()) { line ->
                ChatLine(line)
            }
        }
        if (chat.state != ChatState.JOINED && chat.lines.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_connecting),
                style = MaterialTheme.typography.bodyMedium,
                color = LumenColors.OnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ChatLine(line: ChatMessage) {
    val system = line.system
    Column(Modifier.fillMaxWidth()) {
        if (system != null) {
            Text(
                text = system,
                fontSize = ChatTextSize,
                fontStyle = FontStyle.Italic,
                color = LumenColors.Accent,
            )
        }
        if (line.fragments.isNotEmpty()) {
            val (text, inline) = remember(line) { annotate(line) }
            Text(text = text, inlineContent = inline, fontSize = ChatTextSize, color = LumenColors.OnSurface)
        }
    }
}

private fun annotate(line: ChatMessage): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val nameColor = Color(line.color.toInt())
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(line.displayName) }
        append(if (line.action) " " else " : ")
        // `/me` : tout le message dans la couleur du pseudo, comme sur Twitch.
        withStyle(SpanStyle(color = if (line.action) nameColor else Color.Unspecified)) {
            for (fragment in line.fragments) {
                when (fragment) {
                    is ChatFragment.Text -> append(fragment.text)
                    is ChatFragment.Emote -> {
                        inline.getOrPut(fragment.url) { emoteContent(fragment.url, fragment.name) }
                        appendInlineContent(fragment.url, fragment.name)
                    }
                }
            }
        }
    }
    return text to inline
}

private fun emoteContent(url: String, name: String) = InlineTextContent(
    Placeholder(EmoteSize, EmoteSize, PlaceholderVerticalAlign.Center),
) {
    AsyncImage(model = url, contentDescription = name, modifier = Modifier.fillMaxSize())
}
