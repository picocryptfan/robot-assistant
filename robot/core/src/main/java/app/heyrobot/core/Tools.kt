package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

// Pure. The fixed tool set of phase 1 (spec item 7): what the model is told (OpenAPI JSON, at most two
// arguments each), and the validation of what it asks for. :brain only describes the tools; the app
// process validates and runs them (ToolExecutor), after the approval gate.

enum class ToolName(val id: String, val title: String) {
    SET_TIMER("set_timer", "Timers"),
    SET_ALARM("set_alarm", "Alarms"),
    OPEN_APP("open_app", "Open apps"),
    MAPS_SEARCH("maps_search", "Maps search"),
    MAPS_NAVIGATE("maps_navigate", "Maps directions"),
    FLASHLIGHT("flashlight", "Flashlight");

    companion object {
        fun of(id: String?): ToolName? = entries.firstOrNull { it.id == id?.trim()?.lowercase(Locale.ROOT) }
    }
}

data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean)

data class ToolSpec(val tool: ToolName, val description: String, val params: List<ToolParam>) {
    init { require(params.size <= 2) { "phase 1 tools take at most two arguments" } }

    /** {"name", "description", "parameters": {"type":"object", "properties", "required"}} as LiteRT-LM's OpenApiTool wants it. */
    fun openApiJson(): String = JSONObject().apply {
        put("name", tool.id)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                for (p in params) put(p.name, JSONObject().put("type", p.type).put("description", p.description))
            })
            put("required", JSONArray(params.filter { it.required }.map { it.name }))
        })
    }.toString()
}

object ToolSpecs {
    val ALL: List<ToolSpec> = listOf(
        ToolSpec(ToolName.SET_TIMER, "Start a countdown timer in the Clock app. Give minutes, seconds, or both.", listOf(
            ToolParam("minutes", "integer", "Whole minutes of the timer, e.g. 5", false),
            ToolParam("seconds", "integer", "Extra seconds of the timer, e.g. 30", false),
        )),
        ToolSpec(ToolName.SET_ALARM, "Set an alarm in the Clock app for the next time the clock shows hour:minute.", listOf(
            ToolParam("hour", "integer", "Hour on a 24-hour clock, 0 to 23", true),
            ToolParam("minute", "integer", "Minute, 0 to 59", false),
        )),
        ToolSpec(ToolName.OPEN_APP, "Open an installed app by its name.", listOf(
            ToolParam("name", "string", "The app's name as shown on the home screen, e.g. Signal", true),
        )),
        ToolSpec(ToolName.MAPS_SEARCH, "Search for a place or a kind of place in Organic Maps.", listOf(
            ToolParam("query", "string", "What to look for, e.g. pharmacy or Central Station", true),
        )),
        ToolSpec(ToolName.MAPS_NAVIGATE, "Find a destination in Organic Maps to get directions to it.", listOf(
            ToolParam("destination", "string", "Where to go, e.g. an address or a place name", true),
        )),
        ToolSpec(ToolName.FLASHLIGHT, "Turn the phone's flashlight on or off.", listOf(
            ToolParam("on", "boolean", "true to turn it on, false to turn it off", true),
        )),
    )

    fun of(tool: ToolName): ToolSpec = ALL.first { it.tool == tool }
}

/** A validated request to do something on the phone. */
sealed class Action {
    abstract val tool: ToolName
    /** Plain language for the confirmation card and the log-free history ("Set a timer for 5 minutes"). */
    abstract fun describe(): String
    /** Opens another app on top of the assistant panel. */
    open val leavesPanel: Boolean get() = false

    data class SetTimer(val seconds: Long) : Action() {
        override val tool get() = ToolName.SET_TIMER
        override fun describe() = "Set a timer for ${Durations.spoken(seconds)}"
    }

    data class SetAlarm(val hour: Int, val minute: Int) : Action() {
        override val tool get() = ToolName.SET_ALARM
        override fun describe() = String.format(Locale.US, "Set an alarm for %02d:%02d", hour, minute)
    }

    data class OpenApp(val name: String) : Action() {
        override val tool get() = ToolName.OPEN_APP
        override fun describe() = "Open $name"
        override val leavesPanel get() = true
    }

    data class MapsSearch(val query: String) : Action() {
        override val tool get() = ToolName.MAPS_SEARCH
        override fun describe() = "Search Organic Maps for “$query”"
        override val leavesPanel get() = true
    }

