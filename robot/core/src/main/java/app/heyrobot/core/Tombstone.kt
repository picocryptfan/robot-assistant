// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.io.InputStream
import java.util.Locale

// Pure (0.7). A native crash in a problem report: since Android 12, ApplicationExitInfo.getTraceInputStream() gives a
// REASON_CRASH_NATIVE exit's tombstone as a protocol buffer (AOSP system/core/debuggerd/proto/tombstone.proto). Robot reads
// only what a report may say: the signal and its code, Android's abort message when it holds no path and no words that may
// be yours (else only that there is one), and the crashing thread's top [Tombstone.FRAMES] frames as library file names and
// offsets with their symbols. Never a full path, the memory around the fault, a register, the memory maps, the open files,
// the log buffers or the command line, all of which the tombstone also holds. No protobuf library: the few fields below,
// by their numbers in tombstone.proto, read with bounds checked, anything else skipped.

/** What a report says of a native crash. [frames]: "#00 libc.so +0x5b2c4 (abort+164)", the crashing thread's first ones. */
data class NativeCrash(
    val signal: Int,
    val signalName: String?,
    val code: Int,
    val codeName: String?,
    /** Android's abort message when it is safe to show ([Tombstone.safeAbort]); null otherwise. */
    val abortMessage: String?,
    /** There was an abort message (shown or not). */
    val abortPresent: Boolean,
    val frames: List<String>,
    /** How many frames the crashing thread had. */
    val framesTotal: Int,
) {
    /** The report's lines, indented under the exit they belong to. */
    fun lines(): List<String> = buildList {
        add("Native crash: " + (signalName ?: "signal") + " ($signal), code " + (codeName ?: "") + (if (codeName != null) " ($code)" else "$code"))
        add("Abort message: " + when {
            abortMessage != null -> "“$abortMessage”"
            abortPresent -> "present (left out: it may hold a path or words)"
            else -> "none"
        })
        if (frames.isEmpty()) add("Backtrace: none found") else {
            add("Backtrace, the crashing thread's first ${frames.size} of $framesTotal frames:")
            addAll(frames)
        }
    }
}

object Tombstone {
    /** A tombstone is usually well under a megabyte; more is not read. */
    const val MAX_BYTES = 8 * 1024 * 1024
    const val FRAMES = 16
    const val MAX_ABORT = 160
    const val MAX_SYMBOL = 96

    // tombstone.proto's field numbers (message Tombstone, Signal, Thread, BacktraceFrame).
    private const val TOMBSTONE_TID = 6
    private const val TOMBSTONE_SIGNAL = 10
    private const val TOMBSTONE_ABORT = 14
    private const val TOMBSTONE_THREADS = 16
    private const val MAP_KEY = 1
    private const val MAP_VALUE = 2
    private const val SIGNAL_NUMBER = 1
    private const val SIGNAL_NAME = 2
    private const val SIGNAL_CODE = 3
    private const val SIGNAL_CODE_NAME = 4
    private const val THREAD_ID = 1
    private const val THREAD_BACKTRACE = 4
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION = 4
    private const val FRAME_FUNCTION_OFFSET = 5
    private const val FRAME_FILE = 6

