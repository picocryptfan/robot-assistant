// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.heyrobot.assistant.ToolExecutor
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.Brief
import app.heyrobot.core.BriefComposer
import app.heyrobot.core.BriefInput
import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLang
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefParts
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.BriefWeather
import app.heyrobot.core.BriefWeatherMissing
import app.heyrobot.core.DayRange
import app.heyrobot.core.GateDecision
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutinePlan
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.RunRecord
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.StepClass
import app.heyrobot.core.StepCode
import app.heyrobot.core.StepGo
import app.heyrobot.core.ToolName
import app.heyrobot.core.TriggerKind
import app.heyrobot.core.UnreadSource
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.WeatherAsk
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.SettingsStore
import java.time.ZonedDateTime
import java.util.Locale

// VOICE PROCESS (0.8). Runs a routine nobody asked for right then (a trigger fired): each step through RoutineGate (core
// RoutinePlan), no model anywhere, so nothing the routine reads can become an action. Reads, timers, alarms, the
// flashlight and a fixed note run; the weather only with "Routines may go online by themselves" and Open-Meteo on the
// routine's card; opening an app, a draft, maps or a new event becomes a Ready card; anything personal on the lock screen
// waits for the unlock. What it has to say goes to RoutineVoice part by part as it comes (the greeting while the weather
// loads). Runs on RoutineHub's thread.

/** What the morning brief is made of, read now: the clock, the weather (cached 15 minutes), today's calendar, brief reminders. */
object BriefSource {
    @Volatile private var cached: Pair<Long, BriefWeather>? = null
    /** Weather fetched while the alarm rang is kept this long ("Get ready while the alarm rings"). */
    private const val KEEP_MS = 15 * 60_000L

    /**
     * 0.8, 0.7's messages for the brief: the main process counts what it holds by app (MessagesBridge, counts only, never
     * who or what, so the same on the lock screen). Nothing while Settings › Messages is off here, on the main thread, or
     * when the bridge doesn't answer within [UNREAD_MS]. Blocking.
     */
    fun unread(ctx: Context): UnreadSource = UnreadSource {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return@UnreadSource null
        if (!(try { app.heyrobot.sys.MessagesStore.reload(ctx).settings.on } catch (_: Throwable) { false })) return@UnreadSource null
        try {
            app.heyrobot.assistant.MessagesLink(ctx).use { link -> kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(UNREAD_MS) { link.unread() } } }
        } catch (_: Throwable) {
            RLog.w("brief_unread")
            null
        }
    }

    private const val UNREAD_MS = 4_000L

    /** The brief's language: Settings › Routines › Morning brief, else the phone's. */
    fun lang(rs: RoutineSettings): BriefLang = rs.briefLang ?: BriefLang.of(Locale.getDefault().toLanguageTag())

    /** The weather for the brief, from the cache when it is fresh; null when there is none to be had. Blocking. */
    fun weather(ctx: Context, s: RobotSettings): BriefWeather? {
        cached?.let { (at, w) -> if (System.currentTimeMillis() - at < KEEP_MS) return w }
        val w = ToolExecutor(ctx, s, Abort(WeatherAsk.DEADLINE_MS)) { false }.briefWeather() ?: return null
        cached = System.currentTimeMillis() to w
        return w
    }

    /** "Get ready while the alarm rings": the weather now, so the brief starts at once after you turn the alarm off. */
    fun prepare(ctx: Context, s: RobotSettings) {
        try { weather(ctx, s) } catch (t: Throwable) { RLog.w("brief_prepare", t) }
    }

    /**
     * The brief's input now. [missing]: why there is no weather (routines may not go online, or its card didn't list
     * Open-Meteo); [withWeather] false: the weather isn't fetched (the greeting goes first).
     */
    fun input(ctx: Context, s: RobotSettings, rs: RoutineSettings, missing: BriefWeatherMissing?, withWeather: Boolean = true,
        unread: UnreadSource = unread(ctx)): BriefInput {
        val now = ZonedDateTime.now()
        val items = rs.briefItems
        val weather = if (withWeather && missing == null && BriefItem.WEATHER in items) weather(ctx, s) else null
        val day = now.toLocalDate()
        val events = if (BriefItem.CALENDAR in items && Calendars.readGranted(ctx)) {
            try { Calendars.events(ctx, DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone))) } catch (t: Throwable) { RLog.w("brief_calendar", t); null }
        } else null
        val reminders = BriefReminders.due(RoutineFiles.store(ctx).reminders(), day, now.zone).map { it.text }
        return BriefInput(now, lang(rs), items, weather, if (withWeather) missing ?: (if (weather == null) BriefWeatherMissing.UNAVAILABLE else null) else missing,
            events, reminders, if (BriefItem.MESSAGES in items && withWeather) unread.unread() else null)
    }

    /** The reminders the brief just said are read: kept to the end of the day, then gone. */
    fun remindersSaid(ctx: Context) {
        val now = ZonedDateTime.now()
        RoutineFiles.store(ctx).editReminders { BriefReminders.prune(BriefReminders.markRead(it, now.toLocalDate(), now.zone, System.currentTimeMillis()), now.toLocalDate(), now.zone) }
    }
}

