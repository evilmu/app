package br.gov.bomsucesso.threadsdownloader

import org.junit.Assert.*
import org.junit.Test

class MediaUrlTest {
    @Test fun directVideoKeepsQuerySignature() {
        val original = "https://scontent.cdninstagram.com/v/t50.2886-16/clip.mp4?oh=a%3Db&oe=123"
        val media = MediaUrl.parseDirect(original)
        assertNotNull(media)
        assertTrue(media!!.video)
        assertEquals("video/mp4", media.mimeType)
        assertEquals(original, media.url)
    }

    @Test fun postPageCannotBeSavedAsVideo() {
        val post = "https://www.threads.net/@perfil/post/CXYZ?x=1"
        assertNull(MediaUrl.parseDirect(post))
        assertTrue(MediaUrl.isThreadsPost(post))
        assertTrue(MediaUrl.isThreadsPost("https://www.threads.com/@perfil/post/CXYZ"))
    }

    @Test fun rejectUntrustedMediaHosts() {
        assertFalse(MediaUrl.isTrustedCapture("https://fbcdn.net.evil.example/file.mp4"))
        assertFalse(MediaUrl.isTrustedCapture("http://video.fbcdn.net/file.mp4"))
        assertTrue(MediaUrl.isTrustedCapture("https://video.fbcdn.net/clip.mp4?oh=abc"))
    }

    @Test fun detectImageType() {
        val image = MediaUrl.parseDirect("https://cdninstagram.com/media/123.jpeg?size=l")
        assertNotNull(image)
        assertFalse(image!!.video)
        assertEquals("jpg", image.extension)
        assertEquals("image/jpeg", image.mimeType)
    }

    @Test fun embeddedUrlExtraction() {
        assertEquals("https://www.threads.com/@abc/post/xyz",
            MediaUrl.firstUrl("Veja: https://www.threads.com/@abc/post/xyz."))
    }
}
