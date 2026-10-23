// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2 (design Step 8): the weak-phone warning. When it shows (each condition alone and mixed), that a Pixel on its GPU or its
 * TPU is never told it is slow, the numbers it states (measured or estimated, rounded, in seconds, in English and in Québec
 * French) and what it offers to change. PhoneDecisionsTest's frozen record is left as it is: nothing here changes a decision
 * it holds.
 */
class PhoneFitTest {
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '

    private val nineA = Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_600_000_000L)
    private val pixel10 = Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 15_500_000_000L)
    private val pixel11 = Phone("Google", "Pixel 11", "Google", "Tensor G6", 11_500_000_000L)
    private val pixel6a = Phone("Google", "Pixel 6a", "Google", "gs101", 5_700_000_000L)
    private val galaxy = Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L)
    private val motoG = Phone("motorola", "moto g", "QTI", "SM6375", 3_700_000_000L)
    private val exynos6gb = Phone("samsung", "SM-A566B", "Samsung", "s5e8855", 5_600_000_000L)

    private fun fit(p: Phone, gpuOn: Boolean = true, gpuFailed: Boolean = false, tpuFailed: Boolean = false, measured: MeasuredSpeed? = null,
        translate: Boolean = false, helperReady: Boolean = false): PhoneFit =
        PhoneFit.of(p, ModelCatalogue.fast(p, tpuFailed), gpuOn, gpuFailed, { measured }, translate, helperReady)

    private fun speed(readTps: Double, firstWordMs: Long?) = MeasuredSpeed(readTps, 12.0, 5, 5, firstWordMs, 6)

    private fun PhoneFit.en(): List<String> = listOf(titleText.en()) + lines.map { it.en() }
    private fun PhoneFit.fr(): List<String> = listOf(titleText.render(fr)) + lines.map { it.render(fr) }

    // ------------------------------------------------------------------ when it shows

    @Test fun aPixelOnItsGpuOrItsTpuIsNeverSlow() {
        // Every Pixel GrapheneOS supports, by name, at 8 GB and more: no warning at all on the GPU (G1 to G4) or the TPU (G5, G6).
        for ((name, _) in Phones.PIXELS) for (ram in listOf(7_600_000_000L, 11_500_000_000L, 15_500_000_000L)) {
            val p = Phone("Google", name, totalRam = ram)
            val f = fit(p)
            assertTrue(name, f.backend == ModelBackend.GPU || f.backend == ModelBackend.TPU)
            assertFalse("$name $ram", f.slow)
            assertFalse("$name $ram", f.shows)
            // Measured at a plausible speed for where it runs: still not slow.
            val m = if (f.backend == ModelBackend.TPU) speed(520.0, 2_900) else speed(410.0, 4_100)
            assertFalse("$name measured", fit(p, measured = m).shows)
        }
    }

    @Test fun thePixel6aGetsOnlyTheMemoryLine() {
        // 6 GB, Android reports about 5.7: tight, on its GPU, and nothing said about speed.
        val f = fit(pixel6a)
        assertEquals(ModelBackend.GPU, f.backend)
        assertFalse(f.slow)
        assertEquals(FitMemory.TIGHT, f.memory)
        assertEquals(listOf("This phone's memory is tight for Robot",
            "This phone has 5.7 GB of memory. Robot's model uses about 2.6 GB of it while it's loaded, and the voice models need room " +
                "too: Android may stop the model while it answers."), f.en())
        assertEquals(emptySet<FitOffer>(), f.offers)
    }

    @Test fun theCpuIsSlowWhateverPutItThere() {
        // The GPU's check failed (a fault), "Use the GPU" off, a Tensor G6 whose beta TPU build failed (its PowerVR GPU is never used).
        assertTrue(fit(nineA, gpuFailed = true).slow)
        assertTrue(fit(nineA, gpuOn = false).slow)
        val eleven = fit(pixel11, tpuFailed = true)
        assertEquals(BrainModel.GENERAL, eleven.model)
        assertEquals(ModelBackend.CPU, eleven.backend)
        assertTrue(eleven.slow)
        // Before its failure the Tensor G6 is on its TPU: not slow.
        assertFalse(fit(pixel11).shows)
        assertFalse(fit(pixel10).shows)
        // A CPU measured fast is still the CPU.
        assertTrue(fit(nineA, gpuFailed = true, measured = speed(450.0, 3_300)).slow)
    }

    @Test fun aGpuOrTpuIsSlowOnlyWhenItsFirstWordTakesOver10s() {
        assertFalse(fit(galaxy).slow)
        // Measured: 15 s on the GPU is slow; 10.4 s reads "about 10 s" and isn't.
        val slowGpu = fit(galaxy, measured = speed(110.0, 14_800))
        assertTrue(slowGpu.slow)
        assertEquals(ModelBackend.GPU, slowGpu.backend)
        assertFalse(fit(galaxy, measured = speed(150.0, 10_400)).slow)
        assertTrue(fit(galaxy, measured = speed(150.0, 10_500)).slow)
        // Without a measured first word, the measured reading speed: 1,500 tokens at 120 a second is 12.5 s.
        assertTrue(fit(galaxy, measured = speed(120.0, null)).slow)
        assertFalse(fit(galaxy, measured = speed(160.0, null)).slow)
    }

    @Test fun memoryLevels() {
        fun level(ram: Long) = fit(Phone("Google", "Pixel 9a", "Google", "Tensor G4", ram)).memory
        assertEquals(FitMemory.OK, level(0))
        assertEquals(FitMemory.OK, level(7_600_000_000L))
        assertEquals(FitMemory.OK, level(7_000_000_000L))
        assertEquals(FitMemory.TIGHT, level(6_999_999_999L))
        assertEquals(FitMemory.TIGHT, level(5_000_000_000L))
        assertEquals(FitMemory.TOO_SMALL, level(4_999_999_999L))
        assertEquals(FitMemory.TOO_SMALL, level(1))
        // Unknown memory (0) is never a warning by itself.
        assertFalse(fit(Phone("Google", "Pixel 9a", "Google", "Tensor G4", 0)).shows)
    }

    @Test fun slowAndTightTogether() {
        val f = fit(exynos6gb, gpuFailed = true)
        assertTrue(f.slow)
        assertEquals(FitMemory.TIGHT, f.memory)
        assertEquals("Robot will be slow on this phone", f.titleText.en())
        assertEquals(5, f.lines.size)
        assertTrue(f.lines.last().en().startsWith("This phone has 5.6 GB of memory."))
    }

    @Test fun tooSmallComesFirstAndOffersAHelper() {
        val onGpu = fit(motoG)
        assertFalse(onGpu.slow)
        assertEquals(FitMemory.TOO_SMALL, onGpu.memory)
        assertEquals(listOf("Robot's model may not fit in this phone's memory",
            "This phone has 3.7 GB of memory. Robot's model uses about 2.6 GB of it while it's loaded, and the voice models need room " +
                "too: it may not load here, or Android may stop it while it answers. Robot has no smaller model yet."), onGpu.en())
        assertEquals(setOf(FitOffer.HELPER), onGpu.offers)
        assertEquals(emptySet<FitOffer>(), fit(motoG, helperReady = true).offers)
        // On its CPU too: the memory's title, then the speed, then the memory.
        val onCpu = fit(motoG, gpuFailed = true)
        assertTrue(onCpu.slow)
        assertEquals("Robot's model may not fit in this phone's memory", onCpu.titleText.en())
        assertEquals("Robot's model runs on this phone's CPU.", onCpu.lines.first().en())
        assertTrue(onCpu.lines.last().en().endsWith("Robot has no smaller model yet."))
    }

    // ------------------------------------------------------------------ the numbers

    @Test fun theEstimateReadsTheCompactPromptAtTheBudgetsSpeed() {
        // A Pixel 9a's CPU: the low end of its estimate, 107 tokens a second (160 × 0.67), below 187.5: the compact prompt.
        val f = fit(nineA, gpuFailed = true)
        assertFalse(f.measured)
        assertTrue(f.budget.compact)
        assertEquals(107.0, f.budget.readTps, 0.0)
        val share = PhoneFit.compactShare
        assertTrue("compact share $share", share in 0.5..0.99)
        assertEquals(Math.round(Speeds.PROMPT_TOKENS * share / 107.0).toInt(), f.firstWordS)
        assertEquals(1, f.laterS)
        // On the GPU the full prompt at its estimate's low end, 300: 5 s.
        assertEquals(5, fit(nineA).firstWordS)
    }

    @Test fun measuredFiguresComeFirstAndRoundHalfUp() {
        val f = fit(nineA, gpuFailed = true, measured = speed(95.3, 12_500))
        assertTrue(f.measured)
        assertEquals(13, f.firstWordS)
        assertEquals(1, f.laterS)
        assertEquals(12, fit(nineA, gpuFailed = true, measured = speed(95.3, 12_499)).firstWordS)
        // A slower CPU: 120 tokens at 40 a second is 3 s.
        assertEquals(3, fit(nineA, gpuFailed = true, measured = speed(40.0, 31_000)).laterS)
        // No measured first word: the compact prompt at the measured reading speed.
        val noFirst = fit(nineA, gpuFailed = true, measured = speed(95.3, null))
        assertEquals(Math.round(Speeds.PROMPT_TOKENS * PhoneFit.compactShare / 95.3).toInt(), noFirst.firstWordS)
        // Never under 1 s.
        assertEquals(1, PhoneFit.seconds(0.2))
        assertEquals(1, PhoneFit.seconds(Double.NaN))
    }

    /** The setup guide's speed row (Speeds, with this phone's measurement) states the same first word and later answer. */
    @Test fun theRowAboveAndTheWarningAgree() {
        val m = speed(95.3, 12_500)
        val f = fit(nineA, gpuFailed = true, measured = m)
        val row = Speeds.of(BrainModel.GENERAL, ModelBackend.CPU, nineA, m)!!
        assertEquals("13 s", row.firstWord)
        assertEquals("${f.firstWordS} s", row.firstWord)
        assertEquals("${f.laterS} s", row.later)
        // Without a measured first word, the row reads the full prompt at the reading speed, as job 2 made it.
        assertEquals("16 s", Speeds.of(BrainModel.GENERAL, ModelBackend.CPU, nineA, speed(95.3, null))!!.firstWord)
        // Job 2's Galaxy S25 figures (410 tokens a second, 3.5 s to the first word) read the same either way: 4 s.
        assertEquals("4 s", Speeds.of(BrainModel.GENERAL, ModelBackend.GPU, galaxy, speed(410.0, 3_500))!!.firstWord)
    }

    @Test fun theWordsInEnglish() {
        val f = fit(nineA, gpuFailed = true, measured = speed(95.3, 12_500))
        assertEquals(listOf(
            "Robot will be slow on this phone",
            "Robot's model runs on this phone's CPU.",
            "Measured on this phone: a new conversation starts answering in about 13${nb}s, later answers in about 1${nb}s.",
            "To answer sooner, Robot reads back fewer earlier exchanges of a conversation here, and gives its model shorter instructions.",
            "A question about a photo or the screen takes longer again: the model looks at the picture first.",
        ), f.en())
        val e = fit(nineA, gpuFailed = true)
        assertEquals("Estimate: a new conversation starts answering in about ${e.firstWordS}${nb}s, later answers in about 1${nb}s.", e.lines[1].en())
    }

    @Test fun theWordsInQuebecFrench() {
        val f = fit(exynos6gb, gpuFailed = true, measured = speed(95.3, 12_500))
        assertEquals(listOf(
            "Robot sera lent sur ce téléphone",
            "Le modèle de Robot fonctionne sur le CPU de ce téléphone.",
            "Mesuré sur ce téléphone$nb: une nouvelle conversation commence à répondre en environ 13${nb}s, les réponses suivantes en " +
                "environ 1${nb}s.",
            "Pour répondre plus vite, Robot relit ici moins d’échanges précédents d’une conversation, et donne des consignes plus courtes à son modèle.",
            "Une question sur une photo ou sur l’écran prend encore plus de temps$nb: le modèle regarde d’abord l’image.",
            "Ce téléphone a 5,6${nb}Go de mémoire. Le modèle de Robot en utilise environ 2,6${nb}Go quand il est chargé, et les modèles de voix " +
                "ont aussi besoin de place$nb: Android pourrait arrêter le modèle pendant qu’il répond.",
        ), f.fr())
        val e = fit(nineA, gpuFailed = true)
        assertEquals("Estimation$nb: une nouvelle conversation commence à répondre en environ ${e.firstWordS}${nb}s, les réponses suivantes en " +
            "environ 1${nb}s.", e.lines[1].render(fr))
        assertEquals("La mémoire de ce téléphone est juste pour Robot", fit(pixel6a).titleText.render(fr))
        assertEquals("Le modèle de Robot pourrait ne pas tenir dans la mémoire de ce téléphone", fit(motoG).titleText.render(fr))
        assertTrue(fit(motoG).lines.last().render(fr).endsWith("Robot n’a pas encore de modèle plus petit."))
    }

    @Test fun onlyTheCpuReadsBackFewerExchanges() {
        // A CPU between 187.5 and 416 tokens a second keeps the full prompt but caps the history; above, no budget at all.
        assertEquals("To answer sooner, Robot reads back fewer earlier exchanges of a conversation here.",
            fit(nineA, gpuFailed = true, measured = speed(250.0, 6_000)).lines[2].en())
        val fastCpu = fit(nineA, gpuFailed = true, measured = speed(450.0, 3_300))
        assertFalse(fastCpu.lines.any { "fewer earlier exchanges" in it.en() })
        // A slow GPU: the panel never cuts its prompt there, so the warning doesn't say it does.
        val gpu = fit(galaxy, measured = speed(110.0, 14_800))
        assertEquals(listOf("Robot will be slow on this phone", "Robot's model runs on this phone's GPU.",
            "Measured on this phone: a new conversation starts answering in about 15${nb}s, later answers in about 1${nb}s.",
            "A question about a photo or the screen takes longer again: the model looks at the picture first."), gpu.en())
    }

    // ------------------------------------------------------------------ what it offers

    @Test fun theOffers() {
        // "Use the GPU" off by choice: offered; after the GPU failed, never (it would only fail again until you try it again).
        assertEquals(setOf(FitOffer.USE_GPU), fit(nineA, gpuOn = false).offers)
        assertEquals(emptySet<FitOffer>(), fit(nineA, gpuFailed = true).offers)
        assertEquals(emptySet<FitOffer>(), fit(nineA, gpuOn = false, gpuFailed = true).offers)
        // A Tensor G6's PowerVR GPU is never offered.
        assertEquals(emptySet<FitOffer>(), fit(pixel11, gpuOn = false, tpuFailed = true).offers)
        // Translate transcripts: offered off only where the phone is slow.
        assertEquals(setOf(FitOffer.TRANSLATE_OFF), fit(nineA, gpuFailed = true, translate = true).offers)
        assertEquals(emptySet<FitOffer>(), fit(nineA, translate = true).offers)
        assertEquals(setOf(FitOffer.USE_GPU, FitOffer.TRANSLATE_OFF), fit(nineA, gpuOn = false, translate = true).offers)
        // A helper: only where the model may not fit, and none is set up.
        assertEquals(setOf(FitOffer.HELPER, FitOffer.TRANSLATE_OFF), fit(motoG, gpuFailed = true, translate = true).offers)
        assertEquals(emptySet<FitOffer>(), fit(pixel6a).offers)
    }
}