    data class MapsNavigate(val destination: String) : Action() {
        override val tool get() = ToolName.MAPS_NAVIGATE
        override fun describe() = "Find “$destination” in Organic Maps for directions"
        override val leavesPanel get() = true
    }

    data class Flashlight(val on: Boolean) : Action() {
        override val tool get() = ToolName.FLASHLIGHT
        override fun describe() = if (on) "Turn the flashlight on" else "Turn the flashlight off"
    }
}

sealed class ParsedCall {
    data class Ok(val action: Action) : ParsedCall()
    /** [tool] is the name the model used (maybe unknown); [reason] goes back to the model. */
    data class Invalid(val tool: String, val reason: String) : ParsedCall()
}

/** A tool call as the model produced it: a name and loosely typed arguments. */
data class RawToolCall(val name: String, val args: Map<String, Any?>)

object Durations {
    const val MAX_TIMER_S = 24 * 3600L

    fun spoken(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (s > 0 || isEmpty()) add(plural(s, "second"))
        }
        return when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> "${parts[0]}, ${parts[1]} and ${parts[2]}"
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

object ToolCalls {
    private const val MAX_TEXT = 200

    /** Validates one call. Unknown argument names are ignored; missing or out-of-range ones are refused. */
    fun parse(call: RawToolCall): ParsedCall {
        val tool = ToolName.of(call.name) ?: return ParsedCall.Invalid(call.name.take(40), "unknown tool")
        val a = call.args.mapKeys { it.key.trim().lowercase(Locale.ROOT) }
        fun bad(why: String) = ParsedCall.Invalid(tool.id, why)
        return when (tool) {
            ToolName.SET_TIMER -> {
                val min = number(a["minutes"] ?: a["minute"] ?: a["mins"])
                val sec = number(a["seconds"] ?: a["second"] ?: a["secs"])
                if (a.containsKey("minutes") && min == null) return bad("minutes must be a number")
                if (a.containsKey("seconds") && sec == null) return bad("seconds must be a number")
                if ((min ?: 0.0) < 0 || (sec ?: 0.0) < 0) return bad("the timer cannot be negative")
                val total = ((min ?: 0.0) * 60 + (sec ?: 0.0)).roundToLong()
                when {
                    total <= 0 -> bad("give the timer's length in minutes or seconds")
                    total > Durations.MAX_TIMER_S -> bad("timers can be at most 24 hours")
                    else -> ParsedCall.Ok(Action.SetTimer(total))
                }
            }
            ToolName.SET_ALARM -> {
                val rawHour = a["hour"] ?: a["hours"]
                // "7:30" in the hour argument: small models do this.
                val clock = (rawHour as? String)?.let { Regex("""^\s*(\d{1,2})\s*[:.h]\s*(\d{2})\s*$""").find(it) }
                val hour = clock?.groupValues?.get(1)?.toDoubleOrNull() ?: number(rawHour) ?: return bad("hour is required")
                val minute = clock?.groupValues?.get(2)?.toDoubleOrNull() ?: number(a["minute"] ?: a["minutes"]) ?: 0.0
                if (hour % 1.0 != 0.0 || minute % 1.0 != 0.0) return bad("hour and minute must be whole numbers")
                if (hour !in 0.0..23.0) return bad("hour must be 0 to 23")
                if (minute !in 0.0..59.0) return bad("minute must be 0 to 59")
                ParsedCall.Ok(Action.SetAlarm(hour.toInt(), minute.toInt()))
            }
            ToolName.OPEN_APP -> text(a["name"] ?: a["app"] ?: a["app_name"], 60)?.let { ParsedCall.Ok(Action.OpenApp(it)) }
                ?: bad("name is required")
            ToolName.MAPS_SEARCH -> text(a["query"] ?: a["q"] ?: a["place"], MAX_TEXT)?.let { ParsedCall.Ok(Action.MapsSearch(it)) }
                ?: bad("query is required")
            ToolName.MAPS_NAVIGATE -> text(a["destination"] ?: a["to"] ?: a["place"], MAX_TEXT)?.let { ParsedCall.Ok(Action.MapsNavigate(it)) }
                ?: bad("destination is required")
            ToolName.FLASHLIGHT -> bool(a["on"] ?: a["state"] ?: a["enabled"])?.let { ParsedCall.Ok(Action.Flashlight(it)) }
                ?: bad("on must be true or false")
        }
    }

