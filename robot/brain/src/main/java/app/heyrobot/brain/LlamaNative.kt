package app.heyrobot.brain

import app.heyrobot.core.LlamaCpu
import java.io.File

// :BRAIN PROCESS ONLY. The JNI surface of librobot_llama.so (brain/src/main/cpp/robot_llama.cpp, built by
// tools/build_llama.sh) and the one place that loads it (ProcessIsolationTest enforces that). LlamaEngine calls it from
// its one worker thread; only cancel() is called from elsewhere. Strings travel as UTF-8 bytes, never JNI's modified UTF-8.

internal object LlamaNative {
    const val LIB = "robot_llama"
    const val FILE = "librobot_llama.so"

    // generate() results.
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

    /** The reply as it is written: whole UTF-8 characters; false stops it. */
    fun interface Sink { fun piece(bytes: ByteArray): Boolean }

    @JvmStatic external fun load(path: ByteArray, nCtx: Int, threads: Int, batchThreads: Int, sampling: FloatArray, why: IntArray): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun cancel(handle: Long, run: Long)
    @JvmStatic external fun trainContext(handle: Long): Int
    /** The vocabulary's control (and unknown) tokens' texts, UTF-8; null on failure (core ControlText breaks them). */
    @JvmStatic external fun specials(handle: Long): Array<ByteArray>?
    @JvmStatic external fun prepare(handle: Long, roles: Array<ByteArray>, texts: Array<ByteArray>, think: Boolean, flags: IntArray): Int
    @JvmStatic external fun generate(handle: Long, run: Long, maxTokens: Int, sink: Sink, stats: LongArray): Int

    const val NOT_IN_BUILD = "GGUF files can't run in this build of Robot: its llama.cpp library wasn't built (tools/build_llama.sh)."

    private var tried = false
    private var problem: String? = null

    /**
     * Null when the library is loaded and this CPU runs it, else why not, in plain words. Reads /proc/cpuinfo before it
     * loads the library (built for dotprod, i8mm and fp16: core LlamaCpu); loads it once.
     */
    @Synchronized fun ready(): String? {
        if (tried) return problem
        tried = true
        val missing = LlamaCpu.missing(try { File("/proc/cpuinfo").readText() } catch (_: Exception) { null })
        problem = if (missing.isNotEmpty()) "This phone's CPU lacks what Robot's llama.cpp is built for (${missing.joinToString(", ")})."
        else try {
            System.loadLibrary(LIB)
            null
        } catch (_: UnsatisfiedLinkError) {
            NOT_IN_BUILD
        }
        return problem
    }
}
