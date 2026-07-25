package br.gov.bomsucesso.threadsdownloader.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.Application
import android.app.Dialog
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.net.URL
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hook legado compatível com ReLSPosed.
 *
 * Estratégia:
 * 1. Confirma o carregamento pelo Application.attach.
 * 2. Captura URLs de mídia que o Threads realmente carregou.
 * 3. Detecta menus por estrutura, posição e textos, sem depender de classes ofuscadas.
 * 4. Usa ContentProvider.call como IPC primário e broadcast explícito como fallback.
 */
class ThreadsHook : IXposedHookLoadPackage {

    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader"
        private const val RECEIVER_CLASS = "$MODULE_PACKAGE.CapturedMediaReceiver"
        private const val ACTION_CAPTURED = "$MODULE_PACKAGE.MEDIA_CAPTURED"
        private const val ACTION_HOOK_ACTIVE = "$MODULE_PACKAGE.HOOK_ACTIVE"
        private const val SETTINGS_URI = "content://$MODULE_PACKAGE.settings/current"
        private const val METHOD_HEARTBEAT = "heartbeat"
        private const val METHOD_CAPTURED = "captured"
        private const val METHOD_EVENT = "event"
        private const val HOOK_VERSION = "3.3.0-relsposed"
        private const val INJECTED_TAG = "threads_enhancer_menu_330"
        private const val MAX_URLS = 32

        private val mediaUrls = ArrayDeque<String>()
        private val hookedDialogClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

        private val MENU_HINTS = setOf(
            "salvar",
            "ocultar",
            "denunciar",
            "copiar link",
            "compartilhar",
            "deixar de seguir",
            "silenciar",
            "restringir",
            "nao tenho interesse",
            "sobre esta conta",
            "gerenciar",
            "remixar",
            "save",
            "hide",
            "report",
            "copy link",
            "share",
            "unfollow",
            "mute",
            "restrict",
            "not interested",
            "about this account",
            "manage",
            "remix",
            "guardar",
            "copiar enlace",
            "compartir",
            "dejar de seguir"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity = WeakReference<Activity>(null)
    private var processName: String = THREADS_PACKAGE
    private var uiProcess = false
    private var lastIpcErrorLogAt = 0L

    private val debouncedScan = Runnable {
        currentActivity.get()?.let { activity ->
            runCatching { scanActivityForMenus(activity) }
                .onFailure { reportHookError(activity, "debounced_scan", it) }
        }
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE) return

        processName = param.processName
        uiProcess = param.processName == THREADS_PACKAGE

        XposedBridge.log("ThreadsEnhancer/ReLSPosed: pacote carregado em ${param.processName}")

        hookApplicationAttach()
        hookJavaNetUrl()
        hookAndroidUri()

        if (uiProcess) {
            hookDialogs(param.classLoader)
            hookDialogFragments(param.classLoader)
            hookViewMutations()
            hookMenuTextSignals()
        }
    }

    private fun hookApplicationAttach() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val application = param.thisObject as? Application ?: return
                        if (application.packageName != THREADS_PACKAGE) return

                        XposedBridge.log("ThreadsEnhancer/ReLSPosed: Application.attach confirmado")
                        sendHeartbeat(application)

