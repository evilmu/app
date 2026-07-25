package br.gov.bomsucesso.threadsdownloader.hook

import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
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

class ThreadsHook : IXposedHookLoadPackage {

    companion object {
        private const val THREADS_PACKAGE = "com.instagram.barcelona"
        private const val INJECTED_TAG = "threads_downloader_menu_v2"
        private const val MAX_URLS = 24
        private val mediaUrls = ArrayDeque<String>()
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != THREADS_PACKAGE) return

        XposedBridge.log("ThreadsDownloader: Threads carregado em ${param.processName}")
        hookJavaNetUrl()
        hookAndroidUri()
        hookBottomSheets()
    }

    private fun hookJavaNetUrl() {
        runCatching {
            XposedBridge.hookAllConstructors(URL::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    rememberMedia(param.thisObject?.toString())
                }
            })
        }.onFailure { XposedBridge.log("ThreadsDownloader URL hook: ${it.message}") }
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
        }.onFailure { XposedBridge.log("ThreadsDownloader Uri hook: ${it.message}") }
    }

    private fun hookBottomSheets() {
        runCatching {
            XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    dialog.window?.decorView?.post {
                        runCatching { injectMenu(dialog) }
                            .onFailure { XposedBridge.log("ThreadsDownloader menu: ${it.message}") }
                    }
                }
            })
        }.onFailure { XposedBridge.log("ThreadsDownloader Dialog hook: ${it.message}") }
    }

    private fun injectMenu(dialog: Dialog) {
        if (!looksLikeBottomSheet(dialog)) return
        val content = dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (findTaggedView(content) != null) return

        val target = findBestVerticalContainer(content) ?: content
        val context = target.context
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = INJECTED_TAG
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }

        section.addView(createMenuRow(context, "Baixar", android.R.drawable.stat_sys_download) {
            val url = synchronized(mediaUrls) { mediaUrls.lastOrNull() }
            if (url == null) {
                Toast.makeText(context, "Abra a mídia antes de baixar", Toast.LENGTH_LONG).show()
            } else {
                enqueueDownload(context, url)
                dialog.dismiss()
            }
        })

        section.addView(createMenuRow(context, "Opções de download", android.R.drawable.ic_menu_manage) {
            showDownloadOptions(context, dialog)
        })

        val index = if (target.childCount > 1) 1 else target.childCount
        target.addView(
            section,
            index,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        XposedBridge.log("ThreadsDownloader: opções injetadas no menu")
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

    private fun showDownloadOptions(context: Context, parent: Dialog) {
        val urls = synchronized(mediaUrls) { mediaUrls.toList().asReversed() }
        if (urls.isEmpty()) {
            Toast.makeText(context, "Nenhuma mídia detectada. Abra o vídeo ou imagem primeiro.", Toast.LENGTH_LONG).show()
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
                    limited.reversed().forEach { enqueueDownload(context, it) }
                } else {
                    val index = if (limited.size > 1) which - 1 else which
                    limited.getOrNull(index)?.let { enqueueDownload(context, it) }
                }
                parent.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enqueueDownload(context: Context, url: String) {
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
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Threads/$fileName")

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "Download iniciado", Toast.LENGTH_SHORT).show()
        }.onFailure {
            XposedBridge.log("ThreadsDownloader download: ${it.message}")
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
        XposedBridge.log("ThreadsDownloader mídia: ${sanitizeForLog(url)}")
    }

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

    private fun looksLikeBottomSheet(dialog: Dialog): Boolean {
        val name = dialog.javaClass.name.lowercase()
        if (name.contains("bottomsheet") || name.contains("bottom_sheet")) return true
        val gravity = dialog.window?.attributes?.gravity ?: 0
        return gravity and Gravity.BOTTOM == Gravity.BOTTOM
    }

    private fun findTaggedView(root: View): View? {
        if (root.tag == INJECTED_TAG) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTaggedView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findBestVerticalContainer(root: ViewGroup): ViewGroup? {
        var best: ViewGroup? = null
        var bestChildren = -1

        fun visit(view: View) {
            if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL && view.childCount > bestChildren) {
                best = view
                bestChildren = view.childCount
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }

        visit(root)
        return best
    }

    private fun resolveTextColor(context: Context): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else Color.WHITE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun sanitizeForLog(url: String): String =
        url.substringBefore('?') + if ('?' in url) "?…" else ""
}
