// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.HoldWhy
import app.heyrobot.core.output.Keep
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.Trust
import app.heyrobot.core.output.Utterance
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
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
    const val NAME = "Morning brief" // l10n: not text (the built-in routine's saved name; shown as RoutineStrings.morningBrief, RoutineTexts.name)

    fun of(rs: RoutineSettings): Routine {
        val trigger = when (val t = rs.briefTrigger) {
            is RoutineTrigger.AlarmDismissed -> RoutineTrigger.AlarmDismissed(rs.alarmWindow)
            else -> t
        }
        return Routine(ID, NAME, trigger, listOf(RoutineStep.Brief), enabled = rs.briefEnabled, created = 0, output = rs.briefOutput,
            online = if (rs.briefOnline) setOf(OnlineUse.OPEN_METEO) else emptySet())
    }

    /** Settings' choices for when the brief runs. */
    enum class When(private val msg: Msg) {
        ALARM(RoutineStrings.whenAlarm), TIME(RoutineStrings.whenTime), UNLOCK(RoutineStrings.whenUnlock), ASKED(RoutineStrings.whenAsked);

        /** 1.1: Settings' words without a language yet; [title] is their English. */
        val text: Phrase get() = msg()
        val title: String get() = text.en()

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
    fun skipLine(go: StepGo, step: RoutineStep, lang: Lang): String? = skipText(go, step)?.let { BriefComposer.shown(it, lang) }

    /** 1.1: [skipLine] without a language yet. */
    fun skipText(go: StepGo, step: RoutineStep): Phrase? = when {
        step.online == null -> null
        go == StepGo.SKIP_OFFLINE -> BriefComposer.missingPhrase(BriefWeatherMissing.NOT_ONLINE)
        go == StepGo.SKIP_NO_CONSENT -> RoutineStrings.skippedNoConsent()
        else -> null
    }

    /** What a run that left things for you says last: "1 thing is ready for you on the phone." and "Unlock to hear the rest." */
    fun closing(ready: Int, waiting: Int, lang: Lang): List<String> = closingText(ready, waiting).map { BriefComposer.shown(it, lang) }

    /** 1.1: [closing] without a language yet. */
    fun closingText(ready: Int, waiting: Int): List<Phrase> = listOfNotNull(
        ready.takeIf { it > 0 }?.let { RoutineStrings.readyOnPhone(it) },
        waiting.takeIf { it > 0 }?.let { RoutineStrings.unlockRest() },
    )
}

// ---------------------------------------------------------------------------------------------- spoken parts

/**
 * One piece of a run's speech that "next", "skip" and "repeat" move by: a brief item (the weather, the calendar), or one
 * step's result. [personal]: OutputGuard's class (a calendar title, a note, a place).
 */
data class SpokenPart(val lines: List<String>, val personal: Boolean, val code: StepCode, val item: BriefItem? = null,
    /** 1.1: [lines] as the screen shows them (French with its no-break spaces); the voice says [lines]. */
    val shown: List<String> = lines) {
    val text: String get() = lines.joinToString(" ")
    val shownText: String get() = shown.joinToString(" ")
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
            if (last != null && l.item != null && last.item == l.item) {
                out[out.lastIndex] = last.copy(lines = last.lines + l.text, shown = last.shown + l.shown, personal = last.personal || l.personal)
            } else out += SpokenPart(listOf(l.text), l.personal, StepCode.BRIEF, l.item, listOf(l.shown))
        }
        return out
    }
}

