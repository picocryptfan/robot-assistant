package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// Pure (java.io). What a .litertlm file holds, read from its header without loading it (0.4.1, "your own model file"):
// the LiteRT-LM header's sections, the decoder's TFLite operator codes and metadata, and the LlmMetadata proto. A Kotlin
// port of the 0.4.1 research's inspect_litertlm.py. Robot runs a file on the TPU only when every sign of a Tensor G5
// build is there: a tf_lite_aux section, a prefill_decode whose operators include DISPATCH_OP, a LiteRtStamp naming
// Tensor_G5, and Gemma 4 metadata. Never by its name: a "…_Google_Tensor_G5.litertlm" can be a plain CPU export, which
// LiteRT-LM 0.18.0 would then run on the CPU quietly while Robot believed it was on the TPU.
//
// The file is untrusted: every offset is checked against the file and its section, every count and string is capped,
// and anything unexpected ends the check as "unreadable" (never an exception, never a large read).

/** Random access to a file's bytes. */
interface ByteSource {
    val size: Long

    /** Exactly [n] bytes at [pos]; throws [LiteRtLmFormat] when they aren't all in the file. */
    fun read(pos: Long, n: Int): ByteArray
}

class LiteRtLmFormat(why: String) : Exception(why)

class FileByteSource(file: File) : ByteSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(pos: Long, n: Int): ByteArray {
        // Never pos + n: a position near Long.MAX_VALUE would overflow past the check.
        if (pos < 0 || n < 0 || pos > size - n) throw LiteRtLmFormat("outside the file")
        val b = ByteArray(n)
        raf.seek(pos)
        raf.readFully(b)
        return b
    }

    override fun close() = raf.close()
}

/**
 * The bytes of a file already open (0.4.5): :brain reads a GGUF file's header through the very file it then hands to
 * llama.cpp, never through its name again. The caller owns [channel] and closes it.
 */
class ChannelByteSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()

    override fun read(pos: Long, n: Int): ByteArray {
        if (pos < 0 || n < 0 || pos > size - n) throw LiteRtLmFormat("outside the file")
        val b = ByteBuffer.allocate(n)
        var at = pos
        while (b.hasRemaining()) {
            val k = channel.read(b, at)
            // The file shrank under the read (or a read that makes no progress): never a loop, never a short read.
            if (k <= 0) throw LiteRtLmFormat("outside the file")
            at += k
        }
        return b.array()
    }
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(pos: Long, n: Int): ByteArray {
        if (pos < 0 || n < 0 || pos > bytes.size.toLong() - n) throw LiteRtLmFormat("outside the file")
        return bytes.copyOfRange(pos.toInt(), pos.toInt() + n)
    }
}

