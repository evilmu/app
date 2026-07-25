package br.gov.bomsucesso.threadsdownloader.hook

import android.app.AndroidAppHelper
import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ThreadsHook : IXposedHookLoadPackage {
    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader"
        private const val ACTION_CAPTURED = "$MODULE_PACKAGE.MEDIA_CAPTURED"
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE) return
        XposedBridge.log("ThreadsDownloader: Threads carregado em ${param.processName}")
        hookJavaNetUrl()
        hookAndroidUri()
    }

    private fun hookJavaNetUrl() {
        runCatching {
            XposedBridge.hookAllConstructors(java.net.URL::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { inspect(param.thisObject?.toString()) }
            })
        }.onFailure { XposedBridge.log("ThreadsDownloader URL hook: ${it.message}") }
    }

    private fun hookAndroidUri() {
        runCatching {
            XposedHelpers.findAndHookMethod(Uri::class.java, "parse", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { inspect(param.args.firstOrNull() as? String) }
            })
        }.onFailure { XposedBridge.log("ThreadsDownloader Uri hook: ${it.message}") }
    }

    private fun inspect(candidate: String?) {
        val url = candidate ?: return
        if (!isLikelyVideo(url)) return
        XposedBridge.log("ThreadsDownloader mídia: ${sanitizeForLog(url)}")
        val context = AndroidAppHelper.currentApplication()?.applicationContext ?: return
        runCatching { context.sendBroadcast(Intent(ACTION_CAPTURED).setPackage(MODULE_PACKAGE).putExtra("url", url)) }
    }

    private fun isLikelyVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") && (lower.contains(".mp4") || lower.contains(".m4v") || lower.contains("video") || lower.contains("fbcdn.net") || lower.contains("cdninstagram.com"))
    }

    private fun sanitizeForLog(url: String): String = url.substringBefore('?') + if ('?' in url) "?…" else ""
}
