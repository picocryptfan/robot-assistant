// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.core.Approval
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.BrainModel
import app.heyrobot.core.GateDecision
import app.heyrobot.core.ToolName
import app.heyrobot.core.InstalledFile
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceFile
import app.heyrobot.core.VoiceMark
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

internal val robotRoot: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
internal fun spokenJson() = JSONObject(File(robotRoot, "tools/spoken/spoken.json").readText())

/** This code against the runner's own file, and the parts that need no phone: settings, models, recordings. */
class SpokenContractTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cfg = spokenJson()
    private fun strings(key: String) = cfg.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }

    @Test fun theRunnersFileAndThisCodeAgree() {
        assertEquals(cfg.getInt("protocol"), SpokenProtocol.VERSION)
        assertEquals(strings("harmlessCards").toSet(), SpokenProtocol.HARMLESS_CARDS)
        assertEquals(cfg.getString("results"), "files/" + SpokenProtocol.RESULTS)
        assertEquals(cfg.getString("clipsDir"), "files/" + SpokenProtocol.CLIPS)
        assertEquals(cfg.getString("importDir"), "files/" + SpokenAssumptions.IMPORT)
        assertEquals(cfg.getString("recordingsDir"), "files/" + SpokenProtocol.RECORDINGS)
        assertEquals("app.heyrobot.spoken.SpokenReceiver", cfg.getString("receiver"))
        assertTrue(strings("riskyCards").none { it in SpokenProtocol.HARMLESS_CARDS })
    }

    @Test fun onlyWhatTheRunnerAsksIsAcceptedAsAClip() {
        assertTrue(SpokenProtocol.clipName("say-0123456789abcdef.s16"))
        assertTrue(SpokenProtocol.clipName("no-0123456789abcdef.s16"))
        assertFalse(SpokenProtocol.clipName("say-0123456789ABCDEF.s16"))
        assertFalse(SpokenProtocol.clipName("say-0123456789abcdef.s16\n"))
    }

    @Test fun aSenderThatNamesItselfMustBeTheShell() {
        assertTrue(SpokenAssumptions.senderAllowed(2000))
        assertTrue("am broadcast's sender is hidden (a Pixel 8, ST1)", SpokenAssumptions.senderAllowed(-1))
        for (uid in listOf(0, 1000, 10234)) assertFalse("$uid", SpokenAssumptions.senderAllowed(uid))
    }

    @Test fun theShortListIsTheRunners() {
        assertEquals(cfg.getJSONObject("settings").keys().asSequence().toSet(), SpokenSettings.NAMES)
        val modes = cfg.getJSONObject("settings").getJSONArray("speakReplies")
        for (i in 0 until modes.length()) assertEquals(SpeakReplies.valueOf(modes.getString(i)), SpokenSettings.set(RobotSettings(), "speakReplies", modes.getString(i))!!.speakReplies)
    }

    /** Everything that just happens on your Robot is here or in the list of what stays: a new tool fails until it's decided. */
    @Test fun whatReachesOutStopsAtACardOnTheTestBuild() {
        val on = SpokenSettings.guard(RobotSettings())
        for (t in SpokenSettings.REACHES_OUT) {
            assertEquals(t.id, Approval.ASK, on.approval(t))
            assertEquals(t.id, GateDecision.CONFIRM, ApprovalGate.decide(t, locked = false, settings = on, asked = true))
            assertFalse(t.id, t.id in SpokenProtocol.HARMLESS_CARDS)
            assertTrue(t.id, t.id in strings("riskyCards"))
        }
        // On the phone itself, Robot's own data, or a permission this build is never given.
        val stays = setOf(ToolName.FLASHLIGHT, ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES, ToolName.READ_CALENDAR, ToolName.WEATHER,
            ToolName.PHONE_STATUS, ToolName.REMEMBER, ToolName.FIND_FILES, ToolName.READ_FILE, ToolName.ADD_TO_LIST, ToolName.CALCULATE)
        assertEquals(stays, ToolName.entries.filter { on.approval(it) == Approval.AUTO }.toSet())
    }

    @Test fun noSettingOrResetUndoesIt() {
        val guarded = SpokenSettings.guard(RobotSettings())
        assertEquals(guarded.approvals, SpokenSettings.reset(guarded).approvals)
        assertEquals(guarded.approvals, SpokenSettings.set(guarded, "voiceCards", "true")!!.approvals)
        assertFalse(SpokenSettings.NAMES.any { "approval" in it.lowercase() })
    }

    @Test fun settingsCheckTheirValues() {
        val s = RobotSettings()
        assertEquals(!s.wakeWord, SpokenSettings.set(s, "wakeWord", (!s.wakeWord).toString())!!.wakeWord)
        assertEquals(1.2f, SpokenSettings.set(s, "speechRate", "1.2")!!.speechRate)
        assertEquals("heart", SpokenSettings.set(s, "voice", "heart")!!.voice)
        for ((n, v) in listOf("wakeWord" to "maybe", "speakReplies" to "SOMETIMES", "speechRate" to "fast", "speechRate" to "9", "voice" to "x".repeat(65), "language" to "fr", "voice" to null, null to "x"))
            assertNull("$n=$v", SpokenSettings.set(s, n, v))
    }

    @Test fun resetPutsOnlyTheShortListBack() {
        val d = RobotSettings()
        val s = SpokenSettings.NAMES.fold(d) { a, n ->
            SpokenSettings.set(a, n, when (n) { "speakReplies" -> SpeakReplies.entries.first { it != d.speakReplies }.name; "speechRate" -> "1.5"; "voice" -> "v"; else -> "${!(a.javaClass.getMethod("get" + n.replaceFirstChar(Char::uppercase)).invoke(a) as Boolean)}" })!!
        }
        assertNotEquals(d, s)
        assertEquals(d, SpokenSettings.reset(s))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun theImporterTakesOnlyWhatTheCatalogueNames() {
        val dir = tmp.newFolder("import")
        val bundle = VoiceBundle.entries.first()
        File(dir, bundle.id).mkdirs()
        val known = File(File(dir, bundle.id), bundle.files.first().name).apply { writeText("x") }
        val model = File(dir, BrainModel.entries.first { it.builtIn }.file).apply { writeText("x") }
        File(dir, "evil.bin").writeText("x")
        File(File(dir, bundle.id), "evil.so").writeText("x")
        assertEquals(setOf(known, model), SpokenImporter.plan(dir).map { it.src }.toSet())
    }

    @Test fun aGoodFileIsInstalledAndMarkedAndItsSourceGone() {
        val b = "real weights".toByteArray()
        val src = tmp.newFile("in").apply { writeBytes(b) }
        val to = tmp.newFolder("bundle")
        assertTrue(SpokenImporter.installVoice(src, VoiceFile("m.onnx", b.size.toLong(), sha(b)), to))
        assertFalse(src.exists())
        assertTrue(File(to, "m.onnx").readBytes().contentEquals(b))
        val mark: List<InstalledFile> = VoiceMark.decode(File(to, VoiceMark.FILE).readText())
        assertEquals(listOf("m.onnx"), mark.map { it.name })
        assertEquals(sha(b), mark[0].sha256)
    }

    @Test fun aFileWithTheWrongHashIsRefusedAsADownloadIs() {
        val b = "tampered".toByteArray()
        val src = tmp.newFile("in").apply { writeBytes(b) }
        val to = tmp.newFolder("bundle")
        assertFalse(SpokenImporter.installVoice(src, VoiceFile("m.onnx", b.size.toLong(), sha("real".toByteArray())), to))
        assertFalse("the source is gone either way", src.exists())
        assertFalse("never installed", File(to, "m.onnx").exists())
        assertFalse("never marked", File(to, VoiceMark.FILE).exists())
    }

    @Test fun recordingsAreLittleEndianWholeOrNotAtAll() {
        val dir = File(tmp.root, "rec")
        val r = SpokenRecorder(dir, maxSamples = 4)
        assertFalse("nothing held", r.keep("en-001"))
        r.frame(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))
        assertEquals("a long phrase stops growing", 4, r.held())
        assertTrue(r.keep("en-001"))
        assertEquals(listOf("en-001.s16"), dir.list()!!.toList())
        val b = File(dir, "en-001.s16").readBytes()
        assertEquals(8, b.size)
        assertEquals(32767, (b[3].toInt() shl 8) or (b[2].toInt() and 0xff))
        assertEquals(-32767, (b[5].toInt() shl 8) or (b[4].toInt() and 0xff))
        assertEquals(0, r.held())
        r.frame(floatArrayOf(0.1f))
        r.discard()
        assertFalse(r.keep("en-002"))
    }

    @Test fun thePhraseListHasWhatThePlanAsks() {
        val all = SpokenRecorder.phrases(File(robotRoot, "app/src/spoken/assets/spoken/phrases.txt").readLines())
        val en = all.filter { it.lang == "en" }
        val fr = all.filter { it.lang == "fr" }
        assertTrue("${en.size}", en.size >= 120)
        assertTrue("${fr.size}", fr.size >= 60)
        assertEquals(all.size, en.size + fr.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals("en-001", en[0].id)
        assertEquals("fr-001", fr[0].id)
        assertTrue(all.all { it.words.isNotBlank() })
        for (p in listOf(en, fr)) assertTrue(p.any { it.words == "hey robot" })
        for (w in listOf("yes", "no", "cancel")) assertTrue(w, en.any { it.words == w })
        for (w in listOf("oui", "non", "annule")) assertTrue(w, fr.any { it.words == w })
    }
}
