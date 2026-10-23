// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 1.2 (job 1, chip identity): every decision Robot makes from a phone's Build fields, written out and compared with a
 * frozen record (test resource `phones/decisions.txt`, first written on develop/1.1's code at 69ad5cc2, before the chip map). The
 * record is the proof that 1.2's chip map changed nothing on a Pixel, nor on a phone whose chip Robot doesn't know: which
 * Fast it gets, the models listed, where each runs, the GPU, the TPU, the speeds and their sources, :brain's refusals, the
 * memory warnings, and on those phones what Settings › Model says about the phone and its TPU, in English and French.
 *
 * Phones whose chip 1.2 names (Snapdragon, Exynos, MediaTek) are in the record too, every decision but the two display
 * lines that name their chip ([display] false). Never re-record this file to make a change pass: a difference here is a
 * change in behaviour, and needs a reason in the commit that makes it, and a line here.
 *
 * Changes to the record:
 * - 447b592b (1.2, the chip map): one line. A phone that calls itself a Google "Pixel 9a" but reports SOC_MANUFACTURER
 *   "QTI" and SOC_MODEL "SM8650" was a Tensor G4 by its name; the SoC fields now win, so it is a Snapdragon 8 Gen 3 and its
 *   GPU estimate's source is the unknown GPU's ("No figure for this phone's GPU") instead of the Tensor G4 Mali's. No real
 *   Pixel reports a Qualcomm chip; every Pixel line and every unknown chip's line is unchanged.
 */
class PhoneDecisionsTest {
    private val fr = Langs.of(Lang.FR)

    /** Pixels by name alone, and with the Build.SOC_MODEL their factory images set; phones Robot doesn't know. */
    private val shown: List<Phone> = Phones.PIXELS.map { Phone("Google", it.first, totalRam = 7_600_000_000L) } + listOf(
        Phone("Google", "Pixel 6", "Google", "Tensor", 7_500_000_000L),
        Phone("Google", "Pixel 6a", "Google", "gs101", 5_700_000_000L),
        Phone("Google", "Pixel 7", "Google", "GS201", 7_600_000_000L),
        Phone("Google", "Pixel 8", "Google", "Tensor G3", 7_600_000_000L),
        Phone("Google", "Pixel 8 Pro", "Google", "zuma", 11_500_000_000L),
        Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_600_000_000L),
        Phone("Google", "Pixel 9 Pro", "Google", "zumapro", 15_500_000_000L),
        Phone("Google", "Pixel 10a", "Google", "Tensor G4", 7_600_000_000L),
        Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 15_500_000_000L),
        Phone("Google", "Pixel 10", "Google", "Tensor G4", 11_500_000_000L),
        Phone("Google", "Pixel 11", "Google", "Tensor G6", 11_500_000_000L),
        Phone("Google", "Pixel 11 Pro Fold", "Google", "Tensor G6", 15_500_000_000L),
        Phone("Google", "Pixel 99", "Google", "Tensor G9", 11_500_000_000L),
        Phone("Google", "Pixel 9a", "", "", 0),
        Phone("", "", "", "", 0),
        Phone("Fairphone", "FP5", "", "", 7_600_000_000L),
        Phone("Fairphone", "FP5", "QTI", "QCM6490", 7_600_000_000L),
        Phone("OnePlus", "CPH2581", "Unisoc", "T820", 7_600_000_000L),
        Phone("samsung", "SM-S931W", "", "", 11_500_000_000L),
        Phone("samsung", "SM-S931W", "samsung", "SM-S931W", 11_500_000_000L),
        Phone("samsung", "SM-S931W", "QTI", "SM-S931W", 11_500_000_000L),
    )

    /** Phones whose chip 1.2 names: every decision as before, without the two lines that now name the chip. */
    private val named: List<Phone> = listOf(
        Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L),
        Phone("samsung", "SM-S931U", "QTI", "SM8750-AC", 11_500_000_000L),
        Phone("samsung", "SM-S938B", "Qualcomm", "sm8750", 11_500_000_000L),
        Phone("samsung", "SM-S948B", "Qualcomm", "SM8850", 11_500_000_000L),
        Phone("samsung", "Pixel 9a", "Qualcomm", "SM8850", 7_600_000_000L),
        Phone("Google", "Pixel 9a", "QTI", "SM8650", 7_600_000_000L),
        Phone("samsung", "SM-S921W", "QTI", "SM8650", 7_600_000_000L),
        Phone("samsung", "SM-S911W", "QTI", "SM8550", 7_600_000_000L),
        Phone("samsung", "SM-S901W", "QTI", "SM8450", 7_600_000_000L),
        Phone("motorola", "moto g", "QTI", "SM6375", 3_700_000_000L),
        Phone("samsung", "SM-S921B", "Samsung", "s5e9945", 7_600_000_000L),
        Phone("samsung", "SM-A566B", "Samsung", "s5e8855", 5_600_000_000L),
        Phone("Xiaomi", "24129PN74G", "Mediatek", "MT6991", 11_500_000_000L),
        Phone("Xiaomi", "23129RN51X", "Mediatek", "MT6769", 3_700_000_000L),
    )

    /** Long texts once each, at the end of the record ("#3" in a phone's lines), so the record stays short enough to read. */
    private val texts = LinkedHashMap<String, Int>()
    private fun ref(s: String): String = "#" + texts.getOrPut(s) { texts.size + 1 }

    private fun describe(p: Phone, display: Boolean): String = buildString {
        append("== ").append(listOf(p.manufacturer, p.model, p.socManufacturer, p.socModel, p.totalRam.toString()).joinToString(" | ")).append('\n')
        if (display) {
            append("chip ").append(p.chip.name).append(" · title “").append(p.chip.title).append("” · tensor ").append(p.chip.tensor).append('\n')
            append("phone en: ").append(Phones.phoneText(p).en()).append('\n')
            append("phone fr: ").append(Phones.phoneText(p).render(fr)).append('\n')
            append("tpu line en: ").append(Phones.tpuLineText(p).en()).append('\n')
            append("tpu line fr: ").append(Phones.tpuLineText(p).render(fr)).append('\n')
        }
        append("tpu ").append(p.tpu).append(" · gpu offered ").append(ModelCatalogue.gpuOffered(p)).append(" · small memory ").append(p.smallMemory).append('\n')
        append("series of chip: ").append(if (display) Phones.seriesOf(p.chip) else "-").append('\n')
        for (tpuFailed in listOf(false, true)) {
            append("tpuFailed ").append(tpuFailed).append(": fast ").append(ModelCatalogue.fast(p, tpuFailed))
                .append(" · fallback ").append(ModelCatalogue.fallback(p, tpuFailed))
                .append(" · models ").append(ModelCatalogue.models(p, tpuFailed)).append('\n')
        }
        for (m in BrainModel.entries) {
            // Its title and what it resolves to, without and with a TPU failure; where it runs with the GPU on and fine, on
            // and failed, off and fine, off and failed; the memory warning.
            append(m).append(": ").append(listOf(false, true).joinToString(", ") { f ->
                ModelCatalogue.titleText(m, p, f).let { "${it.en()} / ${it.render(fr)}" } + " -> " + ModelCatalogue.resolve(m, p, f)
            })
            append(" · runs ").append(listOf(true, false).flatMap { on -> listOf(false, true).map { failed -> ModelCatalogue.backend(m, p, on, failed) } }.joinToString(" "))
            append(" · memory ").append(ModelCatalogue.memoryWarningText(m, p)?.en()?.let { ref(it) } ?: "none").append('\n')
            for (b in ModelBackend.entries) {
                val e = Speeds.of(m, b, p)
                append("  ").append(b).append(": speed ")
                if (e == null) append("none") else {
                    append(if (e.measured) "measured " else "estimate ")
                    append(listOf(e.textPhrase().en(), e.sourceText.en(), e.textPhrase().render(fr), e.sourceText.render(fr)).joinToString(" ") { ref(it) })
                }
                if (m.builtIn) {
                    val fp = "${m.bytes}|1"
                    val refusal = LoadCheck.refuseText(m, b, fp, "${m.sha256}|$fp", null, maxTokens = m.contextTokens, phone = p)
                    append(" · ").append(refusal?.let { "refused " + ref(it.en()) } ?: "loads")
                }
                append('\n')
            }
        }
        // Your own file: a complete TPU build stamped for each Tensor, and one for a Qualcomm chip, on this phone.
        for (stamp in listOf("Google Tensor_G5", "Google Tensor_G6", "Qualcomm SM8750", "Qualcomm SM8450")) {
            val r = LiteRtLmReport(readable = true, decoder = true, aux = true, dispatchOp = true, stamp = stamp, modelType = "gemma4")
            append("own file stamped “").append(stamp).append("”: tensorBuild ").append(r.tensorBuild).append(" · runs on this TPU ")
                .append(r.runsOnTpuOf(p)).append('\n')
        }
    }

    private fun record(): String = buildString {
        append("# Robot's decisions from a phone's Build fields (PhoneDecisionsTest). Frozen: never re-record to make a change pass.\n")
        for (p in shown) append(describe(p, display = true))
        append("\n# Chips 1.2 names: every decision but the two lines that name the chip.\n")
        for (p in named) append(describe(p, display = false))
        append("\n# The texts.\n")
        for ((t, i) in texts) append('#').append(i).append(' ').append(t).append('\n')
    }

    @Test fun nothingChangesOnPixelsNorOnPhonesRobotDoesntKnow() {
        texts.clear()
        val actual = record()
        val expected = javaClass.getResourceAsStream("/phones/decisions.txt")?.bufferedReader()?.readText()
        if (expected == null) {
            val out = File("build/phones-decisions.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            fail("No frozen record yet: wrote ${out.absolutePath}")
        }
        if (expected != actual) {
            val e = expected!!.lines()
            val a = actual.lines()
            val i = (0 until minOf(e.size, a.size)).firstOrNull { e[it] != a[it] } ?: minOf(e.size, a.size)
            File("build/phones-decisions.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            assertEquals("first difference at line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
            assertEquals(expected, actual)
        }
    }
}