    fun number(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> when (v.toDouble()) { 1.0 -> true; 0.0 -> false; else -> null }
        is String -> when (v.trim().lowercase(Locale.ROOT)) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun text(v: Any?, max: Int): String? {
        val s = (v as? String ?: (v as? Number)?.toString())?.replace(Regex("\\s+"), " ")?.trim()?.trim('"', '\'')
        return s?.takeIf { it.isNotEmpty() }?.take(max)
    }

    // ------------------------------------------------------------------ text fallback

    /** A tool call written into the reply text, and the part of the text it occupied. */
    data class TextCall(val call: RawToolCall, val range: IntRange)

    /**
     * Tool calls the model wrote as text instead of through LiteRT-LM's tool-call channel (small models
     * sometimes do). Recognises only known tool names, in three shapes:
     * - Gemma's `call:set_timer{minutes:5}` (optionally inside `<start_function_call>`...`<end_function_call>`),
     * - a JSON object `{"name":"set_timer","arguments":{"minutes":5}}` (also "parameters"/"args"),
     * - a Python-style call `set_timer(minutes=5)`, typically in a ```tool_code block.
     */
    fun parseText(text: String): List<TextCall> {
        val out = mutableListOf<TextCall>()
        val names = ToolName.entries.joinToString("|") { it.id }
        Regex("""(?:<start_function_call>\s*)?call:($names)\s*\{(.*?)\}(?:\s*<end_function_call>)?""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            out += TextCall(RawToolCall(m.groupValues[1], gemmaArgs(m.groupValues[2])), m.range)
        }
        if (out.isEmpty()) {
            Regex("""\{[^{}]*"(?:name|tool|function)"\s*:\s*"($names)"[^{}]*\{[^{}]*\}[^{}]*\}|\{[^{}]*"(?:name|tool|function)"\s*:\s*"($names)"[^{}]*\}""").findAll(text).forEach { m ->
                jsonCall(m.value)?.let { out += TextCall(it, m.range) }
            }
        }
        if (out.isEmpty()) {
            Regex("""\b($names)\s*\(([^()]*)\)""").findAll(text).forEach { m ->
                out += TextCall(RawToolCall(m.groupValues[1], pythonArgs(m.groupValues[2])), m.range)
            }
        }
        return out
    }

    /** The reply with the textual tool calls (and any code fences or markers around them) removed. */
    fun stripText(text: String, calls: List<TextCall>): String {
        var s = text
        for (c in calls.sortedByDescending { it.range.first }) s = s.removeRange(c.range)
        return s.replace(Regex("```[a-zA-Z_]*\\s*```"), "").replace("<start_function_call>", "").replace("<end_function_call>", "").trim()
    }

    private fun gemmaArgs(body: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        // key:value pairs; values may be wrapped in <escape>...<escape> or quotes and may contain commas then.
        val re = Regex("""(\w+)\s*:\s*(<escape>(.*?)<escape>|"((?:[^"\\]|\\.)*)"|'([^']*)'|[^,]*)""", RegexOption.DOT_MATCHES_ALL)
        re.findAll(body).forEach { r ->
            val whole = r.groupValues[2]
            m[r.groupValues[1]] = when {
                whole.startsWith("<escape>") -> r.groupValues[3]
                whole.startsWith("\"") -> r.groupValues[4]
                whole.startsWith("'") -> r.groupValues[5]
                else -> scalar(whole.trim(), quoted = false)
            }
        }
        return m
    }

    private fun pythonArgs(body: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        Regex("""(\w+)\s*=\s*("((?:[^"\\]|\\.)*)"|'([^']*)'|[^,]+)""").findAll(body).forEach { r ->
            val v = r.groupValues[2].trim()
            m[r.groupValues[1]] = when {
                v.startsWith("\"") -> r.groupValues[3]
                v.startsWith("'") -> r.groupValues[4]
                else -> scalar(v, quoted = false)
            }
        }
        return m
    }

    private fun scalar(v: String, quoted: Boolean): Any? {
        if (quoted) return v
        val t = v.trim()
        return when {
            t.equals("true", true) || t == "True" -> true
            t.equals("false", true) -> false
            t.toLongOrNull() != null -> t.toLong()
            t.toDoubleOrNull() != null -> t.toDouble()
            else -> t
        }
    }

    private fun jsonCall(s: String): RawToolCall? = try {
        val o = JSONObject(s)
        val name = o.optString("name").ifEmpty { o.optString("tool") }.ifEmpty { o.optString("function") }
        val args = o.optJSONObject("arguments") ?: o.optJSONObject("parameters") ?: o.optJSONObject("args") ?: JSONObject()
        RawToolCall(name, Json.toMap(args))
    } catch (_: Exception) { null }
}
