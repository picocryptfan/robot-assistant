// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.Read
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * robot-to-robot-plan.md §4.3 (job L7): what you ask another Robot, read from words by code, in English and Québec French: a
 * free/busy window (a day, a part of it, a week, one time; never before now, never more than 14 days) and a plan's 1 to 3 times
 * and title. Words it can't place give a question back, never a guess.
 */
class LinkAskTest {
    /** Wednesday 21 Oct 2026, 10:00 in Halifax. */
    private val now = ZonedDateTime.parse("2026-10-21T10:00:00-03:00[America/Halifax]")
    private fun t(s: String) = ZonedDateTime.parse(s).toEpochSecond()

    private fun window(w: String, lang: Lang = Lang.EN): FbAsk = (LinkAsk.window(w, now, lang) as Read.Ok).value
    private fun why(r: Read<*>) = (r as Read.Unreadable).why.en()

    @Test fun windowsInEnglish() {
        assertEquals(FbAsk(t("2026-10-23T17:00:00-03:00"), t("2026-10-23T23:00:00-03:00")), window("Friday evening"))
        assertEquals(FbAsk(t("2026-10-22T00:00:00-03:00"), t("2026-10-23T00:00:00-03:00")), window("tomorrow"))
        assertEquals(FbAsk(t("2026-10-22T06:00:00-03:00"), t("2026-10-22T12:00:00-03:00")), window("tomorrow morning"))
        assertEquals(FbAsk(t("2026-10-21T17:00:00-03:00"), t("2026-10-21T23:00:00-03:00")), window("tonight"))
        assertEquals(FbAsk(t("2026-10-21T12:00:00-03:00"), t("2026-10-21T17:00:00-03:00")), window("this afternoon"))
        // Today: from now, not from midnight.
        assertEquals(FbAsk(t("2026-10-21T10:00:00-03:00"), t("2026-10-22T00:00:00-03:00")), window("today"))
        assertEquals(FbAsk(t("2026-10-26T00:00:00-03:00"), t("2026-11-02T00:00:00-04:00")), window("next week"))
        assertEquals(FbAsk(t("2026-10-24T00:00:00-03:00"), t("2026-10-26T00:00:00-03:00")), window("this weekend"))
        // One time: that hour.
        val one = window("Friday at 7pm")
        assertEquals(t("2026-10-23T19:00:00-03:00"), one.from)
        assertEquals(3_600L, one.to - one.from)
    }

    @Test fun windowsInQuebecFrench() {
        assertEquals(window("Friday evening"), window("vendredi soir", Lang.FR))
        assertEquals(window("tomorrow"), window("demain", Lang.FR))
        assertEquals(window("tonight"), window("ce soir", Lang.FR))
        assertEquals(window("tomorrow morning"), window("demain matin", Lang.FR))
        assertEquals(window("next week"), window("la semaine prochaine", Lang.FR))
    }

    @Test fun windowsNeverGuess() {
        assertEquals(LinkStrings.askWhen().en(), why(LinkAsk.window("", now, Lang.EN)))
        assertEquals(LinkStrings.askWhen().en(), why(LinkAsk.window("whenever it suits", now, Lang.EN)))
        assertEquals(LinkStrings.whenPassed().en(), why(LinkAsk.window("yesterday", now, Lang.EN)))
        assertEquals(LinkStrings.whenPassed().en(), why(LinkAsk.window("this morning", now.withHour(13), Lang.EN)))
        // At most 14 days.
        val far = window("the week after next")
        assertTrue(far.to - far.from <= 14 * 86_400L)
    }

    @Test fun plans() {
        val p = (LinkAsk.plan("dinner Friday at 7pm or Saturday at 6pm", now, Lang.EN, 60) as Read.Ok).value
        assertEquals(EventFields("dinner", listOf(t("2026-10-23T19:00:00-03:00"), t("2026-10-24T18:00:00-03:00")), 60), p)
        val fr = (LinkAsk.plan("un souper vendredi à 19 h ou samedi à 18 h", now, Lang.FR, 90) as Read.Ok).value
        assertEquals(listOf(t("2026-10-23T19:00:00-03:00"), t("2026-10-24T18:00:00-03:00")), fr.times)
        assertEquals("souper", fr.title)
        assertEquals(90L, fr.minutes)
        // A range said sets the length.
        assertEquals(120L, (LinkAsk.plan("hockey Saturday from 7pm to 9pm", now, Lang.EN, 60) as Read.Ok).value.minutes)
        // No title: "Plan".
        assertEquals(LinkStrings.planTitle().en(), (LinkAsk.plan("Friday at 7pm", now, Lang.EN, 60) as Read.Ok).value.title)
    }

    @Test fun plansNeverGuess() {
        assertEquals(LinkStrings.planTimes().en(), why(LinkAsk.plan("dinner on Friday", now, Lang.EN, 60)))
        assertEquals(LinkStrings.planTimes().en(), why(LinkAsk.plan("", now, Lang.EN, 60)))
        assertEquals(LinkStrings.planTooMany().en(), why(LinkAsk.plan("lunch Monday at noon or Tuesday at noon or Wednesday at noon or Thursday at noon", now.plusDays(2), Lang.EN, 60)))
        assertEquals(LinkStrings.whenPassed().en(), why(LinkAsk.plan("coffee today at 8am", now, Lang.EN, 60)))
    }

    @Test fun askKindsAreReadLeniently() {
        assertEquals(LinkAskKind.FREE_BUSY, LinkAskKind.of("free_busy"))
        assertEquals(LinkAskKind.FREE_BUSY, LinkAskKind.of("Availability"))
        assertEquals(LinkAskKind.PLAN, LinkAskKind.of("event"))
        assertEquals(LinkAskKind.CANCEL_PLAN, LinkAskKind.of("cancel"))
        assertEquals(null, LinkAskKind.of("lasagna recipe"))
    }
}
