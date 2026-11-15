package dev.lumen.tv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.ModalSideRail
import dev.lumen.designsystem.SideRailItem

enum class ShellTab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Search(R.string.nav_search, R.drawable.ic_nav_search),
    Home(R.string.nav_home, R.drawable.ic_nav_home),
    Following(R.string.nav_following, R.drawable.ic_nav_favorite),
    Profiles(R.string.nav_profiles, R.drawable.ic_nav_person),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
}

/** Un compte doit se reconnecter : une pastille sur « Profils » le signale depuis n'importe quel onglet. */
val LocalNeedsReauth = compositionLocalOf { false }

/**
 * Barre latérale repliée en icônes ; elle s'ouvre par-dessus la page quand le focus y entre (gauche),
 * sans pousser ni remesurer les rangées, et sans animer sa largeur (saccades sur les box modestes).
 */
@Composable
fun Shell(selected: ShellTab?, onTab: (ShellTab) -> Unit, content: @Composable () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    ModalSideRail(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        entry = selectedFocus.takeIf { selected != null },
        rail = {
            val needsReauth = LocalNeedsReauth.current
            ShellTab.entries.forEach { tab ->
                val label = stringResource(tab.label)
                val reauth = stringResource(R.string.nav_needs_reauth)
                SideRailItem(
                    selected = tab == selected,
                    onClick = { onTab(tab) },
                    label = label,
                    modifier = (if (tab == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                        .semantics { if (tab == ShellTab.Profiles && needsReauth) stateDescription = reauth },
                ) {
                    Icon(painterResource(tab.icon), contentDescription = null)
                    if (tab == ShellTab.Profiles && needsReauth) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(10.dp)
                                .background(LumenColors.Error, CircleShape),
                        )
                    }
                }
            }
        },
        content = content,
    )
}
