package app.heyrobot.core

import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.HoldWhy
import app.heyrobot.core.output.Keep
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.Trust
import app.heyrobot.core.output.Utterance
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// Pure. What the app needs around the routines' core to run them (0.8): the built-in morning brief as a routine, the
// state file (the ledger, AlarmWatch, the last run of each routine), each step's fate in a run, the spoken parts with
// "next", "skip", "repeat" and "stop", the runs a reboot or Doze missed, which receivers a set of routines needs,
// OutputGuard's decision for each part, the editor's steps and the words Settings and the notifications show.

// ---------------------------------------------------------------------------------------------- the built-in brief

/**
 * The morning brief as a routine: one step ([RoutineStep.Brief]), on the trigger chosen in Settings › Routines ›
 * Morning brief ("When I turn off my alarm" by default, between [RoutineSettings.alarmWindow]'s hours), on while
 * [RoutineSettings.briefEnabled], its weather online by itself only once its card listed Open-Meteo ([RoutineSettings.briefOnline]).
 */
object BriefRoutine {
    const val ID = "morning-brief"
    const val NAME = "Morning brief"

    fun of(rs: RoutineSettings): Routine {
        val trigger = when (val t = rs.briefTrigger) {
            is RoutineTrigger.AlarmDismissed -> RoutineTrigger.AlarmDismissed(rs.alarmWindow)
            else -> t
        }
        return Routine(ID, NAME, trigger, listOf(RoutineStep.Brief), enabled = rs.briefEnabled, created = 0, output = rs.briefOutput,
            online = if (rs.briefOnline) setOf(OnlineUse.OPEN_METEO) else emptySet())
    }

    /** Settings' choices for when the brief runs. */
    enum class When(val title: String) {
        ALARM("When I turn off my alarm"), TIME("At a time"), UNLOCK("At the first unlock"), ASKED("Only when I ask");

        companion object {
            fun of(t: RoutineTrigger): When = when (t) {
                is RoutineTrigger.At -> TIME
                is RoutineTrigger.Unlock -> UNLOCK
                RoutineTrigger.Asked -> ASKED
                else -> ALARM
            }
        }
    }

    /** The trigger for [w], keeping what [now] already says (the time and days of an At). */
    fun trigger(w: When, now: RoutineTrigger): RoutineTrigger = when (w) {
        When.ALARM -> RoutineTrigger.AlarmDismissed()
        When.TIME -> now as? RoutineTrigger.At ?: RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS)
        When.UNLOCK -> now as? RoutineTrigger.Unlock ?: RoutineTrigger.Unlock()
        When.ASKED -> RoutineTrigger.Asked
    }
}

// ---------------------------------------------------------------------------------------------- state.json

/** The last run of a routine, for Settings ("Today 06:47 · an alarm turned off · 1 step, 1 ready"): counts only. */
data class RunRecord(val at: Long, val kind: TriggerKind, val steps: Int, val ready: Int = 0, val held: Int = 0)

/**
 * files/robot/state.json: the run ledger (each trigger runs a routine once), AlarmWatch's state across a :voice restart,
 * [checked]: when the schedule was last caught up (missed runs are looked for after it), and the last run of each routine.
 */
data class RoutineState(
    val ledger: RunLedger = RunLedger(),
    val alarm: AlarmWatchState = AlarmWatchState(),
    val checked: Long = 0,
    val runs: Map<String, RunRecord> = emptyMap(),
) {
    /** Without routines that no longer exist. */
    fun keep(ids: Set<String>): RoutineState = copy(ledger = ledger.keep(ids), runs = runs.filterKeys { it in ids })
}

object RoutineStateFile {
    fun encode(s: RoutineState): String = JSONObject(RoutineStateCodec.encode(s.ledger, s.alarm)).put("checked", s.checked)
        .put("runs", JSONObject().apply {
            for ((id, r) in s.runs) put(id, JSONObject().put("at", r.at).put("kind", r.kind.id).put("steps", r.steps).put("ready", r.ready).put("held", r.held))
        }).toString()

