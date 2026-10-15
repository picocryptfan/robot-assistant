// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryEnvelope
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemoryVault
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import app.heyrobot.core.writeAtomicBytes
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Main and voice processes. What Robot remembers (0.7, design §A5, §A8, §A9): one file, files/memory/memory.enc,
// AES-256-GCM with an Android Keystore key (TEE, not exportable) made with setUnlockedDeviceRequired(true), so it can't be
// read while the phone is locked, not even after the first unlock; core MemoryEnvelope is its frame and decides what a
// file means. Every change happens under a lock both app processes take (memory.lock, as People's), written atomically
// (tmp, fsync, rename), and nothing is ever written over a file that couldn't be read: locked, its key gone, damaged, or
// written by a newer Robot. A Keystore error that may pass reads as "not now", never as "gone", and nothing here ever
// deletes the file by itself: Settings › Memory's Start over keeps it aside as memory.enc.bad, Delete all is your tap.
// "Use memory on the lock screen" (off) re-encrypts it with a second key made without the unlock requirement, and back.
// Excluded from backup and device transfer (allowBackup false, data_extraction_rules.xml). Nothing here logs an item.

/** Memory's two Keystore keys: the one that needs the phone unlocked (the default), and the lock screen's. */
object MemoryKeyAliases {
    const val UNLOCKED = "robot_memory_v1"
    const val LOCK_SCREEN = "robot_memory_lock_v1"
}

/** Memory's keys by alias (the Keystore on the phone, a software key in tests). */
interface MemoryKeys {
    /** The cipher for [alias]; null when there is no such key and [create] is false. Throws when the Keystore can't answer now. */
    fun cipher(alias: String, create: Boolean): MemoryCipher?
    fun delete(alias: String)
}

/**
 * The Android Keystore's AES-256-GCM keys. [UNLOCKED][MemoryKeyAliases.UNLOCKED] is made with setUnlockedDeviceRequired
 * (true); the lock screen's without it. A random 12-byte IV per seal (the Keystore's own), the additional data
 * authenticated. A tag that fails is BAD_DATA, a key Android invalidated is GONE, anything else LOCKED ("not now").
 */
class KeystoreMemoryKeys : MemoryKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun cipher(alias: String, create: Boolean): MemoryCipher? {
        val ks = store()
        val key = (ks.getKey(alias, null) as? SecretKey) ?: if (create) generate(alias) else return null
        return KeystoreCipher(alias, key)
    }

    override fun delete(alias: String) {
        try { store().deleteEntry(alias) } catch (t: Throwable) { RLog.w("memory_key_delete", t) }
    }

    private fun generate(alias: String): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(alias == MemoryKeyAliases.UNLOCKED)
            .build())
        return gen.generateKey()
    }

    private class KeystoreCipher(override val alias: String, private val key: SecretKey) : MemoryCipher {
        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = keyed {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(aad)
            val ct = c.doFinal(plain)
            require(c.iv.size == IV) { "iv" }
            c.iv + ct
        }

        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            if (sealed.size <= IV) throw MemoryKeyException(KeyProblem.BAD_DATA)
            return keyed {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV))
                c.updateAAD(aad)
                c.doFinal(sealed, IV, sealed.size - IV)
            }
        }

        private inline fun <T> keyed(block: () -> T): T = try {
            block()
        } catch (e: MemoryKeyException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw MemoryKeyException(KeyProblem.BAD_DATA, e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw MemoryKeyException(KeyProblem.GONE, e)
        } catch (e: Exception) {
            // Locked (the key needs the phone unlocked), or the Keystore busy: not now, and never a reason to drop anything.
            throw MemoryKeyException(KeyProblem.LOCKED, e)
        }
    }

    private companion object {
        const val IV = 12
        const val TAG_BITS = 128
    }
}

/**
 * The vault on [dir] (files/memory/ on the phone): memory.enc, its lock, and Start over's memory.enc.bad. [lockScreen]:
 * "Use memory on the lock screen" now, the key a new file gets. Pure java.io around [keys], so the JVM tests run it.
 */