/**
 * How many unread messages the brief says (0.8: the voice process asks the main process's MessagesBridge, which counts
 * what 0.7's messages hold, by app, and nothing when Settings › Messages is off). [NONE]: the brief says nothing about
 * messages.
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
     * day), not one the ledger shows as run, and never one from before the routine was last saved. [LateRun.what]: run now
     * (≤ 10 min late), a Ready card (≤ 3 h, the same day), or nothing.
     */
    fun late(routines: List<Routine>, since: ZonedDateTime, now: ZonedDateTime, ledger: RunLedger): List<LateRun> =
        routines.filter { it.enabled }.mapNotNull { r ->
            val t = r.trigger as? RoutineTrigger.At ?: return@mapNotNull null
            val from = maxOf(since, Instant.ofEpochMilli(r.edited).atZone(now.zone))
            val due = RoutineSchedule.lastMissed(t, from, now) ?: return@mapNotNull null
            val slot = RoutineSchedule.slot(t, due)
            if (ledger.last[r.id]?.key == RoutineTriggers.timeKey(slot)) null else LateRun(r, slot, due, RoutineSchedule.missed(due, now))
        }

    /** The time an alarm set for [scheduledAt] (ms) fired at [now]: run, offer or drop. */
    fun fired(scheduledAt: Long, now: ZonedDateTime): MissedRun = RoutineSchedule.missed(Instant.ofEpochMilli(scheduledAt).atZone(now.zone), now)
}

/** What :voice listens for, so nothing is registered that no routine uses. */
object TriggerNeeds {
    /**
     * The trigger kinds the enabled [routines] wait for; [waitingForUnlock]: a held part or a run waits for the unlock, so
     * USER_PRESENT is needed too. The time kind is the exact alarm; ASKED needs nothing.
     */
    fun of(routines: List<Routine>, waitingForUnlock: Boolean = false): Set<TriggerKind> =
        routines.filter { it.enabled }.map { it.trigger.kind }.toSet() - TriggerKind.ASKED +
            (if (waitingForUnlock) setOf(TriggerKind.UNLOCK) else emptySet())

    /**
     * "Get ready while the alarm rings": a routine after an alarm speaks the brief with its weather, and the brief's
     * setting allows it. The early fetch goes online only where that routine's own run would (RoutineGate.briefWeather):
     * "Routines may go online by themselves" on and Open-Meteo on its card. Clock's ALARM_ALERT comes to an exported
     * receiver any app can send to, so it must never take Robot online where the routine itself wouldn't.
     */
    fun prepareOnAlert(routines: List<Routine>, rs: RoutineSettings): Boolean =
        rs.briefPrepare && rs.online && BriefItem.WEATHER in rs.briefItems && routines.any {
            it.enabled && it.trigger is RoutineTrigger.AlarmDismissed && it.output != RoutineOutput.SILENT && RoutineStep.Brief in it.steps &&
                RoutineGate.briefWeather(it, RunOrigin.UNATTENDED, rs) == null
        }
}

// ---------------------------------------------------------------------------------------------- where it may be said

/**
 * Where an unattended run's sound goes now (0.8 review), as the voice process reads it: [routed] the output Robot's voice
 * would play on (AudioManager.getAudioDevicesForAttributes; null: the phone itself), [trust] DeviceMemory's word on it, and
 * [car] the car when car mode runs with it connected ([carTrusted]: its trusted session, [readAloud]: Settings › Car
 * mode › "Read aloud when others may hear").
 */
data class RoutineRoute(
    val routed: app.heyrobot.core.output.SeenDevice? = null,
    val trust: Trust = Trust.NOT_PRIVATE,
    val car: app.heyrobot.core.output.SeenDevice? = null,
    val carTrusted: Boolean = false,
    val readAloud: app.heyrobot.core.output.ReadAloud = app.heyrobot.core.output.ReadAloud.ASK,
) {
    /** Robot's voice plays on the phone itself (its speaker or earpiece). */
    val onPhone: Boolean get() = routed == null || routed.type.family == app.heyrobot.core.output.DeviceFamily.BUILTIN
}