object RoutineRunner {
    /** What waits for the unlock after a run: steps to run then, and parts already composed (the rest of the brief). */
    class Waiting(val routine: Routine, val kind: TriggerKind, val steps: List<RoutineStep>, val parts: List<SpokenPart>, val heldAt: ZonedDateTime,
        val whole: Boolean = false)

    private fun part(text: String, personal: Boolean, code: StepCode, item: BriefItem? = null) = SpokenPart(listOf(text), personal, code, item)

    /**
     * Runs [r] for a trigger of [kind] ([key]: the occurrence the ledger records). [steps]: only these (the ones that
     * waited for the unlock), after [before] (the rest of a brief); [resumed]: said after the unlock.
     */
    fun run(ctx: Context, r: Routine, kind: TriggerKind, key: String?, steps: List<RoutineStep> = r.steps, before: List<SpokenPart> = emptyList(),
        resumed: Boolean = false) {
        val started = SystemClock.elapsedRealtime()
        val store = RoutineFiles.store(ctx)
        val s = SettingsStore.load(ctx)
        val rs = store.settings()
        val locked = Assistant.locked(ctx)
        val lang = BriefSource.lang(rs)
        val routine = r.copy(steps = steps)
        val plan = RoutinePlan.plan(routine, RunOrigin.UNATTENDED, locked, s, rs)
        val speaks = r.output != RoutineOutput.SILENT
        val silent = StringBuilder()
        val session = if (speaks) RoutineVoice.open(r, RoutineVoice.Surface.SERVICE, rs.briefLock == BriefLock.EVERYTHING, locked) { end ->
            RoutineHub.spoken(ctx, r, kind, end)
        }.also { it.resumed = resumed } else null
        fun say(parts: List<SpokenPart>) {
            if (parts.isEmpty()) return
            if (session != null) RoutineVoice.add(session, parts)
            else parts.forEach { silent.append(it.text).append(' ') }
        }
        say(before)
        val now = ZonedDateTime.now()
        var ready = 0
        var failed = 0
        var done = 0
        val waiting = mutableListOf<RoutineStep>()
        val heldParts = mutableListOf<SpokenPart>()
        for (p in plan) {
            var fail = false
            when (p.go) {
                StepGo.NEVER, StepGo.DISABLED -> {}
                StepGo.READY, StepGo.CONFIRM -> { ready(ctx, r, p.step); ready++ }
                StepGo.WAIT_UNLOCK -> waiting += p.step
                StepGo.SKIP_OFFLINE, StepGo.SKIP_NO_CONSENT -> RoutinePlan.skipLine(p.go, p.step, lang)?.let { say(listOf(part(it, false, p.step.code))) }
                StepGo.RUN, StepGo.RUN_PUBLIC -> when (val step = p.step) {
                    RoutineStep.Brief -> {
                        val (spoken, held) = brief(ctx, s, rs, routine, locked, ::say)
                        heldParts += held
                        VoiceLog.add(VoiceEvent.BRIEF_SPOKEN, spoken.sumOf { it.lines.size }, held.sumOf { it.lines.size }, spoken.sumOf { it.chars })
                    }
                    is RoutineStep.Say -> say(listOf(part(step.text, true, StepCode.SAY)))
                    RoutineStep.Reminders -> BriefComposer.reminders(BriefReminders.due(store.reminders(), now.toLocalDate(), now.zone).map { it.text }, lang)
                        ?.let { say(listOf(part(it, true, StepCode.REMINDERS, BriefItem.REMINDERS))) }
                    // 0.7's messages, counted by app (never who or what): nothing while Settings › Messages is off.
                    RoutineStep.Messages -> BriefSource.unread(ctx).unread()?.let { say(listOf(part(BriefComposer.messages(it, lang), false, StepCode.MESSAGES))) }
                    is RoutineStep.Tool -> when (val t = tool(ctx, s, step.action, locked, public = p.go == StepGo.RUN_PUBLIC)) {
                        null -> { ready(ctx, r, step); ready++ }
                        else -> { fail = t.second; if (t.first != null) say(listOf(t.first!!)) }
                    }
                }
            }
            VoiceLog.add(VoiceEvent.ROUTINE_STEP, p.step.code, p.go, if (fail) 1 else 0)
            if (fail) failed++ else if (RoutinePlan.runs(p.go)) done++
        }
        say(RoutinePlan.closing(ready, waiting.size, lang).map { part(it, false, StepCode.SAY) })
        if (session != null) RoutineVoice.close(session) else RoutineNotices.silent(ctx, r, silent.toString().trim())
        if (waiting.isNotEmpty() || heldParts.isNotEmpty()) RoutineHub.waitForUnlock(Waiting(r, kind, waiting, heldParts, ZonedDateTime.now()))
        // The rest after the unlock isn't a run of its own: the last run stays the one that started it.
        if (!resumed) store.editState { st ->
            st.copy(runs = st.runs + (r.id to RunRecord(System.currentTimeMillis(), kind, steps.size, ready, waiting.size + if (heldParts.isEmpty()) 0 else 1)))
                .let { if (key != null) it.copy(ledger = it.ledger.record(r.id, key, ZonedDateTime.now())) else it }
        }
        if (ready > 0) RoutineNotices.ready(ctx, ReadyCards.live(store.ready(), now.toLocalDate()))
        RoutineNotices.summary(ctx, ReadyCards.runSummary(r.name, done, ready, waiting.size, failed))
        VoiceLog.add(VoiceEvent.ROUTINE_RUN, plan.size, ready + waiting.size, (SystemClock.elapsedRealtime() - started).toInt())
        RLog.i("routine_run", "steps" to plan.size, "ready" to ready, "waiting" to waiting.size, "failed" to failed)
    }

