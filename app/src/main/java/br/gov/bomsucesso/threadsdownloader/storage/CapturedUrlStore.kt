package br.gov.bomsucesso.threadsdownloader.storage

import android.content.Context

object CapturedUrlStore {
    private const val PREFS = "captured_media"
    private const val KEY = "last_url"

    fun save(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, url)
            .apply()
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
}
