// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.backup.BackupExport
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.EverydaySettingsCodec
import app.heyrobot.core.everyday.EverydaySettingsFile
import app.heyrobot.core.everyday.MapsApp
import app.heyrobot.core.everyday.MusicApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Car-media-maps (docs/pm/handoff-car-media-maps.md): the music tile's four cases, the maps choice, the directions tile,
// and the locks on what the car screen opens.
class CarMediaTest {
    private val self = "ai.robot_app"
    private val sha = "a".repeat(64)
    private val pinned = MusicApp("org.example.music", sha, "Music")
    private val maps = MapsApp("net.osmand", "b".repeat(64), "OsmAnd")
    private val home = 45.5 to -73.6
    private val here = 45.4 to -73.5

    // ------------------------------------------------------------------ the music tile

    @Test fun tileNamesTheSessionPlayingNow() {
        val t = CarMusicTile.of(listOf(MediaNow("org.example.podcasts", false, "Old", "Show"), MediaNow("org.example.radio", true, "Episode 12", "Host")), pinned, AppPin.OK, self)
        assertEquals(MusicTile.Session("org.example.radio", true, "Episode 12", "Host"), t)
        assertEquals("Episode 12", CarMusicTile.song(t as MusicTile.Session))
        assertEquals("Host", CarMusicTile.artist(t))
    }

    @Test fun anArtistWithNoTitleTakesTheBigLine() {
        val t = MusicTile.Session("p.q", true, null, "Host")
        assertEquals("Host", CarMusicTile.song(t))
        assertNull(CarMusicTile.artist(t))
    }

    @Test fun aPlaceholderArtistIsLeftOut() {
        for (nobody in listOf("Unknown Artist", "<unknown>", "UNKNOWN", "Artiste inconnu")) {
            val t = CarMusicTile.of(listOf(MediaNow("org.example.radio", true, "Episode 12", nobody)), pinned, AppPin.OK, self)
            assertNull(nobody, CarMusicTile.artist(t as MusicTile.Session))
        }
    }

    @Test fun aCutStopsAtAWholeWord() {
        assertEquals("Harvest…", CarText.cutAtWord("Harvest Moon", 10))
        assertEquals("Harvest…", CarText.cutAtWord("Harvest Moon", 7))
        assertEquals("Harvest…", CarText.cutAtWord("Harvest Moon", 8))
        assertEquals("Harvest Moon…", CarText.cutAtWord("Harvest Moon", 12))
        assertEquals("Harvest…", CarText.cutAtWord("Harvest, Moon", 9))
        assertEquals("Superlong…", CarText.cutAtWord("Superlongword", 9))
    }

    @Test fun tileNamesAPausedSessionSeenThisDriveBeforeThePinnedApp() {
        val t = CarMusicTile.of(listOf(MediaNow("org.example.podcasts", false, "Episode 3", null)), pinned, AppPin.OK, self, setOf("org.example.podcasts"))
        assertEquals(MusicTile.Session("org.example.podcasts", false, "Episode 3", null), t)
        assertEquals("org.example.podcasts", t.pkg)
    }

    @Test fun tileWithNoSessionNamesThePinnedApp() {
        assertEquals(MusicTile.Pinned(pinned), CarMusicTile.of(emptyList(), pinned, AppPin.OK, self))
        // Robot's own session never counts.
        assertEquals(MusicTile.Pinned(pinned), CarMusicTile.of(listOf(MediaNow(self, true)), pinned, AppPin.OK, self))
    }

    @Test fun tileWithNeitherIsQuiet() {
        assertEquals(MusicTile.Choose, CarMusicTile.of(emptyList(), null, null, self))
        // A pinned app since replaced by someone else's, or uninstalled, isn't offered.
        assertEquals(MusicTile.Choose, CarMusicTile.of(emptyList(), pinned, AppPin.REPLACED, self))
        assertEquals(MusicTile.Choose, CarMusicTile.of(emptyList(), pinned, AppPin.GONE, self))
        assertNull(MusicTile.Choose.pkg)
    }

