// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.feedback.FeedbackRef
import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.feedback.ReportShot
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

// RP5 (report-seal-format.md): the sealed report is "RRPT" 0x01, then libsignal's HPKE seal of ReportFile's bytes with
// info "robot-report-v1" and those 5 bytes as aad; opened back with the private key to the same report. A throwaway key.
class ReportSealTest {
    private val pair = IdentityKeyPair.generate()
    private val pub = pair.publicKey.serialize()
    private val head = byteArrayOf('R'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), 1)
    private val info = "robot-report-v1".toByteArray(Charsets.US_ASCII)

    private fun png(w: Int, h: Int): ByteArray = ReportShot(w, h, IntArray(w * h) { 0xFF336699.toInt() }).png()

    private fun open(file: ByteArray, aad: ByteArray = head, inf: ByteArray = info): ByteArray =
        pair.privateKey.open(file.copyOfRange(head.size, file.size), inf, aad)

    @Test fun sealsAndOpensBackToTheSameReport() {
        val r = ReportFile.Report(FeedbackRef.new(), "Robot feedback\nWhat you said: what's 2 plus 2\n", png(64, 32))
        val file = ReportFile.seal(r, ReportSeal.sealer(pub))
        assertArrayEquals(head, file.copyOfRange(0, 5))
        assertEquals(ReportFile.Read.Ok(r), ReportFile.read(open(file)).let { if (it is ReportFile.Read.Ok) ReportFile.Read.Ok(r) else it })
        assertArrayEquals(ReportFile.write(r), open(file))
        // Nothing outside the seal: the reference is not in the file's plain bytes.
        assertTrue(!String(file, Charsets.ISO_8859_1).contains(r.ref))
    }

    @Test fun aWrongAadOrInfoDoesNotOpen() {
        val file = ReportFile.seal(ReportFile.Report(FeedbackRef.new(), "x"), ReportSeal.sealer(pub))
        assertTrue(runCatching { open(file, aad = head.copyOf().also { it[4] = 2 }) }.isFailure)
        assertTrue(runCatching { open(file, inf = "robot-link-hello-v1".toByteArray()) }.isFailure)
        assertTrue(runCatching { IdentityKeyPair.generate().privateKey.open(file.copyOfRange(5, file.size), info, head) }.isFailure)
    }

    @Test fun onlyAnX25519KeyIsTaken() {
        for (bad in listOf(ByteArray(0), ByteArray(33), pub.copyOf(32), pub + 0)) assertTrue(runCatching { ReportSeal.sealer(bad) }.isFailure)
    }

    /** One sample for the PC tool (tools/reports on main): build/test-results/report-sample/ (beelink-gradle brings it back), a 4096 × 2 screenshot (the widest side it takes). */
    @Test fun writesASampleForThePcTool() {
        val dir = File("build/test-results/report-sample").apply { deleteRecursively(); mkdirs() }
        val r = ReportFile.Report(FeedbackRef.new(), "Robot feedback\nKind: Wrong answer\nWhat you said: what's 2 plus 2\n", png(4096, 2))
        File(dir, ReportFile.name(r.ref)).writeBytes(ReportFile.seal(r, ReportSeal.sealer(pub)))
        File(dir, "private.key").writeBytes(pair.privateKey.serialize())
        assertEquals(2, dir.list()!!.size)
    }
}
