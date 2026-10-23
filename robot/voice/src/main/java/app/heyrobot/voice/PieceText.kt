// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.VoiceForm

// Pure. What a Kokoro or Kitten piece is given: its clock times written as espeak must read them in the piece's language
// (core VoiceForm.clock). Every piece passes here, so the model's streamed French (« 12:30 »), a message's (« 12h30 »)
// and Robot's English ("7:02") are said right, and the screen never sees it.

internal object PieceText {
    /**
     * [text] for the voice. The piece's language is the one SherpaTextToSpeech.generation() gives sherpa: [lang] for
     * another speaker (Siwis reading a French reply, a helper's voice), else the chosen voice's [voiceLang] (Siwis's
     * "fr"; null for Heart and Kitten, which read English).
     */
    fun of(text: String, speaker: Int?, lang: String?, voiceLang: String?): String {
        val l = if (speaker == null) voiceLang else lang
        return VoiceForm.clock(text, if (l?.startsWith("fr") == true) Lang.FR else Lang.EN)
    }
}
