package dev.lumen.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private val ItemHeight = 48.dp
private val CollapsedItemWidth = 56.dp
private val ExpandedItemWidth = 256.dp
private val RailPadding = 12.dp

/** Place réservée à gauche de la page par la barre repliée. */
val SideRailCollapsedWidth = CollapsedItemWidth + RailPadding * 2

@Stable
class SideRailScope internal constructor() {
    /** Ouverte (libellés visibles) tant que le focus est dans la barre. */
    var expanded by mutableStateOf(false)
        internal set
}

/**
 * Barre latérale repliée en icônes, ouverte par-dessus la page quand le focus y entre.
 * Elle passe d'une largeur à l'autre sans animation : la largeur animée de tv-material
 * (`ModalNavigationDrawer`) faisait sauter des images sur les box modestes, chaque étape
 * remesurant la barre et redessinant le voile.
 * La barre a toujours un fond plein : celui de la page repliée, un panneau `Surface` ouverte.
 * Le focus y entre toujours sur [entry] (l'onglet courant) s'il est fourni, sinon sur l'entrée la plus proche.
 */
@Composable
fun ModalSideRail(
    rail: @Composable SideRailScope.() -> Unit,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val scope = remember { SideRailScope() }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = SideRailCollapsedWidth)) { content() }
        // Ouverte : la page s'assombrit d'un voile uni, la barre garde un fond plein (jamais transparent).
        if (scope.expanded) {
            Box(Modifier.fillMaxSize().background(LumenColors.Background.copy(alpha = 0.6f)))
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .onFocusChanged { scope.expanded = it.hasFocus }
                // `focusRestorer(entry)` ignore son repli à la première entrée : on redirige nous-mêmes.
                .then(if (entry != null) Modifier.focusProperties { onEnter = { entry.requestFocus() } } else Modifier)
                .focusGroup()
                .background(if (scope.expanded) LumenColors.Surface else LumenColors.Background)
                .padding(RailPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            scope.rail()
        }
    }
}

/** Entrée de [ModalSideRail] : l'icône seule une fois repliée, l'icône et [label] une fois ouverte. */
@Composable
fun SideRailScope.SideRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        // Le libellé est lu dans les deux états, même replié (icône seule).
        modifier = modifier
            .height(ItemHeight)
            .width(if (expanded) ExpandedItemWidth else CollapsedItemWidth)
            .semantics { contentDescription = label },
        shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            // Repliée, les entrées non choisies s'effacent, comme dans tv-material.
            contentColor = if (expanded) colors.onSurface else colors.onSurface.copy(alpha = 0.4f),
            focusedContainerColor = colors.inverseSurface,
            focusedContentColor = colors.inverseOnSurface,
            pressedContainerColor = colors.inverseSurface,
            pressedContentColor = colors.inverseOnSurface,
            selectedContainerColor = colors.secondaryContainer.copy(alpha = 0.4f),
            selectedContentColor = colors.onSecondaryContainer,
            focusedSelectedContainerColor = colors.inverseSurface,
            focusedSelectedContentColor = colors.inverseOnSurface,
            pressedSelectedContainerColor = colors.inverseSurface,
            pressedSelectedContentColor = colors.inverseOnSurface,
        ),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                )
            }
        }
    }
}
