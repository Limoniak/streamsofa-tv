package dev.lumen.tv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import dev.lumen.designsystem.LumenColors

enum class ShellTab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Search(R.string.nav_search, R.drawable.ic_nav_search),
    Home(R.string.nav_home, R.drawable.ic_nav_home),
    Following(R.string.nav_following, R.drawable.ic_nav_favorite),
    Profiles(R.string.nav_profiles, R.drawable.ic_nav_person),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
}

/** Un compte doit se reconnecter : une pastille sur « Profils » le signale depuis n'importe quel onglet. */
val LocalNeedsReauth = compositionLocalOf { false }

/** Barre latérale repliée en icônes ; elle s'ouvre quand le focus y entre (gauche). */
@Composable
fun Shell(selected: ShellTab?, onTab: (ShellTab) -> Unit, content: @Composable () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    NavigationDrawer(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(12.dp)
                    .focusRestorer(selectedFocus),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                val needsReauth = LocalNeedsReauth.current
                ShellTab.entries.forEach { tab ->
                    NavigationDrawerItem(
                        selected = tab == selected,
                        onClick = { onTab(tab) },
                        leadingContent = {
                            Box {
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
                        },
                        modifier = if (tab == selected) Modifier.focusRequester(selectedFocus) else Modifier,
                    ) {
                        Text(stringResource(tab.label))
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