    /** What can be read; a damaged file is a fresh state (a routine may then run once more, never twice for one trigger). */
    fun decode(json: String?): RoutineState {
        val (ledger, alarm) = RoutineStateCodec.decode(json)
        val o = try { JSONObject(json ?: return RoutineState()) } catch (_: Exception) { return RoutineState() }
        if ((o.opt("v") as? Number)?.toInt() != RoutineStateCodec.VERSION) return RoutineState()
        val runs = buildMap {
            val r = o.optJSONObject("runs")
            if (r != null) for (id in r.keys()) {
                val x = r.optJSONObject(id) ?: continue
                if (!RoutineCheck.validId(id)) continue
                val kind = TriggerKind.entries.firstOrNull { it.id == (x.opt("kind") as? Number)?.toInt() } ?: continue
                put(id, RunRecord(RoutineCodec.long(x, "at") ?: continue, kind, (x.opt("steps") as? Number)?.toInt() ?: 0,
                    (x.opt("ready") as? Number)?.toInt() ?: 0, (x.opt("held") as? Number)?.toInt() ?: 0))
            }
        }
        return RoutineState(ledger, alarm, RoutineCodec.long(o, "checked") ?: 0, runs)
    }
}

// ---------------------------------------------------------------------------------------------- one run

/** One step of a run and what RoutineGate said about it. */
data class PlannedStep(val index: Int, val step: RoutineStep, val go: StepGo)

object RoutinePlan {
    fun plan(r: Routine, origin: RunOrigin, locked: Boolean, settings: RobotSettings, rs: RoutineSettings): List<PlannedStep> =
        r.steps.mapIndexed { i, s -> PlannedStep(i, s, RoutineGate.decide(s, origin, locked, r, settings, rs)) }

    /** A step that runs now (all of it, or its public part). */
    fun runs(go: StepGo): Boolean = go == StepGo.RUN || go == StepGo.RUN_PUBLIC

    /**
     * The sentence a run says about a step that didn't run as itself, or null: skipped weather ("Weather skipped: routines
     * may not go online."). A step on a Ready card or waiting for the unlock is said once at the end ([closing]).
     */
    fun skipLine(go: StepGo, step: RoutineStep, lang: BriefLang): String? = when {
        step.online == null -> null
        go == StepGo.SKIP_OFFLINE -> BriefComposer.missingText(BriefWeatherMissing.NOT_ONLINE, lang)
        go == StepGo.SKIP_NO_CONSENT -> if (lang == BriefLang.EN) "Weather skipped: this routine's card didn't list it." else "Météo sautée : la carte de cette routine ne l'indiquait pas."
        else -> null
    }

    /** What a run that left things for you says last: "1 thing is ready for you on the phone." and "Unlock to hear the rest." */
    fun closing(ready: Int, waiting: Int, lang: BriefLang): List<String> = listOfNotNull(
        ready.takeIf { it > 0 }?.let {
            if (lang == BriefLang.EN) (if (it == 1) "1 thing is ready for you on the phone." else "$it things are ready for you on the phone.")
            else (if (it == 1) "1 chose t'attend sur le téléphone." else "$it choses t'attendent sur le téléphone.")
        },
        waiting.takeIf { it > 0 }?.let { if (lang == BriefLang.EN) "Unlock to hear the rest." else "Déverrouille pour entendre le reste." },
    )
}

// ---------------------------------------------------------------------------------------------- spoken parts

/**
 * One piece of a run's speech that "next", "skip" and "repeat" move by: a brief item (the weather, the calendar), or one
 * step's result. [personal]: OutputGuard's class (a calendar title, a note, a place).
 */
data class SpokenPart(val lines: List<String>, val personal: Boolean, val code: StepCode, val item: BriefItem? = null) {
    val text: String get() = lines.joinToString(" ")
    val chars: Int get() = text.length
}

