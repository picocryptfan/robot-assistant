package app.heyrobot.core

import java.util.Locale

// Pure (0.4.7). Which phone Robot runs on, which chip it has, whether Robot can use its TPU or its GPU, and so which
// models Settings › Model offers there and how fast they are (estimates, with where each figure comes from). The app
// and :brain hand in Android's Build fields (and the memory); this decides.

/** A phone's GPU family, as far as Robot cares: PowerVR (Tensor G5 and G6) is never used. */
enum class GpuKind(val title: String) { MALI("Mali"), POWERVR("PowerVR"), UNKNOWN("unknown") }

/**
 * A Google Tensor chip, as Android names it in Build.SOC_MODEL (ro.soc.model, read from each Pixel's factory image: "Tensor"
 * on the Pixel 6 series, "GS201" on the Pixel 7 series, "Tensor G3" to "Tensor G6" after), or [OTHER] for any other phone.
 */
enum class Chip(val title: String, val gpu: GpuKind) {
    TENSOR_G1("Tensor", GpuKind.MALI),
    TENSOR_G2("Tensor G2", GpuKind.MALI),
    TENSOR_G3("Tensor G3", GpuKind.MALI),
    TENSOR_G4("Tensor G4", GpuKind.MALI),
    TENSOR_G5("Tensor G5", GpuKind.POWERVR),
    TENSOR_G6("Tensor G6", GpuKind.POWERVR),
    OTHER("", GpuKind.UNKNOWN);

    val tensor: Boolean get() = this != OTHER
}

/** What Robot can do with a chip's TPU. */
enum class TpuSupport {
    /** Google publishes a TPU build of Gemma 4 E2B for it and Robot runs it: Fast is that build (the Tensor G5). */
    SUPPORTED,
    /** Google publishes a TPU build for it, but this version of Robot doesn't run it yet (the Tensor G6). */
    NOT_YET,
    /** No TPU build of Gemma exists for it. */
    NONE,
}

/**
 * The phone, from Android's Build fields: [manufacturer] and [model] (Build.MANUFACTURER, Build.MODEL), [socManufacturer]
 * and [socModel] (Build.SOC_MANUFACTURER, Build.SOC_MODEL), and its memory ([totalRam], ActivityManager's totalMem; 0:
 * unknown).
 */
data class Phone(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String = "",
    val socModel: String = "",
    val totalRam: Long = 0,
) {
    val chip: Chip get() = Phones.chip(this)
    val tpu: TpuSupport get() = Phones.tpu(chip)

    /** "Pixel 9a", or "Samsung SM-S938B" (the model alone when it already names the maker). */
    val name: String get() {
        val m = model.trim().ifEmpty { "Unknown phone" }
        val maker = manufacturer.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (maker.isEmpty() || m.startsWith("Pixel") || m.lowercase(Locale.ROOT).startsWith(maker.lowercase(Locale.ROOT))) m else "$maker $m"
    }

    /** 8 GB of memory or less (Android reports a little under the label: an 8 GB Pixel says about 7.5 GB). */
    val smallMemory: Boolean get() = totalRam in 1 until 9_000_000_000L
}

object Phones {
    /** Every Pixel GrapheneOS supports, with its chip (Build.MODEL as each factory image names it). */
    val PIXELS: List<Pair<String, Chip>> = listOf(
        "Pixel 6" to Chip.TENSOR_G1, "Pixel 6 Pro" to Chip.TENSOR_G1, "Pixel 6a" to Chip.TENSOR_G1,
        "Pixel 7" to Chip.TENSOR_G2, "Pixel 7 Pro" to Chip.TENSOR_G2, "Pixel 7a" to Chip.TENSOR_G2,
        "Pixel Fold" to Chip.TENSOR_G2, "Pixel Tablet" to Chip.TENSOR_G2,
        "Pixel 8" to Chip.TENSOR_G3, "Pixel 8 Pro" to Chip.TENSOR_G3, "Pixel 8a" to Chip.TENSOR_G3,
        "Pixel 9" to Chip.TENSOR_G4, "Pixel 9 Pro" to Chip.TENSOR_G4, "Pixel 9 Pro XL" to Chip.TENSOR_G4,
        "Pixel 9 Pro Fold" to Chip.TENSOR_G4, "Pixel 9a" to Chip.TENSOR_G4, "Pixel 10a" to Chip.TENSOR_G4,
        "Pixel 10" to Chip.TENSOR_G5, "Pixel 10 Pro" to Chip.TENSOR_G5, "Pixel 10 Pro XL" to Chip.TENSOR_G5,
        "Pixel 10 Pro Fold" to Chip.TENSOR_G5,
        "Pixel 11" to Chip.TENSOR_G6, "Pixel 11 Pro" to Chip.TENSOR_G6, "Pixel 11 Pro XL" to Chip.TENSOR_G6,
        "Pixel 11 Pro Fold" to Chip.TENSOR_G6,
    )

