// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import java.io.File
import java.io.FileOutputStream

/**
 * Your voice for the spoken tests (the plan's Q7): one phrase's microphone frames, held in memory until you keep them. The one place
 * any build of Robot writes audio to the phone (noNetworkNoAudioFilesNoRawLogs names this file): raw s16le at 16 kHz in the test
 * build's private files, pulled to the computer by tools/spoken/run.py --pull-voice and deleted from the phone.
 */
class SpokenRecorder(private val dir: File, private val maxSamples: Int = 20 * 16_000) {
    private val samples = ShortArray(maxSamples)
    private var size = 0

    /** A frame from the listener (-1..1); a phrase longer than [maxSamples] stops growing. */
    @Synchronized fun frame(x: FloatArray) {
        for (v in x) {
            if (size == maxSamples) return
            samples[size++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    @Synchronized fun discard() { size = 0 }

    @Synchronized fun held(): Int = size

    /** Writes what's held to <[id]>.s16, whole or not at all, and starts again. False with nothing held. */
    @Synchronized fun keep(id: String): Boolean {
        if (size == 0) return false
        dir.mkdirs()
        val tmp = File(dir, ".$id.part")
        val b = ByteArray(size * 2)
        for (i in 0 until size) {
            b[2 * i] = samples[i].toByte()
            b[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        FileOutputStream(tmp).use { it.write(b); it.fd.sync() }
        size = 0
        return tmp.renameTo(File(dir, "$id.s16"))
    }

    companion object {
        /** assets/spoken/phrases.txt's lines, `en|words` or `fr|words`, as (id, language, words): en-001, fr-001… */
        fun phrases(lines: List<String>): List<Phrase> {
            val seen = HashMap<String, Int>()
            return lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { l ->
                val lang = l.substringBefore('|')
                val n = seen.merge(lang, 1, Int::plus)!!
                Phrase("$lang-%03d".format(n), lang, l.substringAfter('|'))
            }
        }
    }

    data class Phrase(val id: String, val lang: String, val words: String)
}
