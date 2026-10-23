// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.os.storage.StorageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.files.Deleted
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileKeyException
import app.heyrobot.core.files.FileKeyProblem
import app.heyrobot.core.files.FileKeys
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.files.FileSeal
import app.heyrobot.core.files.FileSealReader
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.FileUsage
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.FilesSettingsCodec
import app.heyrobot.core.files.FilesSettingsFile
import app.heyrobot.core.files.FilesSettingsWrite
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.Recovery
import app.heyrobot.core.files.Resealed
import app.heyrobot.core.files.Staged
import app.heyrobot.core.files.TravelMode
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// MAIN AND VOICE PROCESSES. Robot Files' vault (files-1.4-plan.md §2, F2): the one Keystore key, robot_files_v1, behind
// core's FileKeys; one FileStore per process ("main", "voice") on files/files/; and files_settings.json.
//
// The key: AES-256-GCM in the Android Keystore (TEE; not StrongBox, which is slow for this, and no user authentication,
// as memory's), made with setUnlockedDeviceRequired(true) and randomized encryption, never exported. It never sees a
// file's bytes: it wraps each sealed stream's fresh 32-byte key (12-byte IV from the Keystore, 32 bytes, 16-byte tag: 60
// bytes). An existing alias is never replaced: robot_files_v1 is made only when the Keystore has no such alias, and only
// when core asks (create, for a cabinet with nothing sealed yet, under files.lock, which both processes take). A key
// Android invalidated is GONE; a tag that fails is BAD_DATA; anything else (the phone locked, the Keystore busy) is
// LOCKED, "not now": nothing here ever deletes a file or the key by itself.
//
// The vault: the index is read once per process while the phone is unlocked and kept in RAM (FileStore's cache). That
// cache is the one place the key isn't asked again, so every way to the store goes through [FilesVault]'s gate: while the
// phone is locked (the lock screen up, a phone lock on, or the device locked) the cache is dropped and the answer is
// Locked. At every screen-off the cache is dropped too (D2; at every screen-off, not only a locked one: with a lock delay
// the phone locks some seconds later, with no broadcast). recover() runs at the process's start (once the user has
// unlocked since boot: files/ is credential-encrypted storage) and after each unlock. Free space is StorageManager's
// allocatable bytes. Nothing of a file is logged: codes and counts only.

/** The Keystore calls [KeystoreFileKeys] makes: the phone's AndroidKeyStore, or a software stand-in in the tests. */
interface FileKeystore {
    fun contains(alias: String): Boolean
    fun key(alias: String): SecretKey?
    fun generate(spec: KeyGenParameterSpec): SecretKey
    fun delete(alias: String)
}

