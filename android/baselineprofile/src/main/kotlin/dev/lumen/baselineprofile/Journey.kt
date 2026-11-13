package dev.lumen.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE = "dev.streamsofa.tv"

/** L'accueil (ou le choix du profil) a fini de charger quand une rangée est affichée. */
internal fun MacrobenchmarkScope.waitForHome() {
    device.wait(Until.hasObject(By.textContains("live")), 15_000)
}

/** Navigation au D-pad dans l'accueil : rangées de cartes, images, changement de rangée. */
internal fun MacrobenchmarkScope.browseHome() {
    repeat(4) { device.pressDPadRight() }
    repeat(2) { device.pressDPadDown() }
    repeat(4) { device.pressDPadRight() }
    device.waitForIdle()
}
