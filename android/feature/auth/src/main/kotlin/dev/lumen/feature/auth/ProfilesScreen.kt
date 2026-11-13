package dev.lumen.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.AccountsSnapshot
import dev.lumen.designsystem.LumenCard
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets

@Composable
fun ProfilesRoute(
    onEnter: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ProfilesEvent.Enter -> onEnter()
                ProfilesEvent.Reconnect -> onAddAccount()
            }
        }
    }
    ProfilesScreen(
        snapshot = snapshot,
        onSelect = viewModel::select,
        onGuest = viewModel::continueAsGuest,
        onAddAccount = onAddAccount,
        onRemove = viewModel::remove,
    )
}

@Composable
fun ProfilesScreen(
    snapshot: AccountsSnapshot?,
    onSelect: (Account) -> Unit,
    onGuest: () -> Unit,
    onAddAccount: () -> Unit,
    onRemove: (Account) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Account?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = LumenInsets.vertical),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.profiles_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(32.dp))

        if (snapshot != null) {
            // Focus initial : le profil actif, sinon la première carte. Une seule fois par écran.
            val initialFocus = remember { FocusRequester() }
            val initialKey = remember {
                snapshot.accounts.firstOrNull { it.id == snapshot.activeId }?.id
                    ?: snapshot.accounts.firstOrNull()?.id
                    ?: ADD_KEY
            }
            LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                // Marge verticale : la carte focalisée grossit et ne doit pas être rognée.
                contentPadding = PaddingValues(horizontal = LumenInsets.horizontal, vertical = 16.dp),
            ) {
                items(snapshot.accounts, key = { it.id }) { account ->
                    ProfileCard(
                        title = account.displayName,
                        subtitle = when {
                            account.state == AccountState.NEEDS_REAUTH -> stringResource(R.string.profiles_needs_reauth)
                            account.id == snapshot.activeId -> stringResource(R.string.profiles_active)
                            else -> "@${account.login}"
                        },
                        warning = account.state == AccountState.NEEDS_REAUTH,
                        avatarUrl = account.avatarUrl,
                        glyph = account.displayName.take(1).uppercase(),
                        onClick = { onSelect(account) },
                        onLongClick = { pendingRemoval = account },
                        modifier = if (account.id == initialKey) Modifier.focusRequester(initialFocus) else Modifier,
                    )
                }
                item(key = ADD_KEY) {
                    ProfileCard(
                        title = stringResource(R.string.profiles_add),
                        subtitle = "",
                        glyph = "+",
                        onClick = onAddAccount,
                        modifier = if (initialKey == ADD_KEY) Modifier.focusRequester(initialFocus) else Modifier,
                    )
                }
                item(key = GUEST_KEY) {
                    ProfileCard(
                        title = stringResource(R.string.profiles_guest),
                        subtitle = stringResource(R.string.profiles_guest_hint),
                        glyph = "?",
                        onClick = onGuest,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            if (snapshot.accounts.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_remove_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingRemoval?.let { account ->
        RemoveAccountDialog(
            account = account,
            onConfirm = {
                pendingRemoval = null
                onRemove(account)
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

private const val ADD_KEY = "lumen:add"
private const val GUEST_KEY = "lumen:guest"

@Composable
private fun ProfileCard(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String = "",
    warning: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    LumenCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.size(width = 200.dp, height = 232.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(104.dp).clip(CircleShape).background(LumenColors.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                // L'initiale reste visible tant que l'avatar n'est pas chargé, ou s'il échoue.
                Text(text = glyph, style = MaterialTheme.typography.displaySmall, color = LumenColors.Accent)
                if (avatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) LumenColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RemoveAccountDialog(account: Account, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        // Le choix sans conséquence reçoit le focus : un appui réflexe ne retire rien.
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.profiles_remove_title, account.displayName),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.profiles_remove_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                    Text(stringResource(R.string.profiles_remove_cancel))
                }
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.profiles_remove_confirm))
                }
            }
        }
    }
}
