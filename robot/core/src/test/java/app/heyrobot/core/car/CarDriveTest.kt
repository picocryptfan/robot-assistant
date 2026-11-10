// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.headset.RoutePlanner
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.EverydaySettingsCodec
import app.heyrobot.core.everyday.EverydaySettingsFile
import app.heyrobot.core.everyday.MusicApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDriveTest {
    private val self = "app.heyrobot.assistant"
    private val music = MusicApp("org.example.music", "ab".repeat(32), "Music")
    private val podcast = MusicApp("org.example.casts", "cd".repeat(32), "Casts")
    private val voiceNote = MediaNow("org.example.messenger", false, "Voice note", null)

    @Test fun artFollowsTheOrderAlbumThenArtThenIcon() {
        assertEquals("album", CarArt.pick(listOf("album", "art", "icon"), emptyList()) { "address" })
        assertEquals("art", CarArt.pick(listOf(null, "art", "icon"), emptyList()) { "address" })
        assertEquals("icon", CarArt.pick(listOf(null, null, "icon"), listOf("content://a")) { "address" })
    }

    @Test fun artNeverComesFromTheInternet() {
        val asked = mutableListOf<String>()
        val got = CarArt.pick(listOf<String?>(null), listOf("https://example.org/a.jpg", "http://example.org/b.jpg", "HTTP://example.org/c")) { asked += it; it }
        assertNull(got)
        assertEquals(emptyList<String>(), asked)
        assertEquals("content://a/1", CarArt.address(listOf("http://example.org/b.jpg", null, "file:///sdcard/x.jpg", "content://a/1")))
    }

    @Test fun artIsAtMost512() {
        assertEquals(512 to 256, CarArt.scaled(1024, 512))
        assertEquals(1 to 512, CarArt.scaled(2, 4000))
        assertEquals(100 to 90, CarArt.scaled(100, 90))
    }

    @Test fun artIsDroppedOnANewSongAndOnStop() {
        val hold = CarArtHold<Any>()
        val first = Any()
        var loads = 0
        assertSame(first, hold.next("song 1") { loads++; first })
        assertSame(first, hold.next("song 1") { loads++; Any() })
        assertEquals(1, loads)
        assertNull(hold.next("song 2") { null })
        assertNull(hold.next("song 1") { null })
        hold.next("song 1") { first }
        assertNull(hold.next(null) { first })
        val again = Any()
        assertSame(again, hold.next("song 1") { again })
        hold.drop()
        assertNull(hold.next(null) { again })
    }

    @Test fun aPausedSessionFromBeforeTheDriveDoesntTakeTheTile() {
        assertEquals(MusicTile.Pinned(music), CarMusicTile.of(listOf(voiceNote), music, AppPin.OK, self))
    }

    @Test fun yourMusicAppShowsItsPausedSong() {
        val t = CarMusicTile.of(listOf(voiceNote, MediaNow(music.pkg, false, "Song", "Band")), music, AppPin.OK, self)
        assertEquals(MusicTile.Session(music.pkg, false, "Song", "Band"), t)
    }

    @Test fun aSessionSeenPlayingThisDriveComesBack() {
        val cast = MediaNow(podcast.pkg, false, "Episode", null)
        assertEquals(MusicTile.Session(podcast.pkg, false, "Episode", null), CarMusicTile.of(listOf(voiceNote, MediaNow(music.pkg, false, "Song", null), cast), music, AppPin.OK, self, setOf(podcast.pkg)))
    }

    @Test fun whatPlaysAlwaysWins() {
        val t = CarMusicTile.of(listOf(MediaNow(podcast.pkg, true, "Episode", null), MediaNow(music.pkg, false, "Song", null)), music, AppPin.OK, self)
        assertEquals(podcast.pkg, t.pkg)
    }

    @Test fun noMusicAppOffersTheChoice() {
        assertEquals(MusicTile.Choose, CarMusicTile.of(listOf(voiceNote), null, null, self))
        assertEquals(MusicTile.Choose, CarMusicTile.of(emptyList(), music, AppPin.GONE, self))
    }

    @Test fun musicOrPodcasts() {
        assertEquals(CarMusicChoice.Ask(music, podcast), CarMusicChoice.of(music, podcast))
        assertEquals(CarMusicChoice.Open(music), CarMusicChoice.of(music, null))
        assertEquals(CarMusicChoice.Open(podcast), CarMusicChoice.of(null, podcast))
        assertEquals(CarMusicChoice.Open(music), CarMusicChoice.of(music, music))
        assertEquals(CarMusicChoice.ChooseOne, CarMusicChoice.of(null, null))
    }

    @Test fun whatsPlayingOpensWithNoQuestion() {
        assertNull(CarMusicChoice.forTile(true, music.pkg, music, podcast))
        assertNull(CarMusicChoice.forTile(false, "org.example.other", music, podcast))
        assertEquals(CarMusicChoice.Ask(music, podcast), CarMusicChoice.forTile(false, music.pkg, music, podcast))
        assertEquals(CarMusicChoice.Open(music), CarMusicChoice.forTile(false, music.pkg, music, null))
        assertEquals(CarMusicChoice.ChooseOne, CarMusicChoice.forTile(false, null, null, null))
    }

    private val fourthMusic = CarFourth.Action(CarAction.MUSIC_APP)
    private val fourthHome = CarFourth.Action(CarAction.NAVIGATE_HOME)
    private val other = CarBeside.Last.App(music.pkg)

    @Test fun mapShowsAfterAMediaAppOpenedBesideRobot() {
        assertEquals(CarBeside.Back.FOURTH, CarBeside.back(other, "org.example.maps", fourthMusic, CarLarge.MAP, beside = true))
        assertEquals(CarBeside.Back.INFO, CarBeside.back(other, "org.example.maps", fourthHome, CarLarge.MAP, beside = true))
        assertEquals(CarBeside.Back.INFO, CarBeside.back(other, null, CarFourth.App(music), CarLarge.MEDIA, beside = false))
    }

    @Test fun mapGoesOnceTheMapIsBack() {
        assertEquals(CarBeside.Back.NONE, CarBeside.back(CarBeside.Last.Map, "org.example.maps", fourthMusic, CarLarge.MAP, beside = true))
        assertEquals(CarBeside.Back.NONE, CarBeside.back(CarBeside.Last.App("org.example.maps"), "org.example.maps", fourthMusic, CarLarge.MAP, beside = true))
        assertEquals(CarBeside.Back.NONE, CarBeside.back(null, "org.example.maps", fourthMusic, CarLarge.MAP, beside = true))
    }

    @Test fun noSecondWayToTheMapWhenOneIsThere() {
        assertEquals(CarBeside.Back.NONE, CarBeside.back(other, null, CarFourth.Action(CarAction.MAPS), CarLarge.MAP, beside = true))
        assertEquals(CarBeside.Back.NONE, CarBeside.back(other, null, fourthMusic, CarLarge.MAP, beside = false))
    }

    // The Maps button beside the mic swaps the app beside Robot between your map and your music app.
    private val fourthMaps = CarFourth.Action(CarAction.MAPS)
    private val maps = "org.example.maps"

    @Test fun mapsButtonOpensYourMusicAppWhileTheMapIsBeside() {
        val one = CarMusicChoice.of(music, null)
        assertEquals(CarMusicChoice.Open(music), CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = true, one))
        assertEquals(CarMusicChoice.Open(music), CarBeside.swap(CarBeside.Last.App(maps), maps, fourthMaps, beside = true, one))
        // Both set: it asks which, as the fourth button set to your music app does.
        assertEquals(CarMusicChoice.Ask(music, podcast), CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = true, CarMusicChoice.of(music, podcast)))
    }

    @Test fun mapsButtonWithBothAppsOpensTheOneUsedThisDrive() {
        val both = CarMusicChoice.of(music, podcast)
        // Playing or paused on the tile wins, then the one Robot last put on top this drive.
        assertEquals(CarMusicChoice.Open(podcast), CarBeside.pick(both, podcast.pkg, listOf(music.pkg)))
        assertEquals(CarMusicChoice.Open(music), CarBeside.pick(both, music.pkg, listOf(podcast.pkg)))
        assertEquals(CarMusicChoice.Open(podcast), CarBeside.pick(both, null, listOf(music.pkg, podcast.pkg, "org.example.bus")))
        assertEquals(CarMusicChoice.Open(music), CarBeside.pick(both, "org.example.radio2", listOf(podcast.pkg, music.pkg)))
        assertEquals(CarMusicChoice.Open(podcast), CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = true, CarBeside.pick(both, null, listOf(podcast.pkg))))
        // Neither used this drive: it asks.
        assertEquals(both, CarBeside.pick(both, null, listOf("org.example.bus")))
        assertEquals(both, CarBeside.pick(both, "org.example.radio2", emptyList()))
        // One app set: nothing to pick.
        assertEquals(CarMusicChoice.Open(music), CarBeside.pick(CarMusicChoice.of(music, null), podcast.pkg, listOf(podcast.pkg)))
    }

    @Test fun mapsButtonBringsTheMapBackWhileAnotherAppIsBeside() {
        val one = CarMusicChoice.of(music, null)
        assertNull(CarBeside.swap(other, maps, fourthMaps, beside = true, one))
        assertNull(CarBeside.swap(CarBeside.Last.App("org.example.bus"), maps, fourthMaps, beside = true, one))
    }

    @Test fun mapsButtonShowsMapsWhenRobotDoesNotKnowWhatIsBeside() {
        val one = CarMusicChoice.of(music, null)
        // Nothing put beside this drive, or an app opened by voice with no package: a tap brings the map back, a sure result.
        assertNull(CarBeside.swap(null, maps, fourthMaps, beside = true, one))
        assertNull(CarBeside.swap(CarBeside.Last.Unknown, maps, fourthMaps, beside = true, one))
        assertEquals(CarBeside.Back.NONE, CarBeside.back(CarBeside.Last.Unknown, maps, fourthMusic, CarLarge.MAP, beside = true))
    }

    @Test fun anAppOpenedByVoiceIsRecordedAsTheButtonsAre() {
        assertEquals(CarBeside.Last.Map, CarBeside.opened(maps, maps))
        assertEquals(CarBeside.Last.App(music.pkg), CarBeside.opened(music.pkg, maps))
        assertEquals(CarBeside.Last.Unknown, CarBeside.opened(null, maps))
        assertEquals(CarBeside.Last.App(music.pkg), CarBeside.opened(music.pkg, null))
        // After "open <music app>" by voice the button shows Maps, not the app that's already on top.
        assertNull(CarBeside.swap(CarBeside.opened(music.pkg, maps), maps, fourthMaps, beside = true, CarMusicChoice.of(music, null)))
    }

    @Test fun mapsButtonIsAsBeforeWithNoMusicAppOrNothingBeside() {
        assertNull(CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = true, CarMusicChoice.of(null, null)))
        assertNull(CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = false, CarMusicChoice.of(music, null)))
        assertNull(CarBeside.swap(CarBeside.Last.Map, null, fourthMaps, beside = true, CarMusicChoice.of(music, null)))
        // Only the button set to Maps swaps; the one set to your music app already becomes Map (Back.FOURTH).
        assertNull(CarBeside.swap(CarBeside.Last.Map, maps, fourthMusic, beside = true, CarMusicChoice.of(music, null)))
        assertNull(CarBeside.swap(CarBeside.Last.Map, maps, fourthHome, beside = true, CarMusicChoice.of(music, null)))
    }

    @Test fun theFourthButtonsTapDoesWhatItShows() {
        val one = CarMusicChoice.of(music, null)
        val none = CarBeside.Back.NONE
        assertEquals(CarBeside.Tap.Maps, CarBeside.tap(fourthMaps, none, null, one))
        assertEquals(CarBeside.Tap.Follow(CarMusicChoice.Open(music)), CarBeside.tap(fourthMaps, none, CarMusicChoice.Open(music), one))
        assertEquals(CarBeside.Tap.Follow(CarMusicChoice.Ask(music, podcast)), CarBeside.tap(fourthMaps, none, CarMusicChoice.Ask(music, podcast), one))
        assertEquals(CarBeside.Tap.Maps, CarBeside.tap(fourthMusic, CarBeside.Back.FOURTH, null, one))
        assertEquals(CarBeside.Tap.Follow(one), CarBeside.tap(fourthMusic, CarBeside.Back.INFO, null, one))
        assertEquals(CarBeside.Tap.Home, CarBeside.tap(fourthHome, none, null, one))
        assertEquals(CarBeside.Tap.Follow(CarMusicChoice.Open(podcast)), CarBeside.tap(CarFourth.App(podcast), none, null, one))
    }

    @Test fun lockedTheMapsButtonsTapReachesTheLocksBeforeAnythingOpens() {
        // From what's beside Robot to the gate: the swap names your music app, the tap opens it, and the open meets CarOpen.app.
        val one = CarMusicChoice.of(music, null)
        val tap = CarBeside.tap(fourthMaps, CarBeside.Back.NONE, CarBeside.swap(CarBeside.Last.Map, maps, fourthMaps, beside = true, one), one)
        val pkg = (((tap as CarBeside.Tap.Follow).choice) as CarMusicChoice.Open).app.pkg
        assertEquals(music.pkg, pkg)
        assertEquals(CarOpen.Gate.UNLOCK, CarOpen.app(pkg, keyguard = true, trusted = false, phoneLock = false, lockApps = emptySet()))
        assertEquals(CarOpen.Gate.PHONE_LOCK, CarOpen.app(pkg, keyguard = false, trusted = false, phoneLock = true, lockApps = emptySet()))
        // An app Phone lock leaves open in the car opens.
        assertEquals(CarOpen.Gate.OPEN, CarOpen.app(pkg, keyguard = false, trusted = false, phoneLock = true, lockApps = setOf(pkg)))
        // A voice open on the lock screen records the app, so the next tap is Maps (CarOpen.maps' own gate).
        assertEquals(CarBeside.Tap.Maps, CarBeside.tap(fourthMaps, CarBeside.Back.NONE, CarBeside.swap(CarBeside.opened(pkg, maps), maps, fourthMaps, beside = true, one), one))
    }

    @Test fun recentsShowNoPictureOfWordsOrOverTheLockScreen() {
        assertTrue(CarRecents.picture(showsMessage = false, showsTurn = false, trusted = false))
        assertFalse(CarRecents.picture(showsMessage = true, showsTurn = false, trusted = false))
        assertFalse(CarRecents.picture(showsMessage = false, showsTurn = true, trusted = false))
        assertFalse(CarRecents.picture(showsMessage = false, showsTurn = false, trusted = true))
    }

    @Test fun keptAppsRoundTripAtMostEightAndNoTwice() {
        val nine = (1..9).map { MusicApp("org.example.a$it", "ab".repeat(32), "App $it") }
        val json = JSONObject(CarLayout(apps = nine + nine.first()).toJson().toString())
        val back = CarLayout.fromJson(json)
        assertEquals(CarLayout.MAX_APPS, back.apps.size)
        assertEquals(nine.take(8), back.apps)
        assertEquals(emptyList<MusicApp>(), CarLayout.fromJson(JSONObject()).apps)
        assertFalse(CarLayout().toJson().has("apps"))
    }

    @Test fun aKeptAppThatIsntValidIsLeftOut() {
        val o = JSONObject("""{"apps":[{"package":"org.example.a","sha256":"nope","label":"A"},{"package":"org.example.music","sha256":"${"ab".repeat(32)}","label":"Music"}]}""")
        assertEquals(listOf(music), CarLayout.fromJson(o).apps)
    }

    @Test fun thePodcastAppIsKept() {
        val raw = EverydaySettingsCodec.encode(EverydaySettings(podcastApp = podcast))
        assertEquals(podcast, (EverydaySettingsCodec.decode(raw) as EverydaySettingsFile.Ok).settings.podcastApp)
        assertNull((EverydaySettingsCodec.decode("""{"v":1}""") as EverydaySettingsFile.Ok).settings.podcastApp)
        assertEquals(EverydaySettingsFile.Damaged, EverydaySettingsCodec.decode("""{"v":1,"podcastApp":{"package":"x"}}"""))
    }

    @Test fun aSongWithNoPictureYetAsksAgain() {
        val hold = CarArtHold<String>()
        assertEquals(null, hold.next("song") { null })
        assertEquals("art", hold.next("song") { "art" })
        var asked = 0
        assertEquals("art", hold.next("song") { asked++; "other" })
        assertEquals(0, asked)
    }

    @Test fun aReplyStaysAFewSecondsAndListeningDoesnt() {
        val hold = CarLineHold<String>(6_000)
        assertEquals("Listening", hold.next("Listening", keep = false, nowMs = 0))
        assertEquals(null, hold.next(null, keep = false, nowMs = 1))
        assertEquals("Reply", hold.next("Reply", keep = true, nowMs = 10))
        assertEquals("Reply", hold.next(null, keep = false, nowMs = 6_009))
        assertEquals(null, hold.next(null, keep = false, nowMs = 6_010))
    }

    @Test fun carModeWithNothingConnectedListensOnThePhone() {
        val speaker = SeenDevice(RouteType.SPEAKER, "", "")
        val sco = SeenDevice(RouteType.BT_SCO, "00:11", "Earbuds")
        fun plan(vararg d: SeenDevice, call: Boolean = false, car: SeenDevice? = null) =
            RoutePlanner.plan(EntryKind.BVRA, d.toList(), DeviceMemory(), HeadsetSettings(), call, car = car)
        assertEquals(true, CarTurn.listensOnPhone(on = true, fromCar = true, decision = plan(speaker)))
        // A headset or the car takes the session, as before; a call and car mode being off change nothing.
        assertEquals(false, CarTurn.listensOnPhone(on = true, fromCar = true, decision = plan(speaker, sco)))
        assertEquals(false, CarTurn.listensOnPhone(on = true, fromCar = true, decision = plan(speaker, sco, car = sco)))
        assertEquals(false, CarTurn.listensOnPhone(on = true, fromCar = true, decision = plan(speaker, call = true)))
        assertEquals(false, CarTurn.listensOnPhone(on = false, fromCar = true, decision = plan(speaker)))
        assertEquals(false, CarTurn.listensOnPhone(on = true, fromCar = false, decision = plan(speaker)))
    }

    @Test fun onlyTheCarsOwnShowsWithTheScreenUpHaveNoPanel() {
        assertEquals(true, CarTurn.quiet(on = true, screenUp = true, trusted = true, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = false, screenUp = true, trusted = true, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = true, screenUp = false, trusted = true, windowNeeded = false))
        // Another app's showAssist always opens the panel: nothing listens unseen.
        assertEquals(false, CarTurn.quiet(on = true, screenUp = true, trusted = false, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = true, screenUp = true, trusted = true, windowNeeded = true))
    }

    /** fix-car-stay: the assist gesture or the power button with the car screen up: no panel, and the phone's mic listens. */
    @Test fun aQuietShowFromAVoiceWayInListensOnThePhone() {
        assertEquals(true, CarTurn.listensQuietly(quiet = true, handsFree = false, voice = true))
        assertEquals(false, CarTurn.listensQuietly(quiet = true, handsFree = true, voice = true))
        assertEquals(false, CarTurn.listensQuietly(quiet = true, handsFree = false, voice = false))
        assertEquals(false, CarTurn.listensQuietly(quiet = false, handsFree = false, voice = true))
    }

    /** fix-car-stay: a turn with no panel the phone's mic hears closes only once nothing listens, thinks, asks or speaks. */
    @Test fun aQuietTurnClosesOnlyWhenIdle() {
        assertEquals(true, CarTurnEnd.quietDone(quietTurn = true, handsFree = false, idle = true, speaking = false, waits = false))
        assertEquals(false, CarTurnEnd.quietDone(quietTurn = true, handsFree = false, idle = false, speaking = false, waits = false))
        assertEquals(false, CarTurnEnd.quietDone(quietTurn = true, handsFree = false, idle = true, speaking = true, waits = false))
        assertEquals(false, CarTurnEnd.quietDone(quietTurn = true, handsFree = true, idle = true, speaking = false, waits = false))
        assertEquals(false, CarTurnEnd.quietDone(quietTurn = false, handsFree = false, idle = true, speaking = false, waits = false))
        // fix-car-stay-b: a card waiting for a yes, or a listen due 350 ms after Robot asks, holds the turn while it's idle.
        assertEquals(false, CarTurnEnd.quietDone(quietTurn = true, handsFree = false, idle = true, speaking = false, waits = true))
    }

    /** The quiet check's polls as CarModeController runs them, each (a listen coming, idle, speaking): the poll the turn closes on, or -1. */
    private fun closesOn(polls: List<Triple<Boolean, Boolean, Boolean>>, sending: (Int) -> Boolean = { false }): Int {
        var held = 0
        polls.forEachIndexed { i, (coming, idle, speaking) ->
            held = CarTurnEnd.listenHeld(held, coming, idle, speaking)
            if (CarTurnEnd.quietDone(true, false, idle, speaking, CarTurnEnd.listenHolds(held) || sending(i))) return i
        }
        return -1
    }
    private val speech = Triple(true, false, true)
    private val gap = Triple(true, true, false)
    private val listening = Triple(false, false, false)
    private val idleNow = Triple(false, true, false)

    /** fix-car-stay-c: an answer read aloud for 2 to 8 polls, then the 350 ms before its listen: the turn holds (it closed as Robot asked). */
    @Test fun aLongAnswerThenTheGapBeforeItsListenHolds() {
        for (n in 1..8) assertEquals("speech over $n polls", -1, closesOn(List(n) { speech } + gap + listening))
    }

    /** fix-car-stay-c: a listen flag left set closes the turn by the third idle poll, after speech as without it. */
    @Test fun aStuckListenFlagClosesByTheThirdIdlePoll() {
        assertEquals(2, closesOn(List(5) { gap }))
        assertEquals(6, closesOn(List(4) { speech } + List(5) { gap }))
        assertEquals(0, closesOn(listOf(idleNow)))
    }

    /** fix-car-stay-d: a session's end waits while Robot speaks or a reply is in its 3 seconds (or on its way), then closes; never past its tries. */
    @Test fun aSessionEndWaitsForAReplyInItsWindow() {
        assertEquals(true, CarTurnEnd.closeWaits(speaking = false, sending = true, tries = 5))
        assertEquals(true, CarTurnEnd.closeWaits(speaking = true, sending = false, tries = 5))
        assertEquals(false, CarTurnEnd.closeWaits(speaking = false, sending = false, tries = 5))
        assertEquals(false, CarTurnEnd.closeWaits(speaking = true, sending = true, tries = 0))
    }

    /** fix-car-stay-c: the power button or the assist gesture on a locked phone keeps its panel, which waits for a tap. */
    @Test fun aSystemShowOnALockedPhoneKeepsItsPanel() {
        assertEquals(false, CarTurn.quietAllowed(trusted = true, systemShow = true, locked = true))
        assertEquals(true, CarTurn.quietAllowed(trusted = true, systemShow = true, locked = false))
        assertEquals(true, CarTurn.quietAllowed(trusted = true, systemShow = false, locked = true))
        assertEquals(false, CarTurn.quietAllowed(trusted = false, systemShow = false, locked = false))
    }

    /** fix-car-stay: the car screen out of sight just after it opened an app beside itself means Android put the app over it. */
    @Test fun theCarScreenCoveredRightAfterAnOpenComesBack() {
        assertEquals(true, CarSplit.covered(multiWindow = false, finishing = false, sinceOpen = 400))
        assertEquals(false, CarSplit.covered(multiWindow = true, finishing = false, sinceOpen = 400))
        assertEquals(false, CarSplit.covered(multiWindow = false, finishing = true, sinceOpen = 400))
        assertEquals(false, CarSplit.covered(multiWindow = false, finishing = false, sinceOpen = CarSplit.CHECK_MS))
        assertEquals(false, CarSplit.covered(multiWindow = false, finishing = false, sinceOpen = Long.MAX_VALUE / 2))
    }

    /** fix-car-stay-f: Robot's own close of a turn: the car screen's talk stops Robot's words; a question said in a reply's 3 seconds is answered first. */
    @Test fun hideWaitsForAReplyAndTheQuestionItStopped() {
        assertEquals(false, CarTurnEnd.hideWaits(words = false, speaking = true, sending = false, busy = false, replyHeld = false, tries = 5))
        assertEquals(true, CarTurnEnd.hideWaits(words = false, speaking = true, sending = true, busy = false, replyHeld = false, tries = 5))
        assertEquals(true, CarTurnEnd.hideWaits(words = true, speaking = true, sending = false, busy = false, replyHeld = false, tries = 5))
        assertEquals(true, CarTurnEnd.hideWaits(words = true, speaking = false, sending = false, busy = true, replyHeld = true, tries = 5))
        assertEquals(false, CarTurnEnd.hideWaits(words = true, speaking = false, sending = false, busy = true, replyHeld = false, tries = 5))
        assertEquals(false, CarTurnEnd.hideWaits(words = false, speaking = false, sending = false, busy = true, replyHeld = true, tries = 5))
        // fix-car-stay-g: the car screen's talk waits for the reply's answer line ("Sent.") once a reply held it.
        assertEquals(true, CarTurnEnd.hideWaits(words = false, speaking = true, sending = false, busy = false, replyHeld = true, tries = 5))
        assertEquals(false, CarTurnEnd.hideWaits(words = true, speaking = true, sending = true, busy = true, replyHeld = true, tries = 0))
    }

    /** fix-car-132 (review-131 Low 6): the car screen's talk stops the answer to a question said in the reply's 3 seconds. */
    @Test fun theCarScreensTalkStopsAnAnswerAfterAReply() {
        assertEquals(false, CarTurnEnd.hideWaits(words = false, speaking = true, sending = false, busy = true, replyHeld = true, tries = 5))
        assertEquals(true, CarTurnEnd.hideWaits(words = false, speaking = true, sending = false, busy = false, replyHeld = true, tries = 5))
        assertEquals(true, CarTurnEnd.hideWaits(words = true, speaking = true, sending = false, busy = true, replyHeld = true, tries = 5))
    }

    /** fix-car-132: a failed open of your app beside Robot drops the swap; a lock's refusal changes nothing. */
    @Test fun aFailedOpenDropsTheSwap() {
        assertEquals(CarBeside.Last.App("a.music"), CarBeside.appOpen("a.music", opened = true, blocked = false, CarBeside.Last.Map))
        assertEquals(CarBeside.Last.Unknown, CarBeside.appOpen("a.music", opened = false, blocked = false, CarBeside.Last.Map))
        assertEquals(null, CarBeside.appOpen("a.music", opened = false, blocked = true, CarBeside.Last.Map))
    }
}