    /** Reads [input] (at most [MAX_BYTES]) and decodes it; null when it isn't a tombstone Robot can read. */
    fun read(input: InputStream): NativeCrash? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buf.size() < MAX_BYTES) {
            val n = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return decode(buf.toByteArray())
    }

    /** One field: its number, wire type, the number it holds (varint, fixed) or where its bytes are (length-delimited). */
    private class Field(val number: Int, val wire: Int, val value: Long, val from: Int, val to: Int)

    /** The fields of the message in [b] from [start] to [end]; stops at the first that doesn't fit (a cut file). */
    private fun fields(b: ByteArray, start: Int, end: Int): List<Field> {
        val out = ArrayList<Field>()
        var pos = start
        fun varint(): Long? {
            var shift = 0
            var result = 0L
            while (pos < end && shift < 64) {
                val x = b[pos++].toInt()
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        while (pos < end) {
            val key = varint() ?: break
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (number <= 0) break
            when (wire) {
                0 -> out += Field(number, wire, varint() ?: break, 0, 0)
                1 -> { if (end - pos < 8) break; out += Field(number, wire, le(b, pos, 8), 0, 0); pos += 8 }
                2 -> {
                    val len = varint() ?: break
                    if (len < 0 || len > end - pos) break
                    out += Field(number, wire, 0, pos, pos + len.toInt())
                    pos += len.toInt()
                }
                5 -> { if (end - pos < 4) break; out += Field(number, wire, le(b, pos, 4), 0, 0); pos += 4 }
                else -> break
            }
        }
        return out
    }

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[at + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun text(b: ByteArray, f: Field): String = String(b, f.from, f.to - f.from, Charsets.UTF_8)

    /** The tombstone in [b], or null when it has neither a signal nor a thread Robot can find. */
    fun decode(b: ByteArray): NativeCrash? {
        val top = fields(b, 0, b.size)
        val tid = top.lastOrNull { it.number == TOMBSTONE_TID && it.wire == 0 }?.value?.toInt()
        val signal = top.lastOrNull { it.number == TOMBSTONE_SIGNAL && it.wire == 2 }?.let { s -> fields(b, s.from, s.to) }
        val abort = top.lastOrNull { it.number == TOMBSTONE_ABORT && it.wire == 2 }?.let { text(b, it) }
        // threads is a map<uint32, Thread>: entries of a key and a Thread; the crashing one is the tombstone's tid.
        val threads = top.filter { it.number == TOMBSTONE_THREADS && it.wire == 2 }.mapNotNull { e ->
            val entry = fields(b, e.from, e.to)
            val value = entry.lastOrNull { it.number == MAP_VALUE && it.wire == 2 } ?: return@mapNotNull null
            val key = entry.lastOrNull { it.number == MAP_KEY && it.wire == 0 }?.value?.toInt()
            val thread = fields(b, value.from, value.to)
            (key ?: thread.lastOrNull { it.number == THREAD_ID && it.wire == 0 }?.value?.toInt()) to thread
        }
        if (signal == null && threads.isEmpty()) return null
        val crashing = threads.firstOrNull { it.first == tid }?.second ?: threads.firstOrNull()?.second.orEmpty()
        val frames = crashing.filter { it.number == THREAD_BACKTRACE && it.wire == 2 }
        val shown = frames.take(FRAMES).mapIndexed { i, f -> frame(i, fields(b, f.from, f.to), b) }
        fun sig(n: Int) = signal?.lastOrNull { it.number == n }
        return NativeCrash(
            signal = sig(SIGNAL_NUMBER)?.value?.toInt() ?: 0,
            signalName = sig(SIGNAL_NAME)?.takeIf { it.wire == 2 }?.let { code(text(b, it)) },
            code = sig(SIGNAL_CODE)?.value?.toInt() ?: 0,
            codeName = sig(SIGNAL_CODE_NAME)?.takeIf { it.wire == 2 }?.let { code(text(b, it)) },
            abortMessage = abort?.let(::safeAbort),
            abortPresent = !abort.isNullOrBlank(),
            frames = shown,
            framesTotal = frames.size,
        )
    }

    /** "SIGSEGV", "SEGV_MAPERR": capitals, digits and underscores, or null. */
    private fun code(s: String): String? = s.filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(24).ifEmpty { null }

    /** "#03 liblitertlm_jni.so +0x1a2b3c (litert::lm::Engine::Run+88)". */
    private fun frame(i: Int, f: List<Field>, b: ByteArray): String {
        val relPc = f.lastOrNull { it.number == FRAME_REL_PC && it.wire == 0 }?.value ?: 0L
        val file = f.lastOrNull { it.number == FRAME_FILE && it.wire == 2 }?.let { library(text(b, it)) } ?: "(unknown)"
        val fn = f.lastOrNull { it.number == FRAME_FUNCTION && it.wire == 2 }?.let { symbol(text(b, it)) }
        val off = f.lastOrNull { it.number == FRAME_FUNCTION_OFFSET && it.wire == 0 }?.value ?: 0L
        val sym = fn?.let { " (" + it + (if (off > 0) "+$off" else "") + ")" } ?: ""
        return String.format(Locale.US, "#%02d %s +0x%x%s", i, file, relPc, sym)
    }

    private val LIBRARY = Regex("""lib[A-Za-z0-9_.+-]*\.so""")
    /** Android's own files a frame may be in, by their fixed names. */
    private val SYSTEM_FILES = setOf("linker64", "linker", "app_process64", "app_process32", "boot.oat", "boot-framework.oat", "base.odex", "base.oat",
        "base.vdex", "[vdso]")

    /**
     * A frame's file as a report may name it: a library's file name ("/data/app/…/lib/arm64/liblitertlm_jni.so",
     * "base.apk!libfoo.so" → its last part), or one of Android's own fixed names; anything else (a model file, a file of
     * yours, an anonymous mapping) is "(not a library)".
     */
    fun library(path: String): String {
        val last = path.trim().substringAfterLast('/').substringAfterLast('!')
        return if (LIBRARY.matches(last) || last in SYSTEM_FILES) last else "(not a library)"
    }

    /** A symbol's name: the characters C++, Java and Kotlin names use, at most [MAX_SYMBOL]. */
    fun symbol(s: String): String? =
        s.filter { it.isLetterOrDigit() && it.code < 128 || it in "_:.~<>,()&*[] $-" }.trim().take(MAX_SYMBOL).ifEmpty { null }

    /**
     * Android's abort message, when it can't hold anything of yours: one line of plain ASCII, short, with no path, no quote
     * (where a library would put text it was given), no address or link and no long number. Else null.
     */
    fun safeAbort(s: String): String? {
        val t = s.trim()
        if (t.isEmpty() || t.length > MAX_ABORT) return null
        if (t.any { it.code !in 0x20..0x7e }) return null
        if (t.any { it in "/\\\"'`@" } || "://" in t) return null
        if (Regex("""\d{7,}""").containsMatchIn(t)) return null
        return t
    }
}
