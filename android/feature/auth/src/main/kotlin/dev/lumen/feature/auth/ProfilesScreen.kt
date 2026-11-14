package dev.lumen.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalResources
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
import dev.lumen.core.ProfileLock
import java.util.Optional
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
        locks = ProfileLocks(
            checkPin = viewModel::checkPin,
            checkParentPin = viewModel::checkParentPin,
            setPin = viewModel::setPin,
            setKids = viewModel::setKids,
            resetGuest = viewModel::resetGuest,
            kidsLimit = viewModel::kidsLimit,
            setKidsLimit = viewModel::setKidsLimit,
            grantKidsTime = viewModel::grantKidsTime,
        ),
    )
}

/** Actions de l'écran des profils sur les PIN et le mode enfants (`null` = l'invité). */
class ProfileLocks(
    val checkPin: suspend (String?, String) -> Boolean,
    val checkParentPin: suspend (String) -> Boolean,
    val setPin: (String?, String?) -> Unit,
    val setKids: (String?, Boolean) -> Unit,
    val resetGuest: () -> Unit,
    /** Temps d'écran d'un profil enfants, en minutes par jour (0 = sans limite). */
    val kidsLimit: suspend (String?) -> Int = { 0 },
    val setKidsLimit: (String?, Int) -> Unit = { _, _ -> },
    /** Minutes en plus pour aujourd'hui. */
    val grantKidsTime: (String?, Int) -> Unit = { _, _ -> },
)

/** Une saisie de PIN en attente : [check] la valide, puis [then] reçoit le PIN tapé. */
private class PinPrompt(val title: String, val check: suspend (String) -> Boolean, val hint: String? = null, val then: (String) -> Unit)

private class Confirm(val title: String, val body: String, val action: () -> Unit)

