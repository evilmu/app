package br.gov.bomsucesso.threadsdownloader

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** An HTTP 200 login page must not be reported as a successful MP4 download. */
object MediaDownloader {
    private val worker = Executors.newFixedThreadPool(2)
    private const val MAX_BYTES = 1_500_000_000L
    private const val AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    sealed class Event {
        data class Progress(val percent: Int) : Event()
        data class Saved(val filename: String, val uri: Uri) : Event()
        data class Queued(val id: Long) : Event()
        data class Failed(val message: String) : Event()
    }

    fun start(context: Context, media: DirectMedia, onEvent: (Event) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val result = downloadToMediaStore(app, media, onEvent)
                    onEvent(Event.Saved(result.first, result.second))
                } else {
                    val request = DownloadManager.Request(Uri.parse(media.url))
                        .setTitle("Threads " + media.extension.uppercase())
                        .setMimeType(media.mimeType)
                        .setAllowedOverMetered(true)
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "Threads/threads_" + System.currentTimeMillis() + "." + media.extension
                        )
                    request.addRequestHeader("User-Agent", AGENT)
                    request.addRequestHeader("Referer", "https://www.threads.net/")
                    val id = (app.getSystemService(Context.DOWNLOAD_SERVICE)
                        as DownloadManager).enqueue(request)
                    onEvent(Event.Queued(id))
                }
            } catch (e: Exception) {
                val reason = when (e) {
                    is java.net.SocketTimeoutException -> "Tempo esgotado. Tente novamente."
                    is java.net.UnknownHostException -> "Servidor inacessível. Verifique a conexão."
                    else -> e.message ?: e.javaClass.simpleName
                }
                onEvent(Event.Failed(reason))
            }
        }
    }

    private fun downloadToMediaStore(
        context: Context, media: DirectMedia, onEvent: (Event) -> Unit
    ): Pair<String, Uri> {
        val connection = connect(media.url)
        var pending: Uri? = null
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val reason = when (code) {
                    401, 403 -> "Acesso negado ($code). Capture uma nova URL; links temporários expiram."
                    404, 410 -> "Arquivo indisponível ($code). A URL pode ter expirado."
                    429 -> "Servidor limitou os downloads (429). Tente mais tarde."
                    else -> "Servidor retornou HTTP $code."
                }
                throw IOException(reason)
            }
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) throw IOException("Arquivo excede o limite de 1,5 GB.")
            val type = connection.contentType.orEmpty().lowercase()
            if (type.contains("text/") || type.contains("json")) {
                throw IOException("O servidor retornou texto, não um arquivo de mídia.")
            }
            val name = "threads_" + System.currentTimeMillis() + "." + media.extension
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Threads")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Não foi possível criar o arquivo em Downloads.")
            pending = destination
            connection.inputStream.use { source ->
                BufferedInputStream(source, 16_384).use { input ->
                    val prefix = ByteArray(32)
                    var prefixLen = 0
                    while (prefixLen < prefix.size) {
                        val count = input.read(prefix, prefixLen, prefix.size - prefixLen)
                        if (count < 0) break
                        prefixLen += count
                    }
                    if (!validSignature(prefix, prefixLen, media)) {
                        throw IOException("Resposta não é mídia válida. A URL pode ter expirado.")
                    }
                    val stream = resolver.openOutputStream(destination, "w")
                        ?: throw IOException("Não foi possível abrir o arquivo de destino.")
                    stream.use { output ->
                        output.write(prefix, 0, prefixLen)
                        var written = prefixLen.toLong()
                        var previous = -5
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > MAX_BYTES) throw IOException("Arquivo excede o limite de 1,5 GB.")
                            output.write(buffer, 0, n)
                            if (length > 0) {
                                val percent = ((written * 100) / length).toInt().coerceIn(0, 99)
                                if (percent >= previous + 5) {
                                    previous = percent
                                    onEvent(Event.Progress(percent))
                                }
                            }
                        }
                        output.flush()
                        if (length > 0 && written < length) {
                            throw IOException("Conexão interrompida: arquivo incompleto.")
                        }
                        if (written < 32) throw IOException("O servidor retornou um arquivo vazio.")
                    }
                }
            }
            resolver.update(destination, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            pending = null
            return name to destination
        } finally {
            connection.disconnect()
            pending?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun connect(initial: String): HttpURLConnection {
        var next = URL(initial)
        repeat(6) {
            if (next.protocol != "https") throw IOException("Redirecionamento inseguro bloqueado.")
            val connection = next.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", AGENT)
            connection.setRequestProperty("Referer", "https://www.threads.net/")
            connection.setRequestProperty("Accept", "video/*,image/*,application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Redirecionamento sem destino.")
                next = URL(next, location)
                connection.disconnect()
            } else {
                return connection
            }
        }
        throw IOException("Muitos redirecionamentos.")
    }

    private fun validSignature(prefix: ByteArray, size: Int, media: DirectMedia): Boolean {
        if (size < 12) return false
        fun starts(vararg bytes: Int): Boolean = bytes.indices.all {
            it < size && (prefix[it].toInt() and 0xff) == bytes[it]
        }
        val head = String(prefix, 0, size, Charsets.ISO_8859_1).trimStart().lowercase()
        if (head.startsWith("<") || head.startsWith("{")) return false
        return when (media.extension) {
            "jpg" -> starts(0xff, 0xd8, 0xff)
            "png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "webp" -> String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(prefix, 8, 4, Charsets.US_ASCII) == "WEBP"
            "mp4", "m4v" -> {
                val atom = String(prefix, 4, 4, Charsets.US_ASCII)
                atom == "ftyp" || atom == "styp" || atom == "moov" || atom == "mdat"
            }
            else -> false
        }
    }
}
