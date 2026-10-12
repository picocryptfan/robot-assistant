package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

// Pure (org.json, java.time). Routines (docs/plans/routines-headset-car-design.md §2): a trigger and a fixed list of steps,
// each one of Robot's tools with arguments fixed when the routine is saved. No model ever picks a step or an argument
// when a routine runs, so text a routine reads (a calendar title, a note) can't create an action. A routine is saved
// only through its card (RoutineDraft); RoutineGate decides what may run without you. Stored in
// files/robot/routines.json ([RoutineCodec], schema 1), through RoutineStore (an atomic write under a lock both processes
// take, a damaged file kept aside as .bad).

/**
 * A span of the day: [from] inclusive, [to] exclusive. A [to] before [from] wraps past midnight (20:00–04:00 is the
 * night); [from] equal to [to] is refused by [RoutineCheck] (empty or the whole day: unclear).
 */
data class TimeWindow(val from: LocalTime, val to: LocalTime) {
    fun contains(t: LocalTime): Boolean = if (from < to) t >= from && t < to else t >= from || t < to

    companion object {
        /** Settings' "alarmWindow": only alarms between 4:00 and 12:00 start a "when I turn off my alarm" routine. */
        val ALARM = TimeWindow(LocalTime.of(4, 0), LocalTime.of(12, 0))
        /** "When I plug in at night", "le soir": 18:00 to 04:00. */
        val NIGHT = TimeWindow(LocalTime.of(18, 0), LocalTime.of(4, 0))
    }
}

/** Remembered audio devices a routine may wait for (devices.json comes with headset mode, H1). */
enum class DeviceKind(val id: String) {
    HEADSET("headset"), CAR("car");

    companion object {
        fun of(id: String?): DeviceKind? = entries.firstOrNull { it.id == id }
    }
}

/** What starts a routine (design §2.1). */
sealed class RoutineTrigger {
    abstract val kind: TriggerKind

    /**
     * At [time] on [days] (one exact alarm for the soonest of all, RoutineSchedule), or once on [once] when [days] is
     * empty. The wall-clock time in the phone's zone of the moment: after a time zone change it is 7:00 there.
     */
    data class At(val time: LocalTime, val days: Set<DayOfWeek>, val once: LocalDate? = null) : RoutineTrigger() {
        override val kind get() = TriggerKind.TIME
    }

    /**
     * When you turn off a Clock alarm that rang within [window] (AlarmWatch tells a dismissal from a snooze and a
     * missed alarm).
     */
    data class AlarmDismissed(val window: TimeWindow = TimeWindow.ALARM) : RoutineTrigger() {
        override val kind get() = TriggerKind.ALARM
    }

    /** The first unlock after [after] each day. */
    data class Unlock(val after: LocalTime = LocalTime.of(5, 0)) : RoutineTrigger() {
        override val kind get() = TriggerKind.UNLOCK
    }

    /**
     * A remembered headset or car connects ([connect]) or disconnects. [deviceId]: its index key in devices.json; null
     * (a routine made by voice before you picked one): any remembered device of that [type].
     */
    data class Device(val type: DeviceKind, val deviceId: String? = null, val connect: Boolean = true) : RoutineTrigger() {
        override val kind get() = TriggerKind.DEVICE
    }

    /** Charging starts ([start]) or stops. */
    data class Charging(val start: Boolean = true) : RoutineTrigger() {
        override val kind get() = TriggerKind.CHARGING
    }

    /** Only when you ask ("run my evening routine") or tap Run now. */
    data object Asked : RoutineTrigger() {
        override val kind get() = TriggerKind.ASKED
    }
}

/**
 * The editor's conditions for triggers other than a time (design §2.3): only between two times, only on these days, at
 * most once a day (on by default: a flapping Bluetooth link or a spoofed broadcast can't repeat a routine).
 */
data class RoutineConditions(val between: TimeWindow? = null, val days: Set<DayOfWeek>? = null, val oncePerDay: Boolean = true)

/** Where a routine speaks (design §2.3 "Speak"); OutputGuard's rules for headsets come with headset mode. */
enum class RoutineOutput(val id: String, val title: String) {
    HEADSET_ONLY("headset_only", "Headset only"),
    HEADSET_OR_SPEAKER("headset_or_speaker", "Headset, else the phone speaker"),
    SILENT("silent", "Don't speak (notification only)");