@Composable
fun ProfilesScreen(
    snapshot: AccountsSnapshot?,
    onSelect: (Account) -> Unit,
    onGuest: () -> Unit,
    onAddAccount: () -> Unit,
    onRemove: (Account) -> Unit,
    locks: ProfileLocks,
) {
    // Textes lus au clic (titres des boîtes PIN et de confirmation), à jour si la langue change.
    val resources = LocalResources.current
    var prompt by remember { mutableStateOf<PinPrompt?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    // Profil dont on affiche les options : `Some(null)` = l'invité.
    var options by remember { mutableStateOf<Optional<String>?>(null) }
    // Profil dont on règle le temps d'écran (après le PIN parent).
    var screenTimeFor by remember { mutableStateOf<Optional<String>?>(null) }
    val lockOf = { id: String? -> snapshot?.locks?.firstOrNull { it.profileId == id } }
    val hasParentPin = snapshot?.locks.orEmpty().any { it.hasPin && !it.kids }

    fun nameOf(id: String?) = snapshot?.accounts?.firstOrNull { it.id == id }?.displayName ?: resources.getString(R.string.profiles_guest)

    /** Profil protégé : son PIN d'abord. */
    fun ownGate(id: String?, then: () -> Unit) {
        if (lockOf(id)?.hasPin != true) return then()
        prompt = PinPrompt(resources.getString(R.string.pin_enter, nameOf(id)), { locks.checkPin(id, it) }) { then() }
    }

    /** Profil enfants : un PIN parent, s'il en existe un, pour le lever ou le retirer. */
    fun parentGate(id: String?, then: () -> Unit) {
        if (lockOf(id)?.kids != true || !hasParentPin) return then()
        prompt = PinPrompt(resources.getString(R.string.pin_parent), locks.checkParentPin, resources.getString(R.string.pin_parent_hint)) { then() }
    }

    fun open(id: String?, enter: () -> Unit) = ownGate(id, enter)

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
                        badges = lockOf(account.id).badges(),
                        onClick = { open(account.id) { onSelect(account) } },
                        onLongClick = { options = Optional.of(account.id) },
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
                        badges = lockOf(null).badges(),
                        onClick = { open(null, onGuest) },
                        onLongClick = { options = Optional.empty() },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.profiles_remove_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    options?.let { selected ->
        val id = selected.orElse(null)
        val lock = lockOf(id)
        val account = snapshot?.accounts?.firstOrNull { it.id == id }
        ProfileOptionsDialog(
            name = nameOf(id),
            hasPin = lock?.hasPin == true,
            kids = lock?.kids == true,
            guest = id == null,
            needsParentPin = lock?.kids == true && !hasParentPin,
            onSetPin = {
                options = null
                ownGate(id) {
                    prompt = PinPrompt(resources.getString(R.string.pin_new, nameOf(id)), { true }) { locks.setPin(id, it) }
                }
            },
            onRemovePin = {
                options = null
                ownGate(id) { locks.setPin(id, null) }
            },
            onToggleKids = {
                options = null
                if (lock?.kids == true) parentGate(id) { locks.setKids(id, false) } else locks.setKids(id, true)
            },
            onScreenTime = if (lock?.kids == true) {
                {
                    options = null
                    parentGate(id) { screenTimeFor = Optional.ofNullable(id) }
                }
            } else {
                null
            },
            onRemove = {
                options = null
                // PIN oublié : le profil se retire toujours, sauf un profil enfants protégé par un parent.
                parentGate(id) {
                    confirm = if (account != null) {
                        Confirm(resources.getString(R.string.profiles_remove_title, account.displayName), resources.getString(R.string.profiles_remove_body)) { onRemove(account) }
                    } else {
                        Confirm(resources.getString(R.string.profiles_reset_guest_title), resources.getString(R.string.profiles_reset_guest_body), locks.resetGuest)
                    }
                }
            },
            onDismiss = { options = null },
        )
    }

    prompt?.let { current ->
        PinDialog(
            title = current.title,
            hint = current.hint,
            check = current.check,
            onSuccess = { pin ->
                prompt = null
                current.then(pin)
            },
            onDismiss = { prompt = null },
        )
    }

    screenTimeFor?.let { selected ->
        val id = selected.orElse(null)
        ScreenTimeDialog(
            name = nameOf(id),
            load = { locks.kidsLimit(id) },
            onLimit = { locks.setKidsLimit(id, it) },
            onGrant = {
                screenTimeFor = null
                locks.grantKidsTime(id, EXTRA_MINUTES)
            },
            onDismiss = { screenTimeFor = null },
        )
    }

    confirm?.let { current ->
        ConfirmDialog(
            title = current.title,
            body = current.body,
            onConfirm = {
                confirm = null
                current.action()
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ProfileLock?.badges(): String = listOfNotNull(
    stringResource(R.string.profiles_kids_badge).takeIf { this?.kids == true },
    stringResource(R.string.profiles_pin_badge).takeIf { this?.hasPin == true },
).joinToString("  ·  ")

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
    badges: String = "",
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
            if (badges.isNotEmpty()) {
                Text(text = badges, style = MaterialTheme.typography.labelMedium, color = LumenColors.Accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProfileOptionsDialog(
    name: String,
    hasPin: Boolean,
    kids: Boolean,
    guest: Boolean,
    needsParentPin: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onToggleKids: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    /** `null` : pas un profil enfants. */
    onScreenTime: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onSetPin, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(stringResource(if (hasPin) R.string.profiles_pin_change else R.string.profiles_pin_set))
            }
            if (hasPin) {
                OutlinedButton(onClick = onRemovePin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profiles_pin_remove))
                }
            }
            OutlinedButton(onClick = onToggleKids, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (kids) R.string.profiles_kids_off else R.string.profiles_kids_on))
            }
            if (onScreenTime != null) {
                OutlinedButton(onClick = onScreenTime, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profiles_screen_time))
                }
            }
            Text(
                text = stringResource(if (needsParentPin) R.string.profiles_kids_no_parent else R.string.profiles_kids_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (guest) R.string.profiles_reset_guest else R.string.profiles_remove_confirm))
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    dev.lumen.designsystem.ConfirmDialog(
        title = title,
        body = body,
        confirm = stringResource(R.string.profiles_remove_confirm),
        cancel = stringResource(R.string.profiles_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )

/** Limites proposées, en minutes ; 0 = sans limite. */
private val LIMITS = listOf(0, 30, 60, 90, 120, 180)
private const val EXTRA_MINUTES = 30

/** Temps d'écran d'un profil enfants : limite par jour, et 30 minutes de plus pour aujourd'hui. */
@kotlin.OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun ScreenTimeDialog(
    name: String,
    load: suspend () -> Int,
    onLimit: (Int) -> Unit,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    var limit by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { limit = load() }
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(limit != null) { if (limit != null) runCatching { first.requestFocus() } }
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.profiles_screen_time_title, name), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LIMITS.forEach { minutes ->
                    val selected = minutes == limit
                    androidx.tv.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            limit = minutes
                            onLimit(minutes)
                        },
                        modifier = if (selected) Modifier.focusRequester(first) else Modifier,
                    ) {
                        Text(if (minutes == 0) stringResource(R.string.profiles_screen_time_off) else formatMinutes(minutes))
                    }
                }
            }
            OutlinedButton(onClick = onGrant, enabled = (limit ?: 0) > 0) {
                Text(stringResource(R.string.profiles_screen_time_extra, EXTRA_MINUTES))
            }
            Text(
                stringResource(R.string.profiles_screen_time_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** « 30 min », « 1 h », « 1 h 30 ». */
internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}
