package app.heyrobot.core

// Pure. Spells a wake phrase the way the keyword model wants it: sherpa-onnx's text2token(..., tokens_type="bpe")
// is SentencePiece's encode of the upper-cased phrase with the model's kws/bpe.model. That file is, despite its
// name, a unigram model (trainer_spec.model_type = UNIGRAM, 500 pieces, upper case, "▁" before each word). It is a
// protobuf ModelProto, read here by hand, so the main app's Wake words screen can check a phrase without any native
// code (the main process never loads sherpa-onnx). SentencePieceTest checks the result against 115 phrases encoded
// by the Python reference.

class SentencePieceModel private constructor(
    val type: ModelType,
    val pieces: List<Piece>,
    val addDummyPrefix: Boolean,
    val removeExtraWhitespaces: Boolean,
    val escapeWhitespaces: Boolean,
    /** NormalizerSpec.precompiled_charsmap is set: the text is normalised (NFKC or a custom map) before it is encoded. */
    val normalizes: Boolean = false,
) {
    /** TrainerSpec.ModelType. */
    enum class ModelType(val proto: Int) { UNIGRAM(1), BPE(2), WORD(3), CHAR(4) }

    /** ModelProto.SentencePiece.Type. */
    enum class PieceType(val proto: Int) { NORMAL(1), UNKNOWN(2), CONTROL(3), USER_DEFINED(4), UNUSED(5), BYTE(6) }

    data class Piece(val text: String, val score: Float, val type: PieceType)

    companion object {
        // ModelProto: 1 pieces, 2 trainer_spec, 3 normalizer_spec. SentencePiece: 1 piece, 2 score, 3 type.
        // TrainerSpec: 3 model_type. NormalizerSpec: 2 precompiled_charsmap, 3 add_dummy_prefix, 4 remove_extra_whitespaces,
        // 5 escape_whitespaces.
        fun parse(bytes: ByteArray): SentencePieceModel {
            val r = Proto(bytes, 0, bytes.size)
            val pieces = mutableListOf<Piece>()
            var type = ModelType.UNIGRAM
            var dummy = true
            var extra = true
            var escape = true
            var normalizes = false
            while (!r.done) {
                val (field, wire) = r.tag()
                when {
                    field == 1 && wire == 2 -> pieces += piece(r.message())
                    field == 2 && wire == 2 -> {
                        val t = r.message()
                        while (!t.done) {
                            val (f, w) = t.tag()
                            if (f == 3 && w == 0) {
                                val v = t.varint().toInt()
                                type = ModelType.entries.firstOrNull { it.proto == v } ?: throw IllegalArgumentException("model type $v")
                            } else t.skip(w)
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val n = r.message()
                        while (!n.done) {
                            val (f, w) = n.tag()
                            when {
                                f == 3 && w == 0 -> dummy = n.varint() != 0L
                                f == 4 && w == 0 -> extra = n.varint() != 0L
                                f == 5 && w == 0 -> escape = n.varint() != 0L
                                f == 2 && w == 2 -> if (!n.message().done) normalizes = true
                                else -> n.skip(w)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            require(pieces.isNotEmpty()) { "no pieces" }
            return SentencePieceModel(type, pieces, dummy, extra, escape, normalizes)
        }

        private fun piece(r: Proto): Piece {
            var text = ""
            var score = 0f
            var type = PieceType.NORMAL
            while (!r.done) {
                val (f, w) = r.tag()
                when {
                    f == 1 && w == 2 -> text = r.string()
                    f == 2 && w == 5 -> score = Float.fromBits(r.fixed32())
                    f == 3 && w == 0 -> {
                        val v = r.varint().toInt()
                        type = PieceType.entries.firstOrNull { it.proto == v } ?: throw IllegalArgumentException("piece type $v")
                    }
                    else -> r.skip(w)
                }
            }
            return Piece(text, score, type)
        }
    }

    /** A protobuf reader over [b] from [pos] to [end]: just what ModelProto needs (no groups). */
    private class Proto(private val b: ByteArray, private var pos: Int, private val end: Int) {
        val done: Boolean get() = pos >= end

        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                require(pos < end && shift < 64) { "truncated varint" }
                val x = b[pos++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return v
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun fixed32(): Int {
            require(end - pos >= 4) { "truncated fixed32" }
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        private fun length(): Int {
            val n = varint()
            require(n in 0..(end - pos).toLong()) { "bad length" }
            return n.toInt()
        }

        fun message(): Proto {
            val n = length()
            return Proto(b, pos, pos + n).also { pos += n }
        }

        fun string(): String {
            val n = length()
            return String(b, pos, n, Charsets.UTF_8).also { pos += n }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { require(end - pos >= 8); pos += 8 }
                2 -> {
                    // Not "pos += length()": that would add to pos as it was before the length was read.
                    val n = length()
                    pos += n
                }
                5 -> { require(end - pos >= 4); pos += 4 }
                else -> throw IllegalArgumentException("wire type $wire")
            }
        }
    }
}

/**
 * SentencePiece's unigram encoding (unigram_model.cc): the segmentation of the normalised text into pieces whose
 * scores add up to the most, found by Viterbi over every piece that matches at each position. Sums are Float, as
 * there; ties go to the earlier split, as there. A character no piece covers becomes an unknown piece with the
 * smallest score minus 10, returned as the character itself (as encode(out_type=str) does), so a caller that checks
 * the result against tokens.txt refuses it.
 */
class SentencePieceEncoder(private val model: SentencePieceModel) {
    private class Entry(val text: String, val score: Float, val userDefined: Boolean)

    private val byText = HashMap<String, Entry>()
    private val maxLen: Int
    private val unkScore: Float
    private val maxScore: Float

    init {
        require(model.type == SentencePieceModel.ModelType.UNIGRAM) { "only unigram models (this one is ${model.type})" }
        val normal = model.pieces.filter { it.type == SentencePieceModel.PieceType.NORMAL }
        require(normal.isNotEmpty()) { "no normal pieces" }
        // Normal and user-defined pieces are matched; unused, control, unknown and byte pieces are not.
        for (p in model.pieces) {
            if (p.type == SentencePieceModel.PieceType.NORMAL || p.type == SentencePieceModel.PieceType.USER_DEFINED) {
                byText.putIfAbsent(p.text, Entry(p.text, p.score, p.type == SentencePieceModel.PieceType.USER_DEFINED))
            }
        }
        maxLen = byText.keys.maxOf { it.codePointCount(0, it.length) }
        unkScore = normal.minOf { it.score } - UNK_PENALTY
        maxScore = normal.maxOf { it.score }
    }

    /** SentencePiece's whitespace handling for the characters a wake phrase may hold (ASCII letters, apostrophes, spaces). */
    fun normalize(text: String): String {
        var s = text
        if (model.removeExtraWhitespaces) s = s.trim(' ').replace(MANY_SPACES, " ")
        if (s.isEmpty()) return ""
        if (model.addDummyPrefix) s = " $s"
        if (model.escapeWhitespaces) s = s.replace(' ', SPACE)
        return s
    }

    /** The pieces of [text] (normalised first), in order. */
    fun encode(text: String): List<String> {
        val s = normalize(text)
        if (s.isEmpty()) return emptyList()
        val cps = s.codePoints().toArray()
        val n = cps.size
        val best = FloatArray(n + 1)
        val from = IntArray(n + 1) { -1 }
        val piece = arrayOfNulls<String>(n + 1)
        val unknown = BooleanArray(n + 1)
        for (start in 0 until n) {
            if (start > 0 && from[start] < 0) continue
            val here = best[start]
            var single = false
            for (len in 1..minOf(maxLen, n - start)) {
                val sub = String(cps, start, len)
                val e = byText[sub] ?: continue
                val score = if (e.userDefined) len * maxScore - USER_DEFINED_PENALTY else e.score
                val cand = here + score
                val end = start + len
                if (from[end] < 0 || cand > best[end]) {
                    best[end] = cand
                    from[end] = start
                    piece[end] = e.text
                    unknown[end] = false
                }
                if (len == 1) single = true
            }
            if (!single) {
                val cand = here + unkScore
                val end = start + 1
                if (from[end] < 0 || cand > best[end]) {
                    best[end] = cand
                    from[end] = start
                    piece[end] = String(cps, start, 1)
                    unknown[end] = true
                }
            }
        }
        val out = ArrayList<String>()
        var at = n
        // Unknown characters next to each other come out as one piece, as SentencePiece reports them.
        var prevUnknown = false
        while (at > 0) {
            val p = piece[at]!!
            if (unknown[at] && prevUnknown) out[out.size - 1] = p + out[out.size - 1] else out += p
            prevUnknown = unknown[at]
            at = from[at]
        }
        out.reverse()
        return out
    }

    companion object {
        /** SentencePiece's whitespace mark. */
        const val SPACE = '▁'
        private const val UNK_PENALTY = 10f
        private const val USER_DEFINED_PENALTY = 0.1f
        private val MANY_SPACES = Regex(" {2,}")
    }
}
