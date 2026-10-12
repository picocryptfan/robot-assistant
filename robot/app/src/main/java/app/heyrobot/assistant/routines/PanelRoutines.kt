package app.heyrobot.assistant.routines

import app.heyrobot.core.RLog
import app.heyrobot.core.TriggerKind
import app.heyrobot.core.BriefComposer
import android.os.Bundle
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineCommandCode
import app.heyrobot.core.RoutineDraft
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.SpeechLang
import app.heyrobot.core.StepGo
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.RoutineIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.UUID

// VOICE PROCESS (0.8). Routines in the panel, decided by code before the model (core RoutineRouter, English and Québec
// French): the morning brief now, a routine made by voice shown on its card and saved only by your yes (or only a tap
// when it came from the screen), "run my evening routine" (asked: every step goes through the approval gate as a typed
// command would, its cards included), "list my routines", "delete …" (a tap on its card, always), and "remind me in the
// morning to …". Also the panel opened by Settings' Run now or a Ready card's tap. PanelController keeps the turn and its
// bubble; this decides what goes in them, through [Host].

object PanelRoutines {
    /** What a routine turn may do in the panel (PanelController's bubble, voice and cards). */
    interface Host {
        val speak: Boolean
        val locked: Boolean
        /** The reply's text, replaced. */
        fun text(replyId: String, text: String)
        /** A paragraph added to the reply. */
        fun append(replyId: String, text: String)
        /** One sentence in Robot's voice, when replies are spoken. */
        fun say(sentence: String)
        /** A card that waits for Save or Cancel; true when confirmed. [tapOnly]: what Robot says to a spoken yes (only a tap). */
        suspend fun ask(replyId: String, card: Card, tapOnly: String? = null): Boolean
        /** A card shown as it is (done, refused, cancelled). */
        fun show(replyId: String, card: Card)
        /** One of the routine's tool steps, through the approval gate with its card, as typed; null when nothing ran. */
        suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed?
        fun finish(replyId: String)
        /** An app opened: the panel steps aside. */
        suspend fun leave()
    }

    private val app get() = VoiceHub.app
    private fun store() = RoutineFiles.store(app)
    private fun id() = UUID.randomUUID().toString()

    /** The routine phrase [q] is, or null (the rest of the panel answers). [fromContent]: the conversation holds screen text or a photo. */
    fun route(q: String, fromContent: Boolean): RoutineCommand? = try {
        val s = store()
        RoutineRouter.route(q, ZonedDateTime.now(), s.all().map { it.name }, fromContent = fromContent, alarmWindow = s.settings().alarmWindow)
    } catch (t: Throwable) {
        RLog.w("routine_route", t)
        null
    }

    suspend fun handle(replyId: String, cmd: RoutineCommand, host: Host) {
        val done = when (cmd) {
            RoutineCommand.BriefNow -> brief(replyId, host, RunOrigin.ASKED)
            is RoutineCommand.Create -> create(replyId, cmd.draft, host)
            is RoutineCommand.Run -> run(replyId, cmd.name, host)
            RoutineCommand.ListAll -> reply(replyId, host, RoutineCards.list(store().all().filter { it.id != BriefRoutine.ID || it.enabled }))
            is RoutineCommand.Delete -> delete(replyId, cmd.name, host)
            is RoutineCommand.Remind -> remind(replyId, cmd, host)
        }
        VoiceLog.add(VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.of(cmd), if (done) 1 else 0)
        host.finish(replyId)
    }

    private fun reply(replyId: String, host: Host, text: String): Boolean {
        host.text(replyId, text)
        host.say(text)
        return true
    }

    // ------------------------------------------------------------------ the brief now

    /**
     * The brief as asked: the greeting at once, then the rest once the weather and the calendar are read, at the lock
     * level (locked: the time and the weather without the town, "Unlock to hear your day"; the rest after the unlock).
     * Spoken part by part, so "hey robot, next" and "repeat" work.
     */
    private suspend fun brief(replyId: String, host: Host, origin: RunOrigin, routine: Routine? = null): Boolean {
        val s = VoiceHub.settings
        val rs = store().settings()
        val r = routine ?: BriefRoutine.of(rs)
        val locked = host.locked
        val session = if (host.speak) RoutineVoice.open(r, RoutineVoice.Surface.PANEL, rs.briefLock == BriefLock.EVERYTHING, locked) { end ->
            RoutineHub.spoken(app, r, TriggerKind.ASKED, end)
        } else null
        val shown = StringBuilder()
        val (spoken, held) = withContext(Dispatchers.IO) {
            RoutineRunner.brief(app, s, rs, r, locked, { parts ->
                if (session != null) RoutineVoice.add(session, parts)
                VoiceHub.main.post {
                    parts.forEach { p -> shown.append(if (shown.isEmpty()) "" else " ").append(p.text) }
                    host.text(replyId, shown.toString())
                }
            }, origin)
        }
        session?.let(RoutineVoice::close)
        // Shown but not spoken: the reminders count as read now.
        if (session == null && spoken.any { it.item == BriefItem.REMINDERS }) withContext(Dispatchers.IO) { BriefSource.remindersSaid(app) }
        if (held.isNotEmpty()) RoutineHub.waitForUnlock(RoutineRunner.Waiting(r, TriggerKind.ASKED, emptyList(), held, ZonedDateTime.now()))
        VoiceLog.add(VoiceEvent.BRIEF_SPOKEN, spoken.sumOf { it.lines.size }, held.sumOf { it.lines.size }, spoken.sumOf { it.chars })
        return true
    }

