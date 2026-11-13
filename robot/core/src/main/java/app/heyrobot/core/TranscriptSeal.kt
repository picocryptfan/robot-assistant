// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

// MAIN AND VOICE PROCESSES. seal-transcripts (docs/plans/seal-stores-design.md, question 3: "a sixth job after History,
// using the same frame"). Conversation mode's transcripts under Robot's own seal, with History's key rules: everything
// written while the phone is unlocked is sealed under robot_transcripts_v1 (unlock only); while it is locked (a session
// started from the lock screen, or one that goes on after the screen locks) under robot_transcripts_lock_v1, re-sealed
// under the unlock key at the next unlocked list (TranscriptStore.settle). Each file (meta.json, snapshot.json, the
// summaries and the draft) is one sealed text line; the log stays a file of lines, each record sealed on its own, so a
// session still appends and fsyncs one line at a time. A line is "~seal:" and the base64 of History's frame
// (SealedEnvelope: magic, version, the key's alias, then AES-256-GCM), with "<id>/<file>" as additional data, so a
// record never opens under another transcript or file. A log runs to thousands of lines, so as § 2 says for big stores
// each Keystore key seals a random data key (<alias>.key in the folder), opened once per process and dropped from RAM
// when the screen goes off, and the unlock key's is never used while the phone is locked. Audio (audio.ogg) is not sealed:
// nothing in this Robot writes it yet ("Keep audio" isn't built), so there is no reader or writer to seal.

/** Transcripts' two Keystore keys, named here alone. */
object TranscriptKeyAliases {
    const val UNLOCKED = "robot_transcripts_v1"
    const val LOCK_SCREEN = "robot_transcripts_lock_v1"
    val ALL = setOf(UNLOCKED, LOCK_SCREEN)
}

/**
 * fix-seal-transcripts-b, Medium 1: the Keystore failed on an unlocked phone (KeyStore.load, getKey). Not now and never gone, so nothing is set aside or deleted ([problem] stays LOCKED for every caller that only
 * keeps things), but not "unlock" either: settle goes on past the folder, an append says KEY, a backup has its own words.
 */
class TranscriptKeyFault(cause: Throwable?) : MemoryKeyException(KeyProblem.LOCKED, cause)

/**
 * fix-seal-transcripts-c, Low 3: a record a newer Robot wrote (a downgrade, a later record format). Left as it is, unread:
 * LOCKED to every caller that only keeps things, never set aside, never a fault; the screen says a newer Robot wrote it.
 */
class TranscriptTooNew : MemoryKeyException(KeyProblem.LOCKED)

/**
 * The seal of one transcripts folder ([root]). [keystore] gives a Keystore key (null: none under that alias) and throws a
 * [MemoryKeyException] saying why it can't; [dropKeystore] deletes one; [locked] says whether the phone is locked now
 * (unknown counts as locked).
 */