/** The Android Keystore. */
class AndroidFileKeystore : FileKeystore {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun contains(alias: String): Boolean = store().containsAlias(alias)
    override fun key(alias: String): SecretKey? = store().getKey(alias, null) as? SecretKey
    override fun generate(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
    override fun delete(alias: String) = store().deleteEntry(alias)
}

/** robot_files_v1 (core FileKeys' contract): every failure is a [FileKeyException]. */
class KeystoreFileKeys(private val keystore: FileKeystore = AndroidFileKeystore()) : FileKeys {
    override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray = keyed {
        require(key.size == KEY)
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, key(create))
        c.updateAAD(aad)
        val sealed = c.doFinal(key)
        val iv = c.iv
        // The Keystore's own random 12-byte IV, then the key and the tag: 60 bytes, or nothing (not now).
        check(iv != null && iv.size == IV && sealed.size == KEY + TAG)
        iv + sealed
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        if (wrapped.size != FileSeal.WRAPPED) throw FileKeyException(FileKeyProblem.BAD_DATA)
        return keyed {
            val c = Cipher.getInstance(GCM)
            c.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG * 8, wrapped, 0, IV))
            c.updateAAD(aad)
            c.doFinal(wrapped, IV, wrapped.size - IV).also { if (it.size != KEY) throw FileKeyException(FileKeyProblem.BAD_DATA) }
        }
    }

    /** Delete them all, after the key was lost (or by your choice): the next sealed stream makes a new one. */
    override fun delete() = keyed { keystore.delete(ALIAS) }

    /** robot_files_v1: the Keystore's when it has the alias (never made again over it), else a new one only when [create]. */
    private fun key(create: Boolean): SecretKey = synchronized(LOCK) {
        when {
            keystore.contains(ALIAS) -> keystore.key(ALIAS) ?: throw FileKeyException(FileKeyProblem.GONE)
            create -> keystore.generate(spec())
            else -> throw FileKeyException(FileKeyProblem.GONE)
        }
    }

    private inline fun <T> keyed(block: () -> T): T = try {
        block()
    } catch (t: Exception) {
        throw t as? FileKeyException ?: FileKeyException(problemOf(t), t)
    }

    companion object {
        const val ALIAS = "robot_files_v1"
        private const val GCM = "AES/GCM/NoPadding"
        private const val IV = 12
        private const val KEY = 32
        private const val TAG = 16
        private val LOCK = Any()

        /** How robot_files_v1 is made (§2.3): AES-256-GCM, unlock required, randomized; TEE, no user authentication. */
        fun spec(): KeyGenParameterSpec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .build()

        /**
         * What a Keystore failure means, from the exception and its causes (Android may wrap an invalidated key's
         * exception, for one in a getKey's UnrecoverableKeyException): invalidated is GONE, a tag that fails BAD_DATA,
         * anything else LOCKED ("not now", never a reason to drop anything).
         */
        fun problemOf(t: Throwable): FileKeyProblem {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 8) {
                when (c) {
                    is FileKeyException -> return c.problem
                    is KeyPermanentlyInvalidatedException -> return FileKeyProblem.GONE
                    is AEADBadTagException -> return FileKeyProblem.BAD_DATA
                }
                c = c.cause
            }
            return FileKeyProblem.LOCKED
        }
    }
}

/**
 * Robot Files on [dir] for one process: [FileStore] behind the lock gate. [locked]: the phone is locked now (then nothing
 * is read, not even from RAM). Pure java.io around core, so the JVM tests run it with a software key.
 */
