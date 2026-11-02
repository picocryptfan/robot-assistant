// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillValue

// Pure. Home Assistant over its REST API (plan §3.1, C2). Checked against Home Assistant's REST API page on 22 Oct 2026
// (developers.home-assistant.io/docs/api/rest/, now served from contributors.home-assistant.io/core/internals/rest-api/):
// - VERIFIED: every request carries `Authorization: Bearer <token>`; `GET /api/` answers {"message": "API running."}
//   (with its trailing slash); `GET /api/states` lists the states the user may read; `GET /api/states/<entity_id>`
//   answers one state object or 404, `state` a string and the friendly name at `attributes.friendly_name`;
//   `GET /api/services` lists the services by domain; `POST /api/services/<domain>/<service>` takes the service's data
//   (with `entity_id`) as a JSON body, waits until the call is done and answers the list of states that changed; with
//   `?return_response` it answers {"changed_states": […], "service_response": {…}}, and Home Assistant answers 400 when
//   that doesn't fit the service (so each operation says whether its service returns data); a failed call is 500.
// - VERIFIED 26 Oct 2026 (C5), home-assistant.io/actions/script.turn_on/: "Whatever starts the script does not wait for it
//   to finish" (calling `script.<name>` directly would wait); so a script operation's 2xx means only that it started, and
//   Robot checks nothing more (its card says Robot can't see what it does).
// - VERIFIED 26 Oct 2026 (C5), contributors.home-assistant.io/core/entities/: the states of locks (locked, unlocked, open,
//   jammed, and locking, unlocking, opening while they move), covers and valves (open, closed, and opening, closing) and
//   alarm panels (disarmed, armed_home, armed_away, armed_night, armed_vacation, armed_custom_bypass, and pending, arming,
//   disarming, triggered), and their services (lock, unlock, open; open_cover, close_cover; open_valve, close_valve;
//   alarm_disarm and alarm_arm_*). The on/off domains' "on" and "off" are as remembered ([EXPECTED]'s other rows).
// - UNVERIFIED: `homeassistant.turn_on` and `turn_off` on a cover opening and closing it (the homeassistant integration's
//   mapping, as remembered); `/api/services`' `response` field (HaPickers).

object HaPreset {
    /** The request an operation of a Home Assistant connection makes. */
    fun op(op: ConnOp): HttpOp = when (val s = op.spec) {
        is OpSpec.HaCall -> HttpOp(HttpMethod.POST, "/api/services/${s.domain}/${s.service}", // l10n: not text (Home Assistant's path)
            query = if (s.returns) listOf(RETURN_RESPONSE to null) else emptyList(),
            body = OpBody.Json(linkedMapOf<String, Any?>(ENTITY_ID to "{{entity}}") + s.data), params = op.params, json = true)
        OpSpec.HaRead -> HttpOp(HttpMethod.GET, "/api/states/{{entity}}", params = op.params, read = true, json = true) // l10n: not text (Home Assistant's path)
        OpSpec.HaScript -> HttpOp(HttpMethod.POST, "/api/services/script/turn_on", // l10n: not text (Home Assistant's path)
            body = OpBody.Json(linkedMapOf<String, Any?>(ENTITY_ID to "{{entity}}")), params = op.params)
        else -> throw IllegalArgumentException()
    }

    /** Test's requests (plan §3.1): `GET /api/`, then `GET /api/states` and `GET /api/services` for the pickers (C5). */
    fun test(c: Connection): List<HttpRequest> = listOf("/api/", "/api/states", "/api/services").map { path -> // l10n: not text (Home Assistant's paths)
        HttpRequest(HttpMethod.GET, c.address!!, false, c.address.path + path, emptyList(), emptyList(), null, c.auth, read = true, json = true)
    }

    /** `GET /api/`'s reply says the API runs. */
    fun running(json: Any?): Boolean = (json as? Map<*, *>)?.get("message") == API_RUNNING

    /**
     * The state a service leaves an entity of [entityDomain] in (plan §3.1's check), or null when Robot can't tell (it
     * then checks only that Home Assistant said yes). [service] of `homeassistant` counts as the entity's own domain's.
     */
    fun expected(service: String, entityDomain: String): String? = EXPECTED[entityDomain]?.get(service)
        ?: if (entityDomain in ON_OFF && service in setOf("turn_on", "turn_off")) service.removePrefix("turn_") else null // l10n: not text (Home Assistant's services)

