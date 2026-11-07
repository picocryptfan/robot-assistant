// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.core.lock.SomeoneElseLock
import app.heyrobot.core.RoutineLock
import app.heyrobot.core.LockScreen
import app.heyrobot.assistant.ToolExecutor
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.Brief
import app.heyrobot.core.BriefComposer
import app.heyrobot.core.BriefInput
import app.heyrobot.core.BriefItem
import app.heyrobot.core.i18n.Lang
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
import app.heyrobot.core.RoutineTexts
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
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.ReplyLang
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.SettingsStore
import java.time.ZonedDateTime

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

    /**
     * 1.1: a routine's languages when nothing was heard (core ReplyLang.unheard): what it says in
     * Settings › Language › "Robot answers in", else the menus'; Robot's pages in the menus', Android's in the phone's.
     */
    fun langs(ctx: Context, s: RobotSettings = SettingsStore.load(ctx)): Langs =
        AppLanguage.langs(ctx).let { it.copy(say = ReplyLang.unheard(it.ui, s.replyLang)) }

    /** The brief's language: Settings › Routines › Morning brief, else Robot's ("Follow Robot": the reply language of [l]). */
    fun lang(rs: RoutineSettings, l: Langs): Lang = rs.briefLang ?: l.say

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
        unread: UnreadSource = unread(ctx), langs: Langs = langs(ctx, s)): BriefInput {
        val now = ZonedDateTime.now()
        val items = rs.briefItems
        val weather = if (withWeather && missing == null && BriefItem.WEATHER in items) weather(ctx, s) else null
        val day = now.toLocalDate()
        // Today H5: with Today your home page, the brief follows it: its order, its private events with the phone's, and its
        // lists and note, those two read only while the phone is unlocked and no phone lock is on (BriefPrivacy never says
        // them locked either; today-fix-b: Assistant.locked, as PanelRoutines.today, so a phone lock doesn't read them into memory).
        val home = try { app.heyrobot.sys.HomeSettingsStore.current(ctx).takeIf { it.today } } catch (t: Throwable) { RLog.w("brief_home", t); null }
        val open = withWeather && home != null && !Assistant.locked(ctx)
        var privateLocked = false
        val events = if (home != null && BriefItem.CALENDAR in items) {
            app.heyrobot.sys.TodaySource.day(ctx, now, DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone))).also { privateLocked = it.privateLocked }.events
        } else if (BriefItem.CALENDAR in items && Calendars.readGranted(ctx)) {
            try { Calendars.events(ctx, DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone))) } catch (t: Throwable) { RLog.w("brief_calendar", t); null }
        } else null
        val reminders = BriefReminders.due(RoutineFiles.store(ctx).reminders(), day, now.zone).map { it.text }
        // 1.1, Phone lock (§9.2): "Your phone opens at 8:00", from locks.json and the alarm that just ended, so it is right
        // even before :lock has written the lock it starts.
        val opens = try {
            app.heyrobot.lock.PhoneLockNow.files(ctx)?.let { (f, st) ->
                // Only an alarm that just ended counts: a brief asked for later says what is on now.
                val alarm = alarmAt?.takeIf { System.currentTimeMillis() - alarmSetAt in 0..ALARM_FRESH_MS }
                app.heyrobot.core.lock.LockSchedule.briefOpensAt(f, st, app.heyrobot.core.lock.Clocks(now, android.os.SystemClock.elapsedRealtime(), null), alarm)?.toLocalTime()
            }
        } catch (t: Throwable) { RLog.w("brief_lock", t); null }
        // 1.4 F10: Robot Files' part, read only while the phone is unlocked (its key needs it) and Settings › Files says so.
        val files = if (withWeather) files(ctx, now) else null
        // 1.5 L6: Robot to Robot's part, as Files': read only while the phone is unlocked, when its "Show in morning brief" says so.
        val link = if (withWeather) link(ctx, now) else null
        // today-h7-fix 5: read on a locked phone too (BriefPrivacy says it at "Everything" and holds it otherwise), never under
        // someone else's phone lock.
        val news = if (withWeather && home != null && home.news.on && !app.heyrobot.lock.PhoneLockNow.on(ctx)) app.heyrobot.sys.NewsStore.current(ctx).let { st ->
            val ms = System.currentTimeMillis()
            st.pick(home.news, ms) to st.updated(home.news, ms)
        } else null
        val all = items.filter { it != BriefItem.FILES && it != BriefItem.LINK } + listOfNotNull(BriefItem.FILES.takeIf { files != null }, BriefItem.LINK.takeIf { link != null })
        return BriefInput(now, lang(rs, langs), all, weather, if (withWeather) missing ?: (if (weather == null) BriefWeatherMissing.UNAVAILABLE else null) else missing,
            events, reminders, if (BriefItem.MESSAGES in items && withWeather) unread.unread() else null, phoneOpens = opens, files = files, link = link,
            page = home?.order, lists = if (open) app.heyrobot.sys.TodaySource.glances(ctx, lang(rs, langs)) else emptyList(),
            note = if (open && home != null) app.heyrobot.sys.TodaySource.note(ctx, home) else null, privateLocked = privateLocked,
            // H7: the headlines already fetched, never fetched for the brief.
            news = news?.first.orEmpty(), newsAt = news?.second)
    }

    /** Today fix: the calendar part after an unlock ([RoutineRunner.CALENDAR_LATER]), private events read now. */
    fun calendarNow(ctx: Context, lang: Lang): List<SpokenPart> {
        val now = ZonedDateTime.now()
        val day = now.toLocalDate()
        val d = app.heyrobot.sys.TodaySource.day(ctx, now, DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone)))
        return BriefParts.of(BriefComposer.lines(BriefItem.CALENDAR, BriefInput(now, lang, listOf(BriefItem.CALENDAR), events = d.events, privateLocked = d.privateLocked), place = true))
    }

    /**
     * 1.4 F10: Settings › Files' "Show in morning brief" is on, with Robot Files on: the brief has a files part (said only while
     * the phone is unlocked). Read from files_settings.json alone, so it can be asked on a locked phone.
     */
    fun filesWanted(ctx: Context): Boolean = try {
        app.heyrobot.sys.FilesSettingsStore.current(ctx).let { it.on && it.brief }
    } catch (t: Throwable) { RLog.w("brief_files_settings", t); false }

    /**
     * 1.4 F10: the brief's files part now (core FileBrief: what Robot filed yesterday, the reminders due), or null: not wanted,
     * the phone locked or a phone lock on (nothing about files then, files-1.4-plan.md §5.3), or nothing to say.
     */
    fun files(ctx: Context, now: ZonedDateTime): app.heyrobot.core.files.FilesBrief? = try {
        if (!filesWanted(ctx) || Assistant.keyguard(ctx) || app.heyrobot.sys.RobotFiles.locked(ctx)) null
        // F12: as travel mode shows it (hidden drawers' files and their reminders left out).
        else app.heyrobot.sys.RobotFiles.shown(ctx)?.let { app.heyrobot.core.files.FileBrief.of(it, now.toLocalDate(), now.zone) }?.takeIf { !it.empty }
    } catch (t: Throwable) {
        RLog.w("brief_files", t)
        null
    }

    /**
     * 1.5 L6: Robot to Robot's part now (core LinkBrief: requests waiting, what Robot handled on its own in the last day), or
     * null: Robot to Robot off, its "Show in morning brief" off, the phone locked or a phone lock on, or nothing to say.
     */
    fun link(ctx: Context, now: ZonedDateTime): app.heyrobot.core.LinkBrief? = try {
        val s = app.heyrobot.sys.LinkSettingsStore.current(ctx)
        if (!s.on || !s.brief || Assistant.locked(ctx)) null else {
            val client = app.heyrobot.sys.LinkClient(ctx)
            try {
                client.desk()?.let { d ->
                    val since = now.toEpochSecond() - 86_400
                    app.heyrobot.core.LinkBrief(d.cards.size + d.more.values.sum(),
                        d.lines.count { it.entry.way == app.heyrobot.core.link.DeskWay.IN && it.entry.state == app.heyrobot.core.link.DeskState.HANDLED && it.entry.done >= since })
                }?.takeIf { !it.empty }
            } finally {
                client.close()
            }
        }
    } catch (t: Throwable) {
        RLog.w("brief_link", t)
        null
    }

    /** The files part after an unlock (the brief ran locked and left its place, [RoutineRunner.FILES_LATER]); none while still locked. */
    fun filesNow(ctx: Context, lang: Lang): List<SpokenPart> {
        val now = ZonedDateTime.now()
        val f = files(ctx, now) ?: return emptyList()
        return BriefParts.of(BriefComposer.lines(BriefItem.FILES, BriefInput(now, lang, listOf(BriefItem.FILES), files = f), place = true))
    }

    /** 1.1: the first ring of the alarm whose ALARM_DONE started this run ("a:<ms>", the trigger's key), or null. */
    @Volatile var alarmAt: Long? = null
        set(v) { field = v; alarmSetAt = System.currentTimeMillis() }
    @Volatile private var alarmSetAt = 0L
    private const val ALARM_FRESH_MS = 10 * 60_000L

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

    /** 1.1: [p] as the voice says it in [l]: English as written, French without its typography (core VoiceForm). */
    fun said(p: Phrase, l: Langs): String = if (l.say == Lang.EN) p.render(l) else p.render(l.voice)

    /** 1.1: [p] said in [l] and shown with its typography. */
    private fun part(p: Phrase, l: Langs, personal: Boolean, code: StepCode, item: BriefItem? = null) =
        SpokenPart(listOf(said(p, l)), personal, code, item, listOf(p.render(l)))

    /** A tool step's words while they are still [en] (a result whose English was changed keeps that English as it is). */
    private fun Phrase?.orPlain(en: String): Phrase = this?.takeIf { it.en() == en } ?: Phrase.plain(en)

    /**
     * Runs [r] for a trigger of [kind] ([key]: the occurrence the ledger records). [steps]: only these (the ones that
     * waited for the unlock), after [before] (the rest of a brief); [resumed]: said after the unlock.
     */
    fun run(ctx: Context, r: Routine, kind: TriggerKind, key: String?, steps: List<RoutineStep> = r.steps, before: List<SpokenPart> = emptyList(),
        resumed: Boolean = false) {
        val started = SystemClock.elapsedRealtime()
        BriefSource.alarmAt = if (kind == TriggerKind.ALARM) key?.removePrefix("a:")?.toLongOrNull() else null
        val store = RoutineFiles.store(ctx)
        // F6: during someone else's lock, the lock screen's rules: nothing personal is said, and what would wait for the unlock
        // is dropped, since that unlock would be theirs. During your own lock, routines are unchanged: the screen is unlocked,
        // so a step held for the unlock would wait for an unlock that doesn't come; an app a step opens is covered anyway.
        val someoneElse = PhoneLockNow.someoneElse(ctx)
        val s = SettingsStore.load(ctx).let { if (someoneElse) it.copy(lockedTools = it.lockedTools intersect LockScreen.DEFAULT) else it }
        val rs = SomeoneElseLock.routines(store.settings(), someoneElse)
        val locked = Assistant.keyguard(ctx) || someoneElse
        val langs = BriefSource.langs(ctx, s)
        val briefLang = BriefSource.lang(rs, langs)
        val routine = r.copy(steps = steps, lock = if (someoneElse) RoutineLock.FOLLOW else r.lock)
        val plan = RoutinePlan.plan(routine, RunOrigin.UNATTENDED, locked, s, rs)
        // review-quiet-when-silent, 4: on a silenced phone a run whose voice would go to the room is shown, not said; a run
        // started by turning off your alarm still speaks (fix-126-review 2: only then, with the alarm's key; a missed alarm's
        // run at the unlock and a brief's held rest come with none).
        val quiet = r.output != RoutineOutput.SILENT && RoutineVoice.quiet(kind, key, r.output)
        val speaks = r.output != RoutineOutput.SILENT && !quiet
        val silent = StringBuilder()
        val session = if (speaks) RoutineVoice.open(r, RoutineVoice.Surface.SERVICE, rs.briefLock == BriefLock.EVERYTHING, locked) { end ->
            RoutineHub.spoken(ctx, r, kind, end)
        }.also { it.resumed = resumed } else null
        fun say(all: List<SpokenPart>) {
            val parts = SomeoneElseLock.spoken(all, someoneElse)
            if (parts.isEmpty()) return
            if (session != null) RoutineVoice.add(session, parts)
            else parts.forEach { silent.append(it.shownText).append(' ') }
        }
        // 1.4 F10: the files part a locked brief left for the unlock, read now that the phone is unlocked (or dropped).
        say(before.flatMap { if (it.item == BriefItem.FILES && it.lines.isEmpty()) BriefSource.filesNow(ctx, briefLang) else if (it.item == BriefItem.CALENDAR && it.lines.isEmpty()) BriefSource.calendarNow(ctx, briefLang) else listOf(it) })
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
                StepGo.WAIT_UNLOCK -> if (!someoneElse) waiting += p.step
                StepGo.SKIP_OFFLINE, StepGo.SKIP_NO_CONSENT -> RoutinePlan.skipText(p.go, p.step)?.let { say(listOf(part(it, langs, false, p.step.code))) }
                StepGo.RUN, StepGo.RUN_PUBLIC -> when (val step = p.step) {
                    RoutineStep.Brief -> {
                        val (spoken, held) = brief(ctx, s, rs, routine, locked, ::say, langs = langs)
                        if (!someoneElse) heldParts += held
                        VoiceLog.add(VoiceEvent.BRIEF_SPOKEN, spoken.sumOf { it.lines.size }, held.sumOf { it.lines.size }, spoken.sumOf { it.chars })
                    }
                    is RoutineStep.Say -> say(listOf(part(step.text, true, StepCode.SAY)))
                    RoutineStep.Reminders -> BriefComposer.remindersText(BriefReminders.due(store.reminders(), now.toLocalDate(), now.zone).map { it.text }, briefLang)
                        ?.let { say(listOf(part(it, langs.copy(say = briefLang), true, StepCode.REMINDERS, BriefItem.REMINDERS))) }
                    // 0.7's messages, counted by app (never who or what): nothing while Settings › Messages is off.
                    RoutineStep.Messages -> BriefSource.unread(ctx).unread()?.let { say(listOf(part(BriefComposer.messagesText(it), langs.copy(say = briefLang), false, StepCode.MESSAGES))) }
                    is RoutineStep.Tool -> when (val t = tool(ctx, s, step.action, locked, public = p.go == StepGo.RUN_PUBLIC, langs = langs)) {
                        null -> { ready(ctx, r, step); ready++ }
                        else -> { fail = t.second; if (t.first != null) say(listOf(t.first!!)) }
                    }
                }
            }
            VoiceLog.add(VoiceEvent.ROUTINE_STEP, p.step.code, p.go, if (fail) 1 else 0)
            if (fail) failed++ else if (RoutinePlan.runs(p.go)) done++
        }
        say(RoutinePlan.closingText(ready, waiting.size).map { part(it, langs, false, StepCode.SAY) })
        if (quiet && silent.isNotBlank()) silent.insert(0, ' ').insert(0, RoutineStrings.stayedQuiet().render(langs))
        if (session != null) RoutineVoice.close(session) else RoutineNotices.silent(ctx, r, silent.toString().trim())
        if (waiting.isNotEmpty() || heldParts.isNotEmpty()) RoutineHub.waitForUnlock(Waiting(r, kind, waiting, heldParts, ZonedDateTime.now()))
        // The rest after the unlock isn't a run of its own: the last run stays the one that started it.
        if (!resumed) store.editState { st ->
            st.copy(runs = st.runs + (r.id to RunRecord(System.currentTimeMillis(), kind, steps.size, ready, waiting.size + if (heldParts.isEmpty()) 0 else 1)))
                .let { if (key != null) it.copy(ledger = it.ledger.record(r.id, key, ZonedDateTime.now())) else it }
        }
        if (ready > 0) RoutineNotices.ready(ctx, ReadyCards.live(store.ready(), now.toLocalDate()))
        RoutineNotices.summary(ctx, ReadyCards.runSummaryText(RoutineTexts.name(r), done, ready, waiting.size, failed)?.render(langs))
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
        origin: RunOrigin = RunOrigin.UNATTENDED, langs: Langs = BriefSource.langs(ctx, s),
        read: (List<app.heyrobot.core.CalendarEvent>?) -> Unit = {}): Pair<List<SpokenPart>, List<SpokenPart>> {
        val missing = RoutineGate.briefWeather(routine, origin, rs)
        val first = BriefSource.input(ctx, s, rs, missing, withWeather = false, langs = langs)
        val greeting = if (BriefItem.GREETING in rs.briefItems && !(locked && rs.briefLock == BriefLock.NOTHING))
            BriefParts.of(BriefComposer.lines(BriefItem.GREETING, first, place = false)) else emptyList()
        say(greeting)
        val input = BriefSource.input(ctx, s, rs, missing, langs = langs).also { read(it.events) }
        val b: Brief = BriefComposer.compose(input, locked, rs.briefLock)
        val rest = BriefParts.of(b.spoken).filter { greeting.isEmpty() || it.item != BriefItem.GREETING }
        say(rest)
        val held = calendarLater(BriefParts.of(b.held), locked && input.privateLocked)
        // 1.4 F10: locked, the files part can't be read (its key needs the unlock): its place waits for it, before the closing,
        // only when the brief already holds parts for the unlock (at "Everything" nothing waits, and the files part is skipped).
        if (!locked || held.isEmpty() || !BriefSource.filesWanted(ctx)) return (greeting + rest) to held
        val at = held.indexOfFirst { it.item == BriefItem.CLOSING }.let { if (it < 0) held.size else it }
        return (greeting + rest) to (held.take(at) + FILES_LATER + held.drop(at))
    }

    /** 1.4 F10: where a locked brief's files part goes once the phone is unlocked ([run] reads it then). */
    val FILES_LATER = SpokenPart(emptyList(), true, StepCode.BRIEF, BriefItem.FILES)

    /** Today fix: where a locked brief's calendar part goes while private events are locked; read again after the unlock. */
    val CALENDAR_LATER = SpokenPart(emptyList(), true, StepCode.BRIEF, BriefItem.CALENDAR)

    /** [held] with its calendar part left as [CALENDAR_LATER] when [later] (it was read without the private events). */
    fun calendarLater(held: List<SpokenPart>, later: Boolean): List<SpokenPart> {
        val at = held.indexOfFirst { it.item == BriefItem.CALENDAR }
        if (!later || at < 0) return held
        return held.filterIndexed { i, p -> i == at || p.item != BriefItem.CALENDAR }.map { if (it.item == BriefItem.CALENDAR) CALENDAR_LATER else it }
    }

    /**
     * A tool step: prepared and run as the panel would, never launching anything (those steps are Ready cards). The
     * spoken result, and whether it failed; null when its card would ask (a name not sure, a choice): a Ready card then.
     * [public]: the weather without the place's name.
     */
    private fun tool(ctx: Context, s: RobotSettings, a: Action, locked: Boolean, public: Boolean, langs: Langs): Pair<SpokenPart?, Boolean>? {
        val ex = ToolExecutor(ctx, s, Abort(if (a.online) WeatherAsk.DEADLINE_MS else Abort.NO_DEADLINE)) { i ->
            // Timers and alarms go to Clock with EXTRA_SKIP_UI: nothing shows.
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (t: Throwable) { RLog.w("routine_launch", t); false }
        }
        val code = StepCode.of(a.tool)
        val personal = RoutineGate.classOf(a.tool) == StepClass.READ_LOCAL || (a.tool == ToolName.WEATHER && !public)
        return when (val p = ex.prepare(a)) {
            is Prepared.Refused -> part(p.text.orPlain(p.detail), langs, false, code) to true
            is Prepared.Ready -> {
                if (ApprovalGate.decide(a.tool, locked, s, p.reasons, content = false, asked = true) != GateDecision.RUN) return null
                val r = ex.run(p.choices.firstOrNull()?.action ?: p.action)
                // 1.1: in the routine's reply language when the result has its words (core Executed's phrases), else as it is.
                val words = r.spoken?.let { r.spokenText.orPlain(it) } ?: r.text.orPlain(r.detail)
                var said = said(words, langs)
                var shown = words.render(langs)
                if (public && a.tool == ToolName.WEATHER) {
                    val place = r.results?.firstOrNull()?.get("place") as? String
                    if (place != null) {
                        val head = place.replaceFirstChar { it.uppercase() } + ". "
                        said = said.removePrefix(head)
                        shown = shown.removePrefix(head)
                    }
                }
                SpokenPart(listOf(said), personal, code, null, listOf(shown)) to !r.status.succeeded
            }
        }
    }
}
