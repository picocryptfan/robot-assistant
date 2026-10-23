// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ModelStrings
import java.time.ZoneOffset
import java.time.ZonedDateTime

// Pure. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 8): a plain warning before setup ends, on a phone where
// Robot will be slow or its memory is short. The setup guide shows it on its phone step and again in its summary, Settings ›
// Model in its phone group; it never blocks anything. Slow: Fast runs on the CPU (by chip, after its GPU or TPU failed,
// or with "Use the GPU" off), or a new conversation's first word takes over [PhoneFit.SLOW_FIRST_WORD_S] seconds. Memory:
// under 8 GB is tight, under about 5 GB too small. A Pixel on its GPU or its TPU is never slow (PhoneFitTest).

/** How this phone's memory suits Robot's model: [TIGHT] under 8 GB ([Phone.underEightGb]), [TOO_SMALL] under about 5 GB. */
enum class FitMemory { OK, TIGHT, TOO_SMALL }

/**
 * What the warning offers to change, each a button that does what it says: [USE_GPU], "Use the GPU" back on where it is off
 * by your choice and the GPU hasn't failed; [TRANSLATE_OFF], "Translate transcripts" off (the model translates each line
 * too); [HELPER], set up a helper on a phone too small for the model, where none is set up.
 */
enum class FitOffer { USE_GPU, TRANSLATE_OFF, HELPER }

/**
 * The warning for [model] (this phone's Fast) on [backend] on a phone of [chip] and [totalRam] bytes: whether it is [slow],
 * how its [memory] suits the model, and the numbers it states: a new conversation's first word in about [firstWordS]
 * seconds and later answers in about [laterS], [measured] on this phone or Robot's estimate; [budget] is the reading budget
 * the panel gives that backend (core PrefillBudget), which says whether Robot reads back fewer exchanges there.
 */
