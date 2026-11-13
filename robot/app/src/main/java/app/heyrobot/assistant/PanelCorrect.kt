// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.CorrectRouter
import app.heyrobot.core.CorrectRouter.Done
import app.heyrobot.core.CorrectRouter.Plan
import app.heyrobot.core.EventText
import app.heyrobot.core.Executed
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.CorrectStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.NotesFiles

/**
 * correct-last (idea 11, docs/plans/correct-last-plan.md): Robot's own last action, in memory only (a restart forgets it),
 * for "undo that" and "no, 6:45" (core CorrectRouter decides). ToolExecutor records alarms, timers, events, notes, texts and
 * calls; PanelReplies any other card with an Undo. Nothing here is written to a file or logged but the kind.
 */
object PanelCorrect {
    @Volatile private var last: Done? = null

    /** What PanelController.send takes: an undo always; a change only while Robot holds a last action to change. */
    fun route(q: String, now: Long = System.currentTimeMillis()): CorrectRouter.Ask? =
        CorrectRouter.parse(q)?.takeIf { it is CorrectRouter.Ask.Undo || changeable(last, now) }

    /**
     * review 11: "no, 10" is claimed only within the window, about an alarm or a timed event Robot just set; review Low F: or an
     * alarm set at a time you already had one, so Robot says it can't tell them apart instead of the model setting a third.
     */
    private fun changeable(d: Done?, now: Long) = d != null && now - d.at in 0..CorrectRouter.WINDOW_MS &&
        (d is Done.Alarm || d is Done.Kept && d.alarm || d is Done.Event && !d.row.allDay)

    fun forget() { last = null }

    /** What Robot holds now: ToolExecutor.run's mark, and what a model turn gets back ([keep]). */
    fun held(): Done? = last

    /** PanelController: a model turn keeps what the turn before held (review High 2), unless something was recorded since. */
    fun keep(d: Done?) { if (last == null) last = d }

    /**
     * ToolExecutor.run, the panel's only (review Medium 3: a routine's steps are not your last action): what [action] did.
     * Every action replaces the record (review High 2): one Robot can undo or change as itself, any other success as a kind
     * it can't undo, a failed one clears it, so "undo that" never reaches past the newest. Events and notes are recorded
     * where they're written (the record is no longer [mark]); [shared]: an alarm set at a time one was already set for.
     */
    fun ran(action: Action, r: Executed, mark: Done? = last, shared: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (last !== mark) return
        last = when {
            r.status == ActionStatus.DRAFT_OPENED -> Done.Elsewhere(now)
            !r.status.succeeded -> null
            action is Action.SetAlarm -> if (shared) Done.Kept(now, alarm = true, hour = action.hour, minute = action.minute) else Done.Alarm(action.hour, action.minute, now)
            action is Action.SetTimer -> Done.Timer(action.seconds, now)
            action is Action.SendText || action is Action.Call -> Done.Sent(now)
            action is Action.DraftEmail -> Done.Elsewhere(now)
            else -> Done.Kept(now)
        }
    }

    fun event(row: app.heyrobot.core.CalendarEvent, now: Long = System.currentTimeMillis()) { last = Done.Event(row, now) }

    fun note(n: app.heyrobot.core.Note, now: Long = System.currentTimeMillis()) { last = Done.Note(n, null, now) }

    /** PanelReplies: a card just got an Undo ([key]); a note's card is the note just recorded, so it keeps its check. */
    fun card(actionId: String, key: String, now: Long = System.currentTimeMillis()) {
        // review Medium 5, LS10 kept (the default): a list change is undone only on the very next turn, by Lists itself.
        if (PanelLists.isUndo(key)) { last = Done.Kept(now); return }
        val l = last
        last = if (l is Done.Note && l.note.id == key) l.copy(actionId = actionId) else Done.Card(actionId, now)
    }

    /** review Medium 4: what a card's own Undo said ([detail]); review Low G: when it said nothing in time, only that Robot asked. */
    fun cardSaid(detail: String?, l: app.heyrobot.core.i18n.Langs): String = detail ?: CorrectStrings.undoAsked().render(l)

