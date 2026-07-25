package br.gov.bomsucesso.threadsdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContentView(buildUi())
        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    private fun buildUi(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply { text = getString(R.string.title); textSize = 28f })
        root.addView(TextView(this).apply { text = getString(R.string.instructions); textSize = 16f; setPadding(0, padding, 0, padding) })
        urlInput = EditText(this).apply { hint = getString(R.string.url_hint); minLines = 3 }
        root.addView(urlInput)
        root.addView(Button(this).apply {
            text = getString(R.string.use_captured)
            setOnClickListener {
                val captured = CapturedUrlStore.read(this@MainActivity)
                if (captured.isNullOrBlank()) setStatus("Nenhuma URL foi capturada ainda.")
                else { urlInput.setText(captured); setStatus("URL capturada carregada.") }
            }
        })
        root.addView(Button(this).apply {
            text = "Colar"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                if (!value.isNullOrBlank()) urlInput.setText(extractFirstUrl(value) ?: value)
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.download)
            setOnClickListener { runCatching { enqueueDownload(urlInput.text.toString().trim()) }.onFailure { setStatus("Erro: ${it.message}") } }
        })
        status = TextView(this).apply { text = getString(R.string.status_ready); textSize = 15f; setPadding(0, padding, 0, 0) }
        root.addView(status)
        return ScrollView(this).apply { addView(root) }
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        extractFirstUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.let { urlInput.setText(it); setStatus("Link recebido pelo menu Compartilhar.") }
    }

    private fun enqueueDownload(value: String) {
        val url = extractFirstUrl(value) ?: value
        require(url.startsWith("https://")) { "Informe uma URL HTTPS válida." }
        val filename = "threads_${System.currentTimeMillis()}.mp4"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Vídeo do Threads")
            .setDescription("Download em andamento")
            .setMimeType("video/mp4")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Threads/$filename")
        val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        setStatus("Download iniciado. ID: $id")
        Toast.makeText(this, "Download iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun extractFirstUrl(text: String): String? = Regex("""https://[^\s]+""").find(text)?.value?.trimEnd('.', ',', ')', ']')
    private fun setStatus(value: String) { status.text = value }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }
}
