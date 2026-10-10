package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.KwsSpeller
import app.heyrobot.core.RLog

// Main and voice processes. The keyword model's spelling files from the APK's assets (kws/bpe.model and kws/tokens.txt,
// packaged with the :voice module): pure Kotlin reads them (core KwsSpeller), so the main app's Wake words screen checks
// a phrase without loading sherpa-onnx, and the voice process builds the keyword lines with the same code.

object KwsAssets {
    @Volatile private var cached: KwsSpeller? = null

    /** Null if the files are missing or damaged (logged): the wake phrases then can't be checked or built. */
    fun speller(ctx: Context): KwsSpeller? = cached ?: synchronized(this) {
        cached ?: try {
            val bpe = ctx.assets.open("kws/bpe.model").use { it.readBytes() }
            val tokens = ctx.assets.open("kws/tokens.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            KwsSpeller.load(bpe, tokens).also { cached = it }
        } catch (t: Throwable) {
            RLog.w("kws_speller", t)
            null
        }
    }
}
