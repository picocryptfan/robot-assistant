package app.heyrobot.core

import org.json.JSONObject
import java.io.File
import java.util.Locale

// Pure (java.io). What a GGUF model file holds (0.4.4, "your own model file" run by llama.cpp), read from its header
// without loading it: the version, the metadata Robot needs (architecture, name, context, layers, quantisation,
// tokenizer, chat template) and every tensor's shape, type and place, so a file that is cut short or laid out wrongly is
// refused here, before llama.cpp's own parser ever sees it. llama.cpp's GGUF parser has had memory-safety bugs; Robot
// reads the file first, runs llama.cpp only in its separate :brain process, and only on a file you imported.
//
// The file is untrusted: every count, length and string is capped, every position is checked against the file without
// adding (so nothing overflows past a check), and anything unexpected ends the check as "unreadable" (never an exception,
// never a large read). The tokenizer's vocabulary is skipped, string by string, never kept.

/** What the check found. Content from the file ([name], [architecture], [licence]) is for the screen only, never logged. */
data class GgufReport(
    /** A GGUF file whose header Robot could read to the end of its tensor list. */
    val readable: Boolean,
    val version: Int = 0,
    val tensors: Int = 0,
    /** general.architecture ("gemma3", "qwen3", …), printable. */
    val architecture: String? = null,
    /** general.name ("Medgemma-1.5-4B-It"), printable. */
    val name: String? = null,
    /** general.size_label ("4B"). */
    val sizeLabel: String? = null,
    /** general.license as the file states it ("apache-2.0", "other"). */
    val licence: String? = null,
    /** general.file_type, llama.cpp's llama_ftype (15: Q4_K_M); -1: not stated. */
    val fileType: Int = -1,
    /** <arch>.context_length: the context it was trained for. */
    val contextLength: Long = 0,
    /** <arch>.block_count. */
    val blocks: Int = 0,
    /** The KV cache's bytes per token of context at f16 (keys and values of every layer); 0: not known. */
    val kvBytesPerToken: Long = 0,
    /** Every tensor's elements, added up: about the number of parameters. */
    val parameters: Long = 0,
    /** tokenizer.ggml.model ("llama", "gpt2", …). */
    val tokenizer: String? = null,
    /** tokenizer.chat_template is there (llama.cpp formats the conversation with it). */
    val chatTemplate: Boolean = false,
    /** The template reads `enable_thinking` (Qwen3 and others): Robot can tell it whether to think first. */
    val templateThinking: Boolean = false,
    /** The template writes <think> blocks. */
    val templateThinkTags: Boolean = false,
    val fileBytes: Long = 0,
    /** The phone's memory when checked (bytes, ActivityManager's totalMem); 0: unknown, no memory check. */
    val totalRam: Long = 0,
    /** Why the file couldn't be read ([readable] false). */
    val problem: String? = null,
) {
    /** "Q4_K_M", or null when the file doesn't say. */
    val quantisation: String? get() = GgufCheck.FILE_TYPES[fileType]

    /** Thinks before it answers when its template lets it, or (Qwen3) when its system prompt doesn't say /no_think. */
    val thinks: Boolean get() = templateThinking || templateThinkTags || GgufCheck.qwen3(architecture)

    /** Memory while loaded, about: the file (memory-mapped, all of it read for every token), the KV cache for Robot's context, and work space. */
    val memoryBytes: Long get() = fileBytes.coerceAtLeast(0) + kvBytesPerToken.coerceAtLeast(0) * ContextWindow.MAX_TOKENS + GgufCheck.WORK_BYTES

    /** Too big for this phone's memory next to Robot's voice and Android; null when it fits or the memory is unknown. */
    val memoryProblem: String? get() {
        if (totalRam <= 0) return null
        val room = totalRam - GgufCheck.VOICE_BYTES - GgufCheck.SYSTEM_BYTES
        return if (memoryBytes <= room) null
        else "It needs about ${ModelLogic.gb(memoryBytes)} of memory while it runs, and next to Robot's voice (about " +
            "${ModelLogic.gb(GgufCheck.VOICE_BYTES)}) and Android this phone has about ${ModelLogic.gb(room.coerceAtLeast(0))} for it. Choose a smaller file " +
            "(fewer parameters, or a smaller quantisation such as Q4_K_M)."
    }

    /** Why Robot can't run it here at all; null when it can. */
    val unusable: String? get() = when {
        !readable -> problem ?: "It isn't a GGUF model file."
        architecture == null -> "It doesn't say what kind of model it is (general.architecture)."
        architecture !in GgufCheck.ARCHITECTURES -> "Its architecture “$architecture” isn't one that llama.cpp ${GgufCheck.LLAMA_TAG} knows."
        architecture in GgufCheck.NOT_CHAT -> "It's a “$architecture” model, which doesn't write answers (an encoder, an embedding, " +
            "a speech, a draft or a diffusion model)."
        tokenizer == null -> "It has no tokenizer (tokenizer.ggml.model)."
        contextLength <= 0 -> "It doesn't say its context length ($architecture.context_length)."
        contextLength < ContextWindow.MAX_TOKENS -> "It was trained for only $contextLength tokens of context; Robot needs ${ContextWindow.MAX_TOKENS}."
        !chatTemplate -> "It has no chat template (tokenizer.chat_template), so Robot can't write a conversation for it."
        else -> memoryProblem
    }

    /** What the check found, a line each, for Settings › Model. */
    fun lines(): List<String> = buildList {
        if (!readable) {
            add(unusable ?: "Robot couldn't read it.")
            return@buildList
        }
        val what = listOfNotNull(architecture?.let { "architecture $it" }, if (parameters > 0) "about ${billions(parameters)} parameters" else null)
        add("GGUF file, version $version" + (name?.let { ": “$it”" } ?: "") + (if (what.isNotEmpty()) " (${what.joinToString(", ")})" else "") + ".")
        add("Quantisation: " + (quantisation ?: if (fileType >= 0) "type $fileType" else "not stated") + ".")
        if (contextLength > 0) add(String.format(Locale.US, "Context: %,d tokens (Robot uses %,d).", contextLength, ContextWindow.MAX_TOKENS))
        val why = unusable
        if (why != null) {
            licence?.let { add("Licence the file names: “$it”.") }
            add(why)
            return@buildList
        }
        add(String.format(Locale.US, "Memory: about %s while it runs (the file and the cache for Robot's %,d tokens), beside about %s for Robot's voice.",
            ModelLogic.gb(memoryBytes), ContextWindow.MAX_TOKENS, ModelLogic.gb(GgufCheck.VOICE_BYTES)))
        add("Runs on the CPU with llama.cpp: slower than Fast on the TPU, and more battery.")
        add("Sees photos: no. It reads text only: a photo question won't work, and a screen question uses only the screen's text.")
        add("Actions (timers, texts, notes…) use Robot's text format: they may not work with every model.")
        if (thinks) add("It can think before it answers: Settings › Model › “Let the model think first” (off: it answers directly).")
        licence?.let { add("Licence the file names: “$it”. Check its terms where you got it.") }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("readable", readable); put("version", version); put("tensors", tensors)
        architecture?.let { put("architecture", it) }; name?.let { put("name", it) }; sizeLabel?.let { put("sizeLabel", it) }
        licence?.let { put("licence", it) }; put("fileType", fileType); put("contextLength", contextLength); put("blocks", blocks)
        put("kvBytesPerToken", kvBytesPerToken); put("parameters", parameters); tokenizer?.let { put("tokenizer", it) }
        put("chatTemplate", chatTemplate); put("templateThinking", templateThinking); put("templateThinkTags", templateThinkTags)
        put("fileBytes", fileBytes); put("totalRam", totalRam); problem?.let { put("problem", it) }
    }

    companion object {
        private fun billions(n: Long): String = if (n >= 1_000_000_000L) String.format(Locale.US, "%.1f billion", n / 1e9)
            else String.format(Locale.US, "%,d million", n / 1_000_000)

        fun fromJson(o: JSONObject?): GgufReport? {
            if (o == null) return null
            return try {
                fun text(k: String, max: Int) = if (o.has(k)) GgufCheck.printable(o.optString(k), max) else null
                GgufReport(
                    readable = o.getBoolean("readable"), version = o.optInt("version"), tensors = o.optInt("tensors"),
                    architecture = text("architecture", GgufCheck.MAX_SHOWN), name = text("name", GgufCheck.MAX_SHOWN),
                    sizeLabel = text("sizeLabel", GgufCheck.MAX_SHOWN), licence = text("licence", GgufCheck.MAX_SHOWN),
                    fileType = o.optInt("fileType", -1), contextLength = o.optLong("contextLength").coerceAtLeast(0), blocks = o.optInt("blocks"),
                    kvBytesPerToken = o.optLong("kvBytesPerToken").coerceAtLeast(0), parameters = o.optLong("parameters").coerceAtLeast(0),
                    tokenizer = text("tokenizer", GgufCheck.MAX_SHOWN), chatTemplate = o.optBoolean("chatTemplate"),
                    templateThinking = o.optBoolean("templateThinking"), templateThinkTags = o.optBoolean("templateThinkTags"),
                    fileBytes = o.optLong("fileBytes").coerceAtLeast(0), totalRam = o.optLong("totalRam").coerceAtLeast(0),
                    problem = if (o.has("problem")) o.optString("problem").take(300) else null,
                )
            } catch (_: Exception) { null }
        }
    }
}

