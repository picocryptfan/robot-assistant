// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

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

    @Test fun onlyTheCarsOwnShowsWithTheScreenUpHaveNoPanel() {
        assertEquals(true, CarTurn.quiet(on = true, screenUp = true, fromCar = true, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = false, screenUp = true, fromCar = true, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = true, screenUp = false, fromCar = true, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = true, screenUp = true, fromCar = false, windowNeeded = false))
        assertEquals(false, CarTurn.quiet(on = true, screenUp = true, fromCar = true, windowNeeded = true))
    }
}
