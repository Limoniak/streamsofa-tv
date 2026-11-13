package dev.lumen.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Message d'erreur ou d'état vide, avec une action optionnelle.
 * [takeFocus] : l'action reçoit le focus (sinon la télécommande n'aurait rien à sélectionner).
 */
@Composable
fun MessageState(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    takeFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = LumenColors.OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.focusRequester(focus)) { Text(actionLabel) }
            if (takeFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}
