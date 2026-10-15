// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    private val apps = listOf(
        AppEntry("Signal", "org.thoughtcrime.securesms/.Main"),
        AppEntry("Organic Maps", "app.organicmaps/.Splash"),
        AppEntry("Clock", "com.android.deskclock/.Clock"),
        AppEntry("Calculator", "calc/.Main"),
        AppEntry("Calendar", "cal/.Main"),
        AppEntry("Café Finder", "cafe/.Main"),
    )

    @Test fun appNamesMatchLoosely() {
        assertEquals("Signal", AppMatcher.best("signal", apps)?.label)
        assertEquals("Signal", AppMatcher.best("the Signal app", apps)?.label)
        assertEquals("Organic Maps", AppMatcher.best("organic", apps)?.label)
        assertEquals("Organic Maps", AppMatcher.best("maps", apps)?.label)
        assertEquals("Café Finder", AppMatcher.best("cafe finder", apps)?.label)
        assertEquals("Signal", AppMatcher.best("Signal messenger", apps)?.label)
        assertNull(AppMatcher.best("telegram", apps))
        assertNull(AppMatcher.best("", apps))
    }

    @Test fun nearMissesNeverOpenTheWrongApp() {
        val phone = listOf(
            AppEntry("Tiles", "tiles/.Main"), AppEntry("Bitwarden", "bw/.Main"), AppEntry("Cal", "cal/.Main"),
            AppEntry("Calculator", "calc/.Main"), AppEntry("Signal", "sig/.Main"), AppEntry("Keynotes", "kn/.Main"),
        )
        // "Open Files" launched Tiles in 0.1.0 (one letter off): a spelling-only match is no match.
        assertNull(AppMatcher.match("Files", phone))
        assertNull(AppMatcher.match("calculater", phone))
        assertNull(AppMatcher.match("Signel", phone))
        // A longer name never matches a short label it merely starts with ("calendar" is not "Cal").
        assertNull(AppMatcher.match("calendar", phone))
        // "Warden" is inside Bitwarden: a loose match, so the card (showing "Bitwarden") is always shown first.
        val w = AppMatcher.match("Warden", phone)!!
        assertEquals("Bitwarden", w.app.label)
        assertTrue(w.loose)
        assertTrue(AppMatcher.match("notes", phone)!!.loose)
        // Clear matches are not loose.
        assertEquals(MatchKind.EXACT, AppMatcher.match("signal", phone)!!.kind)
        assertEquals(MatchKind.PREFIX, AppMatcher.match("calc", phone)!!.kind)
        assertEquals(false, AppMatcher.match("the signal app", phone)!!.loose)
        // Too short to mean anything.
        assertNull(AppMatcher.match("ca", phone))
    }

    @Test fun shorterLabelWinsTies() {
        val l = listOf(AppEntry("Notes Pro", "a"), AppEntry("Notes", "b"))
        assertEquals("Notes", AppMatcher.best("notes", l)?.label)
    }

    @Test fun editDistance() {
        assertEquals(0, AppMatcher.editDistance("abc", "abc"))
        assertEquals(1, AppMatcher.editDistance("abc", "abd"))
        assertEquals(3, AppMatcher.editDistance("", "abc"))
    }

    @Test fun screenTextIsDedupedAndCut() {
        assertEquals("Inbox\n3 new", ScreenText.build(listOf(" Inbox ", "", "3  new", "Inbox")))
        assertNull(ScreenText.build(listOf("  ", "\n")))
        val big = ScreenText.build((1..1000).map { "line number $it" }, maxChars = 100)!!
        assertTrue(big.length <= 102)
        assertTrue(big.endsWith("…"))
    }

    @Test fun markdownBlocksAndSpans() {
        val b = Markdown.parse("# Title\nSome **bold** and *it* and `code`.\n\n- one\n- two\n1. first\n```\nx = 1\n```")
        assertEquals(listOf(Markdown.BlockKind.HEADING, Markdown.BlockKind.PARAGRAPH, Markdown.BlockKind.BULLET, Markdown.BlockKind.BULLET,
            Markdown.BlockKind.NUMBERED, Markdown.BlockKind.CODE), b.map { it.kind })
        assertEquals(listOf(
            Markdown.Span("Some "), Markdown.Span("bold", bold = true), Markdown.Span(" and "), Markdown.Span("it", italic = true),
            Markdown.Span(" and "), Markdown.Span("code", code = true), Markdown.Span("."),
        ), b[1].spans)
        assertEquals("1.", b[4].marker)
        assertEquals("x = 1", b[5].spans.single().text)
    }

    @Test fun unfinishedMarkdownStaysText() {
        assertEquals(listOf(Markdown.Span("so **bol")), Markdown.inline("so **bol"))
        assertEquals(listOf(Markdown.Span("snake_case_name")), Markdown.inline("snake_case_name"))
    }

    @Test fun bootNoticeOnlyWhenRobotWasTheAssistantAndDidNotStart() {
        assertTrue(BootLogic.shouldNotify(everReady = true, readyThisBoot = false))
        assertTrue(!BootLogic.shouldNotify(everReady = true, readyThisBoot = true))
        assertTrue(!BootLogic.shouldNotify(everReady = false, readyThisBoot = false))
    }
}