object GgufCheck {
    const val MAGIC = "GGUF"
    /** The llama.cpp release Robot builds (tools/build_llama.sh); [ARCHITECTURES] is its list. */
    const val LLAMA_TAG = "b11200"

    /** Caps on what the header may declare. llama.cpp's own limits are wider; a real model is far below these. */
    const val MAX_KV = 4096
    const val MAX_TENSORS = 65_536
    const val MAX_KEY = 256
    /** Any one string (a chat template is a few kilobytes, a vocabulary entry a few bytes). */
    const val MAX_STRING = 1 shl 20
    /** Elements of one array (vocabularies and merges are 150,000 to 260,000). */
    const val MAX_ARRAY = 1 shl 22
    /** The header, metadata and tensor list together: real files need 5 to 20 MB. */
    const val MAX_HEADER = 192L shl 20
    /** ggml's GGML_MAX_DIMS and GGML_MAX_NAME (a name is shorter than this). */
    const val MAX_DIMS = 4
    const val MAX_NAME = 64
    const val MAX_ALIGNMENT = 1 shl 20
    /** What the screen shows of a string from the file. */
    const val MAX_SHOWN = 80

    /** Memory guidance (0.4.4): Robot's voice process (Parakeet, Kokoro and the wake words) beside the model … */
    const val VOICE_BYTES = 1_300_000_000L
    /** … Android, the launcher and the apps it keeps; and llama.cpp's own work space beside the file and the cache. */
    const val SYSTEM_BYTES = 4_000_000_000L
    const val WORK_BYTES = 400_000_000L

