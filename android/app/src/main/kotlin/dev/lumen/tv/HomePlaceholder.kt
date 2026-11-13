package dev.lumen.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.lumen.core.AccountState
import dev.lumen.core.AccountsSnapshot
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets

/** Écran provisoire du jalon 2 : remplacé par l'accueil (suivis, catégories) au jalon 3. */
@Composable
fun HomePlaceholder(snapshot: AccountsSnapshot, onSwitchProfile: () -> Unit, onReconnect: () -> Unit) {
    val active = snapshot.accounts.firstOrNull { it.id == snapshot.activeId }
    val needsReauth = active?.state == AccountState.NEEDS_REAUTH
    val focus = remember { FocusRequester() }
    LaunchedEffect(needsReauth) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                active == null -> stringResource(R.string.home_guest)
                needsReauth -> stringResource(R.string.home_needs_reauth, active.displayName)
                else -> stringResource(R.string.home_signed_in, active.displayName)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = if (needsReauth) LumenColors.Error else MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (needsReauth) {
                Button(onClick = onReconnect, modifier = Modifier.focusRequester(focus)) {
                    Text(stringResource(R.string.home_reconnect))
                }
            }
            OutlinedButton(
                onClick = onSwitchProfile,
                modifier = if (needsReauth) Modifier else Modifier.focusRequester(focus),
            ) {
                Text(stringResource(R.string.home_switch_profile))
            }
        }
    }
}
