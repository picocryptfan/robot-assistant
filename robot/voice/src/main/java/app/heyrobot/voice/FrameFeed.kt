// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

/**
 * VOICE PROCESS. Frames for [Listener]'s loop in place of the microphone's, for a test build that speaks to Robot without a
 * speaker. [current] is null in every release: no main source sets it (ProcessIsolation's sibling, SpokenBuildTest), so the
 * loop reads the microphone as ever, after one null check.
 */
interface FrameFeed {
    /** Fills [into] with up to [count] samples of 16 kHz mono PCM at real-time pace (it blocks), and returns how many. */
    fun read(into: ShortArray, count: Int): Int

    companion object {
        @Volatile var current: FrameFeed? = null
    }
}
