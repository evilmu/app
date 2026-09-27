package br.gov.bomsucesso.threadsdownloader.storage

import android.content.Context
import org.json.JSONArray
import java.util.LinkedHashSet

/** Stores the last 30 media URLs so a stray image never hides a captured video. */
object CapturedUrlStore {
    private const val PREFS = "captured_media"
    private const val KEY = "recent_urls"
    private const val LEGACY = "last_url"
    private const val CAPACITY = 30

    @Synchronized
    fun save(context: Context, url: String) {
        val entries = LinkedHashSet<String>()
        entries.add(url)
        entries.addAll(readAll(context))
        val arr = JSONArray()
        entries.take(CAPACITY).forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .putString(LEGACY, url)
            .commit()
    }

    @Synchronized
    fun readAll(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = mutableListOf<String>()
        val json = prefs.getString(KEY, null)
        if (json != null) {
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    if (value.startsWith("https://") && value !in result) result.add(value)
                }
            }
        }
        prefs.getString(LEGACY, null)?.let { if (result.isEmpty()) result.add(it) }
        return result.take(CAPACITY)
    }

    fun read(context: Context): String? = readAll(context).firstOrNull()

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY).remove(LEGACY).commit()
    }
}
