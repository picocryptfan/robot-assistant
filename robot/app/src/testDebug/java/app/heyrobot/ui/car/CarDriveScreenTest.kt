// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.core.car.CarBeside
import app.heyrobot.core.car.CarMusicChoice
import app.heyrobot.core.car.CarAction
import app.heyrobot.core.car.CarFourth
import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarLarge
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FR = "fr-rCA-w411dp-h891dp-xhdpi"
private const val HALF = "w411dp-h420dp-xhdpi"
private const val HALF_FR = "fr-rCA-w411dp-h420dp-xhdpi"
private const val WIDE = "w914dp-h359dp-xhdpi"
private const val WIDE_FR = "fr-rCA-w914dp-h359dp-xhdpi"
private const val WIDE_HALF = "w477dp-h359dp-xhdpi"
private const val WIDE_HALF_FR = "fr-rCA-w477dp-h359dp-xhdpi"

/**
 * What a real drive asked of the car screen (docs/pm/handoff-car-mode-drive-fixes.md): album art, the choosing line, Robot's
 * lines with no panel, Yes and Cancel, the Apps list, music or podcasts, the way back to the map, no header, icons only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CarDriveScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private val actions = CarScreenActions(
        talk = { calls += "talk" }, apps = { calls += "apps" }, settings = { calls += "settings" }, fourth = { calls += "fourth" },
        maps = { calls += "maps" }, openMusic = { calls += "open" }, yes = { calls += "yes" }, cancel = { calls += "cancel" },
        closeSheet = { calls += "close" }, pick = { _, row -> calls += "pick " + row.pkg }, addApps = { calls += "add" },
    )
    /** A plain picture: a square of two colours, so the goldens show where it sits. */
    private val art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, if (x + y < 64) AColor.rgb(180, 70, 40) else AColor.rgb(40, 90, 150))
    }
    private val song = CarMusicUi("Radio app", "Harvest Moon", playing = true, artist = "Neil Young")
    private val withArt = song.copy(art = art)
    private val withIcon = song.copy(icon = art)
    private val big = CarLayout(large = CarLarge.MEDIA, info = CarInfo.DIRECTIONS)
    private val small = CarLayout(large = CarLarge.MAP, info = CarInfo.MEDIA)
    private val reply = CarTurnUi(words = "Harvest Moon by Neil Young is playing in your radio app, and the next one is up after it.")
    private val listening = CarTurnUi(listening = true, words = "what's the weather like in")
    private val ask = CarTurnUi(ask = CarAskUi("Call Marie Martin?"))

    private fun draw(s: CarScreenState) { rule.show { CarModeScreen(s, actions) } }
    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    // Art: large with the words beside or under it, small beside the title; words never over it.
    @Test fun artInTheLargePanel() { draw(CarScreenState(music = withArt, layout = big)); rule.shot("car_art"); noProblems() }
    @Test @Config(qualifiers = FR) fun artInTheLargePanelFrench() { draw(CarScreenState(music = withArt, layout = big)); rule.shot("car_art_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun artOnItsSide() { draw(CarScreenState(music = withArt, layout = big)); rule.shot("car_art_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun artOnItsSideFrench() { draw(CarScreenState(music = withArt, layout = big)); rule.shot("car_art_landscape_fr"); noProblems() }
    @Test fun artBesideTheTitle() { draw(CarScreenState(music = withArt, layout = small)); rule.shot("car_art_small"); noProblems() }
    @Test @Config(qualifiers = HALF) fun artBesideTheMap() { draw(CarScreenState(music = withArt, layout = small, beside = true)); rule.shot("car_art_split"); noProblems() }
    @Test @Config(qualifiers = HALF_FR) fun artBesideTheMapFrench() { draw(CarScreenState(music = withArt, layout = small, beside = true)); rule.shot("car_art_split_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF) fun artBesideTheMapOnItsSide() { draw(CarScreenState(music = withArt, layout = small, beside = true)); rule.shot("car_art_split_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun artBesideTheMapOnItsSideFrench() { draw(CarScreenState(music = withArt, layout = small, beside = true)); rule.shot("car_art_split_landscape_fr"); noProblems() }

    @Test fun noArtShowsTheAppsIcon() { draw(CarScreenState(music = withIcon, layout = big)); rule.shot("car_no_art"); noProblems() }
    @Test @Config(qualifiers = FR) fun noArtShowsTheAppsIconFrench() { draw(CarScreenState(music = withIcon, layout = big)); rule.shot("car_no_art_fr"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun noArtOnItsSide() { draw(CarScreenState(music = withIcon, layout = big)); rule.shot("car_no_art_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_FR) fun noArtOnItsSideFrench() { draw(CarScreenState(music = withIcon, layout = big)); rule.shot("car_no_art_landscape_fr"); noProblems() }

    // The picture is the card, not an icon in it (the user, 2 Nov 2026).
    private fun artNode() = rule.onNodeWithContentDescription("Album art", substring = true, useUnmergedTree = true).fetchSemanticsNode()
    @Test fun theArtFillsTheLargeCard() {
        draw(CarScreenState(music = withArt, layout = big))
        val b = artNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("art $b in $root", b.width >= root.width - 80f && b.height > b.width)
    }
    @Test @Config(qualifiers = HALF) fun theArtFillsTheCardBesideTheMap() {
        draw(CarScreenState(music = withArt, layout = small, beside = true))
        val b = artNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("art $b in $root", b.width >= root.width - 80f && b.height > 400f)
    }
    @Test fun aTurnInTheCardTakesTheArtAway() {
        draw(CarScreenState(music = withArt, layout = small, turn = CarTurnUi(words = "Twelve degrees")))
        rule.onAllNodesWithContentDescription("Album art", useUnmergedTree = true).assertCountEquals(0)
    }

    /** The words stay white whatever the theme, and the picture shows, with the scrim, in the golden. */
    private fun over(color: Int, dark: Boolean, name: String) {
        val picture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        rule.show(dark = dark) { CarModeScreen(CarScreenState(music = song.copy(art = picture), layout = big), actions) }
        rule.waitForIdle()
        val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("Harvest Moon", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals(Color.White, results.first().layoutInput.style.color)
        rule.shot(name)
        noProblems()
    }
    @Test fun overAWhitePicture() = over(android.graphics.Color.WHITE, dark = false, "car_art_white")
    @Test fun overAWhitePictureDark() = over(android.graphics.Color.WHITE, dark = true, "car_art_white_dark")
    @Test fun overABlackPicture() = over(android.graphics.Color.BLACK, dark = false, "car_art_black")
    @Test fun overABlackPictureDark() = over(android.graphics.Color.BLACK, dark = true, "car_art_black_dark")

    /** The worst picture is a white one: the scrim leaves the words 4.5:1 on it, the title and the softer artist line. */
    @Test fun theScrimLeavesTheWordsReadableOverWhite() {
        val under = ArtScrim.compositeOver(Color.White)
        fun contrast(a: Color) = (a.luminance() + 0.05) / (under.luminance() + 0.05)
        assertTrue(contrast(Color.White) >= 4.5f)
        assertTrue(contrast(ArtSoft.compositeOver(under)) >= 4.5f)
    }

    @Test fun artIsNamedAndTheIconIsNot() {
        draw(CarScreenState(music = withArt, layout = big))
        rule.onNodeWithContentDescription("Album art", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun noArtNoPicture() {
        draw(CarScreenState(music = song, layout = big))
        rule.onAllNodesWithContentDescription("Album art", useUnmergedTree = true).assertCountEquals(0)
    }

    // The choosing line.
    @Test fun noMusicAppChosenAsksForOne() {
        draw(CarScreenState(layout = big))
        rule.onNodeWithText("Choose your music app").performClick()
        assertEquals(listOf("open"), calls)
        rule.shot("car_choose_line")
    }
    @Test @Config(qualifiers = FR) fun noMusicAppChosenAsksForOneFrench() { draw(CarScreenState(layout = big)); rule.onNodeWithText("Choisis ton appli de musique").assertExists(); rule.shot("car_choose_line_fr"); noProblems() }
    @Test @Config(qualifiers = HALF) fun chooseLineBesideTheMap() { draw(CarScreenState(layout = small, beside = true)); rule.shot("car_choose_line_split"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun chooseLineOnItsSide() { draw(CarScreenState(layout = big)); rule.shot("car_choose_line_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun chooseLineBesideTheMapOnItsSideFrench() { draw(CarScreenState(layout = small, beside = true)); rule.shot("car_choose_line_split_landscape_fr"); noProblems() }

    // Listening and reply lines.
    private val listeningNow = CarScreenState(music = song, layout = small, turn = listening)
    private val replyNow = CarScreenState(music = song, layout = small, turn = reply)
    @Test fun listeningLine() {
        draw(listeningNow)
        rule.onNodeWithText("what's the weather like in").assertExists()
        // Talk says so to a screen reader, and has no words on it.
        rule.onNodeWithContentDescription("Listening…").assertExists()
        rule.shot("car_listening")
        noProblems()
    }
    @Test @Config(qualifiers = FR) fun listeningLineFrench() { draw(listeningNow.copy(turn = CarTurnUi(listening = true))); rule.onNodeWithText("À l’écoute…").assertExists(); rule.shot("car_listening_fr"); noProblems() }
    @Test @Config(qualifiers = HALF) fun listeningBesideTheMap() { draw(listeningNow.copy(beside = true)); rule.shot("car_listening_split"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun listeningOnItsSide() { draw(listeningNow); rule.shot("car_listening_landscape"); noProblems() }
    @Test fun replyLine() { draw(replyNow); rule.shot("car_reply"); noProblems() }
    @Test @Config(qualifiers = FR) fun replyLineFrench() { draw(replyNow.copy(turn = CarTurnUi(words = "Il fait 12 degrés à Halifax, avec un peu de pluie en fin de journée et du vent."))); rule.shot("car_reply_fr"); noProblems() }
    @Test @Config(qualifiers = HALF) fun replyBesideTheMap() { draw(replyNow.copy(beside = true)); rule.shot("car_reply_split"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun replyBesideTheMapOnItsSideFrench() { draw(replyNow.copy(beside = true, turn = CarTurnUi(words = "Il fait 12 degrés à Halifax, avec un peu de pluie en fin de journée et du vent."))); rule.shot("car_reply_split_landscape_fr"); noProblems() }

    // Yes and Cancel.
    private val askNow = CarScreenState(music = song, layout = small, turn = ask)
    @Test fun aCardNeedingAYesShowsBigYesAndCancel() {
        draw(askNow)
        rule.onNodeWithContentDescription("Yes").assertHeightIsAtLeast(72.dp).performClick()
        rule.onNodeWithContentDescription("Cancel").assertHeightIsAtLeast(72.dp).performClick()
        assertEquals(listOf("yes", "cancel"), calls)
        rule.shot("car_ask")
        noProblems()
    }
    @Test fun aCardComesBeforeASheet() {
        draw(askNow.copy(sheet = CarSheet.Apps(emptyList())))
        rule.onNodeWithContentDescription("Yes").assertHeightIsAtLeast(72.dp)
    }

    @Test fun aRefusalComesBeforeASheet() {
        draw(CarScreenState(music = song, layout = small, said = "Unlock once", sheet = CarSheet.Apps(emptyList())))
        rule.onNodeWithText("Unlock once").assertExists()
    }

    @Test fun aListOfChoicesIsAnsweredAloudSoItHasNoYes() {
        draw(askNow.copy(turn = CarTurnUi(ask = CarAskUi("Which Marie?", canYes = false))))
        rule.onAllNodesWithContentDescription("Yes").assertCountEquals(0)
        rule.onNodeWithContentDescription("Cancel").assertExists()
    }
    // fix-car-long-card: a long card scrolls in the pane, whole; one only a tap answers says Approve.
    @Test fun aLongCardThatTakesATapShowsWholeWithApprove() { draw(askNow.copy(turn = CarTurnUi(ask = CarAskUi("Run in Termux?\n" + "ls -la ".repeat(40), whole = false, tapOnly = true)))); rule.onNodeWithContentDescription("Approve").assertExists(); rule.onNodeWithContentDescription("Cancel").assertExists(); noProblems() }
    @Test @Config(qualifiers = FR) fun aLongCardThatTakesATapFrench() { draw(askNow.copy(turn = CarTurnUi(ask = CarAskUi("Lancer dans Termux ?\n" + "ls -la ".repeat(40), whole = false, tapOnly = true)))); rule.onNodeWithContentDescription("Approuver").assertExists(); noProblems() }
    @Test @Config(qualifiers = FR) fun yesAndCancelFrench() { draw(askNow.copy(turn = CarTurnUi(ask = CarAskUi("Appeler Marie Martin ?")))); rule.onNodeWithContentDescription("Oui").assertExists(); rule.shot("car_ask_fr"); noProblems() }
    @Test @Config(qualifiers = HALF) fun yesAndCancelBesideTheMap() { draw(askNow.copy(beside = true)); rule.shot("car_ask_split"); noProblems() }
    @Test @Config(qualifiers = WIDE) fun yesAndCancelOnItsSide() { draw(askNow); rule.shot("car_ask_landscape"); noProblems() }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun yesAndCancelBesideTheMapOnItsSideFrench() { draw(askNow.copy(beside = true, turn = CarTurnUi(ask = CarAskUi("Appeler Marie Martin ?")))); rule.shot("car_ask_split_landscape_fr"); noProblems() }

    // What Robot has to say takes the information panel for a few seconds: no strip at the top.
    @Test fun whatRobotSaysIsInTheInformationPanel() {
        draw(CarScreenState(music = song, layout = small, said = "That app is closed during this lock."))
        rule.onNodeWithText("That app is closed during this lock.").assertExists()
        rule.onAllNodesWithText("Harvest Moon").assertCountEquals(0)
        rule.shot("car_said")
    }

    // Header: nothing above the panels, and the key on Talk.
    @Test fun noHeaderAndNoEndButton() {
        draw(CarScreenState(music = song))
        rule.onAllNodesWithText("End car mode").assertCountEquals(0)
        rule.onAllNodesWithText("Say “hey robot”, or tap Talk.").assertCountEquals(0)
        val map = rule.onNodeWithContentDescription("Open maps beside Robot").getUnclippedBoundsInRoot()
        assertTrue("the first panel is at the top: ${map.top}", map.top < 80.dp)
    }

    @Test fun theKeyIsOnTalkInTheTrustedSessionOnly() {
        draw(CarScreenState(music = song, trusted = true))
        val talk = rule.onNodeWithContentDescription("Talk").getUnclippedBoundsInRoot()
        val key = rule.onNodeWithContentDescription("Unlocked features on", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(key.left >= talk.left && key.right <= talk.right && key.top >= talk.top && key.bottom <= talk.bottom)
        rule.shot("car_trusted_key")
    }

    @Test fun noKeyOutsideTheTrustedSession() {
        draw(CarScreenState(music = song))
        rule.onAllNodesWithContentDescription("Unlocked features on", useUnmergedTree = true).assertCountEquals(0)
    }

    // Apps.
    @Test fun appsWithNoneOffersAddApps() {
        draw(CarScreenState(sheet = CarSheet.Apps(emptyList())))
        rule.onNodeWithContentDescription("Add apps").performClick()
        assertEquals(listOf("add"), calls)
        rule.shot("car_apps_none")
    }
    @Test fun appsWithSomeOpensThem() {
        val sheet = CarSheet.Apps(listOf(CarAppRow("org.example.bus", "Bus times", art), CarAppRow("org.example.gas", "Gas prices")))
        draw(CarScreenState(sheet = sheet))
        rule.onNodeWithContentDescription("Add apps").assertDoesNotExist()
        rule.onNodeWithContentDescription("Bus times").assertHeightIsAtLeast(72.dp).performClick()
        rule.onNodeWithContentDescription("Gas prices").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("pick org.example.bus", "pick org.example.gas", "close"), calls)
        rule.shot("car_apps_some")
        noProblems()
    }
    @Test @Config(qualifiers = HALF_FR) fun appsBesideTheMapFrench() {
        draw(CarScreenState(beside = true, sheet = CarSheet.Apps(listOf(CarAppRow("org.example.bus", "Horaire des autobus", art)))))
        rule.shot("car_apps_split_fr")
        noProblems()
    }

    // Music or podcasts.
    @Test fun musicOrPodcastsIsTwoBigButtonsNamedAfterTheApps() {
        draw(CarScreenState(sheet = CarSheet.MusicOrPodcasts(CarAppRow("org.example.music", "Radio app", art), CarAppRow("org.example.pods", "Podcast app", art))))
        rule.onNodeWithText("Music or podcasts?").assertExists()
        rule.onNodeWithContentDescription("Radio app").assertHeightIsAtLeast(72.dp).performClick()
        rule.onNodeWithContentDescription("Podcast app").assertHeightIsAtLeast(72.dp).performClick()
        assertEquals(listOf("pick org.example.music", "pick org.example.pods"), calls)
        rule.shot("car_music_or_podcasts")
        noProblems()
    }
    @Test @Config(qualifiers = WIDE_HALF_FR) fun musicOrPodcastsBesideTheMapFrench() {
        draw(CarScreenState(beside = true, sheet = CarSheet.MusicOrPodcasts(CarAppRow("a.b", "Appli radio", art), CarAppRow("c.d", "Appli balados", art))))
        rule.shot("car_music_or_podcasts_split_landscape_fr")
        noProblems()
    }

    // The way back to the map, in the fourth button's place or in the information panel.
    @Test @Config(qualifiers = HALF) fun theFourthButtonBecomesMap() {
        draw(CarScreenState(music = song, beside = true, back = CarBeside.Back.FOURTH))
        rule.onNodeWithContentDescription("Map").performClick()
        assertEquals(listOf("fourth"), calls)
        rule.onAllNodesWithContentDescription("Music").assertCountEquals(0)
        rule.onAllNodesWithText("Map").assertCountEquals(0)
        rule.shot("car_map_back_fourth")
    }
    @Test @Config(qualifiers = HALF) fun otherwiseMapJoinsTheInformationPanel() {
        val bus = CarFourth.App(MusicApp("org.example.bus", "a".repeat(64), "Bus times"))
        draw(CarScreenState(music = song, beside = true, back = CarBeside.Back.INFO, layout = CarLayout(fourth = bus)))
        rule.onNodeWithContentDescription("Map").performClick()
        assertEquals(listOf("maps"), calls)
        rule.onNodeWithContentDescription("Bus times").assertExists()
        rule.shot("car_map_back_info")
    }
    @Test @Config(qualifiers = HALF) fun afterARouteItReadsBackToDirections() {
        draw(CarScreenState(music = song, beside = true, directions = true, back = CarBeside.Back.FOURTH))
        rule.onNodeWithContentDescription("Back to directions").assertExists()
    }
    @Test @Config(qualifiers = HALF) fun mapIsGoneOnceTheMapIsBack() {
        draw(CarScreenState(music = song, beside = true, back = CarBeside.Back.NONE))
        rule.onAllNodesWithContentDescription("Map").assertCountEquals(0)
        rule.onNodeWithContentDescription("Music").assertExists()
    }

    // The Maps button beside the mic, while your map is beside this screen: it names your music app and opens it there.
    private val radio = MusicApp("org.example.radio", "b".repeat(64), "Harbour Radio")
    private val mapsFourth = CarLayout(fourth = CarFourth.Action(CarAction.MAPS))
    @Test @Config(qualifiers = HALF) fun theMapsButtonNamesYourMusicAppWhileTheMapIsBeside() {
        draw(CarScreenState(music = song, beside = true, layout = mapsFourth, swap = CarMusicChoice.Open(radio), swapIcon = art))
        rule.onNodeWithContentDescription("Harbour Radio").performClick()
        assertEquals(listOf("fourth"), calls)
        rule.onAllNodesWithContentDescription("Maps").assertCountEquals(0)
        rule.onAllNodesWithText("Harbour Radio").assertCountEquals(0)
        rule.shot("car_maps_swap_music")
    }
    @Test @Config(qualifiers = HALF_FR) fun theMapsButtonNamesYourMusicAppWhileTheMapIsBesideFrench() {
        draw(CarScreenState(music = song, beside = true, layout = mapsFourth, swap = CarMusicChoice.Open(radio)))
        rule.onNodeWithContentDescription("Harbour Radio").assertExists()
        rule.onAllNodesWithContentDescription("Cartes").assertCountEquals(0)
    }
    @Test @Config(qualifiers = HALF) fun beforeItsIconLoadsTheSwapNamesTheAppWithNoPlayIcon() {
        // First frame, no icon yet: TalkBack already says the app's name, and the stand-in icon isn't Play.
        draw(CarScreenState(music = song, beside = true, layout = mapsFourth, swap = CarMusicChoice.Open(radio), swapIcon = null))
        rule.onNodeWithContentDescription("Harbour Radio").assertExists()
        rule.onAllNodesWithContentDescription("Play").assertCountEquals(0)
        assertEquals(app.heyrobot.ui.common.RIcons.Apps, swapPlaceholder)
        assertNotEquals(app.heyrobot.ui.common.RIcons.Play, swapPlaceholder)
    }
    @Test @Config(qualifiers = HALF) fun theMapsButtonIsMapsWhileYourMusicAppIsBeside() {
        draw(CarScreenState(music = song, beside = true, layout = mapsFourth, swap = null))
        rule.onNodeWithContentDescription("Maps").performClick()
        assertEquals(listOf("fourth"), calls)
        rule.onAllNodesWithContentDescription("Harbour Radio").assertCountEquals(0)
    }
    @Test @Config(qualifiers = HALF) fun theMapsButtonAsksWithBothAppsSet() {
        draw(CarScreenState(music = song, beside = true, layout = mapsFourth, swap = CarMusicChoice.Ask(radio, MusicApp("org.example.casts", "c".repeat(64), "Casts"))))
        rule.onNodeWithContentDescription("Music").assertExists()
        rule.onAllNodesWithContentDescription("Maps").assertCountEquals(0)
    }

    // Icons only, each with its spoken name in both languages.
    @Test fun theRowSpeaksItsNames() {
        draw(CarScreenState(music = song))
        for (d in listOf("Talk", "Music", "Apps", "Settings")) { rule.onNodeWithContentDescription(d).assertExists(); rule.onAllNodesWithText(d).assertCountEquals(0) }
    }
    @Test @Config(qualifiers = FR) fun theRowSpeaksItsNamesFrench() {
        draw(CarScreenState(music = song))
        for (d in listOf("Parler", "Musique", "Applis", "Réglages")) { rule.onNodeWithContentDescription(d).assertExists(); rule.onAllNodesWithText(d).assertCountEquals(0) }
    }
    @Test fun theFourthButtonSetToAnAppShowsItsOwnIcon() {
        val bus = CarFourth.App(MusicApp("org.example.bus", "a".repeat(64), "Bus times"))
        draw(CarScreenState(music = song, layout = CarLayout(fourth = bus), fourthIcon = art))
        rule.onNodeWithContentDescription("Bus times").assertExists()
        rule.shot("car_fourth_app_icon")
    }
}
