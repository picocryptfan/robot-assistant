// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2 (job 1): the chip map from Android's SoC fields (Socs), the chips' vendors and accelerators, the "under 8 GB"
 * threshold, and what Settings › Model says about the chip. Every non-Google row is UNVERIFIED until a phone reports it
 * (the design, Step 1); these tests pin what the code does with each value, and that a chip it names runs exactly as an
 * unknown chip did.
 */
class ChipMapTest {
    private val fr = Langs.of(Lang.FR)

    /** Each mapped SOC_MODEL, as the design's table expects it, and the variants the normaliser must read the same. */
    @Test fun eachMappedSocModel() {
        val table = listOf(
            "QTI" to "SM8850" to Chip.SNAPDRAGON_8_ELITE_GEN5,
            "QTI" to "SM8750" to Chip.SNAPDRAGON_8_ELITE,
            "QTI" to "SM8650" to Chip.SNAPDRAGON_8_GEN3,
            "QTI" to "SM8550" to Chip.SNAPDRAGON_8_GEN2,
            "QTI" to "SM8475" to Chip.SNAPDRAGON_8_PLUS_GEN1,
            "QTI" to "SM8450" to Chip.SNAPDRAGON_8_GEN1,
            "QTI" to "SM7675" to Chip.SNAPDRAGON,
            "QTI" to "SM6375" to Chip.SNAPDRAGON,
            "Samsung" to "s5e9945" to Chip.EXYNOS_2400,
            "Samsung" to "s5e8855" to Chip.EXYNOS,
            "Samsung" to "s5e9955" to Chip.EXYNOS,
            "Mediatek" to "MT6989" to Chip.DIMENSITY,
            "Mediatek" to "MT6991" to Chip.DIMENSITY,
            "Mediatek" to "MT6993" to Chip.DIMENSITY,
            "Mediatek" to "MT6769" to Chip.MEDIATEK,
            "Mediatek" to "MT6878" to Chip.MEDIATEK,
        )
        for ((fields, chip) in table) {
            val (maker, model) = fields
            assertEquals("$maker $model", chip, Socs.of(maker, model))
            assertEquals("$maker $model", chip, Phone("someone", "Phone 1", maker, model).chip)
            // Case, spaces and a suffix after the part number don't matter.
            assertEquals("$maker $model", chip, Socs.of(" ${maker.lowercase()} ", " ${model.lowercase()} "))
            assertEquals("$maker $model", chip, Socs.of(maker.uppercase(), model.uppercase() + "-AC"))
        }
        // Qualcomm's two names for itself.
        assertEquals(Chip.SNAPDRAGON_8_ELITE, Socs.of("Qualcomm", "SM8750"))
        assertEquals(Chip.SNAPDRAGON_8_ELITE, Socs.of("qti", "sm8750-ac"))
        assertEquals(Chip.SNAPDRAGON_8_ELITE, Socs.of("QTI", "SM8750P"))
        assertEquals(Chip.DIMENSITY, Socs.of("MediaTek", "MT6991"))
        // Every named chip is in the map, and nothing maps to a Tensor.
        val named = Chip.entries.filter { !it.tensor && it != Chip.OTHER }
        assertEquals(named.toSet(), table.map { it.second }.toSet())
    }

    /** Unknown and empty values, other makers' parts and Samsung's model numbers are OTHER: the chip Robot always ran. */
    @Test fun unknownAndEmptyValuesAreOther() {
        for ((maker, model) in listOf(
            "" to "", "QTI" to "", "" to "SM8750", "Samsung" to "", "Mediatek" to "", "  " to "  ",
            // Samsung's model numbers start with "SM-": never a chip, in either field.
            "QTI" to "SM-S931W", "samsung" to "SM-S931W", "Samsung" to "SM8750", "Mediatek" to "SM8750",
            // A part number of another maker, or none at all.
            "QTI" to "s5e9945", "QTI" to "MT6989", "Samsung" to "MT6989", "Mediatek" to "s5e9945",
            "QTI" to "QCM6490", "QTI" to "SDM845", "QTI" to "SM875", "QTI" to "SM87501", "QTI" to "XSM8750",
            "Unisoc" to "T820", "Qualcomm Technologies" to "SM8750", "Samsung Electronics" to "s5e9945", "MTK" to "MT6989",
            "Spreadtrum" to "SC9863A", "QTI" to "Snapdragon 8 Elite", "Samsung" to "Exynos 2400", "Mediatek" to "Dimensity 9400",
        )) assertEquals("“$maker” “$model”", Chip.OTHER, Socs.of(maker, model))
        // A Galaxy with no SoC fields stays OTHER, whatever its model number says.
        assertEquals(Chip.OTHER, Phone("samsung", "SM-S931W").chip)
        assertEquals(Chip.OTHER, Phone("samsung", "SM-S931W", "", "").chip)
        assertEquals(Chip.OTHER, Phone("samsung", "SM8750", "", "").chip)
    }

