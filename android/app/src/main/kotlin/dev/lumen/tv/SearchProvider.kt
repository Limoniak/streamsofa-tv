package dev.lumen.tv

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.SearchResults
import dev.lumen.data.CatalogRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suggestions pour la recherche globale (et vocale) du launcher Android TV.
 * Chaque résultat ouvre un lien `lumen://` que la navigation sait suivre.
 */
class SearchProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun catalog(): CatalogRepository
    }

    override fun onCreate() = true

    // Appelé sur un thread binder, jamais le thread principal : attendre le réseau y est permis.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val query = (args?.firstOrNull() ?: uri.lastPathSegment).orEmpty().trim()
        val cursor = MatrixCursor(COLUMNS)
        if (query.length < 2 || query == SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        val catalog = EntryPointAccessors.fromApplication(context!!.applicationContext, Deps::class.java).catalog()
        val results = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { runCatching { catalog.search(query) }.getOrNull() } }
            ?: return cursor
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull() ?: DEFAULT_LIMIT
        suggestions(results).take(limit).forEachIndexed { index, row -> cursor.addRow(arrayOf(index, *row)) }
        return cursor
    }

    override fun getType(uri: Uri) = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val DEFAULT_LIMIT = 20
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_IS_LIVE,
        )

        /** Lignes sans `_ID` : texte, sous-titre, image, lien, en direct (1/0). */
        fun suggestions(results: SearchResults): List<Array<Any>> =
            results.channels.map { c ->
                val link = if (c.live) "lumen://live/${Uri.encode(c.login)}" else "lumen://channel/${Uri.encode(c.login)}"
                arrayOf<Any>(c.displayName, if (c.live) c.title else "", c.avatarUrl, link, if (c.live) 1 else 0)
            } + results.categories.map { g ->
                val link = "lumen://category/${Uri.encode(g.id)}?name=${Uri.encode(g.name)}"
                arrayOf<Any>(g.name, "", g.boxArtUrl.replace("{width}", "285").replace("{height}", "380"), link, 0)
            }
    }
}
