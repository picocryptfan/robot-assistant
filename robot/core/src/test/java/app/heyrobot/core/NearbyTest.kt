package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Directions to the nearest pharmacy": OpenStreetMap's Nominatim, the closest place, Organic Maps' route (0.4.0). */
class NearbyTest {
    /** Recorded from Nominatim on 11 October 2026 (core/src/test/resources/nominatim), around 45.51, -73.59. */
    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("nominatim/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun onlyTheWordsAndARoundedBoxGo() {
        val url = Nearby.searchUrl("gas station", 45.508_84, -73.587_81, 0.02)
        assertEquals("https://nominatim.openstreetmap.org/search?q=gas%20station&format=jsonv2&limit=40&bounded=1&viewbox=-73.61,45.53,-73.57,45.49", url)
        // Two decimals of the position, never more: the box is built from the rounded centre.
        assertFalse(url.contains("45.508") || url.contains("73.587"))
        assertTrue(Nearby.searchUrl("pharmacie", 45.5, -73.6, 0.1).contains("viewbox=-73.70,45.60,-73.50,45.40"))
        assertEquals("Robot/0.4 (GPL app)", Nearby.USER_AGENT)
        assertEquals(1_000L, Nearby.MIN_GAP_MS)
        assertEquals(listOf(0.02, 0.1), Nearby.BOXES)
        // A query is encoded strictly: no "&" or "=" of its own reaches the URL.
        assertTrue(Nearby.searchUrl("A&W=1", 0.0, 0.0, 0.02).startsWith("https://nominatim.openstreetmap.org/search?q=A%26W%3D1&"))
    }

    @Test fun recordedAnswersAreReadAndTheClosestIsTaken() {
        val pharmacies = Nearby.places(sample("pharmacy-montreal.json"))!!
        assertEquals(20, pharmacies.size)
        assertEquals(NearbyPlace("Jean Coutu", "5692 Avenue du Parc, Mile-End", 45.5230116, -73.60483), pharmacies[0])
        val (place, metres) = Nearby.closest(pharmacies, 45.5256, -73.5975)!!
        assertEquals("Familiprix", place.name)
        assertTrue("$metres", metres in 400.0..600.0)
        val gas = Nearby.places(sample("gas-station-montreal.json"))!!
        assertEquals("Petro Canada", gas[0].name)
        assertEquals("Boulevard Saint-Laurent, Mile-End", gas[0].address)
        assertEquals("Super Relais Dépanneur", Nearby.closest(gas, 45.5258, -73.5982)!!.first.name)
        // Nothing, or not an answer.
        assertEquals(emptyList<NearbyPlace>(), Nearby.places("[]"))
        assertNull(Nearby.places("""{"error":"Bad request"}"""))
        assertNull(Nearby.places(null))
        assertNull(Nearby.closest(emptyList(), 0.0, 0.0))
        // A place without a position or a name is left out, never fatal.
        assertEquals(listOf("Ok"), Nearby.places("""[{"lat":"x","lon":"1","name":"No"},{"lat":"1","lon":"2","name":"","display_name":""},{"lat":"1","lon":"2","name":"Ok"}]""")!!.map { it.name })
    }

    @Test fun distancesAreRoughAndInTheRegionsUnits() {
        assertEquals("about 450 m", Nearby.distance(440.0, WeatherUnits.METRIC))
        assertEquals("about 50 m", Nearby.distance(3.0, WeatherUnits.METRIC))
        assertEquals("about 1.2 km", Nearby.distance(1_234.0, WeatherUnits.METRIC))
        assertEquals("about 12 km", Nearby.distance(12_345.0, WeatherUnits.METRIC))
        assertEquals("about 0.8 mi", Nearby.distance(1_234.0, WeatherUnits.IMPERIAL))
        assertEquals("about 150 ft", Nearby.distance(40.0, WeatherUnits.IMPERIAL))
        assertEquals("about 12 mi", Nearby.distance(19_500.0, WeatherUnits.IMPERIAL))
        // Montreal to Quebec City, about 233 km.
        assertTrue(Nearby.metres(45.5017, -73.5673, 46.8139, -71.2080) in 228_000.0..238_000.0)
    }

    @Test fun organicMapsRouteHasEveryPartInOrder() {
        // Organic Maps (map/mwm_url.cpp) wants sll, saddr, dll, daddr and type, in that order, or refuses the link.
        val uri = OrganicMapsRoute.uri(45.5256, -73.5975, "Your position (approximate)", 45.5231358, -73.603922, "Familiprix")
        assertEquals("om://route?sll=45.525600,-73.597500&saddr=Your%20position%20%28approximate%29&dll=45.523136,-73.603922&daddr=Familiprix&type=vehicle", uri)
        val keys = uri.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf("sll", "saddr", "dll", "daddr", "type"), keys)
        assertEquals("geo:45.523136,-73.603922?q=45.523136,-73.603922(Caf%C3%A9%20%26%20Co)", OrganicMapsRoute.geo(45.5231358, -73.603922, "Café & Co"))
    }

    @Test fun nearestInTheModelsWordsOrYourOwn() {
        for (s in listOf("the nearest pharmacy", "closest gas station", "Nearest Tim Hortons to me", "la pharmacie la plus proche", "le dépanneur le plus près")) {
            assertTrue(s, NearestAsk.said(s))
        }
        for (s in listOf("pharmacy", "pharmacy near me", "pharmacie près de moi", "Central Station", "Nearby Café", null)) assertFalse("$s", NearestAsk.said(s))
        // The model dropped "nearest": your own words this turn still say it, with every word of what it searches.
        assertTrue(NearestAsk.inWords("Directions to the nearest gas station please", "gas station"))
        assertTrue(NearestAsk.inWords("c'est où la pharmacie la plus proche", "pharmacie"))
        assertFalse(NearestAsk.inWords("directions to the nearest gas station", "pharmacy"))
        assertFalse(NearestAsk.inWords("directions to the gas station", "gas station"))
        assertFalse(NearestAsk.inWords(null, "pharmacy"))
    }

    @Test fun theMapsActionsCarryItAndGoOnlineOnlyThen() {
        val n = Action.MapsNavigate("pharmacy", nearest = true)
        assertTrue(n.online)
        assertFalse(Action.MapsNavigate("pharmacy").online)
        assertTrue(Action.MapsSearch("pharmacy", nearest = true).online)
        assertTrue(Action.Weather(null, null).online)
        assertFalse(Action.SetTimer(60).online)
        assertEquals("Find the nearest “pharmacy” online (OpenStreetMap) and open the route in Organic Maps", n.describe())
        assertEquals(Action.MapsNavigate("pharmacy"), Nearby.plain(n))
        assertEquals(Action.MapsSearch("x"), Nearby.plain(Action.MapsSearch("x", nearest = true)))
        // Where you are: personal for Claude, as the weather is.
        assertEquals(Personal.LOCATION, Personal.of(ToolName.MAPS_NAVIGATE))
        assertEquals(Personal.LOCATION, Personal.of(ToolName.MAPS_SEARCH))
        // On by default, and off stays off.
        assertTrue(RobotSettings().nearestOnline)
        assertFalse(RobotSettings.fromJson(RobotSettings(nearestOnline = false).toJson().toString()).nearestOnline)
        assertTrue(RobotSettings.fromJson("""{"schema":5}""").nearestOnline)
        // Every failure has its own words and log code.
        assertEquals(NearbyFail.entries.size, NearbyFail.entries.map { it.id }.distinct().size)
    }
}
