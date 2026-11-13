// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.pause

import app.heyrobot.core.lock.GateFacts
import app.heyrobot.core.lock.LockGate
import app.heyrobot.core.lock.LockLimits
import app.heyrobot.core.lock.ScreenTime
import app.heyrobot.core.lock.UsageEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

// Pure. App pause (idea 2, docs/plans/app-pause-plan.md): you pick the apps that eat your day; when one comes to the front,
// :lock's accessibility service shows the pause cover, which says how often you opened it today and for how long, and
// lets Open work after a short wait. Off by default. Its file (files/robot/app_pause.json) holds only on/off, the picked
// packages and the wait. The counts are never stored: each is worked out from Android's own usage history when the cover
// shows or you ask, and dropped once shown or said. Nothing leaves the phone.

/** App pause's settings: [on], the picked [apps] (packages), the [waitSeconds] before Open works. */
data class PauseSettings(val on: Boolean = false, val apps: Set<String> = emptySet(), val waitSeconds: Int = DEFAULT_WAIT) {
    fun encode(): String = JSONObject().put("v", 1).put("on", on).put("apps", JSONArray(apps.sorted())).put("wait", waitSeconds).toString()

    /** Window events are wanted: on, with at least one app that may be paused. */
    fun wantsEvents(never: NeverPick): Boolean = on && apps.any { !never.blocks(it) }

    fun without(pkg: String) = copy(apps = apps - pkg)

    companion object {
        const val DEFAULT_WAIT = 10
        val WAITS = listOf(5, 10, 20, 30)
        const val MAX_APPS = 30

        /** Anything unreadable is off: a broken file never pauses anything. */
        fun decode(text: String?): PauseSettings = try {
            if (text.isNullOrBlank()) PauseSettings() else JSONObject(text).let { o ->
                val a = o.optJSONArray("apps") ?: JSONArray()
                val apps = (0 until a.length()).mapNotNull { a.optString(it).takeIf(LockLimits::validPackage) }.take(MAX_APPS).toSet()
                PauseSettings(o.optBoolean("on", false), apps, o.optInt("wait", DEFAULT_WAIT).takeIf { it in WAITS } ?: DEFAULT_WAIT)
            }
        } catch (_: Exception) {
            PauseSettings()
        }
    }
}

/**
 * What can never be picked or paused, so the pause can never stand between you and help or a way out: Robot itself
 * ([own]), every Home app ([homes]), the default and known dialers, the phone and its emergency dialer, emergency and
 * safety apps, Android's Settings and the rest of [LockGate.NEVER_OPEN], the system's own windows and the keyboard.
 */