    /** Pixels are read exactly as before: their SoC fields, else their name (PhoneDecisionsTest holds every decision). */
    @Test fun pixelsUnchanged() {
        val socs = mapOf("Tensor" to Chip.TENSOR_G1, "GS101" to Chip.TENSOR_G1, "Tensor G2" to Chip.TENSOR_G2, "GS201" to Chip.TENSOR_G2,
            "Tensor G3" to Chip.TENSOR_G3, "zuma" to Chip.TENSOR_G3, "Tensor G4" to Chip.TENSOR_G4, "zumapro" to Chip.TENSOR_G4,
            "Tensor G5" to Chip.TENSOR_G5, "Tensor G6" to Chip.TENSOR_G6)
        for ((soc, chip) in socs) {
            assertEquals(soc, chip, Socs.of("Google", soc))
            assertEquals(soc, chip, Socs.of("", soc.takeIf { it.startsWith("Tensor") } ?: continue))
        }
        for ((model, chip) in Phones.PIXELS) {
            assertEquals(model, chip, Phone("Google", model).chip)
            assertEquals(model, chip, Phone("Google", model, "Google", "").chip)
        }
        assertEquals(Chip.OTHER, Socs.of("Google", "Tensor G9"))
        assertEquals(Chip.OTHER, Socs.of("Google", "SM8750"))
        for (c in Chip.entries.filter { it.tensor }) {
            assertEquals(ChipVendor.GOOGLE, c.vendor)
            assertEquals("TPU", c.accelerator)
        }
        assertEquals(Chip.entries.filter { it.name.startsWith("TENSOR_") }, Chip.entries.filter { it.tensor })
    }

    /** Vendors, GPUs and accelerators: NPU only on a Snapdragon, no new chip has a PowerVR GPU (which Robot never uses). */
    @Test fun vendorsGpusAndAccelerators() {
        for (c in Chip.entries) {
            assertEquals(c.name, c.vendor == ChipVendor.GOOGLE, c.tensor)
            assertEquals(c.name, if (c.vendor == ChipVendor.QUALCOMM) "NPU" else "TPU", c.accelerator)
            assertEquals(c.name, c.title.isEmpty(), c == Chip.OTHER)
            if (!c.tensor) assertTrue(c.name, c.gpu != GpuKind.POWERVR && c.gpu != GpuKind.MALI)
        }
        assertEquals(setOf(GpuKind.ADRENO), Chip.entries.filter { it.vendor == ChipVendor.QUALCOMM }.map { it.gpu }.toSet())
        assertEquals(GpuKind.XCLIPSE, Chip.EXYNOS_2400.gpu)
        assertEquals("NPU", ModelBackend.TPU.titleOn(Chip.SNAPDRAGON_8_ELITE))
        assertEquals("TPU", ModelBackend.TPU.titleOn(Chip.TENSOR_G5))
        assertEquals("TPU", ModelBackend.TPU.titleOn(Chip.EXYNOS_2400))
        assertEquals("TPU", ModelBackend.TPU.titleOn(Chip.OTHER))
        for (c in Chip.entries) for (b in listOf(ModelBackend.CPU, ModelBackend.GPU)) assertEquals(b.title, b.titleOn(c))
        // The reports and logs keep the backend's own name.
        assertEquals("TPU", ModelBackend.TPU.title)
        assertEquals("npu", ModelBackend.TPU.id)
    }

