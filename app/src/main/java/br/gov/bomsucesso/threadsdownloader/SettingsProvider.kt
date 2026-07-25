package br.gov.bomsucesso.threadsdownloader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val prefs = requireNotNull(context).getSharedPreferences("module_settings", 0)
        return MatrixCursor(COLUMNS).apply {
            addRow(arrayOf(
                prefs.getBoolean("download_enabled", true).asInt(),
                prefs.getBoolean("options_enabled", true).asInt(),
                prefs.getBoolean("videos_enabled", true).asInt(),
                prefs.getBoolean("images_enabled", true).asInt(),
                prefs.getBoolean("notifications_enabled", true).asInt()
            ))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.threadsdownloader.settings"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun Boolean.asInt() = if (this) 1 else 0

    companion object {
        const val AUTHORITY = "br.gov.bomsucesso.threadsdownloader.settings"
        val URI: Uri = Uri.parse("content://$AUTHORITY/current")
        private val COLUMNS = arrayOf("download", "options", "videos", "images", "notifications")
    }
}
