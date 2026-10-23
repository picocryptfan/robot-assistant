// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Pure. Getting a file in (files-1.4-plan.md §3, F5): one copy of a sender's bytes into Robot Files' incoming/ folder,
// with a cap and a deadline, for every way in (the share sheet, a scan, the panel's "file this", a transcript). Nothing
// here decodes anything: the bytes are hashed and sealed as they stream (FileStore.stage), and their first 4 KiB are kept
// in RAM for the type's sniff (core parse FileSniff's fixed table), never the sender's word for it.
//
// What a sender can do, and what answers it:
//   - send more than 200 MiB, or a stream with no end: FileSeal's writer refuses the byte past the cap (TOO_BIG);
//   - claim a size it doesn't have: the claim only refuses early (over the cap, or over the free space); the real size
//     is what is counted and kept;
//   - stop sending, or send nothing at all: a watchdog on the caller's thread gives up after IDLE_MS without a byte or
//     TOTAL_MS in all, closes the sender's stream (which ends a read blocked on it) and leaves the copy to end; whatever
//     it staged after all is discarded when it does, so nothing given up lands in Inbox at the next start;
//   - not answer when its file is opened or asked about: [IntakeCopy.within] gives up after OPEN_MS and closes what
//     comes later;
//   - fail part way: the sender's failure (UNREADABLE) is told apart from Robot's own storage's (IO).
// The staged file has no entry yet: the caller checks for a duplicate (FileStore.duplicateOf) and then commits it, or
// discards it. A staged file that is neither is adopted into Inbox by the next recovery, so every way in discards on
// every way out (cancelled, closed, a duplicate you didn't add).

/** The caps and times of getting a file in (EST, set again after the phone's checks). */
object IntakeLimits {
    /** Files in one share: more are left out and counted (the window says how many). */
    const val MAX_ITEMS = 20
    /** Opening one shared file, and asking its name and size: a sender that doesn't answer in this is given up. */
    const val OPEN_MS = 15_000L
    /** A copy that gets no byte for this long is given up. */
    const val IDLE_MS = 20_000L
    /** One file's whole copy: 200 MiB in this time is about 1.2 MB a second, far slower than any app on the phone. */
    const val TOTAL_MS = 180_000L
    /** How long a copy given up has to stop before it is left to end on its own. */
    const val STOP_MS = 5_000L
    /** How often the watchdog looks. */
    const val TICK_MS = 100L
    /** Shared text with no file (a link from the browser) becomes a text file of at most this many bytes (UTF-8). */
    const val MAX_TEXT = 256 * 1024
}

/** Why a file wasn't added (FilesStrings says each in words). */
enum class IntakeProblem {
    /** The phone is locked, or a phone lock is on: robot_files_v1 can't be used now. */
    LOCKED,
    KEY_GONE,
    /** The files' list couldn't be read (damaged, or a newer Robot's). */
    INDEX,
    /** 5,000 files. */
    FULL,
    TOO_BIG,
    LOW_SPACE,
    /** The sender stopped sending, or didn't answer. */
    TIMED_OUT,
    /** The sender's bytes couldn't be read (its stream failed, or it couldn't be opened). */
    UNREADABLE,
    /** No bytes at all. */
    EMPTY,
    /** Robot's own storage failed. Nothing half-written is left. */
    IO,
    /** Robot doesn't take a file shared this way (file:, Robot's own storage, another user's). */
    NOT_ALLOWED,
    /** Robot Files is off. */
    OFF,
    ;

    companion object {
        fun of(p: FileProblem): IntakeProblem = when (p) {
            FileProblem.LOCKED -> LOCKED
            FileProblem.KEY_GONE -> KEY_GONE
            FileProblem.INDEX -> INDEX
            FileProblem.FULL -> FULL
            FileProblem.TOO_BIG -> TOO_BIG
            FileProblem.LOW_SPACE -> LOW_SPACE
            FileProblem.DAMAGED, FileProblem.NEWER, FileProblem.MISSING, FileProblem.IO -> IO
        }
    }
}

/** What one copy came to. */
sealed class Intake {
    /** Sealed into incoming/, not yet in the index: commit it or discard it. [type]: sniffed from its first bytes. */
    class Ready(val staged: Staged, val type: FileType) : Intake() {
        override fun toString(): String = "Ready($type)"  // l10n: not text (for tests)
    }

    /** Not added; nothing of it is left. [needed]: for LOW_SPACE, how many more bytes adding it needs. */
    class Failed(val problem: IntakeProblem, val needed: Long = 0) : Intake() {
        override fun toString(): String = "Failed($problem)"  // l10n: not text (for tests)
    }
}

/**
 * One copy at a time into [stage] (FileStore.stage through the vault's gate), under a watchdog: [discard] takes back what
 * a copy staged after it was given up. [now]: milliseconds of a monotonic clock. Every call blocks: never on the main thread.
 */
