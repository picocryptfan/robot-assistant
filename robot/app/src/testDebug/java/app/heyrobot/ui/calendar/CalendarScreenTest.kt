// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.calendar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.calendar.CalendarAgenda
import app.heyrobot.core.calendar.PrivateEvent
import app.heyrobot.core.i18n.Lang
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Calendar tab (home-design H3) at 360 dp, at twice the text size, in French, light and dark: nothing cut, every tap
 * target 48 dp with words TalkBack reads; a private event and one Robot added open the form, anyone else's opens in the
 * calendar app; leave-by is the next event's place and one tap to the maps app; History is behind the clock icon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CalendarScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 3, 9, 15, 0, 0, zone)
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val dentist = CalendarEvent(ms(14, 30), ms(15, 30), false, "Dentist", "214 Main St", id = 11, calendar = "Family")
    private val added = CalendarEvent(ms(17), ms(18), false, "Pick up the car", null, id = 12, calendar = "Family")
    private val private = PrivateEvent("a".repeat(32), "Surprise party planning", ms(19), ms(20), false, null, 1)

    private fun view(readable: Boolean = true, state: PrivateState = PrivateState.OK): CalendarView {
        val events = CalendarAgenda.join(if (readable) listOf(dentist, added) else null, listOf(private))
        return CalendarView(now, now.toLocalDate(), CalendarAgenda.days(events, now.toLocalDate(), zone), readable, state, setOf(12L),
            events.filter { it.end > now.toInstant().toEpochMilli() }.minByOrNull { it.begin })
    }

    private val taps = mutableListOf<String>()
    private val actions = CalendarActions(history = { taps += "history" }, allow = { taps += "allow" }, jump = { taps += "jump:$it" },
        openInApp = { taps += "app:${it.title}" }, addInApp = { taps += "addapp" }, directions = { taps += "maps:$it" },
        save = { taps += "save:${it.title}"; null }, delete = { taps += "delete:${it.title}" }, startOver = { taps += "startOver" })

    private fun page(v: CalendarView = view(), lang: Lang = Lang.EN, dark: Boolean = false) =
        rule.show(dark = dark) { CalendarScreen(v, lang, actions) }

    private fun nothingCut() {
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val cut = rule.onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { n ->
                val out = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
                val r = out.firstOrNull() ?: return@mapNotNull null
                val edge = n.boundsInRoot.left < -1f || n.positionInRoot.x + n.size.width > width + 1f
                r.layoutInput.text.text.takeIf { edge || (0 until r.lineCount).any { r.isLineEllipsized(it) } }
            }
        assertEquals(emptyList<String>(), cut)
    }

    private fun checks() {
        rule.waitForIdle()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        nothingCut()
    }

    private fun tap(text: String) {
        rule.onNode(hasText(text, substring = true) and hasClickAction()).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1_000)
    }


    @Test fun onlyPrivateAndRobotAddedEventsOpenTheForm() {
        page()
        checks()
        tap("Dentist")
        assertEquals(listOf("app:Dentist"), taps)
        tap("Pick up the car")
        rule.onNode(hasText("Change the event")).assertExists()
        rule.onNode(hasText("What") and hasText("Pick up the car")).assertExists()
        rule.onNode(hasText("Cancel") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        tap("Surprise party planning")
        rule.onNode(hasText("Delete this event") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("app:Dentist", "delete:Surprise party planning"), taps)
        rule.onNode(hasText("Added by Robot")).assertExists()
        rule.onNode(hasText("Private")).assertExists()
    }

    @Test fun leaveByIsTheNextPlaceAndOneTapToTheMapsApp() {
        page()
        rule.onNode(hasText("Next: Dentist", substring = true)).assertExists()
        rule.onNode(hasContentDescription("Directions to 214 Main St in your maps app") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf("maps:214 Main St"), taps)
    }

    @Test fun historyIsBehindTheClock() {
        page()
        rule.onNode(hasContentDescription("History") and hasClickAction()).performClick()
        assertEquals(listOf("history"), taps)
    }

    @Test fun addingAskWhereAndAPrivateOneSaves() {
        page()
        tap("Add an event")
        tap("In your calendar app")
        assertEquals(listOf("addapp"), taps)
        tap("Add an event")
        tap("Kept only in Robot")
        rule.onNode(hasText("New private event")).assertExists()
    }

    /**
     * The real form, not a stand-in: it settles (a value-based text field in a dialog window never let Robolectric go
     * idle, today-h4), keeps its fields to their caps, and saves only on Save.
     */
    @Test fun theFormSettlesAndSavesOnlyOnSave() {
        page()
        tap("Add an event")
        tap("Kept only in Robot")
        rule.onNode(hasText("New private event")).assertExists()
        rule.onNode(hasText("What") and hasSetTextAction()).performTextReplacement("Picnic " + "x".repeat(300))
        rule.onNode(hasText("Where (optional)") and hasSetTextAction()).performTextReplacement("Point Pleasant Park")
        rule.waitForIdle()
        assertEquals(emptyList<String>(), taps)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, taps.size)
        assertEquals("save:Picnic " + "x".repeat(app.heyrobot.core.calendar.PrivateEventsCodec.MAX_TITLE - 7), taps.single())
        rule.onAllNodes(hasText("New private event")).assertCountEquals(0)
    }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi", fontScale = 2f)
    fun theFormInFrenchAtTwiceTheTextSize() {
        page(lang = Lang.FR)
        tap("Surprise party planning")
        rule.waitForIdle()
        rule.onNode(hasText("Surprise party planning") and hasSetTextAction()).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun withoutTheCalendarPrivateEventsStillShowAndItAsks() {
        page(view(readable = false))
        checks()
        rule.onNode(hasText("Surprise party planning")).assertExists()
        tap("Allow")
        assertEquals(listOf("allow"), taps)
    }

    /** Today fix (review-today-first, finding 2): private events Robot can't open offer Start over, as Lists does, after a confirm. */
    @Test fun unreadableOffersStartOver() {
        page(view(state = PrivateState.UNREADABLE))
        rule.onNode(hasText("Robot can't open your private events on this phone", substring = true)).assertExists()
        tap("Start over")
        assertEquals(emptyList<String>(), taps)
        rule.onNode(hasText("Start over with private events?")).assertExists()
        rule.onAllNodes(hasText("Start over") and hasClickAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("startOver"), taps)
    }

    @Test fun lockedSaysSo() {
        page(view(state = PrivateState.LOCKED))
        rule.onNode(hasText("Unlock your phone to see your private events.")).assertExists()
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun onA360dpPhone() { page(); checks(); rule.onNodeWithTag(CALENDAR_STRIP).assertExists() }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2f)
    fun atTwiceTheTextSize() { page(); checks(); rule.onAllNodesWithTag(CALENDAR_STRIP).assertCountEquals(0) }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi", fontScale = 2f)
    fun inFrenchAtTwiceTheTextSize() {
        page(lang = Lang.FR)
        checks()
        rule.onNode(hasText("Ajouter un événement") and hasClickAction()).assertExists()
        rule.onNode(hasText("Agenda")).assertExists()
    }

    @Test fun inTheDark() { page(dark = true); checks() }

    @Test fun theDayHeadingsAreHeadings() {
        page()
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        assertTrue(headings.size.toString(), headings.size >= 2)
        rule.onNode(hasText("Today, ", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }
}
