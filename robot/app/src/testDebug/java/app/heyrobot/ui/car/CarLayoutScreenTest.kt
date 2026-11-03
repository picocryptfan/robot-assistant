// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.car.CarAction
import app.heyrobot.core.car.CarBeside
import app.heyrobot.core.car.CarFourth
import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarLarge
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.car.CarMessageLine
import app.heyrobot.core.car.CarMessagesNow
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.ui.settings.CarScreenRows
import app.heyrobot.ui.settings.launcherApps
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FR = "fr-rCA-w411dp-h891dp-xhdpi"
private const val HALF = "w411dp-h420dp-xhdpi"
private const val HALF_FR = "fr-rCA-w411dp-h420dp-xhdpi"
private const val HALF_SHORT = "w411dp-h400dp-xhdpi"
/** The car screen at the split's smallest stop: 3/4 for the other app, and Android's own smallest window. */
private const val SPLIT_34 = "w411dp-h240dp-xhdpi"
private const val SPLIT_LEAST = "w411dp-h220dp-xhdpi"
private const val SPLIT_34_FR = "fr-rCA-w411dp-h240dp-xhdpi"
private const val HALF_SHORT_FR = "fr-rCA-w411dp-h400dp-xhdpi"

/** A Pixel 8 on its side, inside the system bars: 359dp high, not the 411 of the whole screen. */
private const val WIDE = "w914dp-h359dp-xhdpi"
private const val WIDE_FR = "fr-rCA-w914dp-h359dp-xhdpi"

/** Half of that, beside a map. */
private const val WIDE_HALF = "w477dp-h359dp-xhdpi"
private const val WIDE_HALF_FR = "fr-rCA-w477dp-h359dp-xhdpi"

