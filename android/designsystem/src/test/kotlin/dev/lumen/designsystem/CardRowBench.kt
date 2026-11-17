package dev.lumen.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Banc de mesure d'une rangée de cartes à la télécommande, lancé à la main :
 * `./gradlew :designsystem:testDebugUnitTest --tests '*CardRowBench*' -Dstreamsofa.bench=1 --rerun`.
 * Mesure, sur la JVM (ordre de grandeur relatif, pas des millisecondes d'appareil) : le temps passé à
 * composer, mesurer et dessiner pendant 30 déplacements du focus, image par image, et le nombre de
 * recompositions de la rangée et des cartes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardRowBench {
    @get:Rule
    val rule = createComposeRule()

    private val streams = List(60) { i ->
        StreamCardModel(
            key = "s$i", title = "Titre du live numéro $i, assez long pour être coupé", channelName = "Chaine$i",
            categoryName = "Just Chatting", viewers = 1000 + i, thumbnailTemplate = "", avatarUrl = "",
        )
    }

    @Test
    fun scrollARowWithTheRemote() {
        assumeTrue(System.getProperty("streamsofa.bench") != null)
        var cardCompositions = 0
        val first = FocusRequester()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LumenTheme {
                LazyRow(contentPadding = PaddingValues(48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(streams, key = { it.key }) { stream ->
                        SideEffect { cardCompositions++ }
                        StreamCard(stream, onClick = {}, modifier = if (stream.key == "s0") Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle { first.requestFocus() }
        rule.mainClock.advanceTimeBy(1_000)
        // Échauffement de la JVM, puis mesure.
        val runs = LongArray(80)
        for (run in runs.indices) {
            val key = if (run % 2 == 0) Key.DirectionRight else Key.DirectionLeft
            cardCompositions = 0
            val start = System.nanoTime()
            repeat(30) {
                rule.onRoot().performKeyInput { pressKey(key) }
                repeat(10) { rule.mainClock.advanceTimeByFrame() }
            }
            runs[run] = (System.nanoTime() - start) / 1_000
        }
        val steady = runs.drop(30).sorted()
        println("BENCH median_us=${steady[steady.size / 2]} p25_us=${steady[steady.size / 4]} min_us=${steady.first()} itemCompositions=$cardCompositions")
    }
}