    /**
     * Every chip 1.2 names runs exactly as an unknown chip: no TPU or NPU, the general build, the GPU offered and the same
     * estimates, the same refusals. (The comparison is made here, not written out, so it holds for any chip added later.)
     */
    @Test fun aNamedChipRunsAsAnUnknownOne() {
        val socs = mapOf(Chip.SNAPDRAGON_8_ELITE_GEN5 to ("QTI" to "SM8850"), Chip.SNAPDRAGON_8_ELITE to ("QTI" to "SM8750"),
            Chip.SNAPDRAGON_8_GEN3 to ("QTI" to "SM8650"), Chip.SNAPDRAGON_8_GEN2 to ("QTI" to "SM8550"),
            Chip.SNAPDRAGON_8_PLUS_GEN1 to ("QTI" to "SM8475"), Chip.SNAPDRAGON_8_GEN1 to ("QTI" to "SM8450"), Chip.SNAPDRAGON to ("QTI" to "SM6375"),
            Chip.EXYNOS_2400 to ("Samsung" to "s5e9945"), Chip.EXYNOS to ("Samsung" to "s5e8855"), Chip.DIMENSITY to ("Mediatek" to "MT6991"),
            Chip.MEDIATEK to ("Mediatek" to "MT6769"))
        assertEquals(Chip.entries.filter { !it.tensor && it != Chip.OTHER }.toSet(), socs.keys)
        for (ram in listOf(0L, 3_700_000_000L, 5_600_000_000L, 7_600_000_000L, 11_500_000_000L)) {
            val unknown = Phone("samsung", "SM-S931W", "", "", ram)
            assertEquals(Chip.OTHER, unknown.chip)
            for ((chip, fields) in socs) {
                val p = Phone("samsung", "SM-S931W", fields.first, fields.second, ram)
                assertEquals(chip, p.chip)
                assertEquals(chip.name, TpuSupport.NONE, p.tpu)
                assertEquals(chip.name, ModelCatalogue.gpuOffered(unknown), ModelCatalogue.gpuOffered(p))
                assertEquals(chip.name, p.smallMemory to p.underEightGb, unknown.smallMemory to unknown.underEightGb)
                for (failed in listOf(false, true)) {
                    assertEquals(chip.name, ModelCatalogue.fast(unknown, failed), ModelCatalogue.fast(p, failed))
                    assertEquals(chip.name, ModelCatalogue.models(unknown, failed), ModelCatalogue.models(p, failed))
                    assertEquals(chip.name, ModelCatalogue.fallback(unknown, failed), ModelCatalogue.fallback(p, failed))
                }
                for (m in BrainModel.entries) {
                    assertEquals(m.name, ModelCatalogue.resolve(m, unknown), ModelCatalogue.resolve(m, p))
                    assertEquals(m.name, ModelCatalogue.memoryWarningText(m, unknown)?.en(), ModelCatalogue.memoryWarningText(m, p)?.en())
                    for (on in listOf(true, false)) for (gpuFailed in listOf(false, true)) {
                        assertEquals(m.name, ModelCatalogue.backend(m, unknown, on, gpuFailed), ModelCatalogue.backend(m, p, on, gpuFailed))
                    }
                    for (b in ModelBackend.entries) {
                        val a = Speeds.of(m, b, unknown)
                        val e = Speeds.of(m, b, p)
                        assertEquals("${chip.name} ${m.name} $b", a?.textPhrase()?.en(), e?.textPhrase()?.en())
                        assertEquals("${chip.name} ${m.name} $b", a?.sourceText?.en(), e?.sourceText?.en())
                        assertEquals("${chip.name} ${m.name} $b", a?.sourceText?.render(fr), e?.sourceText?.render(fr))
                        assertEquals("${chip.name} ${m.name} $b", a?.measured, e?.measured)
                        if (!m.builtIn) continue
                        val fp = "${m.bytes}|1"
                        val refusal = LoadCheck.refuseText(m, b, fp, "${m.sha256}|$fp", null, maxTokens = m.contextTokens, phone = p)?.en()
                        assertEquals("${chip.name} ${m.name} $b",
                            LoadCheck.refuseText(m, b, fp, "${m.sha256}|$fp", null, maxTokens = m.contextTokens, phone = unknown)?.en(), refusal)
                        // Every TPU build, on every chip that isn't its own, is refused.
                        if (b == ModelBackend.TPU) assertEquals("${chip.name} ${m.name}", LoadCheck.NO_TPU, refusal)
                    }
                }
                // Your own TPU build, stamped for a Tensor or a Qualcomm chip, never runs on it either.
                for (stamp in listOf("Google Tensor_G5", "Google Tensor_G6", "Qualcomm SM8750", "Qualcomm SM8450")) {
                    val r = LiteRtLmReport(readable = true, decoder = true, aux = true, dispatchOp = true, stamp = stamp, modelType = "gemma4")
                    assertFalse("${chip.name} $stamp", r.runsOnTpuOf(p))
                }
            }
        }
        // A Qualcomm stamp names no chip Robot builds for (the Snapdragon build waits: design Step 4).
        assertNull(LiteRtLmCheck.chipOf("Qualcomm SM8750"))
        assertNull(LiteRtLmCheck.chipOf("Qualcomm Snapdragon_8_Elite"))
    }

