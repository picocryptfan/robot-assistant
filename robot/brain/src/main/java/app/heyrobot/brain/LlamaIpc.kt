package app.heyrobot.brain

import android.os.IBinder
import android.os.Parcel

// The Binder interface between :brain (LlamaEngine, through LlamaLink) and llama.cpp's isolated process (LlamaService),
// 0.4.5. Written by hand (no AIDL): a descriptor, a code per call, and Parcel layouts read and written only here. :brain
// keeps every decision (the file's checks, its context, the prompt, the control tokens, the thinking filter, the
// watchdogs, the fallback); the isolated side only loads the file it is given as an open descriptor, formats and counts a
// conversation with the template :brain gives it, and writes a reply. Nothing that crosses here names a file, a key or a
// setting. Everything the isolated side returns is untrusted in :brain: sizes and counts are capped where it is read.

internal object LlamaIpc {
    /** Bound by name from :brain (LlamaLink): :brain's code never refers to the service's class, nor to llama.cpp's bridge. */
    const val SERVICE_CLASS = "app.heyrobot.brain.LlamaService"
    const val DESCRIPTOR = "app.heyrobot.brain.ILlama"
    const val CALLBACK = "app.heyrobot.brain.ILlamaCallback"

    // :brain -> the isolated process.
    /** In: the callback binder. Loads the library; calls the callback's HELLO (its pid); out: the library's problem or null. */
    const val HELLO = IBinder.FIRST_CALL_TRANSACTION
    /** In: the file (a read-only ParcelFileDescriptor), context, threads, prompt threads, sampling. Out: see [LoadResult]. */
    const val LOAD = IBinder.FIRST_CALL_TRANSACTION + 1
    /** One way. In: handle, run. Stops that run (and any earlier one), also inside a prompt being read. */
    const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 2
    /** In: handle, roles, texts (UTF-8), think. Out: token count or a PREP_* code, flags. */
    const val PREPARE = IBinder.FIRST_CALL_TRANSACTION + 3
    /** In: handle, run, max tokens. The reply's pieces come through the callback's PIECE; out: a GEN_* code, stats. */
    const val GENERATE = IBinder.FIRST_CALL_TRANSACTION + 4
    /** One way. The isolated process ends itself: the model's memory goes with it, whatever llama.cpp is doing. */
    const val EXIT = IBinder.FIRST_CALL_TRANSACTION + 5

    // The isolated process -> :brain (the callback binder :brain passes in HELLO).
    /** In: its pid. Out: true when :brain accepts it (its calling uid is an isolated one). */
    const val CB_HELLO = IBinder.FIRST_CALL_TRANSACTION
    /** In: a piece of the reply (whole UTF-8 characters). Out: true to go on, false to stop. */
    const val CB_PIECE = IBinder.FIRST_CALL_TRANSACTION + 1

    /** Caps on what :brain reads back: a reply piece, and the special tokens' count and total size. */
    const val MAX_PIECE = 64 * 1024
    const val MAX_SPECIALS = 100_000
    const val MAX_SPECIAL_BYTES = 512 * 1024

    // generate() results (kept in step with robot_llama.cpp).
    const val GEN_END = 0
    const val GEN_LENGTH = 1
    const val GEN_CANCELLED = 2
    const val GEN_CONTEXT_FULL = 3
    const val GEN_NOT_PREPARED = -1
    const val GEN_DECODE_FAILED = -2

    // prepare() failures.
    const val PREP_TEMPLATE_FAILED = -1
    const val PREP_TOKENIZE_FAILED = -2
    const val PREP_TOO_LONG = -3

    // load()'s failed part.
    const val LOAD_MODEL = 1
    const val LOAD_CONTEXT = 2
    const val LOAD_TEMPLATE = 3

    // generate()'s stats.
    const val STATS_PROMPT = 0
    const val STATS_REUSED = 1
    const val STATS_PROMPT_MS = 2
    const val STATS_GENERATED = 3
    const val STATS_GENERATE_MS = 4
    const val STATS_FIRST_MS = 5
    const val STATS_POSITION = 6
    const val STATS_COUNT = 7

    const val NOT_IN_BUILD = "GGUF files can't run in this build of Robot: its llama.cpp library wasn't built (tools/build_llama.sh)."
    const val NOT_ISOLATED = "llama.cpp runs only in Robot's isolated process."

    /** LOAD's answer: [handle] 0 on failure ([why] a LOAD_* part), the context llama.cpp's parser read, the special tokens. */
    class LoadResult(val handle: Long, val why: Int, val trainContext: Long, val control: List<ByteArray>?, val userDefined: List<ByteArray>?)

    fun writeArrays(p: Parcel, items: List<ByteArray>?) {
        if (items == null) {
            p.writeInt(-1)
            return
        }
        p.writeInt(items.size)
        for (b in items) p.writeByteArray(b)
    }

    /** At most [maxCount] arrays of [maxBytes] in all; null when the writer sent none, or more than that. */
    fun readArrays(p: Parcel, maxCount: Int, maxBytes: Int): List<ByteArray>? {
        val n = p.readInt()
        if (n < 0 || n > maxCount) return null
        var total = 0L
        val out = ArrayList<ByteArray>(n)
        repeat(n) {
            val b = p.createByteArray() ?: return null
            total += b.size
            if (total > maxBytes) return null
            out += b
        }
        return out
    }
}
