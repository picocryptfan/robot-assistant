// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Today H5: the spoken brief and "how's my day" follow the page's order and parts; Today off, both are as before. */
class BriefPageTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 3, 8, 0, 0, 0, zone)
    private fun ms(h: Int) = now.withHour(h).toInstant().toEpochMilli()
    private val standup = CalendarEvent(ms(9), ms(10), false, "Standup", null)
    private val lists = listOf(ListGlance("a", "Groceries", 3), ListGlance("b", "To-do", 0), ListGlance("c", "Hardware", 1), ListGlance("d", "Gifts", 2))
    private val note = NoteGlance("n1", "Parking", listOf("level 3, row F"), true)
    private val mine = listOf(TodayPart.NOTE, TodayPart.LISTS, TodayPart.CALENDAR)

    private fun input(lang: Lang = Lang.EN, page: List<TodayPart>? = mine, items: List<BriefItem> = BriefItem.DEFAULTS) =
        BriefInput(now, lang, items, events = listOf(standup), reminders = listOf("call the garage"), page = page, lists = lists, note = note)

    private fun said(b: Brief) = b.spoken.mapNotNull { it.item }.distinct()

    @Test fun todayOffTheBriefIsAsBefore() {
        val off = input(page = null)
        assertEquals(BriefComposer.compose(off.copy(lists = emptyList(), note = null)), BriefComposer.compose(off))
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.CALENDAR, BriefItem.REMINDERS, BriefItem.CLOSING), said(BriefComposer.compose(off)))
    }

    @Test fun todayOnTheBriefFollowsThePagesOrder() {
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.NOTE, BriefItem.LISTS, BriefItem.REMINDERS, BriefItem.CALENDAR, BriefItem.CLOSING),
            said(BriefComposer.compose(input())))
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.CALENDAR, BriefItem.LISTS, BriefItem.REMINDERS, BriefItem.NOTE, BriefItem.CLOSING),
            said(BriefComposer.compose(input(page = TodayPart.DEFAULT))))
    }

    @Test fun theListsAndTheNoteAsThePageShowsThemInEnglishAndFrench() {
        val en = BriefComposer.compose(input()).text
        assertTrue(en, "Groceries, 3 left." in en && "Hardware, 1 left." in en)
        // A list with nothing left isn't on the page, and the page shows two.
        assertFalse(en, "To-do" in en || "Gifts" in en)
        assertTrue(en, "Your note: Parking, level 3, row F." in en)
        val fr = BriefComposer.compose(input(Lang.FR)).text
        assertTrue(fr, "Groceries, encore 3." in fr && "Ta note" in fr && "Parking, level 3, row F." in fr)
        assertEquals("Your note: Parking.", BriefComposer.shown(BriefComposer.noteText(note.copy(lines = emptyList()))!!, Lang.EN))
    }

    @Test fun aPartYouTurnedOffIsntRead() {
        val b = BriefComposer.compose(input(items = BriefItem.DEFAULTS - BriefItem.CALENDAR - BriefItem.REMINDERS))
        assertFalse(BriefItem.CALENDAR in said(b) || BriefItem.REMINDERS in said(b))
        assertFalse(b.text, "Standup" in b.text || "garage" in b.text)
    }

    // H7: your feeds' three headlines, after the page's parts; older than a day, it says which day they're from.
    private val news = listOf(app.heyrobot.core.news.NewsHeadline("Storm reaches the coast.", "https://n.example.org/1", "N", ms(6)),
        app.heyrobot.core.news.NewsHeadline("Ferry back on time", "https://n.example.org/2", "N", ms(5)))

    @Test fun theNewsIsReadAfterThePagesPartsAndSaysWhenItsOld() {
        val b = BriefComposer.compose(input().copy(news = news, newsAt = ms(6)))
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.NOTE, BriefItem.LISTS, BriefItem.REMINDERS, BriefItem.CALENDAR, BriefItem.NEWS, BriefItem.CLOSING), said(b))
        assertTrue(b.text, "In the news: Storm reaches the coast. Ferry back on time." in b.text)
        val old = BriefComposer.compose(input().copy(news = news, newsAt = now.minusDays(2).toInstant().toEpochMilli()))
        assertTrue(old.text, "In the news from Sunday: Storm" in old.text)
        // fix-today-review 1: yesterday's, though under a day old (a missed morning): it still says the day.
        val late = BriefComposer.compose(input().copy(news = news, newsAt = now.minusHours(22).toInstant().toEpochMilli()))
        assertTrue(late.text, "In the news from Monday: Storm" in late.text)
        val fr = BriefComposer.compose(input(Lang.FR).copy(news = news, newsAt = ms(6)))
        assertTrue(fr.text, "Dans les nouvelles" in fr.text && "Ferry back on time." in fr.text)
        // No feeds, or Today off: nothing about the news.
        assertFalse(BriefItem.NEWS in said(BriefComposer.compose(input())))
        assertFalse(BriefItem.NEWS in said(BriefComposer.compose(input(page = null).copy(news = news))))
        // today-h7-fix 5: locked, the news follows the level (home-design §6.2): said at "Everything", else held for the unlock.
        for (level in BriefLock.entries) {
            val l = BriefComposer.compose(input().copy(news = news, newsAt = ms(6)), locked = true, level = level)
            assertEquals("$level", level == BriefLock.EVERYTHING, "Storm" in l.text)
            assertEquals("$level", level != BriefLock.EVERYTHING, BriefItem.NEWS in l.held.mapNotNull { it.item })
        }
    }

    @Test fun lockedTheListsAndTheNoteAreNeitherSaidNorHeld() {
        for (level in BriefLock.entries) {
            val b = BriefComposer.compose(input(), locked = true, level = level)
            val all = (b.spoken + b.held).mapNotNull { it.item }
            assertFalse("$level", BriefItem.LISTS in all || BriefItem.NOTE in all)
            assertFalse("$level", "Groceries" in b.text + b.heldText || "Parking" in b.text + b.heldText)
        }
    }

    @Test fun howsMyDayFollowsThePageWithTodayOn() {
        val p = Today.picture(DayInput(now, listOf(standup), listOf("call the garage"), 0, emptyList()))
        val on = BriefComposer.shown(Today.line(p, Lang.EN, mine, note), Lang.EN)
        val a = on.indexOf("Your note"); val b = on.indexOf("garage"); val c = on.indexOf("Standup")
        assertTrue(on, a in 0 until b && b < c)
        val off = BriefComposer.shown(Today.line(p, Lang.EN), Lang.EN)
        assertEquals(off, BriefComposer.shown(Today.line(p, Lang.EN, null, note), Lang.EN))
        assertFalse(off, "Your note" in off)
        assertTrue(off, off.indexOf("Standup") < off.indexOf("garage"))
        val fr = BriefComposer.shown(Today.line(p, Lang.FR, mine, note), Lang.FR)
        assertTrue(fr, fr.indexOf("Ta note") in 0 until fr.indexOf("Standup"))
    }
}
