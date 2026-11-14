package dev.lumen.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaque traduction (`values-xx/strings.xml`) doit porter exactement les clés de l'anglais (`values/`)
 * et les mêmes paramètres (`%1$s`, `%2$d`…) : une clé oubliée retomberait en anglais sans prévenir,
 * un paramètre faux planterait à l'affichage. Vaut pour tous les modules, pas seulement `:data`.
 */
class TranslationsTest {
    private val entry = Regex("""<string name="(\w+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d+\$[sd]""")

    /** Le test tourne dans `android/data` ; on remonte à `android/`. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun strings(file: File): Map<String, String> =
        entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resDirs(): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isDirectory && it.name == "res" && File(it, "values/strings.xml").exists() }
        .toList()

    @Test
    fun everyLanguageHasTheSameKeysAndPlaceholders() {
        val dirs = resDirs()
        assertTrue("aucun strings.xml trouvé sous $root", dirs.isNotEmpty())
        val problems = mutableListOf<String>()
        for (res in dirs) {
            val english = strings(File(res, "values/strings.xml"))
            val translations = res.listFiles { dir -> dir.name.startsWith("values-") && File(dir, "strings.xml").exists() }.orEmpty()
            for (dir in translations.sortedBy { it.name }) {
                val where = "${res.relativeTo(root)}/${dir.name}"
                val translated = strings(File(dir, "strings.xml"))
                (english.keys - translated.keys).forEach { problems += "$where : clé manquante $it" }
                (translated.keys - english.keys).forEach { problems += "$where : clé inconnue $it" }
                for ((key, value) in translated) {
                    val expected = placeholder.findAll(english[key] ?: continue).map { it.value }.sorted().toList()
                    val actual = placeholder.findAll(value).map { it.value }.sorted().toList()
                    if (expected != actual) problems += "$where : paramètres de $key ($actual au lieu de $expected)"
                    // Une apostrophe nue tronque la chaîne à la compilation des ressources.
                    if (Regex("""(?<!\\)'""").containsMatchIn(value)) problems += "$where : apostrophe non échappée dans $key"
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun everyModuleOffersTheSameLanguages() {
        val perModule = resDirs().associate { res ->
            res.relativeTo(root).path to res.listFiles { dir -> dir.name.startsWith("values-") && File(dir, "strings.xml").exists() }
                .orEmpty().map { it.name }.sorted()
        }
        assertEquals(perModule.toString(), 1, perModule.values.toSet().size)
    }

    @Test
    fun localeConfigListsEveryLanguage() {
        val config = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("""android:name="([\w-]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        // `values-pt-rBR` ↔ `pt-BR`.
        val shipped = File(root, "app/src/main/res").listFiles { dir -> dir.name.startsWith("values-") && File(dir, "strings.xml").exists() }
            .orEmpty().map { it.name.removePrefix("values-").replace("-r", "-") }.toSet() + "en"
        assertEquals(shipped, declared)
    }

    @Test
    fun apkKeepsEveryLanguage() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val filters = Regex("""localeFilters \+= listOf\(([^)]*)\)""").find(gradle)?.groupValues?.get(1)
            ?.let { list -> Regex("\"([\\w-]+)\"").findAll(list).map { it.groupValues[1].replace("-r", "-") }.toSet() }
        val declared = Regex("""android:name="([\w-]+)"""")
            .findAll(File(root, "app/src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()
        // Une langue absente du filtre serait retirée de l'APK sans erreur de compilation.
        // Le filtre s'écrit comme les dossiers (`pt-rBR`), locales_config en BCP 47 (`pt-BR`).
        assertEquals(declared, filters)
    }
}