class FilesVault(
    val dir: File,
    keys: FileKeys,
    process: String,
    space: () -> Long = { dir.usableSpace },
    private val locked: () -> Boolean,
    /** FX3: the restore's staging folder for Robot Files (FileStore's restoreStaging); the phone's vault passes it. */
    restoreStaging: File? = null,
) {
    private val store = FileStore(dir, keys, process, space, restoreStaging = restoreStaging)

    /** Whether Robot Files was ever used here (until then nothing is made on disk, not even the folder). */
    fun exists(): Boolean = dir.isDirectory

    /** The index as this phone can read it now: Locked while the phone is (the copy in RAM dropped first). */
    fun load(): IndexLoad = gated(IndexLoad.Locked) { if (exists()) store.load() else IndexLoad.Empty }

    /** One change of entries (FileStore.update); Locked while the phone is. */
    fun update(change: (FileIndex) -> FileIndex): IndexLoad = gated(IndexLoad.Locked) { if (exists()) store.update(change) else IndexLoad.Empty }

    /** What the sealed files take on storage: no key and nothing decrypted, so also while locked (Settings' row). */
    fun usage(): FileUsage = if (exists()) store.usage() else FileUsage(0, 0)

    /**
     * What a crash cut short, finished (at the process's start and after each unlock). While locked only the parts that
     * need no key run (a delete's keys and bytes); the rest waits for the next unlock. Null when there is nothing here.
     */
    fun recover(): Recovery? {
        if (!exists()) return null
        if (locked()) store.drop()
        return try { store.recover() } catch (t: Exception) { RLog.w("files_recover", t); null }
    }

    /** The index leaves RAM (screen-off, a change from the other process, Delete them all). */
    fun drop() = store.drop()

    // Adding and reading (F5): the same gate. Locked, each throws FileException(LOCKED) and the copy in RAM is dropped.

    /**
     * Seals [input] into this process's incoming/ folder (FileStore.stage): not yet in the index, so [commit] or [discard]
     * it on every way out. [declared]: the sender's claimed size. Throws [FileException].
     */
    fun stage(input: InputStream, declared: Long? = null): Staged = gatedOrThrow { store.stage(input, declared) }

    /**
     * [staged] gets its entry in Inbox (FileStore.commit). Locked, the staged file is discarded and LOCKED thrown: the add
     * failed whole (a staged file left behind would land in Inbox at the next start without your answer).
     */
    fun commit(staged: Staged, intake: FileIntake): FileEntry {
        if (locked()) {
            store.drop()
            discard(staged)
            throw FileException(FileProblem.LOCKED)
        }
        return store.commit(staged, intake)
    }

    /** [staged] isn't wanted: its key destroyed, its bytes removed. Needs no key, so not gated: it works after the phone locks. */
    fun discard(staged: Staged) = try { store.discard(staged) } catch (t: Exception) { RLog.w("files_discard", t) }

    /** The entry whose bytes have [sha256] ("You already have this file"); null when none, or while locked. */
    fun duplicateOf(sha256: String): FileEntry? = gated(null) { if (exists()) store.duplicateOf(sha256) else null }

    /** File [id]'s bytes as they stream (FileStore.open). Throws [FileException]. Close it when done. */
    fun open(id: FileId): FileSealReader = gatedOrThrow { store.open(id) }

    /** [id]'s thumbnail, or null when it has none. Throws [FileException]. */
    fun thumb(id: FileId): ByteArray? = gatedOrThrow { store.thumb(id) }

    /** Seals [bytes] (a thumbnail :files made) as [id]'s. Throws [FileException]. */
    fun putThumb(id: FileId, bytes: ByteArray) = gatedOrThrow { store.putThumb(id, bytes) }

    /**
     * Deletes [ids] (FileStore.delete: journaled, each file's wrapped keys destroyed, then its bytes, then its entry): F7's
     * Delete, after its dialog (your tap, refused while locked) and its 10-second Undo. Deliberately not gated, as
     * [discard]: it needs no key, and a delete you confirmed must still happen when the phone locks during its Undo (the
     * entries then leave the index at the next unlock, FileStore's journal hiding them until then). The RAM copy is
     * dropped while locked. Null when there is no Robot Files here.
     */
    fun delete(ids: Collection<FileId>): Deleted? {
        if (!exists()) return null
        if (locked()) store.drop()
        return store.delete(ids)
    }

    /** Delete them all (Settings › Files, after its dialog): every file and robot_files_v1. Refused while locked. */
    fun deleteAll(): Boolean = gated(false) {
        if (exists()) store.deleteAll()
        true
    }

    /** Start over after an index Robot can't read (damaged, or a newer Robot's); null when that isn't the case, or locked. */
    fun startOver(): Recovery? = gated(null) { if (exists()) store.startOver() else null }

    // F11, a restore (assistant/backup RestoreWriter, in :voice): the same gate. Its staging folder is the restore's own,
    // never this one; each file and the entries it adds are sealed there under robot_files_v1 with fresh keys.

    /** Ready to seal restored files (FileStore.prepareRestore): the index as it is now. Throws [FileException]. */
    fun prepareRestore(): FileIndex = gatedOrThrow { store.prepareRestore() }

    /** One restored file sealed into [staging] (FileStore.sealRestored). Throws [FileException]. */
    fun sealRestored(staging: File, id: FileId, input: InputStream): Resealed = gatedOrThrow { store.sealRestored(staging, id, input) }

    /** The entries a restore adds, sealed into [file]. Throws [FileException]. */
    fun sealRestoreIndex(file: File, index: FileIndex) = gatedOrThrow { store.sealRestoreIndex(file, index) }

    /** The entries [sealRestoreIndex] sealed. Throws [FileException]. */
    fun readRestoreIndex(file: File): FileIndex = gatedOrThrow { store.readRestoreIndex(file) }

    /** A restore's repeatable commit (FileStore.restoreIn). Throws [FileException] when it can't now. */
    fun restoreIn(staging: File, add: List<FileEntry>, delete: Set<FileId>, extra: (Map<String, Any?>) -> Map<String, Any?>): Int =
        gatedOrThrow { store.restoreIn(staging, add, delete, extra) }

    /** What a restore left in [staging] destroyed. Needs no key, so not gated (as [discard]). */
    fun discardRestore(staging: File) = try { store.discardRestore(staging) } catch (t: Exception) { RLog.w("files_restore_discard", t) }

    private inline fun <T> gated(whenLocked: T, block: () -> T): T {
        if (locked()) {
            store.drop()
            return whenLocked
        }
        return block()
    }

    private inline fun <T> gatedOrThrow(block: () -> T): T {
        if (locked()) {
            store.drop()
            throw FileException(FileProblem.LOCKED)
        }
        return block()
    }
}

