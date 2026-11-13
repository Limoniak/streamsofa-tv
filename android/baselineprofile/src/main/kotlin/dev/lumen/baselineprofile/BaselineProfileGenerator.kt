package dev.lumen.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parcours critique compilé à l'avance : démarrage à froid puis navigation au D-pad dans
 * l'accueil (rangées de cartes, images). Fonctionne en invité comme avec un compte.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndBrowse() = rule.collect(packageName = "dev.streamsofa.tv", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // L'accueil (ou le choix du profil) a fini de charger quand une rangée est affichée.
        device.wait(Until.hasObject(By.textContains("live")), 15_000)
        repeat(4) { device.pressDPadRight() }
        repeat(2) { device.pressDPadDown() }
        repeat(4) { device.pressDPadRight() }
        device.waitForIdle()
    }
}
