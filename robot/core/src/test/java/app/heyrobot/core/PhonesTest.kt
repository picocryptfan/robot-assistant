package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.7: which chip a phone has, which Fast it gets, where that runs, and the speeds Settings › Model shows. */
class PhonesTest {
    private fun pixel(model: String, soc: String, ram: Long = 7_500_000_000L) = Phone("Google", model, "Google", soc, ram)

    private val pixel9a = pixel("Pixel 9a", "Tensor G4")
    private val pixel10Pro = pixel("Pixel 10 Pro", "Tensor G5", 15_500_000_000L)
    private val pixel11 = pixel("Pixel 11", "Tensor G6", 11_500_000_000L)

    /** Build.SOC_MODEL as each Pixel's factory image sets ro.soc.model (read from the vendor images, 2026-10). */
    @Test fun everyPixelsChipFromItsSocModel() {
        val socs = mapOf(
            "Pixel 6" to "Tensor", "Pixel 7" to "GS201", "Pixel 8" to "Tensor G3", "Pixel 8 Pro" to "Tensor G3", "Pixel 8a" to "Tensor G3",
            "Pixel 9" to "Tensor G4", "Pixel 9 Pro" to "Tensor G4", "Pixel 9 Pro XL" to "Tensor G4", "Pixel 9 Pro Fold" to "Tensor G4",
            "Pixel 9a" to "Tensor G4", "Pixel 10a" to "Tensor G4", "Pixel 10 Pro" to "Tensor G5",
            "Pixel 11" to "Tensor G6", "Pixel 11 Pro" to "Tensor G6", "Pixel 11 Pro XL" to "Tensor G6", "Pixel 11 Pro Fold" to "Tensor G6",
        )
        for ((model, soc) in socs) {
            val expected = Phones.PIXELS.single { it.first == model }.second
            assertEquals(model, expected, pixel(model, soc).chip)
        }
        assertEquals(Chip.TENSOR_G1, pixel("Pixel 6", "Tensor").chip)
        assertEquals(Chip.TENSOR_G2, pixel("Pixel 7", "GS201").chip)
    }

    /** Without Build.SOC_MODEL, a Google phone's model name decides; every Pixel GrapheneOS supports is listed. */
    @Test fun everyPixelByItsNameAlone() {
        assertEquals(25, Phones.PIXELS.size)
        assertEquals(Phones.PIXELS.size, Phones.PIXELS.map { it.first }.toSet().size)
        for ((model, chip) in Phones.PIXELS) assertEquals(model, chip, Phone("Google", model).chip)
        // The Pixel 10a has last year's chip: never the Pixel 10's TPU.
        assertEquals(Chip.TENSOR_G4, Phone("Google", "Pixel 10a").chip)
        assertEquals(TpuSupport.NONE, Phone("Google", "Pixel 10a").tpu)
        for (m in listOf("Pixel 10", "Pixel 10 Pro", "Pixel 10 Pro XL", "Pixel 10 Pro Fold")) assertEquals(m, TpuSupport.SUPPORTED, Phone("Google", m).tpu)
        for (m in listOf("Pixel 11", "Pixel 11 Pro", "Pixel 11 Pro XL", "Pixel 11 Pro Fold")) assertEquals(m, TpuSupport.NOT_YET, Phone("Google", m).tpu)
        for (m in listOf("Pixel 6", "Pixel 6a", "Pixel 7a", "Pixel Fold", "Pixel Tablet", "Pixel 8a", "Pixel 9", "Pixel 9a")) {
            assertEquals(m, TpuSupport.NONE, Phone("Google", m).tpu)
        }
    }

    /** The SoC field wins over the name; another maker's phone is never a Tensor by its name; unknown chips are OTHER. */
    @Test fun otherPhonesAreNeverTensors() {
        assertEquals(Chip.TENSOR_G4, Phone("Google", "Pixel 10", "Google", "Tensor G4").chip)
        assertEquals(Chip.OTHER, Phone("samsung", "Pixel 9a", "Qualcomm", "SM8850").chip)
        assertEquals(Chip.OTHER, Phone("samsung", "SM-S948B", "Qualcomm", "SM8850").chip)
        assertEquals(Chip.OTHER, Phone("Google", "Pixel 99", "Google", "Tensor G9").chip)
        assertEquals(Chip.OTHER, Phone("", "").chip)
        assertEquals("Samsung SM-S948B", Phone("samsung", "SM-S948B").name)
        assertEquals("Pixel 9a", pixel9a.name)
        assertEquals("This phone: Pixel 9a (Tensor G4)", Phones.line(pixel9a))
        assertEquals("This phone: Samsung SM-S948B", Phones.line(Phone("samsung", "SM-S948B", "Qualcomm", "SM8850")))
    }