class TranscriptSeal(
    private val root: File,
    private val keystore: (alias: String, create: Boolean) -> MemoryCipher?,
    private val dropKeystore: (String) -> Unit,
    val locked: () -> Boolean,
) {
    /** A data key in RAM with its key file's time: one the other process replaced is opened again (fix-seal-transcripts, Low 7). */
    private class Cached(val cipher: MemoryCipher, val stamp: Long)
    private val data = ConcurrentHashMap<String, Cached>()
    /** Aliases whose Keystore key the Keystore itself said is gone: only these are deleted by [replace]. */
    private val keystoreGone = ConcurrentHashMap.newKeySet<String>()

    fun isSealed(text: String): Boolean = text.startsWith(MARK)

    /** The alias a sealed line's header names, or null (plain, damaged). No key is asked for. */
    fun aliasOf(line: String): String? = if (!isSealed(line)) null else try { frame.alias(decode(line)) } catch (_: Exception) { null }

    /** [text] sealed for [name] ("<id>/<file>") as one line, under the key the phone's state allows now (or [alias]). */
    fun seal(name: String, text: String, alias: String = if (locked()) TranscriptKeyAliases.LOCK_SCREEN else TranscriptKeyAliases.UNLOCKED): String = faults {
        // fix-seal-transcripts-b, Low 4: a lost key is made again once (as settle does), so a restore or a live session goes on.
        val k = try { dataKey(alias, create = true) } catch (e: MemoryKeyException) {
            if (e.problem != KeyProblem.GONE) throw e
            replace(alias)
            dataKey(alias, create = true)
        } ?: throw MemoryKeyException(KeyProblem.LOCKED) // never a plain line: no key is not now, not "write it plain"
        MARK + Base64.getEncoder().encodeToString(frame.seal(text.toByteArray(UTF_8), k, name))
    }

    /** A sealed line opened; throws a [MemoryKeyException]: LOCKED not now, GONE its key is lost, BAD_DATA damaged or not this file's. */
    fun open(name: String, line: String): String = faults {
        val bytes = try { decode(line) } catch (_: Exception) { throw MemoryKeyException(KeyProblem.BAD_DATA) }
        // fix-seal-transcripts-d, Low 3: the frame says only Locked for a newer Robot's key file, so its own word is kept.
        var newerKey: TranscriptTooNew? = null
        when (val o = frame.open(bytes, { a -> if (a !in TranscriptKeyAliases.ALL) throw MemoryKeyException(KeyProblem.BAD_DATA) else
            try { busy { dataKey(a, create = false) } } catch (e: TranscriptTooNew) { newerKey = e; throw e } }, name) { it }) {
            is SealedOpen.Ok -> String(o.value, UTF_8)
            SealedOpen.Locked -> throw newerKey ?: MemoryKeyException(KeyProblem.LOCKED)
            SealedOpen.KeyGone -> throw MemoryKeyException(KeyProblem.GONE)
            is SealedOpen.TooNew -> throw TranscriptTooNew()
            else -> throw MemoryKeyException(KeyProblem.BAD_DATA)
        }
    }

    /** The screen went off: the data keys' copies in RAM forgotten (the next use opens them through the Keystore again). */
    fun drop() = data.clear()

    /**
     * [alias]'s key can never open again (Android invalidated it, or its key file is damaged): the key file kept aside
     * (never deleted), so the next seal makes a new one. The Keystore key is deleted only when the Keystore said it is gone
     * (fix-seal-transcripts, High 2): a key file that failed alone leaves it as it is.
     */
    fun replace(alias: String) {
        keyLock {
            val f = keyFile(alias)
            if (f.exists()) for (n in 1..9) { val to = File(root, f.name + if (n == 1) ".bad" else ".bad$n"); if (!to.exists()) { f.renameTo(to); break } }
            if (keystoreGone.remove(alias)) try { dropKeystore(alias) } catch (_: Exception) {}
            data.remove(alias)
        }
    }

    /** fix-seal-transcripts-b, Low 5: [locked] (a binder call) asked once for a whole file's records, not once per record. */
    fun <T> reading(block: () -> T): T {
        if (readLocked.get() != null) return block()
        readLocked.set(locked())
        try { return block() } finally { readLocked.remove() }
    }

    private val readLocked = ThreadLocal<Boolean?>()
    private fun lockedNow(): Boolean = readLocked.get() ?: locked()

    @Volatile private var faultLogged = false

    /**
     * Medium 1: LOCKED while the phone is unlocked is a fault, logged once per process (the error's class and message, no
     * text). fix-seal-transcripts-c, Low 4: only when the phone is unlocked both as [reading] saw it and now, so a lock
     * change in mid-read is never the process's one fault.
     */
    private inline fun <T> faults(block: () -> T): T = try { block() } catch (e: MemoryKeyException) {
        if (e.problem != KeyProblem.LOCKED || e is TranscriptKeyFault || e is TranscriptTooNew || lockedNow() || locked()) throw e
        val cause = e.cause ?: e
        if (!faultLogged) { faultLogged = true; RLog.w("transcripts_key_fault", cause, "message" to cause.message?.take(160)) } // l10n: not text (a log code)
        throw TranscriptKeyFault(cause)
    }

    private fun keyFile(alias: String) = File(root, ".$alias.key")

    /** [alias]'s data key: opened from its file, or made (with its file) when [create] and there is none. */
    private fun dataKey(alias: String, create: Boolean): MemoryCipher? {
        // fix-seal-transcripts, Medium 3: the unlock key's data key is never used while the phone is locked, even from RAM
        // (a session that goes on in the lock delay, Smart Lock): forgotten, and LOCKED.
        if (alias == TranscriptKeyAliases.UNLOCKED && lockedNow()) { data.remove(alias); throw MemoryKeyException(KeyProblem.LOCKED) }
        val f = keyFile(alias)
        data[alias]?.takeIf { it.stamp == f.lastModified() }?.let { return it.cipher }
        return keyLock {
            data[alias]?.takeIf { it.stamp == f.lastModified() }?.let { return@keyLock it.cipher }
            val raw: ByteArray? = if (f.isFile) {
                val bytes = busy { f.readBytes() }
                when (val o = keyFrame.open(bytes, { a -> if (a != alias) throw MemoryKeyException(KeyProblem.BAD_DATA) else ks(a, false) }, f.name) { it }) {
                    is SealedOpen.Ok -> o.value
                    SealedOpen.Locked -> throw MemoryKeyException(KeyProblem.LOCKED)
                    // The Keystore says its key is gone: what it sealed never opens again, and replace() deletes it.
                    SealedOpen.KeyGone -> { keystoreGone += alias; throw MemoryKeyException(KeyProblem.GONE) }
                    // A newer Robot's key file: never a reason to drop anything. fix-seal-transcripts-d, Low 3: what it seals is
                    // a newer Robot's record (left out of a backup and counted), never the process's Keystore fault.
                    is SealedOpen.TooNew -> throw TranscriptTooNew()
                    // The key file itself is damaged (or not this alias's): it goes aside, the Keystore key stays.
                    else -> throw MemoryKeyException(KeyProblem.GONE)
                }
            } else if (!create) null else {
                val k = ByteArray(32).also { random.nextBytes(it) }
                val kc = ks(alias, true) ?: throw MemoryKeyException(KeyProblem.GONE).also { keystoreGone += alias }
                root.mkdirs()
                val tmp = File(root, ".${f.name}.tmp")
                val sealedKey = try { keyFrame.seal(k, kc, f.name) } catch (e: MemoryKeyException) {
                    if (e.problem == KeyProblem.GONE) keystoreGone += alias
                    throw e
                }
                busy { FileOutputStream(tmp).use { it.write(sealedKey); it.fd.sync() } }
                if (!tmp.renameTo(f)) { tmp.delete(); throw MemoryKeyException(KeyProblem.LOCKED) }
                k
            }
            raw?.takeIf { it.size == 32 }?.let { DataCipher(alias, it) }?.also { data[alias] = Cached(it, f.lastModified()) }
                ?: if (raw == null) null else throw MemoryKeyException(KeyProblem.GONE)
        }
    }

    /**
     * fix-seal-transcripts, High 2: the Keystore's key for [alias], its every use included. Only the Keystore's own word
     * (a [MemoryKeyException]) says GONE or BAD_DATA; any other error (KeyStore.load, getKey, a busy keystore2) is LOCKED,
     * not now, and never a reason to set a transcript aside or delete a key.
     */
    private fun ks(alias: String, create: Boolean): MemoryCipher? = busy { keystore(alias, create) }?.let { c ->
        object : MemoryCipher {
            override val alias = c.alias
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = busy { c.seal(plain, aad) }
            override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = busy { c.open(sealed, aad) }
        }
    }

    private inline fun <T> busy(block: () -> T): T = try { block() } catch (e: MemoryKeyException) { throw e } catch (e: Exception) {
        throw MemoryKeyException(KeyProblem.LOCKED, e)
    }

    /** Both processes make a data key under this lock, so neither writes over a key the other already sealed with. */
    private fun <T> keyLock(block: () -> T): T = synchronized(this) {
        root.mkdirs()
        RandomAccessFile(File(root, ".keys.lock"), "rw").use { raf -> raf.channel.lock().use { block() } } // l10n: not text (a file name)
    }

    private fun decode(line: String): ByteArray = Base64.getDecoder().decode(line.trimEnd().removePrefix(MARK))

    /** AES-256-GCM in this process with a data key: a random 12-byte IV, then the text and its tag. */
    private class DataCipher(override val alias: String, raw: ByteArray) : MemoryCipher {
        private val key = SecretKeySpec(raw, "AES") // l10n: not text (an algorithm name)

        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }
            return iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            if (sealed.size < 12 + 16) throw MemoryKeyException(KeyProblem.BAD_DATA)
            return try {
                Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); updateAAD(aad) }
                    .doFinal(sealed, 12, sealed.size - 12)
            } catch (e: AEADBadTagException) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
        }
    }

    companion object {
        /** The start of a sealed line (plain JSON starts with "{"). */
        const val MARK = "~seal:" // l10n: not text (a file format's marker)
        private const val GCM = "AES/GCM/NoPadding" // l10n: not text (an algorithm name)
        val frame = SealedEnvelope("RTRS", "transcript", 1) // l10n: not text (the record's magic and the additional data's kind)
        private val keyFrame = SealedEnvelope("RTRK", "transcript-key", 1) // l10n: not text (the key file's magic and kind)
        private val random = SecureRandom()
    }
}
