package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached

/** Saisie d'un message : le clavier système s'ouvre, « Envoyer » du clavier ou du bouton. */
@Composable
fun ChatComposer(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    val send = { if (text.isNotBlank()) onSend(text) else onDismiss() }

    // Largeur libre : la largeur par défaut d'une boîte de dialogue coupait le bouton.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            modifier = Modifier
                .background(LumenColors.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
            // Retour ferme d'abord le clavier : un bouton explicite pour sortir sans envoyer.
            Button(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        }
    }
}