                        if (uiProcess) {
                            registerLifecycleCallbacks(application)
                        }
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed attach hook: ${it.message}")
        }
    }

    private fun registerLifecycleCallbacks(application: Application) {
        runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    currentActivity = WeakReference(activity)
                    sendHeartbeat(application)

                    // O menu pode ser criado por animação ou renderização assíncrona.
                    listOf(80L, 250L, 600L, 1_200L).forEach { delay ->
                        mainHandler.postDelayed({
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                runCatching { scanActivityForMenus(activity) }
                                    .onFailure { reportHookError(activity, "resume_scan", it) }
                            }
                        }, delay)
                    }
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivity.get() === activity) currentActivity.clear()
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed lifecycle: ${it.message}")
        }
    }

    /**
     * Menus customizados do Threads nem sempre são Dialog/BottomSheetDialog.
     * Quando uma nova View entra na árvore, fazemos uma varredura curta e debounced.
     */
    private fun hookViewMutations() {
        runCatching {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args.firstOrNull { it is View } as? View
                    if (child?.tag == INJECTED_TAG) return
                    scheduleDebouncedScan(110L)
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed ViewGroup hook: ${it.message}")
        }
    }

    /**
     * Um texto conhecido do menu é um sinal forte de que a action sheet foi renderizada.
     */
    private fun hookMenuTextSignals() {
        runCatching {
            XposedBridge.hookAllMethods(TextView::class.java, "setText", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val value = param.args.firstOrNull() as? CharSequence ?: return
                    if (isMenuHint(normalize(value.toString()))) {
                        scheduleDebouncedScan(70L)
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed TextView hook: ${it.message}")
        }
    }

    private fun scheduleDebouncedScan(delay: Long) {
        if (!uiProcess || currentActivity.get() == null) return
        mainHandler.removeCallbacks(debouncedScan)
        mainHandler.postDelayed(debouncedScan, delay)
    }

    private fun hookJavaNetUrl() {
        runCatching {
            XposedBridge.hookAllConstructors(URL::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    rememberMedia(param.thisObject?.toString())
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed URL hook: ${it.message}")
        }
    }

    private fun hookAndroidUri() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Uri::class.java,
                "parse",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberMedia(param.args.firstOrNull() as? String)
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed Uri hook: ${it.message}")
        }
    }

    private fun hookDialogs(classLoader: ClassLoader) {
        hookDialogClass(Dialog::class.java)

        listOf(
            "androidx.appcompat.app.AppCompatDialog",
            "com.google.android.material.bottomsheet.BottomSheetDialog"
        ).forEach { className ->
            XposedHelpers.findClassIfExists(className, classLoader)?.let(::hookDialogClass)
        }
    }

    private fun hookDialogClass(dialogClass: Class<*>) {
        synchronized(hookedDialogClasses) {
            if (!hookedDialogClasses.add(dialogClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(dialogClass, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    dialog.window?.decorView?.postDelayed({
                        val root = dialog.window?.decorView as? ViewGroup ?: return@postDelayed
                        runCatching {
                            injectFromRoot(root, "dialog") { dialog.dismiss() }
                        }.onFailure {
                            reportHookError(root.context, "dialog_injection", it)
                        }
                    }, 140L)
                }
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed dialog ${dialogClass.name}: ${it.message}")
        }
    }

    private fun hookDialogFragments(classLoader: ClassLoader) {
        listOf(
            "androidx.fragment.app.DialogFragment",
            "com.google.android.material.bottomsheet.BottomSheetDialogFragment"
        ).forEach { className ->
            val fragmentClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(fragmentClass, "onStart", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dialog = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getDialog") as? Dialog
                        }.getOrNull() ?: return

                        dialog.window?.decorView?.postDelayed({
                            val root = dialog.window?.decorView as? ViewGroup ?: return@postDelayed
                            runCatching {
                                injectFromRoot(root, "dialog_fragment") { dialog.dismiss() }
                            }.onFailure {
                                reportHookError(root.context, "fragment_injection", it)
                            }
                        }, 140L)
                    }
                })
            }.onFailure {
                XposedBridge.log("ThreadsEnhancer/ReLSPosed fragment $className: ${it.message}")
            }
        }
    }

    private fun scanActivityForMenus(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        injectFromRoot(root, "activity_tree", null)
    }

    private fun injectFromRoot(
        root: ViewGroup,
        source: String,
        dismiss: (() -> Unit)?
    ): Boolean {
        if (findTaggedView(root) != null) return false

        val candidate = findBestMenuCandidate(root) ?: return false
        reportEvent(
            candidate.group.context,
            "menu_candidate",
            "$source score=${candidate.score} texts=${candidate.texts.take(8).joinToString("|")}"
        )

        val settings = readModuleSettings(candidate.group.context)
        if (!settings.download && !settings.options) return false

        val section = createDownloadSection(candidate.group.context, settings, dismiss)
        var target = findMenuListTarget(candidate.group)

        if (isAdapterManaged(target)) {
            target = (target.parent as? ViewGroup) ?: candidate.group
        }

        val inserted = addSectionSafely(target, section) ||
            (target !== candidate.group && addSectionSafely(candidate.group, section))

        if (inserted) {
            XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed: opções injetadas ($source, score=${candidate.score})"
            )
            reportEvent(candidate.group.context, "menu_injected", source)
        } else {
            reportEvent(candidate.group.context, "hook_error", "Falha ao inserir no container: $source")
        }

        return inserted
    }

    private fun createDownloadSection(
        context: Context,
        settings: ModuleSettings,
        dismiss: (() -> Unit)?
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = INJECTED_TAG
            setPadding(0, dp(context, 4), 0, dp(context, 4))

            addView(View(context).apply {
                setBackgroundColor(Color.argb(42, 128, 128, 128))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)))

            if (settings.download) {
                addView(createMenuRow(context, "Baixar", android.R.drawable.stat_sys_download) {
                    val url = synchronized(mediaUrls) {
                        mediaUrls.toList().asReversed().firstOrNull { isAllowed(it, settings) }
                    }
                    if (url == null) {
                        Toast.makeText(context, "Abra a mídia antes de baixar", Toast.LENGTH_LONG).show()
                    } else {
                        enqueueDownload(context, url, settings.notifications)
                        dismiss?.invoke()
                    }
                })
            }

            if (settings.options) {
                addView(createMenuRow(context, "Opções de download", android.R.drawable.ic_menu_manage) {
                    showDownloadOptions(context, settings, dismiss)
                })
            }

            addView(View(context).apply {
                setBackgroundColor(Color.argb(42, 128, 128, 128))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)))
        }
    }

    private fun addSectionSafely(target: ViewGroup, section: View): Boolean {
        if (section.parent != null || findTaggedView(target) != null || isAdapterManaged(target)) return false

        return runCatching {
            val firstChild = target.getChildAt(0)
            val index = if (firstChild != null && firstChild.height in 1..dp(target.context, 36)) 1 else 0
            val safeIndex = index.coerceAtMost(target.childCount)

            val params = if (target is LinearLayout) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            target.addView(section, safeIndex, params)
            true
        }.getOrElse {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed addView: ${it.message}")
            false
        }
    }

    private fun findBestMenuCandidate(root: ViewGroup): MenuCandidate? {
        val metrics = root.resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        var best: MenuCandidate? = null
        var visited = 0

        fun visit(group: ViewGroup, depth: Int) {
            if (visited++ > 1_800 || depth > 14 || !group.isShown) return

            if (group !== root && group.width > 0 && group.height > 0 && group.childCount >= 2) {
                val location = IntArray(2)
                runCatching { group.getLocationOnScreen(location) }
                val top = location[1]
                val bottom = top + group.height
                val widthRatio = group.width.toFloat() / screenWidth
                val heightRatio = group.height.toFloat() / screenHeight
                val bottomAligned = bottom >= (screenHeight * 0.78f)

                if (bottomAligned && widthRatio >= 0.62f && group.height >= dp(group.context, 110)) {
                    val texts = collectVisibleTexts(group, 36)
                    val normalized = texts.map(::normalize).filter { it.isNotBlank() }
                    val hintMatches = normalized.count(::isMenuHint)
                    val shortTexts = normalized.count { it.length in 2..48 }
                    val directRows = countRowLikeChildren(group)
                    val name = group.javaClass.name.lowercase()
                    val tag = group.tag?.toString()?.lowercase().orEmpty()
                    val semanticClass = listOf("bottom", "sheet", "dialog", "modal", "menu", "action")
                        .count { name.contains(it) || tag.contains(it) }

                    val lowerScreenBonus = if (top >= screenHeight * 0.18f) 3 else 0
                    val compactBonus = if (heightRatio in 0.16f..0.88f) 2 else 0
                    val score = hintMatches * 7 + directRows * 2 + semanticClass * 3 +
                        lowerScreenBonus + compactBonus + shortTexts.coerceAtMost(8)

                    val qualifies = hintMatches >= 2 ||
                        (hintMatches >= 1 && directRows >= 2 && shortTexts >= 3) ||
                        (semanticClass >= 1 && directRows >= 3 && shortTexts >= 3)

                    if (qualifies && (best == null || score > best!!.score)) {
                        best = MenuCandidate(group, score, texts)
                    }
                }
            }

            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is ViewGroup) visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return best
    }

    private fun findMenuListTarget(candidate: ViewGroup): ViewGroup {
        var best: ViewGroup = candidate
        var bestScore = Int.MIN_VALUE
        var visited = 0

        fun visit(group: ViewGroup, depth: Int) {
            if (visited++ > 800 || depth > 10 || !group.isShown) return

            val texts = collectVisibleTexts(group, 24).map(::normalize)
            val hints = texts.count(::isMenuHint)
            val rows = countRowLikeChildren(group)
            val verticalBonus = if (group is LinearLayout && group.orientation == LinearLayout.VERTICAL) 4 else 0
            val childBonus = if (group.childCount in 2..16) 2 else 0
            val adapterPenalty = if (isAdapterManaged(group)) 20 else 0
            val score = hints * 5 + rows * 4 + verticalBonus + childBonus - adapterPenalty

            if (score > bestScore) {
                bestScore = score
                best = group
            }

            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is ViewGroup) visit(child, depth + 1)
            }
        }

        visit(candidate, 0)
        return best
    }

    private fun countRowLikeChildren(group: ViewGroup): Int {
        var count = 0
        val minHeight = dp(group.context, 34)
        val maxHeight = dp(group.context, 120)

        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (!child.isShown || child.height !in minHeight..maxHeight) continue
            val texts = collectVisibleTexts(child, 4).map(::normalize).filter { it.length in 1..64 }
            if (texts.size in 1..3) count++
        }
        return count
    }

    private fun collectVisibleTexts(root: View, limit: Int): List<String> {
        val result = ArrayList<String>(limit)

        fun visit(view: View, depth: Int) {
            if (result.size >= limit || depth > 10 || !view.isShown) return
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) result.add(text)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                    if (result.size >= limit) return
                }
            }
        }

        visit(root, 0)
        return result
    }

    private fun isMenuHint(value: String): Boolean {
        if (value.isBlank()) return false
        return MENU_HINTS.any { hint ->
            value == hint || value.startsWith("$hint ") || value.contains(hint)
        }
    }

    private fun normalize(value: String): String {
        val withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isAdapterManaged(group: ViewGroup): Boolean {
        val name = group.javaClass.name.lowercase()
        return name.contains("recyclerview") ||
            name.contains("listview") ||
            name.contains("adapterview") ||
            name.contains("viewpager")
    }

    private fun createMenuRow(
        context: Context,
        title: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 58)
            setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
            setBackgroundResource(android.R.drawable.list_selector_background)

            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(resolveTextColor(context))
            }, LinearLayout.LayoutParams(dp(context, 27), dp(context, 27)).apply {
                marginEnd = dp(context, 20)
            })

            addView(TextView(context).apply {
                text = title
                textSize = 17.5f
                setTextColor(resolveTextColor(context))
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(context, 54), 1f))

            setOnClickListener { onClick() }
        }
    }

    private fun showDownloadOptions(
        context: Context,
        settings: ModuleSettings,
        dismiss: (() -> Unit)?
    ) {
        val urls = synchronized(mediaUrls) {
            mediaUrls.toList().asReversed().filter { isAllowed(it, settings) }.distinct()
        }
        if (urls.isEmpty()) {
            Toast.makeText(
                context,
                "Nenhuma mídia detectada. Abra o vídeo ou imagem primeiro.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val limited = urls.take(12)
        val labels = limited.mapIndexed { index, url ->
            val type = if (isVideo(url)) "Vídeo" else "Imagem"
            "$type ${index + 1}"
        }.toMutableList()
        if (limited.size > 1) labels.add(0, "Baixar todas (${limited.size})")

        AlertDialog.Builder(context)
            .setTitle("Opções de download")
            .setItems(labels.toTypedArray()) { _, which ->
                if (limited.size > 1 && which == 0) {
                    limited.reversed().forEach {
                        enqueueDownload(context, it, settings.notifications)
                    }
                } else {
                    val index = if (limited.size > 1) which - 1 else which
                    limited.getOrNull(index)?.let {
                        enqueueDownload(context, it, settings.notifications)
                    }
                }
                dismiss?.invoke()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enqueueDownload(context: Context, url: String, notifications: Boolean) {
        runCatching {
            val video = isVideo(url)
            val extension = if (video) ".mp4" else imageExtension(url)
            val fileName = "threads_${System.currentTimeMillis()}$extension"
            val mime = if (video) "video/mp4" else imageMime(extension)

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(if (video) "Vídeo do Threads" else "Imagem do Threads")
                .setDescription("Baixando mídia")
                .setMimeType(mime)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                    if (notifications) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    else DownloadManager.Request.VISIBILITY_HIDDEN
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Threads/$fileName"
                )

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "Download iniciado", Toast.LENGTH_SHORT).show()
        }.onFailure {
            reportHookError(context, "download", it)
            Toast.makeText(context, "Não foi possível baixar esta mídia", Toast.LENGTH_LONG).show()
        }
    }

    private fun rememberMedia(candidate: String?) {
        val url = candidate?.trim() ?: return
        if (!isLikelyMedia(url)) return

        synchronized(mediaUrls) {
            mediaUrls.remove(url)
            mediaUrls.addLast(url)
            while (mediaUrls.size > MAX_URLS) mediaUrls.removeFirst()
        }

        val context = AndroidAppHelper.currentApplication()?.applicationContext
        if (context != null) sendCaptured(context, url)
        XposedBridge.log("ThreadsEnhancer/ReLSPosed mídia: ${sanitizeForLog(url)}")
    }

    private fun sendHeartbeat(context: Context) {
        val extras = Bundle().apply {
            putString("framework", "ReLSPosed")
            putString("process", processName)
            putString("hook_version", HOOK_VERSION)
        }

        if (!callProvider(context, METHOD_HEARTBEAT, extras)) {
            sendFallbackBroadcast(context, ACTION_HOOK_ACTIVE, null)
        }
    }

    private fun sendCaptured(context: Context, url: String) {
        val extras = Bundle().apply {
            putString("framework", "ReLSPosed")
            putString("process", processName)
            putString("hook_version", HOOK_VERSION)
            putString("media_type", if (isVideo(url)) "video" else "image")
            putString("url", url)
        }

        if (!callProvider(context, METHOD_CAPTURED, extras)) {
            sendFallbackBroadcast(context, ACTION_CAPTURED, url)
        }
    }

    private fun reportEvent(context: Context, event: String, details: String) {
        val extras = Bundle().apply {
            putString("event", event)
            putString("details", details.take(500))
        }
        callProvider(context, METHOD_EVENT, extras)
    }

    private fun reportHookError(context: Context, stage: String, error: Throwable) {
        val details = "$stage: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        XposedBridge.log("ThreadsEnhancer/ReLSPosed $details")
        reportEvent(context, "hook_error", details)
    }

    private fun callProvider(context: Context, method: String, extras: Bundle): Boolean {
        return runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), method, null, extras)
                ?.getBoolean("ok", false) == true
        }.getOrElse {
            val now = System.currentTimeMillis()
            if (now - lastIpcErrorLogAt > 10_000L) {
                lastIpcErrorLogAt = now
                XposedBridge.log("ThreadsEnhancer/ReLSPosed IPC $method: ${it.message}")
            }
            false
        }
    }

    private fun sendFallbackBroadcast(context: Context, action: String, url: String?) {
        runCatching {
            val intent = Intent(action).apply {
                component = ComponentName(MODULE_PACKAGE, RECEIVER_CLASS)
                if (url != null) putExtra("url", url)
            }
            context.sendBroadcast(intent)
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed broadcast fallback: ${it.message}")
        }
    }

    private fun readModuleSettings(context: Context): ModuleSettings {
        return runCatching {
            context.contentResolver.query(
                Uri.parse(SETTINGS_URI),
                null,
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ModuleSettings(
                    download = cursor.getInt(cursor.getColumnIndexOrThrow("download")) == 1,
                    options = cursor.getInt(cursor.getColumnIndexOrThrow("options")) == 1,
                    videos = cursor.getInt(cursor.getColumnIndexOrThrow("videos")) == 1,
                    images = cursor.getInt(cursor.getColumnIndexOrThrow("images")) == 1,
                    notifications = cursor.getInt(cursor.getColumnIndexOrThrow("notifications")) == 1
                )
            }
        }.getOrNull() ?: ModuleSettings()
    }

    private fun isAllowed(url: String, settings: ModuleSettings): Boolean =
        if (isVideo(url)) settings.videos else settings.images

    private fun isLikelyMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("https://")) return false

        val hostMatches = lower.contains("fbcdn.net") ||
            lower.contains("cdninstagram.com") ||
            lower.contains("instagram.com")
        val mediaMatches = lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".webp") ||
            lower.contains("video") ||
            lower.contains("image")
        return hostMatches && mediaMatches
    }

    private fun isVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".m4v") || lower.contains("video")
    }

    private fun imageExtension(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".webp") -> ".webp"
            clean.endsWith(".png") -> ".png"
            else -> ".jpg"
        }
    }

    private fun imageMime(extension: String): String = when (extension) {
        ".webp" -> "image/webp"
        ".png" -> "image/png"
        else -> "image/jpeg"
    }

    private fun findTaggedView(root: View): View? {
        if (root.tag == INJECTED_TAG) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTaggedView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun resolveTextColor(context: Context): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else {
            Color.WHITE
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun sanitizeForLog(url: String): String =
        url.substringBefore('?') + if ('?' in url) "?…" else ""

    private data class MenuCandidate(
        val group: ViewGroup,
        val score: Int,
        val texts: List<String>
    )

    private data class ModuleSettings(
        val download: Boolean = true,
        val options: Boolean = true,
        val videos: Boolean = true,
        val images: Boolean = true,
        val notifications: Boolean = true
    )
}