/** What the check found. Content from the file ([stamp], [modelType]) is for the screen only, never logged. */
data class LiteRtLmReport(
    /** A .litertlm whose header Robot could read. */
    val readable: Boolean,
    /** "1.5.0". */
    val format: String = "",
    /** The sections' model types ("tf_lite_prefill_decode", …) or data types ("LlmMetadataProto", …). */
    val sections: List<String> = emptyList(),
    /** A tf_lite_prefill_decode section: a language model. */
    val decoder: Boolean = false,
    /** A tf_lite_aux section (LiteRT-LM's NPU executor needs it). */
    val aux: Boolean = false,
    /** The decoder's operators include DISPATCH_OP: compiled code for an NPU. */
    val dispatchOp: Boolean = false,
    /** The decoder's LiteRtStamp, printable ("Google Tensor_G5"); null: none. */
    val stamp: String? = null,
    /** LlmMetadata's model type ("gemma4", "generic_model", …); null: none found. */
    val modelType: String? = null,
    /** Gemma 4's tool-call tokens: "<|tool_call>" opens a call and "<|tool_response>" an answer. */
    val toolTokens: Boolean = false,
    /** LlmMetadata's max_num_tokens (0: not stated, as in Google's own files). Robot's context for it: [contextTokens]. */
    val maxTokens: Int = 0,
    /** A vision encoder section: it can look at photos. */
    val vision: Boolean = false,
    /** Why the file couldn't be read ([readable] false). */
    val problem: String? = null,
) {
    val gemma4: Boolean get() = modelType == "gemma4"

    /** 0.8: the Tensor chip the stamp names ("Tensor_G5", "Tensor_G6"), or null (none, or a chip Robot doesn't know). */
    val tensorStamp: Chip? get() = LiteRtLmCheck.chipOf(stamp)

    /**
     * The chip whose TPU it was compiled for, by every sign Robot checks (never by the file's name): the aux section,
     * DISPATCH_OP, Gemma 4 metadata and the stamp; null when it isn't a complete TPU build. 0.8: any Tensor the stamp
     * names (the Tensor G5 or G6); whether it runs here is [runsOnTpuOf] (the phone's own chip, and a TPU Robot uses).
     */
    val tensorBuild: Chip? get() = if (readable && decoder && aux && dispatchOp && gemma4) tensorStamp else null

    /** A complete TPU build for [p]'s chip, which Robot runs on that phone's TPU (Phones.tpu: supported, or in beta). */
    fun runsOnTpuOf(p: Phone): Boolean = tensorBuild != null && tensorBuild == p.chip && p.tpu != TpuSupport.NONE

    /**
     * 0.4.3: built for 2,048 to 4,095 tokens of context. Robot runs it as chat only (no tools, no actions, a shorter
     * memory of the conversation), loaded with its own context ([contextTokens]).
     */
    val shortContext: Boolean get() = maxTokens in ContextWindow.MIN_TOKENS until ContextWindow.MAX_TOKENS

    /**
     * The context Robot loads it with (ContextWindow.forFile): 4,096 when the file states none or at least that, its own
     * when shorter ([shortContext]); 0 when it is too short to run at all.
     */
    val contextTokens: Int get() = ContextWindow.forFile(maxTokens) ?: 0

    /** Why Robot can't run it here at all; null when it can. */
    val unusable: String? get() = when {
        !readable -> problem ?: "It isn't a LiteRT-LM model file (.litertlm)."
        !decoder -> "It holds no language model (no prefill_decode section)."
        dispatchOp && tensorBuild == null && tensorStamp != null ->
            "It holds TPU code stamped for the ${tensorStamp!!.title} but not everything a complete ${tensorStamp!!.title} build has (" +
                listOfNotNull(if (!aux) "the aux section" else null, if (!gemma4) "Gemma 4 metadata" else null).joinToString(" and ") +
                "), so Robot won't run it on the TPU, and the CPU can't run TPU code."
        dispatchOp && tensorBuild == null -> "It was compiled for another chip's NPU" + (stamp?.let { " ($it)" } ?: "") +
            ", so this phone can run it on neither its TPU nor its CPU."
        maxTokens in 1 until ContextWindow.MIN_TOKENS -> "It was built for only $maxTokens tokens of context; Robot needs at least " +
            "${ContextWindow.MIN_TOKENS} to chat (${ContextWindow.MAX_TOKENS} for actions)."
        else -> null
    }

    /** Where it can run: the TPU for a proven TPU build (on its own chip only: [runsOnTpuOf]), else the CPU; null: nowhere. */
    val backend: ModelBackend? get() = when {
        unusable != null -> null
        tensorBuild != null -> ModelBackend.TPU
        else -> ModelBackend.CPU
    }

    /** What the check found, a line each, for Settings › Model. */
    fun lines(): List<String> = buildList {
        if (!readable) {
            add(unusable ?: "Robot couldn't read it.")
            return@buildList
        }
        add("LiteRT-LM file, format $format, ${sections.size} sections.")
        add(when {
            tensorBuild != null -> "Compiled for the ${tensorBuild!!.title}'s TPU (${Phones.seriesOf(tensorBuild!!)}): yes — aux section, " +
                "DISPATCH_OP and the stamp “${stamp.orEmpty()}”."
            dispatchOp -> "Compiled for an NPU" + (stamp?.let { " (stamp “$it”)" } ?: "") + ", but not as a complete Tensor TPU build."
            else -> "Compiled for a TPU: no. It runs on the CPU."
        })
        // Chat only: no actions whatever the metadata, so the line about them would only confuse.
        add(when {
            shortContext && gemma4 -> "Gemma 4."
            shortContext && modelType != null -> "Model type “$modelType”, not Gemma 4: Robot is written for Gemma 4, so answers may be odd."
            shortContext -> "No model metadata Robot recognises: answers may be odd."
            gemma4 && toolTokens -> "Gemma 4, with its tool-calling metadata: actions should work."
            gemma4 -> "Gemma 4, but without its tool-calling metadata: actions (timers, texts, notes…) may not work."
            modelType != null -> "Model type “$modelType”, not Gemma 4: Robot is written for Gemma 4, so answers may be odd and actions may not work."
            else -> "No model metadata Robot recognises: answers may be odd and actions may not work."
        })
        add(if (vision) "Can see photos: yes." else "Can see photos: no (questions about a photo won't work).")
        when {
            shortContext && unusable == null -> add("Short context ($maxTokens tokens): chat only — no actions, shorter memory.")
            maxTokens >= ContextWindow.MAX_TOKENS -> add("Context: $maxTokens tokens (Robot uses ${ContextWindow.MAX_TOKENS}).")
        }
        unusable?.let { add(it) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("readable", readable); put("format", format); put("sections", JSONArray(sections)); put("decoder", decoder); put("aux", aux)
        // "tensorG5Stamp" for the records an older Robot reads (0.8 reads the chip from the stamp).
        put("dispatchOp", dispatchOp); stamp?.let { put("stamp", it) }; put("tensorG5Stamp", tensorStamp == Chip.TENSOR_G5); modelType?.let { put("modelType", it) }
        put("toolTokens", toolTokens); put("maxTokens", maxTokens); put("vision", vision); problem?.let { put("problem", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): LiteRtLmReport? {
            if (o == null) return null
            return try {
                LiteRtLmReport(
                    readable = o.getBoolean("readable"), format = o.optString("format", "").take(16),
                    sections = o.optJSONArray("sections")?.let { a -> (0 until minOf(a.length(), LiteRtLmCheck.MAX_SECTIONS)).map { a.optString(it).take(64) } } ?: emptyList(),
                    decoder = o.optBoolean("decoder"), aux = o.optBoolean("aux"), dispatchOp = o.optBoolean("dispatchOp"),
                    stamp = if (o.has("stamp")) o.optString("stamp").take(LiteRtLmCheck.MAX_STAMP) else null,
                    modelType = if (o.has("modelType")) o.optString("modelType").take(32) else null, toolTokens = o.optBoolean("toolTokens"),
                    maxTokens = o.optInt("maxTokens"), vision = o.optBoolean("vision"), problem = if (o.has("problem")) o.optString("problem") else null,
                )
            } catch (_: Exception) { null }
        }
    }
}

object LiteRtLmCheck {
    const val MAGIC = "LITERTLM"

    /**
     * 0.8: the Tensor chip a LiteRtStamp names, one of its words exactly ("Google Tensor_G6" -> Tensor G6; Android's
     * Build.SOC_MODEL says "Tensor G6"); null for none, or a chip Robot doesn't know.
     */
    fun chipOf(stamp: String?): Chip? {
        val word = stamp?.split(' ')?.firstOrNull { Regex("Tensor_G[0-9]+").matches(it) } ?: return null
        val title = word.replace('_', ' ')
        return Chip.entries.firstOrNull { it.tensor && it.title == title }
    }
    /** The header flatbuffer, sections, operator codes, metadata entries, strings and stamps are capped at these. */
    const val MAX_HEADER = 4 shl 20
    const val MAX_SECTIONS = 64
    const val MAX_OPCODES = 4096
    const val MAX_METADATA = 1024
    const val MAX_STRING = 256
    const val MAX_STAMP = 80
    const val MAX_LLM_METADATA = 1 shl 20

    /** The Gemma 4 tool tokens (LlmMetadata › Gemma4 code_fence_start and function_response_start). */
    const val TOOL_CALL = "<|tool_call>"
    const val TOOL_RESPONSE = "<|tool_response>"

    /** LiteRT-LM's AnySectionDataType, by number. */
    private val DATA_TYPES = listOf("NONE", "GenericBinaryData", "Deprecated", "TFLiteModel", "SP_Tokenizer", "LlmMetadataProto",
        "HF_Tokenizer_Zlib", "TFLiteWeights", "EmbeddingMetadataProto", "ExecutorMetadataProto", "TtsMetadataProto", "AsrMetadataProto",
        "ImageGenMetadataProto")
    private const val TFLITE = 3
    private const val SP_TOKENIZER = 4
    private const val LLM_METADATA = 5
    private const val HF_TOKENIZER_ZLIB = 6
    /** A tokenizer section (Gemma 4's SentencePiece model is 4.7 MB), and a Hugging Face tokenizer.json once inflated. */
    const val MAX_TOKENIZER = 32 shl 20
    const val MAX_TOKENIZER_JSON = 32 shl 20

    /** LlmModelType's oneof, by field number (runtime/proto/llm_model_type.proto). */
    private val MODEL_TYPES = mapOf(1 to "generic_model", 2 to "gemma3n", 3 to "function_gemma", 4 to "gemma3", 5 to "qwen3", 7 to "qwen2p5",
        8 to "gemma4", 9 to "fast_vlm", 11 to "lfm2", 12 to "minicpm5", 13 to "minicpmv4")

    fun check(file: File, strict: Boolean = false): LiteRtLmReport = try {
        FileByteSource(file).use { check(it, strict) }
    } catch (_: Exception) {
        LiteRtLmReport(readable = false, problem = "Robot couldn't read the file.")
    }

    /**
     * [strict] (your own imported file, 1.0): [strictProblem] first, and a file it refuses is unreadable, so neither the
     * app's record nor :brain's load ever hands it to LiteRT-LM.
     */
    fun check(src: ByteSource, strict: Boolean): LiteRtLmReport {
        if (strict) strictProblem(src)?.let { return LiteRtLmReport(readable = false, problem = "Robot won't open this file: $it.") }
        return check(src)
    }

    /** Sections this far apart, or this much after the last one, mean the file isn't what its header says. */
    const val MAX_GAP = 1L shl 20

    /** Known section data types for a file Robot runs: AnySectionDataType without NONE (0) and Deprecated (2). */
    private val STRICT_TYPES = setOf(1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    private val MODEL_TYPE_NAME = Regex("[a-z0-9_]{1,64}")

    /**
     * 1.0 audit (native-01, ipc-5): your own .litertlm is parsed by LiteRT-LM's native code in :brain, which has Robot's
     * permissions (it needs the GPU and the TPU, so it can't be isolated as a .gguf is). Before LiteRT-LM ever sees an
     * imported file, its container is checked strictly here, and anything odd refuses it: the magic and a 1.x format; a
     * header that fits the file; one to [MAX_SECTIONS] sections, each of a known data type, non-empty, after the header and
     * inside the file, none overlapping another; TFLite sections named "tf_lite_…" and starting as TFLite flatbuffers do
     * ("TFL3"); exactly one prefill_decode model; metadata and tokenizers within their caps; and no more than [MAX_GAP]
     * unaccounted for between sections or after the last (the file's size matches what its header describes). Null: it
     * passes; otherwise why not, in words. Never an exception, never a large read.
     */
    fun strictProblem(src: ByteSource): String? = try {
        strictOrThrow(src)
    } catch (e: LiteRtLmFormat) {
        e.message ?: "it can't be read"
    } catch (_: Exception) {
        "it can't be read"
    }

    private fun strictOrThrow(src: ByteSource): String? {
        if (src.size < 32) return "it is too short"
        val head = src.read(0, 32)
        if (String(head, 0, 8, Charsets.ISO_8859_1) != MAGIC) return "it isn't a LiteRT-LM file"
        val le = Le(head)
        if (le.u32(8) != 1L) return "its format version (${le.u32(8)}) isn't one Robot knows"
        val headerEnd = le.u64(24)
        if (headerEnd <= 32 || headerEnd > src.size || headerEnd - 32 > MAX_HEADER) return "its header doesn't fit the file"
        val (_, sections) = sections(src) ?: return "it isn't a LiteRT-LM file"
        if (sections.isEmpty()) return "it has no sections"
        for (s in sections) {
            if (s.dataType !in STRICT_TYPES) return "a section has an unknown type (${s.dataType})"
            if (s.begin < headerEnd) return "a section overlaps the header"
            if (s.end <= s.begin) return "a section is empty"
            if (s.modelType != null && !MODEL_TYPE_NAME.matches(s.modelType)) return "a section's name isn't one Robot accepts"
            val size = s.end - s.begin
            when (s.dataType) {
                TFLITE -> {
                    if (s.modelType == null || !s.modelType.startsWith("tf_lite_")) return "a TFLite section isn't named as one"
                    if (size < 8 || String(src.read(s.begin + 4, 4), Charsets.ISO_8859_1) != "TFL3") return "a TFLite section isn't a TFLite model"
                }
                LLM_METADATA -> if (size > MAX_LLM_METADATA) return "its metadata is too large"
                SP_TOKENIZER -> if (size > MAX_TOKENIZER) return "its tokenizer is too large"
                HF_TOKENIZER_ZLIB -> {
                    if (size < 9 || size > MAX_TOKENIZER) return "its tokenizer's size is wrong"
                    val inflated = Le(src.read(s.begin, 8)).u64(0)
                    if (inflated < 1 || inflated > MAX_TOKENIZER_JSON) return "its tokenizer's size is wrong"
                }
            }
        }
        if (sections.count { it.modelType == "tf_lite_prefill_decode" && it.dataType == TFLITE } != 1) return "it doesn't have exactly one language model"
        if (sections.count { it.dataType == LLM_METADATA } > 1) return "it has more than one metadata section"
        val sorted = sections.sortedBy { it.begin }
        if (sorted.first().begin - headerEnd > MAX_GAP) return "its sections don't follow its header"
        for (i in 1 until sorted.size) {
            if (sorted[i].begin < sorted[i - 1].end) return "two sections overlap"
            if (sorted[i].begin - sorted[i - 1].end > MAX_GAP) return "its sections leave part of the file unaccounted for"
        }
        if (src.size - sorted.last().end > MAX_GAP) return "its size doesn't match its sections"
        return null
    }

    fun check(src: ByteSource): LiteRtLmReport = try {
        read(src)
    } catch (e: LiteRtLmFormat) {
        LiteRtLmReport(readable = false, problem = "It isn't a LiteRT-LM model file Robot can read (${e.message}).")
    } catch (_: Exception) {
        LiteRtLmReport(readable = false, problem = "It isn't a LiteRT-LM model file Robot can read.")
    }

    private class Section(val modelType: String?, val dataType: Int, val begin: Long, val end: Long)

    /** The header's format version and sections; null when the file doesn't start with [MAGIC]. */
    private fun sections(src: ByteSource): Pair<String, List<Section>>? {
        if (src.size < 32) throw LiteRtLmFormat("too short")
        val head = src.read(0, 32)
        if (String(head, 0, 8, Charsets.ISO_8859_1) != MAGIC) return null
        val le = Le(head)
        val format = "${le.u32(8)}.${le.u32(12)}.${le.u32(16)}"
        val headerEnd = le.u64(24)
        if (headerEnd <= 32 || headerEnd > src.size || headerEnd - 32 > MAX_HEADER) throw LiteRtLmFormat("bad header size")
        val fb = Fb(src, 32, headerEnd)
        val root = fb.root()
        return format to root.table(1)?.tables(0, MAX_SECTIONS).orEmpty().map { s ->
            val type = s.tables(0, MAX_SECTIONS).firstNotNullOfOrNull { kv -> if (kv.string(0) == "model_type") kvString(kv) else null }
            val begin = s.u64(1)
            val end = s.u64(2)
            if (begin < 0 || end < begin || end > src.size) throw LiteRtLmFormat("a section outside the file")
            Section(type, s.u8(3), begin, end)
        }
    }

    private fun read(src: ByteSource): LiteRtLmReport {
        val (format, sections) = sections(src) ?: return LiteRtLmReport(readable = false, problem = "It isn't a LiteRT-LM model file (.litertlm).")
        val names = sections.map { s -> s.modelType ?: DATA_TYPES.getOrElse(s.dataType) { i -> "type $i" } }
        val decoder = sections.firstOrNull { it.modelType == "tf_lite_prefill_decode" && it.dataType == TFLITE }
        var dispatch = false
        var stamp: String? = null
        if (decoder != null) {
            val model = Fb(src, decoder.begin, decoder.end).root()
            dispatch = model.tables(1, MAX_OPCODES).any { it.string(1) == "DISPATCH_OP" }
            stamp = stampOf(model)
        }
        val meta = sections.firstOrNull { it.dataType == LLM_METADATA }?.let { s ->
            if (s.end - s.begin > MAX_LLM_METADATA) throw LiteRtLmFormat("metadata too large")
            llmMetadata(src.read(s.begin, (s.end - s.begin).toInt()))
        }
        return LiteRtLmReport(
            readable = true, format = format, sections = names,
            decoder = decoder != null,
            aux = sections.any { it.modelType == "tf_lite_aux" },
            dispatchOp = dispatch, stamp = stamp,
            modelType = meta?.type, toolTokens = meta?.tools == true, maxTokens = meta?.maxTokens ?: 0,
            vision = sections.any { it.modelType == "tf_lite_vision_encoder" },
        )
    }

    /**
     * The special tokens of the file's own tokenizer, as LiteRT-LM 0.18.0 reads them from a prompt's text, and the
     * character that breaks one for it ([ModelTokens], for ControlText). LiteRT-LM renders the conversation with the
     * model's template and encodes the whole string (SentencePieceTokenizer: SentencePieceProcessor::Encode, which reads a
     * user-defined piece anywhere in the text and a control piece nowhere; HuggingFaceTokenizer: tokenizers' encode, which
     * reads every added token). Null when the file has no tokenizer Robot can read: then ControlText has only
     * PromptMarkers.KNOWN. Capped like the rest of the check; never an exception.
     */
    fun tokens(src: ByteSource): ModelTokens? = try {
        val sections = sections(src)?.second
        val sp = sections?.firstOrNull { it.dataType == SP_TOKENIZER }
        val hf = sections?.firstOrNull { it.dataType == HF_TOKENIZER_ZLIB }
        when {
            sp != null && sp.end - sp.begin in 1..MAX_TOKENIZER.toLong() -> sentencePieceTokens(src.read(sp.begin, (sp.end - sp.begin).toInt()))
            hf != null && hf.end - hf.begin in 9..MAX_TOKENIZER.toLong() -> huggingFaceTokens(src.read(hf.begin, (hf.end - hf.begin).toInt()))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * A SentencePiece model's control and unknown pieces, and its user-defined ones (read from text wherever they are).
     * The joiner breaks them when the model encodes it as itself (a piece for it, or byte fallback) and doesn't normalise
     * the text first; else a zero-width space when it has a piece for that; else a plain space (an unknown character would
     * become the model's unknown token: Qwen3's file's is "<|endoftext|>").
     */
    fun sentencePieceTokens(model: ByteArray): ModelTokens {
        val m = SentencePieceModel.parse(model)
        val control = ArrayList<String>()
        val user = ArrayList<String>()
        var bytes = false
        var joiner = false
        var zeroWidth = false
        for (p in m.pieces) when (p.type) {
            SentencePieceModel.PieceType.CONTROL, SentencePieceModel.PieceType.UNKNOWN -> control += p.text
            SentencePieceModel.PieceType.USER_DEFINED -> {
                user += p.text
                if (p.text == ControlText.JOINER) joiner = true
                if (p.text == ControlText.ZERO_WIDTH_SPACE) zeroWidth = true
            }
            SentencePieceModel.PieceType.BYTE -> bytes = true
            SentencePieceModel.PieceType.NORMAL -> {
                if (p.text == ControlText.JOINER) joiner = true
                if (p.text == ControlText.ZERO_WIDTH_SPACE) zeroWidth = true
            }
            SentencePieceModel.PieceType.UNUSED -> {}
        }
        val breaker = when {
            m.normalizes -> ControlText.SPACE
            joiner || bytes -> ControlText.JOINER
            zeroWidth -> ControlText.ZERO_WIDTH_SPACE
            else -> ControlText.SPACE
        }
        return ModelTokens(control, user, breaker)
    }

    /**
     * A HF_Tokenizer_Zlib section (LiteRT-LM's layout: the inflated size, 8 bytes little-endian, then zlib data): its
     * tokenizer.json's added tokens, special or not. The joiner breaks them for a byte-level or byte-fallback tokenizer
     * with no normaliser; anything else gets a plain space.
     */
    fun huggingFaceTokens(section: ByteArray): ModelTokens {
        val size = Le(section).u64(0)
        if (size <= 0 || size > MAX_TOKENIZER_JSON) throw LiteRtLmFormat("a tokenizer too large")
        val json = ByteArray(size.toInt())
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(section, 8, section.size - 8)
            var n = 0
            while (n < json.size && !inflater.finished()) {
                val k = inflater.inflate(json, n, json.size - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            if (n != json.size) throw LiteRtLmFormat("a tokenizer cut short")
        } finally {
            inflater.end()
        }
        val key = find(json, "\"added_tokens\"".toByteArray(), 0)
        val open = if (key < 0) -1 else (key until minOf(json.size, key + 64)).firstOrNull { json[it] == '['.code.toByte() } ?: -1
        val control = ArrayList<String>()
        val user = ArrayList<String>()
        if (open >= 0) {
            val close = arrayEnd(json, open)
            val added = JSONArray(String(json, open, close - open + 1, Charsets.UTF_8))
            for (i in 0 until minOf(added.length(), MAX_ADDED)) {
                val t = added.optJSONObject(i) ?: continue
                val text = t.optString("content")
                if (t.optBoolean("special", false)) control += text else user += text
            }
        }
        // The top level's "normalizer" (an added token has "normalized", and a Sequence "normalizers").
        val noNormalizer = literalAfter(json, "normalizer", "null")
        val bytes = find(json, "\"ByteLevel\"".toByteArray(), 0) >= 0 || literalAfter(json, "byte_fallback", "true")
        return ModelTokens(control, user, if (noNormalizer && bytes) ControlText.JOINER else ControlText.SPACE)
    }

    /** At most this many added tokens are read (Gemma's tokenizer.json has about 6,400; Qwen's, 26). */
    const val MAX_ADDED = 100_000

    /** The first value of "[key]" in the JSON [b] is the literal [value] (null, true…). */
    private fun literalAfter(b: ByteArray, key: String, value: String): Boolean {
        val at = find(b, "\"$key\"".toByteArray(), 0)
        if (at < 0) return false
        var i = at + key.length + 2
        while (i < b.size && (b[i] == ' '.code.toByte() || b[i] == ':'.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte() || b[i] == '\t'.code.toByte())) i++
        val v = value.toByteArray()
        return i + v.size <= b.size && (v.indices).all { b[i + it] == v[it] }
    }

    private fun find(b: ByteArray, what: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        while (i <= b.size - what.size) {
            var k = 0
            while (k < what.size && b[i + k] == what[k]) k++
            if (k == what.size) return i
            i++
        }
        return -1
    }

    /** The index of the ']' that closes the JSON array opening at [open] (strings and escapes skipped). */
    private fun arrayEnd(b: ByteArray, open: Int): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < b.size) {
            val c = b[i].toInt().toChar()
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        throw LiteRtLmFormat("an added-token list cut short")
    }

    /** A KeyValuePair's value when it is a string (value_type 9, StringValue). */
    private fun kvString(kv: Tbl): String? = if (kv.u8(1) == 9) kv.table(2)?.string(0) else null

    /** The decoder's LiteRtStamp: its buffer's NUL-separated strings, printable, spaced ("Google Tensor_G5"). */
    private fun stampOf(model: Tbl): String? {
        val entry = model.tables(6, MAX_METADATA).firstOrNull { it.string(0) == "LiteRtStamp" } ?: return null
        val index = entry.u32(1)
        val (start, count) = model.vector(4) ?: return null
        if (index >= count) throw LiteRtLmFormat("a stamp outside the buffers")
        val buffer = model.fb.tableAt(start + 4 * index + model.fb.u32(start + 4 * index))
        val data = buffer.vector(0)
        val bytes = when {
            data != null && data.second > 0 -> model.fb.bytes(data.first, minOf(data.second, 4096L).toInt())
            buffer.u64(1) > 0 -> {
                val off = buffer.u64(1)
                val n = minOf(buffer.u64(2), 4096L)
                if (off < 0 || n < 0) throw LiteRtLmFormat("a bad stamp")
                model.fb.bytes(off, n.toInt())
            }
            else -> return null
        }
        return bytes.toString(Charsets.ISO_8859_1).split('\u0000')
            .map { s -> s.filter { it in ' '..'~' }.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ").take(MAX_STAMP).ifEmpty { null }
    }

    private class Meta(val type: String?, val tools: Boolean, val maxTokens: Int)

    /** LlmMetadata: max_num_tokens (5) and llm_model_type (6), whose set field names the type; Gemma4's 5 and 12 are its tool tokens. */
    private fun llmMetadata(b: ByteArray): Meta {
        var max = 0
        var type: String? = null
        var tools = false
        Proto(b).fields { field, wire, value, sub ->
            when {
                field == 5 && wire == 0 -> max = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                field == 6 && wire == 2 -> Proto(sub!!).fields { f2, w2, _, inner ->
                    if (w2 == 2 && type == null) {
                        type = MODEL_TYPES[f2] ?: "type $f2"
                        if (f2 == 8) {
                            var call = false
                            var response = false
                            Proto(inner!!).fields { f3, w3, _, s3 ->
                                if (w3 == 2 && f3 == 5) call = s3!!.toString(Charsets.UTF_8) == TOOL_CALL
                                if (w3 == 2 && f3 == 12) response = s3!!.toString(Charsets.UTF_8) == TOOL_RESPONSE
                            }
                            tools = call && response
                        }
                    }
                }
            }
        }
        return Meta(type, tools, max)
    }

    /** Little-endian numbers in a byte array. */
    private class Le(val b: ByteArray) {
        fun u32(i: Int): Long = (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)
        fun u64(i: Int): Long = u32(i) or (u32(i + 4) shl 32)
    }

    /** A flatbuffer in [base, limit) of the file: offsets are relative to [base], and nothing outside is ever read. */
    private class Fb(val src: ByteSource, val base: Long, val limit: Long) {
        fun bytes(off: Long, n: Int): ByteArray {
            // Compared without adding: an offset from the file (a u64) can be near Long.MAX_VALUE.
            if (off < 0 || n < 0 || off > limit - base - n) throw LiteRtLmFormat("an offset outside its section")
            return src.read(base + off, n)
        }

        fun u8(off: Long): Int = bytes(off, 1)[0].toInt() and 0xff
        fun u16(off: Long): Int = Le(bytes(off, 2) + ByteArray(2)).u32(0).toInt()
        fun u32(off: Long): Long = Le(bytes(off, 4)).u32(0)
        fun i32(off: Long): Long = u32(off).toInt().toLong()
        fun u64(off: Long): Long = Le(bytes(off, 8)).u64(0)

        fun tableAt(pos: Long): Tbl = Tbl(this, pos)
        fun root(): Tbl = tableAt(u32(0))
    }

    private class Tbl(val fb: Fb, val pos: Long) {
        private val vt = pos - fb.i32(pos)
        private val vtLen = fb.u16(vt)

        /** The field's offset from the table, 0 when absent. */
        private fun field(slot: Int): Long {
            val o = 4 + 2 * slot
            return if (o + 2 > vtLen) 0 else fb.u16(vt + o).toLong()
        }

        fun table(slot: Int): Tbl? {
            val o = field(slot)
            if (o == 0L) return null
            val p = pos + o
            return fb.tableAt(p + fb.u32(p))
        }

        /** A vector's first element and its length. */
        fun vector(slot: Int): Pair<Long, Long>? {
            val o = field(slot)
            if (o == 0L) return null
            val p = pos + o
            val v = p + fb.u32(p)
            return v + 4 to fb.u32(v)
        }

        fun tables(slot: Int, max: Int): List<Tbl> {
            val (start, n) = vector(slot) ?: return emptyList()
            if (n > max) throw LiteRtLmFormat("too many entries")
            return (0 until n.toInt()).map { i -> val e = start + 4L * i; fb.tableAt(e + fb.u32(e)) }
        }

        fun string(slot: Int): String? {
            val (start, n) = vector(slot) ?: return null
            if (n > MAX_STRING) throw LiteRtLmFormat("a string too long")
            return fb.bytes(start, n.toInt()).toString(Charsets.UTF_8)
        }

        fun u8(slot: Int): Int = field(slot).let { if (it == 0L) 0 else fb.u8(pos + it) }
        fun u32(slot: Int): Long = field(slot).let { if (it == 0L) 0 else fb.u32(pos + it) }
        fun u64(slot: Int): Long = field(slot).let { if (it == 0L) 0 else fb.u64(pos + it) }
    }

    /** Protobuf wire format: each field with its number, wire type, varint value or length-delimited bytes. */
    private class Proto(val b: ByteArray) {
        private var i = 0

        private fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                if (i >= b.size || shift > 63) throw LiteRtLmFormat("a bad varint")
                val c = b[i++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return r
                shift += 7
            }
        }

        fun fields(each: (field: Int, wire: Int, value: Long, bytes: ByteArray?) -> Unit) {
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                if (field <= 0) throw LiteRtLmFormat("a bad field")
                when (wire) {
                    0 -> each(field, wire, varint(), null)
                    1 -> { skip(8); each(field, wire, 0, null) }
                    5 -> { skip(4); each(field, wire, 0, null) }
                    2 -> {
                        val n = varint()
                        if (n < 0 || n > b.size - i) throw LiteRtLmFormat("a field past the end")
                        val sub = b.copyOfRange(i, i + n.toInt())
                        i += n.toInt()
                        each(field, wire, 0, sub)
                    }
                    else -> throw LiteRtLmFormat("an unknown wire type")
                }
            }
        }

        private fun skip(n: Int) {
            if (n > b.size - i) throw LiteRtLmFormat("a field past the end")
            i += n
        }
    }
}

/**
 * A model's special tokens as its tokenizer reads them from plain text ([control]: control and unknown tokens, or special
 * added ones; [userDefined]: the others, of which ControlText breaks the marker-shaped), and the character that breaks
 * one for this tokenizer ([breaker]: one it encodes as itself, never as an unknown token, and doesn't normalise away).
 */
data class ModelTokens(val control: List<String>, val userDefined: List<String>, val breaker: String) {
    /** What breaks these tokens (and PromptMarkers.KNOWN) in text from outside Robot's own prompt. */
    fun controlText(): ControlText = ControlText(PromptMarkers.of(control, userDefined), breaker)
}

/**
 * Your own model file (Settings › Model), as the main app recorded it after the copy and the check: shown in the app,
 * read by the voice process before a load, and by :brain before it loads it. [name] is the picked file's name, for the
 * screen only (never a path, never logged, never a reason to trust it). [fingerprint] is the copy's size and
 * modification time when it was checked. A LiteRT-LM file has its [report]; a GGUF file (0.4.5) its [gguf] report, and
 * then [report] only says it isn't a LiteRT-LM file.
 */
data class OwnModel(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val fingerprint: String,
    val report: LiteRtLmReport,
    /** You allowed the TPU for this file (its warning card); only ever true for a proven TPU build (Tensor G5 or G6). */
    val tpuAllowed: Boolean = false,
    val checkedAt: Long = 0,
    /** 0.4.5: the GGUF check of a GGUF file (llama.cpp runs it); null for a LiteRT-LM file. */
    val gguf: GgufReport? = null,
) {
    val format: ModelFormat get() = if (gguf != null) ModelFormat.GGUF else ModelFormat.LITERT_LM

    /** 0.8: the chip a LiteRT-LM file was compiled for, by every sign Robot checks; null for any other file. */
    val tensorBuild: Chip? get() = if (gguf == null) report.tensorBuild else null

    /** Why Robot can't run it here at all; null when it can. */
    val unusable: String? get() = if (gguf != null) gguf.unusable else report.unusable

    /** Where its check says it can run, before your say about the TPU: a GGUF file on the CPU only; null: nowhere. */
    val runsOn: ModelBackend? get() = if (gguf != null) (if (gguf.unusable == null) ModelBackend.CPU else null) else report.backend

    /** Where it runs: the TPU only for a proven TPU build you allowed (on its own chip: LoadCheck), else the CPU; null: nowhere. */
    val backend: ModelBackend? get() = when (runsOn) {
        ModelBackend.TPU -> if (tpuAllowed) ModelBackend.TPU else null
        else -> runsOn
    }

    /**
     * The context Robot loads it with (ContextWindow.forFile), whichever kind of file: 4,096, or its own when it was built
     * for 2,048 to 4,095 tokens (chat only); 0 when it is too short to run at all.
     */
    val contextTokens: Int get() = gguf?.contextTokens ?: report.contextTokens

    /** Built for less than 4,096 tokens: Robot runs it as chat only (0.4.3), a GGUF file as a .litertlm. */
    val shortContext: Boolean get() = gguf?.shortContext ?: report.shortContext

    /** It can look at photos: a LiteRT-LM file with a vision encoder; never a GGUF file (llama.cpp runs text only here). */
    val vision: Boolean get() = gguf == null && report.vision

    /** What the check found, a line each, for Settings › Model. */
    fun lines(): List<String> = gguf?.lines() ?: report.lines()

    /**
     * [fingerprintNow]: the file's now (null: no file). A changed file must be checked again. [ggufEngine]: this build of
     * Robot has its llama.cpp library (tools/build_llama.sh); without it a GGUF file can't run.
     */
    fun availability(fingerprintNow: String?, ggufEngine: Boolean = true): ModelAvailability = when {
        fingerprintNow == null -> ModelAvailability.NOT_DOWNLOADED
        fingerprintNow != fingerprint -> ModelAvailability.NOT_CHECKED
        runsOn == null -> ModelAvailability.UNUSABLE
        gguf != null && !ggufEngine -> ModelAvailability.UNUSABLE
        runsOn == ModelBackend.TPU && !tpuAllowed -> ModelAvailability.NEEDS_TPU_OK
        else -> ModelAvailability.READY
    }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("bytes", bytes).put("sha256", sha256).put("fingerprint", fingerprint)
        .put("report", report.toJson()).put("tpuAllowed", tpuAllowed && tensorBuild != null).put("checkedAt", checkedAt)
        .apply { gguf?.let { put("gguf", it.toJson()) } }

    companion object {
        private val SHA = Regex("^[0-9a-f]{64}$")

        /** The [report] a GGUF file's record carries (the LiteRT-LM check doesn't read GGUF files). */
        val NOT_LITERT_LM = LiteRtLmReport(readable = false, problem = "It is a GGUF file, not a LiteRT-LM file.")

        fun fromJson(text: String?): OwnModel? = try {
            val o = JSONObject(text ?: return null)
            val report = LiteRtLmReport.fromJson(o.optJSONObject("report")) ?: return null
            val sha = o.getString("sha256").takeIf { SHA.matches(it) } ?: return null
            // A "gguf" entry that can't be read makes the whole record unreadable (checked again), never a LiteRT-LM one.
            val gguf = if (o.has("gguf")) GgufReport.fromJson(o.optJSONObject("gguf")) ?: return null else null
            OwnModel(o.optString("name", "").take(120), o.getLong("bytes"), sha, o.getString("fingerprint"), report,
                // Never the TPU for a file that isn't a proven TPU build, whatever the file says.
                o.optBoolean("tpuAllowed", false) && gguf == null && report.tensorBuild != null, o.optLong("checkedAt", 0), gguf)
        } catch (_: Exception) { null }
    }
}
