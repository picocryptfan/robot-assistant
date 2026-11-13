// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.BuildConfig
import app.heyrobot.core.feedback.ReportKey
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RP5: the throwaway report key is the debug build's alone (src/debug, for phone row 8): a release build carries none,
// and while core's key is empty a release seals nothing (the card offers plain text only).
class ReportKeysTest {
    @Test fun aReleaseBuildCarriesNoTestKey() {
        val k = ReportKeys.testKey()
        if (BuildConfig.BUILD_TYPE == "release") {
            assertNull(k)
            if (ReportKey.KEY.isEmpty()) assertNull(ReportKeys.key())
        } else {
            assertEquals("554c 3c71 13b0 83ca fdec", ReportKey.fingerprint(k!!))
        }
    }

    @Test fun theTestKeyIsInTheDebugSourceSetOnly() {
        val key = File("src/debug/java/app/heyrobot/sys/ReportTestKey.kt").readText().substringAfter("KEY = \"").substringBefore('"')
        assertEquals(44, key.length)
        val shipped = listOf("src/main", "src/spoken", "../core/src/main", "../link/src/main").map(::File)
        assertTrue(shipped.all { it.isDirectory })
        val carriers = shipped.flatMap { d -> d.walkTopDown().filter { it.isFile && it.readText().contains(key) }.toList() }
        assertEquals(emptyList<File>(), carriers)
    }

    // verify_apk.sh's key check (tools/report_key_hits.py): the class and the key are found; main's own lookup of the
    // class by name, in every release dex (report-seal-b's run failed on it, 9 Nov 2026), isn't a hit.
    @Test fun theApkCheckFindsTheKeyAndTheClassButNotTheLookupName() {
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() }.getOrDefault(1) == 0
        assumeTrue("needs python3", python)
        val src = File("src/debug/java/app/heyrobot/sys/ReportTestKey.kt")
        val key = src.readText().substringAfter("KEY = \"").substringBefore('"')
        val lookup = "app.heyrobot.sys.ReportTestKey"
        assertTrue(File("src/main/java/app/heyrobot/sys/ReportKeys.kt").readText().contains("\"$lookup\""))
        fun hits(vararg entries: Pair<String, ByteArray>): String {
            val apk = File.createTempFile("report-key", ".apk")
            try {
                ZipOutputStream(apk.outputStream()).use { z ->
                    entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
                }
                val p = ProcessBuilder("python3", "-I", "../tools/report_key_hits.py", apk.path, src.path).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                assertTrue("report_key_hits.py: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
                return out
            } finally { apk.delete() }
        }
        val pad = "\u0000dex\u0000app.heyrobot.sys.MtSpeedRun\u0000"
        assertEquals("", hits("classes4.dex" to (pad + lookup + "\u0000").toByteArray(), "res/a.xml" to "plain".toByteArray()))
        assertEquals("classes.dex(class)", hits("classes.dex" to (pad + "Lapp/heyrobot/sys/ReportTestKey;").toByteArray()))
        assertEquals("classes2.dex(base64)", hits("classes2.dex" to (pad + key).toByteArray()))
        val raw = java.util.Base64.getDecoder().decode(key)
        assertEquals("assets/k.bin(bytes)", hits("assets/k.bin" to byteArrayOf(1, 2) + raw))
        // L4: the fingerprint the script computes is the published one, spaced and as bare hex.
        assertEquals("a.txt(fingerprint)", hits("a.txt" to "554c 3c71 13b0 83ca fdec".toByteArray()))
        assertEquals("b.txt(fingerprint hex)", hits("b.txt" to "554c3c7113b083cafdec".toByteArray()))
    }
}
