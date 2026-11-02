// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog

/**
 * 1.1: the question's words once you've stopped speaking. The last pass decodes the whole question again; when it finds
 * nothing though speech was heard, the words the screen showed while you spoke (the last live text) are the question,
 * so a question you watched being written is never answered with "Sorry, I didn't catch that." Re-decoding the audio
 * behind the live text would give the same words (the decode is deterministic), so they're used as they are.
 * Logs the result (numbers only): [VoiceEvent.STT_DONE], [VoiceEvent.STT_USED_SHOWN] or [VoiceEvent.STT_EMPTY].
 */
internal object FinalWords {
    fun pick(final: String, lastShown: String, heardSpeech: Boolean, audioMs: Int, shownAudioMs: Int, decodeMs: Int): String {
        if (final.isNotEmpty()) {
            VoiceLog.add(VoiceEvent.STT_DONE, final.length, audioMs, decodeMs)
            return final
        }
        val shown = lastShown.trim()
        if (heardSpeech && shown.isNotEmpty()) {
            VoiceLog.add(VoiceEvent.STT_USED_SHOWN, shown.length, audioMs, shownAudioMs)
            return shown
        }
        VoiceLog.add(VoiceEvent.STT_EMPTY, audioMs, if (heardSpeech) 1 else 0)
        return ""
    }
}
