// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * F48: the mark a passphrase copied to the clipboard leaves on disk, so it is still cleared after the screen or the process
 * that copied it has gone. It never holds the passphrase: only a random salt and a hash, enough to tell whether the
 * clipboard still holds it. Only Robot's own made passphrases are marked (long and random, so the hash gives nothing away).
 */
data class ClipMark(val salt: String, val hash: String, val dueAt: Long) {
    enum class Action { WAIT, CLEAR, FORGET }

    fun matches(text: String): Boolean = hash == hashOf(salt, text)

    /** The mark, due now: the backup it was for has ended. */
    fun dueNow(now: Long): ClipMark = copy(dueAt = minOf(dueAt, now))

    fun toJson(): String = JSONObject().put("salt", salt).put("hash", hash).put("dueAt", dueAt).toString() // l10n: not text (JSON keys)

    companion object {
        const val FILE = "backup_clip.json" // l10n: not text (a file name)

        fun make(text: String, dueAt: Long, random: SecureRandom = SecureRandom()): ClipMark {
            val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) } // l10n: not text
            return ClipMark(salt, hashOf(salt, text), dueAt)
        }

        fun fromJson(json: String?): ClipMark? = try {
            val o = JSONObject(json ?: "")
            ClipMark(o.getString("salt"), o.getString("hash"), o.getLong("dueAt")).takeIf { it.salt.isNotEmpty() && it.hash.isNotEmpty() } // l10n: not text (JSON keys)
        } catch (_: Exception) {
            null
        }

        /**
         * What to do with [mark]. Not due yet, or no clip to read (Android gives none without the focus): wait. The clip still
         * holds it: clear. Something else is there now: it's yours, so forget the mark and leave it.
         */
        fun decide(mark: ClipMark, clip: String?, now: Long): Action = when {
            now < mark.dueAt || clip == null -> Action.WAIT
            mark.matches(clip) -> Action.CLEAR
            else -> Action.FORGET
        }

        private fun hashOf(salt: String, text: String): String =
            MessageDigest.getInstance("SHA-256").digest((salt + "\n" + text).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } // l10n: not text
    }
}
