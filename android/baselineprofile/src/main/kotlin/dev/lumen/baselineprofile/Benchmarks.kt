package dev.lumen.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mesures sur un appareil branché, avec et sans Baseline Profile, pour voir ce qu'il apporte
 * et repérer une régression :
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Plumen.abis=x86_64
 * Sur un émulateur les chiffres ne valent que comparés entre eux (ajouter
 * `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}

/** Fluidité du défilement de l'accueil au D-pad (durée des images, images en retard). */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun browseHomeWithoutProfile() = browse(CompilationMode.None())

    @Test
    fun browseHomeWithBaselineProfile() = browse(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun browse(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        // Processus tué avant chaque itération : le parcours repart toujours du même accueil.
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForHome()
        },
    ) {
        browseHome()
    }
}