    companion object {
        fun of(id: String?): RoutineOutput? = entries.firstOrNull { it.id == id }
    }
}

/** "Locked phone" in the editor: follow Settings › Safety & privacy (the default), or wait for an unlock. */
enum class RoutineLock(val id: String) {
    FOLLOW("follow"), WAIT_FOR_UNLOCK("wait_for_unlock");

    companion object {
        fun of(id: String?): RoutineLock? = entries.firstOrNull { it.id == id }
    }
}

/** A service a step reaches online by itself; the routine's card names it and saving it is the consent (design §2.5). */
enum class OnlineUse(val id: String, val title: String) {
    OPEN_METEO("open_meteo", "Open-Meteo (the weather)"),
    NOMINATIM("nominatim", "OpenStreetMap's Nominatim (the nearest place)");

    companion object {
        fun of(id: String?): OnlineUse? = entries.firstOrNull { it.id == id }
    }
}

/** One step: one of Robot's tools with fixed arguments, or a routine-only one (the brief, a fixed sentence). */
sealed class RoutineStep {
    /** A tool with the arguments fixed when saved ([Action] as ToolCalls.parse made it; never a resolved target). */
    data class Tool(val action: Action) : RoutineStep()

    /** The morning brief (BriefComposer): time, weather, today's calendar, brief reminders. */
    data object Brief : RoutineStep()

    /** Say [text], a sentence you wrote (at most [RoutineCheck.MAX_SAY] characters). */
    data class Say(val text: String) : RoutineStep()

    /** Read today's brief reminders ("remind me in the morning to …"). */
    data object Reminders : RoutineStep()

    /** How many unread messages you have (0.7: MessageSource; until then it says nothing). */
    data object Messages : RoutineStep()

    /** The tool it runs, or null for the routine-only steps. */
    val tool: ToolName? get() = (this as? Tool)?.action?.tool

    /** Where it goes online by itself, or null. The brief goes to Open-Meteo for its weather. */
    val online: OnlineUse? get() = when (this) {
        is Tool -> when (val a = action) {
            is Action.Weather -> OnlineUse.OPEN_METEO
            is Action.MapsSearch -> if (a.nearest) OnlineUse.NOMINATIM else null
            is Action.MapsNavigate -> if (a.nearest) OnlineUse.NOMINATIM else null
            else -> null
        }
        Brief -> OnlineUse.OPEN_METEO
        else -> null
    }

    /** The card's line for it ("Check the weather here"); the tool steps say what their card would say. */
    fun describe(): String = when (this) {
        is Tool -> action.describe()
        Brief -> "Read your morning brief"
        is Say -> "Say “$text”"
        Reminders -> "Read your brief reminders"
        Messages -> "Say how many unread messages you have"
    }

    /** The code the voice log would record for it (never the arguments). */
    val code: StepCode get() = when (this) {
        is Tool -> StepCode.of(action.tool)
        Brief -> StepCode.BRIEF
        is Say -> StepCode.SAY
        Reminders -> StepCode.REMINDERS
        Messages -> StepCode.MESSAGES
    }
}

data class Routine(
    val id: String,
    val name: String,
    val trigger: RoutineTrigger,
    val steps: List<RoutineStep>,
    val enabled: Boolean = true,
    /** Wall clock ms when it was saved first, and last edited. */
    val created: Long,
    val edited: Long = created,
    val conditions: RoutineConditions = RoutineConditions(),
    val output: RoutineOutput = RoutineOutput.HEADSET_OR_SPEAKER,
    val lock: RoutineLock = RoutineLock.FOLLOW,
    /** The online services the save card listed: what its online steps may reach without you (RoutineGate). */
    val online: Set<OnlineUse> = emptySet(),
) {
    /** The online services its steps reach. */
    val needsOnline: Set<OnlineUse> get() = steps.mapNotNull { it.online }.toSet()
}

// ---------------------------------------------------------------------------------------------- log codes

/**
 * Voice log ids 140–159 are routines' and the morning brief's (VoiceEvent.ROUTINE_TRIGGER to BRIEF_CONTROL, 0.8: 140 to
 * 151 used, the rest free), codes and numbers only, never names or text. These enums are their codes.
 */
