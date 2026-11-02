// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepCheck

/** Connections as the plan's examples have them (§3), for the connect tests. Demo hosts only. */
object ConnFixtures {
    fun url(s: String, whole: Boolean = false): ConnUrl = ConnUrl.parse(s, whole)!!

    fun entity(vararg ids: String) = OpParam(ParamType.CHOICE, ids.toList(), default = ids.singleOrNull())

    /** "Turn off a light": light.turn_off on the downstairs or the kitchen light. */
    val lightOff = ConnOp("light_off", "Turn off a light", OpSpec.HaCall("light", "turn_off"),
        mapOf("entity" to entity("light.downstairs", "light.kitchen")))

    /** "Dim the lights": light.turn_on with a brightness. */
    val dim = ConnOp("dim", "Dim the lights", OpSpec.HaCall("light", "turn_on", mapOf("brightness_pct" to "{{level}}", "transition" to 2L)),
        linkedMapOf("entity" to entity("light.downstairs"), "level" to OpParam(ParamType.INTEGER, min = 0.0, max = 100.0, default = "30")))

    val lockDoor = ConnOp("lock_door", "Lock the front door", OpSpec.HaCall("lock", "lock"), mapOf("entity" to entity("lock.front_door")))

    val allLightsOff = ConnOp("all_off", "Turn off all lights", OpSpec.HaCall("light", "turn_off"), mapOf("entity" to entity("all")))

    val homeOffLock = ConnOp("ha_off", "Turn something off", OpSpec.HaCall("homeassistant", "turn_off"), mapOf("entity" to entity("lock.front_door")))

    val blinds = ConnOp("blinds", "Close the blinds", OpSpec.HaCall("cover", "close_cover", classes = mapOf("cover.living_room" to "blind")),
        mapOf("entity" to entity("cover.living_room")))

    val garage = ConnOp("garage", "Close the garage", OpSpec.HaCall("cover", "close_cover"), mapOf("entity" to entity("cover.garage")))

    val forecast = ConnOp("forecast", "Get the forecast", OpSpec.HaCall("weather", "get_forecasts", mapOf("type" to "daily"), returns = true),
        mapOf("entity" to entity("weather.home")), outputs = mapOf("high" to OpOutput("/service_response/weather.home/forecast/0/temperature", ParamType.NUMBER)))

    val readLight = ConnOp("light_state", "Is the light on", OpSpec.HaRead, mapOf("entity" to entity("light.downstairs")),
        outputs = linkedMapOf("state" to OpOutput("/state", ParamType.CHOICE, listOf("on", "off")), "name" to OpOutput("/attributes/friendly_name", ParamType.TEXT)))

    val readTemp = ConnOp("temp", "Living room temperature", OpSpec.HaRead, mapOf("entity" to entity("sensor.living_room_temperature")),
        outputs = mapOf("state" to OpOutput("/state", ParamType.NUMBER)))

    val script = ConnOp("good_night", "Run Good night", OpSpec.HaScript, mapOf("entity" to entity("script.good_night")))

    fun homeAssistant(id: String = "c-ha0001", vararg ops: ConnOp = arrayOf(lightOff, dim, readLight, script)) = Connection(id, ConnKind.HOME_ASSISTANT,
        "Home Assistant", url("https://home.tail1234.ts.net"), auth = ConnAuth.Bearer, where = ConnWhere.HOME, ops = ops.toList(),
        answers = mapOf("https://home.tail1234.ts.net" to ConsentAnswer.ALLOW), tested = ConnTested(1_761_000_000_000L, "2026.10.1"))

    val publish = ConnOp("tell_family", "Tell the family", OpSpec.NtfyPublish("{{message}}", title = "From Robot", priority = "{{urgency}}", tags = listOf("house")),
        linkedMapOf("message" to OpParam(ParamType.TEXT, maxLen = 500), "urgency" to OpParam(ParamType.INTEGER, min = 3.0, max = 4.0, default = "3")))

    fun ntfy(id: String = "c-ntfy01", onlyMine: Boolean = false) = Connection(id, ConnKind.NTFY, "Jarvis's ntfy", url("https://ntfy.example.org"),
        auth = ConnAuth.Bearer, ops = listOf(if (onlyMine) publish.copy(spec = (publish.spec as OpSpec.NtfyPublish).copy(onlyMine = true)) else publish))

    val n8nPost = ConnOp("log_trip", "Log a trip", OpSpec.Webhook(HttpMethod.POST, "/webhook/trips/{{kind}}",
        query = listOf("source" to "robot"), headers = listOf("X-Robot-Note" to "{{note}}"),
        body = OpBody.Json(linkedMapOf("minutes" to "{{minutes}}", "note" to "Trip: {{note}}", "fixed" to listOf(1L, 2.5, true, null)))),
        linkedMapOf("kind" to OpParam(ParamType.CHOICE, listOf("work", "home")), "minutes" to OpParam(ParamType.INTEGER, min = 0.0, max = 600.0),
            "note" to OpParam(ParamType.TEXT, maxLen = 80, default = "none")),
        outputs = mapOf("saved" to OpOutput("/saved", ParamType.YES_NO), "count" to OpOutput("/count", ParamType.INTEGER)),
        check = StepCheck.Equals("/ok", true))

    fun webhook(id: String = "c-n8n001") = Connection(id, ConnKind.WEBHOOK, "n8n", url("https://n8n.example.org"), ops = listOf(n8nPost))

    val discordPost = ConnOp("post", "Post to the channel", OpSpec.Webhook(HttpMethod.POST, body = OpBody.Json(mapOf("content" to "{{text}}"))),
        mapOf("text" to OpParam(ParamType.TEXT, maxLen = 200)), marks = setOf(SkillEffect.MESSAGES_SOMEONE))

    /** A webhook whose whole address is the key: only its origin is kept. */
    fun discord(id: String = "c-disc01") = Connection(id, ConnKind.WEBHOOK, "Discord", url("https://discord.example.com"), secretAddress = true,
        ops = listOf(discordPost))

    fun routing(id: String = "c-osrm01", kind: RoutingKind = RoutingKind.OSRM, address: String = "http://100.101.102.20:5000") =
        Connection(id, ConnKind.ROUTING, "My routing server", url(address), routing = kind)

    fun handOff(id: String = "c-app001") = Connection(id, ConnKind.HAND_OFF, "Signal", handOff = HandOffApp("org.thoughtcrime.securesms", "a".repeat(64)))

    /** C7: "Signal" asked to share text (SEND, text/plain), the text a slot a step fills. */
    val shareText = IntentSpec(HandOffAction.SEND, mime = IntentSpecs.TEXT_PLAIN, extras = listOf(HandOffExtra("android.intent.extra.TEXT", ParamType.TEXT, "{{text}}")),
        params = mapOf("text" to OpParam(ParamType.TEXT, maxLen = 500)))

    /** C7: a web page of yours opened in your browser, its page a slot (VIEW, https, the host fixed). */
    val viewPage = IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/page/{{page}}", params = mapOf("page" to OpParam(ParamType.TEXT, maxLen = 80)))

    fun signal(id: String = "c-app001", spec: IntentSpec = shareText) =
        Connection(id, ConnKind.HAND_OFF, "Signal", handOff = HandOffApp("org.thoughtcrime.securesms", "a".repeat(64), spec))
}