    @Test fun songTextIsOneShortLine() {
        assertEquals("A B", CarMusicTile.clean(" A\n\tB "))
        assertNull(CarMusicTile.clean("  "))
        assertNull(CarMusicTile.clean(null))
        val long = CarMusicTile.clean("x".repeat(200))!!
        assertEquals(CarMusicTile.MAX_TEXT, long.length)
        assertTrue(long.endsWith("…"))
        assertNull(CarMusicTile.song(MusicTile.Session("p.q", true, null, null)))
    }

    // ------------------------------------------------------------------ the maps choice

    private fun links(p: MapsPlan) = (p as MapsPlan.Open).links

    @Test fun automaticWithOrganicMapsOpensItsRoute() {
        val l = links(MapsChoice.route(null, null, organic = true, here, "Your position", home, "Home"))
        assertEquals(listOf(MapsChoice.ORGANIC_MAPS, MapsChoice.ORGANIC_MAPS), l.map { it.pkg })
        assertTrue(l[0].uri.startsWith("om://route?sll=45.400000,-73.500000"))
        assertTrue(l[1].uri.startsWith("geo:45.500000,-73.600000"))
        assertEquals(MapsChoice.ORGANIC_MAPS, MapsChoice.app(null, null, organic = true, geoDefault = "com.example.maps"))
        // Without the position: the place, still in Organic Maps.
        assertEquals(listOf(MapsLink(app.heyrobot.core.OrganicMapsRoute.geo(45.5, -73.6, "Home"), MapsChoice.ORGANIC_MAPS)),
            links(MapsChoice.route(null, null, organic = true, null, "Your position", home, "Home")))
    }

    @Test fun automaticWithoutOrganicMapsOpensThePhonesDefault() {
        val l = links(MapsChoice.route(null, null, organic = false, here, "Your position", home, "Home"))
        assertEquals(listOf(MapsLink(app.heyrobot.core.OrganicMapsRoute.geo(45.5, -73.6, "Home"), null)), l)
        assertEquals("com.example.maps", MapsChoice.app(null, null, organic = false, geoDefault = "com.example.maps"))
        assertEquals(listOf(MapsLink("geo:0,0?q=gas", null)), links(MapsChoice.search(null, null, organic = false, "gas")))
        assertEquals(MapsChoice.ORGANIC_MAPS, links(MapsChoice.search(null, null, organic = true, "gas"))[0].pkg)
    }

    @Test fun aPickedAppGetsEveryRoute() {
        // Organic Maps installed too: the picked app wins, with the place (it documents no route link), and never our position.
        val l = links(MapsChoice.route(maps, AppPin.OK, organic = true, here, "Your position", home, "Home"))
        assertEquals(listOf(MapsLink(app.heyrobot.core.OrganicMapsRoute.geo(45.5, -73.6, "Home"), "net.osmand")), l)
        assertEquals("net.osmand", MapsChoice.app(maps, AppPin.OK, organic = true))
        assertEquals(listOf(MapsLink("geo:0,0?q=gas", "net.osmand")), links(MapsChoice.search(maps, AppPin.OK, organic = true, "gas")))
        // Google Maps: its documented directions link first.
        val g = MapsApp(MapsChoice.GOOGLE_MAPS, sha, "Maps")
        val gl = links(MapsChoice.route(g, AppPin.OK, organic = false, here, "Your position", home, "Home"))
        assertEquals("google.navigation:q=45.500000,-73.600000&mode=d", gl[0].uri)
        assertFalse(gl[0].uri.contains("45.400000"))
        // Organic Maps picked: its route.
        val o = MapsApp(MapsChoice.ORGANIC_MAPS, sha, "Organic Maps")
        assertTrue(links(MapsChoice.route(o, AppPin.OK, organic = true, here, "Your position", home, "Home"))[0].uri.startsWith("om://route"))
    }

    @Test fun aPickedAppReplacedGetsNothingAndOneGoneFallsBackToAutomatic() {
        assertEquals(MapsPlan.Repick("OsmAnd"), MapsChoice.route(maps, AppPin.REPLACED, organic = true, here, "Your position", home, "Home"))
        assertEquals(MapsPlan.Repick("OsmAnd"), MapsChoice.search(maps, AppPin.REPLACED, organic = true, "gas"))
        assertNull(MapsChoice.app(maps, AppPin.REPLACED, organic = true, geoDefault = "net.osmand"))
        assertEquals(MapsChoice.ORGANIC_MAPS, links(MapsChoice.route(maps, AppPin.GONE, organic = true, here, "Your position", home, "Home"))[0].pkg)
        assertEquals(MapsChoice.ORGANIC_MAPS, MapsChoice.app(maps, AppPin.GONE, organic = true))
    }

