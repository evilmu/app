package br.gov.bomsucesso.threadsdownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore

class CapturedMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CAPTURED -> {
                val url = intent.getStringExtra("url") ?: return
                if (!url.startsWith("https://")) return
                CapturedUrlStore.save(context, url)
                markHookActive(context)
            }
            ACTION_HOOK_ACTIVE -> markHookActive(context)
        }
    }

    private fun markHookActive(context: Context) {
        context.getSharedPreferences("runtime_status", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_hook_active", System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val ACTION_CAPTURED = "br.gov.bomsucesso.threadsdownloader.MEDIA_CAPTURED"
        const val ACTION_HOOK_ACTIVE = "br.gov.bomsucesso.threadsdownloader.HOOK_ACTIVE"
    }
}
