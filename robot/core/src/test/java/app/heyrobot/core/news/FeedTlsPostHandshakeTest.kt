// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
import javax.net.ssl.SSLEngineResult.Status.OK
import javax.net.ssl.SSLSession

/** review-home-net-bind L2: a wrap that takes no data but writes a record (a post-handshake message) doesn't fail the write. */
class FeedTlsPostHandshakeTest {
    /** The first wrap writes a 5-byte record and takes nothing; each later one passes its data through. */
    private class PostHandshakeFirst : SSLEngine() {
        private val real = SSLContext.getDefault().createSSLEngine("news.example", 443)
        private var first = true
        override fun wrap(srcs: Array<out ByteBuffer>, offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult {
            if (first) { first = false; dst.put(ByteArray(5) { 0x17 }); return SSLEngineResult(OK, NOT_HANDSHAKING, 0, 5) }
            var n = 0
            for (i in offset until offset + length) { n += srcs[i].remaining(); dst.put(srcs[i]) }
            return SSLEngineResult(OK, NOT_HANDSHAKING, n, n)
        }
        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, offset: Int, length: Int): SSLEngineResult = throw UnsupportedOperationException()
        override fun getDelegatedTask(): Runnable? = null
        override fun closeInbound() {}
        override fun isInboundDone() = false
        override fun closeOutbound() {}
        override fun isOutboundDone() = false
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): SSLSession = real.session
        override fun beginHandshake() {}
        override fun getHandshakeStatus() = NOT_HANDSHAKING
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode() = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth() = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth() = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation() = true
    }

    @Test(timeout = 10_000) fun aRecordWithNoDataBeforeTheRequestDoesntFailTheWrite() {
        val out = ByteArrayOutputStream()
        FeedTls(PostHandshakeFirst(), ByteArrayInputStream(ByteArray(0)), out).write("GET / HTTP/1.1\r\n".toByteArray())
        assertEquals(5 + 16, out.size())
    }
}
