// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import java.security.MessageDigest
import java.util.Base64

// Pure (1.4, report-a-problem-plan.md "What's new" 5; report-seal-format.md). The developer's public key the sealed
// reports go to: a libsignal X25519 public key, its 33 serialized bytes (0x05 and 32) in base64. Empty until the
// developer makes it with the PC tool's keygen (tools/reports); while it is, the card offers plain text only. Its
// fingerprint, shown on the card and printed by keygen: SHA-256 of the 33 bytes, the first 10 as 20 hex digits in five
// groups of four.

object ReportKey {
    /** The release key, base64; empty: none yet. A debug build may carry a throwaway one of its own (app's debug source set). */
    const val KEY = "" // l10n: not text (a key)
    const val BYTES = 33

    fun valid(key: ByteArray?): Boolean = key != null && key.size == BYTES && key[0] == 0x05.toByte()

    /** [b64] as a key, or null (empty, not base64, or not 33 bytes starting 0x05). */
    fun decode(b64: String?): ByteArray? {
        val t = b64?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try { Base64.getDecoder().decode(t) } catch (_: IllegalArgumentException) { null }?.takeIf(::valid)
    }

    fun fingerprint(key: ByteArray): String {
        require(valid(key)) { "not a report key" } // l10n: not text (an exception)
        val d = MessageDigest.getInstance("SHA-256").digest(key) // l10n: not text (an algorithm's name)
        return (0 until 10).joinToString("") { "%02x".format(d[it]) }.chunked(4).joinToString(" ") // l10n: not text (a format)
    }
}