data class NeverPick(val own: String, val homes: Set<String> = emptySet(), val dialer: String? = null,
    val emergency: String? = null, val keyboard: String? = null) {
    fun blocks(pkg: String?): Boolean = pkg == null || pkg == own || pkg in homes || pkg == dialer || pkg == emergency ||
        pkg == keyboard || pkg in FIXED

    companion object {
        /** By name, whatever the phone says: the phone and telecom, emergency and safety, cell broadcast alerts, Home apps. */
        val FIXED: Set<String> = setOf(
            "com.android.phone", "com.android.server.telecom", "com.android.emergency", "com.google.android.apps.safetyhub",
            "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        ) + LockGate.KNOWN_DIALERS + LockGate.NEVER_OPEN + LockGate.SYSTEM + GateFacts.PERMISSION_CONTROLLERS

        fun of(f: GateFacts, homes: Set<String>) = NeverPick(f.ownPackage, homes, f.dialer, f.emergencyDialer, f.keyboard)
    }
}

/**
 * Main thread of :lock: whether the window that just came to the front gets the pause. An app is paused when it comes
 * to the front from another app, not on each of its own screens. After Open on the cover, that app has a grant: no pause
 * until the screen goes off or it has been away [GRACE_MS]. Robot's own windows, the system's and the keyboard don't
 * count as leaving it.
 */
class PauseGate {
    private var front: String? = null
    private var granted: String? = null
    private var leftAt: Long? = null

    /**
     * [busy]: a lock is on, the phone is locked, a call is on or car mode is on (whatever started it): the pause stays out of
     * the way (the lock decides during a lock). True: show the pause cover for [pkg].
     */
    fun onFront(pkg: String?, at: Long, s: PauseSettings, never: NeverPick, busy: Boolean): Boolean {
        if (pkg == null || pkg == never.own || pkg in LockGate.SYSTEM || pkg == never.keyboard) return false
        val g = granted
        if (g != null && pkg != g) leftAt = leftAt ?: at
        if (g != null && pkg == g) {
            val away = leftAt
            leftAt = null
            if (away == null || at - away <= GRACE_MS) { front = pkg; return false }
            granted = null
        }
        val fresh = pkg != front
        front = pkg
        return fresh && !busy && s.on && pkg in s.apps && !never.blocks(pkg)
    }

    /** Open on the cover: [pkg] comes to the front without the pause. */
    fun grant(pkg: String) { granted = pkg; leftAt = null; front = null }

    /** The screen went off: grants end, and the next app to the front is fresh. */
    fun screenOff() { granted = null; leftAt = null; front = null }

    companion object {
        const val GRACE_MS = 2 * 60_000L
    }
}

/** The wait on the cover: Open works [waitSeconds] after the cover showed, never later. */
object PauseWait {
    fun remainingMs(shownAt: Long, now: Long, waitSeconds: Int): Long =
        (shownAt + waitSeconds.coerceIn(0, PauseSettings.WAITS.max()) * 1000L - now.coerceAtLeast(shownAt)).coerceAtLeast(0)

    fun canOpen(shownAt: Long, now: Long, waitSeconds: Int) = remainingMs(shownAt, now, waitSeconds) == 0L
}

/** Today so far for one app: how often it was opened, and for how many whole minutes it was in front. */
data class AppToday(val opens: Int, val minutes: Int)

/** Today's pickups: the screen came on [count] times; [glances] of those it went off again with no unlock (a guess). */
data class Pickups(val count: Int, val glances: Int?)

object PauseCount {
    /** Local midnight today, in [zone]: the day's counts start there. */
    fun dayStart(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** How far back to read so a visit that began before midnight is cut at midnight, not lost. */
    const val LOOK_BACK_MS = 3 * 3_600_000L

    /**
     * [pkg] today: an open is it coming to the front from another app ([ignore]: Robot's own windows and the system's, so
     * the cover between doesn't make two). [now]: when it is opening now and its event isn't in [events] yet, it counts.
     */
    fun app(events: List<UsageEvent>, pkg: String, from: Long, now: Long, ignore: Set<String>, openingNow: Boolean = false): AppToday {
        var opens = 0
        var last: String? = null
        var since: Long? = null
        var ms = 0L
        fun stop(t: Long) { since?.let { ms += (minOf(t, now) - maxOf(it, from)).coerceAtLeast(0) }; since = null }
        for (e in events.sortedBy { it.t }) {
            if (e.t > now) break
            when (e.type) {
                ScreenTime.RESUMED -> {
                    val p = e.pkg ?: continue
                    if (p in ignore) { if (p != pkg) stop(e.t); continue }
                    if (p == pkg) { if (last != pkg && e.t >= from) opens++; if (since == null) since = e.t } else stop(e.t)
                    last = p
                }
                ScreenTime.PAUSED, ScreenTime.STOPPED -> if (e.pkg == pkg) stop(e.t)
                // Picked up again later, the same app in front is a new open.
                ScreenTime.SCREEN_OFF, ScreenTime.SHUTDOWN -> { stop(e.t); last = null }
            }
        }
        stop(now)
        if (openingNow && last != pkg) opens++
        return AppToday(opens, (ms / 60_000L).toInt())
    }

    /**
     * Today's pickups: each time the screen came on since [from]. A glance is a pickup where the screen went off again
     * with no unlock: a guess (a notification read on the lock screen, or the time), said as one. Null when the events show
     * no unlock at all (no lock screen, or none recorded), as every pickup would look like a glance.
     */
    fun pickups(events: List<UsageEvent>, from: Long, now: Long): Pickups {
        var count = 0
        var glances = 0
        var on = false
        var unlocked = false
        var anyUnlock = false
        for (e in events.sortedBy { it.t }) {
            if (e.t > now) break
            when (e.type) {
                ScreenTime.SCREEN_ON -> if (e.t >= from && !on) { count++; on = true; unlocked = false }
                ScreenTime.KEYGUARD_HIDDEN -> { anyUnlock = true; unlocked = true }
                ScreenTime.SCREEN_OFF, ScreenTime.SHUTDOWN -> { if (on && !unlocked) glances++; on = false }
            }
        }
        return Pickups(count, if (anyUnlock) glances else null)
    }
}