    /** review 9: a move past midnight says so; review Low I: so does one to the day before. */
    internal fun movedSaid(was: Long, begin: Long, zone: java.time.ZoneId): Phrase {
        val at = java.time.Instant.ofEpochMilli(begin).atZone(zone)
        val day = java.time.Instant.ofEpochMilli(was).atZone(zone).toLocalDate()
        val line = when {
            at.toLocalDate().isAfter(day) -> CorrectStrings.eventMovedNextDay
            at.toLocalDate().isBefore(day) -> CorrectStrings.eventMovedDayBefore
            else -> CorrectStrings.eventMoved
        }
        return line("time" to EventText.clock(at.hour, at.minute))
    }

    /** review-e Low: a turn stopped once [handle] ran shows its line (not spoken); the turn's stop handler takes it. */
    private val shown = java.util.concurrent.ConcurrentHashMap<String, Phrase>()
    fun hold(replyId: String, r: Reply): Reply = r.also { shown[replyId] = it.line }
    fun taken(replyId: String): Phrase? = shown.remove(replyId)

    /** [line] said; [card], a card whose own Undo PanelController runs (on the main thread). */
    class Reply(val line: Phrase, val card: String? = null)

    /** What [handle] turns alarms off and sets them through: ToolExecutor; a test's fake records what's sent, in order. */
    interface Hands {
        /** AlarmManager's next alarm clock (null: none). */
        fun nextAlarm(): Long?
        /** Asks your clock to turn off the alarm at exactly [hour]:[minute]; false: no clock took it. */
        fun sendDismiss(hour: Int, minute: Int): Boolean
        fun run(action: Action): Executed
        /**
         * fix-alarm-moved: a move's new alarm. The turn-off just brought your clock's alarms page up; ToolExecutor sets this
         * one through that page (no SKIP_UI), so the page that later takes "cancel my alarm" holds it (phone-train-14-b).
         */
        fun setMoved(hour: Int, minute: Int): Executed = run(Action.SetAlarm(hour, minute))
        fun pause(ms: Long) = Thread.sleep(ms)
        /** fix-batch-15 M1: the time a move's set is read back from (a test's fake gives a fixed one). */
        fun now(): Long = System.currentTimeMillis()
        /** review-d Low: "stop" or the wake word during the wait; then nothing more is sent. */
        fun stopped(): Boolean = false
    }

    /**
     * review High A, Medium B: the turn-off is sent only when the next alarm is [hour]:[minute], so Robot can tell when it
     * goes; else nothing is sent (NOT_SENT). Null: no clock took it. Then it waits up to 5 s for the next alarm to move on (review Low H: a clock
     * slow from a cold start), and logs how long it took. review-e High: once the turn-off is sent, a stop doesn't cut the
     * wait, since the clock may still turn the alarm off; the line says what the read-back shows.
     */
    const val OFF_STEP_MS = 250L
    const val OFF_STEPS = 20

    fun turnOff(hands: Hands, hour: Int, minute: Int, zone: java.time.ZoneId): CorrectRouter.Off? {
        val before = try { hands.nextAlarm() } catch (_: Exception) { null }
        if (hands.stopped()) return CorrectRouter.Off.STOPPED
        if (before == null || !CorrectRouter.isNext(hour, minute, before, zone)) return CorrectRouter.Off.NOT_SENT
        if (!hands.sendDismiss(hour, minute)) return null
        for (i in 1..OFF_STEPS) {
            hands.pause(OFF_STEP_MS)
            if (app.heyrobot.core.AlarmCancel.gone(before, try { hands.nextAlarm() } catch (_: Exception) { before })) {
                RLog.i("correct_off", "gone" to true, "ms" to i * OFF_STEP_MS) // l10n: not text
                return CorrectRouter.Off.GONE
            }
        }
        RLog.i("correct_off", "gone" to false, "ms" to OFF_STEPS * OFF_STEP_MS) // l10n: not text
        return CorrectRouter.Off.STILL_ON
    }

