package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nearest place's searches and lookup without the network (0.4.0 review): every way it can fail ends in Organic
 * Maps' search with its reason, Nominatim is asked at most once a second, and a recent answer is reused.
 */
class NearbySearchTest {
    private var now = 1_000_000L
    private val clock = { now }
    private val slept = mutableListOf<Long>()
    private val search = NearbySearch(clock) { ms -> slept += ms; now += ms }
    private val urls = mutableListOf<String>()

    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("nominatim/$name")!!.readBytes().toString(Charsets.UTF_8)

    /** A fetch that answers each request in turn (a String body, or a NearbyFailure to throw). */
    private fun fetch(vararg answers: Any): (String, Abort) -> String {
        val left = answers.toMutableList()
        return { url, _ ->
            urls += url
            when (val a = left.removeAt(0)) {
                is String -> a
                is NearbyFailure -> throw a
                else -> error("bad answer")
            }
        }
    }

    private fun abort(ms: Long = 20_000) = Abort(ms, clock)

    private fun lookup(abort: Abort = abort(), allowed: Boolean = true, at: Pair<Double, Double>? = 45.5256 to -73.5975,
        fetch: (String, Abort) -> String): NearbyLookup =
        Nearby.lookup(allowed, abort, locate = { at }, search = { lat, lon -> search.search("pharmacy", lat, lon, abort, fetch) })

    @Test fun foundTakesTheClosest() {
        val r = lookup(fetch = fetch(sample("pharmacy-montreal.json"))) as NearbyLookup.Found
        assertEquals("Familiprix", r.place.name)
        assertEquals(20, r.places)
        assertEquals(1, urls.size)
        assertTrue(urls.single().startsWith("https://${Nearby.HOST}/search?q=pharmacy&"))
    }

    @Test fun anHttpErrorOrABusyServiceSaysSo() {
        assertEquals(NearbyLookup.Instead(NearbyFail.SERVICE), lookup(fetch = fetch(NearbyFailure(NearbyFail.SERVICE, 500))))
        assertEquals(NearbyLookup.Instead(NearbyFail.BUSY), lookup(fetch = fetch(NearbyFailure(NearbyFail.BUSY, 429))))
        assertEquals(NearbyLookup.Instead(NearbyFail.NO_INTERNET), lookup(fetch = fetch(NearbyFailure(NearbyFail.NO_INTERNET))))
        assertEquals(null, Nearby.httpFail(200))
        assertEquals(NearbyFail.BUSY, Nearby.httpFail(429))
        assertEquals(NearbyFail.BUSY, Nearby.httpFail(503))
        for (code in listOf(204, 301, 302, 400, 403, 404, 500)) assertEquals("$code", NearbyFail.SERVICE, Nearby.httpFail(code))
    }

    @Test fun malformedJsonIsAServiceError() {
        assertEquals(NearbyLookup.Instead(NearbyFail.SERVICE), lookup(fetch = fetch("<html>Bad gateway</html>")))
        assertEquals(NearbyLookup.Instead(NearbyFail.SERVICE), lookup(fetch = fetch("""{"error":"Bad request"}""")))
    }

    @Test fun nothingInEitherBoxIsNothingNearby() {
        assertEquals(NearbyLookup.Instead(NearbyFail.NOTHING), lookup(fetch = fetch("[]", "[]")))
        // The small box, then the larger one, a second apart (Nominatim's usage policy).
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("viewbox=-73.62,45.55,-73.58,45.51") && urls[1].contains("viewbox=-73.70,45.63,-73.50,45.43"))
        assertEquals(listOf(Nearby.MIN_GAP_MS), slept)
    }

    @Test fun noLocationOrNotAllowedNeverGoesOnline() {
        val never: (String, Abort) -> String = { _, _ -> error("must not fetch") }
        assertEquals(NearbyLookup.Instead(NearbyFail.NO_LOCATION), lookup(at = null, fetch = never))
        assertEquals(NearbyLookup.Instead(NearbyFail.NOT_ALLOWED), lookup(allowed = false, fetch = never))
        assertTrue(urls.isEmpty())
    }

    @Test fun theDeadlineOrStopEndsItWithoutARequest() {
        // Too little time left for a request: no request at all.
        val late = abort(300)
        assertEquals(NearbyLookup.Instead(NearbyFail.TIMEOUT), lookup(late, fetch = fetch("[]")))
        assertTrue(urls.isEmpty())
        // The fetch timed out (the client's own timeout, or the deadline's disconnect).
        assertEquals(NearbyLookup.Instead(NearbyFail.TIMEOUT), lookup(fetch = fetch(NearbyFailure(NearbyFail.TIMEOUT))))
        // Stop before anything was asked.
        val stopped = abort().also { it.cancel() }
        assertEquals(NearbyLookup.Instead(NearbyFail.STOPPED), lookup(stopped, fetch = fetch("[]")))
        // The wait for the next second outlasts what is left: the deadline, not a late request.
        val before = urls.size
        assertEquals(NearbyLookup.Instead(NearbyFail.TIMEOUT), lookup(abort(1_200), fetch = fetch("[]", "[]")))
        assertEquals(before, urls.size)
    }

    /** What a late lookup and its fallback both want to open: the first to claim it only. */
    @Test fun onlyTheFirstClaimOpensAnApp() {
        val a = abort()
        assertTrue(a.claim())
        assertTrue(!a.claim())
        a.cancel()
        assertTrue(!a.claim())
        val b = abort().also { it.cancel() }
        assertTrue(b.claim())
    }

    @Test fun aRecentAnswerIsReusedAndRequestsAreCounted() {
        var counted = -1
        val body = sample("pharmacy-montreal.json")
        val first = search.search("pharmacy", 45.5256, -73.5975, abort(), fetch(body)) { counted = it }
        assertEquals(1, counted)
        now += 60_000
        val again = search.search("pharmacy", 45.5256, -73.5975, abort(), fetch()) { counted = it }
        assertEquals(first, again)
        assertEquals(0, counted)
        // After ten minutes the answer is asked for again.
        now += Nearby.CACHE_MS
        search.search("pharmacy", 45.5256, -73.5975, abort(), fetch(body)) { counted = it }
        assertEquals(1, counted)
        assertEquals(2, urls.size)
    }
}
