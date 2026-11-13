// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.logbook

import android.content.Context
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.logbook.Logbook
import app.heyrobot.core.logbook.LogbookAnswer
import app.heyrobot.core.logbook.LogbookCommand
import app.heyrobot.core.logbook.LogbookDelete
import app.heyrobot.core.logbook.LogbookHeard
import app.heyrobot.core.logbook.LogbookResult
import app.heyrobot.core.logbook.LogbookRouter
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LogbookStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.LogbookAdd
import app.heyrobot.sys.LogbookFiles
import app.heyrobot.sys.LogbookRead
import app.heyrobot.sys.LogbookVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.UUID

/**
 * VOICE PROCESS. The panel's logbook slot (docs/plans/spoken-log-plan.md §2 to §4): after the everyday set and before
 * hands-free, memory and the model (PanelController.send). Your words to the log (core LogbookRouter), answered by code
 * (core Logbook); nothing goes online. During a phone lock it takes nothing (fail closed). The router reads only [heard],
 * read off the main thread after each turn (and again in [route] when it says locked but the phone is unlocked); before the first read nothing routes (spoken-log-4: off until read, fail closed).
 * Lines that carry an entry's words are personal. The voice log keeps the kind of turn and how it ended, never an entry's words.
 */
object PanelLogbook {
    @Volatile private var heard: LogbookHeard? = null

    /** What the router knows of the log. Off the main thread. */
    fun refresh(ctx: Context) {
        heard = try {
            if (!LogbookFiles.settings(ctx).on) LogbookHeard.OFF else when (val r = LogbookFiles.store(ctx).read()) {
                is LogbookRead.Ok -> LogbookHeard.of(true, r.entries)
                else -> LogbookHeard(on = true, empty = false, stems = emptySet(), units = emptySet(), locked = true)
            }
        } catch (t: Exception) {
            RLog.w("logbook_heard", t) // l10n: not text (a log code)
            LogbookHeard.OFF
        }
    }

    fun route(ctx: Context, q: String): LogbookCommand? {
        if (PhoneLockNow.someoneElse(ctx)) return null
        val h = current(heard, app.heyrobot.sys.Assistant.keyguard(ctx)) { refresh(ctx); heard }
        return LogbookRouter.route(q, h, ZonedDateTime.now())
    }

    /**
     * What the router reads now. A panel shown over the lock screen stays up through the unlock (send reads the lock
     * per question, PanelContinuity), so [h] can still say locked: then the log is read again, here on the caller's
     * thread, once after that unlock, and an unlocked phone never gets "unlock first". [lockedNow]: the lock screen is up.
     */
    internal fun current(h: LogbookHeard?, lockedNow: Boolean, reread: () -> LogbookHeard?): LogbookHeard {
        val last = h ?: LogbookHeard.OFF
        return if (last.locked && !lockedNow) reread() ?: LogbookHeard.OFF else last
    }

    suspend fun handle(ctx: Context, replyId: String, cmd: LogbookCommand, host: PanelRoutines.Host) {
        val l = host.langs(null, cmd.lang)
        try {
            val result = if (cmd is LogbookCommand.DeleteLast) deleteLast(LogbookFiles.store(ctx), replyId, host, l, ZonedDateTime.now())
            else reply(replyId, host, l, withContext(Dispatchers.IO) { answer(ctx, cmd, ZonedDateTime.now()) })
            VoiceLog.add(VoiceEvent.LOGBOOK, cmd.kind.id, result.id)
        } finally {
            host.finish(replyId)
        }
        withContext(Dispatchers.IO) { refresh(ctx) }
    }

    private fun reply(replyId: String, host: PanelRoutines.Host, l: Langs, a: LogbookAnswer): LogbookResult {
        host.text(replyId, a.text.render(l))
        host.say(a.text.render(l.voice), personal = a.result !in NOT_PERSONAL)
        return a.result
    }

    /** spoken-log-4: the only replies with none of your entries' words in them. */
    private val NOT_PERSONAL = setOf(LogbookResult.EMPTY, LogbookResult.LOCKED, LogbookResult.FULL, LogbookResult.FAILED)

