// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import java.io.ByteArrayOutputStream

/** Records written by hand, rules or not: for files no Robot would write (a newer one's, a damaged one, a hostile one). */
class RawRecords {
    private val out = ByteArrayOutputStream()
    var entries = 0L
        private set
    var bytes = 0L
        private set

    fun raw(b: ByteArray) = apply { out.write(b) }

    fun record(kind: Int, meta: String, data: ByteArray? = null, declared: Long? = data?.size?.toLong()) = apply {
        val m = meta.toByteArray(Charsets.UTF_8)
        out.write(kind)
        int(m.size.toLong())
        out.write(m)
        if (declared != null) {
            long(declared)
            data?.let(out::write)
        }
    }

    fun manifest(json: String = BackupKit.manifest(*BackupCategory.entries.toTypedArray()).toJson()) = record(RecordKind.MANIFEST, json)

    fun entry(c: String, n: String, data: ByteArray, v: Int? = null) = apply {
        record(RecordKind.ENTRY, "{\"c\":${jsonString(c)},\"n\":${jsonString(n)}" + (v?.let { ",\"v\":$it" } ?: "") + "}", data)
        entries++
        bytes += data.size
    }

    fun end(e: Long = entries, b: Long = bytes) = record(RecordKind.END, "{\"entries\":$e,\"bytes\":$b}")

    fun bytes(): ByteArray = out.toByteArray()

    private fun int(v: Long) {
        for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
    }

    private fun long(v: Long) {
        for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
    }
}
