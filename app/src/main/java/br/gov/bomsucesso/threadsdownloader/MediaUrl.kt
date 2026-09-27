package br.gov.bomsucesso.threadsdownloader

import java.net.URI
import java.util.Locale

/** Apenas arquivos de mídia HTTPS. Links de publicação nunca viram falsos .mp4. */
data class DirectMedia(val url: String, val extension: String, val mimeType: String) {
    val video: Boolean get() = mimeType.startsWith("video/")
}

object MediaUrl {
    private val urlPattern = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String): String? =
        urlPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', ';')

    fun parseDirect(value: String): DirectMedia? {
        val uri = try { URI(value.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return null
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val ext = path.substringAfterLast('.', "")
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "m4v" -> "video/x-m4v"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> return null
        }
        return DirectMedia(value.trim(), if (ext == "jpeg") "jpg" else ext, mime)
    }

    fun isThreadsPost(value: String): Boolean {
        val uri = try { URI(value.trim()) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "threads.net" || host.endsWith(".threads.net") ||
            host == "threads.com" || host.endsWith(".threads.com")
    }

    fun isTrustedCapture(value: String): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return parseDirect(value) != null &&
            (host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
                host == "cdninstagram.com" || host.endsWith(".cdninstagram.com"))
    }
}