object RoutineOutputs {
    /** What "Headset only" counts as a headset: worn, never a speaker, line out or the phone. */
    private val HEADSETS = setOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.BT_A2DP, RouteType.BT_SCO,
        RouteType.BLE_HEADSET, RouteType.HEARING_AID)

    /**
     * OutputGuard's decision for [part] of an unattended run, by where its sound goes now ([route]):
     * - car mode with its car: the car's context, so a personal part follows "Read aloud when others may hear" (never
     *   without "Always": nobody is asked during an unattended run) and the trusted session on the lock screen;
     * - "Headset, else the phone speaker" on the phone itself: as on the phone (personal parts unlocked, or at
     *   "Everything");
     * - anywhere else (a Bluetooth device not confirmed private, a speaker, line out): personal parts only where it is
     *   private, public parts play;
     * - "Headset only": only on a headset (never the car, a speaker or the phone), personal parts only on a private one.
     * [everything]: the brief's lock level is "Everything" (RoutineGate let a personal part run locked). Null: "Don't
     * speak". A personal part kept from a device others may hear waits as the lock screen's does ([waitsForUnlock]).
     */
    fun decide(part: SpokenPart, output: RoutineOutput, phone: PhoneState, route: RoutineRoute, everything: Boolean): OutputDecision? {
        val u = Utterance(if (part.personal) SpeechClass.PERSONAL else SpeechClass.PUBLIC, SpeechSource.ROUTINE, part.chars)
        val guard = GuardSettings(readAloud = route.readAloud)
        val ctx = when (output) {
            RoutineOutput.SILENT -> return null
            RoutineOutput.HEADSET_ONLY -> {
                val headset = route.routed?.takeIf { route.car == null && it.type in HEADSETS }
                OutputContext(SessionKind.HEADSET, headset, if (headset == null) Trust.NOT_PRIVATE else route.trust, phone, headset,
                    lockAllowsPersonal = everything)
            }
            RoutineOutput.HEADSET_OR_SPEAKER -> when {
                route.car != null -> OutputContext(SessionKind.CAR, route.car, Trust.NOT_PRIVATE, phone, route.car, carTrusted = route.carTrusted,
                    lockAllowsPersonal = everything)
                route.onPhone -> OutputContext(SessionKind.PHONE, null, Trust.NOT_PRIVATE, phone, lockAllowsPersonal = everything)
                else -> OutputContext(SessionKind.HEADSET, route.routed, route.trust, phone, route.routed, lockAllowsPersonal = everything)
            }
        }
        return OutputGuard.decide(u, ctx, guard)
    }

    /**
     * A decision that keeps the part for later ("Finish when I unlock") rather than dropping it: held for the lock screen,
     * or kept from a device others may hear (asked about, or not private), to say once it is back on the phone or in
     * private headphones.
     */
    fun waitsForUnlock(d: OutputDecision?): Boolean = when (d) {
        is OutputDecision.Ask -> true
        is OutputDecision.Hold -> d.keep == Keep.UNLOCK || d.why == HoldWhy.NOT_PRIVATE
        else -> false
    }

    /** Why a part wasn't said, for the voice log (OUTPUT_HELD comes with headset mode; routines log the run's held count). */
    fun why(d: OutputDecision?): HoldWhy? = (d as? OutputDecision.Hold)?.why
}

/** A routine phrase the panel handled, for the voice log (150 ROUTINE_COMMAND, a). */
enum class RoutineCommandCode(override val id: Int, override val title: String) : LogCode {
    BRIEF_NOW(1, "the brief now"), CREATE(2, "make a routine"), RUN(3, "run a routine"), LIST(4, "list the routines"),
    DELETE(5, "delete a routine"), REMIND(6, "a brief reminder"), REMIND_ON(7, "a dated reminder"),
    /** 1.1: "remind me about it", nothing to remind and no time: Robot asked what and when. */
    REMIND_WHAT(8, "a reminder with nothing to remind"),
    /** 1.1: "cancel my alarm": Robot can't, and offered the clock app. */
    CANT_CANCEL(9, "an alarm or timer to cancel");

