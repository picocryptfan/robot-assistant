// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import android.os.SystemClock
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lock.Clocks
import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.EndEarlyRules
import app.heyrobot.core.lock.LockCommand
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.LockState
import app.heyrobot.lock.LockSignals
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

// :VOICE. Phone lock by voice in the panel: core LockRouter decides what you said, before the model; this
// answers. "Lock the phone until 7" shows a card, and only Lock on it sends the lock to :lock (LockSignals.startVoice,
// :voice writes no lock file). "How long is left" answers, on the lock screen too. "End the lock" says how it ends, and
// with the code opens :lock's code pad on the screen: Robot never takes a spoken code. Starting a lock needs an unlocked
// phone (open question 4), and one started by voice for yourself can always end after a 10-minute wait (12).

object PanelLock {
    private val app get() = VoiceHub.app

    /** The Phone lock command [q] is, or null. */
    fun route(q: String): LockCommand? = try {
        val files = PhoneLockNow.files(app)
        LockRouter.route(q, ZonedDateTime.now(), files?.first?.locks?.map { it.name }.orEmpty(), phoneLock = files != null, lockOn = PhoneLockNow.on(app))
    } catch (t: Throwable) {
        RLog.w("lock_route", t)
        null
    }

    private fun clocks() = Clocks(ZonedDateTime.now(), SystemClock.elapsedRealtime(), null)

    suspend fun handle(replyId: String, cmd: LockCommand, host: PanelRoutines.Host, said: String) {
        val l = host.langs(said, cmd.lang)
        fun answer(vararg p: Phrase) {
            host.text(replyId, p.joinToString(" ") { it.render(l) })
            host.say(p.joinToString(" ") { RoutineRunner.said(it, l) })
        }
        val files = PhoneLockNow.files(app)
        try {
            if (files == null) answer(LockStrings.voiceNeedSetup())
            else when (cmd) {
                is LockCommand.Start -> start(replyId, cmd, files.first, files.second, host, l, ::answer)
                is LockCommand.Ask -> answer(*ask(files.first, files.second))
                is LockCommand.End -> end(files.first, files.second, host, ::answer)
                is LockCommand.Skip -> skip(cmd, files.first, files.second, host, ::answer)
            }
        } catch (t: Throwable) {
            RLog.w("lock_voice", t)
        }
        RLog.i("lock_voice", "kind" to RLog.Code(cmd.javaClass.simpleName))
        host.finish(replyId)
    }

