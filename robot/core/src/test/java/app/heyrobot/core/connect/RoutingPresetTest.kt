// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.ValueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Travel time (plan §3.4): where you are rounded to about 1 km, each server's request as its docs give it, and the three
 * replies (recorded shapes, written from each server's documented fields), in any phone language.
 */
class RoutingPresetTest {
    private var locale: Locale = Locale.getDefault()

    @Before fun french() { Locale.setDefault(Locale.CANADA_FRENCH) }
    @After fun back() { Locale.setDefault(locale) }

    private val home = SavedPlace.of("Home", 45.50884, -73.58781)!!
    private fun ok(b: Built) = (b as Built.Ok).request
    private fun body(r: HttpRequest) = StrictJson.parse(r.body!!.parts.joinToString("") { (it as BodyPart.Text).text })!!.value

    @Test fun osrm() {
        val c = ConnFixtures.routing()
        val r = ok(RoutingPreset.request(c, 45.47312, -73.60219, home))
        assertEquals(HttpMethod.GET, r.method)
        assertEquals("/route/v1/driving/-73.60,45.47;-73.59,45.51?overview=false&steps=false&alternatives=false", r.tail)
        assertTrue(r.read && r.json && r.secrets.isEmpty())
        assertEquals("http://100.101.102.20:5000/route/v1/driving/-73.60,45.47;-73.59,45.51?overview=false&steps=false&alternatives=false",
            (ConnWire.assemble(r, ConnSecrets()) as ConnWire.Result.Ok).wire.url)
        val reply = """{"code":"Ok","routes":[{"duration":1514.3,"distance":17532.8,"weight":1514.3,"legs":[]}],"waypoints":[]}"""
        assertEquals(TravelTime(25, 17.5), RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse(reply)!!.value))
        assertNull(RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"NoRoute","message":"Impossible route between points"}""")!!.value))
        assertNull(RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"Ok","routes":[]}""")!!.value))
    }

    @Test fun valhalla() {
        val c = ConnFixtures.routing("c-valh01", RoutingKind.VALHALLA, "https://route.example.org")
        val r = ok(RoutingPreset.request(c, 45.47312, -73.60219, home))
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/route", r.tail)
        assertEquals(mapOf("locations" to listOf(mapOf("lat" to 45.47, "lon" to -73.6), mapOf("lat" to 45.51, "lon" to -73.59)), "costing" to "auto",
            "units" to "kilometers", "directions_type" to "none"), body(r))
        val reply = """{"trip":{"locations":[],"legs":[],"summary":{"time":1514.3,"length":17.533,"has_toll":false},"status_message":"Found route between points","status":0,"units":"kilometers","language":"en-US"}}"""
        assertEquals(TravelTime(25, 17.5), RoutingPreset.read(RoutingKind.VALHALLA, StrictJson.parse(reply)!!.value))
        assertNull(RoutingPreset.read(RoutingKind.VALHALLA, StrictJson.parse("""{"error_code":442,"error":"No path could be found for input","status_code":400}""")!!.value))
    }

    @Test fun graphHopper() {
        val c = ConnFixtures.routing("c-grph01", RoutingKind.GRAPHHOPPER, "https://[fd00::20]:8989")
        val r = ok(RoutingPreset.request(c, 45.47312, -73.60219, home))
        assertEquals("/route", r.tail)
        // Longitude first, as GraphHopper's JSON takes points.
        assertEquals(mapOf("points" to listOf(listOf(-73.6, 45.47), listOf(-73.59, 45.51)), "profile" to "car", "calc_points" to false, "instructions" to false), body(r))
        val reply = """{"hints":{},"info":{"copyrights":["GraphHopper"]},"paths":[{"distance":17532.8,"weight":1700.1,"time":1514300,"transfers":0,"snapped_waypoints":"x"}]}"""
        assertEquals(TravelTime(25, 17.5), RoutingPreset.read(RoutingKind.GRAPHHOPPER, StrictJson.parse(reply)!!.value))
        assertNull(RoutingPreset.read(RoutingKind.GRAPHHOPPER, StrictJson.parse("""{"message":"Cannot find point 2","hints":[]}""")!!.value))
    }

    @Test fun outOfReason() {
        assertNull(RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"Ok","routes":[{"duration":-5,"distance":10}]}""")!!.value))
        assertNull(RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"Ok","routes":[{"duration":"25","distance":10}]}""")!!.value))
        assertNull(RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"Ok","routes":[{"duration":9999999,"distance":10}]}""")!!.value))
        // A very short trip is still "about 1 min".
        assertEquals(TravelTime(1, 0.1), RoutingPreset.read(RoutingKind.OSRM, StrictJson.parse("""{"code":"Ok","routes":[{"duration":10,"distance":80}]}""")!!.value))
    }

    @Test fun outputsAreTypedValuesFromOutside() {
        val o = TravelTime(25, 17.5).outputs()
        assertEquals(25L, o.getValue("minutes").value)
        assertEquals(ParamType.INTEGER, o.getValue("minutes").type)
        assertEquals(17.5, o.getValue("km").value)
        assertTrue(o.values.all { it.sources == setOf(ValueSource.ONLINE) && !it.onlineText })
    }

    @Test fun testAndPlaces() {
        val c = ConnFixtures.routing()
        // Test sends two fixed points near Home, nothing about where you are.
        assertEquals("/route/v1/driving/-73.59,45.51;-73.59,45.52?overview=false&steps=false&alternatives=false", ok(RoutingPreset.test(c, home)).tail)
        assertEquals(home to null, RoutingPreset.place("home", home, null))
        assertEquals(null to ConnFailure.NO_WORK, RoutingPreset.place("work", home, null))
        assertEquals(null to ConnFailure.NO_HOME, RoutingPreset.place("home", null, home))
        assertEquals("Save your home in Settings › Car mode first.", ConnFailure.NO_HOME.text("x").en())
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), RoutingPreset.request(c, Double.NaN, 0.0, home))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), RoutingPreset.request(c, 91.0, 0.0, home))
        // A key goes only over https; an http address typed as a public number is refused before anything is built.
        assertEquals(Built.Refused(ConnFailure.KEY_OVER_HTTP), RoutingPreset.request(c.copy(auth = ConnAuth.Bearer), 45.0, -73.0, home))
        assertEquals(Built.Refused(ConnFailure.MOVED), RoutingPreset.request(ConnFixtures.routing(address = "http://8.8.8.8"), 45.0, -73.0, home))
        val keyed = ConnFixtures.routing("c-keyd01", address = "https://route.example.org").copy(auth = ConnAuth.Header("X-Api-Key"))
        val w = (ConnWire.assemble(ok(RoutingPreset.request(keyed, 45.0, -73.0, home)), ConnSecrets(header = "k1")) as ConnWire.Result.Ok).wire
        assertEquals("X-Api-Key" to "k1", w.headers.last())
        assertTrue(!w.url.contains("k1"))
    }
}
