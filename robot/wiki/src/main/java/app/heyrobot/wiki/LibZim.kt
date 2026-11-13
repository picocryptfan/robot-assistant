// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

// :WIKI PROCESS ONLY. The JNI of librobotzim.so (wiki/src/main/cpp/robotzim_jni.cpp): open a ZIM file from a descriptor,
// title suggestions, full-text search, an article's HTML, a metadata value, close. The library is loaded on first use, and
// a build made without tools/build_libzim.sh has none: [available] says so instead of crashing. Text crosses as UTF-8
// bytes. Nothing here or in the library logs a word.

/** The reader's native side, as WikiReader uses it; tests give it one without the library. */
internal interface ZimNative {
    val available: Boolean
    /** A handle, or null when the descriptor isn't a readable ZIM file. The library keeps its own copy of [fd]. */
    fun open(fd: Int): Long?
    /** Article count, then 1 or 0 for a title index and a full-text index. */
    fun info(handle: Long): LongArray?
    fun meta(handle: Long, name: String): String?
    /** Path, title, path, title... */
    fun titles(handle: Long, query: String, max: Int): List<String>?
    fun search(handle: Long, query: String, max: Int): List<String>?
    /** Path, title and HTML (cut at [maxBytes]) of the article at [path], redirects followed. */
    fun article(handle: Long, path: String, maxBytes: Int): List<String>?
    fun close(handle: Long)
}

internal object LibZim : ZimNative {
    override val available: Boolean by lazy {
        try {
            System.loadLibrary("robotzim")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    override fun open(fd: Int): Long? {
        val err = IntArray(1)
        val h = nativeOpen(fd, err)
        return if (h == 0L || err[0] != 0) null else h
    }

    override fun info(handle: Long): LongArray? = nativeInfo(handle)

    override fun meta(handle: Long, name: String): String? = nativeMeta(handle, name.encodeToByteArray())?.decodeToString()

    override fun titles(handle: Long, query: String, max: Int): List<String>? =
        nativeTitles(handle, query.encodeToByteArray(), max)?.map { it.decodeToString() }

    override fun search(handle: Long, query: String, max: Int): List<String>? =
        nativeSearch(handle, query.encodeToByteArray(), max)?.map { it.decodeToString() }

    override fun article(handle: Long, path: String, maxBytes: Int): List<String>? =
        nativeArticle(handle, path.encodeToByteArray(), maxBytes)?.map { it.decodeToString() }

    override fun close(handle: Long) = nativeClose(handle)

    @JvmStatic private external fun nativeOpen(fd: Int, err: IntArray): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeInfo(handle: Long): LongArray?

    @JvmStatic private external fun nativeMeta(handle: Long, name: ByteArray): ByteArray?

    @JvmStatic private external fun nativeTitles(handle: Long, query: ByteArray, max: Int): Array<ByteArray>?

    @JvmStatic private external fun nativeSearch(handle: Long, query: ByteArray, max: Int): Array<ByteArray>?

    @JvmStatic private external fun nativeArticle(handle: Long, path: ByteArray, maxBytes: Int): Array<ByteArray>?
}
