package app.heyrobot.core.news

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** review-link-net L3: a write on a closed engine fails instead of looping. */
class FeedTlsCloseTest {
    @Test(timeout = 10_000) fun aWriteOnAClosedEngineFailsInsteadOfLooping() {
        val engine = SSLContext.getDefault().createSSLEngine("news.example", 443).apply { useClientMode = true; closeOutbound() }
        val e = runCatching { FeedTls(engine, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).write("GET / HTTP/1.1\r\n".toByteArray()) }.exceptionOrNull()
        assertTrue(e.toString(), e is SSLException)
    }
}
