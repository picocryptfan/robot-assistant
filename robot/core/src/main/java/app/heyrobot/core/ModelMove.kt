package app.heyrobot.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

// Pure (java.io only). 1.0.1: where Robot's model files are trusted. Up to 1.0.0 they lived in the app's external files
// folder (DownloadManager's only choice), which a computer over USB or adb can write to, and were trusted after their
// SHA-256 check on their size and modification time alone. From 1.0.1 a model lives in the app's internal storage
// (filesDir/models), which only Robot can write: a finished download, your own imported file and a 1.0 model are copied
// in, hashed as they are written, and the outside copy is deleted only once the inside one is complete and checked. A
// model still outside (no room to move it yet, or the copy failed) is hashed in full before each load instead.

object ModelMove {
    /** Room kept free besides the file while it is copied in (half the models' headroom). */
    const val MARGIN = BrainModel.HEADROOM / 2

    /** The copy stops (and is deleted) when the free space falls below this. */
    const val FLOOR = MARGIN / 2

    private const val BUFFER = 8 shl 20
    private const val SPACE_CHECK_EVERY = 256L shl 20

    /** There is room to copy [bytes] in: [free] (below 0: unknown, so no) minus what downloads under way still write ([pending]). */
    fun room(bytes: Long, free: Long, pending: Long = 0): Boolean = free >= 0 && free - pending.coerceAtLeast(0) >= bytes + MARGIN

    /** A model at [path] is hashed in full before each load unless it is inside [inside] (the app's internal storage). */
    fun hashBeforeLoad(path: File, inside: File): Boolean = try {
        !path.canonicalPath.startsWith(inside.canonicalPath + File.separator)
    } catch (_: Exception) { true }

    /** The copy in progress for [dst]: a leftover one (a copy that stopped with the app) is deleted before the next. */
    fun part(dst: File): File = File(dst.parentFile, dst.name + ".part")

    sealed class Result {
        /** [file] is complete and its SHA-256 is [sha256] (the expected one). The source is untouched. */
        data class Moved(val file: File, val sha256: String) : Result()

        /** Not enough free space to start, or it ran out on the way: nothing kept. */
        data object NoRoom : Result()

        /** The bytes copied aren't the expected ones (the source changed since its check): nothing kept. */
        data class Mismatch(val sha256: String) : Result()

        /** Stopped ([copyIn]'s go said no), or the copy or the rename failed: nothing kept. */
        data class Failed(val error: Throwable?) : Result()
    }

    /**
     * Copies [src] to [dst] through [part], hashing every byte as it is written, then renames it into place. [expected]:
     * the SHA-256 it must have (null: any, for a file checked by its contents afterwards). [free]: the free bytes now (read
     * at the start and every [checkEvery] bytes); [pending]: what downloads under way will still write. [go] is asked before each
     * block (false stops it); [progress] gets the bytes copied and the total. Never touches [src].
     */
    fun copyIn(src: File, dst: File, expected: String?, free: () -> Long, pending: Long = 0, go: () -> Boolean = { true },
        checkEvery: Long = SPACE_CHECK_EVERY, progress: (Long, Long) -> Unit = { _, _ -> }): Result {
        val part = part(dst)
        part.delete()
        val total = src.length()
        if (!room(total, free(), pending)) return Result.NoRoom
        try {
            dst.parentFile?.mkdirs()
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var nextCheck = checkEvery
            FileInputStream(src).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (!go()) { part.delete(); return Result.Failed(null) }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done >= nextCheck) {
                            nextCheck += checkEvery
                            if (free() in 0 until FLOOR) { part.delete(); return Result.NoRoom }
                        }
                        progress(done, total)
                    }
                    out.fd.sync()
                }
            }
            val sha = hex(md.digest())
            if (expected != null && sha != expected) { part.delete(); return Result.Mismatch(sha) }
            if (!go()) { part.delete(); return Result.Failed(null) }
            dst.delete()
            if (!part.renameTo(dst)) { part.delete(); return Result.Failed(null) }
            return Result.Moved(dst, sha)
        } catch (t: Throwable) {
            part.delete()
            return Result.Failed(t)
        }
    }

    /** The SHA-256 of [input] as lowercase hex; [progress] gets the bytes read so far. */
    fun sha256(input: InputStream, progress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            done += n
            progress(done)
        }
        return hex(md.digest())
    }

    /** The SHA-256 of everything [channel] holds, read by position (the channel's own position is left alone). */
    fun sha256(channel: FileChannel): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(BUFFER)
        var at = 0L
        while (true) {
            buf.clear()
            val n = channel.read(buf, at)
            if (n < 0) break
            md.update(buf.array(), 0, n)
            at += n
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
