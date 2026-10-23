// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.ModelStrings
import app.heyrobot.core.i18n.Phrase
import java.util.Locale

// Pure (0.4.7). Which phone Robot runs on, which chip it has, whether Robot can use its TPU or its GPU, and so which
// models Settings › Model offers there and how fast they are (estimates, with where each figure comes from). The app
// and :brain hand in Android's Build fields (and the memory); this decides.

/**
 * A phone's GPU family, as far as Robot cares: PowerVR (Tensor G5 and G6) is never used. 1.2: Adreno (Snapdragon) and
 * Xclipse (the Exynos 2400) are named for the reports; Robot treats them as it treats any GPU that isn't PowerVR.
 */
enum class GpuKind(val title: String) { MALI("Mali"), POWERVR("PowerVR"), ADRENO("Adreno"), XCLIPSE("Xclipse"), UNKNOWN("unknown") } // l10n: not text (the GPUs' names, and unknown only in the report)

/** 1.2: who makes a phone's chip, from Build.SOC_MANUFACTURER ([Socs]). */
enum class ChipVendor { GOOGLE, QUALCOMM, SAMSUNG, MEDIATEK, OTHER }

/**
 * A phone's chip, as Android names it in Build.SOC_MODEL, or [OTHER] for a chip Robot doesn't know. Google's Tensor chips
 * (ro.soc.model, read from each Pixel's factory image: "Tensor" on the Pixel 6 series, "GS201" on the Pixel 7 series,
 * "Tensor G3" to "Tensor G6" after) decide which TPU build Fast is. 1.2: Snapdragon, Exynos and MediaTek chips are named
 * ([Socs] has the map and which entries no phone has confirmed yet), for the screens and the reports; Robot runs them as
 * it runs [OTHER]: no TPU or NPU build, the general build on the GPU or the CPU.
 */
