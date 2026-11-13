// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.pause

import app.heyrobot.core.lock.ScreenTime.KEYGUARD_HIDDEN
import app.heyrobot.core.lock.ScreenTime.PAUSED
import app.heyrobot.core.lock.ScreenTime.RESUMED
import app.heyrobot.core.lock.ScreenTime.SCREEN_OFF
import app.heyrobot.core.lock.ScreenTime.SCREEN_ON
import app.heyrobot.core.lock.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AppPauseTest {
    private val own = "app.heyrobot"
    private val video = "com.example.video"
    private val chat = "com.example.chat"
    private val home = "com.example.home"
    private val never = NeverPick(own, homes = setOf(home), dialer = "com.example.dialer", emergency = "com.example.sos", keyboard = "com.example.keys")
    private val on = PauseSettings(on = true, apps = setOf(video))
    private val zone = ZoneId.of("America/Halifax")
    private fun at(h: Int, m: Int = 0, day: Int = 7) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    @Test fun offByDefaultAndBrokenFileIsOff() {
        assertFalse(PauseSettings().on)
        assertEquals(PauseSettings(), PauseSettings.decode("{not json"))
        assertEquals(PauseSettings(), PauseSettings.decode(null))
        assertFalse(PauseSettings().wantsEvents(never))
    }

    @Test fun fileRoundTripsAndDropsBadValues() {
        val s = PauseSettings(on = true, apps = setOf(video, chat), waitSeconds = 20)
        assertEquals(s, PauseSettings.decode(s.encode()))
        val bad = PauseSettings.decode("""{"on":true,"apps":["../x","com.example.video"],"wait":2}""")
        assertEquals(setOf(video), bad.apps)
        assertEquals(PauseSettings.DEFAULT_WAIT, bad.waitSeconds)
    }

    @Test fun neverPickList() {
        for (p in listOf(own, home, "com.example.dialer", "com.example.sos", "com.example.keys", "com.android.settings", "com.android.phone",
            "com.google.android.dialer", "com.android.systemui", "com.google.android.apps.nexuslauncher", "com.google.android.apps.safetyhub", null))
            assertTrue("$p", never.blocks(p))
        assertFalse(never.blocks(video))
        // A hand-edited file that picked the dialer or Settings never pauses them, and asks for no events.
        val forced = PauseSettings(on = true, apps = setOf("com.android.settings", "com.example.dialer", home))
        assertFalse(forced.wantsEvents(never))
        val g = PauseGate()
        for (p in forced.apps) assertFalse(p, g.onFront(p, 0, forced, never, busy = false))
    }

    @Test fun pausesOnlyWhenComingFromAnotherApp() {
        val g = PauseGate()
        assertFalse(g.onFront(home, 0, on, never, false))
        assertTrue(g.onFront(video, 1, on, never, false))
        // Its own next screen, the keyboard, the system's windows: no second pause.
        assertFalse(g.onFront(video, 2, on, never, false))
        assertFalse(g.onFront("com.example.keys", 3, on, never, false))
        assertFalse(g.onFront("com.android.systemui", 4, on, never, false))
        assertFalse(g.onFront(chat, 5, on, never, false))
        assertTrue(g.onFront(video, 6, on, never, false))
    }

    @Test fun neverDuringALockACallOrLockedOrWhenOff() {
        assertFalse(PauseGate().onFront(video, 1, on, never, busy = true))
        assertFalse(PauseGate().onFront(video, 1, on.copy(on = false), never, busy = false))
        assertFalse(PauseGate().onFront(chat, 1, on, never, busy = false))
    }

    @Test fun openGrantLastsUntilScreenOffOrTwoMinutesAway() {
        val g = PauseGate()
        assertTrue(g.onFront(video, 0, on, never, false))
        g.grant(video)
        assertFalse(g.onFront(own, 1, on, never, false))
        assertFalse(g.onFront(video, 2, on, never, false))
        // Away a minute (a link opened in another app): back without a pause.
        assertFalse(g.onFront(chat, 10, on, never, false))
        assertFalse(g.onFront(video, 10 + min, on, never, false))
        // Away more than 2 minutes: the pause again.
        assertFalse(g.onFront(chat, 20 * min, on, never, false))
        assertTrue(g.onFront(video, 23 * min, on, never, false))
        g.grant(video)
        assertFalse(g.onFront(video, 24 * min, on, never, false))
        g.screenOff()
        assertTrue(g.onFront(video, 25 * min, on, never, false))
    }

    @Test fun coverTurnOffForThatApp() {
        val off = on.copy(apps = setOf(video, chat)).without(video)
        assertEquals(setOf(chat), off.apps)
        assertFalse(PauseGate().onFront(video, 1, off, never, false))
        assertFalse(on.without(video).wantsEvents(never))
    }

    @Test fun theWaitAndOpenAlwaysWorksAfter() {
        assertEquals(10_000L, PauseWait.remainingMs(1_000, 1_000, 10))
        assertEquals(4_000L, PauseWait.remainingMs(1_000, 7_000, 10))
        assertFalse(PauseWait.canOpen(1_000, 10_999, 10))
        assertTrue(PauseWait.canOpen(1_000, 11_000, 10))
        assertTrue(PauseWait.canOpen(1_000, 99_000_000, 10))
        // A clock set back never makes the wait longer than the wait.
        assertEquals(10_000L, PauseWait.remainingMs(5_000, 0, 10))
        // A hand-edited wait is capped.
        assertTrue(PauseWait.canOpen(0, 30_000, 9_999))
    }

    @Test fun countsOpensAndMinutesToday() {
        val day = PauseCount.dayStart(at(15), zone)
        val ev = listOf(
            UsageEvent(at(9), RESUMED, video), UsageEvent(at(9, 20), RESUMED, home),
            // The cover between doesn't make two opens.
            UsageEvent(at(12), RESUMED, video), UsageEvent(at(12), RESUMED, own), UsageEvent(at(12, 1), RESUMED, video),
            UsageEvent(at(12, 30), PAUSED, video), UsageEvent(at(12, 30), RESUMED, chat),
            UsageEvent(at(14), RESUMED, video), UsageEvent(at(14, 2), SCREEN_OFF),
        )
        val t = PauseCount.app(ev, video, day, at(15), ignore = setOf(own))
        assertEquals(3, t.opens)
        assertEquals(20 + 29 + 2, t.minutes)
        // Opening now, its event not read yet: this one counts.
        assertEquals(4, PauseCount.app(ev, video, day, at(15), setOf(own), openingNow = true).opens)
        assertEquals(3, PauseCount.app(ev + UsageEvent(at(15), RESUMED, video), video, day, at(15), setOf(own), openingNow = true).opens - 1)
    }

    @Test fun dayBoundaryCutsAtLocalMidnight() {
        val now = at(0, 30, day = 8)
        val day = PauseCount.dayStart(now, zone)
        assertEquals(at(0, 0, day = 8), day)
        val ev = listOf(UsageEvent(at(23, 0, day = 7), RESUMED, video), UsageEvent(at(23, 50, day = 7), RESUMED, home),
            UsageEvent(at(23, 55, day = 7), RESUMED, video), UsageEvent(at(0, 10, day = 8), RESUMED, home),
            UsageEvent(at(23, 40, day = 7), SCREEN_ON), UsageEvent(at(0, 20, day = 8), SCREEN_OFF), UsageEvent(at(0, 25, day = 8), SCREEN_ON))
        val t = PauseCount.app(ev, video, day, now, setOf(own))
        // Yesterday's opens don't count; the visit across midnight counts from midnight.
        assertEquals(0, t.opens)
        assertEquals(10, t.minutes)
        assertEquals(1, PauseCount.pickups(ev, day, now).count)
    }

    @Test fun pickupsAndGlancesAsAGuess() {
        val day = PauseCount.dayStart(at(12), zone)
        val ev = listOf(
            UsageEvent(at(8), SCREEN_ON), UsageEvent(at(8, 1), KEYGUARD_HIDDEN), UsageEvent(at(8, 10), SCREEN_OFF),
            UsageEvent(at(9), SCREEN_ON), UsageEvent(at(9), SCREEN_OFF),
            UsageEvent(at(10), SCREEN_ON), UsageEvent(at(10), SCREEN_ON), UsageEvent(at(10, 1), SCREEN_OFF),
            UsageEvent(at(11), SCREEN_ON),
        )
        assertEquals(Pickups(4, 2), PauseCount.pickups(ev, day, at(12)))
        // No unlock in the history at all (no lock screen): no guess.
        assertNull(PauseCount.pickups(ev.filter { it.type != KEYGUARD_HIDDEN }, day, at(12)).glances)
        assertEquals(Pickups(0, null), PauseCount.pickups(emptyList(), day, at(12)))
    }

    @Test fun phoneStatusAnswersPickupsWithCountsOnly() {
        assertEquals(app.heyrobot.core.PhonePart.PICKUPS, app.heyrobot.core.PhonePart.of("pickups"))
        assertEquals(app.heyrobot.core.PhonePart.PICKUPS, app.heyrobot.core.PhonePart.of("how many times did I pick up my phone"))
        assertEquals(app.heyrobot.core.PhonePart.PICKUPS, app.heyrobot.core.PhonePart.of("combien de fois j'ai déverrouillé"))
        assertEquals(app.heyrobot.core.PhonePart.BATTERY, app.heyrobot.core.PhonePart.of("battery"))
        val f = app.heyrobot.core.PhoneFacts(usageAccess = true, pickups = Pickups(12, 5))
        val en = app.heyrobot.core.PhoneStatus.linesText(f, app.heyrobot.core.PhonePart.PICKUPS).en()
        assertEquals("Pickups today: 12 times; my guess: 5 of those were only a look at the lock screen, with no unlock", en)
        assertEquals("Pickups today: 1 time", app.heyrobot.core.PhoneStatus.linesText(f.copy(pickups = Pickups(1, null)), app.heyrobot.core.PhonePart.PICKUPS).en())
        val none = app.heyrobot.core.PhoneStatus.linesText(app.heyrobot.core.PhoneFacts(), app.heyrobot.core.PhonePart.PICKUPS).en()
        assertTrue(none, none.contains("Usage access"))
        // Pickups are never part of "all".
        assertFalse(app.heyrobot.core.PhoneStatus.linesText(f, app.heyrobot.core.PhonePart.ALL).en().contains("Pickups"))
    }
}