/**
 * Where a run's speech is: the parts, the one at [at], whether it is being said ([inPart]) and whether it was stopped.
 * "next" and "skip" move on to the next part, "repeat" says the part again (the last one, between parts), "stop" ends it.
 */
data class PlayState(val parts: List<SpokenPart> = emptyList(), val at: Int = 0, val inPart: Boolean = false, val stopped: Boolean = false) {
    val current: SpokenPart? get() = if (stopped) null else parts.getOrNull(at)
    val done: Boolean get() = stopped || at >= parts.size

    /** The current part started playing. */
    fun started(): PlayState = copy(inPart = true)

    /** The current part ended by itself. */
    fun played(): PlayState = copy(at = at + 1, inPart = false)

    fun plus(more: List<SpokenPart>): PlayState = copy(parts = parts + more)

    fun control(c: BriefControl): PlayState = when (c) {
        BriefControl.NEXT, BriefControl.SKIP -> copy(at = minOf(parts.size, at + 1), inPart = false)
        BriefControl.REPEAT -> if (inPart || at == 0) copy(inPart = false, stopped = false) else copy(at = at - 1, inPart = false, stopped = false)
        BriefControl.STOP -> copy(stopped = true, inPart = false)
    }
}

object BriefParts {
    /** The brief's lines, one part per item (the greeting, the weather…); "Unlock to hear your day" is a part of its own. */
    fun of(lines: List<BriefLine>): List<SpokenPart> {
        val out = mutableListOf<SpokenPart>()
        for (l in lines) {
            val last = out.lastOrNull()
            if (last != null && l.item != null && last.item == l.item) out[out.lastIndex] = last.copy(lines = last.lines + l.text, personal = last.personal || l.personal)
            else out += SpokenPart(listOf(l.text), l.personal, StepCode.BRIEF, l.item)
        }
        return out
    }
}

/**
 * 0.7's seam: how many unread messages the brief says (MessageSource.unread() once messages exist). Until then
 * [NONE]: the brief says nothing about messages.
 */
fun interface UnreadSource {
    fun unread(): UnreadCount?

    companion object {
        val NONE = UnreadSource { null }
    }
}

// ---------------------------------------------------------------------------------------------- missed runs, receivers

/** A time routine whose time passed while nothing ran it (Doze, the phone off, not unlocked after a reboot). */
data class LateRun(val routine: Routine, val slot: LocalDateTime, val due: ZonedDateTime, val what: MissedRun)

object RoutineCatchUp {
    /**
     * The time routines due in (since, now] that didn't run: the latest occurrence of each only (never one run per missed
     * day), and not one the ledger shows as run. [LateRun.what]: run now (≤ 10 min late), a Ready card (≤ 3 h, the same
     * day), or nothing.
     */
    fun late(routines: List<Routine>, since: ZonedDateTime, now: ZonedDateTime, ledger: RunLedger): List<LateRun> =
        routines.filter { it.enabled }.mapNotNull { r ->
            val t = r.trigger as? RoutineTrigger.At ?: return@mapNotNull null
            val due = RoutineSchedule.lastMissed(t, since, now) ?: return@mapNotNull null
            val slot = RoutineSchedule.slot(t, due)
            if (ledger.last[r.id]?.key == RoutineTriggers.timeKey(slot)) null else LateRun(r, slot, due, RoutineSchedule.missed(due, now))
        }

    /** The time an alarm set for [scheduledAt] (ms) fired at [now]: run, offer or drop. */
    fun fired(scheduledAt: Long, now: ZonedDateTime): MissedRun = RoutineSchedule.missed(Instant.ofEpochMilli(scheduledAt).atZone(now.zone), now)
}

/** What :voice listens for, so nothing is registered that no routine uses (design §2.7). */
object TriggerNeeds {
    /**
     * The trigger kinds the enabled [routines] wait for; [waitingForUnlock]: a held part or a run waits for the unlock, so
     * USER_PRESENT is needed too. The time kind is the exact alarm; ASKED needs nothing.
     */
    fun of(routines: List<Routine>, waitingForUnlock: Boolean = false): Set<TriggerKind> =
        routines.filter { it.enabled }.map { it.trigger.kind }.toSet() - TriggerKind.ASKED +
            (if (waitingForUnlock) setOf(TriggerKind.UNLOCK) else emptySet())