data class PhoneFit(
    val model: BrainModel,
    val backend: ModelBackend,
    val chip: Chip,
    val slow: Boolean,
    val memory: FitMemory,
    val firstWordS: Int,
    val laterS: Int,
    val measured: Boolean,
    val budget: PrefillBudget,
    val totalRam: Long,
    val offers: Set<FitOffer> = emptySet(),
) {
    /** Whether the warning shows at all. */
    val shows: Boolean get() = slow || memory != FitMemory.OK

    /** Its title: the memory first when the model may not fit, then the speed, then tight memory. */
    val titleText: Phrase get() = when {
        memory == FitMemory.TOO_SMALL -> ModelStrings.fitSmallTitle()
        slow -> ModelStrings.fitSlowTitle()
        else -> ModelStrings.fitTightTitle()
    }

    /**
     * Its sentences, in order: where the model runs and how long answers take (slow only), what Robot does about it (on the
     * CPU, where the panel reads back fewer exchanges), what a picture adds, then the memory. Empty when it doesn't show.
     */
    val lines: List<Phrase> get() = if (!shows) emptyList() else buildList {
        if (slow) {
            add(ModelStrings.fitRunsOn("device" to backend.titleOn(chip)))
            add((if (measured) ModelStrings.fitMeasured else ModelStrings.fitEstimate)("first" to firstWordS, "later" to laterS))
            when {
                budget.compact -> add(ModelStrings.fitCompact())
                budget.tokens >= 0 -> add(ModelStrings.fitHistory())
            }
            add(ModelStrings.fitPicture())
        }
        val size = PhoneStatus.sizeText(totalRam)
        val needs = PhoneStatus.sizeText(model.memoryBytes)
        when (memory) {
            FitMemory.TIGHT -> add(ModelStrings.fitMemoryTight("size" to size, "model" to needs))
            FitMemory.TOO_SMALL -> add(ModelStrings.fitMemorySmall("size" to size, "model" to needs))
            FitMemory.OK -> {}
        }
    }

    companion object {
        /** A new conversation whose first word takes longer than this is slow, wherever the model runs (EST, design Step 8). */
        const val SLOW_FIRST_WORD_S = 10

        /**
         * Under this much memory the model may not fit: a 4 GB phone. The general build (about 2.6 GB while loaded) and the
         * speech models (about 1 GB on disk) beside Android (EST, design Step 8).
         */
        const val TOO_SMALL_BYTES = 5_000_000_000L

        /** A later answer reads only the new turn: about this many tokens, as Settings' figures count it ([Speeds]). */
        const val LATER_TOKENS = 120

        /**
         * The warning for [model] on [p]: where it runs ([ModelCatalogue.backend] with [gpuOn] and [gpuFailed]), what this phone
         * measured for it there ([measured], SpeedLog's medians for that backend, or null), and for the offers whether
         * "Translate transcripts" is on ([translate]) and a helper can answer ([helperReady]).
         *
         * The first word: this phone's measured first word of a new or rebuilt conversation when it has one (SpeedLog's
         * median, real), else the prompt it reads (Robot's [Speeds.PROMPT_TOKENS], scaled to the compact form where the panel
         * uses it, [compactShare]) at the reading speed the panel's budget takes: the measured median, else the low end of
         * Robot's estimate, else [PrefillBudget.UNKNOWN_CPU_TPS]. Later answers: [LATER_TOKENS] at that speed. Whole seconds,
         * at least 1.
         */
        fun of(p: Phone, model: BrainModel, gpuOn: Boolean, gpuFailed: Boolean, measured: (ModelBackend) -> MeasuredSpeed? = { null },
            translate: Boolean = false, helperReady: Boolean = false): PhoneFit {
            val backend = ModelCatalogue.backend(model, p, gpuOn, gpuFailed)
            val here = measured(backend)
            val estimateTps = Speeds.readTps(model, backend, p)
            val budget = PrefillBudget.of(backend, here?.readTps, estimateTps)
            val readTps = here?.readTps?.takeIf { it.isFinite() && it > 0.0 } ?: estimateTps?.takeIf { it.isFinite() && it > 0.0 }
                ?: PrefillBudget.UNKNOWN_CPU_TPS
            val share = if (budget.compact) compactShare else 1.0
            val first = here?.firstWordMs?.takeIf { it > 0 }?.let { it / 1000.0 } ?: (Speeds.PROMPT_TOKENS * share / readTps)
            val firstS = seconds(first)
            // The rounded figure, as the warning states it: "about 10 s" is never called slow on a GPU or a TPU.
            val slow = backend == ModelBackend.CPU || firstS > SLOW_FIRST_WORD_S
            val memory = when {
                p.totalRam in 1 until TOO_SMALL_BYTES -> FitMemory.TOO_SMALL
                p.underEightGb -> FitMemory.TIGHT
                else -> FitMemory.OK
            }
            val offers = buildSet {
                if (slow && model.gpu && ModelCatalogue.gpuOffered(p) && !gpuOn && !gpuFailed) add(FitOffer.USE_GPU)
                if (slow && translate) add(FitOffer.TRANSLATE_OFF)
                if (memory == FitMemory.TOO_SMALL && !helperReady) add(FitOffer.HELPER)
            }
            return PhoneFit(model, backend, p.chip, slow, memory, firstS, seconds(LATER_TOKENS / readTps), here != null, budget, p.totalRam, offers)
        }

        /** Whole seconds, at least 1 (half up). */
        fun seconds(x: Double): Int = if (!x.isFinite()) 1 else Math.round(x).toInt().coerceAtLeast(1)

        /**
         * The compact prompt's size as a share of the full one, as the panel builds both for a new conversation (the system
         * prompt and the core tools' descriptions, [Prompt.fixedTokens]): what a slow CPU reads instead of [Speeds.PROMPT_TOKENS].
         * Estimated tokens on both sides, so the estimate's error mostly cancels; the real count is UNVERIFIED until a phone's
         * brain_fixed_tokens is read (PrefillBudget).
         */
        val compactShare: Double by lazy {
            val full = Prompt.fixedTokens(Prompt.system(REFERENCE_DAY, locked = false), Prompt.CORE_TOOLS)
            val compact = Prompt.fixedTokens(Prompt.system(REFERENCE_DAY, locked = false, compact = true), Prompt.CORE_TOOLS, compact = true)
            (compact.toDouble() / full).coerceIn(0.1, 1.0)
        }

        /** A day for the system prompt's date line, which changes its size by a token or two at most. */
        private val REFERENCE_DAY: ZonedDateTime = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC)
    }
}