    // ------------------------------------------------------------------ a routine made by voice

    /**
     * Its card: what starts it, every step and what it does by itself, where it speaks, what goes online. A yes or Save
     * saves it; never by itself. From the screen's text, only the tap.
     */
    private suspend fun create(replyId: String, draft: RoutineDraft, host: Host): Boolean {
        val card = draft.card()
        val body = card.lines.joinToString("\n")
        val id = id()
        if (!draft.canSave) {
            host.text(replyId, "I couldn't make that routine as you said it: edit it in Robot › Settings › Routines.")
            host.show(replyId, Card(id, null, card.title, ActionStatus.INVALID, card.footer, body = body))
            return false
        }
        host.text(replyId, "Here's the routine. " + card.footer)
        host.say("Here's the routine. " + if (draft.fromContent) "Tap Save to keep it." else "Say yes to save it.")
        val yes = host.ask(replyId, Card(id, null, card.title, ActionStatus.PENDING, card.footer, body = body, confirm = "Save", cancel = "Cancel"),
            tapOnly = if (draft.fromContent) "Tap Save: this routine came from the screen." else null)
        if (!yes) {
            host.show(replyId, Card(id, null, card.title, ActionStatus.DECLINED, "Not saved.", body = body))
            return false
        }
        val saved = draft.save(System.currentTimeMillis())
        val ok = saved != null && withContext(Dispatchers.IO) { store().save(saved) }
        if (!ok) {
            host.show(replyId, Card(id, null, card.title, ActionStatus.FAILED, "Robot couldn't save it. Settings › Routines says why.", body = body))
            return false
        }
        RoutineHub.reload()
        host.show(replyId, Card(id, null, "Saved “${saved.name}”", ActionStatus.DONE, "It runs ${RoutineCards.whenText(saved)}.", body = body))
        host.say("Saved.")
        return true
    }

    // ------------------------------------------------------------------ run, delete, remind

    private fun find(name: String): Routine? {
        val all = store().all()
        if (name.isEmpty()) return all.filter { it.id != BriefRoutine.ID }.singleOrNull()
        return RoutineRouter.find(name, all) { it.name }
    }

    private fun names(): String = store().all().filter { it.id != BriefRoutine.ID }.joinToString(", ") { "“${it.name}”" }.ifEmpty { "none yet" }

    private suspend fun run(replyId: String, name: String, host: Host): Boolean {
        val r = find(name) ?: return !reply(replyId, host, if (name.isEmpty()) "Which routine? Yours: ${names()}." else "You have no routine called “$name”. Yours: ${names()}.")
        runAsked(replyId, r, host, RunOrigin.ASKED)
        return true
    }

    /**
     * Runs [r] with you there ([RunOrigin.ASKED], or a Ready card's tap): the brief and its sentences in the bubble and
     * the voice, each tool step through the approval gate with its card (asked, never "from content"), what is off or
     * needs the unlock said in a line. An app opening ends it.
     */
    suspend fun runAsked(replyId: String, r: Routine, host: Host, origin: RunOrigin) {
        val s = VoiceHub.settings
        val rs = store().settings()
        host.text(replyId, "Running “${r.name}”.")
        for (step in r.steps) {
            val go = RoutineGate.decide(step, origin, host.locked, r, s, rs)
            VoiceLog.add(VoiceEvent.ROUTINE_STEP, step.code, go)
            when (go) {
                StepGo.NEVER -> host.append(replyId, "Routines can't do this: ${step.describe()}.")
                StepGo.DISABLED -> host.append(replyId, "Off in Settings › Actions: ${step.describe()}.")
                StepGo.WAIT_UNLOCK -> host.append(replyId, "Unlock your phone for: ${step.describe()}.")
                else -> when (step) {
                    RoutineStep.Brief -> brief(replyId, host, origin, r)
                    is RoutineStep.Say -> { host.append(replyId, step.text); host.say(step.text) }
                    RoutineStep.Reminders -> {
                        val now = ZonedDateTime.now()
                        val due = BriefReminders.due(store().reminders(), now.toLocalDate(), now.zone).map { it.text }
                        val line = BriefComposer.reminders(due, BriefSource.lang(rs)) ?: "No brief reminders today."
                        host.append(replyId, line)
                        host.say(line)
                    }
                    RoutineStep.Messages -> {}
                    is RoutineStep.Tool -> {
                        val e = host.tool(replyId, step) ?: continue
                        if (e.launchedApp && e.status.succeeded) return host.leave()
                        host.say(e.spoken ?: e.detail)
                    }
                }
            }
        }
    }