/** Robot Files in this process: its vault, the screen-off and unlock hooks, and files_settings.json. */
object RobotFiles {
    /** The processes that may hold Robot Files: never :brain, :llama, :mt or :lock. */
    private val PROCESSES = setOf("main", "voice")

    @Volatile private var vault: FilesVault? = null
    @Volatile private var started = false
    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-files").apply { isDaemon = true } } }

    /** files/files: credential-encrypted storage, and every file in it sealed again under robot_files_v1. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "files")

    /** This process's name for its incoming/ folder ("main", "voice"), or null for one that may not hold files. */
    fun process(): String? = Application.getProcessName().let { if (it.contains(':')) it.substringAfter(':') else "main" }.takeIf { it in PROCESSES }

    /** This process's vault (one FileStore per process). Throws in a process that may not hold files. */
    fun vault(ctx: Context): FilesVault = vault ?: synchronized(this) {
        vault ?: run {
            val app = ctx.applicationContext
            val p = checkNotNull(process())
            FilesVault(dir(app), KeystoreFileKeys(), p, space = { allocatable(app) }, locked = { locked(app) },
                restoreStaging = RestoreFiles.journal(app).staging(BackupCategory.FILES)).also { vault = it }
        }
    }

    /** The phone is locked for Robot Files: the lock screen is up, a phone lock is on, or the device is locked. */
    fun locked(ctx: Context): Boolean = Assistant.locked(ctx) ||
        try { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }

