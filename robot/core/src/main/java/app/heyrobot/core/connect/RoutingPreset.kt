// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import java.util.Locale

// Pure. The routing server you run, for `travel_time` (plan §3.4, C2): where the phone is, rounded to two decimals
// (about 1 km, the weather's rounding), and a place you saved in Car mode (Home or Work), and back the minutes and the
// kilometres. No public server is ever offered. A key, when your server needs one, goes in a header (Bearer, or one you
// name), never in the address. Checked against each server's docs on 22 Oct 2026, and (C6, 26 Oct 2026) against its
// source, since no container could be run on the desk:
// - OSRM (project-osrm.org/docs/v5.24.0/api/), VERIFIED: `GET /route/v1/{profile}/{lon},{lat};{lon},{lat}`, longitude
//   first, `overview=false` for no geometry; the reply's `code` is "Ok", `routes[0].duration` in seconds and `.distance`
//   in metres; an error, "NoRoute" included, is HTTP 400. The profile's name: osrm-routed checks only the version
//   (src/server/service_handler.cpp), so "driving" reaches whatever profile its data was built with. VERIFIED in source.
// - Valhalla (valhalla.github.io/valhalla/api/route/api-reference/), VERIFIED: `/route` takes GET and POST, `locations`
//   as {"lat", "lon"}, `costing` "auto", `units` "kilometers" (the default), `directions_type` "none" for no
//   maneuvers; `trip.summary.length` is in the units asked for; no route is HTTP 400 (error_code 442). VERIFIED in source
//   (src/worker.cc): a POST's body is read as the JSON request when there is no `json` parameter, so where you are stays
//   out of the address. VERIFIED in source (src/tyr/route_serializer_valhalla.cc): `trip.summary.time` is the legs'
//   times summed, in seconds (its sample: 325 for 4,973 m).
// - GraphHopper (graphhopper/docs/web/api-doc.md), VERIFIED: POST `/route` is GET's request as JSON, `points` as
//   [longitude, latitude]; `calc_points` and `instructions` false for distance and time only; `paths[0].distance` in
//   metres and `.time` in milliseconds. The profile is the server's own (its config, listed by `GET /info`): "car" is the
//   docs' example and the usual name; a server without it answers 400, which Test shows as "said no (error 400)".
// C6: where you are goes to a routing server over plain http only to loopback or Tailscale (ConnRules.locationOverHttp):
// [request] refuses anything else before a request exists, Test's included.

/** The travel time `travel_time` gives (StepKind.TRAVEL_TIME's outputs): whole minutes, and kilometres to one decimal. */
data class TravelTime(val minutes: Long, val km: Double) {
    /** As a step's outputs: typed values from outside the phone (they may flow on; D15). */
    fun outputs(): Map<String, SkillValue> = linkedMapOf(
        "minutes" to SkillValue.of(minutes, ParamType.INTEGER, ValueSource.ONLINE),
        "km" to SkillValue.of(km, ParamType.NUMBER, ValueSource.ONLINE),
    )
}

