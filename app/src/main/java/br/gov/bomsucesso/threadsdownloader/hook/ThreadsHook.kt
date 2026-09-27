package br.gov.bomsucesso.threadsdownloader.hook

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Xposed entry point must only modify the official Threads process.
 * No global URL capture or unrelated floating download button.
 */
class ThreadsHook : IXposedHookLoadPackage {
    companion object {
        private const val THREADS = "com.instagram.barcelona"
        private const val MODULE = "br.gov.bomsucesso.threadsdownloader.inlinepost"
        private val installed = AtomicBoolean(false)
    }

    override fun handleLoadPackage(load: XC_LoadPackage.LoadPackageParam) {
        if (load.packageName != THREADS || load.processName != THREADS) return
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!installed.compareAndSet(false, true)) return
                    val host = param.args[0] as? Context ?: return
                    val version = runCatching {
                        host.packageManager.getPackageInfo(THREADS, 0).versionName
                    }.getOrNull() ?: "desconhecida"
                    XposedBridge.log("ThreadsInline: Threads versão $version; registro dos hooks por post")
                    runCatching {
                        NativeInlineHook.install(load.classLoader)
                        host.sendBroadcast(
                            Intent("$MODULE.HOOK_READY")
                                .setComponent(ComponentName(MODULE,
                                    "br.gov.bomsucesso.threadsdownloader.CapturedMediaReceiver"))
                        )
                    }.onFailure {
                        installed.set(false)
                        XposedBridge.log("ThreadsInline: erro ao ativar: ${it.javaClass.simpleName}")
                    }
                }
            }
        )
    }
}
