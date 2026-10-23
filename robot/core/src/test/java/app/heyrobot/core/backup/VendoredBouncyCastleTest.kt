// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Robot's copy of Bouncy Castle 1.81's Argon2 (core's app/heyrobot/thirdparty/bouncycastle/, its NOTICE): exactly the
 * files the NOTICE lists, each one, read without the comment above its package line and with the package's old name, with
 * the SHA-256 the NOTICE gives "as copied"; the licence is Bouncy Castle's, and the app ships the same NOTICE and text.
 */
class VendoredBouncyCastleTest {
    private val dir = File("src/main/java/app/heyrobot/thirdparty/bouncycastle")
    private val notice by lazy { File(dir, "NOTICE").readText() }
    private val rows by lazy {
        Regex("""(?m)^(org/bouncycastle/\S+\.java)\s+([0-9a-f]{64})\s+([0-9a-f]{64})$""").findAll(notice).map { it.groupValues }.toList()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun eachFileIsTheOneTheNoticeNames() {
        assertEquals(20, rows.size)
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.map { "org/bouncycastle/" + it.relativeTo(dir).path }.sorted().toList()
        assertEquals(rows.map { it[1] }.sorted(), files)
        for ((_, path, upstream, copied) in rows) {
            val text = File(dir, path.removePrefix("org/bouncycastle/")).readText()
            val header = text.substringBefore("\npackage ")
            assertTrue(path, header.startsWith("/*\n * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/$path)") && header.endsWith(" */"))
            val body = text.substringAfter(header + "\n")
            assertTrue(path, !body.contains("org.bouncycastle"))
            assertEquals(path, copied, sha256(body.replace("app.heyrobot.thirdparty.bouncycastle", "org.bouncycastle").toByteArray()))
            // Only Blake2bDigest differs from the tag (its services-registry calls removed).
            if (!path.endsWith("Blake2bDigest.java")) assertEquals(path, upstream, copied)
        }
        val blake = File(dir, "crypto/digests/Blake2bDigest.java").readText()
        assertTrue(!blake.contains("CryptoServicesRegistrar") && notice.contains("Blake2bDigest.java: the three CryptoServicesRegistrar.checkConstraints"))
    }

    @Test fun theLicenceAndNoticeShipInTheApp() {
        val assets = File("../app/src/main/assets/licenses")
        assertEquals(File(assets, "MIT-bouncycastle.txt").readText(), File(dir, "LICENSE").readText())
        assertEquals(notice, File(assets, "notices/bouncycastle-NOTICE.txt").readText())
        assertTrue(File(dir, "LICENSE").readText().startsWith("Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc."))
    }
}