    /**
     * At the process's start (RobotApp, main and :voice only): the screen-off and unlock hooks, then, in :voice (which
     * files in the background), recover() once the user has unlocked since boot. The main app reads the index only once
     * Robot Files is used in it ([use]: the Files tab, Settings › Files, the share window, a scan, a transcript saved; F0a,
     * F7): recover() reads the index into RAM, so it runs there at the first use and after each unlock from then on.
     */
    fun start(app: Application) {
        if (process() == null || started) return
        started = true
        val main = process() == "main"
        try {
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            vault(app).drop()
                            // F12: hidden drawers shown with your PIN go out of sight again.
                            TravelReveal.end()
                            // F7: a copy handed to another app goes at screen-off, its grant revoked.
                            if (main) FilesOut.revokeAll(app)
                            // FX2 (K1): a delete waiting for its Undo goes through now (its Undo can't be tapped from here).
                            FileDeletes.here.commitAll()
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            if (!main || used) worker.execute { recover(app) }
                            // FX2 (K3): a plain copy or a scan's photo left by a main app Android ended goes after 15 minutes.
                            if (!main) worker.execute { clearStale(app) }
                        }
                    }
                }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) { RLog.w("files_hooks", t) }
        if (!main) worker.execute { if (userUnlocked(app)) { recover(app); clearStale(app) } }
        // F5: a scan's photo the camera wrote just before a crash never stays in the cache; F7: nor a copy handed to another app.
        if (main) worker.execute { if (userUnlocked(app)) { ScanFiles.clear(app); FilesOut.clear(app) } }
    }

    /** The main app uses Robot Files now: the first time in this process, what a crash cut short is finished (recover). */
    fun use(ctx: Context) {
        if (used || process() != "main") return
        used = true
        val app = ctx.applicationContext
        worker.execute { if (userUnlocked(app)) recover(app) }
    }

    /** Robot Files was used in this process (the main app reads the index only from then on). */
    @Volatile private var used = false

    /**
     * F12: the index as travel mode shows it in this process (core TravelMode.shown): without the hidden drawers' files while
     * travel mode is on, unless you showed them with your PIN here ([TravelReveal], the main app only). Null while locked or
     * when it can't be read. Every reader that shows, says or counts files goes through this; filing, backups and restores
     * read the whole index.
     */
    fun shown(ctx: Context): FileIndex? = vault(ctx).load().current?.let { TravelMode.shown(it, FilesSettingsStore.current(ctx), TravelReveal.on()) }

    /** Main changed Robot Files (Cmd.FILES: Delete them all, Start over, its settings): this process reads them again. */
    fun changed(ctx: Context) {
        vault(ctx).drop()
        FilesSettingsStore.forget()
    }

    private fun recover(app: Context) {
        val r = try { vault(app).recover() } catch (t: Throwable) { RLog.w("files_recover", t); null } ?: return
        RLog.i("files_recover", "deletes" to r.deletesFinished, "moved" to r.movedIn, "adopted" to r.adopted, "parts" to r.partsRemoved,
            "thumbs" to r.thumbsRemoved, "missing" to r.missing, "waiting" to r.waiting)
    }

    /** FX2 (files-1.4-review.md K3), in :voice: copies handed out and scans' photos older than 15 minutes, deleted. */
    private fun clearStale(app: Context) {
        try {
            val n = FilesOut.clearStale(app) + ScanFiles.clearStale(app)
            if (n > 0) RLog.i("files_out_stale", "removed" to n)
        } catch (t: Throwable) { RLog.w("files_out_stale", t) }
    }

    private fun userUnlocked(ctx: Context): Boolean = try { ctx.getSystemService(UserManager::class.java).isUserUnlocked } catch (_: Throwable) { false }

    /** The bytes that can be allocated on Robot's storage now (FileStore falls back to the folder's usable space). */
    private fun allocatable(app: Context): Long {
        val sm = app.getSystemService(StorageManager::class.java)
        return sm.getAllocatableBytes(sm.getUuidForPath(app.filesDir))
    }
}

/**
 * F12, travel mode: hidden drawers shown again after you confirmed with the phone's PIN (KeyguardManager's confirm screen,
 * Settings › Files' "Show hidden drawers"), in the main app's Files screens and Settings › Files only, in this process's RAM:
 * for [MS] at most, and never past the screen going off (RobotFiles' hook) or travel mode being turned on again. :voice
 * (the panel, Robot's voice, the brief) never has one: there hidden drawers stay out of sight while travel mode is on.
 */
object TravelReveal {
    /** How long hidden drawers show after your PIN: 10 minutes, unless the screen goes off first. */
    const val MS = 10 * 60_000L

    @Volatile private var until = 0L

    fun on(now: Long = System.currentTimeMillis()): Boolean = now < until

    /** Your PIN was confirmed just now: in the main app only. */
    fun start(now: Long = System.currentTimeMillis()) {
        if (RobotFiles.process() == "main") until = now + MS
    }

    fun end() {
        until = 0L
    }
}

/**
 * Settings › Files' values (core FilesSettings) in files/robot/files_settings.json, never in settings.json. The main app
 * writes it; both app processes read it, again whenever the file changed. A file from a newer Robot is never written over.
 */
