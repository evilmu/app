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
                if (MediaUrl.isTrustedCapture(url)) CapturedUrlStore.save(context, url)
            }
            ACTION_READY -> context.getSharedPreferences("hook_status", Context.MODE_PRIVATE)
                .edit().putLong("ready_at", System.currentTimeMillis()).apply()
        }
    }

    companion object {
        const val ACTION_CAPTURED = "br.gov.bomsucesso.threadsdownloader.fixed.MEDIA_CAPTURED"
        const val ACTION_READY = "br.gov.bomsucesso.threadsdownloader.fixed.HOOK_READY"
    }
}
