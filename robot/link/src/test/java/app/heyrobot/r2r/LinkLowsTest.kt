package app.heyrobot.r2r

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** review-link-net L3 (a write on a closed engine) and L4 (Socks5's host check, a long isolation key). */
class LinkLowsTest {
    @Test(timeout = 10_000) fun aWriteOnAClosedEngineFailsInsteadOfLooping() {
        val engine = SSLContext.getDefault().createSSLEngine("push.example", 443).apply { useClientMode = true; closeOutbound() }
        val e = runCatching { TlsStream(engine, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).write("POST / HTTP/1.1\r\n".toByteArray()) }.exceptionOrNull()
        assertTrue(e.toString(), e is SSLException)
    }

    @Test fun aHostWithALetterOutsideAsciiIsRefusedBeforeAnythingIsSent() {
        val out = ByteArrayOutputStream()
        val e = runCatching { Socks5.connect(ByteArrayInputStream(ByteArray(0)), out, "bücher.example", 443, "feed:x") }.exceptionOrNull()
        assertTrue(e.toString(), e is IllegalArgumentException)
        assertEquals(0, out.size())
    }

    @Test fun aLongIsolationKeyIsShortenedAndStaysItsOwn() {
        val a = "feed:" + "a".repeat(240) + ".example"
        val b = "feed:" + "b".repeat(240) + ".example"
        assertEquals("feed:news.example", Socks5.isolationKey("feed:news.example"))
        assertTrue(Socks5.isolationKey(a).length in 1..255)
        assertEquals(Socks5.isolationKey(a), Socks5.isolationKey(a))
        assertNotEquals(Socks5.isolationKey(a), Socks5.isolationKey(b))
    }
}
