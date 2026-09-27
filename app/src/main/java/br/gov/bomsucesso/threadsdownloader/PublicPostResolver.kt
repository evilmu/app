package br.gov.bomsucesso.threadsdownloader

import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

/**
 * Resolve metadados PUBLICAMENTE disponíveis de uma publicação.
 * Conteúdo privado, login e DRM não são contornados.
 * Execute somente fora da thread principal.
 */
object PublicPostResolver {
    private const val MAX_HTML_BYTES = 2_000_000
    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([\w:.-]+)\s*=\s*(["'])(.*?)\2""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val embeddedVideo = Regex(
        "\"(?:video_url|videoUrl|playback_url)\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )

    fun resolve(postUrl: String): List<DirectMedia> {
        require(MediaUrl.isThreadsPost(postUrl)) { "Use um link de publicação do Threads." }
        val connection = URL(postUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("O Threads retornou HTTP $status. O link pode ter expirado ou exigir login.")
            }
            if (!MediaUrl.isThreadsPost(connection.url.toExternalForm())) {
                throw IllegalStateException("O Threads redirecionou para outra página; publicação pode exigir login.")
            }
            val kind = connection.contentType.orEmpty()
            if (!kind.contains("html", ignoreCase = true)) {
                throw IllegalStateException("A publicação não retornou uma página HTML válida.")
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_HTML_BYTES) {
                        throw IllegalStateException("A página retornada é grande demais para análise.")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            val html = bytes.toString(Charsets.UTF_8.name())
            val video = LinkedHashMap<String, DirectMedia>()
            val image = LinkedHashMap<String, DirectMedia>()
            for (match in metaTag.findAll(html)) {
                val props = attribute.findAll(match.value).associate {
                    it.groupValues[1].lowercase() to it.groupValues[3]
                }
                val key = props["property"] ?: props["name"] ?: continue
                val raw = props["content"] ?: continue
                val item = MediaUrl.parseDirect(decode(raw)) ?: continue
                if (key in listOf("og:video", "og:video:url", "og:video:secure_url",
                        "twitter:player:stream") && item.video) {
                    video[item.url] = item
                } else if (key in listOf("og:image", "og:image:url", "twitter:image")
                    && !item.video) {
                    image[item.url] = item
                }
            }
            for (match in embeddedVideo.findAll(html)) {
                val item = MediaUrl.parseDirect(decode(match.groupValues[1])) ?: continue
                if (item.video) video[item.url] = item
            }
            if (video.isNotEmpty()) return video.values.toList()
            if (image.isNotEmpty()) return image.values.toList()
            throw IllegalStateException(
                "Nenhuma URL pública de mídia foi exposta pelo Threads. Abra a publicação no app " +
                "e use uma URL direta capturada pelo ReLSPosed; conteúdos privados não são suportados."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(raw: String): String = Html.fromHtml(
        raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u0025", "%"),
        Html.FROM_HTML_MODE_LEGACY
    ).toString().trim()
}