class IntakeCopy(
    private val stage: (InputStream, Long?) -> Staged,
    private val discard: (Staged) -> Unit,
    private val times: Times = Times(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The watchdog's times (the tests' are short). */
    data class Times(
        val idleMs: Long = IntakeLimits.IDLE_MS,
        val totalMs: Long = IntakeLimits.TOTAL_MS,
        val openMs: Long = IntakeLimits.OPEN_MS,
        val stopMs: Long = IntakeLimits.STOP_MS,
        val tickMs: Long = IntakeLimits.TICK_MS,
    )

    /**
     * Copies [input] into incoming/ (closed when done or given up). [declared]: the size the sender claims, or null; a
     * negative one is no claim. [stop]: what else to do when the copy is given up (cancel the sender's open call).
     */
    fun copy(input: InputStream, declared: Long?, stop: () -> Unit = {}): Intake {
        val claim = declared?.takeIf { it >= 0 }
        if (claim != null && claim > FileLimits.MAX_FILE) {
            closeQuietly(input)
            return Intake.Failed(IntakeProblem.TOO_BIG)
        }
        val watched = Watched(input, now)
        val done = CompletableFuture<Staged>()
        Thread({
            try {
                done.complete(stage(watched, claim))
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }, "robot-files-intake").apply { isDaemon = true }.start()  // l10n: not text (a thread's name)
        while (true) {
            try {
                val staged = done.get(times.tickMs, TimeUnit.MILLISECONDS)
                closeQuietly(input)
                return ready(staged, watched)
            } catch (_: TimeoutException) {
                val t = now()
                // Idle counts only while bytes are awaited: once the sender's stream ended, the seal's last chunk and fsync remain.
                if (t - watched.started >= times.totalMs || (!watched.ended && t - watched.last >= times.idleMs)) break
            } catch (x: ExecutionException) {
                closeQuietly(input)
                return failed(x.cause, watched)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Given up: every read from now on fails, the sender's stream is closed (a read blocked on it ends), and whatever
        // the copy staged after all is discarded when it ends.
        watched.giveUp()
        try { stop() } catch (_: Exception) {}
        closeQuietly(input)
        done.whenComplete { s, _ -> if (s != null) try { discard(s) } catch (_: Exception) {} }
        try { done.get(times.stopMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        return Intake.Failed(IntakeProblem.TIMED_OUT)
    }

    /**
     * [block] (opening a sender's file, asking its name and size) given [Times.openMs]: its value, or null when it didn't
     * answer in time ([cancel] is called then, and a value that comes later is passed to [late], to be closed). What
     * [block] throws is thrown here.
     */
    fun <T : Any> within(cancel: () -> Unit, late: (T) -> Unit, block: () -> T?): T? {
        val done = CompletableFuture<T?>()
        Thread({
            try {
                done.complete(block())
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }, "robot-files-open").apply { isDaemon = true }.start()  // l10n: not text (a thread's name)
        return try {
            done.get(times.openMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            try { cancel() } catch (_: Exception) {}
            done.whenComplete { v, _ -> if (v != null) try { late(v) } catch (_: Exception) {} }
            null
        } catch (x: ExecutionException) {
            throw x.cause ?: x
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            try { cancel() } catch (_: Exception) {}
            done.whenComplete { v, _ -> if (v != null) try { late(v) } catch (_: Exception) {} }
            null
        }
    }

    /** [bytes] Robot made itself (a scan, the panel's picture, a transcript): the same copy, from memory. */
    fun copy(bytes: ByteArray): Intake = copy(bytes.inputStream(), bytes.size.toLong())

    private fun ready(staged: Staged, watched: Watched): Intake {
        if (watched.count == 0L || staged.size == 0L) {
            try { discard(staged) } catch (_: Exception) {}
            return Intake.Failed(IntakeProblem.EMPTY)
        }
        // FX2 (files-1.4-review.md finding 14): then what its first bytes can't tell (a ZIP appended to a JPEG or a PDF, markup
        // deep in a text), from the whole stream: such a file is DANGEROUS from the start, as a ZIP or HTML is.
        val first = FileSniff.sniff(watched.head(), watched.count)
        return Intake.Ready(staged, watched.watch.hidden(first) ?: first)
    }

    /** The sender's own failure first (FileStore reads it as IO, or as low space when space is short too), then Robot's. */
    private fun failed(cause: Throwable?, watched: Watched): Intake = when {
        watched.failed -> Intake.Failed(IntakeProblem.UNREADABLE)
        cause is FileException -> Intake.Failed(IntakeProblem.of(cause.problem), cause.needed)
        else -> Intake.Failed(IntakeProblem.IO)
    }

    private fun closeQuietly(input: InputStream) = try { input.close() } catch (_: Exception) {}
}

/**
 * The sender's stream as the copy reads it: when the last byte came (for the watchdog), how many came, its first bytes
 * (for the sniff), whether the sender's own read failed, and, once given up, a failure for every read.
 */
private class Watched(private val input: InputStream, private val now: () -> Long) : InputStream() {
    val started: Long = now()
    @Volatile var last: Long = started
        private set
    @Volatile var count = 0L
        private set
    @Volatile var failed = false
        private set
    /** The sender's stream ended (a read gave -1). */
    @Volatile var ended = false
        private set
    @Volatile private var gaveUp = false
    private val prefix = ByteArray(FileSniff.PREFIX)
    private var kept = 0
    /** FX2: its end and its markup, for FileSniff's second look. */
    val watch = FileSniff.Watch()

    fun giveUp() {
        gaveUp = true
    }

    fun head(): ByteArray = prefix.copyOf(kept)

    override fun read(): Int {
        val one = ByteArray(1)
        val k = read(one, 0, 1)
        return if (k <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (gaveUp) throw IOException()
        if (len == 0) return 0
        val k = try {
            input.read(b, off, len)
        } catch (x: IOException) {
            if (!gaveUp) failed = true
            throw x
        } catch (x: RuntimeException) {
            // A sender's stream may throw anything; it is the sender's failure, never Robot's.
            if (!gaveUp) failed = true
            throw IOException(x)
        }
        if (gaveUp) throw IOException()
        if (k < 0) ended = true
        if (k > 0) {
            if (kept < prefix.size) {
                val n = minOf(k, prefix.size - kept)
                System.arraycopy(b, off, prefix, kept, n)
                kept += n
            }
            count += k
            watch.feed(b, off, k)
            last = now()
        }
        return k
    }

    override fun close() = input.close()
}