    /**
     * The chip: Build.SOC_MODEL when the maker is Google (or the name says Tensor), else a Google phone's model name
     * ([PIXELS]), else [Chip.OTHER]. The model name alone never makes a non-Google phone a Tensor.
     */
    fun chip(p: Phone): Chip {
        val soc = p.socModel.trim().lowercase(Locale.ROOT)
        val google = p.socManufacturer.trim().equals("google", ignoreCase = true)
        if (google || soc.startsWith("tensor")) {
            when (soc) {
                "tensor", "gs101" -> return Chip.TENSOR_G1
                "tensor g2", "gs201" -> return Chip.TENSOR_G2
                "tensor g3", "zuma" -> return Chip.TENSOR_G3
                "tensor g4", "zumapro" -> return Chip.TENSOR_G4
                "tensor g5" -> return Chip.TENSOR_G5
                "tensor g6" -> return Chip.TENSOR_G6
            }
        }
        if (!p.manufacturer.trim().equals("google", ignoreCase = true)) return Chip.OTHER
        val model = p.model.trim()
        return PIXELS.firstOrNull { it.first.equals(model, ignoreCase = true) }?.second ?: Chip.OTHER
    }

    /**
     * The Tensor G5 alone runs Robot's TPU build (Fast, pinned since 0.1). Google also publishes a Tensor G6 build
     * (gemma-4-E2B-it_Google_Tensor_G6.litertlm, at the same commit), but this version of Robot hasn't been run on a
     * Pixel 11 and its content check knows the Tensor G5 stamp only, so the G6 is [TpuSupport.NOT_YET].
     */
    fun tpu(chip: Chip): TpuSupport = when (chip) {
        Chip.TENSOR_G5 -> TpuSupport.SUPPORTED
        Chip.TENSOR_G6 -> TpuSupport.NOT_YET
        else -> TpuSupport.NONE
    }

    /** "This phone: Pixel 9a (Tensor G4)". */
    fun line(p: Phone): String = "This phone: ${p.name}" + p.chip.title.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

    /** Whether its TPU runs Robot's model, and what Fast is here, in a sentence. */
    fun tpuLine(p: Phone): String = when (p.tpu) {
        TpuSupport.SUPPORTED -> "Its TPU is supported: Fast is Google's Tensor G5 build of Gemma 4 E2B, on the TPU."
        TpuSupport.NOT_YET -> "Google publishes a TPU build for the Tensor G6, but this version of Robot doesn't run it yet: " +
            "Fast is Gemma 4 E2B's general build, " + (if (ModelCatalogue.gpuOffered(p)) "on the GPU." else "on the CPU.")
        TpuSupport.NONE -> (if (p.chip.tensor) "Google publishes no TPU build of Gemma for the ${p.chip.title}" else "Robot has no TPU build for this phone") +
            ": Fast is Gemma 4 E2B's general build, " + (if (ModelCatalogue.gpuOffered(p)) "on the GPU (recommended) or the CPU." else "on the CPU.")
    }

    /** Settings › Model's short list: which Pixels Robot's TPU build runs on. */
    val TPU_LIST: List<String> = listOf(
        "Tensor G5 (Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, Pixel 10 Pro Fold): TPU supported. Fast is Google's Tensor G5 build.",
        "Tensor G6 (Pixel 11, Pixel 11 Pro, Pixel 11 Pro XL, Pixel 11 Pro Fold): Google publishes a TPU build; not supported by this version of " +
            "Robot yet. Fast runs on the CPU.",
        "Tensor G1 to G4 (Pixel 6 to 9 and their a models, Pixel 10a, Fold, Tablet), and other phones: no TPU build exists. " +
            "Fast is the general build, on the GPU (recommended) or the CPU.",
    )
}

