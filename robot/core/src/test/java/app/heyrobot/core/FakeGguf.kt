// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.io.ByteArrayOutputStream

/**
 * Builds small GGUF files for the content check's tests (0.4.5): the header, metadata of every GGUF type, and tensors
 * whose data really follows (zeros), laid out as llama.cpp writes them (in order, each padded to the alignment).
 */
object FakeGguf {
    /** A tensor: its name, shape (fastest dimension first), ggml type and, when given, a forced offset. */
    data class Tensor(val name: String, val dims: List<Long>, val type: Int = F32, val offset: Long? = null)

    const val F32 = 0
    const val F16 = 1
    const val Q4_0 = 2
    const val Q8_0 = 8
    const val Q4_K = 12
    const val Q6_K = 14

    /** Elements and bytes per block, as GgufCheck has them (only the types these tests use). */
    private val BLOCKS = mapOf(F32 to (1 to 4), F16 to (1 to 2), Q4_0 to (32 to 18), Q8_0 to (32 to 34), Q4_K to (256 to 144), Q6_K to (256 to 210))

    /** An array value of [type] (a GGUF value type) whose elements are [items] (Strings, Numbers or Booleans). */
    data class Arr(val type: Int, val items: List<Any>)

    /** A scalar written as a given GGUF value type (Int and Long values default to UINT32, Strings to STRING). */
    data class Typed(val type: Int, val value: Any)