    /** "Get ready while the alarm rings": a routine after an alarm speaks, and the brief's setting allows it. */
    fun prepareOnAlert(routines: List<Routine>, rs: RoutineSettings): Boolean =
        rs.briefPrepare && routines.any { it.enabled && it.trigger is RoutineTrigger.AlarmDismissed && it.output != RoutineOutput.SILENT }
}

// ---------------------------------------------------------------------------------------------- where it may be said

object RoutineOutputs {
    /**
     * OutputGuard's decision for [part] of an unattended run: on the phone (the routine speaks "Headset, else the phone
     * speaker"), or in a headset session for "Headset only" ([headset]: the headset sound goes to now, null when none is
     * connected; [trust] from DeviceMemory, or by its type until headset mode remembers devices). [everything]: the brief's
     * lock level is "Everything" (RoutineGate let a personal part run locked). Null: "Don't speak".
     */
    fun decide(part: SpokenPart, output: RoutineOutput, phone: PhoneState, headset: SeenDevice?, trust: Trust, everything: Boolean): OutputDecision? {
        val u = Utterance(if (part.personal) SpeechClass.PERSONAL else SpeechClass.PUBLIC, SpeechSource.ROUTINE, part.chars)
        val ctx = when (output) {
            RoutineOutput.SILENT -> return null
            RoutineOutput.HEADSET_ONLY -> OutputContext(SessionKind.HEADSET, headset, if (headset == null) Trust.NOT_PRIVATE else trust, phone, headset,
                lockAllowsPersonal = everything)
            RoutineOutput.HEADSET_OR_SPEAKER -> OutputContext(SessionKind.PHONE, null, Trust.NOT_PRIVATE, phone, lockAllowsPersonal = everything)
        }
        return OutputGuard.decide(u, ctx, GuardSettings())
    }

    /** A decision that waits for the unlock (the part is kept, "Finish when I unlock"), rather than dropped. */
    fun waitsForUnlock(d: OutputDecision?): Boolean = d is OutputDecision.Hold && d.keep == Keep.UNLOCK

    /** Why a part wasn't said, for the voice log (OUTPUT_HELD comes with headset mode; routines log the run's held count). */
    fun why(d: OutputDecision?): HoldWhy? = (d as? OutputDecision.Hold)?.why
}

/** A routine phrase the panel handled, for the voice log (150 ROUTINE_COMMAND, a). */
enum class RoutineCommandCode(override val id: Int, override val title: String) : LogCode {
    BRIEF_NOW(1, "the brief now"), CREATE(2, "make a routine"), RUN(3, "run a routine"), LIST(4, "list the routines"),
    DELETE(5, "delete a routine"), REMIND(6, "a brief reminder");

    companion object {
        fun of(c: RoutineCommand): RoutineCommandCode = when (c) {
            RoutineCommand.BriefNow -> BRIEF_NOW
            is RoutineCommand.Create -> CREATE
            is RoutineCommand.Run -> RUN
            RoutineCommand.ListAll -> LIST
            is RoutineCommand.Delete -> DELETE
            is RoutineCommand.Remind -> REMIND
        }
    }
}

/** How a run was carried out loud, for the voice log (147 ROUTINE_SPEECH, a). */
enum class SpeechWay(override val id: Int, override val title: String) : LogCode {
    FOREGROUND(1, "spoken by the foreground service"), NOTIFICATION(2, "Android refused it: a notification to tap"),
    PANEL(3, "in the panel"), NO_ASSISTANT(4, "Robot isn't the assistant: a notification"), SILENT(5, "notification only"),
    NOTHING(6, "nothing to say"),
}

// ---------------------------------------------------------------------------------------------- the editor

