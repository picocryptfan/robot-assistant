// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.BrainError
import app.heyrobot.core.LogCode

/** 1.3: how a reading ended, for VoiceLog's READ_ALOUD (codes only: nothing it read is kept, point-and-read-plan.md). */
enum class ReadCode(override val id: Int, override val title: String) : LogCode {
    READ(1, "read"), NO_WORDS(2, "no words"), NO_VISION(3, "needs Fast or Smarter"), STOPPED(4, "stopped"), FAILED(5, "failed"), // l10n: not text (voice log codes)
    ;

    companion object {
        /** A reading that ended with [error] (null: it finished), its reply [reading] when it did. */
        fun of(error: BrainError?, stopped: Boolean, reading: ReadAloud.Reading?): ReadCode = when {
            stopped -> STOPPED
            error == BrainError.NO_VISION -> NO_VISION
            error != null || reading == null -> FAILED
            reading.text.isBlank() -> NO_WORDS
            else -> READ
        }
    }
}
