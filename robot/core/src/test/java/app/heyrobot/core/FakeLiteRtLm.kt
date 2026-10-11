package app.heyrobot.core

import java.io.ByteArrayOutputStream

/**
 * Tiny fake .litertlm files for the content check's tests (no model is ever downloaded): a LiteRT-LM header flatbuffer,
 * TFLite flatbuffer sections holding only operator codes, buffers and metadata, and an LlmMetadata proto.
 *
 * The flatbuffer writer lays objects out front to back: a table's vtable just before it, and every object a field
 * points to after it (flatbuffer offsets point forward), so no builder library is needed.
 */
object FakeLiteRtLm {
    sealed class V
    class Str(val s: String) : V()
    class Bytes(val b: ByteArray) : V()
    class Vec(val items: List<Obj>) : V()
    /** A table: one entry per field slot, null for an absent field. */
    class Obj(vararg val fields: F?) : V()

    sealed class F
    class U8(val v: Int) : F()
    class U32(val v: Long) : F()
    class U64(val v: Long) : F()
    class Ref(val v: V) : F()

    private class Buf {
        var b = ByteArray(256)
        var n = 0
        fun ensure(k: Int) { if (n + k > b.size) b = b.copyOf(maxOf(b.size * 2, n + k)) }
        fun u8(v: Int) { ensure(1); b[n++] = v.toByte() }
        fun u16(v: Int) { u8(v and 0xff); u8((v ushr 8) and 0xff) }
        fun u32(v: Long) { for (i in 0 until 4) u8(((v ushr (8 * i)) and 0xff).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) u8(((v ushr (8 * i)) and 0xff).toInt()) }
        fun bytes(x: ByteArray) { ensure(x.size); System.arraycopy(x, 0, b, n, x.size); n += x.size }
        fun patch32(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xff).toByte() }
        fun result() = b.copyOf(n)
    }

    /** A flatbuffer whose root is [root]; [ident] (4 letters, "TFL3") follows the root offset as in a real file. */
    fun flatbuffer(root: Obj, ident: String? = null): ByteArray {
        val w = Buf()
        w.u32(0)
        ident?.let { w.bytes(it.toByteArray(Charsets.US_ASCII)) }
        val r = write(w, root)
        w.patch32(0, r.toLong())
        return w.result()
    }

    private fun write(w: Buf, v: V): Int = when (v) {
        is Str -> { val p = w.n; val s = v.s.toByteArray(Charsets.UTF_8); w.u32(s.size.toLong()); w.bytes(s); w.u8(0); p }
        is Bytes -> { val p = w.n; w.u32(v.b.size.toLong()); w.bytes(v.b); p }
        is Vec -> {
            val p = w.n
            w.u32(v.items.size.toLong())
            val slots = v.items.map { val at = w.n; w.u32(0); at }
            for ((i, item) in v.items.withIndex()) { val t = write(w, item); w.patch32(slots[i], (t - slots[i]).toLong()) }
            p
        }
        is Obj -> {
            // vtable: its size, the table's size, then each field's offset in the table (0: absent).
            val sizes = v.fields.map { f -> when (f) { null -> 0; is U8 -> 1; is U32 -> 4; is U64 -> 8; is Ref -> 4 } }
            val offsets = ArrayList<Int>()
            var at = 4
            for (s in sizes) { offsets += if (s == 0) 0 else at; at += s }
            val vt = w.n
            w.u16(4 + 2 * sizes.size)
            w.u16(at)
            offsets.forEach(w::u16)
            val table = w.n
            w.u32((table - vt).toLong())
            val refs = ArrayList<Pair<Int, V>>()
            for (f in v.fields) when (f) {
                null -> {}
                is U8 -> w.u8(f.v)
                is U32 -> w.u32(f.v)
                is U64 -> w.u64(f.v)
                is Ref -> { refs += w.n to f.v; w.u32(0) }
            }
            for ((slot, child) in refs) { val c = write(w, child); w.patch32(slot, (c - slot).toLong()) }
            table
        }
    }

    // ------------------------------------------------------------------ LiteRT-LM

    /** A KeyValuePair holding a string (value_type 9, StringValue). */
    fun kv(key: String, value: String) = Obj(Ref(Str(key)), U8(9), Ref(Obj(Ref(Str(value)))))

    class Section(val modelType: String?, val dataType: Int, val data: ByteArray)

    const val TFLITE = 3
    const val LLM_METADATA = 5
    const val SP_TOKENIZER = 4

    /** "LITERTLM", version 1.5.0, the header's end, the header flatbuffer, then each section 16-byte aligned. */
    fun litertlm(sections: List<Section>, magic: String = "LITERTLM"): ByteArray {
        fun header(begins: List<Long>): ByteArray = flatbuffer(Obj(
            Ref(Obj(Ref(Vec(listOf(kv("model_name", "fake")))))),
            Ref(Obj(Ref(Vec(sections.mapIndexed { i, s ->
                Obj(Ref(Vec(listOfNotNull(s.modelType?.let { kv("model_type", it) }))), U64(begins[i]), U64(begins[i] + s.data.size), U8(s.dataType))
            })))),
        ))
        val length = header(sections.map { 0L }).size
        val begins = ArrayList<Long>()
        var at = align(32L + length)
        for (s in sections) { begins += at; at = align(at + s.data.size) }
        val h = header(begins)
        check(h.size == length)
        val out = ByteArray(at.toInt())
        System.arraycopy(magic.toByteArray(Charsets.US_ASCII), 0, out, 0, 8)
        fun put32(p: Int, v: Long) { for (i in 0 until 4) out[p + i] = ((v ushr (8 * i)) and 0xff).toByte() }
        put32(8, 1); put32(12, 5); put32(16, 0)
        val end = 32L + h.size
        put32(24, end); put32(28, end ushr 32)
        System.arraycopy(h, 0, out, 32, h.size)
        for ((i, s) in sections.withIndex()) System.arraycopy(s.data, 0, out, begins[i].toInt(), s.data.size)
        return out
    }

    private fun align(p: Long) = (p + 15) / 16 * 16

    // ------------------------------------------------------------------ TFLite

    /** An operator code: a custom one ("DISPATCH_OP") or a builtin by number. */
    fun op(custom: String? = null, builtin: Int = 32) = Obj(U8(minOf(builtin, 127)), custom?.let { Ref(Str(it)) }, U32(1), U32(builtin.toLong()))

    /**
     * A TFLite model with [ops] and one metadata entry per [metadata] name, each in a buffer holding its bytes; with
     * [outside], the stamp's bytes sit after the flatbuffer (buffer offset and size), as "buffers outside flatbuffers" does.
     */
    fun tflite(ops: List<Obj>, metadata: Map<String, ByteArray> = emptyMap(), outside: Boolean = false): ByteArray {
        val names = metadata.keys.toList()
        if (!outside) {
            return flatbuffer(Obj(U32(3), Ref(Vec(ops)), null, null,
                Ref(Vec(listOf(Obj()) + names.map { Obj(Ref(Bytes(metadata.getValue(it)))) })), null,
                Ref(Vec(names.mapIndexed { i, n -> Obj(Ref(Str(n)), U32((i + 1).toLong())) }))), ident = "TFL3")
        }
        // Two passes: the flatbuffer's length doesn't depend on the offsets (fixed-width fields).
        fun fb(at: List<Long>) = flatbuffer(Obj(U32(3), Ref(Vec(ops)), null, null,
            Ref(Vec(listOf(Obj()) + names.mapIndexed { i, n -> Obj(null, U64(at[i]), U64(metadata.getValue(n).size.toLong())) })), null,
            Ref(Vec(names.mapIndexed { i, n -> Obj(Ref(Str(n)), U32((i + 1).toLong())) }))), ident = "TFL3")
        val base = fb(names.map { 1L }).size.toLong()
        val at = ArrayList<Long>()
        var p = align(base)
        for (n in names) { at += p; p = align(p + metadata.getValue(n).size) }
        val head = fb(at)
        val out = ByteArray(p.toInt())
        System.arraycopy(head, 0, out, 0, head.size)
        for ((i, n) in names.withIndex()) { val d = metadata.getValue(n); System.arraycopy(d, 0, out, at[i].toInt(), d.size) }
        return out
    }

    /** A LiteRtStamp's bytes: NUL-separated strings, padded with NULs as Google's are. */
    fun stamp(vararg parts: String): ByteArray = (parts.joinToString("\u0000") + "\u0000".repeat(24)).toByteArray(Charsets.ISO_8859_1)

    // ------------------------------------------------------------------ LlmMetadata (protobuf)

    private fun varint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); return }
            o.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun pbInt(field: Int, v: Long): ByteArray = ByteArrayOutputStream().also { varint(it, (field shl 3).toLong()); varint(it, v) }.toByteArray()
    fun pbBytes(field: Int, b: ByteArray): ByteArray = ByteArrayOutputStream().also { varint(it, ((field shl 3) or 2).toLong()); varint(it, b.size.toLong()); it.write(b) }.toByteArray()
    fun pbStr(field: Int, s: String) = pbBytes(field, s.toByteArray(Charsets.UTF_8))

    /** Gemma 4's metadata as Google's files have it (tool tokens; [tools] false leaves them out). */
    fun gemma4(tools: Boolean = true): ByteArray {
        val g = if (tools) pbStr(5, "<|tool_call>") + pbStr(6, "<tool_call|>") + pbStr(10, "<|\"|>") + pbStr(12, "<|tool_response>")
        else pbInt(15, 16)
        return pbBytes(8, g)
    }

    /** LlmMetadata with [modelType] (an LlmModelType message's bytes), a chat template, and [maxTokens] when > 0. */
    fun llmMetadata(modelType: ByteArray, maxTokens: Int = 0): ByteArray =
        pbBytes(2, pbBytes(1, pbStr(1, "\u0001"))) + (if (maxTokens > 0) pbInt(5, maxTokens.toLong()) else ByteArray(0)) +
            pbBytes(6, modelType) + pbStr(7, "{{ messages }}")

    /** generic_model, empty (as the renamed "Tensor G5" CPU export has it). */
    val GENERIC: ByteArray get() = pbBytes(1, ByteArray(0))
}