    /** llama.cpp's architecture names at [LLAMA_TAG] (src/llama-arch.cpp, LLM_ARCH_NAMES). */
    val ARCHITECTURES: Set<String> = setOf(
        "clip", "llama", "llama4", "deci", "falcon", "grok", "gpt2", "gptj", "gptneox", "mpt", "baichuan", "starcoder", "refact",
        "bert", "modern-bert", "nomic-bert", "nomic-bert-moe", "neo-bert", "jina-bert-v2", "jina-bert-v3", "eurobert",
        "bloom", "stablelm", "qwen", "qwen2", "qwen2moe", "qwen2vl", "qwen3", "qwen3moe", "qwen3next", "qwen3vl", "qwen3vlmoe",
        "qwen35", "qwen35moe", "qwen4exp", "phi2", "phi3", "phimoe", "plamo", "plamo2", "plamo3", "codeshell", "orion",
        "internlm2", "minicpm", "minicpm3", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4", "gemma4-assistant",
        "gemma-embedding", "starcoder2", "mamba", "mamba2", "maple", "jamba", "falcon-h1", "xverse", "command-r", "cohere2",
        "cohere2moe", "dbrx", "olmo", "olmo2", "olmoe", "muse-glimmer", "openelm", "arctic", "deepseek", "deepseek2",
        "deepseek2-ocr", "deepseek32", "deepseek4", "chatglm", "glm4", "glm4moe", "glm-dsa", "bitnet", "t5", "t5encoder",
        "jais", "jais2", "nemotron", "nemotron_h", "nemotron_h_moe", "exaone", "exaone4", "exaone-moe", "rwkv6", "rwkv6qwen2",
        "rwkv7", "arwkv7", "granite", "granitemoe", "granitehybrid", "graniteswitch", "granite_swa", "chameleon",
        "wavtokenizer-dec", "plm", "bailingmoe", "bailingmoe2", "bailingmoe3", "dots1", "dots3note", "arcee", "afmoe",
        "laguna", "ernie4_5", "ernie4_5-moe", "hunyuan-moe", "hunyuan-dense", "hunyuan_vl", "hy_v3", "hy_v4", "smollm3",
        "gpt-oss", "lfm2", "lfm2moe", "dream", "smallthinker", "llada", "llada-moe", "seed_oss", "grovemoe", "apertus",
        "minimax-01", "hrm_text", "minimax-m2", "minimax-m3", "cogvlm", "rnd1", "pangu-embedded", "mistral3", "eagle3",
        "dflash", "mistral4", "paddleocr", "mimo2", "step35", "spark2_5", "llama-embed", "maincoder", "kimi-linear", "kimi-k3",
        "talkie", "mellum", "nanbeige", "qwen3tts", "pockettts",
    )

