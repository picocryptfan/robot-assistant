// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The everyday set, E6 (everyday-set-plan.md §6.3): the one filter in front of the speech library, with hostile text. What
 * comes out never holds a control, format, private-use, unassigned or surrogate character, nothing above U+FFFF, no word
 * longer than 40 characters and no more than 4,000 characters; another app's pieces are each at most 400 characters, hold
 * only Latin, common and inherited characters, and have no tags. The same text twice gives the same result.
 */
class TtsTextTest {
    private fun assertSafe(out: String, what: String) {
        assertTrue("$what: too long", out.length <= TtsText.MAX_INPUT)
        for (c in out) {
            val t = Character.getType(c).toByte()
            assertFalse("$what: U+%04X".format(c.code), t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE ||
                t == Character.UNASSIGNED || t == Character.SURROGATE || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR)
        }
        var run = 0
        for (c in out) {
            run = if (Character.isWhitespace(c) || Character.isSpaceChar(c)) 0 else run + 1
            assertTrue("$what: a word of $run", run <= TtsText.MAX_WORD)
        }
        assertEquals("$what: twice", out, TtsText.native(out))
    }

    private fun assertAppSafe(pieces: List<String>, what: String, tags: Boolean = true) {
        for (p in pieces) {
            assertSafe(p, what)
            assertTrue("$what: a piece of ${p.length}", p.length in 1..TtsText.MAX_PIECE)
            for (c in p) assertTrue("$what: U+%04X's script".format(c.code), Character.UnicodeScript.of(c.code) in
                setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED))
            // No whole tag is left (a lone "<" is text). Not for random text, where "&lt;b&gt;" inside SSML reads back as "<b>".
            if (tags) assertFalse("$what: a tag", Regex("""</?[A-Za-z!?][^<>]{0,500}>""").containsMatchIn(p))
        }
    }

    /** Our audit's F9: U+0001 then "B" makes espeak copy the next word into a 60-entry global array, unbounded. */
    @Test fun espeaksEmbeddedCommandNeverArrives() {
        for (text in listOf("\u0001B" + "!".repeat(500), "Read this \u0001B${"x".repeat(300)} now", "\u0001V" + "voice".repeat(80),
            "[\u0002hh@'loU]", "a\u0001b\u0002c\u001Bd")) {
            val out = TtsText.native(text)
            assertFalse(out, out.any { it.code < 0x20 })
            assertSafe(out, text.take(20))
            assertAppSafe(TtsText.forApp(text), text.take(20))
        }
        assertEquals("Read this B" + "x".repeat(39) + " now", TtsText.native("Read this \u0001B" + "x".repeat(300) + " now"))
    }

    @Test fun everyControlBecomesASpace() {
        val controls = (0x00..0x1F) + 0x7F + (0x80..0x9F)
        for (c in controls) {
            val out = TtsText.native("one${c.toChar()}two")
            assertEquals("U+%04X".format(c), "one two", out)
        }
    }

    @Test fun formatCharactersGoWithoutATrace() {
        for (c in listOf(0x00AD, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202E, 0x2060, 0x2066, 0x2069, 0xFEFF, 0x061C, 0x180E))
            assertEquals("U+%04X".format(c), "password", TtsText.native("pass${c.toChar()}word"))
    }

    @Test fun privateUseNoncharactersAndSurrogatesBecomeSpaces() {
        // espeak's own markup is U+E000 plus a character: never from outside.
        assertEquals("a b", TtsText.native("a\uE03Cb"))
        assertEquals("a b", TtsText.native("a\uFFFEb"))
        assertEquals("a b", TtsText.native("a\uFDD0b"))
        // Lone surrogates, either half, go as the release's SpeechText.forEngine drops them (FA5).
        assertEquals("ab", TtsText.native("a\uD800b"))
        assertEquals("ab", TtsText.native("a\uDC00b"))
        // Above U+FFFF (an emoji, a mathematical letter, a historic script): JNI would hand them over as surrogate halves.
        for (s in listOf("a\uD83D\uDE00b", "a\uD835\uDC00b", "a\uD800\uDF30b")) {
            val out = TtsText.native(s)
            assertFalse(out, out.any { it.isSurrogate() })
            assertSafe(out, s)
        }
    }

    @Test fun nfcJoinsALetterAndItsMark() {
        assertEquals("café", TtsText.native("cafe\u0301"))
        // A thousand marks on one letter are one long word: cut at 40.
        val out = TtsText.native("a" + "\u0301".repeat(3_000) + " end")
        assertSafe(out, "marks")
        assertTrue(out, out.endsWith(" end"))
    }

    @Test fun longWordsAreCutAt40() {
        assertEquals("x".repeat(40), TtsText.native("x".repeat(5_000)))
        assertEquals("start " + "y".repeat(40) + " end", TtsText.native("start " + "y".repeat(41) + " end"))
        // Exactly 40 stays whole.
        assertEquals("z".repeat(40), TtsText.native("z".repeat(40)))
    }

    @Test fun atMost4000CharactersAreRead() {
        val words = "word ".repeat(2_000)
        assertTrue(TtsText.native(words).length <= TtsText.MAX_INPUT)
        val pieces = TtsText.forApp(words)
        assertTrue(pieces.sumOf { it.length + 1 } <= TtsText.MAX_INPUT + 1)
        assertAppSafe(pieces, "words")
    }

    @Test fun anotherAppsLongSentenceIsSplitAtACommaOrASpace() {
        val sentence = (1..120).joinToString(" ") { "word$it" } + "."
        val pieces = TtsText.forApp(sentence)
        assertTrue(pieces.size >= 3)
        assertAppSafe(pieces, "sentence")
        assertEquals(sentence, pieces.joinToString(" "))
        val commas = (1..60).joinToString(", ") { "item $it" }
        val cp = TtsText.forApp(commas)
        assertAppSafe(cp, "commas")
        assertTrue(cp.dropLast(1).all { it.endsWith(",") })
    }

    @Test fun anotherAppsTextSplitsAtLinesAndSentenceEnds() {
        assertEquals(listOf("First line", "Second line"), TtsText.forApp("First line\nSecond line"))
        assertEquals(listOf("One.", "Two!", "Three?", "4 more."), TtsText.forApp("One. Two!  Three? 4 more."))
        assertEquals(listOf("Il est 12 h 30.", "« Bonjour » dit-il."), TtsText.forApp("Il est 12 h 30. « Bonjour » dit-il."))
        // A lower-case word after a full stop isn't a new sentence (an abbreviation, "e.g. this").
        assertEquals(listOf("See e.g. this one."), TtsText.forApp("See e.g. this one."))
    }

    @Test fun tagsAreDroppedAndTheirTextKept() {
        assertEquals(listOf("Hello world & you"), TtsText.forApp("<speak>Hello <break time='1s'/>world &amp; you</speak>"))
        assertEquals(listOf("Bold and plain."), TtsText.forApp("<b>Bold</b> and <i>plain</i>."))
        assertEquals(listOf("Text"), TtsText.forApp("<?xml version=\"1.0\"?><!-- a note --><speak>Text</speak>"))
        // Without a tag, "<" and ">" and entities are what the app wrote.
        assertEquals(listOf("a < b and c > d"), TtsText.forApp("a < b and c > d"))
        assertEquals(listOf("Fish &amp; chips"), TtsText.forApp("Fish &amp; chips"))
        // A "tag" longer than 500 characters is text, read as words, cut at 40 each.
        val long = TtsText.forApp("<a" + " b".repeat(400) + ">")
        assertAppSafe(long, "long tag")
    }

    @Test fun otherAlphabetsAreDroppedFromAnotherAppsText() {
        assertEquals(listOf("Hello world"), TtsText.forApp("Hello Привет 你好 مرحبا שלום world"))
        assertEquals(listOf("Café, crème brûlée, 5 € and 20 °C."), TtsText.forApp("Café, crème brûlée, 5 € and 20 °C."))
        // Nothing left to say: no piece at all.
        assertEquals(emptyList<String>(), TtsText.forApp("你好"))
        assertEquals(emptyList<String>(), TtsText.forApp("\uD83D\uDE00 \uD83D\uDC4D"))
        assertEquals(emptyList<String>(), TtsText.forApp("... --- !!!"))
        assertEquals(emptyList<String>(), TtsText.forApp(""))
    }

    @Test fun robotsOwnSentencesPassUnchanged() {
        for (s in listOf("It's 7:02, and the weather is sunny.", "Il fait 20\u00A0°C ce matin\u202F!", "Your next meeting is at 3 PM with Sam.",
            "That's 18 % of 64.50, so 11.61 dollars."))
            assertEquals(s, TtsText.native(s))
    }

    /** Random text from every plane: the invariants hold, and it stays fast. */
    @Test fun randomTextKeepsEveryRule() {
        val r = Random(20261023)
        val t0 = System.nanoTime()
        repeat(300) { i ->
            val len = r.nextInt(0, 6_000)
            val sb = StringBuilder(len)
            while (sb.length < len) {
                val cp = when (r.nextInt(6)) {
                    0 -> r.nextInt(0, 0x80)
                    1 -> r.nextInt(0x80, 0x800)
                    2 -> r.nextInt(0x800, 0x10000)
                    3 -> r.nextInt(0x10000, 0x110000)
                    4 -> " .,\n<>&;\u0001B".random(r).code
                    else -> ('a'..'z').random(r).code
                }
                sb.appendCodePoint(cp)
            }
            val s = sb.toString()
            assertSafe(TtsText.native(s), "native #$i")
            assertAppSafe(TtsText.forApp(s), "app #$i", tags = false)
        }
        assertTrue("too slow", (System.nanoTime() - t0) / 1_000_000 < 20_000)
    }

    /**
     * espeak-ng's own crash vectors for CVE-2023-49990 to 49994 (tests/crash_vectors in espeak-ng's fix, commit 58f1e0b6,
     * GPL-3.0-or-later; here byte for byte, pinned by SHA-256). The fork Robot ships (csukuangfj/espeak-ng ed530aa1) doesn't
     * have that fix, so these must not reach it as they are. Each one needs what the filter takes out: U+0001's embedded
     * commands (49990, 49991; 49993 is "B"'s punctuation list, our audit's F9), a word of hundreds of letters (49992,
     * RemoveEnding), or control characters around "[[" (49994, whose crash is in the synthesizer's wavegen, which sherpa never
     * runs: it calls espeak_TextToPhonemes alone). An app's text arrives as a Java string, so each is read as UTF-8 (bad bytes
     * become U+FFFD) and as Latin-1 (every byte a character, C1 controls included).
     */
    @Test fun espeakCrashVectorsLoseTheirTriggers() {
        val pins = mapOf(
            "49990" to "394393a595687b8e2335acb85ca0d4605404c3e7843e633482761bf14b5cc7ca",
            "49991" to "886bd4336c6bcc6d83d2f9c06cca759a908653a7ffa2d6fed78f825d54371123",
            "49992" to "bd0f6b511fdd38d9dda7ab983859649b6a51a6a17ecd79f423a46401f3fd0558",
            "49993" to "be0cfcaaf472642056678a43293c77fe34b96c169c408cb486c2631d96ade040",
            "49994" to "b39e098ecaeb71326288d388653bc05f793215bc955d1d9286f7208bc76173fe")
        for ((cve, sha) in pins) {
            val bytes = javaClass.getResourceAsStream("/espeak-cve/cve-2023-$cve.bin")!!.use { it.readBytes() }
            val hex = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(cve, sha, hex)
            for ((cs, text) in listOf("UTF-8" to String(bytes, Charsets.UTF_8), "Latin-1" to String(bytes, Charsets.ISO_8859_1))) {
                val what = "CVE-2023-$cve as $cs"
                assertTrue(what, text.any { it == '\u0001' } || text.length > 300 || cve == "49994")
                val own = TtsText.native(text)
                assertSafe(own, what)
                assertFalse(what, own.contains('\u0001'))
                assertAppSafe(TtsText.forApp(text), what, tags = false)
            }
        }
    }

    /** The worst shapes for a regex or a normalizer at 4,000 characters and more: each well under a second. */
    @Test fun adversarialShapesStayLinear() {
        val shapes = listOf("<a".repeat(5_000), "<" + "a".repeat(10_000), "&amp;".repeat(2_000), ". A".repeat(3_000), "\u0301".repeat(10_000),
            "a\u0301\u0327\u0308".repeat(2_000), ", ".repeat(5_000), "x".repeat(100_000), "\u0001B".repeat(5_000))
        for (s in shapes) {
            val t0 = System.nanoTime()
            assertSafe(TtsText.native(s), s.take(8))
            assertAppSafe(TtsText.forApp(s), s.take(8))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("${s.take(8)}: $ms ms", ms < 2_000)
        }
    }
}