/**
 * The steps Settings › Routines' editor offers (design §2.3 "Do"), each one of Robot's tools with its one argument, or a
 * routine-only step. Texts, emails and new events come from routines made by voice (two arguments each); never
 * freeing memory or storage or Ask Claude.
 */
enum class StepChoice(val title: String, val field: String? = null, val hint: String? = null) {
    BRIEF("Read your morning brief"),
    SAY("Say a sentence", "What Robot says", "One line, up to ${RoutineCheck.MAX_SAY} characters."),
    REMINDERS("Read your brief reminders"),
    WEATHER("Tell the weather here today"),
    CALENDAR("Read today's calendar"),
    PHONE("Say the phone's status"),
    NOTES("Find notes", "Words to look for", null),
    TIMER("Set a timer", "Minutes", "1 to 1440."),
    ALARM("Set an alarm", "Time", "As 06:30, on a 24-hour clock."),
    FLASH_ON("Turn the flashlight on"),
    FLASH_OFF("Turn the flashlight off"),
    NOTE("Add a note", "The note's text", null),
    OPEN_APP("Open an app", "The app's name", "It waits for you: a Ready card you tap."),
    MAPS_SEARCH("Search Organic Maps", "What to search for", "It waits for you: a Ready card you tap."),
    MAPS_NAVIGATE("Directions in Organic Maps", "Where to", "It waits for you: a Ready card you tap.");

    /** The step for [arg] (what was typed, when [field] asks for something), or null when it can't be used. */
    fun build(arg: String? = null): RoutineStep? {
        val a = arg?.trim().orEmpty()
        if (field != null && a.isEmpty()) return null
        return when (this) {
            BRIEF -> RoutineStep.Brief
            SAY -> RoutineStep.Say(a).takeIf { a.length <= RoutineCheck.MAX_SAY && !a.contains('\n') }
            REMINDERS -> RoutineStep.Reminders
            WEATHER -> tool(ToolName.WEATHER, emptyMap())
            CALENDAR -> tool(ToolName.READ_CALENDAR, mapOf("day" to "today"))
            PHONE -> tool(ToolName.PHONE_STATUS, mapOf("about" to "all"))
            NOTES -> tool(ToolName.FIND_NOTES, mapOf("query" to a))
            TIMER -> a.toIntOrNull()?.takeIf { it in 1..1440 }?.let { tool(ToolName.SET_TIMER, mapOf("seconds" to it * 60)) }
            ALARM -> Regex("^([01]?\\d|2[0-3])\\s*[:.h]\\s*([0-5]\\d)$").find(a)?.let { m ->
                tool(ToolName.SET_ALARM, mapOf("hour" to m.groupValues[1].toInt(), "minute" to m.groupValues[2].toInt()))
            }
            FLASH_ON -> tool(ToolName.FLASHLIGHT, mapOf("on" to true))
            FLASH_OFF -> tool(ToolName.FLASHLIGHT, mapOf("on" to false))
            NOTE -> tool(ToolName.ADD_NOTE, mapOf("text" to a))
            OPEN_APP -> tool(ToolName.OPEN_APP, mapOf("name" to a))
            MAPS_SEARCH -> tool(ToolName.MAPS_SEARCH, mapOf("query" to a))
            MAPS_NAVIGATE -> tool(ToolName.MAPS_NAVIGATE, mapOf("destination" to a))
        }
    }

    /** What is wrong with [arg], in a line for the edit dialog; null when it can be used. */
    fun problem(arg: String?): String? = if (build(arg) != null) null else when {
        arg.isNullOrBlank() -> "Type ${field?.replaceFirstChar { it.lowercase(Locale.ROOT) } ?: "something"}."
        this == TIMER -> "A number of minutes, 1 to 1440."
        this == ALARM -> "A time such as 06:30."
        this == SAY -> "One line, up to ${RoutineCheck.MAX_SAY} characters."
        else -> "Robot can't use that."
    }

