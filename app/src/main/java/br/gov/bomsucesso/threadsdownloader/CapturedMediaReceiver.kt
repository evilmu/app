package br.gov.bomsucesso.threadsdownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Heartbeat only; media is downloaded directly from the selected Threads post. */
class CapturedMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_READY) return
        context.getSharedPreferences("hook_status", Context.MODE_PRIVATE)
            .edit().putLong("ready_at", System.currentTimeMillis()).apply()
    }

    companion object {
        const val ACTION_READY = "br.gov.bomsucesso.threadsdownloader.inlinepost.HOOK_READY"
    }
}