    /**
     * Known to llama.cpp, but not a model that answers a chat turn by turn: encoders and embeddings, an encoder-decoder,
     * speech, the draft models of speculative decoding, diffusion models (another way of writing), and readers of images only.
     */
    val NOT_CHAT: Set<String> = setOf(
        "clip", "bert", "modern-bert", "nomic-bert", "nomic-bert-moe", "neo-bert", "jina-bert-v2", "jina-bert-v3", "eurobert",
        "t5", "t5encoder", "gemma-embedding", "llama-embed", "wavtokenizer-dec", "qwen3tts", "pockettts",
        "eagle3", "dflash", "gemma4-assistant", "dream", "llada", "llada-moe", "rnd1", "deepseek2-ocr", "paddleocr",
    )

    /** llama.cpp's llama_ftype names (general.file_type). */
    val FILE_TYPES: Map<Int, String> = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K", 11 to "Q3_K_S",
        12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K",
        19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S", 22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL",
        26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0",
        37 to "TQ2_0", 38 to "MXFP4_MOE", 39 to "NVFP4", 40 to "Q1_0", 41 to "Q2_0",
    )

    /** ggml's tensor types at [LLAMA_TAG]: elements per block and bytes per block (ggml.c type_traits, ggml-common.h). */
    private val TENSOR_TYPES: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (1 to 4), 1 to (1 to 2), 2 to (32 to 18), 3 to (32 to 20), 6 to (32 to 22), 7 to (32 to 24), 8 to (32 to 34),
        9 to (32 to 36), 10 to (256 to 84), 11 to (256 to 110), 12 to (256 to 144), 13 to (256 to 176), 14 to (256 to 210),
        15 to (256 to 292), 16 to (256 to 66), 17 to (256 to 74), 18 to (256 to 98), 19 to (256 to 50), 20 to (32 to 18),
        21 to (256 to 110), 22 to (256 to 82), 23 to (256 to 136), 24 to (1 to 1), 25 to (1 to 2), 26 to (1 to 4), 27 to (1 to 8),
        28 to (1 to 8), 29 to (256 to 56), 30 to (1 to 2), 34 to (256 to 54), 35 to (256 to 66), 39 to (32 to 17),
        40 to (64 to 36), 41 to (128 to 18), 42 to (64 to 18),
    )

    /** Qwen3's families, which also take "/no_think" in the system prompt. */
    fun qwen3(architecture: String?): Boolean = architecture == "qwen3" || architecture == "qwen3moe"

    // GGUF value types.
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /** Fixed sizes of the scalar types. */
    private val SIZES = mapOf(T_UINT8 to 1, T_INT8 to 1, T_UINT16 to 2, T_INT16 to 2, T_UINT32 to 4, T_INT32 to 4, T_FLOAT32 to 4,
        T_BOOL to 1, T_UINT64 to 8, T_INT64 to 8, T_FLOAT64 to 8)

    /** Printable characters only (no controls, no invisible formatting), at most [max]. */
    fun printable(s: String, max: Int): String? =
        s.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.trim().take(max).ifEmpty { null }

    fun check(file: File, totalRam: Long = 0): GgufReport = try {
        FileByteSource(file).use { check(it, totalRam) }
    } catch (_: Exception) {
        GgufReport(readable = false, problem = "Robot couldn't read the file.")
    }

    fun check(src: ByteSource, totalRam: Long = 0): GgufReport = try {
        read(src, totalRam.coerceAtLeast(0))
    } catch (e: LiteRtLmFormat) {
        GgufReport(readable = false, problem = "It isn't a GGUF model file Robot can read (${e.message}).", fileBytes = src.size)
    } catch (_: Exception) {
        GgufReport(readable = false, problem = "It isn't a GGUF model file Robot can read.", fileBytes = src.size)
    }

    /** Integer metadata Robot reads (any integer type), by key; per-layer arrays count by their largest element. */
    private fun wanted(key: String): Boolean = key == "general.file_type" || key == "general.alignment" ||
        key.endsWith(".context_length") || key.endsWith(".block_count") || key.endsWith(".embedding_length") ||
        key.endsWith(".attention.head_count") || key.endsWith(".attention.head_count_kv") || key.endsWith(".attention.key_length") ||
        key.endsWith(".attention.value_length")

    private val STRINGS = setOf("general.architecture", "general.name", "general.size_label", "general.license", "tokenizer.ggml.model",
        "tokenizer.chat_template")

    private fun read(src: ByteSource, totalRam: Long): GgufReport {
        val r = Reader(src, minOf(src.size, MAX_HEADER))
        if (src.size < 24) throw LiteRtLmFormat("too short")
        val magic = String(r.bytes(4), Charsets.ISO_8859_1)
        if (magic != MAGIC) return GgufReport(readable = false, problem = "It isn't a GGUF model file.", fileBytes = src.size)
        val version = r.u32()
        if (version and 0xFFFF == 0L) return GgufReport(readable = false, problem = "It is a big-endian GGUF file; this phone reads little-endian ones.", fileBytes = src.size)
        if (version != 2L && version != 3L) return GgufReport(readable = false, problem = "It is GGUF version $version; llama.cpp reads versions 2 and 3.", fileBytes = src.size)
        val nTensors = r.u64()
        val nKv = r.u64()
        if (nTensors < 0 || nTensors > MAX_TENSORS) throw LiteRtLmFormat("too many tensors")
        if (nKv < 0 || nKv > MAX_KV) throw LiteRtLmFormat("too many metadata entries")
        val keys = HashSet<String>()
        val ints = HashMap<String, Long>()
        val strings = HashMap<String, String>()
        var alignmentType = -1
        repeat(nKv.toInt()) {
            val key = r.string(MAX_KEY)
            if (key.isEmpty()) throw LiteRtLmFormat("an empty key")
            if (!keys.add(key)) throw LiteRtLmFormat("a key twice")
            val type = r.u32().toInt()
            if (type == T_ARRAY) {
                val elem = r.u32().toInt()
                val n = r.u64()
                if (n < 0 || n > MAX_ARRAY) throw LiteRtLmFormat("an array too long")
                when {
                    elem == T_STRING -> repeat(n.toInt()) { r.skipString() }
                    elem in SIZES -> {
                        val size = SIZES.getValue(elem)
                        if (wanted(key) && elem.isInteger() && n <= 4096) {
                            // Per-layer values (head counts of some models): the largest counts for the cache.
                            var max = 0L
                            repeat(n.toInt()) { max = maxOf(max, r.int(elem)) }
                            ints[key] = max
                        } else r.skip(n * size)
                    }
                    else -> throw LiteRtLmFormat("an array of type $elem")
                }
            } else {
                when {
                    type == T_STRING -> if (key in STRINGS) strings[key] = r.string(MAX_STRING) else r.skipString()
                    type in SIZES -> {
                        if (key == "general.alignment") alignmentType = type
                        if (wanted(key) && type.isInteger()) ints[key] = r.int(type) else r.skip(SIZES.getValue(type).toLong())
                    }
                    else -> throw LiteRtLmFormat("a value of type $type")
                }
            }
        }
        // As llama.cpp: general.alignment, when there, is a u32 power of two.
        val alignment = if (alignmentType < 0) 32L else {
            if (alignmentType != T_UINT32) throw LiteRtLmFormat("an alignment that isn't a u32")
            ints.getValue("general.alignment")
        }
        if (alignment <= 0 || alignment > MAX_ALIGNMENT || alignment and (alignment - 1) != 0L) throw LiteRtLmFormat("a bad alignment")
        // The tensors: names, shapes, types, and their places, which follow each other in order (as llama.cpp requires).
        val names = HashSet<String>()
        var expected = 0L
        var parameters = 0L
        repeat(nTensors.toInt()) {
            val name = r.string(MAX_NAME - 1)
            if (!names.add(name)) throw LiteRtLmFormat("a tensor twice")
            val dims = r.u32()
            if (dims > MAX_DIMS) throw LiteRtLmFormat("a tensor with $dims dimensions")
            var elements = 1L
            val ne = LongArray(MAX_DIMS) { 1L }
            for (d in 0 until dims.toInt()) {
                val n = r.i64()
                if (n < 0) throw LiteRtLmFormat("a negative dimension")
                ne[d] = n
                elements = try { Math.multiplyExact(elements, n) } catch (_: ArithmeticException) { throw LiteRtLmFormat("a tensor too large") }
            }
            val type = r.u32().toInt()
            val (block, bytes) = TENSOR_TYPES[type] ?: throw LiteRtLmFormat("a tensor type llama.cpp $LLAMA_TAG doesn't know ($type)")
            if (ne[0] % block != 0L) throw LiteRtLmFormat("a tensor row that doesn't fill its blocks")
            val size = try { Math.multiplyExact(elements / block, bytes.toLong()) } catch (_: ArithmeticException) { throw LiteRtLmFormat("a tensor too large") }
            val offset = r.u64()
            if (offset != expected) throw LiteRtLmFormat("a tensor out of place")
            val padded = pad(size, alignment) ?: throw LiteRtLmFormat("a tensor too large")
            if (padded > Long.MAX_VALUE - expected) throw LiteRtLmFormat("tensors too large")
            expected += padded
            parameters = if (elements > Long.MAX_VALUE - parameters) Long.MAX_VALUE else parameters + elements
        }
        // The tensor data starts at the next aligned position, and must all be in the file.
        val dataStart = pad(r.pos, alignment) ?: throw LiteRtLmFormat("a bad data position")
        if (nTensors > 0 && (dataStart > src.size || expected > src.size - dataStart)) throw LiteRtLmFormat("cut short: its tensors run past the end")
        val arch = strings["general.architecture"]?.let { printable(it, MAX_SHOWN) }
        val template = strings["tokenizer.chat_template"]
        fun archInt(suffix: String): Long = arch?.let { ints["$it.$suffix"] } ?: 0L
        val blocks = archInt("block_count").coerceIn(0, 100_000).toInt()
        val embd = archInt("embedding_length")
        val heads = archInt("attention.head_count")
        val kvHeads = archInt("attention.head_count_kv").takeIf { it > 0 } ?: heads
        val keyLen = archInt("attention.key_length").takeIf { it > 0 } ?: if (heads > 0) embd / heads else 0
        val valueLen = archInt("attention.value_length").takeIf { it > 0 } ?: keyLen
        // f16 keys and values for every layer and token: an upper bound (sliding-window layers hold less).
        val kvPerToken = if (blocks > 0 && kvHeads in 1..65_536 && keyLen in 1..65_536 && valueLen in 1..65_536) blocks * kvHeads * (keyLen + valueLen) * 2 else 0L
        return GgufReport(
            readable = true, version = version.toInt(), tensors = nTensors.toInt(),
            architecture = arch, name = strings["general.name"]?.let { printable(it, MAX_SHOWN) },
            sizeLabel = strings["general.size_label"]?.let { printable(it, MAX_SHOWN) }, licence = strings["general.license"]?.let { printable(it, MAX_SHOWN) },
            fileType = ints["general.file_type"]?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: -1,
            contextLength = archInt("context_length").coerceAtLeast(0), blocks = blocks, kvBytesPerToken = kvPerToken,
            parameters = parameters, tokenizer = strings["tokenizer.ggml.model"]?.let { printable(it, MAX_SHOWN) },
            chatTemplate = !template.isNullOrBlank(), templateThinking = template?.contains("enable_thinking") == true,
            templateThinkTags = template?.contains("<think>") == true, fileBytes = src.size, totalRam = totalRam,
        )
    }

    private fun Int.isInteger() = this in setOf(T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32, T_INT32, T_UINT64, T_INT64)

    /** [n] rounded up to a multiple of [align] (a power of two), or null past Long.MAX_VALUE. */
    private fun pad(n: Long, align: Long): Long? = if (n > Long.MAX_VALUE - (align - 1)) null else (n + align - 1) and (align - 1).inv()

    /**
     * Reads the file in order through a 1 MiB window, never past [limit]: each read checks that its bytes are there
     * (compared without adding, so a length near Long.MAX_VALUE can't overflow past the check).
     */
    private class Reader(private val src: ByteSource, private val limit: Long) {
        var pos = 0L
            private set
        private var window = ByteArray(0)
        private var windowAt = 0L

        private fun need(n: Long) {
            if (n < 0 || pos > limit - n) throw LiteRtLmFormat(if (limit < src.size) "a header too large" else "cut short")
        }

        fun bytes(n: Int): ByteArray {
            need(n.toLong())
            val out = ByteArray(n)
            var done = 0
            while (done < n) {
                val off = pos - windowAt
                if (off < 0 || off >= window.size) {
                    val len = minOf(WINDOW.toLong(), limit - pos).toInt()
                    window = src.read(pos, len)
                    windowAt = pos
                    continue
                }
                val k = minOf(n - done, window.size - off.toInt())
                System.arraycopy(window, off.toInt(), out, done, k)
                done += k
                pos += k
            }
            return out
        }

        fun skip(n: Long) {
            need(n)
            pos += n
        }

        private fun le(n: Int): Long {
            val b = bytes(n)
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }

        fun u32(): Long = le(4)
        fun u64(): Long = le(8)
        fun i64(): Long = le(8)

        /** An integer of GGUF [type]; a negative signed value, or a u64 past Long.MAX_VALUE, reads as -1. */
        fun int(type: Int): Long = when (type) {
            T_UINT8 -> le(1)
            T_INT8 -> le(1).toByte().toLong()
            T_UINT16 -> le(2)
            T_INT16 -> le(2).toShort().toLong()
            T_UINT32 -> le(4)
            T_INT32 -> le(4).toInt().toLong()
            else -> le(8)
        }.let { if (it < 0) -1 else it }

        /** A GGUF string: a u64 length, then UTF-8 bytes; longer than [max] is a damaged file. */
        fun string(max: Int): String {
            val n = u64()
            if (n < 0 || n > max) throw LiteRtLmFormat("a string too long")
            return bytes(n.toInt()).toString(Charsets.UTF_8)
        }

        fun skipString() {
            val n = u64()
            if (n < 0 || n > MAX_STRING) throw LiteRtLmFormat("a string too long")
            skip(n)
        }

        companion object {
            const val WINDOW = 1 shl 20
        }
    }
}
