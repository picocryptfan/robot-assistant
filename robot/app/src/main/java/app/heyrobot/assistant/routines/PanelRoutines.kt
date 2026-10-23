// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import app.heyrobot.core.RLog
import app.heyrobot.core.ToolName
import app.heyrobot.core.TriggerKind
import app.heyrobot.core.BriefComposer
import android.os.Bundle
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Action
import app.heyrobot.core.ActionOutcome
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.Card
import app.heyrobot.core.ClockNeed
import app.heyrobot.core.Executed
import app.heyrobot.core.Outcome
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineLockedPhone
import app.heyrobot.core.RoutineCommandCode
import app.heyrobot.core.RoutineDraft
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.StepGo
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.RoutineTexts
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReplyLang
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.RoutineFacts
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
        /**
         * One sentence in Robot's voice, when replies are spoken. [personal]: it says something of yours (a routine's own
         * sentence, your brief reminders), so in a headset or car session OutputGuard treats it as personal.
         */
        fun say(sentence: String, personal: Boolean = false)
        /** A card that waits for Save or Cancel; true when confirmed. [tapOnly]: what Robot says to a spoken yes (only a tap). */
        suspend fun ask(replyId: String, card: Card, tapOnly: String? = null): Boolean
        /** 1.1 (Skills' save card): [ask], with the answer as it came (core Confirmations: YES, NO, a choice or a chip, Card.CHIP). */
        suspend fun pick(replyId: String, card: Card, tapOnly: String? = null): Int = if (ask(replyId, card, tapOnly)) 0 else -1
        /** A card shown as it is (done, refused, cancelled). */
        fun show(replyId: String, card: Card)
        /** One of the routine's tool steps, through the approval gate with its card, as typed; null when nothing ran. */
        suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed?
        fun finish(replyId: String)
        /**
         * 1.0 (injection-brief-calendar-untainted): the reply reads out your calendar or reminders as they are (core Quoted):
         * the conversation is content and personal from then on.
         */
        fun quoted(replyId: String)
        /** An app opened: the panel steps aside. */
        suspend fun leave()
        /** 1.1: opens [intent] from the panel (the clock app for "cancel my alarm"); false when nothing opened. */
        fun launch(intent: android.content.Intent): Boolean = false

        /**
         * 1.1: this turn's languages (core i18n Langs): what Robot says in its reply language, the
         * cards' buttons and Robot's pages in the menus'. [router]: the language the routine router surely heard
         * (RoutineRouter.lang), null when nothing was heard (Run now, a Ready card) or it isn't sure; [said]: your words.
         * By default decided here from Settings › Language › "Robot answers in", the router's match, then LangId (core
         * ReplyLang); the panel may return its own turn's languages instead (PanelController's turnLangs).
         */
        fun langs(said: String?, router: Lang?): Langs = PanelRoutines.decideLangs(said, router)
    }

    private val app get() = VoiceHub.app

    /** [Host.langs]' default: the setting, else the router's match, else what LangId hears, else the menus' (core ReplyLang). */
    fun decideLangs(said: String?, router: Lang?): Langs {
        val base = AppLanguage.langs(app)
        val setting = VoiceHub.settings.replyLang
        val say = if (said.isNullOrBlank()) ReplyLang.unheard(base.ui, setting) else {
            val guess = if (router != null) null else try { ConversationEngine.langId(app).detect(said) } catch (_: Throwable) { null }
            ReplyLang.decide(guess, router, null, System.currentTimeMillis(), base.ui, setting)
        }
        return base.copy(say = say)
    }

    /** What Robot says, in [l]: the bubble's form and the voice's (French without its typography). */
    private fun Phrase.shown(l: Langs) = render(l)
    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)

    /** A button's words: the menus' language, whatever the reply language (as the cards' Do it and Cancel). */
    private fun Phrase.button(l: Langs) = render(Langs(l.ui, l.ui, l.system, pseudo = l.pseudo))
    private fun store() = RoutineFiles.store(app)
    private fun id() = UUID.randomUUID().toString()

    /**
     * The routine phrase [q] is, or null (the rest of the panel answers). [fromContent]: the conversation holds screen text or a
     * photo. [fresh]: nothing was asked before in this conversation; 1.1, only then does "remind me about it" ask what and when
     * (RoutineCommand.RemindWhat): later, "it" may be something said before, and the model reads it.
     */
    fun route(q: String, fromContent: Boolean, fresh: Boolean = true): RoutineCommand? = try {
        val s = store()
        RoutineRouter.route(q, ZonedDateTime.now(), s.all().map { it.name }, fromContent = fromContent, alarmWindow = s.settings().alarmWindow)
            ?.takeIf { fresh || it != RoutineCommand.RemindWhat }
    } catch (t: Throwable) {
        RLog.w("routine_route", t)
        null
    }

    /** [cmd] as routed from [said], your words this turn (their language is the answer's). */
    suspend fun handle(replyId: String, cmd: RoutineCommand, host: Host, said: String = "") {
        // 1.0 (physical-routines-writable-locked): nothing is created, deleted, reminded or listed on the locked phone; said in
        // French, the refusal is French too (core RoutineRouter.french).
        val langs = host.langs(said, RoutineRouter.lang(said, cmd))
        RoutineLockedPhone.refuse(cmd, host.locked, langs)?.let { why ->
            reply(replyId, host, why, langs)
            VoiceLog.add(VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.of(cmd), 0)
            host.finish(replyId)
            return
        }
        val done = when (cmd) {
            RoutineCommand.BriefNow -> brief(replyId, host, RunOrigin.ASKED, langs)
            is RoutineCommand.Create -> create(replyId, cmd.draft, host, langs)
            is RoutineCommand.Run -> run(replyId, cmd.name, host, langs)
            RoutineCommand.ListAll -> reply(replyId, host, RoutineCards.listText(store().all().filter { it.id != BriefRoutine.ID || it.enabled }), langs)
            is RoutineCommand.Delete -> delete(replyId, cmd.name, host, langs)
            is RoutineCommand.Remind -> remind(replyId, cmd, host, langs)
            is RoutineCommand.RemindOn -> remindOn(replyId, cmd, host, langs)
            RoutineCommand.RemindWhat -> reply(replyId, host, RoutineStrings.remindWhat(), langs)
            is RoutineCommand.CantCancel -> cantCancel(replyId, cmd, host, langs)
        }
        VoiceLog.add(VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.of(cmd), if (done) 1 else 0)
        host.finish(replyId)
    }

    private fun reply(replyId: String, host: Host, text: Phrase, l: Langs): Boolean {
        host.text(replyId, text.shown(l))
        host.say(text.said(l))
        return true
    }

    // ------------------------------------------------------------------ the brief now

    /**
     * The brief as asked: the greeting at once, then the rest once the weather and the calendar are read, at the lock
     * level (locked: the time and the weather without the town, "Unlock to hear your day"; the rest after the unlock).
     * Spoken part by part, so "hey robot, next" and "repeat" work.
     */
    private suspend fun brief(replyId: String, host: Host, origin: RunOrigin, langs: Langs, routine: Routine? = null, append: Boolean = false): Boolean {
        val s = VoiceHub.settings
        val rs = store().settings()
        val r = routine ?: BriefRoutine.of(rs)
        val locked = host.locked
        // Calendar titles and reminders, as written by whoever wrote them: content, and personal.
        host.quoted(replyId)
        val session = if (host.speak) RoutineVoice.open(r, RoutineVoice.Surface.PANEL, rs.briefLock == BriefLock.EVERYTHING, locked) { end ->
            RoutineHub.spoken(app, r, TriggerKind.ASKED, end)
        } else null
        val shown = StringBuilder()
        val (spoken, held) = withContext(Dispatchers.IO) {
            RoutineRunner.brief(app, s, rs, r, locked, { parts ->
                if (session != null) RoutineVoice.add(session, parts)
                if (!append) VoiceHub.main.post {
                    parts.forEach { p -> shown.append(if (shown.isEmpty()) "" else " ").append(p.shownText) }
                    host.text(replyId, shown.toString())
                }
            }, origin, langs)
        }
        session?.let(RoutineVoice::close)
        // The whole of what was said, in the bubble and the saved conversation (the parts above came while it was read).
        val said = spoken.joinToString(" ") { it.shownText }
        if (append) host.append(replyId, said) else host.text(replyId, said)
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
    private suspend fun create(replyId: String, draft: RoutineDraft, host: Host, l: Langs): Boolean {
        val text = draft.cardText()
        val card = text.render(l)
        val body = card.lines.joinToString("\n")
        val id = id()
        val settingsPage = UiStrings.inSettings(UiStrings.routines)
        if (!draft.canSave) {
            host.text(replyId, RoutineStrings.couldntMake("page" to settingsPage).shown(l))
            host.show(replyId, Card(id, null, card.title, ActionStatus.INVALID, card.footer, body = body))
            return false
        }
        host.text(replyId, RoutineStrings.heresRoutine("footer" to text.footer).shown(l))
        // 1.2: after an alarm on a phone without GrapheneOS Clock it would never start: said before the yes, not after.
        val noClock = ClockNeed.unmet(draft.routine.trigger, withContext(Dispatchers.IO) { RoutineFacts.clockInstalled(app) })
        if (noClock) {
            // Shown, and said too: a routine made hands-free (a headset, the car) is heard, not read.
            host.append(replyId, RoutineStrings.clockMissing().shown(l))
            host.say(RoutineStrings.clockMissing().said(l))
        }
        host.say((if (draft.fromContent) RoutineStrings.heresTapSave("save" to UiLabel(UiStrings.save)) else RoutineStrings.heresSayYes()).said(l))
        val yes = host.ask(replyId, Card(id, null, card.title, ActionStatus.PENDING, card.footer, body = body, confirm = UiStrings.save().button(l),
            cancel = UiStrings.cancel().button(l)), tapOnly = if (draft.fromContent) RoutineStrings.tapSaveFromScreen("save" to UiLabel(UiStrings.save)).said(l) else null)
        if (!yes) {
            host.show(replyId, Card(id, null, card.title, ActionStatus.DECLINED, RoutineStrings.notSaved().shown(l), body = body))
            return false
        }
        // The phone may have locked while the card waited.
        if (host.locked) {
            host.show(replyId, Card(id, null, card.title, ActionStatus.NEEDS_UNLOCK, RoutineStrings.unlockChange().shown(l), body = body))
            return false
        }
        val saved = draft.save(System.currentTimeMillis())
        val ok = saved != null && withContext(Dispatchers.IO) { store().save(saved) }
        if (!ok) {
            host.show(replyId, Card(id, null, card.title, ActionStatus.FAILED, RoutineStrings.couldntSave("page" to settingsPage).shown(l), body = body))
            return false
        }
        RoutineHub.reload()
        host.show(replyId, Card(id, null, RoutineStrings.savedTitle("name" to saved.name).shown(l), ActionStatus.DONE,
            RoutineStrings.itRuns("when" to RoutineCards.whenPhrase(saved)).shown(l), body = body))
        host.say(RoutineStrings.saved().said(l))
        return true
    }

    // ------------------------------------------------------------------ run, delete, remind

    private fun find(name: String): Routine? {
        val all = store().all()
        if (name.isEmpty()) return all.filter { it.id != BriefRoutine.ID }.singleOrNull()
        return RoutineRouter.find(name, all) { it.name }
    }

    private fun names(): Phrase = store().all().filter { it.id != BriefRoutine.ID }.map { RoutineStrings.quoted("name" to it.name) }
        .let { if (it.isEmpty()) RoutineStrings.noneYet() else Phrase.Join(it, ", ") }

    private suspend fun run(replyId: String, name: String, host: Host, l: Langs): Boolean {
        val r = find(name) ?: return !reply(replyId, host, if (name.isEmpty()) RoutineStrings.which("names" to names())
            else RoutineStrings.noneCalled("name" to name, "names" to names()), l)
        // A routine that's off (you turned it off, or a backup someone else made brought it in off) runs only after a tap on
        // its card: a step at Just do it would otherwise run with no card of its own. The built-in brief's step is Robot's own.
        if (!r.enabled && r.id != BriefRoutine.ID && !offTapped(replyId, r, host, l)) return false
        runAsked(replyId, r, host, RunOrigin.ASKED, l)
        return true
    }

    /**
     * [r] is off: its steps on a card that only a tap of Run answers (a spoken yes doesn't), before anything runs. True when
     * tapped. On the locked phone, a line instead: unlock, or turn it on in Settings › Routines.
     */
    private suspend fun offTapped(replyId: String, r: Routine, host: Host, l: Langs): Boolean {
        val name = RoutineTexts.name(r)
        val page = UiStrings.inSettings(UiStrings.routines)
        if (host.locked) return !reply(replyId, host, RoutineStrings.offLocked("name" to name, "page" to page), l)
        val id = id()
        val run = UiLabel(UiStrings.run)
        val title = RoutineStrings.offTitle("name" to name).shown(l)
        val body = r.steps.joinToString("\n") { it.summary().shown(l) }
        host.text(replyId, RoutineStrings.offTapRun("name" to name, "run" to run).shown(l))
        host.say(RoutineStrings.offTapRun("name" to name, "run" to run).said(l))
        val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, RoutineStrings.offInPage("page" to page).shown(l), body = body,
            confirm = UiStrings.run().button(l), cancel = UiStrings.cancel().button(l)), tapOnly = RoutineStrings.offTapRunOnCard("run" to run).said(l))
        if (!yes) {
            host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, RoutineStrings.notRun().shown(l), body = body))
            return false
        }
        host.show(replyId, Card(id, null, title, ActionStatus.DONE, null, body = body))
        return true
    }

    /**
     * Runs [r] with you there ([RunOrigin.ASKED], or a Ready card's tap): the brief and its sentences in the bubble and
     * the voice, each tool step through the approval gate with its card (asked, never "from content"), what is off or
     * needs the unlock said in a line. An app opening ends it.
     */
    suspend fun runAsked(replyId: String, r: Routine, host: Host, origin: RunOrigin, l: Langs = host.langs(null, null)) {
        val s = VoiceHub.settings
        val rs = store().settings()
        host.text(replyId, RoutineStrings.runningNow("name" to RoutineTexts.name(r)).shown(l))
        for (step in r.steps) {
            val go = RoutineGate.decide(step, origin, host.locked, r, s, rs)
            VoiceLog.add(VoiceEvent.ROUTINE_STEP, step.code, go)
            when (go) {
                StepGo.NEVER -> host.append(replyId, RoutineStrings.cantDo("step" to step.summary()).shown(l))
                StepGo.DISABLED -> host.append(replyId, RoutineStrings.offIn("page" to UiStrings.inSettings(UiStrings.actions), "step" to step.summary()).shown(l))
                StepGo.WAIT_UNLOCK -> host.append(replyId, RoutineStrings.unlockFor("step" to step.summary()).shown(l))
                else -> {
                    // The routine's own sentence and your brief reminders go in the reply as written (by you, or by whoever
                    // made the backup it came from): content, and personal from then on (core Quoted), as the brief's are.
                    if (step is RoutineStep.Say || step == RoutineStep.Reminders) host.quoted(replyId)
                    when (step) {
                        RoutineStep.Brief -> brief(replyId, host, origin, l, r, append = r.steps.size > 1)
                        is RoutineStep.Say -> { host.append(replyId, step.text); host.say(step.text, personal = true) }
                        RoutineStep.Reminders -> {
                            val now = ZonedDateTime.now()
                            val due = BriefReminders.due(store().reminders(), now.toLocalDate(), now.zone).map { it.text }
                            val bl = l.copy(say = BriefSource.lang(rs, l))
                            // The bubble in the screen's form, like the other steps' (« 14 h 30 »); the voice gets its own (« 14 heures
                            // 30 », core VoiceForm).
                            val line = BriefComposer.remindersText(due, bl.say) ?: RoutineStrings.noReminders()
                            host.append(replyId, line.shown(bl))
                            host.say(line.said(bl), personal = true)
                        }
                        RoutineStep.Messages -> {}
                        is RoutineStep.Tool -> {
                            val e = host.tool(replyId, step) ?: continue
                            if (e.launchedApp && e.status.succeeded) return host.leave()
                            // 1.1: the result's words in the turn's reply language when it has them (core Executed's phrases).
                            val words = e.spoken?.let { en -> e.spokenText?.takeIf { it.en() == en } ?: Phrase.plain(en) }
                                ?: e.text?.takeIf { it.en() == e.detail } ?: Phrase.plain(e.detail)
                            host.say(words.said(l))
                        }
                    }
                }
            }
        }
    }

    private suspend fun delete(replyId: String, name: String, host: Host, l: Langs): Boolean {
        val r = find(name) ?: return !reply(replyId, host, RoutineStrings.noneCalled("name" to name, "names" to names()), l)
        if (r.id == BriefRoutine.ID) return !reply(replyId, host, RoutineStrings.briefBuiltIn("page" to UiStrings.inSettings(UiStrings.routines)), l)
        val id = id()
        val delete = UiLabel(UiStrings.delete)
        val title = RoutineStrings.deleteTitle("name" to r.name).shown(l)
        host.text(replyId, RoutineStrings.tapDelete("delete" to delete).shown(l))
        host.say(RoutineStrings.tapDeleteOnCard("delete" to delete).said(l))
        val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, RoutineCards.whenPhrase(r).shown(l),
            body = r.steps.joinToString("\n") { it.summary().shown(l) }, confirm = UiStrings.delete().button(l), cancel = UiStrings.cancel().button(l)),
            tapOnly = RoutineStrings.tapDeleteOnCard("delete" to delete).said(l))
        if (!yes) {
            host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, RoutineStrings.kept().shown(l)))
            return false
        }
        if (host.locked) {
            host.show(replyId, Card(id, null, title, ActionStatus.NEEDS_UNLOCK, RoutineStrings.unlockChange().shown(l)))
            return false
        }
        val ok = withContext(Dispatchers.IO) { store().delete(r.id) }
        RoutineHub.reload()
        host.show(replyId, Card(id, null, RoutineStrings.deletedTitle("name" to r.name).shown(l), if (ok) ActionStatus.DONE else ActionStatus.FAILED,
            if (ok) null else RoutineStrings.couldntChange().shown(l)))
        return ok
    }

    private suspend fun remind(replyId: String, cmd: RoutineCommand.Remind, host: Host, l: Langs): Boolean {
        val now = ZonedDateTime.now()
        val day = BriefReminders.dayFor(now, cmd.tomorrow)
        if (host.locked) return !reply(replyId, host, RoutineStrings.unlockRemind(), l)
        val ok = withContext(Dispatchers.IO) { store().editReminders { BriefReminders.add(it, cmd.text, day) } } != null
        if (!ok) return !reply(replyId, host, RoutineStrings.couldntKeepReminder(), l)
        val brief = store().settings().briefEnabled
        val text = (if (day == now.toLocalDate()) RoutineStrings.remindThisMorning else RoutineStrings.remindTomorrow)("text" to cmd.text)
        return reply(replyId, host, if (brief) text else Phrase.join(text, RoutineStrings.turnOnBrief("page" to UiStrings.inSettings(UiStrings.routines))), l)
    }

    /**
     * 1.1, a dated reminder: an event with an alert on add_event's card, which always asks (the calendar set in Settings ›
     * People, notes and calendar). Robot's reply is the card's own words once it is added; nothing else is said for it.
     */
    private suspend fun remindOn(replyId: String, cmd: RoutineCommand.RemindOn, host: Host, l: Langs): Boolean {
        val r = host.tool(replyId, RoutineStep.Tool(Action.AddEvent(cmd.title, cmd.whenText, alert = true)))
            ?: return !reply(replyId, host, RoutineStrings.remindOnNotAdded(), l)
        if (r.launchedApp && r.status.succeeded) {
            host.leave()
            return true
        }
        val o = Outcome(ToolName.ADD_EVENT, r.status, r.detail, spoken = r.spoken, text = r.text, spokenText = r.spokenText)
        host.text(replyId, ActionOutcome.line(o) { it.shown(l) })
        host.say(ActionOutcome.line(o) { it.said(l) }, personal = true)
        return r.status.succeeded
    }

    /**
     * 1.1, "cancel my alarm": Android lets Robot set alarms and timers, never cancel them. Robot says so, and its card opens
     * your clock app on a tap only (a spoken yes doesn't; locked, the line alone).
     */
    private suspend fun cantCancel(replyId: String, cmd: RoutineCommand.CantCancel, host: Host, l: Langs): Boolean {
        reply(replyId, host, if (cmd.timer) ToolStrings.cantCancelTimers() else ToolStrings.cantCancelAlarms(), l)
        if (host.locked) return true
        val id = id()
        val title = ToolStrings.openClockApp().shown(l)
        val open = UiLabel(UiStrings.open)
        val yes = host.ask(replyId, Card(id, ToolName.OPEN_APP, title, ActionStatus.PENDING, null, confirm = UiStrings.open().button(l),
            cancel = UiStrings.cancel().button(l)), tapOnly = ToolStrings.tapOpenClock("open" to open).said(l))
        if (!yes) {
            host.show(replyId, Card(id, ToolName.OPEN_APP, title, ActionStatus.DECLINED, null))
            return true
        }
        val show = android.content.Intent(if (cmd.timer) android.provider.AlarmClock.ACTION_SHOW_TIMERS else android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        if (!host.launch(show)) {
            host.show(replyId, Card(id, ToolName.OPEN_APP, title, ActionStatus.NO_APP,
                (if (cmd.timer) ToolStrings.noClockTimers() else ToolStrings.noClockAlarms()).shown(l)))
            return false
        }
        host.leave()
        return true
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
            // Nothing was heard: Settings › Language's "Robot answers in", else the menus'.
            val l = decideLangs(null, null)
            return RoutineStrings.runLabel("name" to RoutineTexts.name(r)).shown(l) to { replyId, host ->
                VoiceLog.add(VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.RUN, 1)
                runAsked(replyId, r, host, RunOrigin.ASKED)
                host.finish(replyId)
            }
        }
        val key = args?.getString(RoutineIntents.ARG_READY) ?: return null
        return RoutineStrings.readyForYou().shown(decideLangs(null, null)) to { replyId, host -> ready(replyId, key, host); host.finish(replyId) }
    }

    /**
     * Ready cards ([key]: the one you tapped, run at once; [RoutineIntents.ALL_READY]: each, with Do it or Not now). A tap
     * runs it through the approval gate (READY_TAP), so who-to cards and the rest still apply; done ones leave the list.
     */
    private suspend fun ready(replyId: String, key: String, host: Host, l: Langs = host.langs(null, null)) {
        val today = ZonedDateTime.now().toLocalDate()
        val live = ReadyCards.live(store().ready(), today)
        val cards = if (key == RoutineIntents.ALL_READY) live else live.filter { it.key == key }
        if (cards.isEmpty()) {
            reply(replyId, host, RoutineStrings.nothingWaiting(), l)
            return
        }
        host.text(replyId, RoutineStrings.readyCountSentence(cards.size).shown(l))
        for (c in cards) {
            // Locked (the notification's tap needs no unlock): a step that needs the unlock keeps its card for after it.
            val routine = store().get(c.routineId)
            if (routine != null && ReadyCards.keptOnTap(c, RoutineGate.decide(c.step, RunOrigin.READY_TAP, host.locked, routine, VoiceHub.settings, store().settings()))) {
                host.append(replyId, RoutineStrings.unlockForStays("step" to c.step.summary()).shown(l))
                continue
            }
            if (key == RoutineIntents.ALL_READY) {
                val id = id()
                val line = c.phrase().shown(l)
                val yes = host.ask(replyId, Card(id, (c.step as? RoutineStep.Tool)?.action?.tool, line, ActionStatus.PENDING, null,
                    confirm = (if (c.missed != null) RoutineStrings.runIt() else UiStrings.doIt()).button(l), cancel = RoutineStrings.notNow().button(l)))
                host.show(replyId, Card(id, (c.step as? RoutineStep.Tool)?.action?.tool, line, if (yes) ActionStatus.DONE else ActionStatus.DECLINED,
                    if (yes) null else RoutineStrings.stillWaiting().shown(l)))
                if (!yes) continue
            }
            withContext(Dispatchers.IO) { store().editReady { list -> list.filterNot { it.key == c.key } } }
            VoiceLog.add(VoiceEvent.READY_TAPPED, if (c.missed != null) 0 else c.step.code.id)
            val r = store().get(c.routineId) ?: continue
            if (c.missed != null) {
                runAsked(replyId, r, host, RunOrigin.READY_TAP, l)
            } else {
                runAsked(replyId, r.copy(steps = listOf(c.step)), host, RunOrigin.READY_TAP, l)
            }
        }
        RoutineNotices.ready(app, ReadyCards.live(store().ready(), today))
    }

}