enum class TriggerKind(override val id: Int, override val title: String) : LogCode {
    TIME(1, "a time"), ALARM(2, "an alarm turned off"), UNLOCK(3, "the first unlock"), DEVICE(4, "a headset or car"),
    CHARGING(5, "charging"), ASKED(6, "you asked"),
}

/** Which step, for the voice log: stable numbers (never renumber). */
enum class StepCode(override val id: Int, override val title: String) : LogCode {
    BRIEF(1, "morning brief"), SAY(2, "a sentence"), REMINDERS(3, "brief reminders"), MESSAGES(4, "unread messages"),
    SET_TIMER(10, "timer"), SET_ALARM(11, "alarm"), OPEN_APP(12, "open app"), MAPS_SEARCH(13, "maps search"),
    MAPS_NAVIGATE(14, "maps directions"), FLASHLIGHT(15, "flashlight"), SEND_TEXT(16, "text"), DRAFT_EMAIL(17, "email"),
    LOOKUP_CONTACT(18, "contact lookup"), ADD_NOTE(19, "new note"), FIND_NOTES(20, "note search"), ADD_EVENT(21, "new event"),
    READ_CALENDAR(22, "calendar"), WEATHER(23, "weather"), PHONE_STATUS(24, "phone status"), FREE_MEMORY(25, "free memory"),
    FREE_STORAGE(26, "free storage"), ASK_CLAUDE(27, "Ask Claude"), REMEMBER(28, "remember"), FORGET(29, "forget"), CALL(30, "call");

    companion object {
        fun of(t: ToolName): StepCode = when (t) {
            ToolName.SET_TIMER -> SET_TIMER
            ToolName.SET_ALARM -> SET_ALARM
            ToolName.OPEN_APP -> OPEN_APP
            ToolName.MAPS_SEARCH -> MAPS_SEARCH
            ToolName.MAPS_NAVIGATE -> MAPS_NAVIGATE
            ToolName.FLASHLIGHT -> FLASHLIGHT
            ToolName.SEND_TEXT -> SEND_TEXT
            ToolName.DRAFT_EMAIL -> DRAFT_EMAIL
            ToolName.LOOKUP_CONTACT -> LOOKUP_CONTACT
            ToolName.ADD_NOTE -> ADD_NOTE
            ToolName.FIND_NOTES -> FIND_NOTES
            ToolName.ADD_EVENT -> ADD_EVENT
            ToolName.READ_CALENDAR -> READ_CALENDAR
            ToolName.WEATHER -> WEATHER
            ToolName.PHONE_STATUS -> PHONE_STATUS
            ToolName.FREE_MEMORY -> FREE_MEMORY
            ToolName.FREE_STORAGE -> FREE_STORAGE
            ToolName.ASK_CLAUDE -> ASK_CLAUDE
            ToolName.REMEMBER -> REMEMBER
            ToolName.FORGET -> FORGET
            ToolName.CALL -> CALL
        }
    }
}

// ---------------------------------------------------------------------------------------------- validation

object RoutineCheck {
    const val MAX_NAME = 40
    const val MAX_STEPS = 8
    const val MAX_SAY = 200
    const val MAX_DEVICE_ID = 64
    /** Routines in the file at most (the editor says so before the next one). */
    const val MAX_ROUTINES = 50

