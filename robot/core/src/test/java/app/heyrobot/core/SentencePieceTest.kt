// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure-Kotlin SentencePiece encoder against the reference (sherpa-onnx 1.13.8's text2token with the keyword
 * model's bpe.model, test resource kws/text2token-reference.tsv), and the wake phrases' spelling, keyword lines,
 * sensitivity presets and rules.
 */
class SentencePieceTest {
    private val root: File = File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val assets = File(root, "voice/src/main/assets/kws")

    private val bpe: ByteArray by lazy {
        val f = File(assets, "bpe.model")
        assertTrue("$f missing: run tools/fetch_third_party.sh --install", f.isFile)
        f.readBytes()
    }
    private val tokensTxt: String by lazy { File(assets, "tokens.txt").readText() }
    private val model by lazy { SentencePieceModel.parse(bpe) }
    private val encoder by lazy { SentencePieceEncoder(model) }
    private val speller by lazy { KwsSpeller.load(bpe, tokensTxt) }

    private fun reference(): List<Pair<String, List<String>>> {
        val text = javaClass.classLoader!!.getResourceAsStream("kws/text2token-reference.tsv")!!.readBytes().toString(Charsets.UTF_8)
        return text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { l ->
            val (p, t) = l.split('\t')
            p to t.split(' ')
        }
    }

    @Test fun theModelIsTheUnigramModelItSaysItIs() {
        assertEquals(SentencePieceModel.ModelType.UNIGRAM, model.type)
        assertEquals(500, model.pieces.size)
        assertTrue(model.addDummyPrefix && model.removeExtraWhitespaces && model.escapeWhitespaces)
        // tokens.txt lists the same pieces in the same order (sherpa-onnx's KWS reads ids from it).
        val ids = tokensTxt.lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') }
        assertEquals(model.pieces.map { it.text }, ids)
        assertEquals(SentencePieceModel.PieceType.UNKNOWN, model.pieces[2].type)
        assertEquals("<unk>", model.pieces[2].text)
        // Every letter and the apostrophe are pieces of their own, so every allowed phrase can be spelled.
        for (c in ('A'..'Z') + '\'') assertTrue("$c", model.pieces.any { it.text == c.toString() && it.type == SentencePieceModel.PieceType.NORMAL })
    }

    @Test fun matchesTheReferenceExactly() {
        val ref = reference()
        assertTrue("at least 60 reference phrases, got ${ref.size}", ref.size >= 60)
        for ((phrase, tokens) in ref) assertEquals(phrase, tokens, encoder.encode(phrase))
        assertEquals(listOf("▁HE", "Y", "▁RO", "B", "O", "T"), encoder.encode("HEY ROBOT"))
    }

    @Test fun everyReferenceTokenIsInTokensTxt() {
        val tokens = KwsSpeller.parseTokens(tokensTxt)
        for ((phrase, t) in reference()) {
            assertTrue(phrase, t.all { it in tokens })
            assertEquals(phrase, KwsSpeller.Result.Ok(t), speller.spell(phrase))
        }
    }

    @Test fun spellingIgnoresCaseSpacesAndTypographicApostrophes() {
        assertEquals(speller.spell("HEY ROBOT"), speller.spell("hey robot"))
        assertEquals(speller.spell("HEY ROBOT"), speller.spell("  Hey   Robot "))
        assertEquals(speller.spell("HEY Y'ALL"), speller.spell("hey y’all"))
    }

    @Test fun aLetterTheModelCantSpellIsRefused() {
        // The model knows only A-Z and the apostrophe: anything else comes back as itself (neighbours merged), as the
        // reference's encode(out_type=str) gives it, and is not in tokens.txt.
        assertEquals(listOf("▁JO", "S", "É"), encoder.encode("JOSÉ"))
        assertEquals(listOf("▁", "R", "2", "D", "2"), encoder.encode("R2D2"))
        assertEquals(listOf("▁", "ÉÉ", "▁", "X"), encoder.encode("ÉÉ X"))
        assertEquals(listOf("▁HE", "Y", "▁RO", "B", "O", "T", "!"), encoder.encode("HEY ROBOT!"))
        assertTrue(speller.spell("josé") is KwsSpeller.Result.Refused)
        assertTrue(speller.spell("r2d2") is KwsSpeller.Result.Refused)
        assertEquals(KwsSpeller.Result.Refused("Type a phrase."), speller.spell("   "))
    }

