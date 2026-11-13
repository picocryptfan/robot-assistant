// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.3, notifications held until a time (docs/plans/notify-hold-plan.md): the pure parts, on fixed lists and a fixed clock. */
class HoldTest {
    private val zone = ZoneId.of("America/Halifax")
    /** Thursday 10:20 in the morning. */
    private val now = ZonedDateTime.of(2026, 11, 5, 10, 20, 0, 0, zone)
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)
    private val labels = mapOf("com.shop" to "Shop", "com.news" to "News Today", "com.mail" to "Mail")
    private val on = HoldSettings(true, listOf("com.shop", "com.news"))

    private fun ask(t: String): HoldAsk? = MessagesRouter.route(t).let { (it as? MessageCommand.Hold)?.ask }
    private fun end(t: String): LocalTime? = ask(t)?.let { HoldTime.end(it.until, now).toLocalTime() }

    @Test fun settingsAreOffWithNoAppUntilYouTurnThemOn() {
        assertEquals(HoldSettings(), HoldSettings.parse(null))
        assertEquals(HoldSettings(), HoldSettings.parse("{not json"))
        assertEquals(on, HoldSettings.parse(HoldSettings.write(on)))
        assertFalse(HoldSettings(false, listOf("com.shop")).picked("com.shop"))
    }

    @Test fun theEnglishPhrasesGiveTheAppsAndTheEnd() {
        assertEquals(HoldAsk(listOf("shopping", "news"), HoldUntil.At(12, 0)), ask("Hold my shopping and news apps till noon."))
        assertEquals(HoldAsk(emptyList(), HoldUntil.At(12, 0)), ask("hold my notifications until noon"))
        assertEquals(HoldAsk(emptyList(), HoldUntil.For(60)), ask("pause my apps for an hour"))
        assertEquals(HoldAsk(emptyList(), HoldUntil.For(120)), ask("I'm away from my phone for 2 hours"))
        assertEquals(LocalTime.of(15, 0), end("I'm off my phone till three"))
        assertEquals(LocalTime.of(11, 0), end("hold my notifications until eleven"))
        assertEquals(LocalTime.of(15, 30), end("snooze my news notifications until 3:30 pm"))
        assertEquals(LocalTime.of(18, 0), end("Robot, hold my apps till 6"))
        assertEquals(listOf("news"), ask("snooze my news notifications until 3:30 p.m.")?.apps)
    }

    @Test fun theFrenchPhrasesGiveTheAppsAndTheEnd() {
        assertEquals(HoldAsk(emptyList(), HoldUntil.At(12, 0)), ask("Mets mes notifications en attente jusqu'à midi."))
        assertEquals(listOf("magasinage", "nouvelles"), ask("mets mes applis de magasinage et de nouvelles en attente jusqu'à 18 h")?.apps)
        assertEquals(LocalTime.of(18, 0), end("mets mes applis de magasinage et de nouvelles en attente jusqu'à 18 h"))
        assertEquals(HoldAsk(emptyList(), HoldUntil.For(60)), ask("suspends mes notifications pendant une heure"))
        assertEquals(LocalTime.of(15, 0), end("je suis loin de mon téléphone jusqu'à trois heures"))
        assertEquals(LocalTime.of(14, 30), end("mets mes notifs en pause jusqu’à 14 h 30"))
    }

    @Test fun misfiresStayWithTheModel() {
        for (t in listOf("hold on", "Hold on a second", "hold the door", "put a hold on my library book", "hold my calls till noon",
            "hold my beer", "pause the music", "snooze my alarm for 10 minutes", "hold my notifications", "hold my apps till noon and six",
            "I'm off work till three", "attends", "mets la musique en pause", "retiens ton souffle", "retiens mes applis de magasinage et de nouvelles jusqu'à 18 h", "je suis loin de chez moi jusqu'à midi"))
            assertNull(t, MessagesRouter.route(t) as? MessageCommand.Hold)
    }

    @Test fun aBareHourIsTheSoonerOfItsTwoAndAHoldEndsWithinTwelveHours() {
        assertEquals(now.withHour(21).withMinute(0), HoldTime.end(HoldUntil.At(9, 0), now))
        assertEquals(now.plusDays(1).withHour(9).withMinute(0), HoldTime.end(HoldUntil.At(9, 0, false), now))
        assertEquals(HoldCode.TOO_LONG, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.At(9, 0, false)), on, true, false, now) { labels[it] ?: "" }.first.code)
        assertEquals(HoldCode.TOO_LONG, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.For(13 * 60)), on, true, false, now) { labels[it] ?: "" }.first.code)
    }

    @Test fun namesSaidMatchOnlyPickedApps() {
        val p = { a: List<String> -> HoldPlan.plan(HoldAsk(a, HoldUntil.At(12, 0)), on, true, false, now) { labels[it] ?: "" } }
        assertEquals(setOf("com.shop", "com.news"), p(listOf("shopping", "news")).second)
        assertEquals(listOf("Shop", "News Today"), p(emptyList()).first.apps)
        assertEquals(LocalTime.of(12, 0), p(emptyList()).first.end)
        // Mail is on the phone but not picked: never held, and Robot says so.
        val mail = p(listOf("shop", "mail"))
        assertEquals(HoldCode.UNKNOWN, mail.first.code)
        assertEquals(emptySet<String>(), mail.second)
        assertEquals(HoldCode.OFF, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.At(12, 0)), on.copy(on = false), true, false, now) { "" }.first.code)
        assertEquals(HoldCode.NO_APPS, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.At(12, 0)), HoldSettings(true), true, false, now) { "" }.first.code)
        assertEquals(HoldCode.NO_ACCESS, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.At(12, 0)), on, false, false, now) { "" }.first.code)
        assertEquals(HoldCode.SOMEONE_ELSE, HoldPlan.plan(HoldAsk(emptyList(), HoldUntil.At(12, 0)), on, true, true, now) { "" }.first.code)
    }

    /** review-after13-a 3: a silent notification (low importance) stays in the shade and isn't counted, as What did I miss does. */
    @Test fun aSilentNotificationIsNeverHeld() {
        val holding = setOf("com.shop")
        val plain = HoldCandidate("com.shop", "promo", title = "50% off")
        assertTrue(HoldRule.mayHold(plain, HoldSettings(true, holding.toList()), holding, "app.heyrobot") { false })
        assertFalse(HoldRule.mayHold(plain.copy(alerting = false), HoldSettings(true, holding.toList()), holding, "app.heyrobot") { false })
    }

    @Test fun aNeverHeldKindOrAnAppNotPickedIsNeverSnoozed() {
        val holding = setOf("com.shop", "com.news", "com.mail", "app.heyrobot")
        val wide = HoldSettings(true, holding.toList())
        val plain = HoldCandidate("com.shop", "promo", title = "50% off")
        val knows = { t: String -> t == "Alex" }
        assertTrue(HoldRule.mayHold(plain, wide, holding, "app.heyrobot", knows))
        val never = HoldRule.NEVER.map { plain.copy(category = it) } + listOf(
            plain.copy(ongoing = true), plain.copy(conversation = true), plain.copy(media = true), plain.copy(fullScreen = true),
            plain.copy(ownProfile = false), plain.copy(title = "Alex"), plain.copy(pkg = "app.heyrobot"))
        for (c in never) assertFalse(c.toString(), HoldRule.mayHold(c, wide, holding, "app.heyrobot", knows))
        for (c in listOf("msg", "email", "call", "missed_call", "alarm", "reminder", "event", "stopwatch", "transport")) assertTrue(c, c in HoldRule.NEVER)
        // Not picked, picked with the switch off, or picked but not in the hold running.
        assertFalse(HoldRule.mayHold(plain.copy(pkg = "com.other"), wide, holding + "com.other", "app.heyrobot", knows))
        assertFalse(HoldRule.mayHold(plain, wide.copy(on = false), holding, "app.heyrobot", knows))
        assertFalse(HoldRule.mayHold(plain, wide, setOf("com.news"), "app.heyrobot", knows))
    }

    @Test fun theBookCountsByAppNameUntilTheEndAndKeepsNothingElse() {
        val b = HoldBook()
        val t0 = now.toInstant().toEpochMilli()
        b.start(setOf("com.shop", "com.news"), t0 + 60_000)
        assertEquals(60_000L, b.held("com.shop", "Shop", t0))
        assertEquals(30_000L, b.held("com.shop", "Shop", t0 + 30_000))
        assertEquals(0L, b.held("com.mail", "Mail", t0))
        assertEquals(0L, b.held("com.news", "News", t0 + 60_000))
        assertEquals(listOf(HeldCount("Shop", 2)), b.take())
        assertEquals(emptySet<String>(), b.holding(t0))
    }

    @Test fun aHoldCarriesOnAfterARestartWithItsCounts() {
        val t0 = now.toInstant().toEpochMilli()
        val before = HoldBook()
        before.start(setOf("com.shop", "com.news"), t0 + 60_000)
        before.held("com.shop", "Shop", t0)
        // The process stops: only the file is left, and a new book reads it back.
        val saved = HoldSaved.parse(before.saved()!!.write())!!
        assertEquals(HoldResume.CARRY_ON, HoldResumeRule.at(saved, t0 + 20_000))
        val after = HoldBook()
        after.restore(saved)
        assertEquals(setOf("com.shop", "com.news"), after.holding(t0 + 20_000))
        assertEquals(40_000L, after.held("com.news", "News", t0 + 20_000))
        assertEquals(t0 + 60_000, after.endsAt())
        assertEquals("Your hold is over. 2 notifications came in: 1 in Shop and 1 in News.", HoldLine.over(after.take(), false).render(en))
        assertNull(after.saved())
    }

    @Test fun anEndAlreadyPastAtStartUpEndsWithTheUsualLine() {
        val t0 = now.toInstant().toEpochMilli()
        val saved = HoldSaved(setOf("com.shop"), t0, listOf(HeldCount("Shop", 3)))
        assertEquals(HoldResume.END, HoldResumeRule.at(saved, t0))
        assertEquals(HoldResume.END, HoldResumeRule.at(saved, t0 + 3_600_000))
        val b = HoldBook()
        b.restore(HoldSaved.parse(saved.write())!!)
        assertEquals(emptySet<String>(), b.holding(t0 + 1))
        assertEquals("Your hold is over. 3 notifications came in: 3 in Shop.", HoldLine.over(b.take(), false).render(en))
        // A clock set back past the 12 hours: ended now, never held longer. No file, a damaged or a newer one: nothing.
        assertEquals(HoldResume.END, HoldResumeRule.at(HoldSaved(setOf("com.shop"), t0 + HoldTime.MAX_MS + 3_600_000, emptyList()), t0))
        assertEquals(HoldResume.NONE, HoldResumeRule.at(HoldSaved.parse(null), t0))
        assertNull(HoldSaved.parse("{"))
        assertNull(HoldSaved.parse(saved.write().replace("\"v\":1", "\"v\":2")))
    }

    @Test fun aNewHoldStartsItsCountsAfresh() {
        val t0 = now.toInstant().toEpochMilli()
        val b = HoldBook()
        b.start(setOf("com.shop"), t0 + 60_000)
        b.held("com.shop", "Shop", t0)
        b.start(setOf("com.news"), t0 + 120_000)
        assertEquals(emptyList<HeldCount>(), b.saved()!!.counts)
    }

    @Test fun theOneLineInBothLanguages() {
        val list = listOf(HeldCount("Shop", 3), HeldCount("News", 5))
        assertEquals("Your hold is over. 8 notifications came in: 3 in Shop and 5 in News.", HoldLine.over(list, false).render(en))
        assertEquals("Ton attente est finie. 8 notifications sont arrivées : 3 dans Shop et 5 dans News.", HoldLine.over(list, false).render(fr))
        assertEquals("Your hold is over. 8 notifications came in.", HoldLine.over(list, true).render(en))
        assertEquals("Your hold is over. Nothing came in.", HoldLine.over(emptyList(), false).render(en))
        assertEquals("Ton attente est finie. 1 notification est arrivée : 1 dans Shop.", HoldLine.over(listOf(HeldCount("Shop", 1)), false).render(fr))
    }

    @Test fun theReplyNamesTheAppsAndTheTimeAndCrossesTheWire() {
        val r = HoldResult(HoldCode.HELD, listOf("Shop", "News"), LocalTime.of(12, 0))
        assertEquals(r, HoldPlan.fromJson(HoldPlan.toJson(r)))
        val a = HoldAsk(listOf("news"), HoldUntil.At(3, 30, true))
        assertEquals(a, HoldPlan.askOf(HoldPlan.askJson(a)))
        assertEquals(HoldAsk(emptyList(), HoldUntil.For(90)), HoldPlan.askOf(HoldPlan.askJson(HoldAsk(emptyList(), HoldUntil.For(90)))))
        assertTrue(HoldLine.reply(r).render(en), HoldLine.reply(r).render(en).startsWith("Holding Shop and News until "))
        assertTrue(HoldLine.reply(r).render(fr).startsWith("Je mets Shop et News en attente jusqu"))
    }
}