object RoutingPreset {
    /** Two decimals, about 1 km (Weather.coord's rounding), with a point whatever the phone's language. */
    fun coord(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    private fun rounded(v: Double): Double = coord(v).toDouble()

    /** The saved place [to] ("home" or "work") names, or the failure that says to save it. */
    fun place(to: String, home: SavedPlace?, work: SavedPlace?): Pair<SavedPlace?, ConnFailure?> = when (to) {
        "home" -> home to (if (home == null) ConnFailure.NO_HOME else null) // l10n: not text (travel_time's place)
        "work" -> work to (if (work == null) ConnFailure.NO_WORK else null) // l10n: not text (travel_time's place)
        else -> null to ConnFailure.BAD_VALUE
    }

    /** [c]'s request from ([fromLat], [fromLon]), where the phone is, to [to]; both rounded to about 1 km. */
    fun request(c: Connection, fromLat: Double, fromLon: Double, to: SavedPlace): Built = request(c, fromLat, fromLon, to.lat, to.lon)

    /** Test's request (plan §3.4): between two fixed points near [home], rounded, so nothing about where you are now is sent. */
    fun test(c: Connection, home: SavedPlace): Built = request(c, home.lat, home.lon, (home.lat + if (home.lat > 89.0) -0.01 else 0.01), home.lon)

    private fun request(c: Connection, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Built {
        ConnRules.refusal(c)?.let { return Built.Refused(it) }
        val kind = c.routing ?: return Built.Refused(ConnFailure.NOWHERE)
        val base = c.address ?: return Built.Refused(ConnFailure.NOWHERE)
        if (listOf(aLat, bLat).any { !it.isFinite() || it !in -90.0..90.0 } || listOf(aLon, bLon).any { !it.isFinite() || it !in -180.0..180.0 })
            return Built.Refused(ConnFailure.BAD_VALUE)
        val (la, lo, lb, lob) = listOf(aLat, aLon, bLat, bLon).map(::rounded)
        val r = when (kind) {
            RoutingKind.OSRM -> HttpRequest(HttpMethod.GET, base, false, base.path + "/route/v1/driving/${coord(lo)},${coord(la)};${coord(lob)},${coord(lb)}", // l10n: not text (OSRM's path)
                listOf("overview" to "false", "steps" to "false", "alternatives" to "false"), emptyList(), null, c.auth, read = true, json = true) // l10n: not text (OSRM's options)
            RoutingKind.VALHALLA -> post(c, base, linkedMapOf("locations" to listOf(linkedMapOf("lat" to la, "lon" to lo), linkedMapOf("lat" to lb, "lon" to lob)), // l10n: not text (Valhalla's fields)
                "costing" to "auto", "units" to "kilometers", "directions_type" to "none")) // l10n: not text (Valhalla's options)
            RoutingKind.GRAPHHOPPER -> post(c, base, linkedMapOf("points" to listOf(listOf(lo, la), listOf(lob, lb)), "profile" to "car", // l10n: not text (GraphHopper's fields)
                "calc_points" to false, "instructions" to false)) // l10n: not text (GraphHopper's options)
        }
        return Built.Ok(r)
    }

    private fun post(c: Connection, base: ConnUrl, body: Map<String, Any?>): HttpRequest =
        HttpRequest(HttpMethod.POST, base, false, base.path + "/route", emptyList(), emptyList(), // l10n: not text (the route path)
            ReqBody(listOf(BodyPart.Text(ConnJson.write(body))), HttpOp.JSON), c.auth, read = true, json = true)

    /** The travel time in [kind]'s reply, or null when it holds none (no route, another shape, a value out of reason). */
    fun read(kind: RoutingKind, json: Any?): TravelTime? {
        fun num(p: String): Double? = ((ConnReply.pointer(json, p) as? Pointed.Found)?.value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        val (seconds, metres) = when (kind) {
            RoutingKind.OSRM -> {
                if ((ConnReply.pointer(json, "/code") as? Pointed.Found)?.value != "Ok") return null // l10n: not text (OSRM's code)
                (num("/routes/0/duration") ?: return null) to (num("/routes/0/distance") ?: return null)
            }
            RoutingKind.VALHALLA -> (num("/trip/summary/time") ?: return null) to ((num("/trip/summary/length") ?: return null) * 1000.0)
            RoutingKind.GRAPHHOPPER -> ((num("/paths/0/time") ?: return null) / 1000.0) to (num("/paths/0/distance") ?: return null)
        }
        // A week's drive or the Earth's girth at most: anything more isn't a travel time.
        if (seconds > 7 * 86_400.0 || metres > 40_000_000.0) return null
        return TravelTime(maxOf(1L, Math.round(seconds / 60.0)), Math.round(metres / 100.0) / 10.0)
    }
}
