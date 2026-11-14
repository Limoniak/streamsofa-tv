package dev.lumen.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** Confirmation d'une action : le choix sans conséquence reçoit le focus, un appui réflexe ne change rien. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestWhenAttached() }
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Text(text = body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(cancel) }
                Button(onClick = onConfirm) { Text(confirm) }
            }
        }
    }
}
