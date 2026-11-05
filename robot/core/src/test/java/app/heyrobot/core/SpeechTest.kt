// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.VoiceForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    private fun split(vararg chunks: String): List<String> {
        val s = SentenceSplitter()
        val out = chunks.flatMap { s.feed(it) }.toMutableList()
        s.flush()?.let { out += it }
        return out
    }

    @Test fun sentencesComeOutAsSoonAsTheyAreComplete() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Hello there"))
        assertEquals(listOf("Hello there!"), s.feed("! How"))
        assertEquals(listOf("How are you?"), s.feed(" are you? I'm"))
        // The end of the stream so far may still be "3." of "3.5": wait for the next chunk or the flush.
        assertEquals(emptyList<String>(), s.feed(" fine."))
        assertEquals("I'm fine.", s.flush())
        assertNull(s.flush())
    }

    @Test fun decimalsAndAbbreviationsDoNotSplit() {
        assertEquals(listOf("It costs 3.50 euros, e.g. at the bakery.", "Done."), split("It costs 3.50 euros, e.g. at the bakery. Done."))
        assertEquals(listOf("Ask Dr. Smith today.", "Then rest."), split("Ask Dr. Smith today. Then rest."))
    }

    @Test fun listsAndLinesSplit() {
        assertEquals(listOf("Steps:", "1. Boil water.", "2. Add tea."), split("Steps:\n1. Boil water.\n2. Add tea."))
    }

    @Test fun longRunsAreCutSoSpeechCanStart() {
        val s = SentenceSplitter(maxChars = 50)
        val out = s.feed("one two three four five six seven eight nine ten eleven twelve thirteen")
        assertEquals(1, out.size)
        assertTrue(out[0].length <= 51)
    }

    @Test fun theFirstPieceIsShortSoSpeechStartsFast() {
        // At the first clause break after 5 words (docs/voice-plan.md 4.9), then whole sentences.
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Sure, here's what I"))
        assertEquals(listOf("Sure, here's what I found today,"), s.feed(" found today, and it"))
        assertEquals(emptyList<String>(), s.feed(" looks good, overall"))
        assertEquals(listOf("and it looks good, overall."), s.feed(". Next"))
        // A new reply gets a short first piece again.
        s.reset()
        assertEquals(listOf("One two three four five six seven eight nine ten eleven twelve"),
            s.feed("One two three four five six seven eight nine ten eleven twelve thirteen"))
        assertEquals("thirteen", s.flush())
    }

    /** A streamed reply as the French voice gets it: ReplyVoice's pieces, SpeechText.clean, then the voice's clock rule. */
    private fun spokenFr(vararg chunks: String): List<String> {
        val v = ReplyVoice()
        v.start("r")
        val pieces = chunks.flatMap { v.feed("r", it, speak = true) } + listOfNotNull(v.finish("r", cancelled = false, speak = true))
        return pieces.map { VoiceForm.clock(SpeechText.clean(it), Lang.FR) }
    }

    @Test fun aFrenchReplysTimesReachTheVoiceWhole() {
        // The model's « 12:30 » and « 12 h 30 », split anywhere while they stream: never « douze virgule trente ».
        assertEquals(listOf("Ton rendez-vous chez le dentiste est demain à 12 heures 30.", "Arrive un peu avant."),
            spokenFr("Ton rendez-vous chez le dentiste est demain à 12", ":", "30. Arrive un peu avant."))
        assertEquals(listOf("Ton rendez-vous chez le dentiste est demain à 12 heures 30.", "Arrive un peu avant."),
            spokenFr("Ton rendez-vous chez le dentiste est demain à 12", " h", " 30. Arrive un peu avant."))
        // The short first piece is cut after 12 words: not between « 12 » and « h 30 », nor « h » and « 30 ».
        val long = "Un deux trois quatre cinq six sept huit neuf dix onze à 12 h 30 et ensuite on verra bien ce qui arrive."
        for (cut in long.indices.filter { long[it] == ' ' }) {
            val out = spokenFr(long.substring(0, cut), long.substring(cut))
            assertTrue(out.toString(), out.joinToString(" ").contains("à 12 heures 30 et"))
        }
        // "**Ouvert** de 9:00 à 17:00": the Markdown goes, the times are said.
        assertEquals(listOf("Ouvert de 9 heures à 17 heures."), spokenFr("**Ouvert** de 9:", "00 à 17:00."))
        // A long run with no sentence end isn't cut inside « 12:30 » or « 1,5 ».
        val s = SentenceSplitter(maxChars = 30)
        val out = s.feed("un deux trois quatre cinq six sept 12:30 1,5 huit neuf dix onze douze") + listOfNotNull(s.flush())
        assertTrue(out.toString(), out.none { it.endsWith("12:") || it.endsWith("1,") })
    }

    @Test fun aShortSentenceIsItsOwnFirstPiece() {
        val s = SentenceSplitter()
        assertEquals(listOf("Sure!"), s.feed("Sure! The weather today is sunny, warm and calm, with"))
        // Already spoken: no early cut any more, wait for the sentence.
        assertEquals(emptyList<String>(), s.feed(" a light breeze all afternoon long and into the evening"))
        // Fewer than five words before the comma: no cut.
        val t = SentenceSplitter()
        assertEquals(emptyList<String>(), t.feed("Well, I think "))
        assertEquals(emptyList<String>(), t.feed("3,5 "))
    }

    @Test fun emojiAreNotRead() {
        assertEquals("Great job! It's 20°C.", SpeechText.clean("Great job! 🎉👍🏽 It's 20°C. ✨"))
        assertEquals("Family", SpeechText.clean("Family 👨‍👩‍👧"))
    }

    @Test fun markdownIsRemovedForSpeech() {
        assertEquals("Bold and code", SpeechText.clean("**Bold** and `code`"))
        assertEquals("Title", SpeechText.clean("## Title"))
        assertEquals("first item", SpeechText.clean("- first item"))
        assertEquals("second", SpeechText.clean("2. second"))
        assertEquals("see the docs", SpeechText.clean("see [the docs](https://x.y/z)"))
        assertEquals("go to a link now", SpeechText.clean("go to https://example.com/a?b now"))
        assertEquals("", SpeechText.clean("***"))
    }

    /** 0.1.2 review, minor 8: sherpa logs CJK characters it is given (and a piece it cannot say), whatever its debug flag. */
    @Test fun cjkTextIsNeverHandedToTheVoice() {
        assertEquals("The word means cat.", SpeechText.clean("The word 猫 means cat."))
        assertEquals("", SpeechText.clean("你好世界"))
        assertEquals("", SpeechText.clean("你好。"))
        assertEquals("Robot", SpeechText.clean("こんにちは, Robot"))
        assertEquals("hello", SpeechText.clean("안녕 hello"))
        assertEquals("Ext B test", SpeechText.clean("Ext B \uD840\uDC00 test"))
        assertEquals("In Tokyo, it is sunny.", SpeechText.clean("In Tokyo（東京）, it is sunny."))
        // Fullwidth ASCII is read as ASCII.
        assertEquals("OK 42!", SpeechText.clean("ＯＫ ４２！"))
        // Nothing left to pronounce: nothing to say (sherpa would log the piece whole).
        assertEquals("", SpeechText.clean("?!"))
        assertEquals("", SpeechText.sayable("— …"))
        // French and other Latin text is untouched.
        assertEquals("Réveille-moi à 6h30, s'il te plaît.", SpeechText.clean("Réveille-moi à 6h30, s'il te plaît."))
        assertEquals("It's 20°C.", SpeechText.sayable("It's 20°C."))
        for (c in listOf(0x4E00, 0x9FFF, 0x3400, 0x3042, 0x30A2, 0xAC00, 0xF900, 0x3001)) {
            assertEquals(Integer.toHexString(c), "", SpeechText.sayable(String(Character.toChars(c))))
        }
    }

    // ReplyVoice: what of a streaming reply is spoken (0.1.2 review, majors 2 and 3).

    private val longFirst = "Sure, here is what I found for you today. "

    @Test fun aFinishedReplySpeaksItsLastSentence() {
        val v = ReplyVoice()
        v.start("r1")
        assertEquals(listOf("Sure, here is what I found for you today."), v.feed("r1", longFirst, speak = true))
        assertEquals(emptyList<String>(), v.feed("r1", "It is sunny", speak = true))
        assertEquals("It is sunny", v.finish("r1", cancelled = false, speak = true))
        // Finished: nothing more for it.
        assertNull(v.finish("r1", cancelled = false, speak = true))
        assertEquals(emptyList<String>(), v.feed("r1", "late. ", speak = true))
    }

    @Test fun aCancelledReplyDropsItsCutOffSentence() {
        val v = ReplyVoice()
        v.start("r1")
        v.feed("r1", longFirst, speak = true)
        v.feed("r1", "The forecast for tomor", speak = true)
        assertNull(v.finish("r1", cancelled = true, speak = true))
        // The next reply starts clean, with a short first piece again.
        v.start("r2")
        assertEquals(listOf("Okay."), v.feed("r2", "Okay. ", speak = true))
    }

    @Test fun stopMutesTheRestOfTheStreamingReply() {
        val v = ReplyVoice()
        v.start("r1")
        assertEquals(1, v.feed("r1", longFirst, speak = true).size)
        v.mute()
        assertFalse(v.live("r1"))
        assertEquals(emptyList<String>(), v.feed("r1", "More words here. And more. ", speak = true))
        assertNull(v.say("r1", "Timer set.", speak = true))
        assertNull(v.finish("r1", cancelled = false, speak = true))
        // The next question speaks again.
        v.start("r2")
        assertTrue(v.live("r2"))
        assertEquals("Done.", v.say("r2", "Done.", speak = true))
        assertEquals(listOf("Okay."), v.feed("r2", "Okay. ", speak = true))
    }

    @Test fun onlyTheCurrentReplyAndOnlyWithSpeechOn() {
        val v = ReplyVoice()
        v.start("r2")
        // A late chunk or the end of an older reply says nothing, and does not disturb the current one.
        assertEquals(emptyList<String>(), v.feed("r1", "Old text. ", speak = true))
        assertNull(v.finish("r1", cancelled = false, speak = true))
        assertTrue(v.live("r2"))
        assertEquals(emptyList<String>(), v.feed("r2", "Quiet, please. ", speak = false))
        assertNull(v.say("r2", "Quiet.", speak = false))
        assertNull(v.finish("r2", cancelled = false, speak = false))
        // Muting with no reply under way only clears what was buffered.
        v.mute()
        v.start("r3")
        assertTrue(v.live("r3"))
    }

    /** No C0 or C1 control or format character (Cc, Cf) and no lone surrogate (Cs) is left. */
    private fun assertClean(s: String) {
        val bad = s.filter { val t = Character.getType(it); t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() || t == Character.SURROGATE.toInt() }
        assertEquals(s, "", bad.map { Integer.toHexString(it.code) }.joinToString(" "))
    }

    /**
     * espeak-ng acts on U+0001 whatever its options ("B" then copies the next word into a fixed array with no bound): no
     * control character ever reaches a voice, from a helper's answer (JSON's \u0001), a web page or a message.
     */
    @Test fun controlCharactersNeverReachTheVoice() {
        val word = "y".repeat(200)
        val hostile = listOf(
            "Here you go.\u0001B$word and more",
            "\u0001100S\u0001B,.;:!? The rest",
            "Next\u0085line and\u009Bcsi and\u2028sep\u2029para",
            "Tab\tand\r\nnewline\u0000nul\u001Bescape\u007Fdel\u0080c1",
            "Zero\u200Bwidth soft\u00ADhyphen \uFEFFbom \u202Eright-to-left\u202C \u2066iso\u2069 tag\uDB40\uDC41",
            "Lone \uD800 high and \uDC00 low",
        )
        for (h in hostile) {
            assertClean(SpeechText.sayable(h))
            assertClean(SpeechText.clean(h))
            assertClean(SpeechText.forEngine(h))
        }
        // The words themselves are still said: a control character is a space, a format character nothing. The word after
        // "B" is cut at SpeechText.MAX_WORD (espeak-ng's CVE-2023-49992).
        val cut = "B" + "y".repeat(SpeechText.MAX_WORD - 1)
        assertEquals("Here you go. $cut and more", SpeechText.sayable(hostile[0]))
        assertEquals("100S B,.;:!? The rest", SpeechText.sayable(hostile[1]))
        assertEquals("Next line and csi and sep para", SpeechText.sayable(hostile[2]))
        assertEquals("Zerowidth softhyphen bom right-to-left iso tag", SpeechText.sayable(hostile[4]))
        assertEquals("Here you go. $cut and more", SpeechText.clean(hostile[0]))
        // Only control characters: nothing to say.
        assertEquals("", SpeechText.sayable("\u0001\u0002\u200B\u0085"))
        assertEquals("", SpeechText.clean("\u0001"))
    }

    /**
     * espeak-ng's own crash vectors for CVE-2023-49990 to 49994 (tests/crash_vectors in espeak-ng's fix, commit 58f1e0b6,
     * GPL-3.0-or-later; here byte for byte under resources/espeak-cve, pinned by SHA-256). The espeak-ng Robot ships inside
     * sherpa-onnx 1.13.8 (csukuangfj/espeak-ng ed530aa1) doesn't have that fix, so no trigger may reach it. Text reaches a
     * voice as a Java string, so each vector is read as UTF-8 (bad bytes become U+FFFD) and as Latin-1 (every byte a
     * character, C1 controls included), then goes through each of SpeechText's steps.
     *
     * - 49990 and 49991: U+0001, which espeak reads as the start of an embedded command whatever its options.
     * - 49993: U+0001 "B", the embedded command whose punctuation list is copied into a fixed array with no bound.
     * - 49994: control characters around "[["; its crash is in the synthesizer's wavegen (PeaksToHarmspect), which
     *   Robot never runs. sherpa-onnx 1.13.8 and piper-phonemize f3ff95af call espeak_Initialize, espeak_SetVoiceByName
     *   and espeak_TextToPhonemesWithTerminator (TranslateClause) only; wavegen runs from speech.c's Synthesize alone
     *   (espeak_Synth, espeak_Synth_Mark, espeak_Key, espeak_Char), which neither calls. Checked like the rest anyway.
     */
    @Test fun espeakCrashVectorsLoseTheirTriggers() {
        for ((cve, text) in espeakVectors()) {
            if (cve != "49992" && cve != "49994") assertTrue(cve, text.contains('\u0001'))
            if (cve == "49993") assertTrue(cve, text.contains("\u0001B"))
            for ((step, out) in listOf("forEngine" to SpeechText.forEngine(text), "sayable" to SpeechText.sayable(text), "clean" to SpeechText.clean(text))) {
                assertClean(out)
                assertFalse("CVE-2023-$cve $step", out.contains('\u0001'))
            }
        }
    }

    /**
     * 49992: a word of hundreds of letters. espeak keeps a word in buffers of N_WORD_BYTES (160) bytes, and its RemoveEnding
     * copies a whole word, up to the next ASCII space, into one with no bound. After every step no run without an ASCII
     * space is longer than [SpeechText.MAX_WORD] UTF-16 units, so at most 120 bytes of the (modified) UTF-8 sherpa hands
     * over. Only ' ' counts as a break: whether espeak ends a word at a no-break space is the C library's iswspace's choice.
     */
    @Test fun espeakCrashVectorsLoseTheirLongWords() {
        for ((cve, text) in espeakVectors()) {
            // The trigger is there: controls as spaces, a word still runs past espeak's buffer.
            if (cve == "49992") assertTrue(cve, longestWordBytes(text.replace(Regex("\\p{Cc}"), " ")) > 160)
            for ((step, out) in listOf("forEngine" to SpeechText.forEngine(text), "sayable" to SpeechText.sayable(text), "clean" to SpeechText.clean(text))) {
                assertTrue("CVE-2023-$cve $step: ${longestWord(out)}", longestWord(out) <= SpeechText.MAX_WORD)
                assertTrue("CVE-2023-$cve $step", longestWordBytes(out) < 160)
            }
        }
    }

    /** The longest run of [s] without an ASCII space, in UTF-16 units. */
    private fun longestWord(s: String): Int = s.split(' ').maxOf { it.length }

    /** The same run's length in the modified UTF-8 JNI's GetStringUTFChars gives sherpa (a surrogate half: 3 bytes). */
    private fun longestWordBytes(s: String): Int = s.split(' ').maxOf { w -> w.sumOf { c -> if (c.code in 1..0x7F) 1 else if (c.code < 0x800) 2 else 3 }.toInt() }

    @Test fun aLongWordIsCutForTheEngine() {
        val forty = "a".repeat(SpeechText.MAX_WORD)
        // Robot's own sentences are left as they are.
        val fr = "Ton rendez-vous est à 12\u202Fh\u202F30\u00A0: « Dîner chez Mom » ! Anticonstitutionnellement, oui."
        assertEquals(fr, SpeechText.forEngine(fr))
        assertEquals("$forty b", SpeechText.forEngine("$forty b"))
        // A longer run keeps its first MAX_WORD units, wherever it is; the next word is said.
        assertEquals("x $forty y", SpeechText.forEngine("x ${forty}aaaa y"))
        assertEquals("1".repeat(SpeechText.MAX_WORD), SpeechText.forEngine("1".repeat(5_000)))
        assertEquals("$forty $forty", SpeechText.forEngine("${forty}zz\u0001${forty}zz"))
        // A no-break space doesn't end a word for the count (espeak may not end one there either).
        assertEquals("ab\u00A0" + "c".repeat(SpeechText.MAX_WORD - 3), SpeechText.forEngine("ab\u00A0" + "c".repeat(100)))
        // A pair (an emoji, U+1F600) is never split: one that would cross the limit goes whole.
        val smile = "\uD83D\uDE00"
        assertEquals("a".repeat(SpeechText.MAX_WORD - 1), SpeechText.forEngine("a".repeat(SpeechText.MAX_WORD - 1) + smile + "zz"))
        assertEquals("a".repeat(SpeechText.MAX_WORD - 2) + smile, SpeechText.forEngine("a".repeat(SpeechText.MAX_WORD - 2) + smile + "zz"))
        // sayable joins " ." to the word before; the word stays at MAX_WORD however many follow.
        assertEquals(forty, SpeechText.sayable(forty + " .".repeat(200)))
        assertEquals("Look: $forty", SpeechText.clean("Look: ${forty}${"b".repeat(300)}"))
    }

    /** The five vectors, each as UTF-8 and as Latin-1: ("4999x", text). */
    private fun espeakVectors(): List<Pair<String, String>> {
        val pins = mapOf(
            "49990" to "394393a595687b8e2335acb85ca0d4605404c3e7843e633482761bf14b5cc7ca",
            "49991" to "886bd4336c6bcc6d83d2f9c06cca759a908653a7ffa2d6fed78f825d54371123",
            "49992" to "bd0f6b511fdd38d9dda7ab983859649b6a51a6a17ecd79f423a46401f3fd0558",
            "49993" to "be0cfcaaf472642056678a43293c77fe34b96c169c408cb486c2631d96ade040",
            "49994" to "b39e098ecaeb71326288d388653bc05f793215bc955d1d9286f7208bc76173fe")
        return pins.flatMap { (cve, sha) ->
            val bytes = javaClass.getResourceAsStream("/espeak-cve/cve-2023-$cve.bin")!!.use { it.readBytes() }
            val hex = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(cve, sha, hex)
            listOf(cve to String(bytes, Charsets.UTF_8), cve to String(bytes, Charsets.ISO_8859_1))
        }
    }

    @Test fun forEngineKeepsFrenchAndEverythingElse() {
        // The narrow no-break space of « 12 h 30 » and the no-break space before « : » are spaces, not controls: kept.
        val fr = "Ton rendez-vous est à 12\u202Fh\u202F30\u00A0: « Dîner chez Mom » ! Ça coûte 3,50\u00A0\$."
        assertEquals(fr, SpeechText.forEngine(fr))
        assertEquals("It's 20°C — fine… 7:02 p.m.", SpeechText.forEngine("It's 20°C — fine… 7:02 p.m."))
        assertEquals("Café 😀 ok", SpeechText.forEngine("Café 😀 ok"))
        assertEquals("a b", SpeechText.forEngine("a\u0001b"))
        assertEquals("ab", SpeechText.forEngine("a\u200Db"))
    }

    /** What the voice says of [reply] streamed in 4-character chunks, after [said]; each piece with the chunk count it came after. */
    private fun spoken(said: String, reply: String): List<Pair<Int, String>> {
        val v = ReplyVoice()
        v.start("r1")
        if (ActionOutcome.recallOnly(emptySet(), said)) v.recallOnly("r1")
        val out = mutableListOf<Pair<Int, String>>()
        reply.chunked(4).forEachIndexed { n, c -> v.feed("r1", c, speak = true).forEach { out += n to it } }
        v.finish("r1", cancelled = false, speak = true)?.let { out += -1 to it }
        return out
    }

    // everyday-1.2.2-a bug 7: the screen dropped "I've saved that" from a recall answer, but the voice had read it out.
    @Test fun aRecallAnswerNeverSaysAloudThatItSaved() {
        for ((said, reply) in listOf(
            "what's my doctor's name" to "Your doctor's name is Dr. Anika Patel. I have saved that information for you. Anything else?",
            "What date is my mom's anniversary?" to "Your mom's anniversary is August 20. I've noted that for you.",
            "Qui est mon dentiste ?" to "Ton dentiste s'appelle docteur Martin. Je l'ai enregistré pour toi.",
        )) {
            val heard = spoken(said, reply)
            assertTrue(heard.toString(), heard.none { ActionOutcome.claims(it.second) })
            // The other sentences come out at the same chunk as without the check: no added wait.
            val plain = spoken("tell me a joke", reply).filterNot { ActionOutcome.claims(it.second) }
            assertEquals(plain, heard)
            assertTrue(heard.isNotEmpty())
        }
    }

    @Test fun otherRepliesAreSaidAsBefore() {
        val joke = "Why did the robot cross the road? It was programmed to."
        assertEquals(listOf("Why did the robot cross the road?", "It was programmed to."), spoken("tell me a joke", joke).map { it.second })
        // You asked for it: "save my doctor's name" is not a question, its claim is said.
        assertTrue(spoken("save my doctor's name, Dr. Patel", "I have saved that.").any { ActionOutcome.claims(it.second) })
        // A recall reply that is nothing but the claim is said at its end, as the screen keeps it.
        assertEquals(listOf(-1 to "I have saved that."), spoken("what's my doctor's name", "I have saved that."))
    }
}
