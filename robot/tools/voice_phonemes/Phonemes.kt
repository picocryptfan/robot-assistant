// SPDX-License-Identifier: GPL-3.0-only
// What Robot's Kokoro voice is given for each line: sherpa-onnx's own debug output (the text after its front end, and
// the token ids espeak-ng's phonemes become), on a computer. Configured as voice's SherpaTextToSpeech.create and
// generation(): Kokoro v1.0 fp32, the model's language "en-us", Heart (speaker 3) for English with no language per
// call, Siwis (speaker 30) with "fr" per call for French. tools/voice_phonemes.sh compiles and runs it; phonemes.py
// reads the log.
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** Args: KOKORO_DIR ESPEAK_DATA_DIR TEXTS.tsv (id, "en" or "fr", text; tab-separated). Each line's marker goes to stderr. */
fun main(a: Array<String>) {
    val (dir, espeak, texts) = a
    val config = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = "$dir/model.onnx", voices = "$dir/voices.bin", tokens = "$dir/tokens.txt", dataDir = espeak, lang = "en-us"),
            numThreads = 2, debug = true, provider = "cpu",
        ),
        maxNumSentences = 1, silenceScale = 0.2f,
    )
    val tts = OfflineTts(null, config)
    for (line in File(texts).readLines(Charsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) continue
        val (id, lang, text) = line.split('\t', limit = 3)
        val fr = lang == "fr"
        System.err.println("@@LINE\t$id\t$lang\t$text")
        System.err.flush()
        tts.generateWithConfig(text, GenerationConfig(sid = if (fr) 30 else 3, speed = 1.0f, extra = if (fr) mapOf("lang" to "fr") else null))
        System.err.println("@@END")
        System.err.flush()
    }
    tts.release()
}