    private suspend fun delete(replyId: String, name: String, host: Host): Boolean {
        val r = find(name) ?: return !reply(replyId, host, "You have no routine called “$name”. Yours: ${names()}.")
        if (r.id == BriefRoutine.ID) return !reply(replyId, host, "The morning brief is built in: turn it off in Robot › Settings › Routines.")
        val id = id()
        host.text(replyId, "Tap Delete to delete it.")
        host.say("Tap Delete on the card to delete it.")
        val yes = host.ask(replyId, Card(id, null, "Delete the routine “${r.name}”?", ActionStatus.PENDING, RoutineCards.whenText(r),
            body = r.steps.joinToString("\n") { it.describe() }, confirm = "Delete", cancel = "Cancel"), tapOnly = "Tap Delete on the card to delete it.")
        if (!yes) {
            host.show(replyId, Card(id, null, "Delete the routine “${r.name}”?", ActionStatus.DECLINED, "Kept."))
            return false
        }
        val ok = withContext(Dispatchers.IO) { store().delete(r.id) }
        RoutineHub.reload()
        host.show(replyId, Card(id, null, "Deleted “${r.name}”", if (ok) ActionStatus.DONE else ActionStatus.FAILED, if (ok) null else "Robot couldn't change the file."))
        return ok
    }

    private suspend fun remind(replyId: String, cmd: RoutineCommand.Remind, host: Host): Boolean {
        val now = ZonedDateTime.now()
        val day = BriefReminders.dayFor(now, cmd.tomorrow)
        val ok = withContext(Dispatchers.IO) { store().editReminders { BriefReminders.add(it, cmd.text, day) } } != null
        if (!ok) return !reply(replyId, host, "Robot couldn't keep that reminder.")
        val fr = SpeechLang.french(cmd.text)
        val brief = store().settings().briefEnabled
        val text = if (fr) "C'est noté pour ton résumé de " + (if (day == now.toLocalDate()) "ce matin" else "demain matin") + " : ${cmd.text}."
            else "I'll remind you in " + (if (day == now.toLocalDate()) "this morning's" else "tomorrow's") + " brief: ${cmd.text}."
        val tail = when {
            brief -> ""
            fr -> " Active le résumé du matin dans Robot › Réglages › Routines pour l'entendre."
            else -> " Turn on the morning brief in Robot › Settings › Routines to hear it."
        }
        return reply(replyId, host, text + tail)
    }

    // ------------------------------------------------------------------ the panel opened for a routine

    /**
     * The panel's show arguments asked for a routine (Settings' Run now) or Ready cards (a tap): what the turn says it is
     * for, and the work; null otherwise.
     */
    fun onShow(args: Bundle?): Pair<String, suspend (String, Host) -> Unit>? {
        val runId = args?.getString(RoutineIntents.ARG_ROUTINE)
        if (runId != null) {
            val r = store().get(runId) ?: return null
            return "Run “${r.name}”" to { replyId, host ->
                VoiceLog.add(VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.RUN, 1)
                runAsked(replyId, r, host, RunOrigin.ASKED)
                host.finish(replyId)
            }
        }
        val key = args?.getString(RoutineIntents.ARG_READY) ?: return null
        return "Ready for you" to { replyId, host -> ready(replyId, key, host); host.finish(replyId) }
    }

    /**
     * Ready cards ([key]: the one you tapped, run at once; [RoutineIntents.ALL_READY]: each, with Do it or Not now). A tap
     * runs it through the approval gate (READY_TAP), so who-to cards and the rest still apply; done ones leave the list.
     */
    private suspend fun ready(replyId: String, key: String, host: Host) {
        val today = ZonedDateTime.now().toLocalDate()
        val live = ReadyCards.live(store().ready(), today)
        val cards = if (key == RoutineIntents.ALL_READY) live else live.filter { it.key == key }
        if (cards.isEmpty()) {
            reply(replyId, host, "Nothing is waiting for you.")
            return
        }
        host.text(replyId, ReadyCards.publicLine(cards.size) + ".")
        for (c in cards) {
            if (key == RoutineIntents.ALL_READY) {
                val id = id()
                val yes = host.ask(replyId, Card(id, (c.step as? RoutineStep.Tool)?.action?.tool, c.text(), ActionStatus.PENDING, null,
                    confirm = if (c.missed != null) "Run it" else "Do it", cancel = "Not now"))
                host.show(replyId, Card(id, (c.step as? RoutineStep.Tool)?.action?.tool, c.text(), if (yes) ActionStatus.DONE else ActionStatus.DECLINED,
                    if (yes) null else "Still waiting until the end of the day."))
                if (!yes) continue
            }
            withContext(Dispatchers.IO) { store().editReady { list -> list.filterNot { it.key == c.key } } }
            VoiceLog.add(VoiceEvent.READY_TAPPED, if (c.missed != null) 0 else c.step.code.id)
            val r = store().get(c.routineId) ?: continue
            if (c.missed != null) {
                runAsked(replyId, r, host, RunOrigin.READY_TAP)
            } else {
                runAsked(replyId, r.copy(steps = listOf(c.step)), host, RunOrigin.READY_TAP)
            }
        }
        RoutineNotices.ready(app, ReadyCards.live(store().ready(), today))
    }

}