    private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    /**
     * What is wrong with [r], in fixed words for the editor and the card; empty when it may be saved and run. A step
     * Robot never does without you ([StepClass.NEVER]: freeing memory or storage, Ask Claude) is refused here as well as by
     * the router, and each tool step's fixed arguments must read back the same through the tool's own validation
     * (ToolCalls.parse): a timer over 24 hours or an alarm at 25:00 can't be saved.
     */
    fun problems(r: Routine): List<String> = buildList {
        if (!validId(r.id)) add("The routine has no valid id.")
        val name = r.name.trim()
        if (name.isEmpty()) add("Give the routine a name.")
        if (name.length > MAX_NAME) add("A routine's name is at most $MAX_NAME characters.")
        if (CONTROL.containsMatchIn(r.name)) add("A routine's name is one line of text.")
        if (r.steps.isEmpty()) add("Add at least one step.")
        if (r.steps.size > MAX_STEPS) add("A routine has at most $MAX_STEPS steps.")
        if (r.steps.count { it == RoutineStep.Brief } > 1) add("The morning brief can be in a routine once.")
        for (s in r.steps) {
            if (RoutineGate.classOf(s) == StepClass.NEVER) add("Routines can't do this: ${s.describe()}.")
            when (s) {
                is RoutineStep.Say -> {
                    if (s.text.isBlank()) add("Say what Robot should say.")
                    if (s.text.length > MAX_SAY) add("A sentence to say is at most $MAX_SAY characters.")
                    if (CONTROL.containsMatchIn(s.text)) add("A sentence to say is one line of text.")
                }
                is RoutineStep.Tool -> if (RoutineCodec.readBack(s) != s) add("A step's details can't be used: ${s.describe()}.")
                else -> Unit
            }
        }
        when (val t = r.trigger) {
            is RoutineTrigger.At -> {
                if (t.days.isEmpty() && t.once == null) add("Pick the days, or a date.")
                if (t.time.second != 0 || t.time.nano != 0) add("A routine's time is in whole minutes.")
                if (t.days.isNotEmpty() && t.once != null) add("Pick the days or a date, not both.")
                if (r.conditions.between != null || r.conditions.days != null) add("A routine at a time has its days already.")
            }
            is RoutineTrigger.AlarmDismissed -> if (t.window.from == t.window.to) add("The alarm's hours can't start and end at the same time.")
            is RoutineTrigger.Device -> t.deviceId?.let { if (it.isBlank() || it.length > MAX_DEVICE_ID || CONTROL.containsMatchIn(it)) add("Pick the device again.") }
            else -> Unit
        }
        r.conditions.between?.let { if (it.from == it.to) add("The hours can't start and end at the same time.") }
        if (r.conditions.days?.isEmpty() == true) add("Pick at least one day.")
    }

    fun valid(r: Routine): Boolean = problems(r).isEmpty()
}

// ---------------------------------------------------------------------------------------------- JSON

/** What a routines file held: the routines, or why none can be used ([TooNew]: never written over by this version). */
sealed class RoutineFile {
    /** [dropped]: routines left out because they couldn't be read or checked (never run in part). */
    data class Ok(val routines: List<Routine>, val dropped: Int = 0) : RoutineFile()
    data class TooNew(val version: Int) : RoutineFile()
    data object Damaged : RoutineFile()
}

/**
 * routines.json, schema 1: `{"v":1,"routines":[…]}`. A routine whose trigger, a step or a value can't be read, or which
 * fails [RoutineCheck], is dropped whole: running the steps that could be read would be a routine you never saved.
 * Tool steps keep the tool's id and its arguments, and are read back through ToolCalls.parse (the tool's own limits).
 */
object RoutineCodec {
    const val VERSION = 1

    fun encode(routines: List<Routine>): String = JSONObject().put("v", VERSION).put("routines", JSONArray().apply {
        for (r in routines) put(toJson(r))
    }).toString()

