// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import java.io.File

// :MT PROCESS ONLY. The JNI of librobotmt.so (mt/src/main/cpp/robotmt_jni.cpp): open a direction, translate a line,
// close. The library is loaded on first use, and a build made without tools/build_robotmt.sh has none: [available] says
// so instead of crashing. Paths and text cross as UTF-8 bytes. Nothing here or in the library logs a word.

internal object Slimt {
    /** librobotmt.so is in this build and loaded. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("robotmt")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** A native handle for one direction, or null when its files didn't load. */
    fun open(model: File, vocabulary: File, shortlist: File): Long? {
        val err = IntArray(1)
        val h = nativeOpen(model.path.encodeToByteArray(), vocabulary.path.encodeToByteArray(), shortlist.path.encodeToByteArray(), err)
        return if (h == 0L || err[0] != 0) null else h
    }

    /** [line] translated (each sentence on its own, in the native code), or null when the engine failed. */
    fun translate(handle: Long, line: String): String? {
        val err = IntArray(1)
        val out = nativeTranslate(handle, line.encodeToByteArray(), err) ?: return null
        return if (err[0] != 0) null else out.decodeToString()
    }

    fun close(handle: Long) = nativeClose(handle)

    @JvmStatic private external fun nativeOpen(model: ByteArray, vocabulary: ByteArray, shortlist: ByteArray, err: IntArray): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeTranslate(handle: Long, utf8: ByteArray, err: IntArray): ByteArray?
}