    // ------------------------------------------------------------------ the directions tile

    @Test fun directionsTile() {
        assertEquals(CarDirections.Tile.BACK_TO_DIRECTIONS, CarDirections.tile(routeThisDrive = true, ongoing = false))
        assertEquals(CarDirections.Tile.BACK_TO_DIRECTIONS, CarDirections.tile(routeThisDrive = false, ongoing = true))
        assertEquals(CarDirections.Tile.MAPS, CarDirections.tile(routeThisDrive = false, ongoing = false))
    }

    // ------------------------------------------------------------------ the locks

    @Test fun mapsUnderPhoneLockOpenOnlyWithMapsOn() {
        assertEquals(CarOpen.Gate.OPEN, CarOpen.maps(keyguard = false, trusted = false, phoneLock = false, lockMaps = false))
        assertEquals(CarOpen.Gate.PHONE_LOCK, CarOpen.maps(keyguard = false, trusted = false, phoneLock = true, lockMaps = false))
        assertEquals(CarOpen.Gate.OPEN, CarOpen.maps(keyguard = false, trusted = false, phoneLock = true, lockMaps = true))
        // On the lock screen too (fail closed), even in the trusted session.
        assertEquals(CarOpen.Gate.PHONE_LOCK, CarOpen.maps(keyguard = true, trusted = true, phoneLock = true, lockMaps = false))
    }

    @Test fun mapsOnTheLockScreenOnlyInTheTrustedSession() {
        assertEquals(CarOpen.Gate.UNLOCK, CarOpen.maps(keyguard = true, trusted = false, phoneLock = false, lockMaps = false))
        assertEquals(CarOpen.Gate.OPEN, CarOpen.maps(keyguard = true, trusted = true, phoneLock = false, lockMaps = false))
    }

    @Test fun musicAppUnderPhoneLockOnlyWhenTheLockLeavesItOpen() {
        assertEquals(CarOpen.Gate.PHONE_LOCK, CarOpen.app("org.example.music", keyguard = false, trusted = false, phoneLock = true, lockApps = setOf("org.other")))
        assertEquals(CarOpen.Gate.OPEN, CarOpen.app("org.example.music", keyguard = false, trusted = false, phoneLock = true, lockApps = setOf("org.example.music")))
        assertEquals(CarOpen.Gate.UNLOCK, CarOpen.app("org.example.music", keyguard = true, trusted = false, phoneLock = false, lockApps = emptySet()))
        assertEquals(CarOpen.Gate.OPEN, CarOpen.app("org.example.music", keyguard = true, trusted = true, phoneLock = false, lockApps = emptySet()))
    }

    // ------------------------------------------------------------------ the setting

    @Test fun mapsAppIsKeptInTheEverydayFileAndNeverInABackup() {
        val s = EverydaySettings(mapsApp = maps)
        val raw = EverydaySettingsCodec.encode(s)
        assertEquals(EverydaySettingsFile.Ok(s), EverydaySettingsCodec.decode(raw))
        assertEquals(EverydaySettingsFile.Damaged, EverydaySettingsCodec.decode("""{"v":1,"mapsApp":{"package":"net.osmand"}}"""))
        assertNull((EverydaySettingsCodec.decode(BackupExport.everyday(raw)) as EverydaySettingsFile.Ok).read.mapsApp)
        assertFalse(BackupExport.everyday("""{"v":9,"mapsApp":{}}""")!!.contains("mapsApp"))
        // A restore keeps this phone's pick, whoever's backup it is.
        for (mine in listOf(true, false)) {
            assertEquals(maps, RestoreSafety.everyday(s, EverydaySettings(), mine).value.mapsApp)
            assertNull(RestoreSafety.everyday(EverydaySettings(), s, mine).value.mapsApp)
        }
    }
}
