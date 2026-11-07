// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.DayInput
import app.heyrobot.core.ListGlance
import app.heyrobot.core.NoteGlance
import app.heyrobot.core.Today
import app.heyrobot.core.TodayInput
import app.heyrobot.core.TodayPage
import app.heyrobot.core.TodayView
import app.heyrobot.core.i18n.Lang
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipeDown
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Today page (home-design H2) at every width down to 360 dp, at twice the text size, in French, light and dark: every
 * part shows, nothing is cut, every tap target is 48 dp with words TalkBack reads, the right-now line is read first, and
 * each part taps through to its own screen. Made-up data; nothing goes online and nothing speaks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TodayScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("America/Halifax")
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun ev(title: String, h: Int, m: Int = 0, place: String? = null) = CalendarEvent(ms(h, m), ms(h, m) + 3_600_000L, false, title, place)
    private val now = ZonedDateTime.of(2026, 11, 3, 9, 15, 0, 0, zone)

    private fun day(events: List<CalendarEvent>? = listOf(ev("Dentist", 14, 30, "214 Main St"), ev("Pick up the car", 17)), robots: Int = 1): TodayView =
        TodayPage.view(TodayInput(Today.picture(DayInput(now, events, listOf("Bring the library books"), 3)), events,
            listOf(ListGlance("g", "Groceries", 4), ListGlance("t", "To-do", 3)), 2, robots,
            NoteGlance("n1", "Parking", listOf("Level 3, row F"), true)))

    private val taps = mutableListOf<String>()
    private val actions = TodayActions(
        talk = { taps += "talk" }, openTime = { taps += "time" }, allowCalendar = { taps += "allow" }, openList = { taps += "list:$it" },
        openReminders = { taps += "reminders" }, openFiles = { taps += "files" }, openRobots = { taps += "robots" }, openNote = { taps += "note:$it" },
        add = { taps += "add:$it" }, pick = { taps += "pick:$it" }, gettingStarted = { taps += "start" },
        reorder = { o -> taps += "order:" + o.joinToString(",") { it.key } },
        fetchWeather = { taps += "weather" }, weatherTown = { taps += "town" },
    )

    private fun page(v: TodayView = day(), lang: Lang = Lang.EN, dark: Boolean = false, ready: Boolean = true,
        order: List<app.heyrobot.core.TodayPart> = app.heyrobot.core.TodayPart.DEFAULT) =
        rule.show(dark = dark) { TodayScreen(v, ready, lang, listOf("n1" to "Parking", "n2" to "Groceries"), actions, order = order) }

    private fun top(p: app.heyrobot.core.TodayPart) = rule.onNodeWithTag(PART + p.key).fetchSemanticsNode().boundsInRoot.top

    private fun move(p: app.heyrobot.core.TodayPart, label: String): Boolean {
        val acts = rule.onNodeWithTag(PART + p.key).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions).orEmpty()
        val a = acts.firstOrNull { it.label == label } ?: return false
        rule.runOnIdle { a.action() }
        return true
    }

    // H4: the parts in your order, by drag or TalkBack.
    @Test fun thePartsFollowYourOrder() {
        page(order = listOf(app.heyrobot.core.TodayPart.NOTE, app.heyrobot.core.TodayPart.CALENDAR, app.heyrobot.core.TodayPart.LISTS))
        checks()
        assertTrue(top(app.heyrobot.core.TodayPart.NOTE) < top(app.heyrobot.core.TodayPart.CALENDAR))
        assertTrue(top(app.heyrobot.core.TodayPart.CALENDAR) < top(app.heyrobot.core.TodayPart.LISTS))
    }

    @Test fun talkBackMovesAPartUpAndDown() {
        page()
        assertTrue(!move(app.heyrobot.core.TodayPart.CALENDAR, "Move up"))
        assertTrue(!move(app.heyrobot.core.TodayPart.NOTE, "Move down"))
        rule.onNode(androidx.compose.ui.test.hasTestTag(PART + "lists") and androidx.compose.ui.test.hasContentDescription("Lists and reminders")).assertExists()
        assertTrue(move(app.heyrobot.core.TodayPart.LISTS, "Move up"))
        assertTrue(move(app.heyrobot.core.TodayPart.CALENDAR, "Move down"))
        assertEquals(listOf("order:lists,calendar,note", "order:lists,calendar,note"), taps)
    }

    @Test fun pressAndHoldThenDragMovesAPartAndOpensNothing() {
        page()
        val noteTop = top(app.heyrobot.core.TodayPart.NOTE)
        val calTop = top(app.heyrobot.core.TodayPart.CALENDAR)
        // A plain swipe, with no hold: the page scrolls, nothing moves.
        rule.onNodeWithTag(PART + "calendar").performTouchInput { swipeDown(centerY, centerY + 300f, 200) }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), taps.filter { it.startsWith("order") })
        rule.onNodeWithTag(PART + "calendar").performTouchInput {
            down(Offset(centerX, 4f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            repeat(20) { moveBy(Offset(0f, (noteTop - calTop + 200f) / 20), 16) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("order:lists,note,calendar"), taps)
    }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi")
    fun talkBackMovesInFrench() {
        page(lang = Lang.FR)
        assertTrue(move(app.heyrobot.core.TodayPart.NOTE, "Monter"))
        assertEquals(listOf("order:calendar,note,lists"), taps)
    }

    /** No text on the page is cut short or runs past the screen's edges. */
    private fun nothingCut() {
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val cut = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }, useUnmergedTree = true)
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

    private fun tap(text: String, substring: Boolean = false) = rule.onNode(hasText(text, substring = substring) and hasClickAction()).performScrollTo().performClick()

    @Test fun eachPartShowsInOrderAndTapsThroughToItsScreen() {
        page()
        checks()
        for (t in listOf("Groceries, 4 left", "To-do, 3 left", "Reminder: Bring the library books", "2 loose ends in Robot Files", "1 request from another Robot", "Parking"))
            tap(t, substring = true)
        tap("Dentist", substring = true)
        rule.onNode(hasText("Talk to Robot") and hasClickAction()).performClick()
        assertEquals(listOf("list:g", "list:t", "reminders", "files", "robots", "note:n1", "time", "talk"), taps)
        rule.onNodeWithTag(STRIP).assertExists()
    }

    @Test fun theRightNowLineIsReadFirstAsAHeading() {
        page()
        val first = rule.onRoot().fetchSemanticsNode().let { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? =
                if (n.config.getOrNull(SemanticsProperties.Heading) != null) n else n.children.firstNotNullOfOrNull(::walk)
            walk(root)
        }
        val said = first?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        assertTrue(said, said.startsWith("Next:") || said.contains("Dentist"))
        assertTrue(said, said.contains("November"))
    }

    @Test fun aQuickAddGoesToTheNote() {
        page()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Row F, by the lift")
        tap("Add")
        assertEquals(listOf("add:Row F, by the lift"), taps)
    }

    @Test fun withoutTheCalendarItAsksWithOneButton() {
        page(day(events = null))
        checks()
        rule.onAllNodesWithTag(STRIP).assertCountEquals(0)
        tap("Allow")
        assertEquals(listOf("allow"), taps)
    }

    @Test fun notReadyShowsTheFixAsOneLine() {
        page(ready = false)
        rule.onNode(hasText("Robot isn't ready yet.")).assertExists()
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun onA360dpPhone() { page(); checks(); rule.onNodeWithTag(STRIP).assertExists() }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2f)
    fun atTwiceTheTextSizeTheRowsStandAlone() {
        page()
        checks()
        rule.onAllNodesWithTag(STRIP).assertCountEquals(0)
        rule.onNode(hasText("Dentist")).assertExists()
    }

    @Test @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun onAWideScreenOneColumnAtMost640dp() {
        page()
        checks()
        val w = rule.onNodeWithTag(STRIP).fetchSemanticsNode().size.width
        val most = with(rule.density) { 640 * density }
        assertTrue("$w > $most", w <= most)
    }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi")
    fun inFrench() {
        page(lang = Lang.FR)
        checks()
        rule.onNode(hasText("14 h 30")).assertExists()
        rule.onNode(hasText("Rappel : Bring the library books")).assertExists()
        rule.onNode(hasText("Groceries, encore 4")).assertExists()
    }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi", fontScale = 2f)
    fun inFrenchAtTwiceTheTextSize() { page(lang = Lang.FR); checks() }

    @Test fun inTheDark() { page(dark = true); checks() }

    private fun weather(w: app.heyrobot.core.WeatherGlance) = day().copy(weather = w)
    private val halifax = app.heyrobot.core.WeatherPlace("Halifax", 44.65, -63.58)
    private fun forecast() = app.heyrobot.core.StoredForecast(ms(6, 32), halifax, app.heyrobot.core.WeatherUnits.METRIC, zone,
        app.heyrobot.core.WeatherNow(4.0, 3), app.heyrobot.core.WeatherDay(now.toLocalDate(), 61, 9.0, 3.0, 70, 2.0, 2.0, 0.0, 0.0, 21.0, 39.0, 98))

    @Test fun theWeatherLineShowsItsAgeAndATapFetchesAgain() {
        val g = app.heyrobot.core.TodayWeather.glance(forecast(), halifax, app.heyrobot.core.WeatherUnits.METRIC, now, Lang.EN)
        page(weather(g))
        checks()
        rule.onNode(hasText("Today: ", substring = true)).assertExists()
        rule.onNode(hasText("high of 9", substring = true)).assertExists()
        tap("Updated 6:32")
        assertEquals(listOf("weather"), taps)
    }

    @Test fun noForecastTodayOffersOneTapAndNoTownOpensSettings() {
        page(weather(app.heyrobot.core.WeatherGlance.NotYet))
        rule.onNode(hasText("No forecast yet today.")).assertExists()
        tap("Get today's forecast")
        assertEquals(listOf("weather"), taps)
    }

    @Test fun noTownSavedOpensSettingsWeather() {
        page(weather(app.heyrobot.core.WeatherGlance.NoTown))
        tap("Set it")
        assertEquals(listOf("town"), taps)
    }

    @Test fun offThereIsNoWeatherLine() {
        page()
        rule.onNode(hasText("forecast", substring = true)).assertDoesNotExist()
        rule.onNode(hasText("Updated", substring = true)).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "fr-rCA-w360dp-h740dp-xhdpi")
    fun theWeatherLineInFrench() {
        val v = weather(app.heyrobot.core.TodayWeather.glance(forecast(), halifax, app.heyrobot.core.WeatherUnits.METRIC, now.withHour(13), Lang.FR))
        rule.show {
            androidx.compose.runtime.CompositionLocalProvider(app.heyrobot.ui.theme.LocalLangs provides app.heyrobot.core.i18n.Langs.of(Lang.FR)) {
                TodayScreen(v, true, Lang.FR, emptyList(), actions)
            }
        }
        checks()
        rule.onNode(hasText("Mis à jour à 6 h 32")).assertExists()
        rule.onNode(hasText("maximum de 9", substring = true)).assertExists()
    }

    @Test fun nothingLeftToday() {
        page(TodayPage.view(TodayInput(Today.picture(DayInput(now.withHour(21), emptyList())), emptyList())))
        checks()
        rule.onNode(hasText("That's your day.")).assertExists()
        rule.onNode(hasText("loose end", substring = true)).assertDoesNotExist()
    }
}