    private suspend fun start(replyId: String, cmd: LockCommand.Start, f: LockFile, st: LockState, host: PanelRoutines.Host, l: Langs, answer: (Array<out Phrase>) -> Unit) {
        // Unlock first: otherwise anyone near the phone could lock it.
        if (Assistant.keyguard(app)) return answer(arrayOf(LockStrings.voiceUnlockFirst()))
        val now = ZonedDateTime.now()
        val end = cmd.end(now)
        if (Duration.between(now, end) > LockSchedule.VOICE_MAX) return answer(arrayOf(LockStrings.voiceTooLong()))
        refusal(f, st, end)?.let { return answer(arrayOf(it)) }
        val id = UUID.randomUUID().toString()
        val title = LockStrings.voiceCard("time" to end.toLocalTime()).render(l)
        val how = if (f.settings.forWho == LockFor.SOMEONE_ELSE) LockStrings.voiceCardCode() else LockStrings.voiceCardWait()
        val detail = listOf(LockStrings.voiceCardBody(), how).joinToString(" ") { it.render(l) }
        val button = Langs(l.ui, l.ui, l.system, pseudo = l.pseudo)
        host.text(replyId, title)
        host.say(RoutineRunner.said(LockStrings.voiceCard("time" to end.toLocalTime()), l))
        val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, detail, confirm = LockStrings.voiceLock().render(button),
            cancel = UiStrings.cancel().render(button)))
        // The phone may have locked, or a lock for someone else started, while the card waited.
        val late = if (yes) PhoneLockNow.files(app)?.let { (f2, st2) -> refusal(f2, st2, end) } else null
        if (!yes || Assistant.keyguard(app) || late != null) {
            host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, (late ?: LockStrings.voiceNotStarted()).render(l)))
            return
        }
        LockSignals.startVoice(app, end.toInstant().toEpochMilli())
        val done = LockStrings.voiceStarted("time" to end.toLocalTime())
        host.show(replyId, Card(id, null, done.render(l), ActionStatus.DONE, detail))
        host.say(RoutineRunner.said(done, l))
    }

    /**
     * Why a lock by voice until [end] doesn't start: a lock for someone else is on (only the code ends or changes it, so
     * nobody at the phone cuts it short), or the lock by voice that is on already lasts until [end] or later (:lock never
     * moves its end sooner). Null: the card may show.
     */
    private fun refusal(f: LockFile, st: LockState, end: ZonedDateTime): Phrase? {
        val c = clocks()
        if (LockSchedule.someoneElseOn(f, st, c)) return LockStrings.voiceSomeoneElse()
        return LockSchedule.voiceEnd(st, c)?.takeIf { !it.isBefore(end) }?.let { LockStrings.voiceAlready("time" to it.toLocalTime()) }
    }

    /** "Your phone opens at 8:00, in 47 minutes." / "8 of 20 minutes in Libby done" / "There's no phone lock on now. The next one is Bedtime, at 22:00." */
    private fun ask(f: LockFile, st: LockState): Array<Phrase> {
        val c = clocks()
        if (PhoneLockNow.on(app) && st.active.isNotEmpty()) {
            LockSchedule.timedOpensAt(f, st, c)?.let { opens ->
                val left = ((opens.toInstant().toEpochMilli() - c.nowMs + 59_999) / 60_000).coerceAtLeast(0)
                return arrayOf(LockStrings.voiceLeft(left, "time" to opens.toLocalTime()))
            }
            LockSchedule.appLocks(f, st).firstOrNull()?.let { (a, r) ->
                return arrayOf(LockStrings.coverProgress("done" to a.usedMs / 60_000, "total" to r.minutes, "label" to label(r.app)))
            }
        }
        val next = LockSchedule.nextLock(f, st, c.now)
        return if (next == null) arrayOf(LockStrings.voiceNone()) else arrayOf(LockStrings.voiceNone(), LockStrings.voiceNext("name" to next.lock.name, "time" to next.start.toLocalTime()))
    }

    private suspend fun end(f: LockFile, st: LockState, host: PanelRoutines.Host, answer: (Array<out Phrase>) -> Unit) {
        val c = clocks()
        when (EndEarlyRules.rule(f.settings, st.active)) {
            null, EndEarly.OFF -> answer(arrayOf(LockSchedule.timedOpensAt(f, st, c)?.let { LockStrings.voiceNoEarly("time" to it.toLocalTime()) } ?: LockStrings.voiceNoEarlyApp()))
            EndEarly.WAIT -> answer(arrayOf(LockStrings.voiceWait("n" to st.active.maxOf { EndEarlyRules.waitMinutes(f.settings, it) })))
            EndEarly.CODE -> {
                answer(arrayOf(LockStrings.voiceCode()))
                codePad(host, LockSignals.PURPOSE_END, null)
            }
        }
    }

    private suspend fun skip(cmd: LockCommand.Skip, f: LockFile, st: LockState, host: PanelRoutines.Host, answer: (Array<out Phrase>) -> Unit) {
        val target = cmd.name?.let { n -> f.locks.firstOrNull { it.name == n && it.on } } ?: LockSchedule.nextLock(f, st, ZonedDateTime.now())?.lock
            ?: return answer(arrayOf(LockStrings.voiceNothingToSkip()))
        // As the warning's Skip this time: with the code, the code pad; otherwise in Settings.
        if (f.settings.effectiveEndEarly != EndEarly.CODE) return answer(arrayOf(LockStrings.voiceNoSkip()))
        answer(arrayOf(LockStrings.voiceCode()))
        codePad(host, LockSignals.PURPOSE_SKIP, target.id)
    }

    /** :lock's code pad on the screen, the panel out of its way. */
    private suspend fun codePad(host: PanelRoutines.Host, purpose: String, lock: String?) {
        host.leave()
        try {
            app.startActivity(LockSignals.code(app, purpose, lock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            RLog.w("lock_code_pad", t)
        }
    }

    private fun label(pkg: String): String = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }
}
