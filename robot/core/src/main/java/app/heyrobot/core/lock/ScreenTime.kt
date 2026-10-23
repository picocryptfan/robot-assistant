// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

// Pure. Screen time from Android's usage events: One app first counts the time its app is in front
// with the screen on and unlocked; Daily limit counts today's time with the screen on and unlocked. :lock's UsageReader
// turns UsageStatsManager.queryEvents into [UsageEvent]s; only the type, the time, the package and the class are kept,
// and nothing is stored but the totals (state.json's usedMs).

/** One usage event: [type] as UsageEvents.Event numbers it ([ScreenTime.RESUMED] and so on), [t] wall-clock ms. */
data class UsageEvent(val t: Long, val type: Int, val pkg: String? = null, val cls: String? = null)

/** What :lock sees at the end of a count: the screen on, unlocked, the counted app in front. */
data class ScreenNow(val screenOn: Boolean = false, val unlocked: Boolean = false, val appInFront: Boolean = false)

object ScreenTime {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val STOPPED = 23
    const val SHUTDOWN = 26
    const val STARTUP = 27

    private val SCREEN = setOf(SCREEN_ON, SCREEN_OFF, SHUTDOWN, STARTUP)
    private val KEYGUARD = setOf(KEYGUARD_SHOWN, KEYGUARD_HIDDEN, SHUTDOWN, STARTUP)

    /**
     * The time from [from] to [to] with the screen on and unlocked, and (with [pkg]) one of [pkg]'s activities in front.
     * What was true at [from] is read from the first event after it that tells: a screen-off first means the screen was on,
     * a keyguard-hidden first means it was locked, a pause first means the app was in front. Events out of order are
     * sorted; a shutdown ends everything, a startup begins with the screen off and locked. With no event that tells, nothing
     * changed in between, so it was as it is at [to]: [now] (the screen on, unlocked, the app in front, as :lock sees them).
     */
    fun count(events: List<UsageEvent>, from: Long, to: Long, pkg: String? = null, now: ScreenNow = ScreenNow()): Long {
        if (to <= from) return 0
        val ev = events.filter { it.t in from..to }.sortedBy { it.t }
        var screenOn = ev.firstOrNull { it.type in SCREEN }?.type.let { if (it == null) now.screenOn else it == SCREEN_OFF || it == SHUTDOWN }
        var unlocked = ev.firstOrNull { it.type in KEYGUARD }?.type.let { if (it == null) now.unlocked else it == KEYGUARD_SHOWN || it == SHUTDOWN }
        // The app's activities in front, by class (two activities of one app overlap during a switch).
        val front = mutableSetOf<String>()
        if (pkg != null) {
            val firstOfApp = ev.firstOrNull { it.pkg == pkg && it.type in setOf(RESUMED, PAUSED, STOPPED) }
            if (firstOfApp != null && firstOfApp.type != RESUMED) front += firstOfApp.cls.orEmpty()
            else if (firstOfApp == null && now.appInFront && ev.none { it.type == RESUMED && it.pkg != null }) front += ""
        }
        fun counting() = screenOn && unlocked && (pkg == null || front.isNotEmpty())
        var total = 0L
        var since = from
        for (e in ev) {
            val was = counting()
            when (e.type) {
                SCREEN_ON -> screenOn = true
                SCREEN_OFF -> screenOn = false
                KEYGUARD_SHOWN -> unlocked = false
                KEYGUARD_HIDDEN -> unlocked = true
                SHUTDOWN -> { screenOn = false; unlocked = false; front.clear() }
                STARTUP -> { screenOn = false; unlocked = false; front.clear() }
                RESUMED -> if (pkg != null && e.pkg == pkg) front += e.cls.orEmpty() else if (pkg != null && e.pkg != null) front.clear()
                PAUSED, STOPPED -> if (pkg != null && e.pkg == pkg) front -= e.cls.orEmpty()
            }
            if (was) total += e.t - since
            since = e.t
        }
        if (counting()) total += to - since
        return total
    }
}
