// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.EventKind
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Today fix (review-today-first, finding 3): History keeps "a private event" in place of each private title a reply said. */
class PanelPrivateEventsTest {
    private fun ev(title: String, kind: EventKind) = CalendarEvent(0, 1, false, title, null, kind = kind)
    private fun turn(id: String, text: String) = ChatTurn(id, TurnRole.ASSISTANT, text, 0L)

    @After fun clean() = PanelPrivateEvents.forget()

    /** fix-today-review-b: a reply that never reaches History (another replaced it) has its titles dropped when the next one ends. */
    @Test fun aReplacedReplysTitlesGoWhenTheNextReplyEnds() {
        PanelPrivateEvents.read("old", listOf(ev("Counsellor", EventKind.PRIVATE)))
        PanelPrivateEvents.read("new", listOf(ev("Dentist", EventKind.PRIVATE)))
        assertEquals("a private event", PanelPrivateEvents.forHistory(turn("new", "Dentist"), Langs.of(Lang.EN)).text)
        assertEquals(0, PanelPrivateEvents.waiting())
        // fix-126-review 6: should it end after all, its titles are still hidden (fail closed).
        assertEquals("a private event", PanelPrivateEvents.forHistory(turn("old", "Counsellor"), Langs.of(Lang.EN)).text)
        assertEquals("Lunch", PanelPrivateEvents.forHistory(turn("other", "Lunch"), Langs.of(Lang.EN)).text)
    }

    @Test fun privateTitlesBecomeAPlaceholderAndPhoneOnesStay() {
        PanelPrivateEvents.read("r1", listOf(ev("Counsellor", EventKind.PRIVATE), ev("Dentist", EventKind.PHONE), ev("  Surprise party.  ", EventKind.PRIVATE)))
        val kept = PanelPrivateEvents.forHistory(turn("r1", "Now: Counsellor, until 10. Next: Dentist at 2. Tomorrow starts with Surprise party at 9."), Langs.of(Lang.EN))
        assertEquals("Now: a private event, until 10. Next: Dentist at 2. Tomorrow starts with a private event at 9.", kept.text)
        // Once only, and only for that reply.
        assertEquals("Counsellor", PanelPrivateEvents.forHistory(turn("r1", "Counsellor"), Langs.of(Lang.EN)).text)
        PanelPrivateEvents.read("r2", listOf(ev("Counsellor", EventKind.PRIVATE)))
        assertEquals("Counsellor", PanelPrivateEvents.forHistory(turn("r3", "Counsellor"), Langs.of(Lang.EN)).text)
        assertEquals("Ensuite : un événement privé à 16 h.", PanelPrivateEvents.forHistory(turn("r2", "Ensuite : Counsellor à 16 h."), Langs.of(Lang.FR)).text)
    }

    @Test fun theLongestTitleGoesFirst() {
        assertEquals("X and X", PanelPrivateEvents.hide("Gym class and Gym", setOf("Gym", "Gym class"), "X"))
        // fix-today-review 5: whole words only.
        assertEquals("Gymnastics, then X.", PanelPrivateEvents.hide("Gymnastics, then Gym.", setOf("Gym"), "X"))
        assertEquals("X (\$5) at 6", PanelPrivateEvents.hide("Dr. Lee (\$5) at 6", setOf("Dr. Lee"), "X"))
    }
}
