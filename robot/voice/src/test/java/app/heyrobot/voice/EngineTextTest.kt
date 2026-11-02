// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No control character reaches a voice engine. espeak-ng (under Kokoro and Kitten, and perhaps the phone's own voice) acts on
 * U+0001 whatever its options: U+0001 "B" copies the next word into a fixed array with no bound. Robot hands text to a voice
 * engine in exactly two places, sherpa-onnx's OfflineTts and Android's TextToSpeech, and each one's text goes through core
 * SpeechText.forEngine in the call itself.
 */
class EngineTextTest {
    private val root = File("src/main/java")

    /** Our sources in :voice, comments out. */
    private fun ours(): Map<File, String> {
        assertTrue("run from the :voice module directory", root.isDirectory)
        return File(root, "app/heyrobot").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
            .associateWith { f -> f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "") }
    }

    @Test fun everyTextAVoiceEngineGetsPassesForEngine() {
        val code = ours()
        // sherpa-onnx's OfflineTts takes text in generate, generateWithCallback, generateWithConfig and
        // generateWithConfigAndCallback: one call, in SherpaTextToSpeech.generate.
        val sherpa = code.flatMap { (f, c) -> Regex("""\.generate\w*\(([^\n]*)""").findAll(c).map { f.name to it.groupValues[1] }.toList() }
        assertEquals(sherpa.toString(), 1, sherpa.size)
        assertEquals("SherpaTextToSpeech.kt", sherpa.single().first)
        assertTrue(sherpa.single().second, sherpa.single().second.startsWith("SpeechText.forEngine(safe)"))
        // Android's TextToSpeech.speak (the phone's own engine, which may be the eSpeak NG app), the one with a queue mode:
        // one call, in TextToSpeech.kt. Our own TextToSpeech.speak(text) (VoiceOutput, replay) ends there too.
        val android = code.flatMap { (f, c) -> Regex("""\.speak\(([^\n]*QUEUE_[^\n]*)""").findAll(c).map { f.name to it.groupValues[1] }.toList() }
        assertEquals(android.toString(), 1, android.size)
        assertEquals("TextToSpeech.kt", android.single().first)
        assertTrue(android.single().second, android.single().second.startsWith("SpeechText.forEngine(sentence)"))
        // And nothing else in :voice speaks through Android's engine or synthesizes with sherpa's.
        val others = code.filter { (_, c) -> Regex("""\.(synthesizeToFile|playSilentUtterance)\(""").containsMatchIn(c) }.keys.map { it.name }
        assertEquals(emptyList<String>(), others)
        // No other module hands text to a voice engine of its own: they all speak through :voice.
        val elsewhere = listOf("app", "core", "brain", "helpers", "mt").map { File("../$it/src/main/java") }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList() }
            .filter { f -> Regex("""\.speak\([^\n]*QUEUE_|\.generateWith\w*\(|\.synthesizeToFile\(|\bOfflineTts\(""").containsMatchIn(f.readText()) }
            .map { it.path }
        assertEquals(emptyList<String>(), elsewhere)
    }

    /** The queue of the phone's voice checks the cleaned text, so a piece of control characters only is never sent. */
    @Test fun thePhonesVoiceCleansBeforeItQueues() {
        val tts = ours().entries.single { it.key.name == "TextToSpeech.kt" }.value
        val queue = tts.substringAfter("private fun queue(").substringBefore("private fun enqueue(")
        assertTrue(queue, Regex("""val sentence = SpeechText\.forEngine\(said\)\s+if \(sentence\.isBlank\(\)\) return""").containsMatchIn(queue))
        // Sherpa's queue takes only sayable text (which calls forEngine first).
        val sherpa = ours().entries.single { it.key.name == "SherpaTextToSpeech.kt" }.value
        assertTrue(Regex("""val text = SpeechText\.sayable\(sentence\)\s+if \(text\.isEmpty\(\)\) return""").containsMatchIn(sherpa))
    }

    /**
     * espeak-ng's crash vectors for CVE-2023-49990 to 49994 (core's resources/espeak-cve, pinned there; core SpeechTest
     * says what each needs) along each whole path into an engine, as a caller hands them over (SpeechText.clean, as the
     * panel, the messages, routines and the interpreter do) or not:
     * - sherpa's: SherpaTextToSpeech.enqueue's sayable, PieceText's clock forms for each language and speaker, then
     *   generate's forEngine. What sherpa-onnx does next only splits: Kokoro's front end (kokoro-multi-lang-lexicon.cc)
     *   swaps a few punctuation marks, merges whitespace and separates Chinese runs, and piper-phonemize hands espeak one
     *   clause at a time; nothing there joins two words or makes a control character;
     * - the phone's voice: AndroidTextToSpeech's forEngine in queue, and again in enqueue.
     * Neither gets U+0001 (49990, 49991, 49993's "B"), any other control or format character, or a word over
     * SpeechText.MAX_WORD (49992). 49994's crash is in wavegen, which sherpa never runs.
     */
    @Test fun espeakCrashVectorsReachNoEngine() {
        val dir = File("../core/src/test/resources/espeak-cve")
        val files = dir.listFiles { f -> f.name.matches(Regex("cve-2023-4999[0-4]\\.bin")) }!!.sortedBy { it.name }
        assertEquals(5, files.size)
        val bad = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt())
        for (f in files) for (cs in listOf(Charsets.UTF_8, Charsets.ISO_8859_1)) {
            val raw = String(f.readBytes(), cs)
            for (text in listOf(raw, SpeechText.clean(raw))) {
                val sayable = SpeechText.sayable(text)
                // Robot's voice in English and in French, a helper's French voice, another voice in English.
                val pieces = listOf(Triple(null, null, null), Triple(null, null, "fr"), Triple(30, "fr", null), Triple(3, null, "fr"))
                    .map { (speaker, lang, voiceLang) -> SpeechText.forEngine(PieceText.of(sayable, speaker, lang, voiceLang)) }
                val phone = SpeechText.forEngine(SpeechText.forEngine(text))
                for (out in pieces + phone) {
                    val what = "${f.name} as $cs"
                    // codePoints() gives a lone surrogate as itself, a pair as one character.
                    assertFalse(what, out.codePoints().anyMatch { Character.getType(it) in bad })
                    assertTrue("$what: ${out.split(' ').maxOf { it.length }}", out.split(' ').all { it.length <= SpeechText.MAX_WORD })
                }
            }
        }
    }

    /** The piece Kokoro or Kitten gets after PieceText's clock forms is still clean (the call cleans it again anyway). */
    @Test fun aHostilePieceIsCleanAfterPieceText() {
        val hostile = SpeechText.sayable("Sure\u0001B" + "x".repeat(200) + " at 12:30 \u0001100S done\u0085\u009B .")
        for (lang in listOf(null, "fr")) {
            val piece = SpeechText.forEngine(PieceText.of(hostile, null, null, lang))
            assertFalse(piece, piece.any { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() })
            // The word after "B" is cut at SpeechText.MAX_WORD (espeak-ng's CVE-2023-49992); the rest is still said.
            assertTrue(piece, piece.contains(" B" + "x".repeat(SpeechText.MAX_WORD - 1) + " ") && !piece.contains("x".repeat(SpeechText.MAX_WORD)))
            assertTrue(piece, piece.contains("done"))
        }
    }
}
