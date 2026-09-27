package br.gov.bomsucesso.threadsdownloader.hook

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Captura apenas URLs diretas de mídia. URLs de páginas e avatares não são
 * confundidas com arquivos MP4. Não lê cookies, contas nem dados pessoais.
 */
class ThreadsHook : IXposedHookLoadPackage {
    companion object {
        private const val THREADS = "com.instagram.barcelona"
        private const val MODULE = "br.gov.bomsucesso.threadsdownloader"
        private const val CAPTURED = "$MODULE.MEDIA_CAPTURED"
        private const val READY = "$MODULE.HOOK_READY"
        private val seen = LinkedHashMap<String, Long>(128, 0.75f, true)
        @Volatile private var context: Context? = null
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS || param.processName != THREADS) return
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    context = (param.thisObject as? Application)?.applicationContext
                    send(READY)
                    XposedBridge.log("ThreadsEnhancer: hook ativo no processo principal")
                }
            }
        )
        hookUrl()
        hookUri()
        hookMediaPlayer()
    }

    private fun hookUrl() {
        runCatching {
            XposedBridge.hookAllConstructors(URL::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    inspect((param.thisObject as? URL)?.toExternalForm())
                }
            })
        }.onFailure { XposedBridge.log("ThreadsEnhancer URL: " + it.javaClass.simpleName) }
    }

    private fun hookUri() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Uri::class.java, "parse", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        inspect(param.args[0] as? String)
                    }
                }
            )
        }.onFailure { XposedBridge.log("ThreadsEnhancer URI: " + it.javaClass.simpleName) }
    }

    private fun hookMediaPlayer() {
        runCatching {
            XposedBridge.hookAllMethods(
                MediaPlayer::class.java, "setDataSource",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args.firstOrNull { it is String || it is Uri }
                            ?.toString()?.let(::inspect)
                    }
                }
            )
        }.onFailure { XposedBridge.log("ThreadsEnhancer Player: " + it.javaClass.simpleName) }
    }

    private fun inspect(raw: String?) {
        if (raw == null || raw.length !in 20..4096) return
        val lower = raw.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") || !(
                lower.contains(".mp4") || lower.contains(".m4v") ||
                lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".webp") || lower.contains(".png"))) return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        val host = uri.host?.lowercase(Locale.ROOT) ?: return
        if (!(host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
                host == "cdninstagram.com" || host.endsWith(".cdninstagram.com"))) return
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        val isVideo = path.endsWith(".mp4") || path.endsWith(".m4v")
        val isImage = path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".webp")
        if (!isVideo && !isImage) return
        // Avatares do feed não devem substituir a mídia que o usuário deseja.
        if (path.contains("t51.2885-19")) return
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if (now - (seen[raw] ?: 0L) < 30_000L) return
            seen[raw] = now
            while (seen.size > 100) seen.remove(seen.keys.first())
        }
        send(CAPTURED, raw)
    }

    private fun send(action: String, url: String? = null) {
        val app = context ?: return
        runCatching {
            val intent = Intent(action)
                .setComponent(ComponentName(MODULE, "$MODULE.CapturedMediaReceiver"))
            if (url != null) intent.putExtra("url", url)
            app.sendBroadcast(intent)
        }.onFailure { XposedBridge.log("ThreadsEnhancer broadcast: " + it.javaClass.simpleName) }
    }
}