    /** A Tensor G5 keeps its TPU Fast, unchanged; every other phone gets the general build, and sees only its own Fast. */
    @Test fun eachPhoneGetsItsFast() {
        assertEquals(BrainModel.FAST, ModelCatalogue.fast(pixel10Pro))
        assertEquals(listOf(BrainModel.FAST, BrainModel.SMARTER, BrainModel.UNCENSORED, BrainModel.OWN), ModelCatalogue.models(pixel10Pro))
        for (p in listOf(pixel9a, pixel11, pixel("Pixel 6a", "Tensor"), Phone("samsung", "SM-S948B", "Qualcomm", "SM8850"))) {
            assertEquals(p.name, BrainModel.GENERAL, ModelCatalogue.fast(p))
            assertEquals(p.name, listOf(BrainModel.GENERAL, BrainModel.SMARTER, BrainModel.UNCENSORED, BrainModel.OWN), ModelCatalogue.models(p))
        }
        // The default (and Fast chosen on a Pixel 10) means this phone's Fast; the settings file isn't rewritten.
        assertEquals(BrainModel.GENERAL, ModelCatalogue.resolve(RobotSettings().brainModelChoice, pixel9a))
        assertEquals(BrainModel.FAST, ModelCatalogue.resolve(BrainModel.GENERAL, pixel10Pro))
        assertEquals(BrainModel.FAST, ModelCatalogue.resolve(BrainModel.FAST, pixel10Pro))
        for (m in listOf(BrainModel.SMARTER, BrainModel.UNCENSORED, BrainModel.OWN)) assertEquals(m, ModelCatalogue.resolve(m, pixel9a))
        assertEquals(BrainModel.FAST.id, RobotSettings().brainModel)
    }

    /** The GPU: on by default for the general build where offered, never on a PowerVR GPU, never after it failed. */
    @Test fun whereTheGeneralBuildRuns() {
        assertEquals(ModelBackend.GPU, ModelCatalogue.backend(BrainModel.GENERAL, pixel9a, gpuOn = true, gpuFailed = false))
        assertEquals(ModelBackend.CPU, ModelCatalogue.backend(BrainModel.GENERAL, pixel9a, gpuOn = false, gpuFailed = false))
        assertEquals(ModelBackend.CPU, ModelCatalogue.backend(BrainModel.GENERAL, pixel9a, gpuOn = true, gpuFailed = true))
        assertTrue(RobotSettings().modelGpu)
        assertFalse(ModelCatalogue.gpuOffered(pixel10Pro))
        assertFalse(ModelCatalogue.gpuOffered(pixel11))
        assertEquals(ModelBackend.CPU, ModelCatalogue.backend(BrainModel.GENERAL, pixel11, gpuOn = true, gpuFailed = false))
        assertEquals(ModelBackend.CPU, ModelCatalogue.backend(BrainModel.GENERAL, pixel10Pro, gpuOn = true, gpuFailed = false))
        assertTrue(ModelCatalogue.gpuOffered(Phone("samsung", "SM-S948B", "Qualcomm", "SM8850")))
        // Nothing else ever runs on the GPU.
        for (m in BrainModel.entries.filter { it != BrainModel.GENERAL }) {
            assertEquals(m.name, m.backend, ModelCatalogue.backend(m, pixel9a, gpuOn = true, gpuFailed = false))
            assertFalse(m.name, m.runsOn(ModelBackend.GPU))
        }
        assertTrue(BrainModel.GENERAL.runsOn(ModelBackend.GPU) && BrainModel.GENERAL.runsOn(ModelBackend.CPU) && !BrainModel.GENERAL.runsOn(ModelBackend.TPU))
    }

    /** :brain's own check: no TPU load on a phone without the Tensor G5, no GPU load where Robot doesn't offer it. */
    @Test fun brainRefusesWhatThisPhoneCantRun() {
        val g = BrainModel.GENERAL
        val fp = "2588147712|1"
        val mark = "${g.sha256}|$fp"
        assertNull(LoadCheck.refuse(g, ModelBackend.GPU, fp, mark, null, phone = pixel9a))
        assertNull(LoadCheck.refuse(g, ModelBackend.CPU, fp, mark, null, phone = pixel9a))
        assertEquals(LoadCheck.NO_GPU, LoadCheck.refuse(g, ModelBackend.GPU, fp, mark, null, phone = pixel10Pro))
        assertEquals(LoadCheck.NO_GPU, LoadCheck.refuse(g, ModelBackend.GPU, fp, mark, null, phone = pixel11))
        assertTrue(LoadCheck.refuse(g, ModelBackend.TPU, fp, mark, null, phone = pixel10Pro)!!.contains("runs on the CPU only"))
        val f = BrainModel.FAST
        assertEquals(LoadCheck.NO_TPU, LoadCheck.refuse(f, ModelBackend.TPU, "3113545589|1", "${f.sha256}|3113545589|1", null, phone = pixel9a))
        assertNull(LoadCheck.refuse(f, ModelBackend.TPU, "3113545589|1", "${f.sha256}|3113545589|1", null, phone = pixel10Pro))
        assertTrue(LoadCheck.refuse(BrainModel.SMARTER, ModelBackend.GPU, fp, mark, null, phone = pixel9a)!!.contains("CPU only"))
    }

