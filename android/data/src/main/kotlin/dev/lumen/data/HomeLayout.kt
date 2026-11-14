package dev.lumen.data

/** Rangées possibles de l'accueil ; [key] est stocké dans la préférence `home_rows`. */
enum class HomeRow(val key: String) {
    Followed("followed"),
    ContinueWatching("continue"),
    Recent("recent"),
    FavoriteCategories("favorites"),
    ForYou("for_you"),
    Recommended("recommended"),
    Popular("popular"),
    Language("language"),
    Categories("categories"),
}

data class HomeRowSetting(val row: HomeRow, val visible: Boolean)

/** Ordre et visibilité par défaut : toutes les rangées, dans l'ordre de [HomeRow]. */
val DEFAULT_HOME_ROWS: List<HomeRowSetting> = HomeRow.entries.map { HomeRowSetting(it, true) }

/**
 * `followed,-recent,popular…` : l'ordre choisi, `-` pour une rangée masquée. Une clé inconnue
 * (rangée retirée d'une version) est ignorée ; une rangée absente (ajoutée depuis) est ajoutée à la
 * fin, visible : une mise à jour montre ses nouveautés sans défaire l'ordre choisi.
 */
fun parseHomeRows(value: String?): List<HomeRowSetting> {
    if (value.isNullOrBlank()) return DEFAULT_HOME_ROWS
    val byKey = HomeRow.entries.associateBy { it.key }
    val chosen = value.split(',').mapNotNull { raw ->
        val key = raw.trim()
        val row = byKey[key.removePrefix("-")] ?: return@mapNotNull null
        HomeRowSetting(row, visible = !key.startsWith("-"))
    }.distinctBy { it.row }
    return chosen + HomeRow.entries.filter { row -> chosen.none { it.row == row } }.map { HomeRowSetting(it, true) }
}

fun List<HomeRowSetting>.toHomeRowsValue(): String = joinToString(",") { (if (it.visible) "" else "-") + it.row.key }

/** Déplace [row] d'un cran ([delta] = -1 vers le haut, +1 vers le bas), sans sortir de la liste. */
fun List<HomeRowSetting>.moved(row: HomeRow, delta: Int): List<HomeRowSetting> {
    val from = indexOfFirst { it.row == row }
    val to = from + delta
    if (from < 0 || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

fun List<HomeRowSetting>.toggled(row: HomeRow): List<HomeRowSetting> =
    map { if (it.row == row) it.copy(visible = !it.visible) else it }