/**
 * Which models a phone gets (0.4.7, pure): Fast is [BrainModel.FAST] (the TPU build) on a Tensor G5 and [BrainModel.GENERAL]
 * everywhere else; the other is never shown. A settings file that names the other one (Fast chosen on another phone, or
 * the default) means this phone's Fast: [resolve] reads it so, and the file is left as it is.
 */
object ModelCatalogue {
    fun fast(p: Phone): BrainModel = if (p.tpu == TpuSupport.SUPPORTED) BrainModel.FAST else BrainModel.GENERAL

    /** Settings › Model's models, in order: this phone's Fast, the E4B models, your own file. */
    fun models(p: Phone): List<BrainModel> {
        val other = if (fast(p) == BrainModel.FAST) BrainModel.GENERAL else BrainModel.FAST
        return BrainModel.entries.filter { it != other }
    }

    /** The model [stored] means on [p]: either Fast is this phone's Fast. */
    fun resolve(stored: BrainModel, p: Phone): BrainModel =
        if (stored == BrainModel.FAST || stored == BrainModel.GENERAL) fast(p) else stored

    /**
     * Robot may offer the GPU here: a Tensor's Mali GPU, or another phone's (Google measured its figures on a Galaxy S26
     * Ultra's GPU). Never a PowerVR GPU (Tensor G5 and G6): Robot's GPU lab ran this file on a Pixel 10 Pro's at about a
     * third of the TPU's writing speed, and the Pixel 10's GPU is the one Google's own AI Edge Gallery leaves out.
     */
    fun gpuOffered(p: Phone): Boolean = p.chip.gpu != GpuKind.POWERVR

    /**
     * Where [m] runs on [p]: the GPU for the general E2B when it is offered here, Settings › Model › "Use the GPU" is on
     * ([gpuOn]) and it hasn't failed here ([gpuFailed]); else the model's own backend (the CPU for the general E2B).
     */
    fun backend(m: BrainModel, p: Phone, gpuOn: Boolean, gpuFailed: Boolean): ModelBackend =
        if (m.gpu && gpuOn && !gpuFailed && gpuOffered(p)) ModelBackend.GPU else m.backend

    /** The memory warning for a model on a phone with 8 GB or less: the E4B models (about 4 GB) beside the voice. */
    fun memoryWarning(m: BrainModel, p: Phone): String? =
        if (p.smallMemory && m.memoryBytes >= 4_000_000_000L) "This phone has ${PhoneStatus.size(p.totalRam)} of memory: " +
            "with the voice models loaded too, Android may stop this model while it answers. Fast is the safer choice here." else null
}

/**
 * How fast a model is on a phone, in plain words, with where the figures come from (0.4.7). [measured]: Robot measured
 * them on this kind of phone; otherwise they are estimates. A new conversation reads Robot's whole prompt (about
 * [PROMPT_TOKENS] tokens with the actions), so its first word waits for that; later answers read only the new turn.
 */
data class SpeedEstimate(val firstWord: String, val later: String, val words: String, val measured: Boolean, val source: String) {
    /** "Estimate: a new conversation starts answering in about 3–5 s, …". */
    fun text(): String = (if (measured) "Measured" else "Estimate") + ": a new conversation starts answering in about $firstWord, " +
        "later answers in about $later, then about $words words a second."
}

object Speeds {
    /** Robot's prompt with the actions, about (a new conversation reads it all: the TPU's 3 s at about 500 tokens a second). */
    const val PROMPT_TOKENS = 1500
    /** Words per token in English, about. */
    const val WORDS_PER_TOKEN = 0.75

    /** Tokens a second, read and written, as ranges. */
    private data class Rates(val readLo: Int, val readHi: Int, val writeLo: Double, val writeHi: Double)

    /** Robot's own measurements on a Pixel 10 Pro (README, GPU lab, LiteRT-LM 0.18.0). */
    private val TPU_PIXEL10 = Rates(500, 560, 17.0, 18.0)
    private val CPU_E2B_PIXEL10 = Rates(160, 210, 18.8, 20.2)
    private val CPU_E4B_PIXEL10 = Rates(70, 70, 8.0, 8.4)

    /**
     * Scaled for a Tensor G4 (and, slower again, G1 to G3): its CPU's fastest cores run at about 80% of the G5's clocks,
     * so two thirds to four fifths of the Pixel 10 Pro's figures. Its Mali GPU is estimated from the lab's PowerVR run
     * of this file (about 540 read and 12 written) rather than Google's Galaxy S26 Ultra figures (3,808 and 52.1): LiteRT-LM
     * prepares no weights on the GPU for Mali or PowerVR, so the S26's are out of reach.
     */
    private fun scaled(r: Rates, lo: Double, hi: Double) = Rates((r.readLo * lo).toInt(), (r.readHi * hi).toInt(), r.writeLo * lo, r.writeHi * hi)
    private val GPU_MALI_G4 = Rates(300, 550, 8.0, 14.0)

