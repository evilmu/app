package br.gov.bomsucesso.threadsdownloader.share

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore
import java.util.Locale

class ShareDownloadActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    private val settings by lazy {
        getSharedPreferences("module_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContentView(buildLoadingView())
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") {
            showFatalError("Compartilhe uma publicação do Threads para este aplicativo.")
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()

        val postUrl = PostMediaResolver.extractPostUrl(sharedText)
        if (postUrl == null) {
            showFatalError("O compartilhamento não contém um link válido do Threads.")
            return
        }

        progress.visibility = View.VISIBLE
        statusText.text = "Localizando a mídia desta publicação…"

        Thread {
            val result = runCatching { PostMediaResolver.resolve(postUrl) }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { media ->
                    val allowed = filterAllowed(media)
                    if (allowed.isEmpty()) {
                        showResolutionFailure(postUrl, null)
                    } else {
                        showMediaChoices(allowed)
                    }
                }.onFailure { error ->
                    showResolutionFailure(postUrl, error)
                }
            }
        }.start()
    }

    private fun filterAllowed(media: List<PostMediaResolver.Media>): List<PostMediaResolver.Media> {
        val videos = settings.getBoolean("videos_enabled", true)
        val images = settings.getBoolean("images_enabled", true)
        return media.filter { item ->
            when (item.kind) {
                PostMediaResolver.Kind.VIDEO -> videos
                PostMediaResolver.Kind.IMAGE -> images
            }
        }
    }

    private fun showMediaChoices(media: List<PostMediaResolver.Media>) {
        if (media.size == 1) {
            enqueue(media.first())
            finish()
            return
        }

        statusText.text = "Escolha o que deseja baixar"
        val labels = ArrayList<String>()
        labels += "Baixar todas (${media.size})"

        var videoIndex = 0
        var imageIndex = 0
        media.forEach { item ->
            labels += when (item.kind) {
                PostMediaResolver.Kind.VIDEO -> "Vídeo ${++videoIndex}"
                PostMediaResolver.Kind.IMAGE -> "Imagem ${++imageIndex}"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Mídias da publicação")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    media.forEach(::enqueue)
                } else {
                    media.getOrNull(which - 1)?.let(::enqueue)
                }
                finish()
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showResolutionFailure(postUrl: String, error: Throwable?) {
        statusText.text = "Não foi possível identificar a mídia automaticamente."
        val fallback = CapturedUrlStore.read(this)
            ?.takeIf(::isDirectMediaUrl)

        val builder = AlertDialog.Builder(this)
            .setTitle("Mídia não encontrada")
            .setMessage(
                buildString {
                    append("A publicação pode ser privada, exigir login ou ter mudado de formato.")
                    if (error?.message?.isNotBlank() == true) {
                        append("\n\nDetalhe: ")
                        append(error.message)
                    }
                }
            )
            .setPositiveButton("Abrir publicação") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)))
                }
                finish()
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }

        if (fallback != null) {
            builder.setNeutralButton("Usar última capturada") { _, _ ->
                enqueue(
                    PostMediaResolver.Media(
                        url = fallback,
                        kind = if (isVideo(fallback)) PostMediaResolver.Kind.VIDEO else PostMediaResolver.Kind.IMAGE,
                        score = 0,
                        source = "explicit_fallback"
                    )
                )
                finish()
            }
        }

        builder.setOnCancelListener { finish() }.show()
    }

    private fun showFatalError(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
        AlertDialog.Builder(this)
            .setTitle("Threads Enhancer")
            .setMessage(message)
            .setPositiveButton("Fechar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun enqueue(media: PostMediaResolver.Media) {
        runCatching {
            val lower = media.url.lowercase(Locale.ROOT)
            val extension = when (media.kind) {
                PostMediaResolver.Kind.VIDEO -> if (lower.substringBefore('?').endsWith(".m4v")) ".m4v" else ".mp4"
                PostMediaResolver.Kind.IMAGE -> when {
                    lower.substringBefore('?').endsWith(".webp") -> ".webp"
                    lower.substringBefore('?').endsWith(".png") -> ".png"
                    else -> ".jpg"
                }
            }
            val mime = when (extension) {
                ".mp4" -> "video/mp4"
                ".m4v" -> "video/x-m4v"
                ".webp" -> "image/webp"
                ".png" -> "image/png"
                else -> "image/jpeg"
            }
            val fileName = "threads_${System.currentTimeMillis()}$extension"
            val notifications = settings.getBoolean("notifications_enabled", true)

            val request = DownloadManager.Request(Uri.parse(media.url))
                .setTitle(
                    if (media.kind == PostMediaResolver.Kind.VIDEO) {
                        "Vídeo do Threads"
                    } else {
                        "Imagem do Threads"
                    }
                )
                .setDescription("Download em andamento")
                .setMimeType(mime)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                    if (notifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_HIDDEN
                    }
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Threads/$fileName"
                )

            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Download iniciado", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "Não foi possível iniciar o download: ${error.message.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildLoadingView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        root.addView(TextView(this).apply {
            text = "Threads Enhancer"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })

        statusText = TextView(this).apply {
            text = "Preparando…"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return url.startsWith("https://") && (
            lower.contains("fbcdn.net") ||
                lower.contains("cdninstagram.com") ||
                lower.contains("instagram.com")
            ) && (
            lower.contains(".mp4") ||
                lower.contains(".m4v") ||
                lower.contains(".jpg") ||
                lower.contains(".jpeg") ||
                lower.contains(".png") ||
                lower.contains(".webp") ||
                lower.contains("/t16/") ||
                lower.contains("/t51.")
            )
    }

    private fun isVideo(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains("/t16/") ||
            lower.contains("video")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