    companion object {
        fun of(c: RoutineCommand): RoutineCommandCode = when (c) {
            RoutineCommand.BriefNow -> BRIEF_NOW
            is RoutineCommand.Create -> CREATE
            is RoutineCommand.Run -> RUN
            RoutineCommand.ListAll -> LIST
            is RoutineCommand.Delete -> DELETE
            is RoutineCommand.Remind -> REMIND
            is RoutineCommand.RemindOn -> REMIND_ON
            RoutineCommand.RemindWhat -> REMIND_WHAT
            is RoutineCommand.CantCancel -> CANT_CANCEL
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
 * The steps Settings › Routines' editor offers, each one of Robot's tools with its one argument, or a
 * routine-only step. Texts, emails and new events come from routines made by voice (two arguments each); never
 * freeing memory or storage or asking a helper.
 */
enum class StepChoice(private val titleMsg: Msg, private val fieldMsg: Msg? = null, private val hintMsg: Msg? = null) {
    BRIEF(RoutineStrings.stepBrief),
    SAY(RoutineStrings.chooseSay, RoutineStrings.fieldSay, RoutineStrings.hintSay),
    REMINDERS(RoutineStrings.stepReminders),
    WEATHER(RoutineStrings.chooseWeather),
    CALENDAR(RoutineStrings.chooseCalendar),
    PHONE(RoutineStrings.choosePhone),
    NOTES(RoutineStrings.chooseNotes, RoutineStrings.fieldNotes, null),
    TIMER(RoutineStrings.chooseTimer, RoutineStrings.fieldTimer, RoutineStrings.hintTimer),
    ALARM(RoutineStrings.chooseAlarm, RoutineStrings.fieldAlarm, RoutineStrings.hintAlarm),
    FLASH_ON(RoutineStrings.chooseFlashOn),
    FLASH_OFF(RoutineStrings.chooseFlashOff),
    NOTE(RoutineStrings.chooseNote, RoutineStrings.fieldNote, null),
    OPEN_APP(RoutineStrings.chooseOpenApp, RoutineStrings.fieldOpenApp, RoutineStrings.hintReady),
    MAPS_SEARCH(RoutineStrings.chooseMapsSearch, RoutineStrings.fieldMapsSearch, RoutineStrings.hintReady),
    MAPS_NAVIGATE(RoutineStrings.chooseMapsNavigate, RoutineStrings.fieldMapsNavigate, RoutineStrings.hintReady);

    /** 1.1: the editor's words without a language yet; [title], [field] and [hint] are their English. */
    val titleText: Phrase get() = titleMsg()
    val fieldText: Phrase? get() = fieldMsg?.invoke()
    val hintText: Phrase? get() = hintMsg?.invoke("max" to RoutineCheck.MAX_SAY)
    val title: String get() = titleText.en()
    val field: String? get() = fieldText?.en()
    val hint: String? get() = hintText?.en()

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
    fun problem(arg: String?, l: Langs = Langs.EN): String? = if (build(arg) != null) null else when {
        arg.isNullOrBlank() -> RoutineStrings.typeField("what" to (fieldText?.render(l)?.replaceFirstChar { it.lowercase(Locale.ROOT) } ?: RoutineStrings.something().render(l))).render(l)
        this == TIMER -> RoutineStrings.problemTimer().render(l)
        this == ALARM -> RoutineStrings.problemAlarm().render(l)
        this == SAY -> RoutineStrings.hintSay("max" to RoutineCheck.MAX_SAY).render(l)
        else -> RoutineStrings.problemUnusable().render(l)
    }

    /** "runs by itself", "waits for you", and "goes online: Open-Meteo (the weather)" for the weather. */
    fun badge(): String = badge(sample())

    /** This step with an argument it takes, to show what kind of step it is. */
    fun sample(): RoutineStep = build(when (this) { TIMER -> "5"; ALARM -> "07:00"; else -> "x" })!!

    companion object {
        fun badge(s: RoutineStep): String = RoutineCards.badgeText(s).en()

        private fun tool(t: ToolName, args: Map<String, Any?>): RoutineStep? = when (val p = ToolCalls.parse(RawToolCall(t.id, args))) {
            is ParsedCall.Ok -> RoutineStep.Tool(p.action).takeIf { RoutineCodec.readBack(it) == it }
            is ParsedCall.Invalid -> null
        }
    }
}

// ---------------------------------------------------------------------------------------------- words

object RoutineTexts {
    /** A routine's name as shown: yours as you wrote it; the built-in brief's in the reader's language (« Résumé du matin »). */
    fun name(id: String, name: String): Phrase = if (id == BriefRoutine.ID) RoutineStrings.morningBrief() else Phrase.plain(name)

    fun name(r: Routine): Phrase = name(r.id, r.name)

    /** "a; b; c", French with its narrow space before each ";" (one entry per pair). */
    fun semicolons(items: List<Phrase>): Phrase = items.reduce { a, b -> RoutineStrings.semicolon("a" to a, "b" to b) }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** A routine's row: "every weekday at 07:00 · 2 steps", "only when you ask · 1 step · off". */
    fun row(r: Routine, l: Langs = Langs.EN): String {
        val args = arrayOf<Pair<String, Any?>>("when" to cap(RoutineCards.whenPhrase(r).render(l)), "steps" to RoutineStrings.steps(r.steps.size))
        return (if (r.enabled) RoutineStrings.row(*args) else RoutineStrings.rowOff(*args)).render(l)
    }

    /** [row]'s first part: "Every weekday at 07:00", capitalised. */
    fun whenRow(r: Routine, l: Langs = Langs.EN): String = cap(RoutineCards.whenPhrase(r).render(l))

    /** "Last run: today 06:47 · an alarm turned off · 1 step, 1 ready", "Not run yet". */
    fun lastRun(rec: RunRecord?, now: ZonedDateTime, l: Langs = Langs.EN): String =
        (lastRunDetail(rec, now)?.let { RoutineStrings.lastRun("run" to it) } ?: RoutineStrings.notRunYet()).render(l)

    /** [lastRun] without "Last run:", for a row titled so; "Not run yet" when it never ran. */
    fun lastRunShort(rec: RunRecord?, now: ZonedDateTime, l: Langs = Langs.EN): String = (lastRunDetail(rec, now) ?: RoutineStrings.notRunYet()).render(l)

    private fun lastRunDetail(rec: RunRecord?, now: ZonedDateTime): Phrase? {
        rec ?: return null
        val at = Instant.ofEpochMilli(rec.at).atZone(now.zone)
        val day: Any = when (at.toLocalDate()) {
            now.toLocalDate() -> RoutineStrings.today()
            now.toLocalDate().minusDays(1) -> RoutineStrings.yesterday()
            else -> at.toLocalDate()
        }
        val counts = listOfNotNull(RoutineStrings.steps(rec.steps), rec.ready.takeIf { it > 0 }?.let { RoutineStrings.nReady(it) },
            rec.held.takeIf { it > 0 }?.let { RoutineStrings.nWaitedUnlock(it) })
        return RoutineStrings.run("day" to day, "time" to EventText.clock(at.hour, at.minute), "kind" to kind(rec.kind), "counts" to Phrase.Join(counts, ", "))
    }

    /** What started a run, as Settings names it ("an alarm turned off"); the voice log keeps [TriggerKind.title]. */
    fun kind(k: TriggerKind): Phrase = when (k) {
        TriggerKind.TIME -> RoutineStrings.kindTime()
        TriggerKind.ALARM -> RoutineStrings.kindAlarm()
        TriggerKind.UNLOCK -> RoutineStrings.kindUnlock()
        TriggerKind.DEVICE -> RoutineStrings.kindDevice()
        TriggerKind.CHARGING -> RoutineStrings.kindCharging()
        TriggerKind.ASKED -> RoutineStrings.kindAsked()
    }

    /** The speaking notification's title. */
    fun speaking(r: Routine): String = speakingText(r).en()

    fun speakingText(r: Routine): Phrase =
        if (r.id == BriefRoutine.ID || r.steps == listOf(RoutineStep.Brief)) RoutineStrings.readingBrief() else RoutineStrings.running("name" to r.name)

    /** On the lock screen (the notification's public version): nothing about which routine. */
    val SPEAKING_PUBLIC: String get() = RoutineStrings.speaking().en()

    /** Android didn't let Robot speak by itself: a tap does. */
    fun readyToPlay(r: Routine): String = readyToPlayText(r).en()

    fun readyToPlayText(r: Routine): Phrase = if (r.id == BriefRoutine.ID) RoutineStrings.briefReady() else RoutineStrings.isReady("name" to r.name)

    val PLAY_TEXT: String get() = playText.en()
    /** "Tap Play to hear it." (the notification's button, in the menus' language). */
    val playText: Phrase get() = RoutineStrings.tapPlay("play" to UiLabel(UiStrings.play))
    val CONTINUE: String get() = RoutineStrings.continueBrief().en()
    val CONTINUE_TEXT: String get() = RoutineStrings.continueText().en()

    /** Robot isn't the assistant, so it can't speak: "Your morning brief is ready · Open Robot". */
    fun notAssistant(r: Routine): String = notAssistantText(r).en()

    fun notAssistantText(r: Routine): Phrase = if (r.id == BriefRoutine.ID) RoutineStrings.briefIsReady() else RoutineStrings.routineIsReady("name" to r.name)
    val NOT_ASSISTANT_TEXT: String get() = RoutineStrings.notAssistant().en()

    /** "Don't speak": what the run would have said, in a notification. */
    fun silentTitle(r: Routine): String = silentTitleText(r).en()

    fun silentTitleText(r: Routine): Phrase = RoutineStrings.ran("name" to name(r))

    /** Settings › Routines' warnings. */
    val NEEDS_ASSISTANT: String get() = RoutineStrings.needsAssistant().en()
    val NOT_LOCKED: String get() = RoutineStrings.notLocked().en()
    val DAMAGED: String get() = RoutineStrings.damaged().en()
    val TOO_NEW: String get() = RoutineStrings.tooNew().en()
}

// ---------------------------------------------------------------------------------------------- the editor's draft

/**
 * A routine being edited in Settings, kept across a recreation (rotation, the process killed) as JSON. Unlike
 * [RoutineCodec.fromJson] it keeps a routine that can't be saved yet (no steps, no name): the editor says what is missing.
 */
object RoutineDraftCodec {
    fun encode(r: Routine): String = RoutineCodec.toJson(r).toString()

    fun decode(json: String?): Routine? = try {
        val o = JSONObject(json ?: return null)
        val c = o.optJSONObject("conditions")
        val between = if (c != null && c.has("from")) {
            val from = RoutineCodec.time(RoutineCodec.str(c, "from"))
            val to = RoutineCodec.time(RoutineCodec.str(c, "to"))
            if (from != null && to != null) TimeWindow(from, to) else null
        } else null
        val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RoutineCodec::step) } }.orEmpty()
        Routine(
            id = RoutineCodec.str(o, "id")!!,
            name = RoutineCodec.str(o, "name").orEmpty(),
            trigger = RoutineCodec.trigger(o.getJSONObject("trigger"))!!,
            steps = steps,
            enabled = RoutineCodec.bool(o, "enabled") ?: true,
            created = RoutineCodec.long(o, "created") ?: 0,
            edited = RoutineCodec.long(o, "edited") ?: 0,
            conditions = RoutineConditions(between, c?.optJSONArray("days")?.let(RoutineCodec::days), c?.let { RoutineCodec.bool(it, "once_per_day") } ?: true),
            output = RoutineOutput.of(RoutineCodec.str(o, "output")) ?: RoutineOutput.HEADSET_OR_SPEAKER,
            lock = RoutineLock.of(RoutineCodec.str(o, "lock")) ?: RoutineLock.FOLLOW,
            online = o.optJSONArray("online")?.let { a -> (0 until a.length()).mapNotNull { OnlineUse.of(a.opt(it) as? String) }.toSet() }.orEmpty(),
        )
    } catch (_: Exception) {
        null
    }

    /** "07:30" (24-hour) as a time, or null. */
    fun time(s: String): LocalTime? = Regex("^\\s*([01]?\\d|2[0-3])\\s*[:.h]\\s*([0-5]\\d)\\s*$").find(s)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    fun hm(t: LocalTime): String = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
}