    @Test fun parsingRefusesDamagedFiles() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(0x0A, 0x7F), bpe.copyOf(bpe.size / 3))) {
            assertTrue(runCatching { SentencePieceEncoder(SentencePieceModel.parse(bad)) }.isFailure)
        }
    }

    // ------------------------------------------------------------------ keyword lines

    @Test fun keywordLinesCarryBoostThresholdAndLabel() {
        val phrases = listOf(
            WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM),
            WakePhrase(2, "robot", WakeSensitivity.LOW),
            WakePhrase(3, "jarvis", WakeSensitivity.HIGH),
            WakePhrase(4, "ok buddy", WakeSensitivity.MEDIUM, enabled = false),
        )
        val b = KeywordBuffer.wake(phrases, speller)
        assertEquals(
            "▁HE Y ▁RO B O T :1.5 #0.25 @W1\n▁RO B O T :1.0 #0.35 @W2\n▁JA R VI S :2.0 #0.18 @W3",
            b.text,
        )
        assertEquals(listOf(1, 2, 3), b.ids)
        assertEquals(emptyList<Int>(), b.refused)
        val tokens = KwsSpeller.parseTokens(tokensTxt)
        for (l in b.text.lines()) assertTrue(l, KeywordBuffer.validLine(l, tokens))
        // A phrase the model can't spell is left out and reported, never handed to sherpa-onnx.
        val r = KeywordBuffer.wake(listOf(WakePhrase(7, "josé"), WakePhrase(8, "hey robot")), speller)
        assertEquals(listOf(7), r.refused)
        assertEquals(listOf(8), r.ids)
        assertEquals("", KeywordBuffer.wake(emptyList(), speller).text)
    }

    @Test fun labelsMapBackToPhrases() {
        assertEquals("W12", KeywordBuffer.label(12))
        assertEquals(12, KeywordBuffer.idOf("W12"))
        assertNull(KeywordBuffer.idOf(KeywordBuffer.STOP_LABEL))
        assertNull(KeywordBuffer.idOf("HEY_ROBOT"))
        assertNull(KeywordBuffer.idOf("W"))
        assertNull(KeywordBuffer.idOf("Wx1"))
    }

    @Test fun onlyCleanLinesAreValid() {
        val tokens = KwsSpeller.parseTokens(tokensTxt)
        assertTrue(KeywordBuffer.validLine("▁ST O P :1.5 #0.2 @STOP", tokens))
        assertTrue(KeywordBuffer.validLine("▁ST O P @STOP", tokens))
        assertFalse(KeywordBuffer.validLine("▁ST O P", tokens))
        assertFalse(KeywordBuffer.validLine("▁HEX Y @W1", tokens))
        assertFalse(KeywordBuffer.validLine("▁ST O P :x #0.2 @STOP", tokens))
        assertFalse(KeywordBuffer.validLine("▁ST O P #-1 @STOP", tokens))
        assertFalse(KeywordBuffer.validLine("▁ST O P @STOP extra", tokens))
        assertFalse(KeywordBuffer.validLine("", tokens))
        assertEquals(listOf("▁ST", "O", "P"), KeywordBuffer.tokensOf("▁ST O P @STOP"))
        assertEquals("a\nb", KeywordBuffer.join("a", "", "  ", "b"))
        assertTrue(runCatching { KeywordBuffer.line(listOf("A B"), KwsThresholds.MEDIUM, "W1") }.isFailure)
        assertTrue(runCatching { KeywordBuffer.line(listOf("A"), KwsThresholds.MEDIUM, "W/1") }.isFailure)
    }

    // ------------------------------------------------------------------ presets and rules

    @Test fun sensitivityPresets() {
        // Medium is what phase 0 proved for "hey robot" and 0.2.2 shipped.
        assertEquals(KwsThresholds.Preset(0.25f, 1.5f), KwsThresholds.wake(WakeSensitivity.MEDIUM))
        assertEquals(KwsThresholds.Preset(0.35f, 1.0f), KwsThresholds.wake(WakeSensitivity.LOW))
        assertEquals(KwsThresholds.Preset(0.18f, 2.0f), KwsThresholds.wake(WakeSensitivity.HIGH))
        // Harder to trigger as sensitivity goes down: a higher threshold and a smaller boost.
        val order = listOf(WakeSensitivity.HIGH, WakeSensitivity.MEDIUM, WakeSensitivity.LOW).map(KwsThresholds::wake)
        assertTrue(order.zipWithNext().all { (a, b) -> a.threshold < b.threshold && a.boost > b.boost })
    }

    @Test fun stopThresholdIsLowerOnlyWithEchoCancellation() {
        assertEquals(KwsThresholds.Preset(0.25f, 1.5f), KwsThresholds.stop(echoCancelled = false))
        assertEquals(KwsThresholds.Preset(0.20f, 1.5f), KwsThresholds.stop(echoCancelled = true))
        assertTrue(KwsThresholds.stop(true).threshold < KwsThresholds.stop(false).threshold)
    }

    /** Settings › Voice › "Interrupt sensitivity": Medium is today's thresholds exactly; Low and High move them, within bounds. */
    @Test fun interruptSensitivityMovesTheTalkingThresholds() {
        val m = InterruptSensitivity.MEDIUM
        assertEquals(InterruptSensitivity.MEDIUM, RobotSettings().interrupt)
        for (aec in listOf(false, true)) assertEquals(KwsThresholds.stop(aec), KwsThresholds.stop(aec, m))
        for (w in WakeSensitivity.entries) assertEquals(KwsThresholds.wake(w), KwsThresholds.talkWake(w, m))
        // 0.4.6: Low and High move "stop" as far as a wake phrase's own Low and High are from its Medium.
        assertEquals(KwsThresholds.LOW, KwsThresholds.stop(false, InterruptSensitivity.LOW))
        assertEquals(KwsThresholds.HIGH, KwsThresholds.stop(false, InterruptSensitivity.HIGH))
        assertEquals(KwsThresholds.Preset(0.3f, 1.0f), KwsThresholds.stop(true, InterruptSensitivity.LOW))
        assertEquals(KwsThresholds.Preset(0.13f, 2.0f), KwsThresholds.stop(true, InterruptSensitivity.HIGH))
        assertEquals(KwsThresholds.HIGH, KwsThresholds.talkWake(WakeSensitivity.MEDIUM, InterruptSensitivity.HIGH))
        assertEquals(KwsThresholds.Preset(0.45f, 0.5f), KwsThresholds.talkWake(WakeSensitivity.LOW, InterruptSensitivity.LOW))
        assertEquals(KwsThresholds.Preset(0.11f, 2.5f), KwsThresholds.talkWake(WakeSensitivity.HIGH, InterruptSensitivity.HIGH))
        // The three levels are three different "stop" lines, with echo cancellation or without: none is the same as another.
        for (aec in listOf(false, true)) assertEquals(3, InterruptSensitivity.entries.map { KwsThresholds.stop(aec, it) }.toSet().size)
        // Easier to trigger as the setting goes up, for every preset; never past the bounds.
        for (base in WakeSensitivity.entries.map(KwsThresholds::wake) + KwsThresholds.STOP + KwsThresholds.STOP_AEC) {
            val (lo, mid, hi) = InterruptSensitivity.entries.map { KwsThresholds.shift(base, it) }
            assertTrue("$base", hi.threshold < mid.threshold && mid.threshold < lo.threshold)
            assertTrue("$base", hi.boost > mid.boost && mid.boost > lo.boost)
            for (p in listOf(lo, hi)) assertTrue("$p", p.threshold in KwsThresholds.MIN_THRESHOLD..KwsThresholds.MAX_THRESHOLD && p.boost in KwsThresholds.MIN_BOOST..KwsThresholds.MAX_BOOST)
        }
        val tiny = KwsThresholds.Preset(0.1f, 2.9f)
        assertEquals(KwsThresholds.Preset(KwsThresholds.MIN_THRESHOLD, KwsThresholds.MAX_BOOST), KwsThresholds.shift(tiny, InterruptSensitivity.HIGH))
        // The lines for while Robot talks: the same phrases, the moved presets; Medium writes exactly the idle lines.
        val phrases = listOf(WakePhrase(1, "hey robot"), WakePhrase(2, "robot", WakeSensitivity.LOW))
        assertEquals(KeywordBuffer.wake(phrases, speller).text, KeywordBuffer.wake(phrases, speller) { KwsThresholds.talkWake(it.sensitivity, m) }.text)
        assertEquals("▁HE Y ▁RO B O T :2.0 #0.18 @W1\n▁RO B O T :1.5 #0.28 @W2",
            KeywordBuffer.wake(phrases, speller) { KwsThresholds.talkWake(it.sensitivity, InterruptSensitivity.HIGH) }.text)
        val back = RobotSettings.fromJson(RobotSettings(interrupt = InterruptSensitivity.HIGH).toJson().toString())
        assertEquals(InterruptSensitivity.HIGH, back.interrupt)
        assertEquals(InterruptSensitivity.MEDIUM, RobotSettings.fromJson("""{"schema":5}""").interrupt)
    }

    /**
     * The slider in Settings › Voice: its position when it is let go is the level saved. Until 0.4.6 a tap saved the level
     * the screen had drawn before the tap (Material's slider moves and ends the gesture in one go), so Low and High were
     * never saved by a tap.
     */
    @Test fun theSliderPositionIsTheLevel() {
        assertEquals(InterruptSensitivity.LOW, InterruptSensitivity.at(0f))
        assertEquals(InterruptSensitivity.MEDIUM, InterruptSensitivity.at(1f))
        assertEquals(InterruptSensitivity.HIGH, InterruptSensitivity.at(2f))
        assertEquals(InterruptSensitivity.LOW, InterruptSensitivity.at(0.4f))
        assertEquals(InterruptSensitivity.MEDIUM, InterruptSensitivity.at(0.6f))
        assertEquals(InterruptSensitivity.HIGH, InterruptSensitivity.at(1.7f))
        assertEquals(InterruptSensitivity.LOW, InterruptSensitivity.at(-3f))
        assertEquals(InterruptSensitivity.HIGH, InterruptSensitivity.at(9f))
        assertEquals(InterruptSensitivity.MEDIUM, InterruptSensitivity.at(Float.NaN))
        for (v in InterruptSensitivity.entries) assertEquals(v, InterruptSensitivity.at(v.ordinal.toFloat()))
    }

    @Test fun phraseRules() {
        assertNull(WakePhrases.problem("hey robot"))
        assertNull(WakePhrases.problem("Hey Y'all"))
        assertNull(WakePhrases.problem("ok hey there robot"))
        assertNull(WakePhrases.problem("abcdefghijklmnopqrstuvwx"))
        assertEquals("Type a phrase.", WakePhrases.problem("  "))
        assertEquals("At most 24 characters.", WakePhrases.problem("abcdefghijklmnopqrstuvwxy"))
        assertEquals("Use 1 to 4 words.", WakePhrases.problem("a b c d e"))
        assertEquals("Use only the letters A to Z, spaces and apostrophes.", WakePhrases.problem("hey robot!"))
        assertEquals("Use only the letters A to Z, spaces and apostrophes.", WakePhrases.problem("r2d2"))
        assertEquals("Use only the letters A to Z, spaces and apostrophes.", WakePhrases.problem("josé"))
        assertEquals("Each word needs a letter.", WakePhrases.problem("hey '"))
        // "Stop" is reserved, alone or in a phrase.
        assertEquals("“Stop” is reserved: saying it stops Robot talking.", WakePhrases.problem("Stop"))
        assertEquals("“Stop” is reserved: saying it stops Robot talking.", WakePhrases.problem("hey stop"))
        assertNull(WakePhrases.problem("stopwatch"))
        val list = listOf(WakePhrase(1, "hey robot"))
        assertEquals("That phrase is already in the list.", WakePhrases.problem("HEY  Robot", list))
        val full = (1..WakePhrases.MAX_PHRASES).map { WakePhrase(it, "robot ${"a".repeat(it)}") }
        assertEquals("At most 8 wake phrases.", WakePhrases.problem("jarvis", full))
        assertEquals("hey y'all", WakePhrases.clean("  hey   y’all "))
    }

    @Test fun singleWordsStartAtLow() {
        assertTrue(WakePhrases.singleWord("robot"))
        assertFalse(WakePhrases.singleWord("hey robot"))
        assertEquals(WakeSensitivity.LOW, WakePhrases.defaultSensitivity(" Robot "))
        assertEquals(WakeSensitivity.MEDIUM, WakePhrases.defaultSensitivity("hey computer"))
    }

    @Test fun sanitizeKeepsOnlyValidUniquePhrases() {
        val raw = listOf(WakePhrase(1, " hey  robot "), WakePhrase(1, "jarvis"), WakePhrase(2, "stop"), WakePhrase(0, "computer"),
            WakePhrase(3, "HEY ROBOT"), WakePhrase(4, "ok buddy", WakeSensitivity.HIGH, enabled = false))
        assertEquals(listOf(WakePhrase(1, "hey robot"), WakePhrase(4, "ok buddy", WakeSensitivity.HIGH, enabled = false)), WakePhrases.sanitize(raw))
        assertEquals(4, WakePhrases.nextId(raw.take(5)))
        assertEquals(5, WakePhrases.nextId(raw))
        assertEquals(1, WakePhrases.nextId(emptyList()))
    }

    @Test fun echoWordsAreEachEnabledPhrasesLastWord() {
        val list = listOf(WakePhrase(1, "hey robot"), WakePhrase(2, "Jarvis"), WakePhrase(3, "ok buddy", enabled = false), WakePhrase(4, "robot"))
        assertEquals(listOf("robot", "jarvis"), WakePhrases.echoWords(list))
    }
}