    /** "runs by itself", "waits for you", and "goes online: Open-Meteo (the weather)" for the weather. */
    fun badge(): String = badge(sample())

    /** This step with an argument it takes, to show what kind of step it is. */
    fun sample(): RoutineStep = build(when (this) { TIMER -> "5"; ALARM -> "07:00"; else -> "x" })!!

    companion object {
        fun badge(s: RoutineStep): String = RoutineGate.classOf(s).badge + (s.online?.let { " · goes online: ${it.title}" } ?: "")

        private fun tool(t: ToolName, args: Map<String, Any?>): RoutineStep? = when (val p = ToolCalls.parse(RawToolCall(t.id, args))) {
            is ParsedCall.Ok -> RoutineStep.Tool(p.action).takeIf { RoutineCodec.readBack(it) == it }
            is ParsedCall.Invalid -> null
        }
    }
}

// ---------------------------------------------------------------------------------------------- words

object RoutineTexts {
    private fun hm(t: LocalTime) = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

    /** A routine's row: "every weekday at 07:00 · 2 steps", "only when you ask · 1 step · off". */
    fun row(r: Routine): String = RoutineCards.whenText(r).replaceFirstChar { it.uppercase() } + " · " +
        (if (r.steps.size == 1) "1 step" else "${r.steps.size} steps") + if (r.enabled) "" else " · off"

    /** "Last run: today 06:47 · an alarm turned off · 1 step, 1 ready", "Not run yet". */
    fun lastRun(rec: RunRecord?, now: ZonedDateTime): String {
        rec ?: return "Not run yet"
        val at = Instant.ofEpochMilli(rec.at).atZone(now.zone)
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().minusDays(1) -> "yesterday"
            else -> BriefClock.dateEn(at.toLocalDate())
        }
        val extra = listOfNotNull(rec.ready.takeIf { it > 0 }?.let { "$it ready" }, rec.held.takeIf { it > 0 }?.let { "$it waited for the unlock" })
        return "Last run: $day ${hm(at.toLocalTime())} · ${rec.kind.title} · " + (if (rec.steps == 1) "1 step" else "${rec.steps} steps") +
            (if (extra.isEmpty()) "" else ", " + extra.joinToString(", "))
    }

    /** The speaking notification's title. */
    fun speaking(r: Routine): String = if (r.id == BriefRoutine.ID || r.steps == listOf(RoutineStep.Brief)) "Robot is reading your morning brief" else "Robot is running “${r.name}”"

    /** On the lock screen (the notification's public version): nothing about which routine. */
    const val SPEAKING_PUBLIC = "Robot is speaking"

    /** Android didn't let Robot speak by itself: a tap does. */
    fun readyToPlay(r: Routine): String = if (r.id == BriefRoutine.ID) "Morning brief ready" else "“${r.name}” is ready"

    const val PLAY_TEXT = "Tap Play to hear it."
    const val CONTINUE = "Continue your brief"
    const val CONTINUE_TEXT = "Unlocked: tap to hear the rest."

    /** Robot isn't the assistant, so it can't speak: "Your morning brief is ready · Open Robot". */
    fun notAssistant(r: Routine): String = if (r.id == BriefRoutine.ID) "Your morning brief is ready" else "Your “${r.name}” routine is ready"
    const val NOT_ASSISTANT_TEXT = "Robot isn't your digital assistant right now, so it can't speak. Open Robot to set it up."

    /** "Don't speak": what the run would have said, in a notification. */
    fun silentTitle(r: Routine): String = "Ran “${r.name}”"

    /** Settings › Routines' warnings. */
    const val NEEDS_ASSISTANT = "Routines need Robot as the digital assistant."
    const val NOT_LOCKED = "Routines don't run while the phone is locked: they wait for the unlock."
    const val DAMAGED = "Robot can't read your routines file. Nothing runs until you start over; the file is kept aside."
    const val TOO_NEW = "Your routines were saved by a newer Robot. Update Robot to use them."
}