object FilesSettingsStore {
    @Volatile private var cached: Pair<Long, FilesSettingsFile>? = null

    fun file(ctx: Context): File = File(Paths.dir(ctx), FilesSettingsCodec.FILE)

    private fun stamp(f: File): Long = if (f.isFile) f.lastModified() * 31 + f.length() else 0L

    /** The file as read (the defaults when there is none; a damaged one's defaults apply). */
    fun read(ctx: Context): FilesSettingsFile {
        val f = file(ctx)
        val s = stamp(f)
        cached?.let { (at, read) -> if (at == s) return read }
        return FilesSettingsCodec.decode(readOrNull(f)).also { cached = s to it }
    }

    fun current(ctx: Context): FilesSettings = read(ctx).settings

    /**
     * FX1 (files-1.4-review.md finding 2): the lock every write of the file takes, across the main app (Settings › Files) and
     * :voice (a restore's finish), so each reads the file as it is and writes over it in one step. Inside a restore's
     * journal lock, never around it.
     */
    fun lock(ctx: Context): File = File(Paths.dir(ctx), "files_settings.lock") // l10n: not text (a file name)

    /**
     * [MAIN] Writes [s] (keys this version doesn't know kept), then tells :voice. [pin]: your PIN just confirmed it
     * (turning travel mode off, unhiding a drawer while it is on); without it, travel mode as the file has it now stays on
     * (core FilesSettingsWrite). False when nothing was written: a newer Robot's file changed beyond travel mode, travel
     * mode on with its drawers unknown, or a failed write.
     */
    fun save(ctx: Context, s: FilesSettings, pin: Boolean = false): Boolean = write(ctx) { raw -> FilesSettingsWrite.save(raw, s, pin) }

    /**
     * [MAIN] FX3: Settings › Files' change: [change] applied to the file as it is now, under [lock] (core
     * FilesSettingsWrite.update), so only the fields it changes are written; a restore's finish that landed meanwhile
     * stays. [pin] as for [save].
     */
    fun update(ctx: Context, pin: Boolean = false, change: (FilesSettings) -> FilesSettings): Boolean =
        write(ctx) { raw -> FilesSettingsWrite.update(raw, pin, change) }

    /** [MAIN] FX3: your drawer [old] renamed [new]: hidden under the new name in the old one's place (core FilesSettingsWrite.rename). */
    fun rename(ctx: Context, old: String, new: String): Boolean = write(ctx) { raw -> FilesSettingsWrite.rename(raw, old, new) }

    /** The text [next] makes of the file as it is (null: nothing written), written under [lock]; then :voice is told. */
    private fun write(ctx: Context, next: (String?) -> String?): Boolean {
        val app = ctx.applicationContext
        val f = file(app)
        val ok = withFileLock(lock(app)) {
            val text = next(readOrNull(f)) ?: return@withFileLock false
            try {
                writeAtomic(f, text)
                true
            } catch (t: Exception) {
                RLog.w("files_settings_save", t)
                false
            } finally {
                cached = null
            }
        }
        if (ok) Cmd.send(app, Cmd.FILES)
        return ok
    }

    /**
     * [VOICE] A restore's finish (RestoreWriter): the backup's [staged] Settings › Files merged over the file as it is now,
     * under [lock] (core RestoreSafety.filesSettingsFile). False when nothing was written.
     */
    fun restore(ctx: Context, staged: String?, mine: Boolean): Boolean = withFileLock(lock(ctx)) {
        val f = file(ctx)
        val text = RestoreSafety.filesSettingsFile(readOrNull(f), staged, mine) ?: return@withFileLock false
        try {
            writeAtomic(f, text)
            true
        } catch (t: Exception) {
            RLog.w("files_settings_restore", t)
            false
        } finally {
            cached = null
        }
    }

    /** Read the file again next time (Cmd.FILES). */
    fun forget() {
        cached = null
    }
}