enum class Chip(val title: String, val gpu: GpuKind, val vendor: ChipVendor) {
    TENSOR_G1("Tensor", GpuKind.MALI, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    TENSOR_G2("Tensor G2", GpuKind.MALI, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    TENSOR_G3("Tensor G3", GpuKind.MALI, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    TENSOR_G4("Tensor G4", GpuKind.MALI, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    TENSOR_G5("Tensor G5", GpuKind.POWERVR, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    TENSOR_G6("Tensor G6", GpuKind.POWERVR, ChipVendor.GOOGLE), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_ELITE_GEN5("Snapdragon 8 Elite Gen 5", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_ELITE("Snapdragon 8 Elite", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_GEN3("Snapdragon 8 Gen 3", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_GEN2("Snapdragon 8 Gen 2", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_PLUS_GEN1("Snapdragon 8+ Gen 1", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    SNAPDRAGON_8_GEN1("Snapdragon 8 Gen 1", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    /** Any other Snapdragon ("SM" and four digits). */
    SNAPDRAGON("Snapdragon", GpuKind.ADRENO, ChipVendor.QUALCOMM), // l10n: not text (phones' and chips' names)
    EXYNOS_2400("Exynos 2400", GpuKind.XCLIPSE, ChipVendor.SAMSUNG), // l10n: not text (phones' and chips' names)
    /** Any other Exynos ("s5e" and four digits): an Xclipse or a Mali GPU by part, so unknown here. */
    EXYNOS("Exynos", GpuKind.UNKNOWN, ChipVendor.SAMSUNG), // l10n: not text (phones' and chips' names)
    /** The Dimensity parts the design names (MT6989, MT6991, MT6993): a Mali or an Immortalis GPU by part, so unknown here. */
    DIMENSITY("Dimensity", GpuKind.UNKNOWN, ChipVendor.MEDIATEK), // l10n: not text (phones' and chips' names)
    /** Any other MediaTek part ("MT" and four digits): Dimensity or Helio. */
    MEDIATEK("MediaTek", GpuKind.UNKNOWN, ChipVendor.MEDIATEK), // l10n: not text (phones' and chips' names)
    OTHER("", GpuKind.UNKNOWN, ChipVendor.OTHER);

    /** A Google Tensor (1.2: by its vendor; before, any chip Robot knew was one). */
    val tensor: Boolean get() = vendor == ChipVendor.GOOGLE

    /**
     * 1.2: what this chip's AI accelerator is called on screen: "NPU" on a Snapdragon (Qualcomm's name), "TPU" on a
     * Tensor (Google's) and on every other chip, as before. ModelBackend.TPU keeps its id and its own title.
     */
    val accelerator: String get() = if (vendor == ChipVendor.QUALCOMM) "NPU" else "TPU" // l10n: not text (the accelerators' names)
}

/**
 * 1.2 (pure): a chip from Android's two SoC fields, Build.SOC_MANUFACTURER and Build.SOC_MODEL. Never from Build.MODEL:
 * Samsung's model numbers also start with "SM-" ("SM-S931W"), so only the SoC fields can name a non-Google chip. Each field
 * is trimmed and read without case; the part number is "SM", "S5E" or "MT" and four digits, whatever follows ("SM8750-AC"
 * is an SM8750), and it counts only with its own maker's name in SOC_MANUFACTURER. Anything else is [Chip.OTHER], which
 * Robot runs as it always has.
 *
 * UNVERIFIED: every non-Google value below is what Android's ro.soc.manufacturer and ro.soc.model usually hold
 * (docs/plans/every-android-phone-1.2-design.md, Step 1), not yet read on a phone. Settings › Model and the reports show the
 * raw fields, and a Test Bench step reads them on each test phone before anything relies on them.
 *
 * | SOC_MANUFACTURER | SOC_MODEL | Chip | Where the value comes from |
 * |---|---|---|---|
 * | Google | Tensor, GS101 | Tensor G1 | factory image, not read on a phone |
 * | Google | Tensor G2, GS201 | Tensor G2 | factory image, not read on a phone |
 * | Google | Tensor G3, zuma | Tensor G3 | factory image, not read on a phone |
 * | Google | Tensor G4, zumapro | Tensor G4 | factory image, not read on a phone |
 * | Google | Tensor G5 | Tensor G5 | factory image (Robot runs on a Pixel 10 Pro; the field itself not logged) |
 * | Google | Tensor G6 | Tensor G6 | factory image, not read on a phone |
 * | QTI or Qualcomm | SM8850 | Snapdragon 8 Elite Gen 5 | UNVERIFIED |
 * | QTI or Qualcomm | SM8750 | Snapdragon 8 Elite | UNVERIFIED |
 * | QTI or Qualcomm | SM8650 | Snapdragon 8 Gen 3 | UNVERIFIED |
 * | QTI or Qualcomm | SM8550 | Snapdragon 8 Gen 2 | UNVERIFIED |
 * | QTI or Qualcomm | SM8475 | Snapdragon 8+ Gen 1 | UNVERIFIED |
 * | QTI or Qualcomm | SM8450 | Snapdragon 8 Gen 1 | UNVERIFIED |
 * | QTI or Qualcomm | any other SM part | Snapdragon | UNVERIFIED |
 * | Samsung | s5e9945 | Exynos 2400 | UNVERIFIED |
 * | Samsung | any other s5e part | Exynos | UNVERIFIED |
 * | Mediatek | MT6989, MT6991, MT6993 | Dimensity | UNVERIFIED |
 * | Mediatek | any other MT part | MediaTek | UNVERIFIED |
 * | anything else, or either field empty | | Chip.OTHER | |
 */
object Socs {
    private val QUALCOMM_MAKERS = setOf("QTI", "QUALCOMM") // l10n: not text (Android's SoC field)
    private val QUALCOMM_PART = Regex("""^SM(\d{4})(?!\d)""")
    private val EXYNOS_PART = Regex("""^S5E(\d{4})(?!\d)""")
    private val MEDIATEK_PART = Regex("""^MT(\d{4})(?!\d)""")

    /** UNVERIFIED (see the table above). */
    private val SNAPDRAGONS = mapOf(
        "8850" to Chip.SNAPDRAGON_8_ELITE_GEN5, "8750" to Chip.SNAPDRAGON_8_ELITE, "8650" to Chip.SNAPDRAGON_8_GEN3,
        "8550" to Chip.SNAPDRAGON_8_GEN2, "8475" to Chip.SNAPDRAGON_8_PLUS_GEN1, "8450" to Chip.SNAPDRAGON_8_GEN1,
    )
    /** UNVERIFIED (see the table above). */
    private val EXYNOSES = mapOf("9945" to Chip.EXYNOS_2400)
    /** UNVERIFIED (see the table above). */
    private val DIMENSITIES = setOf("6989", "6991", "6993")

    /**
     * The chip [socManufacturer] and [socModel] name. A Tensor exactly as before 1.2: its SOC_MODEL when the maker is
     * Google or the name says Tensor. Then a Snapdragon, an Exynos or a MediaTek chip by its part number and its maker.
     */
    fun of(socManufacturer: String, socModel: String): Chip {
        val soc = socModel.trim().lowercase(Locale.ROOT)
        val maker = socManufacturer.trim().uppercase(Locale.ROOT)
        if (maker == "GOOGLE" || soc.startsWith("tensor")) {
            when (soc) {
                "tensor", "gs101" -> return Chip.TENSOR_G1
                "tensor g2", "gs201" -> return Chip.TENSOR_G2
                "tensor g3", "zuma" -> return Chip.TENSOR_G3
                "tensor g4", "zumapro" -> return Chip.TENSOR_G4
                "tensor g5" -> return Chip.TENSOR_G5
                "tensor g6" -> return Chip.TENSOR_G6
            }
            return Chip.OTHER
        }
        val part = soc.uppercase(Locale.ROOT)
        return when {
            maker in QUALCOMM_MAKERS -> QUALCOMM_PART.find(part)?.let { SNAPDRAGONS[it.groupValues[1]] ?: Chip.SNAPDRAGON }
            maker == "SAMSUNG" -> EXYNOS_PART.find(part)?.let { EXYNOSES[it.groupValues[1]] ?: Chip.EXYNOS }
            maker == "MEDIATEK" -> MEDIATEK_PART.find(part)?.let { if (it.groupValues[1] in DIMENSITIES) Chip.DIMENSITY else Chip.MEDIATEK }
            else -> null
        } ?: Chip.OTHER
    }

    /**
     * One of Android's SoC fields as Robot shows and reports it: letters, digits, spaces and . _ , ( ) + - only (anything
     * else becomes "?", so a line break or a direction mark can't reshape a screen or a report), at most [max] characters;
     * "" when empty.
     */
    fun clean(field: String, max: Int = 40): String =
        field.trim().take(max).map { if (it.isLetterOrDigit() && it.code < 128 || it in " ._,()+-") it else '?' }.joinToString("").trim()
}

/** What Robot can do with a chip's TPU. */
enum class TpuSupport {
    /** Google publishes a TPU build of Gemma 4 E2B for it and Robot runs it: Fast is that build (the Tensor G5). */
    SUPPORTED,
    /**
     * 0.8: Google publishes a TPU build for it and Robot runs it, but hasn't run it on that phone yet (the Tensor G6): Fast
     * is that build, marked beta, its speed an estimate; if it doesn't load, Robot runs the general build instead.
     */
    BETA,
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
    val nameText: Phrase get() {
        val m = model.trim().ifEmpty { return ModelStrings.unknownPhone() }
        val maker = manufacturer.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
        return Phrase.plain(if (maker.isEmpty() || m.startsWith("Pixel") || m.lowercase(Locale.ROOT).startsWith(maker.lowercase(Locale.ROOT))) m else "$maker $m")
    }

    /** 8 GB of memory or less (Android reports a little under the label: an 8 GB Pixel says about 7.5 GB). */
    val smallMemory: Boolean get() = totalRam in 1 until 9_000_000_000L

    /**
     * 1.2: under 8 GB of memory, the weak-phone warning's "tight" (design Step 8): a 6 GB phone, never an 8 GB one, which
     * reports about 7.5 GB. A threshold of its own, apart from [smallMemory] (8 GB or less, for the E4B models' warning).
     */
    val underEightGb: Boolean get() = totalRam in 1 until 7_000_000_000L
}

object Phones {
    /** Every Pixel GrapheneOS supports, with its chip (Build.MODEL as each factory image names it). */
    val PIXELS: List<Pair<String, Chip>> = listOf(
        "Pixel 6" to Chip.TENSOR_G1, "Pixel 6 Pro" to Chip.TENSOR_G1, "Pixel 6a" to Chip.TENSOR_G1, // l10n: not text (phones' and chips' names)
        "Pixel 7" to Chip.TENSOR_G2, "Pixel 7 Pro" to Chip.TENSOR_G2, "Pixel 7a" to Chip.TENSOR_G2, // l10n: not text (phones' and chips' names)
        "Pixel Fold" to Chip.TENSOR_G2, "Pixel Tablet" to Chip.TENSOR_G2, // l10n: not text (phones' and chips' names)
        "Pixel 8" to Chip.TENSOR_G3, "Pixel 8 Pro" to Chip.TENSOR_G3, "Pixel 8a" to Chip.TENSOR_G3, // l10n: not text (phones' and chips' names)
        "Pixel 9" to Chip.TENSOR_G4, "Pixel 9 Pro" to Chip.TENSOR_G4, "Pixel 9 Pro XL" to Chip.TENSOR_G4, // l10n: not text (phones' and chips' names)
        "Pixel 9 Pro Fold" to Chip.TENSOR_G4, "Pixel 9a" to Chip.TENSOR_G4, "Pixel 10a" to Chip.TENSOR_G4, // l10n: not text (phones' and chips' names)
        "Pixel 10" to Chip.TENSOR_G5, "Pixel 10 Pro" to Chip.TENSOR_G5, "Pixel 10 Pro XL" to Chip.TENSOR_G5, // l10n: not text (phones' and chips' names)
        "Pixel 10 Pro Fold" to Chip.TENSOR_G5, // l10n: not text (phones' and chips' names)
        "Pixel 11" to Chip.TENSOR_G6, "Pixel 11 Pro" to Chip.TENSOR_G6, "Pixel 11 Pro XL" to Chip.TENSOR_G6, // l10n: not text (phones' and chips' names)
        "Pixel 11 Pro Fold" to Chip.TENSOR_G6, // l10n: not text (phones' and chips' names)
    )

    /**
     * The chip: what Build.SOC_MANUFACTURER and Build.SOC_MODEL name ([Socs]), else a Google phone's model name ([PIXELS]),
     * else [Chip.OTHER]. The model name alone never makes a non-Google phone a Tensor, and never overrides a chip the SoC
     * fields name.
     */
    fun chip(p: Phone): Chip {
        Socs.of(p.socManufacturer, p.socModel).takeIf { it != Chip.OTHER }?.let { return it }
        if (!p.manufacturer.trim().equals("google", ignoreCase = true)) return Chip.OTHER
        val model = p.model.trim()
        return PIXELS.firstOrNull { it.first.equals(model, ignoreCase = true) }?.second ?: Chip.OTHER
    }

    /**
     * The Tensor G5 runs Robot's TPU build (Fast, pinned since 0.1). 0.8: the Tensor G6 runs Google's Tensor G6 build
     * (gemma-4-E2B-it_Google_Tensor_G6.litertlm, at the same commit), as a beta until Robot has run it on a Pixel 11:
     * the content check takes a build for this phone's chip only.
     */
    fun tpu(chip: Chip): TpuSupport = when (chip) {
        Chip.TENSOR_G5 -> TpuSupport.SUPPORTED
        Chip.TENSOR_G6 -> TpuSupport.BETA
        else -> TpuSupport.NONE
    }

    /** "Pixel 10": the first Pixel with [chip] (its series), or the chip's name. */
    fun seriesOf(chip: Chip): String = PIXELS.firstOrNull { it.second == chip }?.first ?: chip.title

    /** "Pixel 9a (Tensor G4)": the phone and its chip. */
    fun phoneText(p: Phone): Phrase = p.chip.title.takeIf { it.isNotEmpty() }?.let { Phrase.join(p.nameText, Phrase.plain("($it)")) } ?: p.nameText

    /** Whether its TPU runs Robot's model, and what Fast is here, in a sentence. */
    fun tpuLineText(p: Phone): Phrase = when (p.tpu) {
        TpuSupport.SUPPORTED -> ModelStrings.tpuSupported()
        TpuSupport.BETA -> ModelStrings.tpuBeta()
        TpuSupport.NONE -> {
            val why = when {
                p.chip.tensor -> ModelStrings.tpuNoneTensor("chip" to p.chip.title)
                // 1.2: a Snapdragon's accelerator is its NPU.
                p.chip.vendor == ChipVendor.QUALCOMM -> ModelStrings.tpuNoneNpu("chip" to p.chip.title)
                else -> ModelStrings.tpuNoneOther()
            }
            (if (ModelCatalogue.gpuOffered(p)) ModelStrings.generalGpu else ModelStrings.generalCpu)("why" to why)
        }
    }

    /**
     * 1.2: the chip as Android names it, "QTI · SM8750" (Build.SOC_MANUFACTURER and Build.SOC_MODEL, [Socs.clean]), each
     * empty field "not given": Settings › Model shows it on every phone, so a Test Bench step can read what a phone says.
     */
    fun socFieldsText(p: Phone): Phrase = Phrase.Join(listOf(p.socManufacturer, p.socModel).map { f ->
        Socs.clean(f).takeIf { it.isNotEmpty() }?.let { Phrase.plain(it) } ?: ModelStrings.socNotGiven()
    }, " · ")

    /** Settings › Model's short list: which Pixels Robot's TPU build runs on. */
    val tpuListText: List<Phrase> get() = listOf(ModelStrings.tpuListG5(), ModelStrings.tpuListG6(), ModelStrings.tpuListOthers())

}

/**
 * Which models a phone gets (0.4.7, pure): Fast is the TPU build for its chip ([BrainModel.FAST] on a Tensor G5, 0.8:
 * [BrainModel.FAST_G6] on a Tensor G6, a beta) and [BrainModel.GENERAL] everywhere else; the others are never shown. A
 * settings file that names another build of Fast (Fast chosen on another phone, or the default) means this phone's Fast:
 * [resolve] reads it so, and the file is left as it is. [tpuFailed] (0.8, TpuFailure): the beta TPU build didn't load
 * here, so Fast is the general build until you try the TPU again (as the GPU's failure keeps the CPU).
 */
object ModelCatalogue {
    fun fast(p: Phone, tpuFailed: Boolean = false): BrainModel = when (p.tpu) {
        TpuSupport.SUPPORTED -> tpuBuild(p.chip) ?: BrainModel.GENERAL
        TpuSupport.BETA -> if (tpuFailed) BrainModel.GENERAL else tpuBuild(p.chip) ?: BrainModel.GENERAL
        TpuSupport.NONE -> BrainModel.GENERAL
    }

    /** The built-in TPU build for [chip], or null. */
    fun tpuBuild(chip: Chip): BrainModel? = BrainModel.entries.firstOrNull { it.tpuChip == chip }

    /**
     * Settings › Model's models, in order: this phone's Fast, its [fallback] where it has one (the general build on a
     * Tensor G6, to download before the beta TPU build ever fails), the E4B models, your own file.
     */
    fun models(p: Phone, tpuFailed: Boolean = false): List<BrainModel> {
        val fast = fast(p, tpuFailed)
        val fallback = fallback(p, tpuFailed)
        return BrainModel.entries.filter { it == fast || it == fallback || !it.isFast }
    }

    /**
     * 0.8: the build Robot switches to by itself when this phone's beta TPU build doesn't load (TpuFailure): the general
     * build on a Tensor G6, listed as [FALLBACK_TITLE] so you can download it before that happens. It isn't a choice of its
     * own (any build of Fast means this phone's Fast, [resolve]); null where there is no beta, and once it is Fast here.
     */
    fun fallback(p: Phone, tpuFailed: Boolean = false): BrainModel? = if (p.tpu == TpuSupport.BETA && !tpuFailed) BrainModel.GENERAL else null

    val fallbackTitleText: Phrase get() = ModelStrings.titleFallback()

    /** [m]'s name in Settings › Model on [p]: the fallback build says so ([FALLBACK_TITLE]), every other its own title. */
    fun titleText(m: BrainModel, p: Phone, tpuFailed: Boolean = false): Phrase = if (m == fallback(p, tpuFailed)) fallbackTitleText else m.titleText

    /** The model [stored] means on [p]: any build of Fast is this phone's Fast. */
    fun resolve(stored: BrainModel, p: Phone, tpuFailed: Boolean = false): BrainModel = if (stored.isFast) fast(p, tpuFailed) else stored

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
    fun memoryWarningText(m: BrainModel, p: Phone): Phrase? =
        if (p.smallMemory && m.memoryBytes >= 4_000_000_000L) ModelStrings.memoryWarning("size" to PhoneStatus.sizeText(p.totalRam)) else null

}

/**
 * How fast a model is on a phone, in plain words, with where the figures come from (0.4.7). [measured]: Robot measured
 * them on this kind of phone; otherwise they are estimates. A new conversation reads Robot's whole prompt (about
 * [PROMPT_TOKENS] tokens with the actions), so its first word waits for that; later answers read only the new turn.
 * 1.2: [here]: measured on this very phone, from your own answers (core SpeedLog).
 */
data class SpeedEstimate(val firstWord: String, val later: String, val words: String, val measured: Boolean, val sourceText: Phrase,
    val here: Boolean = false) {

    /** "Estimate: a new conversation starts answering in about 3–5 s, …". */
    fun textPhrase(): Phrase = (if (here) ModelStrings.measuredHere else if (measured) ModelStrings.measured else ModelStrings.estimate)(
        "first" to firstWord, "later" to later, "words" to words)

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

    private fun of(r: Rates, measured: Boolean, vararg source: Phrase?, laterTokens: Int = 120): SpeedEstimate =
        SpeedEstimate(seconds(PROMPT_TOKENS, r.readLo, r.readHi), seconds(laterTokens, r.readLo, r.readHi), words(r), measured,
            Phrase.Join(source.filterNotNull()))

    /** Google's own figures, from its model card. */
    private val googleCpu: Phrase get() = ModelStrings.googleCpu("read" to Arg.Count(557), "written" to Arg.Num(46.9))
    private val googleGpu: Phrase get() = ModelStrings.googleGpu("read" to Arg.Count(3808), "written" to Arg.Num(52.1))

    /**
     * [m] on [backend] on [p], or null when Robot has no figure (your own file). 1.2 (design Step 5): what Robot measured on
     * this phone ([here], SpeedLog's medians for that model, backend, file and Android build) comes first, for any model;
     * without it, today's estimate with its source. (The design's middle tier, the Test Bench's figures for the same chip,
     * has no rows yet.)
     */
    fun of(m: BrainModel, backend: ModelBackend, p: Phone, here: MeasuredSpeed? = null): SpeedEstimate? {
        if (here != null) return measuredHere(m, here)
        val (r, measured, source) = estimate(m, backend, p) ?: return null
        return of(r, measured, *source.toTypedArray(), laterTokens = if (m == BrainModel.SMARTER || m == BrainModel.UNCENSORED) 140 else 120)
    }

    /**
     * 1.2 (design Step 3): the estimate's reading speed for [m] on [backend] on [p], the low end of its range in tokens a
     * second (the reading budget errs on the slow side), or null when Robot has no figure (your own file). The same figures
     * as [of]'s estimate, never this phone's measurement (PrefillBudget takes that first).
     */
    fun readTps(m: BrainModel, backend: ModelBackend, p: Phone): Double? = estimate(m, backend, p)?.first?.readLo?.toDouble()

    /** The estimate's rates for [m] on [backend] on [p], whether they were measured on this kind of phone, and their sources. */
    private fun estimate(m: BrainModel, backend: ModelBackend, p: Phone): Triple<Rates, Boolean, List<Phrase?>>? {
        val chip = p.chip
        val older = chip in setOf(Chip.TENSOR_G1, Chip.TENSOR_G2, Chip.TENSOR_G3)
        val olderNote = if (older) ModelStrings.olderChip("chip" to chip.title) else null
        return when {
            m == BrainModel.FAST && backend == ModelBackend.TPU -> Triple(TPU_PIXEL10, chip == Chip.TENSOR_G5, listOf(ModelStrings.sourceTpu()))
            // 0.8, beta: no Tensor G6 figure exists anywhere yet; the Tensor G5 build's, never shown as measured.
            m == BrainModel.FAST_G6 && backend == ModelBackend.TPU -> Triple(TPU_PIXEL10, false, listOf(ModelStrings.sourceG6()))
            m == BrainModel.GENERAL && backend == ModelBackend.GPU -> when {
                chip.tensor && chip.gpu == GpuKind.MALI -> Triple(GPU_MALI_G4, false, listOf(ModelStrings.sourceGpuMali(),
                    ModelStrings.googleGpuSmaller("read" to Arg.Count(3808), "written" to Arg.Num(52.1)), olderNote))
                else -> Triple(GPU_MALI_G4, false, listOf(ModelStrings.sourceNoGpuFigure(), googleGpu))
            }
            m == BrainModel.GENERAL -> when {
                chip == Chip.TENSOR_G5 || chip == Chip.TENSOR_G6 -> Triple(CPU_E2B_PIXEL10, chip == Chip.TENSOR_G5, listOf(ModelStrings.sourceCpu(), googleCpu))
                else -> Triple(scaled(CPU_E2B_PIXEL10, 0.67, 0.8), false, listOf(ModelStrings.sourceCpuG4(), googleCpu, olderNote))
            }
            m == BrainModel.SMARTER || m == BrainModel.UNCENSORED -> when (chip) {
                Chip.TENSOR_G5, Chip.TENSOR_G6 -> Triple(CPU_E4B_PIXEL10, chip == Chip.TENSOR_G5, listOf(ModelStrings.sourceE4b()))
                else -> Triple(scaled(CPU_E4B_PIXEL10, 0.67, 0.8), false, listOf(ModelStrings.sourceE4bG4(), olderNote))
            }
            else -> null
        }
    }

    /**
     * SpeedLog's medians as the estimates read: the same prompt sizes, one figure instead of a range. 1.2 (design Step 8): a
     * new conversation's first word is the measured one when Robot has it (the median over conversations read from the
     * start, PhoneFit's figure too, so the setup guide's row and its warning agree), else the prompt at the reading speed.
     */
    private fun measuredHere(m: BrainModel, x: MeasuredSpeed): SpeedEstimate {
        val read = Math.round(x.readTps).toInt().coerceAtLeast(1)
        val later = if (m == BrainModel.SMARTER || m == BrainModel.UNCENSORED) 140 else 120
        val words = Math.round(x.writeTps * WORDS_PER_TOKEN).toInt().toString()
        val first = x.firstWordMs?.takeIf { it > 0 }?.let { "${PhoneFit.seconds(it / 1000.0)} s" } ?: seconds(PROMPT_TOKENS, read, read) // l10n: not text (a figure and its unit, as seconds() writes them)
        return SpeedEstimate(first, seconds(later, read, read), words, true,
            ModelStrings.measuredHereSource("n" to Arg.Count(x.replies.toLong()), "read" to Arg.Count(read.toLong()), "written" to Arg.Num(x.writeTps)),
            here = true)
    }
}