/**
 * The car screen's three areas (docs/plans/car-layout-design.md): half, quarter, quarter; the choices from Settings › Car mode;
 * no home directions button unless you choose it; message panels that show and never send; split screen; every target big.
 * The goldens are the look, in both themes and both languages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CarLayoutScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private val actions = CarScreenActions(
        talk = { calls += "talk" }, apps = { calls += "apps" }, settings = { calls += "settings" }, fourth = { calls += "fourth" },
        maps = { calls += "maps" }, playPause = { calls += "play" }, next = { calls += "next" },
        previous = { calls += "previous" }, openMusic = { calls += "open" }, messageSettings = { calls += "message settings" },
    )
    private val song = CarMusicUi("Radio app", "Harvest Moon", playing = true, artist = "Neil Young")
    private val longTitle = "The Ballad of the Extraordinarily Long Road Home to Cape Breton Island and All the Places In Between"
    private val longSong = song.copy(song = longTitle)
    private val latest = CarMessagesNow(CarMessagesNow.State.SHOWN, listOf(CarMessageLine("Marie", "Running ten minutes late, see you at the rink")))
    private val several = CarMessagesNow(CarMessagesNow.State.SHOWN, listOf(
        CarMessageLine("Marie", "Running ten minutes late, see you at the rink"),
        CarMessageLine("Hockey team", "Who has the extra jerseys for Saturday?"),
        CarMessageLine("Sam", "Can you pick up milk on the way home?"),
    ))

    private val mapMedia = CarScreenState(music = song)
    private val mediaLatest = mapMedia.copy(layout = CarLayout(large = CarLarge.MEDIA, info = CarInfo.LATEST_MESSAGE), messages = latest)
    private val messagesDirections = mapMedia.copy(layout = CarLayout(large = CarLarge.MESSAGES, info = CarInfo.DIRECTIONS), messages = several)

    private val off = CarMessagesNow(CarMessagesNow.State.OFF)
    private val none = CarMessagesNow(CarMessagesNow.State.NONE)
    private val locked = CarMessagesNow(CarMessagesNow.State.LOCKED)
    private val largeOff = mapMedia.copy(layout = CarLayout(large = CarLarge.MESSAGES), messages = off)
    private val infoOff = mapMedia.copy(layout = CarLayout(info = CarInfo.LATEST_MESSAGE), messages = off)
    private val mediaDirections = mapMedia.copy(layout = CarLayout(large = CarLarge.MEDIA, info = CarInfo.DIRECTIONS))
    private val quiet = mediaDirections.copy(music = CarMusicUi())
    private val longLatest = mediaLatest.copy(music = longSong)
    private val navHome = CarFourth.Action(CarAction.NAVIGATE_HOME)

    private fun CarScreenState.fr() = copy(music = if (music.app == null) music else music.copy(app = "Appli radio"))

    private fun draw(s: CarScreenState, dark: Boolean = false) {
        rule.show(dark = dark) { CarModeScreen(s, actions) }
    }

    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    private fun close(a: Dp, b: Dp, within: Dp = 1.dp) = assertTrue("$a and $b", kotlin.math.abs((a - b).value) <= within.value)

    // The goldens: three layouts, split screen, landscape and large text.
    @Test fun mapAndMedia() { draw(mapMedia); rule.shot("car_map_media"); noProblems() }
    @Test fun mapAndMediaDark() { draw(mapMedia, dark = true); rule.shot("car_map_media_dark"); noProblems() }
    @Test @Config(qualifiers = FR) fun mapAndMediaFrench() { draw(mapMedia.fr()); rule.shot("car_map_media_fr"); noProblems() }
    @Test @Config(qualifiers = FR) fun mapAndMediaFrenchDark() { draw(mapMedia.fr(), dark = true); rule.shot("car_map_media_fr_dark"); noProblems() }
    @Test fun mediaAndLatest() { draw(mediaLatest); rule.shot("car_media_latest"); noProblems() }
    @Test fun mediaAndLatestDark() { draw(mediaLatest, dark = true); rule.shot("car_media_latest_dark"); noProblems() }
    @Test @Config(qualifiers = FR) fun mediaAndLatestFrench() { draw(mediaLatest.fr()); rule.shot("car_media_latest_fr"); noProblems() }
    @Test @Config(qualifiers = FR) fun mediaAndLatestFrenchDark() { draw(mediaLatest.fr(), dark = true); rule.shot("car_media_latest_fr_dark"); noProblems() }
    @Test fun messagesAndDirections() { draw(messagesDirections); rule.shot("car_messages_directions"); noProblems() }
    @Test fun messagesAndDirectionsDark() { draw(messagesDirections, dark = true); rule.shot("car_messages_directions_dark"); noProblems() }
    @Test @Config(qualifiers = FR) fun messagesAndDirectionsFrench() { draw(messagesDirections.fr()); rule.shot("car_messages_directions_fr"); noProblems() }
    @Test @Config(qualifiers = FR) fun messagesAndDirectionsFrenchDark() { draw(messagesDirections.fr(), dark = true); rule.shot("car_messages_directions_fr_dark"); noProblems() }
    @Test @Config(qualifiers = HALF) fun besideAMap() { draw(mapMedia.copy(beside = true)); rule.shot("car_beside"); noProblems() }
    @Test @Config(qualifiers = HALF) fun besideAMapDark() { draw(mapMedia.copy(beside = true), dark = true); rule.shot("car_beside_dark"); noProblems() }
    @Test @Config(qualifiers = HALF_FR) fun besideAMapFrench() { draw(mapMedia.copy(beside = true).fr()); rule.shot("car_beside_fr"); noProblems() }
    @Test @Config(qualifiers = HALF_FR) fun besideAMapFrenchDark() { draw(mapMedia.copy(beside = true).fr(), dark = true); rule.shot("car_beside_fr_dark"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF) fun besideAMapOnItsSide() { draw(mapMedia.copy(beside = true)); rule.shot("car_beside_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun besideAMapOnItsSideFrench() { draw(mapMedia.copy(beside = true).fr()); rule.shot("car_beside_landscape_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun landscape() { draw(mapMedia); rule.shot("car_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun landscapeDark() { draw(messagesDirections, dark = true); rule.shot("car_landscape_dark"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun landscapeFrench() { draw(mapMedia.fr()); rule.shot("car_landscape_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun landscapeMessagesAndDirections() { draw(messagesDirections); rule.shot("car_landscape_messages_directions"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun landscapeMessagesAndDirectionsFrench() { draw(messagesDirections.fr()); rule.shot("car_landscape_messages_directions_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun landscapeMediaAndDirections() { draw(mediaDirections); rule.shot("car_landscape_media_directions"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun landscapeMediaAndDirectionsFrench() { draw(mediaDirections.fr()); rule.shot("car_landscape_media_directions_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun landscapeMediaAndLatest() { draw(mediaLatest); rule.shot("car_landscape_media_latest"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun landscapeMediaAndLatestFrench() { draw(mediaLatest.fr()); rule.shot("car_landscape_media_latest_fr"); noProblems() }
    @Test fun aLongTitle() { draw(longLatest); rule.shot("car_long_title"); noProblems() }
    @Test @Config(qualifiers = FR) fun aLongTitleFrench() { draw(longLatest.fr()); rule.shot("car_long_title_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun aLongTitleOnItsSide() { draw(longLatest); rule.shot("car_long_title_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun aLongTitleOnItsSideFrench() { draw(longLatest.fr()); rule.shot("car_long_title_landscape_fr"); noProblems() }
    @Test fun aLongTitleInTheSmallPanel() { draw(mapMedia.copy(music = longSong)); rule.shot("car_long_title_small"); noProblems() }
    @Test fun messagesOffInTheLargePanel() { draw(largeOff); rule.shot("car_messages_off"); noProblems() }
    @Test fun messagesOffInTheLargePanelDark() { draw(largeOff, dark = true); rule.shot("car_messages_off_dark"); noProblems() }
    @Test @Config(qualifiers = FR) fun messagesOffInTheLargePanelFrench() { draw(largeOff.fr()); rule.shot("car_messages_off_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun messagesOffOnItsSide() { draw(largeOff); rule.shot("car_messages_off_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun messagesOffOnItsSideFrench() { draw(largeOff.fr()); rule.shot("car_messages_off_landscape_fr"); noProblems() }
    @Test fun messagesOffInTheInformationPanel() { draw(infoOff); rule.shot("car_messages_off_info"); noProblems() }
    @Test @Config(qualifiers = FR) fun messagesOffInTheInformationPanelFrench() { draw(infoOff.fr()); rule.shot("car_messages_off_info_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun messagesOffInTheInformationPanelOnItsSide() { draw(infoOff); rule.shot("car_messages_off_info_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun messagesOffInTheInformationPanelOnItsSideFrench() { draw(infoOff.fr()); rule.shot("car_messages_off_info_landscape_fr"); noProblems() }
    @Test fun nothingPlaying() { draw(quiet); rule.shot("car_quiet"); noProblems() }
    @Test @Config(qualifiers = FR) fun nothingPlayingFrench() { draw(quiet.fr()); rule.shot("car_quiet_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun nothingPlayingOnItsSide() { draw(quiet); rule.shot("car_quiet_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun nothingPlayingOnItsSideFrench() { draw(quiet.fr()); rule.shot("car_quiet_landscape_fr"); noProblems() }
    @Test @Config(qualifiers = HALF) fun theFourthButtonOnTwoLines() { draw(mapMedia.copy(beside = true, layout = CarLayout(fourth = navHome))); rule.shot("car_fourth_two_lines"); noProblems() }
    @Test @Config(qualifiers = HALF_FR) fun theFourthButtonOnTwoLinesFrench() { draw(mapMedia.copy(beside = true, layout = CarLayout(fourth = navHome)).fr()); rule.shot("car_fourth_two_lines_fr"); noProblems() }
    @Test @Config(qualifiers = PHONE, fontScale = 1.5f) fun largeTextStaysInItsPanels() { draw(mediaLatest); rule.shot("car_large_text"); noProblems() }

    @Test fun theRowIsOneFixedRowAndTheLargePanelHasTheRest() {
        draw(mapMedia)
        val map = rule.onNodeWithContentDescription("Open maps beside Robot").getUnclippedBoundsInRoot()
        val talk = rule.onNodeWithContentDescription("Talk").getUnclippedBoundsInRoot()
        val music = rule.onNodeWithContentDescription("Music").getUnclippedBoundsInRoot()
        val apps = rule.onNodeWithContentDescription("Apps").getUnclippedBoundsInRoot()
        val settings = rule.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        // One row, 72 dp tall, Talk first and wider.
        for (b in listOf(talk, music, apps, settings)) { close(b.top, talk.top); close(b.bottom - b.top, 72.dp) }
        assertTrue(talk.right <= music.left && music.right <= apps.left && apps.right <= settings.left)
        assertTrue((talk.right - talk.left) > (music.right - music.left))
        close(map.left, talk.left)
        close(map.right, settings.right)
        // The large panel takes three times the information panel, between it and the row.
        val info = talk.top - map.bottom - 24.dp
        close(map.bottom - map.top, info * 3, within = 4.dp)
    }

    @Test fun theRowHasNoCallAndNoWords() {
        draw(mapMedia)
        rule.onAllNodesWithContentDescription("Call").assertCountEquals(0)
        for (w in listOf("Talk", "Music", "Apps", "Settings", "Call")) rule.onAllNodesWithText(w).assertCountEquals(0)
    }

    @Test fun noHomeDirectionsButtonUnlessYouChooseIt() {
        draw(mapMedia)
        rule.onAllNodesWithText("Navigate home").assertCountEquals(0)
    }

    @Test fun theFourthButtonIsWhatYouChose() {
        draw(mapMedia.copy(layout = CarLayout(fourth = CarFourth.Action(CarAction.NAVIGATE_HOME))))
        rule.onNodeWithContentDescription("Navigate home").performClick()
        assertEquals(listOf("fourth"), calls)
    }

    @Test fun theFourthButtonCanBeAnApp() {
        draw(mapMedia.copy(layout = CarLayout(fourth = CarFourth.App(MusicApp("org.example.bus", "a".repeat(64), "Bus times")))))
        rule.onNodeWithContentDescription("Bus times").performClick()
        assertEquals(listOf("fourth"), calls)
    }

    @Test fun everyButtonDoesItsJob() {
        draw(mapMedia)
        for (d in listOf("Talk", "Apps", "Music", "Settings", "Open maps beside Robot", "Pause", "Next", "Previous", "Open Radio app"))
            rule.onNodeWithContentDescription(d).performClick()
        assertEquals(listOf("talk", "apps", "fourth", "settings", "maps", "play", "next", "previous", "open"), calls)
    }

    @Test fun messagePanelsOfferNoWayToSend() {
        var s by mutableStateOf(mapMedia.copy(layout = CarLayout(large = CarLarge.MESSAGES, info = CarInfo.DIRECTIONS), messages = several))
        rule.show { CarModeScreen(s, actions) }
        // Talk, the fourth, Apps, Settings and the map's button: the message panel adds none.
        rule.onAllNodes(hasClickAction()).assertCountEquals(5)
        rule.onNodeWithText("To reply, tap Talk and say “reply to” and the name").assertExists()
        s = s.copy(layout = CarLayout(large = CarLarge.MAP, info = CarInfo.LATEST_MESSAGE))
        rule.waitForIdle()
        // The same with the map's panel and the latest message: it's text and no more.
        rule.onNodeWithText("Marie").assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(5)
    }

    @Test fun aLockedMessageHidesItsText() {
        draw(mapMedia.copy(layout = CarLayout(info = CarInfo.LATEST_MESSAGE),
            messages = CarMessagesNow(CarMessagesNow.State.SHOWN, listOf(CarMessageLine("Marie", null)), locked = true)))
        rule.onNodeWithText("Marie").assertExists()
        rule.onNodeWithText("Unlock to read it").assertExists()
    }

    @Test fun messagesOffAndNoneAndLockedEachSayWhy() {
        var state by mutableStateOf(CarMessagesNow(CarMessagesNow.State.OFF))
        rule.show { CarModeScreen(mapMedia.copy(layout = CarLayout(large = CarLarge.MESSAGES), messages = state), actions) }
        rule.onNodeWithText("Messages are off").assertExists()
        rule.onNodeWithContentDescription("Open Settings › Messages").performClick()
        assertEquals(listOf("message settings"), calls)
        state = CarMessagesNow(CarMessagesNow.State.NONE)
        rule.waitForIdle()
        rule.onNodeWithText("No new messages").assertExists()
        rule.onAllNodesWithText("Open Settings › Messages").assertCountEquals(0)
        state = CarMessagesNow(CarMessagesNow.State.LOCKED)
        rule.waitForIdle()
        rule.onNodeWithText("Unlock to see your messages").assertExists()
        rule.onAllNodesWithText("Open Settings › Messages").assertCountEquals(0)
    }

    @Test @Config(qualifiers = WIDE) fun theInformationPanelOffersTheSameWayToMessages() {
        draw(infoOff)
        rule.onNodeWithText("Messages are off").assertExists()
        rule.onNodeWithContentDescription("Open Settings › Messages").performClick()
        assertEquals(listOf("message settings"), calls)
    }

    @Test @Config(qualifiers = HALF) fun inSplitScreenTheMapPanelGivesWayAndTheButtonsStay() {
        draw(mapMedia.copy(beside = true))
        rule.onAllNodesWithText("Open maps beside Robot").assertCountEquals(0)
        for (d in listOf("Talk", "Apps", "Music", "Settings", "Pause")) rule.onNodeWithContentDescription(d).assertExists()
    }

    @Test fun afterARouteTheMapPanelGoesBackToIt() {
        draw(mapMedia.copy(directions = true, layout = CarLayout(fourth = navHome)))
        rule.onNodeWithContentDescription("Back to directions").performClick()
        assertEquals(listOf("maps"), calls)
        // Your fourth button keeps the name you chose it by.
        rule.onNodeWithContentDescription("Navigate home").assertExists()
    }

    // CM4: with the map beside Robot there's nothing to go back to, so a button you chose keeps its name.
    @Test @Config(qualifiers = HALF) fun besideTheMapTheFourthButtonKeepsItsName() {
        draw(mapMedia.copy(beside = true, directions = true, layout = CarLayout(fourth = navHome)))
        rule.onNodeWithContentDescription("Navigate home").assertExists()
        rule.onAllNodesWithText("Back to directions").assertCountEquals(0)
    }

    @Test @Config(qualifiers = HALF) fun aMapsButtonGoesBackToTheRouteEvenInSplitScreen() {
        draw(mapMedia.copy(beside = true, directions = true, layout = CarLayout(fourth = CarFourth.Action(CarAction.MAPS))))
        rule.onNodeWithContentDescription("Back to directions").performClick()
        assertEquals(listOf("fourth"), calls)
    }

    @Test @Config(qualifiers = HALF) fun theDirectionsPanelGoesBackToTheRouteEvenInSplitScreen() {
        draw(mapMedia.copy(beside = true, directions = true, layout = CarLayout(info = CarInfo.DIRECTIONS)))
        rule.onNodeWithContentDescription("Back to directions").performClick()
        assertEquals(listOf("maps"), calls)
    }

    @Test fun directionsSayWhereTheyAreOrShowTheLine() {
        draw(messagesDirections)
        rule.onNodeWithText("Your maps app shows the directions").assertExists()
        rule.onNodeWithContentDescription("Maps").performClick()
        assertEquals(listOf("maps"), calls)
    }

    @Test fun aLineOfDirectionsShowsWhenThereIsOne() {
        draw(messagesDirections.copy(directionText = "Turn left on Barrington St"))
        rule.onNodeWithText("Turn left on Barrington St").assertExists()
    }

    @Test @Config(qualifiers = "w914dp-h240dp-xhdpi") fun aShortDirectionsPanelIsAllButton() {
        draw(mediaDirections)
        rule.onAllNodesWithText("Your maps app shows the directions").assertCountEquals(0)
        rule.onNodeWithContentDescription("Maps").performClick()
        assertEquals(listOf("maps"), calls)
    }

    @Test @Config(qualifiers = WIDE) fun aShortDirectionsPanelStillShowsALine() {
        draw(mediaDirections.copy(directionText = "Turn left on Barrington St"))
        rule.onNodeWithText("Turn left on Barrington St").assertExists()
        assertEquals(emptyList<String>(), textProblems())
    }

    @Test fun theArtistHasItsOwnLineAndATitleStopsAtAWord() = titleStops()
    @Test @Config(qualifiers = WIDE) fun theArtistHasItsOwnLineAndATitleStopsAtAWordOnItsSide() = titleStops()

    // The user's report on 1.2.0: at 3/4 the name was always cut off. It held 64 dp of width, beside a picture.
    private val podcast = "The Long Road Home to Cape Breton Island, Episode 212: All the Places In Between"
    private fun wholeName(s: CarScreenState) {
        draw(s)
        val shown = textNodes().single { (_, r) -> r.layoutInput.text.text.startsWith(s.music.song!!.take(8)) }
        assertEquals(s.music.song, shown.second.layoutInput.text.text)
        assertTrue("clipped: ${shown.second.didOverflowHeight}", !shown.second.didOverflowHeight)
        assertTrue("${shown.second.layoutInput.style.fontSize}", shown.second.layoutInput.style.fontSize.value >= 6.9f)
        assertEquals(emptyList<String>(), touchProblems())
    }
    private val inSplit = mapMedia.copy(beside = true)
    private val withArt = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
    @Test @Config(qualifiers = SPLIT_34) fun aLongNameIsWholeAtTheSmallestSplit() { wholeName(inSplit.copy(music = song.copy(song = podcast, art = withArt))); rule.shot("car_split34_long") }
    @Test @Config(qualifiers = SPLIT_34) fun aLongNameIsWholeWithTheAppsIconToo() = wholeName(inSplit.copy(music = song.copy(song = podcast, icon = withArt)))
    @Test @Config(qualifiers = SPLIT_34) fun aLongNameIsWholeWithTheWayBackToTheMap() = wholeName(inSplit.copy(music = song.copy(song = podcast), back = CarBeside.Back.INFO))
    @Test @Config(qualifiers = SPLIT_LEAST) fun aLongNameIsWholeInAndroidsSmallestWindow() = wholeName(inSplit.copy(music = song.copy(song = podcast)))
    @Test @Config(qualifiers = SPLIT_34, fontScale = 2f) fun aLongNameIsWholeAtTheLargestFontSize() { wholeName(inSplit.copy(music = song.copy(song = podcast))); rule.shot("car_split34_long_large_font") }
    @Test @Config(qualifiers = SPLIT_34_FR) fun aLongNameIsWholeInFrench() = wholeName(inSplit.copy(music = song.copy(song = podcast)).fr())
    @Test @Config(qualifiers = SPLIT_34) fun aShortNameKeepsItsSizeAndItsArtist() {
        draw(inSplit.copy(music = song))
        val shown = texts().first { it.layoutInput.text.text == song.song }
        assertEquals(20f, shown.layoutInput.style.fontSize.value, 0.01f)
        assertTrue("Neil Young" in texts().map { it.layoutInput.text.text })
    }
    @Test @Config(qualifiers = HALF) fun aLongNameIsWholeHalfwayToo() = wholeName(inSplit.copy(music = longSong))

    private fun titleStops(s: CarScreenState = longLatest) {
        draw(s)
        val shown = texts().map { it.layoutInput.text.text }
        val title = shown.single { it.startsWith("The Ballad") }
        assertTrue(title, title.endsWith("…"))
        val kept = title.removeSuffix("…")
        assertTrue(title, longTitle.startsWith(kept) && longTitle[kept.length] == ' ')
        assertTrue("$shown", "Neil Young" in shown)
    }

    // Every text, in every panel and size: whole, big enough to read at a glance, and in a colour with the contrast for it.
    @Test fun everyTextFits() = everyPanel()
    @Test @Config(qualifiers = FR) fun everyTextFitsFrench() = everyPanel()
    @Test @Config(qualifiers = PHONE, fontScale = 1.5f) fun everyTextFitsInLargeText() = everyPanel()
    @Test @Config(qualifiers = HALF) fun everyTextFitsBesideAMap() = everyPanel(beside = true)
    @Test @Config(qualifiers = HALF_FR) fun everyTextFitsBesideAMapFrench() = everyPanel(beside = true)
    @Test @Config(qualifiers = HALF_SHORT) fun everyTextFitsInSplitScreen() = everyPanel(beside = true)
    @Test @Config(qualifiers = HALF_SHORT_FR) fun everyTextFitsInSplitScreenFrench() = everyPanel(beside = true)
    @Test @Config(qualifiers = WIDE) fun everyTextFitsOnItsSide() = everyPanel()
    @Test @Config(qualifiers = WIDE_FR) fun everyTextFitsOnItsSideFrench() = everyPanel()
    @Test @Config(qualifiers = WIDE_HALF) fun everyTextFitsOnItsSideInSplitScreen() = everyPanel(beside = true)
    @Test @Config(qualifiers = WIDE_HALF_FR) fun everyTextFitsOnItsSideInSplitScreenFrench() = everyPanel(beside = true)

    private fun everyPanel(beside: Boolean = false) {
        var s by mutableStateOf(mapMedia)
        var colors: ColorScheme? = null
        rule.show {
            colors = MaterialTheme.colorScheme
            CarModeScreen(s, actions)
        }
        val problems = LinkedHashSet<String>()
        val songs = listOf(song, longSong, CarMusicUi(), CarMusicUi("Radio app"), CarMusicUi("Radio app", "Harvest Moon"))
        val letters = listOf(several, latest, off, none, locked, CarMessagesNow(CarMessagesNow.State.SHOWN, listOf(CarMessageLine("Marie", null)), locked = true))
        for (large in CarLarge.entries) for (info in CarInfo.entries) for (fourth in listOf(CarFourth.Action(CarAction.MUSIC_APP), navHome)) {
            val layout = CarLayout(large = large, info = info, fourth = fourth)
            val shows = CarScreenState(layout = layout, beside = beside)
            val forSongs = if (large == CarLarge.MEDIA || info == CarInfo.MEDIA) songs else songs.take(1)
            val forLetters = if (large == CarLarge.MESSAGES || info == CarInfo.LATEST_MESSAGE) letters else letters.take(1)
            for (m in forSongs) for (n in forLetters) for (route in listOf(false, true)) {
                s = shows.copy(music = m, messages = n, directions = route)
                rule.waitForIdle()
                problems += textProblems("$large/$info: ")
                problems += touchProblems()
                problems += colorProblems(colors!!)
            }
        }
        assertEquals(emptyList<String>(), problems.toList())
    }

    private fun touchProblems() = rule.touchTargetProblems()

    private fun walk(n: SemanticsNode, each: (SemanticsNode) -> Unit) {
        each(n)
        n.children.forEach { walk(it, each) }
    }

    private fun textNodes(): List<Pair<SemanticsNode, TextLayoutResult>> {
        val found = ArrayList<Pair<SemanticsNode, TextLayoutResult>>()
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()) { n ->
            val results = ArrayList<TextLayoutResult>()
            if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) == true) found += n to results.first()
        }
        return found
    }

    private fun texts() = textNodes().map { it.second }

    /** A text with an ellipsis, a word off its line, or part of it clipped by what holds it; and anything under 18sp. */
    private fun textProblems(where: String = ""): List<String> = textNodes().flatMap { (n, r) ->
        val t = r.layoutInput.text.text
        listOfNotNull(
            "$where'$t' overflows".takeIf { r.didOverflowHeight },
            "$where'$t' clipped".takeIf { n.boundsInRoot.height < n.size.height - 1 || n.boundsInRoot.width < n.size.width - 1 },
            "$where'$t' ${r.layoutInput.style.fontSize}".takeIf { r.layoutInput.style.fontSize.value < 18f },
        )
    }

    private fun colorProblems(c: ColorScheme): List<String> {
        val allowed = setOf(c.onBackground, c.onSurface, c.onSurfaceVariant, c.onPrimary)
        return textNodes().mapNotNull { (_, r) -> "'${r.layoutInput.text.text}' ${r.layoutInput.style.color}".takeIf { r.layoutInput.style.color !in allowed } }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun paletteHolds(dark: Boolean) {
        var c: ColorScheme? = null
        rule.show(dark = dark) { c = MaterialTheme.colorScheme }
        rule.waitForIdle()
        val s = c!!
        val pairs = listOf(
            "status" to (s.onBackground to s.background),
            "panel" to (s.onSurface to s.surfaceContainer),
            "second line" to (s.onSurfaceVariant to s.surfaceContainer),
            "tile" to (s.onSurface to s.surfaceContainerHigh),
            "media button" to (s.onSurface to s.surfaceContainerHighest),
            "map hint" to (s.onSurfaceVariant to s.surfaceContainerHigh),
            "talk" to (s.onPrimary to s.primary),
            "end car mode" to (s.onSurface to s.background),
        )
        for ((name, pair) in pairs) assertTrue("$name ${contrast(pair.first, pair.second)}", contrast(pair.first, pair.second) >= 4.5)
        assertTrue("end car mode's edge", contrast(s.outline, s.background) >= 3.0)
    }

    @Test fun theColoursReadInTheLightTheme() = paletteHolds(dark = false)
    @Test fun theColoursReadInTheDarkTheme() = paletteHolds(dark = true)

    // Settings › Car mode › Car screen.
    private fun rows(start: CarLayout, dark: Boolean = false): () -> CarLayout {
        var now = start
        rule.show(dark = dark) { Column { CarScreenRows(start, enabled = true) { now = it } } }
        return { now }
    }

    @Test fun theSettingsRows() { rows(CarLayout()); rule.shot("car_settings_rows") }
    @Test fun theSettingsRowsDark() { rows(mediaLatest.layout, dark = true); rule.shot("car_settings_rows_dark") }
    @Test @Config(qualifiers = FR) fun theSettingsRowsFrench() { rows(CarLayout()); rule.shot("car_settings_rows_fr") }

    @Test fun theSettingsPickTheLargeAndInformationPanels() {
        val now = rows(CarLayout())
        rule.onNodeWithText("Large panel").performClick()
        rule.onNodeWithText("Media player").performClick()
        rule.waitForIdle()
        assertEquals(CarLarge.MEDIA, now().large)
        rule.onNodeWithText("Information panel").performClick()
        rule.onNodeWithText("Latest message").performClick()
        rule.waitForIdle()
        assertEquals(CarInfo.LATEST_MESSAGE, now().info)
    }

    @Test fun theFourthButtonListHasTheActionsAndApps() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        addLauncher(ctx, "org.example.bus")
        val now = rows(CarLayout())
        rule.onNodeWithText("Fourth button").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Navigate home").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Navigate home").performClick()
        rule.waitForIdle()
        assertEquals(CarFourth.Action(CarAction.NAVIGATE_HOME), now().fourth)
    }

    @Test fun theAppListIsLauncherAppsWithoutRobot() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        addLauncher(ctx, "org.example.bus")
        addLauncher(ctx, ctx.packageName)
        addLauncher(ctx, "android")
        assertEquals(listOf("org.example.bus"), launcherApps(ctx).map { it.pkg })
    }

    /** Settings › Car mode › Apps: no more than eight kept, and untick takes one off. */
    @Test fun appsKeptForTheCarStopAtEight() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        addLauncher(ctx, "org.example.bus")
        val full = (1..CarLayout.MAX_APPS).map { MusicApp("org.example.a$it", "a".repeat(64), "Kept $it") }
        val now = rows(CarLayout(apps = full))
        rule.onNodeWithText("Apps for the car").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(launcherApps(ctx).single().label).assertIsNotEnabled()
        rule.onNodeWithText("Kept 1").performClick()
        rule.waitForIdle()
        assertEquals(CarLayout.MAX_APPS - 1, now().apps.size)
        assertTrue(now().apps.none { it.pkg == "org.example.a1" })
    }

    private fun addLauncher(ctx: android.content.Context, pkg: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(ctx.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main" }
        })
    }
}