    /**
     * fix-batch-15 M1 (review-batch-15): a move's new alarm read back up to [OFF_STEPS] times, since your clock may keep it
     * a moment later or, waiting on a tap on its page, not at all. MISSING: Robot says so instead of "changed".
     */
    fun landed(hands: Hands, hour: Int, minute: Int, zone: java.time.ZoneId): CorrectRouter.Landed {
        var seen = CorrectRouter.Landed.MISSING
        for (i in 1..OFF_STEPS) {
            hands.pause(OFF_STEP_MS)
            seen = CorrectRouter.landed(hour, minute, try { hands.nextAlarm() } catch (_: Exception) { null }, hands.now(), zone)
            if (seen != CorrectRouter.Landed.MISSING) break
            if (hands.stopped()) break // fix-batch-15-lows c: a stop ends the read-back; the move's one line is all
        }
        RLog.i("correct_set", "seen" to seen.name) // l10n: not text
        return seen
    }

    /** Off the main thread. [locked] fails closed: its error counts as locked. */
    fun handle(ctx: Context, ask: CorrectRouter.Ask, hands: Hands, locked: () -> Boolean): Reply =
        handle(ask, hands, locked, java.time.ZoneId.systemDefault()) { ctx }

    /** [ctx] is asked for only by an event or a note (the alarm paths' tests have none). */
    internal fun handle(ask: CorrectRouter.Ask, hands: Hands, locked: () -> Boolean, zone: java.time.ZoneId, ctx: () -> Context): Reply {
        val l = last
        val isLocked = try { locked() } catch (_: Exception) { true }
        val seen = try {
            when (l) {
                is Done.Event -> CorrectRouter.seen(l, Calendars.row(ctx(), l.row.id))
                is Done.Note -> CorrectRouter.seen(l, NotesFiles.store(ctx()).get(l.note.id))
                else -> CorrectRouter.Seen.SAME
            }
        } catch (t: Exception) { RLog.w("correct_seen", t); CorrectRouter.Seen.CHANGED }
        val plan = CorrectRouter.plan(ask, l, System.currentTimeMillis(), isLocked, seen, zone)
        RLog.i("correct_last", "kind" to (l?.javaClass?.simpleName ?: "none"), "plan" to plan.javaClass.simpleName) // l10n: not text
        return when (plan) {
            is Plan.Say -> Reply(plan.line)
            is Plan.DismissAlarm -> turnOff(hands, plan.hour, plan.minute, zone).let { off ->
                if (off == CorrectRouter.Off.GONE) last = null
                Reply(CorrectRouter.offSaid(off, plan.hour, plan.minute))
            }
            is Plan.MoveAlarm -> {
                // review High 1 and A: nothing is sent unless Robot can tell, and nothing new is set until the old one went.
                val off = turnOff(hands, plan.from.hour, plan.from.minute, zone)
                if (off != CorrectRouter.Off.GONE) return Reply(CorrectRouter.moveSaid(off, plan.from, plan.hour, plan.minute))
                last = null
                // review-d Low: stopped while the old one went, the new one isn't set.
                if (hands.stopped()) return Reply(CorrectRouter.offStoppedNotSet(plan.from, plan.hour, plan.minute))
                val r = hands.setMoved(plan.hour, plan.minute) // records the new one
                // review Low E: the old one is already off, so a failed set says both.
                if (r.status.succeeded) Reply(CorrectRouter.setSaid(landed(hands, plan.hour, plan.minute, zone), plan.from, plan.hour, plan.minute))
                else { last = null; Reply(CorrectRouter.offNotSet(plan.from, plan.hour, plan.minute)) }
            }
            is Plan.DeleteEvent -> if (Calendars.delete(ctx(), plan.id)) { last = null; app.heyrobot.sys.TodaySource.changed(ctx()); Reply(CorrectStrings.eventUndone()) } else Reply(CorrectStrings.failed())
            is Plan.MoveEvent -> {
                val e = plan.row
                if (!Calendars.update(ctx(), e.id, e.title, e.location.orEmpty(), plan.begin, plan.end, e.allDay, zone)) return Reply(CorrectStrings.failed())
                app.heyrobot.sys.TodaySource.changed(ctx())
                Calendars.row(ctx(), e.id)?.let { event(it) } ?: forget()
                Reply(movedSaid(e.begin, plan.begin, zone))
            }
            is Plan.DeleteNote -> if (NotesFiles.store(ctx()).delete(plan.id)) { last = null; Reply(CorrectStrings.noteUndone()) } else Reply(CorrectStrings.failed())
            is Plan.UndoCard -> { last = null; Reply(CorrectStrings.undone(), card = plan.actionId) }
        }
    }
}