    /** Under 8 GB: a 6 GB phone, never an 8 GB one (about 7.5 GB reported); 8 GB or less stays the E4B warning's. */
    @Test fun underEightGbIsItsOwnThreshold() {
        fun ram(n: Long) = Phone("samsung", "SM-A566B", "Samsung", "s5e8855", n)
        assertFalse(ram(0).underEightGb)
        assertTrue(ram(1).underEightGb)
        assertTrue(ram(3_700_000_000L).underEightGb)
        assertTrue(ram(5_600_000_000L).underEightGb)
        assertTrue(ram(6_999_999_999L).underEightGb)
        assertFalse(ram(7_000_000_000L).underEightGb)
        // A Pixel 9a's 7.6 GB: 8 GB or less, but not under 8 GB.
        val nineA = Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_600_000_000L)
        assertTrue(nineA.smallMemory)
        assertFalse(nineA.underEightGb)
        assertFalse(ram(11_500_000_000L).underEightGb || ram(11_500_000_000L).smallMemory)
        assertTrue(ram(8_999_999_999L).smallMemory && !ram(8_999_999_999L).underEightGb)
    }

    /** Settings › Model: the phone with its chip, the NPU on a Snapdragon, and Android's own SoC fields on every phone. */
    @Test fun settingsSaysWhatTheChipIs() {
        val s25 = Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L)
        assertEquals("This phone: Samsung SM-S931W (Snapdragon 8 Elite)", Phones.line(s25))
        assertEquals("Robot has no NPU build for this phone's Snapdragon 8 Elite: Fast is Gemma 4 E2B's general build, on the GPU " +
            "(recommended) or the CPU.", Phones.tpuLine(s25))
        assertEquals("Robot n\u2019a aucune version NPU pour le Snapdragon 8 Elite de ce téléphone\u00A0: Rapide est la version générale de " +
            "Gemma 4 E2B, sur le GPU (recommandé) ou le CPU.", Phones.tpuLineText(s25).render(fr))
        assertEquals("QTI · SM8750", Phones.socFieldsText(s25).en())
        assertEquals("QTI · SM8750", Phones.socFieldsText(s25).render(fr))
        // An Exynos or an unknown chip keeps the line it had.
        val exynos = Phone("samsung", "SM-S921B", "Samsung", "s5e9945")
        assertEquals("This phone: Samsung SM-S921B (Exynos 2400)", Phones.line(exynos))
        assertEquals(Phones.tpuLine(Phone("samsung", "SM-S921B")), Phones.tpuLine(exynos))
        assertTrue(Phones.tpuLine(exynos).startsWith("Robot has no TPU build for this phone:"))
        // A Pixel's own fields, and a phone that gives none.
        assertEquals("Google · Tensor G4", Phones.socFieldsText(Phone("Google", "Pixel 9a", "Google", "Tensor G4")).en())
        assertEquals("not given · not given", Phones.socFieldsText(Phone("samsung", "SM-S931W")).en())
        assertEquals("non indiqué · non indiqué", Phones.socFieldsText(Phone("samsung", "SM-S931W")).render(fr))
        assertEquals("Google · not given", Phones.socFieldsText(Phone("Google", "Pixel 9a", "Google", "")).en())
    }

    /** What a phone puts in its SoC fields is shown and reported as codes only. */
    @Test fun socFieldsAreCleaned() {
        assertEquals("SM8750-AC", Socs.clean(" SM8750-AC "))
        assertEquals("Tensor G4", Socs.clean("Tensor G4"))
        assertEquals("SM8750?x", Socs.clean("SM8750\nx"))
        assertEquals("SM?8750", Socs.clean("SM\u202E8750"))
        assertEquals("QTI??", Socs.clean("QTI\uD83D\uDE00"))
        assertEquals("s5e9945 ?", Socs.clean("s5e9945 é"))
        assertEquals(40, Socs.clean("A".repeat(500)).length)
        assertEquals("", Socs.clean(""))
        assertEquals("", Socs.clean("   "))
        val odd = Phone("samsung", "SM-S931W", "QTI\u202E\n", "SM8750\u0000" + "9".repeat(500))
        val shown = Phones.socFieldsText(odd).en()
        assertTrue(shown, shown.all { it.code in 32..126 || it == '·' } && shown.length < 90)
    }
}