    private fun seconds(tokens: Int, lo: Int, hi: Int): String {
        val a = Math.round(tokens.toDouble() / hi).toInt().coerceAtLeast(1)
        val b = Math.round(tokens.toDouble() / lo).toInt().coerceAtLeast(1)
        return if (a == b) "$a s" else "$a–$b s"
    }

    private fun words(r: Rates): String {
        val a = Math.round(r.writeLo * WORDS_PER_TOKEN).toInt()
        val b = Math.round(r.writeHi * WORDS_PER_TOKEN).toInt()
        return if (a == b) "$a" else "$a–$b"
    }

    private fun of(r: Rates, measured: Boolean, source: String, laterTokens: Int = 120): SpeedEstimate =
        SpeedEstimate(seconds(PROMPT_TOKENS, r.readLo, r.readHi), seconds(laterTokens, r.readLo, r.readHi), words(r), measured, source)

    private const val GOOGLE_CPU = "Google measured 557 tokens a second read and 46.9 written on a Galaxy S26 Ultra's CPU (its model card)"
    private const val GOOGLE_GPU = "Google measured 3,808 tokens a second read and 52.1 written on a Galaxy S26 Ultra's GPU (its model card)"

    /** [m] on [backend] on [p], or null when Robot has no figure (your own file). */
    fun of(m: BrainModel, backend: ModelBackend, p: Phone): SpeedEstimate? {
        val chip = p.chip
        val older = chip in setOf(Chip.TENSOR_G1, Chip.TENSOR_G2, Chip.TENSOR_G3)
        val olderNote = if (older) " A ${chip.title} is older than a Tensor G4: expect it to be slower than this." else ""
        return when {
            m == BrainModel.FAST && backend == ModelBackend.TPU ->
                of(TPU_PIXEL10, chip == Chip.TENSOR_G5, "Robot measured this on a Pixel 10 Pro's TPU: about 500 to 560 tokens a second read, 17 to 18 written.")
            m == BrainModel.GENERAL && backend == ModelBackend.GPU -> when {
                chip.gpu == GpuKind.MALI -> of(GPU_MALI_G4, false, "Estimated for a Tensor G4's Mali GPU from Robot's run of this file on a " +
                    "Pixel 10 Pro's GPU (about 540 tokens a second read, 12 written). $GOOGLE_GPU; this phone's GPU is much smaller.$olderNote")
                else -> of(GPU_MALI_G4, false, "No figure for this phone's GPU: this is the Tensor G4's estimate. $GOOGLE_GPU.")
            }
            m == BrainModel.GENERAL -> when {
                chip == Chip.TENSOR_G5 || chip == Chip.TENSOR_G6 -> of(CPU_E2B_PIXEL10, chip == Chip.TENSOR_G5,
                    "Robot measured this file on a Pixel 10 Pro's CPU: about 160 to 210 tokens a second read, 19 to 20 written. $GOOGLE_CPU.")
                else -> of(scaled(CPU_E2B_PIXEL10, 0.67, 0.8), false, "Estimated for a Tensor G4 as two thirds to four fifths of Robot's " +
                    "Pixel 10 Pro measurement of this file on the CPU (about 160 to 210 tokens a second read, 19 to 20 written). $GOOGLE_CPU.$olderNote")
            }
            m == BrainModel.SMARTER || m == BrainModel.UNCENSORED -> when (chip) {
                Chip.TENSOR_G5, Chip.TENSOR_G6 -> of(CPU_E4B_PIXEL10, chip == Chip.TENSOR_G5,
                    "Robot measured Gemma 4 E4B on a Pixel 10 Pro's CPU: about 70 tokens a second read, 8 written.", laterTokens = 140)
                else -> of(scaled(CPU_E4B_PIXEL10, 0.67, 0.8), false, "Estimated for a Tensor G4 as two thirds to four fifths of Robot's " +
                    "Pixel 10 Pro measurement (about 70 tokens a second read, 8 written).$olderNote", laterTokens = 140)
            }
            else -> null
        }
    }
}
