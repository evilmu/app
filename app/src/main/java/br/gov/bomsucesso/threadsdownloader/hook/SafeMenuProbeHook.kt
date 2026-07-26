package br.gov.bomsucesso.threadsdownloader.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sonda segura: não desenha interface e não interfere no heartbeat principal.
 * Carrega o DexKit somente após Application.attach e registra os métodos usados
 * quando o menu nativo de publicação é aberto.
 */
class SafeMenuProbeHook : IXposedHookLoadPackage {

    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader"
        private val started = AtomicBoolean(false)
        private val nativeLoaded = AtomicBoolean(false)
        private val hooked = Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())
    }

    private lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE || param.processName != THREADS_PACKAGE) return
        packageParam = param

        XposedBridge.log("ThreadsEnhancer/MenuProbe: entrada carregada")

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(hookParam: MethodHookParam) {
                        val application = hookParam.thisObject as? Application ?: return
                        if (application.packageName != THREADS_PACKAGE) return
                        if (!started.compareAndSet(false, true)) return

                        Thread({ discover(application) }, "threads-native-menu-probe").start()
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/MenuProbe: attach falhou: $it")
        }
    }

    private fun loadDexKit(context: Context): Boolean {
        if (nativeLoaded.get()) return true

        return synchronized(nativeLoaded) {
            if (nativeLoaded.get()) return@synchronized true

            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
                val library = File(appInfo.nativeLibraryDir, "libdexkit.so")
                require(library.isFile) { "libdexkit.so ausente: ${library.absolutePath}" }
                System.load(library.absolutePath)
                nativeLoaded.set(true)
                XposedBridge.log("ThreadsEnhancer/MenuProbe: DexKit carregado")
                true
            }.getOrElse {
                XposedBridge.log("ThreadsEnhancer/MenuProbe: DexKit não carregou: $it")
                false
            }
        }
    }

    private fun discover(context: Context) {
        if (!loadDexKit(context)) return

        val keywords = listOf(
            "Copiar link", "Denunciar", "Salvar", "Não tenho interesse",
            "Copy link", "Report", "Save", "Not interested",
            "Compartilhar", "Share", "Remixar", "Remix"
        )

        runCatching {
            DexKitBridge.create(packageParam.appInfo.sourceDir).use { bridge ->
                keywords.forEach { keyword ->
                    val matches = bridge.findMethod {
                        matcher {
                            usingStrings(keyword)
                        }
                    }

                    XposedBridge.log(
                        "ThreadsEnhancer/MenuProbe: keyword=$keyword count=${matches.size}"
                    )

                    matches.take(50).forEach { methodData ->
                        val method = runCatching {
                            methodData.getMethodInstance(packageParam.classLoader)
                        }.getOrNull() ?: return@forEach

                        installMethodHook(method, keyword)
                    }
                }
            }
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/MenuProbe: descoberta falhou: $it")
            XposedBridge.log(it)
        }
    }

    private fun installMethodHook(method: Method, keyword: String) {
        synchronized(hooked) {
            if (!hooked.add(method)) return
        }

        runCatching {
            XposedBridge.log("ThreadsEnhancer/MenuProbe: candidato ${method.toGenericString()}")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val argTypes = param.args
                        ?.take(12)
                        ?.joinToString(",") { value -> value?.javaClass?.name ?: "null" }
                        .orEmpty()
                    val resultType = param.result?.javaClass?.name ?: "null"

                    XposedBridge.log(
                        "ThreadsEnhancer/MenuProbe HIT: keyword=$keyword " +
                            "method=${method.declaringClass.name}#${method.name} " +
                            "args=[$argTypes] result=$resultType"
                    )
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/MenuProbe: hook falhou ${method.name}: $it")
        }
    }
}