    /** A step that waits for you: a Ready card, kept to the end of the day. */
    private fun ready(ctx: Context, r: Routine, step: RoutineStep) {
        val today = ZonedDateTime.now().toLocalDate()
        RoutineFiles.store(ctx).editReady { ReadyCards.add(it, ReadyCard(r.id, r.name, step, System.currentTimeMillis(), today), today) }
    }

    /**
     * The brief, said through [say] as it comes: the greeting at once, the rest once the weather and the calendar are read,
     * at the lock level (BriefComposer). Returns what was said now and what waits for the unlock.
     */
    fun brief(ctx: Context, s: RobotSettings, rs: RoutineSettings, routine: Routine, locked: Boolean, say: (List<SpokenPart>) -> Unit,
        origin: RunOrigin = RunOrigin.UNATTENDED): Pair<List<SpokenPart>, List<SpokenPart>> {
        val missing = RoutineGate.briefWeather(routine, origin, rs)
        val first = BriefSource.input(ctx, s, rs, missing, withWeather = false)
        val greeting = if (BriefItem.GREETING in rs.briefItems && !(locked && rs.briefLock == BriefLock.NOTHING))
            BriefParts.of(BriefComposer.lines(BriefItem.GREETING, first, place = false)) else emptyList()
        say(greeting)
        val b: Brief = BriefComposer.compose(BriefSource.input(ctx, s, rs, missing), locked, rs.briefLock)
        val rest = BriefParts.of(b.spoken).filter { greeting.isEmpty() || it.item != BriefItem.GREETING }
        say(rest)
        return (greeting + rest) to BriefParts.of(b.held)
    }

    /**
     * A tool step: prepared and run as the panel would, never launching anything (those steps are Ready cards). The
     * spoken result, and whether it failed; null when its card would ask (a name not sure, a choice): a Ready card then.
     * [public]: the weather without the place's name.
     */
    private fun tool(ctx: Context, s: RobotSettings, a: Action, locked: Boolean, public: Boolean): Pair<SpokenPart?, Boolean>? {
        val ex = ToolExecutor(ctx, s, Abort(if (a.online) WeatherAsk.DEADLINE_MS else Abort.NO_DEADLINE)) { i ->
            // Timers and alarms go to Clock with EXTRA_SKIP_UI: nothing shows.
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (t: Throwable) { RLog.w("routine_launch", t); false }
        }
        val code = StepCode.of(a.tool)
        val personal = RoutineGate.classOf(a.tool) == StepClass.READ_LOCAL || (a.tool == ToolName.WEATHER && !public)
        return when (val p = ex.prepare(a)) {
            is Prepared.Refused -> SpokenPart(listOf(p.detail), false, code) to true
            is Prepared.Ready -> {
                if (ApprovalGate.decide(a.tool, locked, s, p.reasons, content = false, asked = true) != GateDecision.RUN) return null
                val r = ex.run(p.choices.firstOrNull()?.action ?: p.action)
                var text = r.spoken ?: r.detail
                if (public && a.tool == ToolName.WEATHER) {
                    val place = r.results?.firstOrNull()?.get("place") as? String
                    if (place != null) text = text.removePrefix(place.replaceFirstChar { it.uppercase() } + ". ")
                }
                SpokenPart(listOf(text), personal, code) to !r.status.succeeded
            }
        }
    }
}
