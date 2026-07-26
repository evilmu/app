package br.gov.bomsucesso.threadsdownloader.hook

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sonda temporária para localizar o método ofuscado que constrói o menu nativo
 * de publicação do Threads 439.x. Não desenha overlay e não altera o menu.
 */
class NativeMenuProbeHook : IXposedHookLoadPackage {

    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader"
        private const val PROVIDER_URI = "content://$MODULE_PACKAGE.settings/current"
        private const val RECEIVER_CLASS = "$MODULE_PACKAGE.CapturedMediaReceiver"
        private const val HOOK_VERSION = "3.4.1-native-menu-probe"
        private val started = AtomicBoolean(false)
        private val hookedMethods = Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())

        init {
            System.loadLibrary("dexkit")
        }
    }

    private lateinit var loadParam: XC_LoadPackage.LoadPackageParam

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE) return
        loadParam = param

        XposedBridge.log("ThreadsEnhancer/Probe: pacote carregado em ${param.processName}")

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hookParam: MethodHookParam) {
                    val application = hookParam.thisObject as? Application ?: return
                    if (application.packageName != THREADS_PACKAGE) return

                    sendHeartbeat(application, param.processName)
                    if (param.processName == THREADS_PACKAGE && started.compareAndSet(false, true)) {
                        installViewDiagnostics()
                        Thread({ discoverMenuMethods(application) }, "threads-menu-probe").start()
                    }
                }
            }
        )
    }

    private fun discoverMenuMethods(context: Context) {
        val keywords = listOf(
            "Copiar link", "Denunciar", "Salvar", "Não tenho interesse",
            "Copy link", "Report", "Save", "Not interested",
            "Compartilhar", "Share", "Remixar", "Remix"
        )

        try {
            DexKitBridge.create(loadParam.appInfo.sourceDir).use { bridge ->
                keywords.forEach { keyword ->
                    val matches = runCatching {
                        bridge.findMethod {
                            matcher {
                                usingStrings(keyword)
                            }
                        }
                    }.getOrElse {
                        XposedBridge.log("ThreadsEnhancer/Probe: busca '$keyword' falhou: $it")
                        emptyList()
                    }

                    XposedBridge.log(
                        "ThreadsEnhancer/Probe: keyword='$keyword' métodos=${matches.size}"
                    )

                    matches.take(40).forEach { data ->
                        val method = runCatching {
                            data.getMethodInstance(loadParam.classLoader)
                        }.getOrNull() ?: return@forEach

                        XposedBridge.log("ThreadsEnhancer/Probe: candidato ${method.toGenericString()}")
                        hookCandidate(method, keyword)
                    }
                }
            }
        } catch (error: Throwable) {
            XposedBridge.log("ThreadsEnhancer/Probe: DexKit fatal: $error")
            XposedBridge.log(error)
        }
    }

    private fun hookCandidate(method: Method, keyword: String) {
        synchronized(hookedMethods) {
            if (!hookedMethods.add(method)) return
        }

        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val summary = buildString {
                        append("keyword=").append(keyword)
                        append(" method=").append(method.declaringClass.name)
                            .append('#').append(method.name)
                        append(" this=").append(param.thisObject?.javaClass?.name ?: "static")
                        append(" args=").append(summarizeArray(param.args))
                        append(" result=").append(summarize(param.result, 0, newVisited()))
                    }
                    XposedBridge.log("ThreadsEnhancer/Probe HIT: ${summary.take(3500)}")
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/Probe: hook candidato falhou $method: $it")
        }
    }

    private fun installViewDiagnostics() {
        runCatching {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args.firstOrNull { it is View } as? View ?: return
                    val parent = param.thisObject as? ViewGroup ?: return
                    val parentName = parent.javaClass.name.lowercase(Locale.ROOT)
                    val childName = child.javaClass.name.lowercase(Locale.ROOT)
                    if (
                        parentName.contains("compose") || parentName.contains("litho") ||
                        parentName.contains("bottom") || parentName.contains("sheet") ||
                        childName.contains("compose") || childName.contains("litho") ||
                        childName.contains("bottom") || childName.contains("sheet")
                    ) {
                        XposedBridge.log(
                            "ThreadsEnhancer/Probe VIEW: parent=${parent.javaClass.name} " +
                                "child=${child.javaClass.name} count=${parent.childCount}"
                        )
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/Probe: ViewGroup.addView: $it")
        }
    }

    private fun summarizeArray(values: kotlin.Array<Any?>?): String {
        if (values == null) return "[]"
        return values.take(8).joinToString(prefix = "[", postfix = "]") {
            summarize(it, 0, newVisited()).take(500)
        }
    }

    private fun newVisited(): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())

    private fun summarize(value: Any?, depth: Int, visited: MutableSet<Any>): String {
        if (value == null) return "null"
        if (depth > 2) return value.javaClass.name
        if (value is String || value is CharSequence || value is Number || value is Boolean || value is Enum<*>) {
            return "${value.javaClass.simpleName}(${value.toString().take(260)})"
        }
        if (!visited.add(value)) return "<cycle:${value.javaClass.simpleName}>"

        if (value is Iterable<*>) {
            return value.take(8).joinToString(
                prefix = "${value.javaClass.name}[",
                postfix = "]"
            ) { summarize(it, depth + 1, visited) }
        }

        if (value.javaClass.isArray) {
            val size = Array.getLength(value)
            val parts = (0 until minOf(size, 8)).map {
                summarize(Array.get(value, it), depth + 1, visited)
            }
            return "${value.javaClass.name}${parts}"
        }

        val fields = allFields(value.javaClass)
            .filterNot { Modifier.isStatic(it.modifiers) }
            .take(14)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    val fieldValue = field.get(value)
                    val interesting = fieldValue is CharSequence ||
                        fieldValue is Uri ||
                        fieldValue is Iterable<*> ||
                        (fieldValue != null && fieldValue.javaClass.name.contains("Function")) ||
                        (fieldValue != null && fieldValue.javaClass.name.contains("Lambda"))
                    if (interesting) {
                        "${field.name}=${summarize(fieldValue, depth + 1, visited)}"
                    } else null
                }.getOrNull()
            }

        return if (fields.isEmpty()) {
            value.javaClass.name
        } else {
            "${value.javaClass.name}{${fields.joinToString()}}"
        }
    }

    private fun allFields(type: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java && result.size < 40) {
            result += current.declaredFields
            current = current.superclass
        }
        return result
    }

    private fun sendHeartbeat(context: Context, processName: String) {
        val extras = Bundle().apply {
            putString("framework", "ReLSPosed")
            putString("process", processName)
            putString("hook_version", HOOK_VERSION)
        }

        val accepted = runCatching {
            context.contentResolver.call(Uri.parse(PROVIDER_URI), "heartbeat", null, extras)
                ?.getBoolean("ok", false) == true
        }.getOrDefault(false)

        if (!accepted) {
            runCatching {
                context.sendBroadcast(Intent("$MODULE_PACKAGE.HOOK_ACTIVE").apply {
                    component = ComponentName(MODULE_PACKAGE, RECEIVER_CLASS)
                })
            }
        }

        XposedBridge.log("ThreadsEnhancer/Probe: heartbeat provider=$accepted")
    }
}
