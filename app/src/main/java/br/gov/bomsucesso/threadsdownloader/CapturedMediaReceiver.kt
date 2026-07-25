package br.gov.bomsucesso.threadsdownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore

class CapturedMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURED) return
        val url = intent.getStringExtra("url") ?: return
        if (!url.startsWith("https://")) return
        CapturedUrlStore.save(context, url)
    }

    companion object {
        const val ACTION_CAPTURED = "br.gov.bomsucesso.threadsdownloader.MEDIA_CAPTURED"
    }
}