    /** The entries deleted by voice in this process, for their cards' Undo ([undoDelete]). */
    private val undos = LogbookDelete.Undos()

    /**
     * spoken-log-3, "delete the last log entry", « efface la dernière inscription du carnet »: a card naming the newest
     * entry and its time, said aloud before a spoken yes can delete it (as a note's delete, 1.2.4); deleted on your yes or
     * tap, with Undo ([undoDelete]) that puts it back whole. An empty or locked logbook: Robot says so.
     */
    internal suspend fun deleteLast(store: LogbookVault, replyId: String, host: PanelRoutines.Host, l: Langs, now: ZonedDateTime): LogbookResult {
        val e = when (val r = withContext(Dispatchers.IO) { store.read() }) {
            is LogbookRead.Ok -> LogbookDelete.last(r.entries) ?: return reply(replyId, host, l, Logbook.empty())
            LogbookRead.Locked -> return reply(replyId, host, l, Logbook.unlockFirst())
            LogbookRead.NotNow -> return reply(replyId, host, l, Logbook.deleteFailed())
        }
        val id = UUID.randomUUID().toString()
        val title = Logbook.deleteTitle(e, now).render(l)
        val ask = Logbook.deleteAsk(e, now)
        val button = Langs(l.ui, l.ui, l.system, pseudo = l.pseudo)
        host.say(ask.render(l.voice), personal = true)
        val yes = host.ask(replyId, Card(id, null, ask.render(l), ActionStatus.PENDING, null, confirm = UiStrings.delete().render(button),
            cancel = UiStrings.cancel().render(button)))
        if (!yes) { host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, null)); return LogbookResult.KEPT }
        host.show(replyId, Card(id, null, title, ActionStatus.DONE, null))
        val key = withContext(Dispatchers.IO) { undos.delete(store, e) } ?: return reply(replyId, host, l, Logbook.deleteFailed())
        val done = Logbook.deleted(e)
        host.show(replyId, Card(UUID.randomUUID().toString(), null, done.text.render(l), ActionStatus.DONE, null, undo = key))
        host.say(done.text.render(l.voice), personal = true)
        return done.result
    }

    /** An Undo's [line] for its card, and its key [again] when it failed for now and can be tried again (spoken-log-4). */
    data class Undone(val line: String, val again: String?)

    /** A deleted entry's Undo ([deleteLast]'s card). During someone else's lock: not back, and tried again later. */
    fun undoDelete(ctx: Context, key: String, l: Langs): Undone {
        if (PhoneLockNow.someoneElse(ctx)) return Undone(LogbookStrings.notRestored().render(l), key)
        return undoDelete(LogbookFiles.store(ctx), key, l).also { refresh(ctx) }
    }

    internal fun undoDelete(store: LogbookVault, key: String, l: Langs): Undone =
        if (undos.restore(store, key)) Undone(LogbookStrings.restored().render(l), null)
        else Undone(LogbookStrings.notRestored().render(l), key.takeIf { undos.has(it) })

    private fun answer(ctx: Context, cmd: LogbookCommand, now: ZonedDateTime): LogbookAnswer {
        val store = LogbookFiles.store(ctx)
        if (cmd is LogbookCommand.Add) {
            val e = Logbook.entryOf(cmd, UUID.randomUUID().toString(), now.toInstant().toEpochMilli())
            return when (store.add(e, LogbookFiles.settings(ctx).addLocked)) {
                LogbookAdd.ADDED -> Logbook.logged(e, cmd.timed, now, locked = false)
                LogbookAdd.ADDED_LOCKED -> Logbook.logged(e, cmd.timed, now, locked = true)
                LogbookAdd.NOT_WHILE_LOCKED -> Logbook.unlockFirst()
                LogbookAdd.FULL -> Logbook.full()
                LogbookAdd.FAILED -> Logbook.failed()
            }
        }
        return when (val r = store.read()) {
            is LogbookRead.Ok -> Logbook.answer(cmd, r.entries, now)
            LogbookRead.Locked -> Logbook.unlockFirst()
            LogbookRead.NotNow -> Logbook.notNow()
        }
    }
}
