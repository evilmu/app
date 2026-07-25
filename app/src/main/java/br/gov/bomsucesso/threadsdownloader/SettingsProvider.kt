package br.gov.bomsucesso.threadsdownloader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore

/**
 * Canal IPC entre o código injetado no processo do Threads e o aplicativo do módulo.
 *
 * O provider é síncrono e mais confiável que broadcasts para atualizar o painel.
 * Apenas o próprio módulo e o pacote oficial do Threads podem consultar ou gravar dados.
 */
class SettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (!isTrustedCaller()) return MatrixCursor(COLUMNS)

        val prefs = requireNotNull(context).getSharedPreferences(PREFS_SETTINGS, 0)
        return MatrixCursor(COLUMNS).apply {
            addRow(
                arrayOf(
                    prefs.getBoolean("download_enabled", true).asInt(),
                    prefs.getBoolean("options_enabled", true).asInt(),
                    prefs.getBoolean("videos_enabled", true).asInt(),
                    prefs.getBoolean("images_enabled", true).asInt(),
                    prefs.getBoolean("notifications_enabled", true).asInt()
                )
            )
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isTrustedCaller()) return result(false, "caller_not_allowed")

        val providerContext = requireNotNull(context)
        val runtime = providerContext.getSharedPreferences(PREFS_RUNTIME, 0)
        val now = System.currentTimeMillis()

        return when (method) {
            METHOD_HEARTBEAT -> {
                runtime.edit()
                    .putLong(KEY_LAST_HOOK_ACTIVE, now)
                    .putString(KEY_FRAMEWORK, extras?.getString("framework") ?: "ReLSPosed")
                    .putString(KEY_PROCESS, extras?.getString("process").orEmpty())
                    .putString(KEY_HOOK_VERSION, extras?.getString("hook_version").orEmpty())
                    .putString(KEY_LAST_EVENT, "heartbeat")
                    .apply()
                result(true)
            }

            METHOD_CAPTURED -> {
                val url = extras?.getString("url")
                if (url.isNullOrBlank() || !url.startsWith("https://")) {
                    return result(false, "invalid_url")
                }

                CapturedUrlStore.save(providerContext, url)
                runtime.edit()
                    .putLong(KEY_LAST_HOOK_ACTIVE, now)
                    .putLong(KEY_LAST_CAPTURE, now)
                    .putString(KEY_FRAMEWORK, extras.getString("framework") ?: "ReLSPosed")
                    .putString(KEY_PROCESS, extras.getString("process").orEmpty())
                    .putString(KEY_MEDIA_TYPE, extras.getString("media_type").orEmpty())
                    .putString(KEY_LAST_EVENT, "media_captured")
                    .apply()
                result(true)
            }

            METHOD_LATEST -> {
                val url = CapturedUrlStore.read(providerContext)
                if (url.isNullOrBlank() || !url.startsWith("https://")) {
                    result(false, "no_media")
                } else {
                    result(true).apply { putString("url", url) }
                }
            }

            METHOD_EVENT -> {
                val event = extras?.getString("event").orEmpty()
                val details = extras?.getString("details").orEmpty()
                val editor = runtime.edit()
                    .putLong(KEY_LAST_HOOK_ACTIVE, now)
                    .putString(KEY_LAST_EVENT, event)
                    .putString(KEY_LAST_EVENT_DETAILS, details)

                when (event) {
                    "menu_candidate" -> editor.putLong(KEY_LAST_MENU_CANDIDATE, now)
                    "menu_injected" -> editor.putLong(KEY_LAST_MENU_INJECTED, now)
                    "hook_error" -> editor
                        .putLong(KEY_LAST_ERROR_TIME, now)
                        .putString(KEY_LAST_ERROR, details)
                }
                editor.apply()
                result(true)
            }

            else -> super.call(method, arg, extras) ?: result(false, "unknown_method")
        }
    }

    private fun isTrustedCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return true

        val packages = context?.packageManager?.getPackagesForUid(callerUid).orEmpty()
        return packages.any { it == THREADS_PACKAGE }
    }

    private fun result(ok: Boolean, error: String? = null): Bundle = Bundle().apply {
        putBoolean("ok", ok)
        if (error != null) putString("error", error)
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.threadsdownloader.settings"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun Boolean.asInt() = if (this) 1 else 0

    companion object {
        const val AUTHORITY = "br.gov.bomsucesso.threadsdownloader.settings"
        const val METHOD_HEARTBEAT = "heartbeat"
        const val METHOD_CAPTURED = "captured"
        const val METHOD_LATEST = "latest"
        const val METHOD_EVENT = "event"

        const val PREFS_SETTINGS = "module_settings"
        const val PREFS_RUNTIME = "runtime_status"

        const val KEY_LAST_HOOK_ACTIVE = "last_hook_active"
        const val KEY_LAST_CAPTURE = "last_capture"
        const val KEY_LAST_MENU_CANDIDATE = "last_menu_candidate"
        const val KEY_LAST_MENU_INJECTED = "last_menu_injected"
        const val KEY_LAST_ERROR_TIME = "last_error_time"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_FRAMEWORK = "framework"
        const val KEY_PROCESS = "process"
        const val KEY_HOOK_VERSION = "hook_version"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_LAST_EVENT = "last_event"
        const val KEY_LAST_EVENT_DETAILS = "last_event_details"

        val URI: Uri = Uri.parse("content://$AUTHORITY/current")

        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private val COLUMNS = arrayOf("download", "options", "videos", "images", "notifications")
    }
}