    class Out {
        private val b = ByteArrayOutputStream()
        val size: Int get() = b.size()
        fun bytes(x: ByteArray) = apply { b.write(x) }
        fun u8(v: Int) = apply { b.write(v and 0xff) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Long) = apply { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        fun u64(v: Long) = apply { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
        fun str(s: String) = apply { val x = s.toByteArray(Charsets.UTF_8); u64(x.size.toLong()); bytes(x) }
        fun toByteArray(): ByteArray = b.toByteArray()
    }

    private fun scalar(o: Out, type: Int, v: Any) {
        when (type) {
            0, 1, 7 -> o.u8(if (v is Boolean) (if (v) 1 else 0) else (v as Number).toInt())
            2, 3 -> o.u16((v as Number).toInt())
            4, 5 -> o.u32((v as Number).toLong())
            6 -> o.u32(java.lang.Float.floatToIntBits((v as Number).toFloat()).toLong())
            8 -> o.str(v as String)
            10, 11 -> o.u64((v as Number).toLong())
            12 -> o.u64(java.lang.Double.doubleToLongBits((v as Number).toDouble()))
            else -> error("type $type")
        }
    }

    private fun value(o: Out, v: Any) {
        when (v) {
            is Typed -> { o.u32(v.type.toLong()); scalar(o, v.type, v.value) }
            is Arr -> { o.u32(9); o.u32(v.type.toLong()); o.u64(v.items.size.toLong()); v.items.forEach { scalar(o, v.type, it) } }
            is String -> { o.u32(8); o.str(v) }
            is Boolean -> { o.u32(7); o.u8(if (v) 1 else 0) }
            is Float -> { o.u32(6); scalar(o, 6, v) }
            is Int, is Long -> { o.u32(4); o.u32((v as Number).toLong()) }
            else -> error("value $v")
        }
    }

    fun nbytes(t: Tensor): Long {
        // A type the check doesn't know gets no data (the check refuses it before it gets that far).
        val (block, bytes) = BLOCKS[t.type] ?: (1 to 0)
        return t.dims.fold(1L) { a, d -> a * d } / block * bytes
    }

    private fun pad(n: Long, a: Long) = (n + a - 1) / a * a

    /**
     * A GGUF file: [magic], [version], the metadata [kv] in order, the [tensors], then their data (zeros). [alignment]: the
     * padding (32 unless general.alignment says otherwise); [cutData]: bytes of tensor data left out at the end.
     */
    fun gguf(kv: List<Pair<String, Any>>, tensors: List<Tensor>, version: Long = 3, magic: String = "GGUF", alignment: Long = 32, cutData: Long = 0,
        tensorCount: Long? = null, kvCount: Long? = null): ByteArray {
        val o = Out()
        o.bytes(magic.toByteArray(Charsets.ISO_8859_1)).u32(version).u64(tensorCount ?: tensors.size.toLong()).u64(kvCount ?: kv.size.toLong())
        for ((k, v) in kv) { o.str(k); value(o, v) }
        var at = 0L
        for (t in tensors) {
            o.str(t.name).u32(t.dims.size.toLong())
            t.dims.forEach { o.u64(it) }
            o.u32(t.type.toLong()).u64(t.offset ?: at)
            at += pad(nbytes(t), alignment)
        }
        if (tensors.isNotEmpty()) {
            val start = pad(o.size.toLong(), alignment)
            o.bytes(ByteArray((start - o.size).toInt()))
            o.bytes(ByteArray((at - cutData).coerceAtLeast(0).toInt()))
        }
        return o.toByteArray()
    }

    /** Gemma 3's chat template, shortened: the beginning of turn, roles and the generation prompt. */
    const val GEMMA3_TEMPLATE = "{{ bos_token }}{%- for message in messages -%}<start_of_turn>{{ message['role'] }}\n{{ message['content'] | trim }}<end_of_turn>\n" +
        "{%- endfor -%}{%- if add_generation_prompt -%}<start_of_turn>model\n{%- endif -%}"

    /** Qwen3's, shortened: ChatML with the thinking switch. */
    const val QWEN3_TEMPLATE = "{%- for message in messages %}<|im_start|>{{ message.role }}\n{{ message.content }}<|im_end|>\n{%- endfor %}" +
        "{%- if add_generation_prompt %}<|im_start|>assistant\n{%- if enable_thinking is defined and enable_thinking is false %}<think>\n\n</think>\n\n{%- endif %}{%- endif %}"

    /** The tensors of a tiny decoder: embeddings, two blocks of attention and feed-forward, the output norm. */
    fun tensors(type: Int = Q8_0): List<Tensor> = listOf(Tensor("token_embd.weight", listOf(256, 64), type)) +
        (0 until 2).flatMap { i ->
            listOf(Tensor("blk.$i.attn_q.weight", listOf(256, 256), type), Tensor("blk.$i.attn_k.weight", listOf(256, 128), type),
                Tensor("blk.$i.attn_norm.weight", listOf(256), F32), Tensor("blk.$i.ffn_up.weight", listOf(256, 512), Q4_K))
        } + Tensor("output_norm.weight", listOf(256), F32)

    /** Like unsloth's MedGemma 1.5 4B Q4_K_M: gemma3, 34 blocks, 4 KV heads of 256, 131,072 tokens of context. */
    fun medgemma(template: String? = GEMMA3_TEMPLATE, context: Long = 131_072, arch: String = "gemma3", tokens: Int = 64,
        extra: List<Pair<String, Any>> = emptyList(), tokenizer: String? = "llama"): ByteArray = gguf(listOfNotNull(
        "general.architecture" to arch,
        "general.type" to "model",
        "general.name" to "Medgemma-1.5-4B-It",
        "general.size_label" to "4B",
        "general.license" to "other",
        "$arch.context_length" to context,
        "$arch.embedding_length" to 2560,
        "$arch.block_count" to 34,
        "$arch.feed_forward_length" to 10240,
        "$arch.attention.head_count" to 8,
        "$arch.attention.head_count_kv" to 4,
        "$arch.attention.key_length" to 256,
        "$arch.attention.value_length" to 256,
        "$arch.rope.freq_base" to 1_000_000f,
        "general.file_type" to 15,
        "general.quantization_version" to 2,
        tokenizer?.let { "tokenizer.ggml.model" to it },
        "tokenizer.ggml.tokens" to Arr(8, (0 until tokens).map { "tok$it" }),
        "tokenizer.ggml.scores" to Arr(6, (0 until tokens).map { 0f }),
        "tokenizer.ggml.token_type" to Arr(5, (0 until tokens).map { 1 }),
        "tokenizer.ggml.bos_token_id" to 2,
        "tokenizer.ggml.add_bos_token" to true,
        template?.let { "tokenizer.chat_template" to it },
    ) + extra, tensors())

    /** Like Qwen's Qwen3-1.7B Q8_0: qwen3, 28 blocks, 8 KV heads of 128, 40,960 tokens, a template with enable_thinking. */
    fun qwen3(template: String = QWEN3_TEMPLATE, arch: String = "qwen3"): ByteArray = gguf(listOf(
        "general.architecture" to arch,
        "general.name" to "Qwen3 1.7B",
        "$arch.block_count" to 28,
        "$arch.context_length" to 40_960,
        "$arch.embedding_length" to 2048,
        "$arch.attention.head_count" to 16,
        "$arch.attention.head_count_kv" to 8,
        "$arch.attention.key_length" to 128,
        "$arch.attention.value_length" to 128,
        "general.file_type" to 7,
        "tokenizer.ggml.model" to "gpt2",
        "tokenizer.ggml.pre" to "qwen2",
        "tokenizer.ggml.tokens" to Arr(8, listOf("a", "b", "<think>", "</think>")),
        "tokenizer.ggml.merges" to Arr(8, listOf("a b")),
        "tokenizer.chat_template" to template,
    ), tensors(Q8_0))
}
