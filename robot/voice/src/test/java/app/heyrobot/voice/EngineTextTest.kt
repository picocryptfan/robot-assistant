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
        assertTrue(sherpa.single().second, sherpa.single().second.startsWith("SpeechText.forEngine(text)"))
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

    /** The piece Kokoro or Kitten gets after PieceText's clock forms is still clean (the call cleans it again anyway). */
    @Test fun aHostilePieceIsCleanAfterPieceText() {
        val hostile = SpeechText.sayable("Sure\u0001B" + "x".repeat(200) + " at 12:30 \u0001100S done\u0085\u009B .")
        for (lang in listOf(null, "fr")) {
            val piece = SpeechText.forEngine(PieceText.of(hostile, null, null, lang))
            assertFalse(piece, piece.any { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() })
            assertTrue(piece, piece.contains("x".repeat(200)) && piece.contains("done"))
        }
    }
}
