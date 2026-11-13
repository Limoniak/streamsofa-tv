package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.lumen.core.ChatMessage
import dev.lumen.core.PickerEmote
import dev.lumen.data.loadCatching
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached

/**
 * Saisie d'un message : le clavier système s'ouvre, « Envoyer » du clavier ou du bouton.
 * [loadEmotes] : émotes du compte pour le sélecteur ; une émote choisie s'ajoute au texte.
 * [replyCandidates] : messages auxquels répondre, figés à l'ouverture (le chat continue de défiler).
 * [onSend] reçoit le texte et l'identifiant du message auquel on répond.
 */
@Composable
fun ChatComposer(
    onSend: (String, String?) -> Unit,
    onDismiss: () -> Unit,
    loadEmotes: (suspend () -> List<PickerEmote>)? = null,
    replyCandidates: () -> List<ChatMessage> = { emptyList() },
) {
    var text by remember { mutableStateOf("") }
    val candidates = remember { replyCandidates() }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var choosingReply by remember { mutableStateOf(false) }
    val replyFocus = remember { FocusRequester() }
    LaunchedEffect(choosingReply) { if (choosingReply) replyFocus.requestWhenAttached() }
    val focus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    var emotes by remember { mutableStateOf<List<PickerEmote>?>(null) }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    LaunchedEffect(picking) {
        if (picking && emotes == null && loadEmotes != null) emotes = loadCatching { loadEmotes() }.getOrDefault(emptyList())
    }
    LaunchedEffect(picking, emotes) { if (picking && !emotes.isNullOrEmpty()) gridFocus.requestWhenAttached() }
    val send = { if (text.isNotBlank()) onSend(text, replyTo?.id) else onDismiss() }

    // Largeur libre : la largeur par défaut d'une boîte de dialogue coupait le bouton.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(LumenColors.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            replyTo?.let { parent ->
                Text(
                    stringResource(R.string.chat_replying_to, parent.displayName, parent.plainText()),
                    color = LumenColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(900.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .width(520.dp)
                        .border(2.dp, LumenColors.Accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.chat_compose_hint), color = LumenColors.OnSurfaceMuted, fontSize = 18.sp)
                    }
                    BasicTextField(
                        value = text,
                        // Limite de Twitch : 500 caractères.
                        onValueChange = { text = it.take(500) },
                        singleLine = true,
                        textStyle = TextStyle(color = LumenColors.OnSurface, fontSize = 18.sp),
                        cursorBrush = SolidColor(LumenColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier
                            .focusRequester(focus)
                            // Sans ça, le champ garde les flèches et la télécommande y reste bloquée.
                            .onPreviewKeyEvent {
                                val out = it.type == KeyEventType.KeyDown && (it.key == Key.DirectionRight || it.key == Key.DirectionDown)
                                if (out) sendFocus.requestFocus()
                                out
                            },
                    )
                }
                Button(onClick = send, modifier = Modifier.focusRequester(sendFocus)) { Text(stringResource(R.string.chat_send)) }
                if (loadEmotes != null) {
                    Button(onClick = {
                        choosingReply = false
                        picking = !picking
                    }) { Text(stringResource(R.string.chat_emotes)) }
                }
                if (candidates.isNotEmpty()) {
                    Button(onClick = {
                        picking = false
                        // Une réponse en cours : le bouton l'annule.
                        if (replyTo != null) replyTo = null else choosingReply = !choosingReply
                    }) { Text(stringResource(if (replyTo != null) R.string.chat_reply_cancel else R.string.chat_reply)) }
                }
                // Retour ferme d'abord le clavier : un bouton explicite pour sortir sans envoyer.
                Button(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
            }
            if (choosingReply) {
                LazyColumn(modifier = Modifier.width(900.dp).height(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(candidates, key = { _, message -> message.id }) { index, message ->
                        Surface(
                            onClick = {
                                replyTo = message
                                choosingReply = false
                                focus.requestFocus()
                            },
                            modifier = (if (index == 0) Modifier.focusRequester(replyFocus) else Modifier).fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = LumenColors.Accent.copy(alpha = 0.35f),
                            ),
                        ) {
                            Text(
                                text = "${message.displayName} : ${message.plainText()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LumenColors.OnSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (picking) {
                val list = emotes
                when {
                    list == null -> Text(stringResource(R.string.chat_emotes_loading), color = LumenColors.OnSurfaceMuted)
                    list.isEmpty() -> Text(stringResource(R.string.chat_emotes_empty), color = LumenColors.OnSurfaceMuted)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(12),
                        // Écran TV = 540 dp de haut : le champ reste visible au-dessus.
                        modifier = Modifier.width(900.dp).height(300.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(list, key = { _, emote -> emote.name }) { index, emote ->
                            EmoteCell(
                                emote = emote,
                                // Ajoutée en fin de message, séparée par une espace : on peut en enchaîner plusieurs.
                                onClick = { text = (text.trimEnd() + " " + emote.name + " ").trimStart().take(500) },
                                modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmoteCell(emote: PickerEmote, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(64.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = LumenColors.Accent.copy(alpha = 0.35f),
        ),
    ) {
        AsyncImage(
            model = emote.url,
            contentDescription = emote.name,
            modifier = Modifier.padding(10.dp).size(44.dp),
        )
    }
}
