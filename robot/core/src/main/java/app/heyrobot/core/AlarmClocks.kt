// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * Which Clock app routines go by (week-fixes). GrapheneOS Clock tells other apps when an alarm starts and ends, and that
 * path stays as it was. No other Clock is known to: Android has no public signal for it, so for those Robot goes by the
 * alarm's sound starting and stopping, still vouched for by that Clock's own next alarm (AlarmCheck).
 */
object AlarmClocks {
    const val GRAPHENE = "com.android.deskclock"

    /** The Clock in use: the one chosen if it's still installed, then the phone's default, GrapheneOS Clock, the first; null: none. */
    fun effective(chosen: String?, installed: List<String>, phoneDefault: String?): String? =
        listOfNotNull(chosen, phoneDefault, GRAPHENE).firstOrNull { it in installed } ?: installed.firstOrNull()

    /** [clock] tells other apps when an alarm ends. */
    fun tells(clock: String?): Boolean = clock == GRAPHENE

    /** The Clock whose alarms count: with none installed, GrapheneOS Clock's path as it always was. */
    fun watched(clock: String?): String = clock ?: GRAPHENE
}

/** The alarm sound's edges: true when it starts, false when it stops, null when nothing changed. */
class AlarmSoundEdge(private var ringing: Boolean) {
    fun see(now: Boolean): Boolean? {
        if (now == ringing) return null
        ringing = now
        return now
    }
}