    fun decode(json: String?): RoutineFile {
        val o = try { JSONObject(json ?: return RoutineFile.Damaged) } catch (_: Exception) { return RoutineFile.Damaged }
        val v = (o.opt("v") as? Number)?.toInt() ?: return RoutineFile.Damaged
        if (v > VERSION) return RoutineFile.TooNew(v)
        if (v < 1) return RoutineFile.Damaged
        val a = o.optJSONArray("routines") ?: return RoutineFile.Damaged
        val out = mutableListOf<Routine>()
        var dropped = 0
        val ids = HashSet<String>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i)?.let(::fromJson)
            if (r == null || !ids.add(r.id)) dropped++ else out += r
        }
        return RoutineFile.Ok(out, dropped)
    }

    fun toJson(r: Routine): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("enabled", r.enabled)
        put("created", r.created)
        put("edited", r.edited)
        put("trigger", trigger(r.trigger))
        put("conditions", JSONObject().apply {
            r.conditions.between?.let { put("from", it.from.toString()); put("to", it.to.toString()) }
            r.conditions.days?.let { put("days", days(it)) }
            put("once_per_day", r.conditions.oncePerDay)
        })
        put("steps", JSONArray().apply { for (s in r.steps) put(step(s)) })
        put("output", r.output.id)
        put("lock", r.lock.id)
        put("online", JSONArray().apply { for (u in OnlineUse.entries) if (u in r.online) put(u.id) })
    }

    /** One routine, or null when anything in it can't be read or it fails [RoutineCheck]. */
    fun fromJson(o: JSONObject): Routine? = try {
        val c = o.optJSONObject("conditions")
        val between = if (c != null && c.has("from")) TimeWindow(time(str(c, "from"))!!, time(str(c, "to"))!!) else null
        val r = Routine(
            id = str(o, "id")!!,
            name = str(o, "name")!!,
            trigger = trigger(o.getJSONObject("trigger"))!!,
            steps = o.getJSONArray("steps").let { a -> (0 until a.length()).map { step(a.getJSONObject(it))!! } },
            enabled = if (o.has("enabled")) bool(o, "enabled")!! else true,
            created = long(o, "created")!!,
            edited = long(o, "edited") ?: long(o, "created")!!,
            conditions = RoutineConditions(
                between = between,
                days = c?.optJSONArray("days")?.let { days(it)!! },
                oncePerDay = if (c != null && c.has("once_per_day")) bool(c, "once_per_day")!! else true,
            ),
            output = if (o.has("output")) RoutineOutput.of(str(o, "output"))!! else RoutineOutput.HEADSET_OR_SPEAKER,
            lock = if (o.has("lock")) RoutineLock.of(str(o, "lock"))!! else RoutineLock.FOLLOW,
            online = o.optJSONArray("online")?.let { a -> (0 until a.length()).map { OnlineUse.of(a.opt(it) as? String)!! }.toSet() } ?: emptySet(),
        )
        r.takeIf { RoutineCheck.valid(it) }
    } catch (_: Exception) { null }

    fun trigger(t: RoutineTrigger): JSONObject = JSONObject().apply {
        when (t) {
            is RoutineTrigger.At -> {
                put("type", "time")
                put("time", t.time.toString())
                put("days", days(t.days))
                t.once?.let { put("once", it.toString()) }
            }
            is RoutineTrigger.AlarmDismissed -> { put("type", "alarm"); put("from", t.window.from.toString()); put("to", t.window.to.toString()) }
            is RoutineTrigger.Unlock -> { put("type", "unlock"); put("after", t.after.toString()) }
            is RoutineTrigger.Device -> { put("type", "device"); put("kind", t.type.id); t.deviceId?.let { put("device", it) }; put("connect", t.connect) }
            is RoutineTrigger.Charging -> { put("type", "charging"); put("start", t.start) }
            RoutineTrigger.Asked -> put("type", "asked")
        }
    }

    fun trigger(o: JSONObject): RoutineTrigger? = when (str(o, "type")) {
        "time" -> RoutineTrigger.At(time(str(o, "time")) ?: return null, days(o.optJSONArray("days") ?: JSONArray()) ?: return null,
            if (o.has("once")) date(str(o, "once")) ?: return null else null)
        "alarm" -> RoutineTrigger.AlarmDismissed(TimeWindow(time(str(o, "from")) ?: return null, time(str(o, "to")) ?: return null))
        "unlock" -> RoutineTrigger.Unlock(time(str(o, "after")) ?: return null)
        "device" -> RoutineTrigger.Device(DeviceKind.of(str(o, "kind")) ?: return null, if (o.has("device")) str(o, "device") ?: return null else null,
            bool(o, "connect") ?: return null)
        "charging" -> RoutineTrigger.Charging(bool(o, "start") ?: return null)
        "asked" -> RoutineTrigger.Asked
        else -> null
    }

    fun step(s: RoutineStep): JSONObject = JSONObject().apply {
        when (s) {
            is RoutineStep.Tool -> {
                put("type", "tool")
                put("tool", s.action.tool.id)
                put("args", Json.obj(args(s.action)))
                if (nearest(s.action)) put("nearest", true)
            }
            RoutineStep.Brief -> put("type", "brief")
            is RoutineStep.Say -> { put("type", "say"); put("text", s.text) }
            RoutineStep.Reminders -> put("type", "reminders")
            RoutineStep.Messages -> put("type", "messages")
        }
    }

    /**
     * A step, or null. A tool step goes through ToolCalls.parse like a call the model made: an unknown tool, a missing or
     * unreadable argument is refused, never repaired.
     */
    fun step(o: JSONObject): RoutineStep? = when (str(o, "type")) {
        "tool" -> {
            val name = str(o, "tool") ?: return null
            val args = o.optJSONObject("args") ?: return null
            when (val p = ToolCalls.parse(RawToolCall(name, Json.toMap(args)))) {
                is ParsedCall.Ok -> RoutineStep.Tool(withNearest(p.action, bool(o, "nearest") == true))
                is ParsedCall.Invalid -> null
            }
        }
        "brief" -> RoutineStep.Brief
        "say" -> RoutineStep.Say(str(o, "text") ?: return null)
        "reminders" -> RoutineStep.Reminders
        "messages" -> RoutineStep.Messages
        else -> null
    }

    /** [s] written and read back: equal to [s] when its arguments are within the tool's limits. */
    fun readBack(s: RoutineStep.Tool): RoutineStep? = step(JSONObject(step(s).toString()))

    /**
     * A tool step's fixed arguments as the tool takes them (ToolSpecs' names). Only what was said is kept: a resolved
     * contact, app or calendar is found again when the step runs, and shown on its card.
     */
    fun args(a: Action): Map<String, Any?> = when (a) {
        is Action.SetTimer -> mapOf("seconds" to a.seconds)
        is Action.SetAlarm -> mapOf("hour" to a.hour, "minute" to a.minute)
        is Action.OpenApp -> mapOf("name" to a.name)
        is Action.MapsSearch -> mapOf("query" to a.query)
        is Action.MapsNavigate -> mapOf("destination" to a.destination)
        is Action.Flashlight -> mapOf("on" to a.on)
        is Action.SendText -> mapOf("to" to a.to, "message" to a.message)
        is Action.DraftEmail -> mapOf("to" to a.to, "subject" to a.subject, "body" to a.text)
        is Action.LookupContact -> mapOf("name" to a.name)
        is Action.Call -> mapOf("to" to a.to)
        is Action.AddNote -> buildMap { put("text", a.text); a.title?.let { put("title", it) } }
        is Action.FindNotes -> mapOf("query" to a.query)
        is Action.AddEvent -> buildMap { put("title", a.title); put("when", a.whenText); a.minutes?.let { put("duration_minutes", it) } }
        is Action.AskClaude -> mapOf("question" to a.text)
        is Action.Weather -> buildMap { a.place?.let { put("place", it) }; a.day?.let { put("day", it) } }
        is Action.PhoneCheck -> mapOf("about" to a.part.name.lowercase())
        is Action.FreeMemory -> buildMap { a.app?.let { put("app", it) } }
        is Action.FreeStorage -> mapOf("what" to a.way.name.lowercase())
        is Action.ReadCalendar -> mapOf("day" to a.day)
        // Never a routine's step (RoutineGate: NEVER); written as asked all the same.
        is Action.Remember -> mapOf("text" to a.text)
        is Action.Forget -> mapOf("query" to a.query)
    }

    private fun nearest(a: Action): Boolean = (a as? Action.MapsSearch)?.nearest == true || (a as? Action.MapsNavigate)?.nearest == true

    private fun withNearest(a: Action, nearest: Boolean): Action = when (a) {
        is Action.MapsSearch -> a.copy(nearest = nearest)
        is Action.MapsNavigate -> a.copy(nearest = nearest)
        else -> a
    }

    /** ISO day numbers, Monday 1 to Sunday 7, in order. */
    fun days(d: Set<DayOfWeek>): JSONArray = JSONArray().apply { for (x in d.sortedBy { it.value }) put(x.value) }

    fun days(a: JSONArray): Set<DayOfWeek>? {
        val out = mutableSetOf<DayOfWeek>()
        for (i in 0 until a.length()) {
            val n = (a.opt(i) as? Number)?.toInt() ?: return null
            if (n !in 1..7) return null
            out += DayOfWeek.of(n)
        }
        return out
    }

    fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.opt(k) as? String
    fun long(o: JSONObject, k: String): Long? = (o.opt(k) as? Number)?.toLong()
    fun bool(o: JSONObject, k: String): Boolean? = o.opt(k) as? Boolean
    fun time(s: String?): LocalTime? = try { s?.let { LocalTime.parse(it) } } catch (_: DateTimeParseException) { null }
    fun date(s: String?): LocalDate? = try { s?.let { LocalDate.parse(it) } } catch (_: DateTimeParseException) { null }
}
