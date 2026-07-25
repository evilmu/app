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
import java.net.URL
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

class ThreadsHook : IXposedHookLoadPackage {

    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader"
        private const val RECEIVER_CLASS = "$MODULE_PACKAGE.CapturedMediaReceiver"
        private const val ACTION_CAPTURED = "$MODULE_PACKAGE.MEDIA_CAPTURED"
        private const val ACTION_HOOK_ACTIVE = "$MODULE_PACKAGE.HOOK_ACTIVE"
        private const val SETTINGS_URI = "content://$MODULE_PACKAGE.settings/current"
        private const val INJECTED_TAG = "threads_enhancer_menu_relsposed_310"
        private const val MAX_URLS = 24

        private val mediaUrls = ArrayDeque<String>()
        private val hookedDialogClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE) return

        XposedBridge.log("ThreadsEnhancer/ReLSPosed: pacote carregado em ${param.processName}")

        hookApplicationAttach()
        hookJavaNetUrl()
        hookAndroidUri()
        hookDialogs(param.classLoader)
        hookDialogFragments(param.classLoader)
    }

    /**
     * No caminho legado do ReLSPosed, Application.attach é um ponto mais confiável
     * que Application.onCreate: o Context já existe e o hook ainda ocorre cedo.
     */
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
                        registerLifecycleHeartbeat(application)
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed attach hook: ${it.message}")
        }
    }

    private fun registerLifecycleHeartbeat(application: Application) {
        runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    sendHeartbeat(application)
                    activity.window?.decorView?.postDelayed({
                        scanActivityForBottomSheets(activity)
                    }, 250)
                }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed lifecycle: ${it.message}")
        }
    }

    private fun sendHeartbeat(context: Context) {
        sendModuleBroadcast(context, ACTION_HOOK_ACTIVE)
        Handler(Looper.getMainLooper()).postDelayed({
            sendModuleBroadcast(context, ACTION_HOOK_ACTIVE)
        }, 1200)
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
                        runCatching { injectMenu(dialog) }
                            .onFailure { XposedBridge.log("ThreadsEnhancer/ReLSPosed menu: ${it.message}") }
                    }, 120)
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
                            runCatching { injectMenu(dialog) }
                                .onFailure { XposedBridge.log("ThreadsEnhancer/ReLSPosed fragment menu: ${it.message}") }
                        }, 120)
                    }
                })
            }.onFailure {
                XposedBridge.log("ThreadsEnhancer/ReLSPosed fragment $className: ${it.message}")
            }
        }
    }

    private fun scanActivityForBottomSheets(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        scanViewTree(root)
    }

    private fun scanViewTree(view: View) {
        if (view is ViewGroup) {
            if (looksLikeVisibleSheet(view) && findTaggedView(view) == null) {
                injectIntoContainer(view)
            }
            for (index in 0 until view.childCount) {
                scanViewTree(view.getChildAt(index))
            }
        }
    }

    private fun looksLikeVisibleSheet(group: ViewGroup): Boolean {
        if (!group.isShown || group.height <= 0 || group.childCount < 3) return false
        val name = group.javaClass.name.lowercase()
        val tagText = group.tag?.toString()?.lowercase().orEmpty()
        return name.contains("bottomsheet") ||
            name.contains("bottom_sheet") ||
            tagText.contains("bottomsheet") ||
            tagText.contains("bottom_sheet")
    }

    private fun injectMenu(dialog: Dialog) {
        if (!looksLikeBottomSheet(dialog)) return
        val content = dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        injectIntoContainer(content) { dialog.dismiss() }
    }

    private fun injectIntoContainer(root: ViewGroup, dismiss: (() -> Unit)? = null) {
        if (findTaggedView(root) != null) return

        val target = findBestVerticalContainer(root) ?: root
        val context = target.context
        val moduleSettings = readModuleSettings(context)
        if (!moduleSettings.download && !moduleSettings.options) return

        sendModuleBroadcast(context, ACTION_HOOK_ACTIVE)

        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = INJECTED_TAG
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }

        if (moduleSettings.download) {
            section.addView(createMenuRow(context, "Baixar", android.R.drawable.stat_sys_download) {
                val url = synchronized(mediaUrls) {
                    mediaUrls.toList().asReversed().firstOrNull { isAllowed(it, moduleSettings) }
                }
                if (url == null) {
                    Toast.makeText(context, "Abra a mídia antes de baixar", Toast.LENGTH_LONG).show()
                } else {
                    enqueueDownload(context, url, moduleSettings.notifications)
                    dismiss?.invoke()
                }
            })
        }

        if (moduleSettings.options) {
            section.addView(createMenuRow(context, "Opções de download", android.R.drawable.ic_menu_manage) {
                showDownloadOptions(context, moduleSettings, dismiss)
            })
        }

        val index = if (target.childCount > 1) 1 else target.childCount
        target.addView(
            section,
            index,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        XposedBridge.log("ThreadsEnhancer/ReLSPosed: opções injetadas no menu")
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
            setPadding(dp(context, 20), dp(context, 11), dp(context, 20), dp(context, 11))
            setBackgroundResource(android.R.drawable.list_selector_background)

            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(resolveTextColor(context))
            }, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)).apply {
                marginEnd = dp(context, 20)
            })

            addView(TextView(context).apply {
                text = title
                textSize = 18f
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
            mediaUrls.toList().asReversed().filter { isAllowed(it, settings) }
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
            val extension = if (video) ".mp4" else ".jpg"
            val fileName = "threads_${System.currentTimeMillis()}$extension"
            val mime = if (video) "video/mp4" else "image/jpeg"

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
            XposedBridge.log("ThreadsEnhancer/ReLSPosed download: ${it.message}")
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
        if (context != null) {
            sendModuleBroadcast(context, ACTION_CAPTURED, url)
        }
        XposedBridge.log("ThreadsEnhancer/ReLSPosed mídia: ${sanitizeForLog(url)}")
    }

    private fun sendModuleBroadcast(context: Context, action: String, url: String? = null) {
        runCatching {
            val intent = Intent(action).apply {
                component = ComponentName(MODULE_PACKAGE, RECEIVER_CLASS)
                if (url != null) putExtra("url", url)
            }
            context.sendBroadcast(intent)
        }.onFailure {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed broadcast: ${it.message}")
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
        return lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains("video")
    }

    private fun looksLikeBottomSheet(dialog: Dialog): Boolean {
        val name = dialog.javaClass.name.lowercase()
        if (name.contains("bottomsheet") || name.contains("bottom_sheet")) return true
        val gravity = dialog.window?.attributes?.gravity ?: 0
        return gravity and Gravity.BOTTOM == Gravity.BOTTOM
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

    private fun findBestVerticalContainer(root: ViewGroup): ViewGroup? {
        var best: ViewGroup? = null
        var bestChildren = -1

        fun visit(view: View) {
            if (view is LinearLayout &&
                view.orientation == LinearLayout.VERTICAL &&
                view.childCount > bestChildren
            ) {
                best = view
                bestChildren = view.childCount
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))
                }
            }
        }

        visit(root)
        return best
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

    private data class ModuleSettings(
        val download: Boolean = true,
        val options: Boolean = true,
        val videos: Boolean = true,
        val images: Boolean = true,
        val notifications: Boolean = true
    )
}
