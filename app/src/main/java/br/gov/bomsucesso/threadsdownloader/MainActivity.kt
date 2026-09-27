package br.gov.bomsucesso.threadsdownloader

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val lookupWorker = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var hookStatus: TextView
    private lateinit var captures: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleSharedText(intent)
        refresh()
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 11)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    private fun buildUi(): ScrollView {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun heading(value: String, size: Float): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setPadding(0, pad / 2, 0, pad / 2)
            }
        root.addView(heading("Threads Downloader", 25f))
        hookStatus = heading("Verificando módulo ReLSPosed…", 13f)
        root.addView(hookStatus)
        root.addView(heading(
            "Cole um link público do Threads ou uma URL direta .mp4/.jpg. " +
            "Para vídeos do feed, abra o Threads e volte aqui para escolher o arquivo.", 15f
        ))
        urlInput = EditText(this).apply {
            hint = "https://www.threads.net/@perfil/post/…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            maxLines = 4
        }
        root.addView(urlInput)
        root.addView(Button(this).apply {
            text = "Baixar mídia"
            setOnClickListener { downloadInput() }
        })
        root.addView(Button(this).apply {
            text = "Colar link"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val value = clipboard.primaryClip?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)?.toString().orEmpty()
                urlInput.setText(MediaUrl.firstUrl(value) ?: value)
            }
        })
        root.addView(heading("Mídias capturadas pelo ReLSPosed", 19f))
        captures = heading("", 14f)
        root.addView(captures)
        root.addView(Button(this).apply {
            text = "Baixar último vídeo capturado"
            setOnClickListener {
                val video = CapturedUrlStore.readAll(this@MainActivity)
                    .mapNotNull(MediaUrl::parseDirect).firstOrNull { it.video }
                if (video == null) showStatus("Nenhum vídeo capturado. Abra um vídeo no Threads e atualize.")
                else download(video)
            }
        })
        root.addView(Button(this).apply {
            text = "Escolher mídia capturada"
            setOnClickListener { showCaptured() }
        })
        root.addView(Button(this).apply {
            text = "Atualizar capturas"
            setOnClickListener { refresh() }
        })
        status = heading("Pronto. Arquivos salvos em Downloads/Threads.", 15f)
        root.addView(status)
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val readyAt = getSharedPreferences("hook_status", Context.MODE_PRIVATE)
            .getLong("ready_at", 0L)
        hookStatus.text = if (readyAt > 0L) {
            "ReLSPosed: hook detectado. Abra uma publicação com vídeo no Threads."
        } else {
            "ReLSPosed: sem confirmação. Ative o módulo para Threads e reinicie o Threads."
        }
        val list = CapturedUrlStore.readAll(this).mapNotNull(MediaUrl::parseDirect)
        captures.text = if (list.isEmpty()) "Nenhuma mídia capturada." else {
            val videos = list.count { it.video }
            val images = list.size - videos
            videos.toString() + " vídeo(s), " + images + " imagem(ns)."
        }
    }

    private fun showCaptured() {
        val list = CapturedUrlStore.readAll(this)
            .mapNotNull(MediaUrl::parseDirect).sortedByDescending { it.video }
        if (list.isEmpty()) {
            showStatus("Nenhuma mídia capturada. Abra um vídeo no Threads primeiro.")
            return
        }
        val labels = list.mapIndexed { index, media ->
            val type = if (media.video) "Vídeo" else "Imagem"
            val host = runCatching { java.net.URI(media.url).host }.getOrDefault("CDN")
            type + " " + (index + 1) + " · " + media.extension.uppercase() + " · " + host
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolha o arquivo")
            .setItems(labels) { _, which -> download(list[which]) }
            .setNeutralButton("Limpar histórico") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Limpar URLs capturadas?")
                    .setPositiveButton("Limpar") { _, _ ->
                        CapturedUrlStore.clear(this)
                        refresh()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun downloadInput() {
        val value = urlInput.text.toString().trim()
        val url = MediaUrl.firstUrl(value) ?: value
        val media = MediaUrl.parseDirect(url)
        when {
            media != null -> download(media)
            MediaUrl.isThreadsPost(url) -> resolvePost(url)
            else -> showStatus(
                "Link inválido. Use uma publicação pública do Threads ou URL direta " +
                "HTTPS (.mp4, .m4v, .jpg, .png, .webp)."
            )
        }
    }

    private fun resolvePost(url: String) {
        showStatus("Consultando metadados públicos da publicação…")
        lookupWorker.execute {
            val result = runCatching { PublicPostResolver.resolve(url) }
            runOnUiThread {
                result.onSuccess { list ->
                    if (list.size == 1) {
                        showStatus("Mídia encontrada. Iniciando download…")
                        download(list[0])
                    } else {
                        val labels = list.mapIndexed { i, item ->
                            (if (item.video) "Vídeo " else "Imagem ") +
                                (i + 1) + " · " + item.extension.uppercase()
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Mídias públicas encontradas")
                            .setItems(labels) { _, index -> download(list[index]) }
                            .setPositiveButton("Baixar todas") { _, _ -> list.forEach(::download) }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }.onFailure { showStatus(it.message ?: "Falha ao consultar a publicação.") }
            }
        }
    }

    private fun download(media: DirectMedia) {
        showStatus("Conectando para baixar " +
            (if (media.video) "vídeo" else "imagem") + "…")
        MediaDownloader.start(this, media) { event ->
            runOnUiThread {
                when (event) {
                    is MediaDownloader.Event.Progress ->
                        showStatus("Baixando… " + event.percent + "%")
                    is MediaDownloader.Event.Saved -> {
                        showStatus("Download concluído: Downloads/Threads/" + event.filename)
                        refresh()
                        AlertDialog.Builder(this)
                            .setTitle("Download concluído")
                            .setMessage("Arquivo salvo em Downloads/Threads/" + event.filename)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Abrir arquivo") { _, _ ->
                                val view = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(event.uri, media.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { startActivity(view) }
                                    .onFailure {
                                        showStatus("Abra Downloads/Threads para ver o arquivo.")
                                    }
                            }.show()
                    }
                    is MediaDownloader.Event.Queued ->
                        showStatus("Download enviado ao Android (ID " + event.id + "). Confira a notificação.")
                    is MediaDownloader.Event.Failed ->
                        showStatus("Falha no download: " + event.message)
                }
            }
        }
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = MediaUrl.firstUrl(shared) ?: return
        urlInput.setText(url)
        showStatus("Link recebido. Toque em Baixar mídia.")
    }

    private fun showStatus(value: String) {
        if (::status.isInitialized) status.text = value
    }

    override fun onDestroy() {
        lookupWorker.shutdown()
        super.onDestroy()
    }
}