    /** Fast's fallback on a Pixel 9a is the general build: a chosen model that isn't ready falls back to it. */
    @Test fun fallbacksUseThisPhonesFast() {
        val g = BrainModel.GENERAL
        val ready = ModelAvailability.READY
        assertEquals(ModelChoice.Decision.Load(g), ModelChoice.decide(g, ready, ready, false, fast = g))
        assertEquals(ModelChoice.Decision.Load(g), ModelChoice.decide(g, ready, ready, true, fast = g))
        assertEquals(ModelChoice.Decision.Load(g, BrainModel.SMARTER, FallbackWhy.NOT_DOWNLOADED),
            ModelChoice.decide(BrainModel.SMARTER, ModelAvailability.NOT_DOWNLOADED, ready, false, fast = g))
        assertEquals(ModelChoice.Decision.Load(g, BrainModel.SMARTER, FallbackWhy.LOAD_FAILED),
            ModelChoice.afterFailure(BrainModel.SMARTER, LoadFailure.ENGINE, ready, fast = g))
        assertTrue(ModelChoice.afterFailure(g, LoadFailure.ENGINE, ready, "boom", fast = g) is ModelChoice.Decision.NoLoad)
        val none = ModelChoice.decide(g, ModelAvailability.NOT_DOWNLOADED, ModelAvailability.NOT_DOWNLOADED, false, fast = g) as ModelChoice.Decision.NoLoad
        assertEquals(ModelChoice.NO_MODEL, none.problem)
    }

    /** The E4B models warn on a phone with 8 GB or less; Fast never does. */
    @Test fun memoryWarnings() {
        for (m in listOf(BrainModel.SMARTER, BrainModel.UNCENSORED)) {
            val w = ModelCatalogue.memoryWarning(m, pixel9a)!!
            assertTrue(w, w.contains("7.5 GB") && w.contains("Fast is the safer choice"))
            assertNull(ModelCatalogue.memoryWarning(m, pixel10Pro))
            assertNull(ModelCatalogue.memoryWarning(m, Phone("Google", "Pixel 9a")))
        }
        for (m in listOf(BrainModel.GENERAL, BrainModel.FAST, BrainModel.OWN)) assertNull(ModelCatalogue.memoryWarning(m, pixel9a))
        // The general build fits beside the voice on an 8 GB phone.
        assertTrue(BrainModel.GENERAL.memoryBytes < 3_000_000_000L)
    }

    /** Every figure says whether it is measured or an estimate, and where it comes from. */
    @Test fun speedsAreLabelled() {
        val tpu = Speeds.of(BrainModel.FAST, ModelBackend.TPU, pixel10Pro)!!
        assertTrue(tpu.measured)
        assertEquals("Measured: a new conversation starts answering in about 3 s, later answers in about 1 s, then about 13–14 words a second.", tpu.text())
        val gpu = Speeds.of(BrainModel.GENERAL, ModelBackend.GPU, pixel9a)!!
        assertFalse(gpu.measured)
        assertTrue(gpu.text(), gpu.text().startsWith("Estimate: a new conversation starts answering in about 3–5 s"))
        assertTrue(gpu.source, gpu.source.contains("3,808") && gpu.source.contains("52.1") && gpu.source.contains("Pixel 10 Pro"))
        val cpu = Speeds.of(BrainModel.GENERAL, ModelBackend.CPU, pixel9a)!!
        assertFalse(cpu.measured)
        assertTrue(cpu.text(), cpu.text().contains("9–14 s") && cpu.text().contains("9–12 words"))
        assertTrue(cpu.source, cpu.source.contains("557") && cpu.source.contains("46.9") && cpu.source.contains("two thirds to four fifths"))
        // The GPU starts answering sooner than the CPU on a Tensor G4.
        assertTrue(gpu.firstWord < cpu.firstWord)
        val e4b = Speeds.of(BrainModel.SMARTER, ModelBackend.CPU, pixel9a)!!
        assertTrue(e4b.text(), !e4b.measured && e4b.text().contains("27–33 s"))
        assertTrue(Speeds.of(BrainModel.SMARTER, ModelBackend.CPU, pixel10Pro)!!.text().contains("21 s"))
        // An older Tensor says it is slower; your own file has no figure.
        assertTrue(Speeds.of(BrainModel.GENERAL, ModelBackend.GPU, pixel("Pixel 6a", "Tensor"))!!.source.contains("slower"))
        assertNull(Speeds.of(BrainModel.OWN, ModelBackend.CPU, pixel9a))
    }

    @Test fun theTpuListNamesEveryPixelFamily() {
        val all = Phones.TPU_LIST.joinToString(" ")
        for (w in listOf("Tensor G5", "Pixel 10 Pro Fold", "Tensor G6", "Pixel 11", "not supported by this version", "Pixel 10a", "GPU (recommended)")) {
            assertTrue(w, all.contains(w))
        }
        assertTrue(Phones.tpuLine(pixel9a).contains("Tensor G4") && Phones.tpuLine(pixel9a).contains("GPU"))
        assertTrue(Phones.tpuLine(pixel10Pro).startsWith("Its TPU is supported"))
        assertTrue(Phones.tpuLine(pixel11).contains("on the CPU"))
        assertFalse(Phones.tpuLine(pixel9a).contains("this phone's TPU"))
    }
}