class FileMemoryVault(
    private val dir: File,
    private val keys: MemoryKeys,
    private val lockScreen: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) : MemoryVault {
    val file: File get() = File(dir, MemoryEnvelope.FILE)

    /** Changes whenever the file does (a cheap check before a cache in RAM is trusted again). */
    fun stamp(): Long = file.let { if (it.isFile) it.lastModified() * 31 + it.length() else 0L }

    /** The key the file is sealed with now ([MemoryKeyAliases]), or null without a file. */
    fun alias(): String? = MemoryEnvelope.alias(readBytes())

    override fun load(): MemoryLoad = locked { open(readBytes()) }

    /**
     * One change under the lock: open, apply [change] (and the day-after sweep of items that ended), seal with the key the
     * file has (a new file: [lockScreen]'s), write. Nothing is written when the file couldn't be opened, or nothing changed.
     */
    override fun update(change: (MemoryFile) -> MemoryFile): MemoryLoad = locked {
        val bytes = readBytes()
        val existing = bytes != null && bytes.isNotEmpty()
        val seal = try {
            if (existing) MemoryEnvelope.alias(bytes)?.let { keys.cipher(it, create = false) } else keys.cipher(defaultAlias(), create = true)
        } catch (t: Throwable) {
            return@locked MemoryLoad.Locked
        } ?: return@locked open(bytes)
        var changed = false
        try {
            val (load, out) = MemoryEnvelope.update(bytes, seal, { a -> if (a == seal.alias) seal else null }) { f ->
                MemoryEdits.sweep(change(f), now()).also { changed = it != f }
            }
            if (out != null && changed) writeAtomicBytes(file, out)
            load
        } catch (e: MemoryKeyException) {
            if (e.problem == KeyProblem.GONE) MemoryLoad.KeyGone else MemoryLoad.Locked
        }
    }

    /** Settings › Memory › Delete all (after its confirm): the file, the one kept aside and the index; the keys stay. */
    override fun deleteAll(): Boolean = locked {
        listOf(MemoryEnvelope.FILE, BAD, INDEX).map { File(dir, it) }.all { !it.exists() || it.delete() }
    }

    /**
     * Start over after a file Robot can't read (damaged, its key gone): it is kept aside as memory.enc.bad (an older one
     * there is replaced) and memory starts empty. Only when the file really can't be opened now: never for a locked phone.
     */
    fun startOver(): Boolean = locked {
        val load = open(readBytes())
        if (load != MemoryLoad.Unreadable && load != MemoryLoad.KeyGone) return@locked false
        val bad = File(dir, BAD)
        bad.delete()
        file.renameTo(bad)
    }

    /**
     * "Use memory on the lock screen" turned on ([lockScreenKey]) or off: the file sealed again with the other key, which
     * is made if needed, and the old key deleted once the new file is written. Only from an unlocked phone; the result is
     * the load (Loaded or Empty: done; anything else: nothing changed).
     */
    fun rekey(lockScreenKey: Boolean): MemoryLoad = locked {
        val target = if (lockScreenKey) MemoryKeyAliases.LOCK_SCREEN else MemoryKeyAliases.UNLOCKED
        val other = if (lockScreenKey) MemoryKeyAliases.UNLOCKED else MemoryKeyAliases.LOCK_SCREEN
        val bytes = readBytes()
        if (bytes == null || bytes.isEmpty()) {
            keys.delete(other)
            return@locked MemoryLoad.Empty
        }
        val from = MemoryEnvelope.alias(bytes) ?: return@locked MemoryLoad.Unreadable
        if (from == target) return@locked open(bytes)
        val load = open(bytes)
        val f = load.current ?: return@locked load
        try {
            val sealer = keys.cipher(target, create = true) ?: return@locked MemoryLoad.Locked
            writeAtomicBytes(file, MemoryEnvelope.seal(f, sealer))
        } catch (e: MemoryKeyException) {
            return@locked MemoryLoad.Locked
        } catch (t: Throwable) {
            RLog.w("memory_rekey", t)
            return@locked MemoryLoad.Locked
        }
        keys.delete(from)
        load
    }

    private fun defaultAlias(): String = if (lockScreen()) MemoryKeyAliases.LOCK_SCREEN else MemoryKeyAliases.UNLOCKED

    /** [bytes] opened with the key their header names; a Keystore that can't answer now reads as locked, never as "gone". */
    private fun open(bytes: ByteArray?): MemoryLoad {
        if (bytes == null || bytes.isEmpty()) return MemoryLoad.Empty
        val alias = MemoryEnvelope.alias(bytes)
        val cipher = try { alias?.let { keys.cipher(it, create = false) } } catch (t: Throwable) { return MemoryLoad.Locked }
        return MemoryEnvelope.open(bytes, { a -> if (a == alias) cipher else null })
    }

    private fun readBytes(): ByteArray? = try { if (file.isFile) file.readBytes() else null } catch (_: Exception) { null }

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        dir.mkdirs()
        FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }

    companion object {
        const val LOCK_FILE = "memory.lock"
        const val BAD = "memory.enc.bad"
        /** The search model's vectors (phase 7.4), sealed with the same key; deleted with everything else. */
        const val INDEX = "index.enc"
        private val LOCK = Any()
    }
}

/** Where memory lives, in both app processes. */
object MemoryFiles {
    /** files/memory: credential-encrypted storage, and memory.enc encrypted again with the Keystore key. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "memory")

    fun vault(ctx: Context): FileMemoryVault {
        val app = ctx.applicationContext
        return FileMemoryVault(dir(app), KeystoreMemoryKeys(), lockScreen = { MemorySettingsStore.current(app).onLockScreen })
    }

    /** What happened to memory, for the voice log (codes only). */
    fun logLocked(load: MemoryLoad) {
        app.heyrobot.core.memory.MemLock.of(load)?.let { VoiceLog.add(VoiceEvent.MEM_LOCKED, it) }
    }
}

/**
 * Settings › Memory (core MemorySettings) in files/robot/memory.json, not in settings.json: [MAIN] writes it; both app
 * processes read it, and [current] reads it again whenever the file changed, so the voice process follows a change
 * without a message. [state] is what this process shows now; Compose reads it.
 */
object MemorySettingsStore {
    val state: MutableState<MemorySettings> = mutableStateOf(MemorySettings())

    @Volatile private var stamp = -1L

    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-memory-settings").apply { isDaemon = true } } }

    fun file(ctx: Context): File = File(Paths.dir(ctx), MemorySettings.FILE)

    /** The settings, read again when the file changed since the last read (a missing or damaged file gives the defaults). */
    fun current(ctx: Context): MemorySettings {
        val f = file(ctx)
        val s = if (f.isFile) f.lastModified() * 31 + f.length() else 0L
        if (s != stamp) {
            state.value = MemorySettings.fromJson(readOrNull(f))
            stamp = s
        }
        return state.value
    }

    /** [MAIN] Shows [s] at once and writes it on the writer thread; then the voice process drops what memory it holds. */
    fun save(ctx: Context, s: MemorySettings) {
        state.value = s
        val app = ctx.applicationContext
        val f = file(app)
        writer.execute {
            try { writeAtomic(f, s.toJson()) } catch (t: Throwable) { RLog.w("memory_settings_save", t) }
            stamp = if (f.isFile) f.lastModified() * 31 + f.length() else 0L
            Cmd.send(app, Cmd.MEMORY_CHANGED)
        }
    }

    /** Waits for the saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }
}
