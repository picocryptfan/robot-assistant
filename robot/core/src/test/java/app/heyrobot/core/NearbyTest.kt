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
        // Nominatim's usage policy: a User-Agent naming the app (never the phone), and OpenStreetMap credited on the card.
        assertEquals("Robot/0.4.0 (app.heyrobot; Android voice assistant, GPL-3.0)", Nearby.userAgent("0.4.0"))
        assertEquals("Robot/0 (app.heyrobot; Android voice assistant, GPL-3.0)", Nearby.userAgent("(;)"))
        assertEquals("Data © OpenStreetMap contributors, ODbL", Nearby.ATTRIBUTION)
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

    /** Only a kind of place or a name goes to Nominatim; an address, a number or an email stays on the phone. */
    @Test fun onlyAKindOfPlaceOrANameGoesOnline() {
        for (q in listOf("pharmacy", "gas station", "Tim Hortons", "7-Eleven", "pharmacie", "boulangerie ouverte", "Jean Coutu")) assertTrue(q, Nearby.sendable(q))
        for (q in listOf("", "  ", "pharmacy near 5692 Avenue du Parc", "514 555 0199", "sam@example.com", "a b c d e f g", "x".repeat(61))) {
            assertFalse(q, Nearby.sendable(q))
        }
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
        // Ask the first time by default (SettingsTest has the migration).
        assertEquals(NearestOnline.ASK, RobotSettings().nearestOnline)
        // Anything in the way: Organic Maps' search, and the card says why.
        assertEquals("No internet: Robot couldn't reach OpenStreetMap, so Robot opened Organic Maps' search instead: pick the place, then tap Route.",
            Nearby.instead(NearbyFail.NO_INTERNET, navigate = true))
        assertEquals("OpenStreetMap knows no such place near you, so Robot opened Organic Maps' search instead.", Nearby.instead(NearbyFail.NOTHING, navigate = false))
        // Every failure has its own words and log code.
        assertEquals(NearbyFail.entries.size, NearbyFail.entries.map { it.id }.distinct().size)
    }

    /** "Find the nearest place online": Ask the first time shows a card that says what goes; Always goes; Never doesn't. */
    @Test fun askTheFirstTimeAlwaysOrNever() {
        val a = Action.MapsNavigate("pharmacy", nearest = true)
        // Not asked for the nearest, or Never: Organic Maps' search, no card of its own.
        assertEquals(Prepared.Ready(Action.MapsNavigate("pharmacy")), Nearby.prepare(a, "pharmacy", wanted = false, mode = NearestOnline.ALWAYS))
        assertEquals(Prepared.Ready(Action.MapsNavigate("pharmacy")), Nearby.prepare(a, "pharmacy", wanted = true, mode = NearestOnline.NEVER))
        assertEquals(Prepared.Ready(Action.MapsNavigate("pharmacy", nearest = true)), Nearby.prepare(Action.MapsNavigate("pharmacy"), "pharmacy", true, NearestOnline.ALWAYS))
        // Ask the first time: always a card (AskWhy.ONLINE), three buttons and "Don't ask again".
        val card = Nearby.prepare(Action.MapsNavigate("pharmacy"), "pharmacy", wanted = true, mode = NearestOnline.ASK)
        assertEquals(setOf(AskWhy.ONLINE), card.why)
        assertEquals(listOf("Allow always", "Just this time", "No, search the map"), card.choices.map { it.label })
        assertEquals("Don't ask again", card.remember)
        assertEquals("To find the closest one, Robot would send “pharmacy” and your area (about 1 km) to OpenStreetMap's Nominatim. " +
            "Nothing else leaves the phone. “No, search the map” uses Organic Maps' search instead, on the phone.", card.note)
        val (always, once, no) = card.choices
        assertEquals(Action.MapsNavigate("pharmacy", nearest = true, consent = NearestConsent.ALWAYS), always.action)
        assertEquals(Action.MapsNavigate("pharmacy", nearest = true, consent = NearestConsent.ONCE), once.action)
        assertEquals(Action.MapsNavigate("pharmacy", nearest = false, consent = NearestConsent.NO), no.action)
        assertTrue(always.action.online && once.action.online && !no.action.online)
        // "Don't ask again" keeps the answer: Always, or Never with No.
        assertEquals(always.action, always.remember)
        assertEquals(always.action, once.remember)
        assertEquals(Action.MapsNavigate("pharmacy", nearest = false, consent = NearestConsent.NEVER), no.remember)
        // The search's own words, whatever the card carried; a fallback never keeps an answer again.
        assertEquals(Action.MapsSearch("x"), Nearby.prepare(Action.MapsSearch("x"), "x", true, NearestOnline.ASK).choices[2].action.let(Nearby::plain))
        assertEquals(Action.MapsNavigate("pharmacy"), Nearby.plain(always.action))
        // The gate always shows it, whatever the level or the switches.
        val loose = RobotSettings(safetyCards = SafetyCards.UNSURE_ONLY, whoCards = false, learnedCards = false, deleteCards = false)
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.MAPS_NAVIGATE, false, loose, why = card.why))
    }

    /** The panel's answer counts while it is newer than what Settings last chose. */
    @Test fun thePanelsAnswerOrSettingsWhicheverIsNewer() {
        val s = RobotSettings(nearestOnline = NearestOnline.ASK, nearestOnlineAt = 1_000)
        assertEquals(NearestOnline.ASK, Nearby.mode(s, null))
        assertEquals(NearestOnline.ALWAYS, Nearby.mode(s, NearestAnswer(NearestOnline.ALWAYS, 2_000)))
        assertEquals(NearestOnline.ASK, Nearby.mode(s, NearestAnswer(NearestOnline.NEVER, 500)))
        assertEquals(NearestOnline.NEVER, Nearby.mode(RobotSettings(), NearestAnswer(NearestOnline.NEVER, 1)))
        val answer = NearestAnswer(NearestOnline.NEVER, 1_234)
        assertEquals(answer, NearestAnswer.fromJson(answer.toJson()))
        for (bad in listOf(null, "", "{", """{"choice":"MAYBE","at":1}""")) assertEquals("$bad", null, NearestAnswer.fromJson(bad))
    }

    /**
     * Settings › Actions › Search server (Nominatim's usage policy: switchable without an update): any HTTPS Nominatim,
     * checked; the URL, the card's line and the failures name the server you chose.
     */
    @Test fun anotherSearchServerIsCheckedAndNamed() {
        val d = NearbyServer.DEFAULT
        assertEquals("https://nominatim.openstreetmap.org", d.base)
        assertEquals("", d.saved)
        assertTrue(d.isDefault)
        assertEquals(d, NearbyServer.of(""))
        assertEquals(d, NearbyServer.of(null))
        assertEquals(d, NearbyServer.check("  ").server)
        // Typed in any usual way: the same server, in one form.
        for (typed in listOf("nominatim.example.org", "https://nominatim.example.org", "HTTPS://Nominatim.Example.org/", "nominatim.example.org:443",
                "https://nominatim.example.org/search", "https://nominatim.example.org/search.php", "nominatim.example.org.")) {
            val c = NearbyServer.check(typed)
            assertEquals(typed, null, c.problem)
            assertEquals(typed, "https://nominatim.example.org", c.server!!.base)
        }
        val own = NearbyServer.parse("https://geo.example.net:8443/nominatim/search")!!
        assertEquals("https://geo.example.net:8443/nominatim", own.base)
        assertEquals("geo.example.net:8443", own.name)
        assertEquals("the Nominatim server at geo.example.net:8443", own.label)
        assertEquals("geo.example.net:8443", own.sentTo)
        assertEquals(own, NearbyServer.parse(own.saved))
        assertEquals("https://192.168.1.20/nominatim", NearbyServer.parse("192.168.1.20/nominatim")!!.base)
        assertTrue(Nearby.searchUrl("pharmacy", 45.5, -73.6, 0.02, own).startsWith("https://geo.example.net:8443/nominatim/search?q=pharmacy&format=jsonv2&"))
        // Never in the clear, never with a query, a user name or a path that climbs; never a bare word or a number.
        for (bad in listOf("http://nominatim.example.org", "ftp://example.org", "https://example.org/search?q=x", "https://example.org/#top",
                "https://me@example.org", "https://example.org:0", "https://example.org:70000", "https://example.org:http", "https://example.org/../etc",
                "https://example.org/a b", "localhost", "nominatim", "1234", "300.1.1.1", "https://[::1]/", "exa_mple.org", "-x.example.org",
                "https://example.org/%2e%2e", "x".repeat(NearbyServer.MAX_LENGTH + 1))) {
            val c = NearbyServer.check(bad)
            assertEquals(bad, null, c.server)
            assertTrue(bad, !c.problem.isNullOrBlank())
        }
        assertEquals("Use an https:// address: the words and your area only go encrypted.", NearbyServer.check("http://x.example.org").problem)
        // The card says exactly who gets the words and the area.
        val card = Nearby.prepare(Action.MapsNavigate("pharmacy"), "pharmacy", wanted = true, mode = NearestOnline.ASK, server = own)
        assertEquals("To find the closest one, Robot would send “pharmacy” and your area (about 1 km) to the Nominatim server at geo.example.net:8443. " +
            "Nothing else leaves the phone. “No, search the map” uses Organic Maps' search instead, on the phone.", card.note)
        assertEquals(Nearby.consentNote("pharmacy"), Nearby.prepare(Action.MapsNavigate("pharmacy"), "pharmacy", true, NearestOnline.ASK).note)
        // A failure names the server that failed; OpenStreetMap's keep their words.
        assertEquals("No internet: Robot couldn't reach geo.example.net:8443, so Robot opened Organic Maps' search instead.",
            Nearby.instead(NearbyFail.NO_INTERNET, navigate = false, server = own))
        assertEquals("geo.example.net:8443 is busy just now", NearbyFail.BUSY.text(own))
        assertEquals("geo.example.net:8443 didn't answer properly", NearbyFail.SERVICE.text(own))
        for (f in NearbyFail.entries) assertEquals(f.text, f.text(d))
        assertEquals(NearbyFail.NO_LOCATION.text, NearbyFail.NO_LOCATION.text(own))
    }
}
