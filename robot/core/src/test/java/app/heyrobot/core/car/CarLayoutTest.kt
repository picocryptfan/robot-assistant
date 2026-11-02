// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.messages.IncomingMessage
import app.heyrobot.core.messages.HiddenWhy
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessagesStatus
import app.heyrobot.core.output.CarFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLayoutTest {
    private val pin = MusicApp("org.example.radio", "a".repeat(64), "Radio")

    @Test fun theHomeButtonIsGone() {
        assertEquals(listOf("TALK", "FOURTH", "APPS", "SETTINGS"), CarScreen.BUTTONS.map { it.name })
    }

    @Test fun theDefaultsAreMapMediaAndYourMusicApp() {
        val d = CarLayout()
        assertEquals(CarLarge.MAP, d.large)
        assertEquals(CarInfo.MEDIA, d.info)
        assertEquals(CarFourth.Action(CarAction.MUSIC_APP), d.fourth)
        assertEquals(d, CarSettings().layout)
        assertEquals(d, CarSettings.fromJson(JSONObject().put("startOnConnect", false)).layout)
    }

    @Test fun theLayoutComesBackFromCarJson() {
        for (l in listOf(CarLayout(CarLarge.MESSAGES, CarInfo.DIRECTIONS, CarFourth.Action(CarAction.NAVIGATE_HOME)), CarLayout(CarLarge.MEDIA, CarInfo.LATEST_MESSAGE, CarFourth.App(pin)))) {
            assertEquals(l, CarFile.decode(CarFile(true, CarSettings(layout = l)).encode()).settings.layout)
        }
    }

    @Test fun whatThisBuildDoesntKnowReadsAsTheDefault() {
        val l = CarLayout.fromJson(JSONObject().put("large", "HOLOGRAM").put("info", 7).put("fourth", "TOASTER"))
        assertEquals(CarLayout(), l)
    }

    @Test fun aDamagedPinIsNoPin() {
        val bad = JSONObject().put("fourthApp", JSONObject().put("package", "../etc").put("sha256", "nope").put("label", "x"))
        assertEquals(CarFourth.Action(CarAction.MUSIC_APP), CarLayout.fromJson(bad).fourth)
    }

    @Test fun aPanelNeverShowsTwice() {
        assertEquals(CarInfo.DIRECTIONS, CarLayout(CarLarge.MEDIA, CarInfo.MEDIA).infoNow(beside = false))
        assertEquals(CarInfo.MEDIA, CarLayout(CarLarge.MESSAGES, CarInfo.LATEST_MESSAGE).infoNow(beside = false))
        assertEquals(CarInfo.LATEST_MESSAGE, CarLayout(CarLarge.MAP, CarInfo.LATEST_MESSAGE).infoNow(beside = false))
    }

    @Test fun inSplitScreenTheOtherAppIsTheLargeHalfAndYourChoiceMovesToTheInformationPanel() {
        assertNull(CarLayout().largeNow(beside = true))
        assertEquals(CarLarge.MAP, CarLayout().largeNow(beside = false))
        assertEquals(CarInfo.MEDIA, CarLayout(CarLarge.MEDIA, CarInfo.DIRECTIONS).infoNow(beside = true))
        assertEquals(CarInfo.LATEST_MESSAGE, CarLayout(CarLarge.MESSAGES, CarInfo.MEDIA).infoNow(beside = true))
        assertEquals(CarInfo.DIRECTIONS, CarLayout(CarLarge.MAP, CarInfo.DIRECTIONS).infoNow(beside = true))
    }

    @Test fun messagesAreAskedForOnlyWhenShown() {
        assertFalse(CarLayout().showsMessages(beside = false))
        assertTrue(CarLayout(CarLarge.MESSAGES).showsMessages(beside = false))
        assertTrue(CarLayout(CarLarge.MAP, CarInfo.LATEST_MESSAGE).showsMessages(beside = true))
        assertFalse(CarLayout(CarLarge.MEDIA, CarInfo.DIRECTIONS).showsMessages(beside = true))
    }

    @Test fun settingsNeverOpenOverTheLockScreen() {
        assertEquals(CarOpen.Gate.UNLOCK, CarOpen.settings(keyguard = true))
        assertEquals(CarOpen.Gate.OPEN, CarOpen.settings(keyguard = false))
    }

    private fun msg(text: String, time: Long, fromMe: Boolean = false, historic: Boolean = false) =
        IncomingMessage("i$time", MessageApp.CHEOGRAM, "c", "Marie", null, text, time, false, true, fromMe = fromMe, historic = historic)

    private fun thread(id: String, title: String?, vararg m: IncomingMessage, hidden: HiddenWhy? = null) =
        MessageThread(MessageApp.CHEOGRAM, id, "k$id", title, false, m.toList(), null, null, hidden, 1L)

    private val on = MessagesStatus(on = true, access = true, connected = true, locked = false, apps = 1)

    @Test fun theNewestConversationComesFirstAndYourOwnWordsAreLeftOut() {
        val now = CarMessagesNow.of(on, listOf(thread("a", "Pierre", msg("old", 5)), thread("b", "Marie", msg("newest", 9), msg("mine", 20, fromMe = true)), thread("c", "Only me", msg("x", 30, fromMe = true))))
        assertEquals(CarMessagesNow.State.SHOWN, now.state)
        assertEquals(listOf(CarMessageLine("Marie", "newest"), CarMessageLine("Pierre", "old")), now.lines)
    }

    @Test fun earlierContextIsNotTheLatestMessage() {
        assertEquals(CarMessagesNow.State.NONE, CarMessagesNow.of(on, listOf(thread("a", "Marie", msg("context", 5, historic = true)))).state)
    }

    @Test fun hiddenOrLockedTextIsNotShownButWhoIs() {
        val hidden = CarMessagesNow.of(on, listOf(thread("a", "Bank", msg("New message", 5), hidden = HiddenWhy.APP_PRIVACY)))
        assertEquals(listOf(CarMessageLine("Bank", null)), hidden.lines)
        val locked = CarMessagesNow.of(on.copy(locked = true), listOf(thread("a", "Marie", msg("", 5))))
        assertEquals(listOf(CarMessageLine("Marie", null)), locked.lines)
        assertTrue(locked.locked)
    }

    @Test fun offNoAccessAndLockedSayWhy() {
        assertEquals(CarMessagesNow.State.OFF, CarMessagesNow.of(on.copy(on = false), emptyList()).state)
        assertEquals(CarMessagesNow.State.OFF, CarMessagesNow.of(on.copy(access = false), listOf(thread("a", "Marie", msg("hi", 5)))).state)
        assertEquals(CarMessagesNow.State.LOCKED, CarMessagesNow.of(on.copy(locked = true), emptyList()).state)
        assertEquals(CarMessagesNow.State.NONE, CarMessagesNow.of(on, emptyList()).state)
    }

    @Test fun aMessageIsCleanedCutAndNeverInAToString() {
        val long = "x".repeat(CarMessagesNow.MAX_TEXT + 50)
        val line = CarMessagesNow.of(on, listOf(thread("a", "Ma\u202Erie", msg("a\u0000b\n$long", 5)))).lines.single()
        assertEquals("Ma rie", line.who)
        assertEquals(CarMessagesNow.MAX_TEXT, line.text!!.length)
        assertTrue(line.text!!.startsWith("a b "))
        assertFalse(line.toString().contains("x"))
        assertEquals(3, CarMessagesNow.of(on, (1..5).map { thread("$it", "P$it", msg("m", it.toLong())) }).lines.size)
    }

    @Test fun directionsComeOnlyFromYourMapsAppsNavigationNotification() {
        val maps = "org.example.maps"
        assertEquals("Turn left · 200 m", CarDirectionText.of(maps, maps, "navigation", " Turn left ", "200 m".replace(' ', ' ')))
        assertNull(CarDirectionText.of("org.example.other", maps, "navigation", "Turn left", null))
        assertNull(CarDirectionText.of(maps, null, "navigation", "Turn left", null))
        assertNull(CarDirectionText.of(maps, maps, "msg", "Turn left", null))
        assertNull(CarDirectionText.of(maps, maps, "navigation", " \u200B", null))
        assertEquals(CarDirectionText.MAX, CarDirectionText.of(maps, maps, "navigation", "y".repeat(500), null)!!.length)
    }

    @Test fun aDefaultLayoutLeavesTheFileAsItWas() {
        assertEquals(false, CarSettings().toJson().has("layout"))
    }

    @Test fun aRestoreNeverBringsAPinnedAppOrAMessagesPanel() {
        val theirs = CarFile(true, CarSettings(layout = CarLayout(large = CarLarge.MESSAGES, fourth = CarFourth.App(MusicApp("org.example.bus", "a".repeat(64), "Bus")))))
        for (mine in listOf(false, true))
            assertEquals(CarLayout(), app.heyrobot.core.backup.RestoreSafety.car(CarFile(), theirs, mine).value.settings.layout)
    }

}