    /**
     * What the reply of a service call says of one entity (plan §3.1): in the expected state, still moving towards it (a
     * garage door "closing": not a failure, and not "done" either), in another, missing from the reply (Robot reads it
     * back after 2 s), or one Robot can't check.
     */
    enum class Seen { RIGHT, MOVING, WRONG, NOT_IN_REPLY, CANT_CHECK }

    /** What [state] (as Home Assistant gave it) says when [want] was expected. */
    fun judge(want: String, state: String?): Seen = when {
        state == want -> Seen.RIGHT
        state != null && MOVING[want]?.contains(state) == true -> Seen.MOVING
        else -> Seen.WRONG
    }

    /**
     * The service call [s]'s reply ([json]: the list of changed states, or `{"changed_states": …}` with
     * `?return_response`) for each of [entities]: in the expected state, in another, missing (C5 reads it back after 2 s),
     * or one Robot can't check.
     */
    fun seen(s: OpSpec.HaCall, json: Any?, entities: List<String>): Map<String, Seen> {
        val changed = (if (s.returns) (json as? Map<*, *>)?.get(CHANGED) else json) as? List<*>
        return entities.associateWith { e ->
            val want = expected(s.service, e.substringBefore('.')) ?: return@associateWith Seen.CANT_CHECK
            val st = changed?.firstOrNull { (it as? Map<*, *>)?.get(ENTITY_ID) == e } as? Map<*, *> ?: return@associateWith Seen.NOT_IN_REPLY
            judge(want, st["state"] as? String)
        }
    }

    /** A read-back's reply (`GET /api/states/<entity>`): in the state [want]. */
    fun state(json: Any?): String? = (json as? Map<*, *>)?.get("state") as? String

    /** A step's entity values as Home Assistant reads `entity_id` ("light.a, light.b"), for [seen]. */
    fun entities(values: Map<String, SkillValue>): List<String> =
        (values[ConnOp.ENTITY]?.value as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != ConnCheck.ALL }.orEmpty()

    const val ENTITY_ID = "entity_id" // l10n: not text (Home Assistant's field)
    private const val RETURN_RESPONSE = "return_response" // l10n: not text (Home Assistant's query)
    private const val CHANGED = "changed_states" // l10n: not text (Home Assistant's field)
    private const val API_RUNNING = "API running." // l10n: not text (Home Assistant's reply)

    /** Domains whose entities are "on" after turn_on and "off" after turn_off. */
    private val ON_OFF = setOf("light", "switch", "fan", "input_boolean", "siren", "automation", "humidifier", "remote") // l10n: not text (Home Assistant's domains)

    /** The other services with a state to check, by entity domain. */
    private val EXPECTED: Map<String, Map<String, String>> = mapOf(
        "lock" to mapOf("lock" to "locked", "unlock" to "unlocked", "open" to "open"), // l10n: not text (Home Assistant's services and states)
        "cover" to mapOf("open_cover" to "open", "close_cover" to "closed", "turn_on" to "open", "turn_off" to "closed"), // l10n: not text (Home Assistant's services and states)
        "valve" to mapOf("open_valve" to "open", "close_valve" to "closed"), // l10n: not text (Home Assistant's services and states)
        "alarm_control_panel" to mapOf("alarm_disarm" to "disarmed", "alarm_arm_home" to "armed_home", "alarm_arm_away" to "armed_away", // l10n: not text (Home Assistant's services and states)
            "alarm_arm_night" to "armed_night", "alarm_arm_vacation" to "armed_vacation", "alarm_arm_custom_bypass" to "armed_custom_bypass"), // l10n: not text (Home Assistant's services and states)
        "climate" to mapOf("turn_off" to "off"), // l10n: not text (Home Assistant's services and states)
        "media_player" to mapOf("turn_off" to "off"), // l10n: not text (Home Assistant's services and states)
    )

    /** The states an entity passes through on its way to the expected one (by the expected state): not yet there, not wrong. */
    private val MOVING: Map<String, Set<String>> = run {
        val armed = setOf("arming", "pending") // l10n: not text (Home Assistant's states)
        mapOf("locked" to setOf("locking"), "unlocked" to setOf("unlocking"), "open" to setOf("opening"), "closed" to setOf("closing"), // l10n: not text (Home Assistant's states)
            "disarmed" to setOf("disarming"), "armed_home" to armed, "armed_away" to armed, "armed_night" to armed, "armed_vacation" to armed, // l10n: not text (Home Assistant's states)
            "armed_custom_bypass" to armed) // l10n: not text (Home Assistant's state)
    }
}
