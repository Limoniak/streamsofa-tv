package dev.lumen.data

/** Notes Markdown de GitHub en texte lisible à la télé : titres sans `#`, puces en « • », sans accents graves. */
fun plainNotes(markdown: String): String = markdown.lines().joinToString("\n") { line ->
    when {
        line.startsWith("#") -> line.trimStart('#', ' ')
        line.startsWith("- ") -> "• " + line.removePrefix("- ")
        else -> line
    }.replace("`", "")
}.trim()
