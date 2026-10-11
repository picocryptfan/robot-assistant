package app.heyrobot.brain

// :LLAMA PROCESS ONLY (LlamaService, android:isolatedProcess). The JNI surface of librobot_llama.so
// (brain/src/main/cpp/robot_llama.cpp, built by tools/build_llama.sh) and the one place that loads it: only LlamaService
// refers to it, and it loads the library only in an isolated process (ProcessIsolationTest and verify_apk.sh enforce
// both). Strings travel as UTF-8 bytes, never JNI's modified UTF-8; the codes and stats slots are LlamaIpc's.

internal object LlamaNative {
    const val LIB = "robot_llama"

    /** The reply as it is written: whole UTF-8 characters; false stops it. */
    fun interface Sink { fun piece(bytes: ByteArray): Boolean }

    /** [fd]: the open GGUF file :brain checked (llama.cpp reads it through its own copy of the descriptor). */
    @JvmStatic external fun load(fd: Int, nCtx: Int, threads: Int, batchThreads: Int, sampling: FloatArray, why: IntArray): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun cancel(handle: Long, run: Long)
    @JvmStatic external fun trainContext(handle: Long): Int
    /** The vocabulary's control and unknown tokens' texts, or with [userDefined] its user-defined ones; null on failure. */
    @JvmStatic external fun specials(handle: Long, userDefined: Boolean): Array<ByteArray>?
    @JvmStatic external fun prepare(handle: Long, roles: Array<ByteArray>, texts: Array<ByteArray>, think: Boolean, flags: IntArray): Int
    @JvmStatic external fun generate(handle: Long, run: Long, maxTokens: Int, sink: Sink, stats: LongArray): Int

    private var tried = false
    private var problem: String? = null

    /**
     * Null when the library is loaded, else why not, in plain words. Never outside an isolated process: there llama.cpp
     * would run as Robot, with its permissions and files. Loads it once. (:brain checked the CPU's features first.)
     */
    @Synchronized fun ready(): String? {
        if (tried) return problem
        tried = true
        problem = if (!android.os.Process.isIsolated()) LlamaIpc.NOT_ISOLATED
        else try {
            System.loadLibrary(LIB)
            null
        } catch (_: UnsatisfiedLinkError) {
            LlamaIpc.NOT_IN_BUILD
        }
        return problem
    }
}
