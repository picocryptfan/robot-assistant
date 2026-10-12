package app.heyrobot.core

import java.io.File
import java.io.FileOutputStream

// Pure java.io: storage helpers shared by every process. Each file has one writer process; readers
// see either the old or the new content, never a half-written file.

/** Writes through a temporary file, fsyncs it and renames it over [f]. */
fun writeAtomic(f: File, text: String) = writeAtomicBytes(f, text.toByteArray(Charsets.UTF_8))

/** [writeAtomic] for bytes (the encrypted voiceprint): a crash leaves the old file or the new one, never half of one. */
fun writeAtomicBytes(f: File, bytes: ByteArray) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".${f.name}.tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    } catch (x: java.io.IOException) {
        tmp.delete()
        throw x
    }
    if (!tmp.renameTo(f)) {
        tmp.delete()
        throw java.io.IOException("rename failed")
    }
}

/** One monitor per lock file, so this process's threads take turns before the OS lock (which is per process). */
private val FILE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

/**
 * Runs [block] holding [lock], a small file beside the data it guards: this process's threads one at a time, and other
 * processes through the OS file lock (as People's .lock and memory.lock). For a read-modify-write that must not lose
 * another writer's change.
 */
fun <T> withFileLock(lock: File, block: () -> T): T {
    lock.parentFile?.mkdirs()
    val monitor = FILE_LOCKS.getOrPut(lock.absolutePath) { Any() }
    return synchronized(monitor) {
        java.nio.channels.FileChannel.open(lock.toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }
}

fun readOrNull(f: File): String? = try { if (f.isFile) f.readText(Charsets.UTF_8) else null } catch (_: Exception) { null }

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

enum class InstallResult { OK, WRONG_SIZE, WRONG_HASH, IO_ERROR }

/**
 * Copies [input] to [dest] through a temporary file next to it, hashing on the way, and moves it into place only
 * if it has exactly [bytes] bytes and the SHA-256 [sha256] (lower-case hex). The copy is fsynced before the rename,
 * so [dest] is either absent, the old file, or the complete checked one. [progress] gets the bytes copied so far.
 * Used for the voice models (download folder → app-private storage) and the espeak-ng data (APK → files).
 */
fun installVerified(input: java.io.InputStream, dest: File, bytes: Long, sha256: String, progress: (Long) -> Unit = {}): InstallResult {
    val tmp = File(dest.parentFile, ".${dest.name}.part")
    try {
        dest.parentFile?.mkdirs()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var done = 0L
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                done += n
                if (done > bytes) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
                progress(done)
            }
            out.fd.sync()
        }
        if (done != bytes) {
            tmp.delete()
            return InstallResult.WRONG_SIZE
        }
        if (hex(md.digest()) != sha256) {
            tmp.delete()
            return InstallResult.WRONG_HASH
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return InstallResult.IO_ERROR
        }
        return InstallResult.OK
    } catch (_: Exception) {
        tmp.delete()
        return InstallResult.IO_ERROR
    }
}

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
