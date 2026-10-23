// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import app.heyrobot.core.InstallResult
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceFile
import app.heyrobot.core.installVerified
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// VOICE PROCESS. Kokoro and Kitten turn text into phonemes with espeak-ng (statically linked into
// libsherpa-onnx-jni.so), which needs its data as real files at an absolute path. The APK carries the English +
// French subset (docs/voice-plan.md 2.5, tools/fetch_third_party.sh); it is copied once to filesDir and every file
// is checked. If espeak cannot initialise, sherpa-onnx calls exit(-1) and the whole :voice process (wake word
// included) goes, so a voice is never created unless this returned a complete directory.

object EspeakData {
    /** Bump when [FILES] change, so phones copy the new set. */
    private const val VERSION = "1"

    val FILES = listOf(
        VoiceFile("phontab", 55_796, "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6"),
        VoiceFile("phonindex", 39_074, "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4"),
        VoiceFile("phondata", 550_424, "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3"),
        VoiceFile("intonations", 2_040, "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc"),
        VoiceFile("en_dict", 166_944, "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35"),
        VoiceFile("fr_dict", 63_727, "e399ab924c4d10beef1fc310b30ea56e4ddfd8b4b64b8ed978e9c65394d49b2d"),
        VoiceFile("lang/gmw/en", 140, "4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83"),
        VoiceFile("lang/gmw/en-US", 257, "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704"),
        VoiceFile("lang/gmw/en-US-nyc", 271, "e8ee9168376d1f7d9e7dcda7c4644c36c529a8bea816e6380895364db46ea2e3"),
        VoiceFile("lang/gmw/en-029", 335, "faeb8cb201056775f733acbd908c66555fe57bf01cb063920f178f389cbf85d3"),
        VoiceFile("lang/gmw/en-GB-scotland", 295, "1ce4282c1f4385dbaf0035e799b2ab1ee9e9a3ab4829100594a3047727b52353"),
        VoiceFile("lang/gmw/en-GB-x-gbclan", 238, "2040e176f1f7f27bdce81c32d7e2b1662449d54d264afd3c0ed871b8e42b6e46"),
        VoiceFile("lang/gmw/en-GB-x-gbcwmd", 188, "927a5ab891c65b30b4426b151d1623a01024f6b773a38497ba1c90cd77a95747"),
        VoiceFile("lang/gmw/en-GB-x-rp", 249, "d0625af7f58561b1b8cf96fd7f93eee6553bcb3eadb9020ae0757bf96e5115e5"),
        VoiceFile("lang/roa/fr", 79, "95f44834b48c075dad13eace54d2c98ff79b81aa0074dd67eebaf66c2909eef8"),
        VoiceFile("lang/roa/fr-BE", 84, "72496a316e47b997f893500b8953dc01a10b86a2d4238e13c1260f50b47d792a"),
        VoiceFile("lang/roa/fr-CH", 86, "b449b0fd99d9cd93261fe1c6b816448b99b4a10420bee3f301e669568ad0de55"),
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "espeak-ng-data")

    private fun complete(d: File) = FILES.all { f -> File(d, f.name).let { it.isFile && it.length() == f.bytes } }

    /** The absolute path of a complete, checked copy (copying it the first time), or null if that failed. */
    @Synchronized
    fun ensure(ctx: Context): String? {
        val d = dir(ctx)
        val mark = File(d, ".complete")
        if (readOrNull(mark)?.trim() == VERSION && complete(d)) return d.absolutePath
        mark.delete()
        for (f in FILES) {
            val r = try {
                ctx.assets.open("espeak-ng-data/${f.name}").use { installVerified(it, File(d, f.name), f.bytes, f.sha256) } // l10n: not text (a path)
            } catch (_: Exception) { InstallResult.IO_ERROR }
            if (r != InstallResult.OK) {
                RLog.i("espeak_copy", "ok" to false, "result" to r)
                return null
            }
        }
        return try {
            writeAtomic(mark, VERSION)
            RLog.i("espeak_copy", "ok" to true, "files" to FILES.size)
            d.absolutePath
        } catch (t: Throwable) {
            RLog.w("espeak_copy", t)
            null
        }
    }
}
