// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.Context
import app.heyrobot.core.SecretRead
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnSecretsCodec
import app.heyrobot.core.connect.SecretsFile
import app.heyrobot.sys.ConnKeyFiles

// VOICE PROCESS. Each connection's secrets (docs/plans/skills-connections-plan.md §3, §4.3; C3): its token, ntfy's topic,
// an address that is itself the key, a header's value you marked secret, all in one key file (core ConnSecretsCodec's
// plain form) encrypted with its own Android Keystore key, robot_conn_<id> (sys/KeystoreSecret.kt, the class helper keys
// share; files in sys/Connections.kt ConnKeyFiles). Pasted once into the connection window, which runs in this process,
// and decrypted only here, just before a call (C4's runner) or Test, for TermuxSecrets (a Termux command holding one is
// refused) and, from C10, a backup with Keys and private codes ([ConnKeys.backup], [ConnKeys.restore], called only by
// BackupSecrets, in this process: the plain form goes straight into the backup's encryption). Never in a log, a crash text, the history, an intent or an
// IPC message: the main app only reads whether one is saved and its masked form. A key the Keystore can't read just now is
// kept; one that can never be read again (its Keystore key gone, the file or its plain form damaged) is removed, and you
// paste it again; one saved by a newer Robot is kept and never used.

/** What reading a connection's key file gave. */
sealed interface ConnKeyRead {
    /** Its secrets. [toString] never shows them. */
    class Present(val secrets: ConnSecrets) : ConnKeyRead {
        override fun toString(): String = "Present(…)" // l10n: not text (toString, for the logs)
    }

    /** None is saved. */
    data object Absent : ConnKeyRead

    /** Saved, but couldn't be read just now: kept, and the next read tries again ([reason]: a code for the log). */
    data class Unavailable(val reason: String) : ConnKeyRead

    /** Saved, but can never be read again: removed as it was read, and Settings says so ([reason]: a code for the log). */
    data class Invalidated(val reason: String) : ConnKeyRead

    /** Saved by a newer Robot ([version] of the plain form): kept as it is, never used or removed. */
    data class TooNew(val version: Int) : ConnKeyRead
}

/** C10: a connection key for a backup: its plain form ([Plain]: the caller zeroes it), none to carry, or none just now. */
sealed interface ConnKeyBackup {
    class Plain(val bytes: ByteArray) : ConnKeyBackup {
        override fun toString(): String = "Plain(${bytes.size} bytes)" // l10n: not text (toString, for the logs)
    }

    data object None : ConnKeyBackup
    /**
     * Saved, but it can't go in this backup: the Keystore couldn't read it just now, or (C12's L6) a newer Robot wrote
     * it. Back up now names the connection.
     */
    data object Unavailable : ConnKeyBackup
}

object ConnKeys {
    /**
     * Encrypts and saves [secrets] for connection [id] (every part it keeps secret, in one file), with each part's masked
     * form for Settings; false (nothing saved) if it can't. Background thread.
     */
    fun save(ctx: Context, id: String, secrets: ConnSecrets): Boolean {
        if (secrets.parts.isEmpty() || !secrets.valid) return false
        val plain = String(ConnSecretsCodec.encode(secrets), Charsets.UTF_8)
        return ConnKeyFiles.store(id).save(ctx, plain, ConnKeyFiles.masksText(secrets))
    }

    /** Connection [id]'s secrets ([ConnKeyRead.Present]), or why there are none to use. Background thread. */
    fun read(ctx: Context, id: String): ConnKeyRead {
        val store = ConnKeyFiles.store(id)
        return when (val r = store.read(ctx)) {
            is SecretRead.Present -> when (val f = ConnSecretsCodec.read(r.value.toByteArray(Charsets.UTF_8))) {
                is SecretsFile.Ok -> ConnKeyRead.Present(f.secrets)
                is SecretsFile.TooNew -> ConnKeyRead.TooNew(f.version)
                // Decrypted, but no Robot will ever read its plain form: removed, as one the Keystore can never read is.
                SecretsFile.Damaged -> {
                    store.forget(ctx)
                    ConnKeyRead.Invalidated(DAMAGED)
                }
            }
            SecretRead.Absent -> ConnKeyRead.Absent
            is SecretRead.Unavailable -> ConnKeyRead.Unavailable(r.reason)
            // KeystoreSecret has already removed it.
            is SecretRead.Invalidated -> ConnKeyRead.Invalidated(r.reason)
        }
    }

    /**
     * C10, Back up now with Keys and private codes (assistant/backup/BackupSecrets only): connection [id]'s secrets in their
     * plain form (core ConnSecretsCodec's, every part in one entry), straight into the backup's encryption; the caller zeroes
     * the bytes once written. [ConnKeyRead.Present]'s are never handed out: only these bytes. Background thread.
     */
    fun backup(ctx: Context, id: String): ConnKeyBackup = when (val r = read(ctx, id)) {
        is ConnKeyRead.Present -> ConnKeyBackup.Plain(ConnSecretsCodec.encode(r.secrets))
        ConnKeyRead.Absent, is ConnKeyRead.Invalidated -> ConnKeyBackup.None
        is ConnKeyRead.Unavailable -> ConnKeyBackup.Unavailable
        // A newer Robot's key file: an older one can't vouch for its plain form, so it stays out of this backup, and (C12's
        // L6) Back up now names its connection, as the Keys row counted it.
        is ConnKeyRead.TooNew -> ConnKeyBackup.Unavailable
    }

    /**
     * C10, a restore from your own backup (BackupSecrets only): [plain] (a backup's entry, core ConnSecretsCodec's form)
     * saved as connection [id]'s secrets, with a fresh Keystore key, only when they read whole, hold exactly [parts] (the
     * parts that connection needs) and none is saved here. False when nothing was saved. The caller zeroes [plain].
     */
    fun restore(ctx: Context, id: String, plain: ByteArray, parts: Set<app.heyrobot.core.connect.SecretPart>): Boolean {
        if (ConnKeyFiles.saved(ctx, id)) return false
        val s = (ConnSecretsCodec.read(plain) as? SecretsFile.Ok)?.secrets ?: return false
        if (s.parts != parts) return false
        return save(ctx, id, s)
    }

    /** Changes when the key file does. */
    fun stamp(ctx: Context, id: String): Long = ConnKeyFiles.store(id).stamp(ctx)

    /** A secret [value] of connection [id] was just pasted into a window with the focus: the clipboard is cleared if it still holds exactly it. */
    fun clearClipboard(ctx: Context, id: String, value: String) = ConnKeyFiles.store(id).clearClipboard(ctx, value)

    private const val DAMAGED = "damaged" // l10n: not text (a code for the log)
}
