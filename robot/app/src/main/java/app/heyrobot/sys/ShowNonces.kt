// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.ShowTrust
import java.io.File
import java.security.SecureRandom

/**
 * 1.0 audit (ipc-1): Robot's app's single-use nonce for its own showAssist (Talk to Robot). The main app mints it into a
 * private file just before the show; the :voice process's panel uses it up (read and deleted) on the first show that names
 * one. Another app can't read the file, so its showAssist opens only the plain panel (core ShowTrust).
 */
object ShowNonces {
    private fun file(ctx: Context) = File(ctx.filesDir, "show_nonce")

    /** Main app: a new nonce, replacing any unused one. */
    fun mint(ctx: Context): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ShowTrust.NonceCheck.encode(nonce, System.currentTimeMillis()))
        if (!tmp.renameTo(f)) { tmp.delete(); throw IllegalStateException("nonce not saved") }
        return nonce
    }

    /** :voice: whether [given] is the minted nonce, still fresh. Any try uses the stored one up. */
    fun consume(ctx: Context, given: String): Boolean {
        val f = file(ctx)
        val stored = try { if (f.isFile) f.readText() else null } catch (_: Throwable) { null }
        f.delete()
        val (nonce, at) = ShowTrust.NonceCheck.decode(stored) ?: return false
        return ShowTrust.NonceCheck.valid(nonce, at, given, System.currentTimeMillis())
    }
}
