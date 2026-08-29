package br.gov.bomsucesso.threadsdownloader.share

import android.os.Build
import android.text.Html
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

internal object PostMediaResolver {

    enum class Kind { VIDEO, IMAGE }

    data class Media(
        val url: String,
        val kind: Kind,
        val score: Int,
        val source: String
    )

    private data class Candidate(
        val url: String,
        val kind: Kind,
        val score: Int,
        val source: String
    )

    private val sharedUrlRegex = Regex("""https?://[^\s<>\"]+""")

    private val metaForward = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*[\"']([^\"']+)[\"'][^>]+content\s*=\s*[\"']([^\"']+)[\"'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val metaReverse = Regex(
        """<meta[^>]+content\s*=\s*[\"']([^\"']+)[\"'][^>]+(?:property|name)\s*=\s*[\"']([^\"']+)[\"'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val jsonMediaRegex = Regex(
        """[\"'](?:video_url|playable_url|playable_url_quality_hd|display_url|image_url|contentUrl|thumbnailUrl)[\"']\s*:\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE
    )

    private val embeddedCdnRegex = Regex(
        """https?(?::|%3A)(?:\\/|/|%2F){2}[^\s\"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    fun extractPostUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null

        return sharedUrlRegex.findAll(sharedText)
            .map { sanitizeSharedUrl(it.value) }
            .firstOrNull { value ->
                runCatching {
                    val host = URI(value).host.orEmpty().lowercase(Locale.ROOT)
                    host == "threads.net" ||
                        host.endsWith(".threads.net") ||
                        host == "threads.com" ||
                        host.endsWith(".threads.com")
                }.getOrDefault(false)
            }
    }

    fun resolve(postUrl: String): List<Media> {
        val html = fetch(postUrl)
        val candidates = LinkedHashMap<String, Candidate>()

        fun add(rawUrl: String, source: String, baseScore: Int, declaredKind: Kind? = null) {
            val normalized = normalizeCandidate(rawUrl) ?: return
            if (!isMediaUrl(normalized)) return

            val kind = declaredKind ?: detectKind(normalized)
            var score = baseScore
            val lower = normalized.lowercase(Locale.ROOT)

            if (kind == Kind.VIDEO) score += 250
            if (lower.contains("/t16/")) score += 180
            if (lower.contains("t51.71878-15") || lower.contains("t51.82787-15")) score += 130
            if (lower.contains("scontent-") || lower.contains("cdninstagram.com")) score += 40
            if (lower.contains("t51.2885-19")) score -= 700 // foto de perfil
            if (lower.contains("150x150") || lower.contains("320x320")) score -= 350

            val previous = candidates[normalized]
            if (previous == null || score > previous.score) {
                candidates[normalized] = Candidate(normalized, kind, score, source)
            }
        }

        metaForward.findAll(html).forEach { match ->
            addMeta(match.groupValues[1], match.groupValues[2], ::add)
        }
        metaReverse.findAll(html).forEach { match ->
            addMeta(match.groupValues[2], match.groupValues[1], ::add)
        }

        jsonMediaRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            add(raw, "embedded_json", 620)
        }

        embeddedCdnRegex.findAll(html).forEach { match ->
            add(match.value, "embedded_url", 300)
        }

        return candidates.values
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.url.length }
            )
            .take(20)
            .map { Media(it.url, it.kind, it.score, it.source) }
    }

    private fun addMeta(
        keyRaw: String,
        valueRaw: String,
        add: (String, String, Int, Kind?) -> Unit
    ) {
        val key = keyRaw.lowercase(Locale.ROOT).trim()
        when {
            key == "og:video" ||
                key == "og:video:url" ||
                key == "og:video:secure_url" ||
                key == "twitter:player:stream" -> add(valueRaw, key, 1_100, Kind.VIDEO)

            key == "og:image" ||
                key == "og:image:url" ||
                key == "og:image:secure_url" ||
                key == "twitter:image" ||
                key == "twitter:image:src" -> add(valueRaw, key, 900, Kind.IMAGE)
        }
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
            setRequestProperty("Cache-Control", "no-cache")
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            requireNotNull(stream) { "Resposta vazia do Threads ($status)" }
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                require(text.isNotBlank()) { "Página vazia do Threads" }
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeCandidate(raw: String): String? {
        var value = raw.trim()
            .replace("\\/", "/")
            .replace("%3A", ":", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)

        value = Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

        value = if (Build.VERSION.SDK_INT >= 24) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(value).toString()
        }

        value = value.trim('"', '\'', ' ', '\n', '\r', '\t')
        if (!value.startsWith("https://", ignoreCase = true)) return null
        return value
    }

    private fun sanitizeSharedUrl(raw: String): String = raw
        .trim()
        .trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val trustedHost = host.contains("fbcdn.net") ||
            host.contains("cdninstagram.com") ||
            host.endsWith("instagram.com")
        if (!trustedHost) return false

        return lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("/t16/") ||
            lower.contains("/t51.") ||
            lower.contains("video")
    }

    private fun detectKind(url: String): Kind {
        val lower = url.lowercase(Locale.ROOT)
        return if (
            lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains("/t16/") ||
            lower.contains("video")
        ) Kind.VIDEO else Kind.IMAGE
    }
}
