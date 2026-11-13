// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import app.heyrobot.core.MomentReminder
import app.heyrobot.core.MomentReminders
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Paths
import java.io.File
import app.heyrobot.core.OpenTicks
import app.heyrobot.assistant.LauncherApps
import app.heyrobot.assistant.PanelPrivateEvents
import android.content.Intent
import app.heyrobot.core.ShowPage
import app.heyrobot.core.TodayArrange
import app.heyrobot.core.QuickArrange
import app.heyrobot.core.QuickItem
import app.heyrobot.core.QuickItems
import app.heyrobot.core.QuickOrder
import app.heyrobot.core.TodayMove
import app.heyrobot.sys.QuickFacts
import app.heyrobot.core.news.NewsAsk
import app.heyrobot.core.news.NewsMode
import app.heyrobot.core.news.NewsMoment
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.sys.NewsStore
import app.heyrobot.ui.today.torOff
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.TodaySource
import app.heyrobot.core.Choice
import app.heyrobot.core.Confirmations
import app.heyrobot.core.NoteDelete
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.core.AlarmCancel
import app.heyrobot.core.EventText
import app.heyrobot.core.lock.SomeoneElseLock
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
import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.Today
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
import app.heyrobot.core.ShowCalendar
import app.heyrobot.core.RoutineLockedPhone
import app.heyrobot.core.RoutineCommandCode
import app.heyrobot.core.TimerAsk
import app.heyrobot.core.RoutineDraft
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineSettings
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
import app.heyrobot.sys.NotesFiles
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.RobotTimers
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.RoutineIntents
import app.heyrobot.assistant.skills.PanelSkills
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.RoutineSkills
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillGo
import app.heyrobot.sys.SkillFiles
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

        /** fix-1.2.1-b: car mode, a headset or car session, or a panel the wake word opened: Robot opens no app on its own. */
        val handsFree: Boolean get() = false
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
    // Idea 6: several no-model requests in one sentence are the skills' (PanelMulti), never one routine's phrase.
    fun route(q: String, fromContent: Boolean, fresh: Boolean = true): RoutineCommand? = if (PanelSkills.multi(q) != null) null else try {
        OwnTimerPanel.route(app, q)?.let { return RoutineCommand.Timer(it) }
        val s = store()
        RoutineRouter.route(q, ZonedDateTime.now(), s.all().map { it.name }, fromContent = fromContent, alarmWindow = s.settings().alarmWindow)
            ?.takeIf { fresh || it != RoutineCommand.RemindWhat }
            ?.let { c -> ShowCalendar.routed(c) { HomeSettingsStore.current(app).today } }
            ?.let { c -> NewsAsk.routed(c) { HomeSettingsStore.current(app).let { it.today && it.news.on } } }
    } catch (t: Throwable) {
        RLog.w("routine_route", t)
        null
    }

    /** [cmd] as routed from [said], your words this turn (their language is the answer's). */
    suspend fun handle(replyId: String, asked: RoutineCommand, host: Host, said: String = "") {
        // today-fix-b: hands-free, "show my calendar" is said as the day line and opens nothing (core ShowCalendar).
        val cmd = ShowCalendar.handsFree(asked, host.handsFree)
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
            RoutineCommand.Today -> today(replyId, host, langs)
            is RoutineCommand.Show -> show(replyId, cmd.page, host, langs)
            is RoutineCommand.Arrange -> arrange(replyId, cmd, host, langs)
            is RoutineCommand.ArrangeQuick -> arrangeQuick(replyId, cmd.item, cmd.move, host, langs)
            RoutineCommand.News -> news(replyId, host, langs)
            is RoutineCommand.Create -> create(replyId, cmd.draft, host, langs)
            is RoutineCommand.Run -> run(replyId, cmd.name, host, langs)
            RoutineCommand.ListAll -> reply(replyId, host, RoutineCards.listText(store().all().filter { it.id != BriefRoutine.ID || it.enabled }), langs)
            is RoutineCommand.Delete -> delete(replyId, cmd.name, host, langs)
            is RoutineCommand.Remind -> remind(replyId, cmd, host, langs)
            is RoutineCommand.RemindWhen -> remindWhen(replyId, cmd, host, langs)
            is RoutineCommand.RemindOn -> remindOn(replyId, cmd, host, langs)
            RoutineCommand.RemindWhat -> reply(replyId, host, RoutineStrings.remindWhat(), langs)
            is RoutineCommand.CantCancel -> cantCancel(replyId, cmd, host, langs)
            is RoutineCommand.DeleteNote -> deleteNote(replyId, cmd, host, langs)
            is RoutineCommand.NextAlarm -> nextAlarm(replyId, host, langs)
            is RoutineCommand.Timer -> reply(replyId, host, OwnTimerPanel.answer(app, cmd.ask) { host.launch(RoutineFacts.toChosenClock(app, it)) }, langs)
            is RoutineCommand.TimerLeft ->
                reply(replyId, host, TimerAsk.answer(RobotTimers.load(app), System.currentTimeMillis(), java.time.ZoneId.systemDefault()), langs)
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
        // F6: during someone else's lock, "Time and weather" at most, and what would wait for the unlock doesn't.
        val someoneElse = PhoneLockNow.someoneElse(app)
        val rs = SomeoneElseLock.routines(store().settings(), someoneElse)
        val r = routine ?: BriefRoutine.of(rs)
        val locked = host.locked || someoneElse
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
            }, origin, langs) { events -> PanelPrivateEvents.read(replyId, events) }
        }
        session?.let(RoutineVoice::close)
        // The whole of what was said, in the bubble and the saved conversation (the parts above came while it was read).
        val said = spoken.joinToString(" ") { it.shownText }
        if (append) host.append(replyId, said) else host.text(replyId, said)
        // Shown but not spoken: the reminders count as read now.
        if (session == null && spoken.any { it.item == BriefItem.REMINDERS }) withContext(Dispatchers.IO) { BriefSource.remindersSaid(app) }
        if (held.isNotEmpty() && !someoneElse) RoutineHub.waitForUnlock(RoutineRunner.Waiting(r, TriggerKind.ASKED, emptyList(), held, ZonedDateTime.now()))
        VoiceLog.add(VoiceEvent.BRIEF_SPOKEN, spoken.sumOf { it.lines.size }, held.sumOf { it.lines.size }, spoken.sumOf { it.chars })
        return true
    }

    /**
     * Today (H0): "how's my day", the day line (core Today) and nothing else, as written in your calendar and reminders, so
     * quoted and personal. Locked, it is said only at "Everything"; during someone else's lock, never.
     */
    private suspend fun today(replyId: String, host: Host, l: Langs): Boolean {
        val someoneElse = PhoneLockNow.someoneElse(app)
        val rs = SomeoneElseLock.routines(store().settings(), someoneElse)
        if (someoneElse || (host.locked && rs.briefLock != BriefLock.EVERYTHING)) return reply(replyId, host, BriefStrings.unlockToHear(), l)
        host.quoted(replyId)
        // H5: with Today your home page, its parts in its order, its note among them (read only while unlocked).
        val line = withContext(Dispatchers.IO) {
            val home = HomeSettingsStore.current(app).takeIf { it.today }
            val input = TodaySource.input(app).also { PanelPrivateEvents.read(replyId, it.events) }
            Today.line(Today.picture(input), l.say, home?.order, if (home != null && !host.locked) TodaySource.note(app, home) else null)
        }
        host.text(replyId, line.shown(l))
        host.say(line.said(l), personal = true)
        return true
    }

    /**
     * H7b: "read me the news", the headlines Today shows, said as the brief says them (BriefComposer.newsText) and shown;
     * hands-free too. Which feeds you read is personal: the locked phone was refused before this, as the brief holds its
     * news, and during someone else's lock it's never said. With "Only when I ask", asking fetches first (your ask, TAP).
     */
    private suspend fun news(replyId: String, host: Host, l: Langs): Boolean {
        if (PhoneLockNow.someoneElse(app)) return reply(replyId, host, RoutineStrings.unlockNews(), l)
        var instead: Phrase? = null
        val said = withContext(Dispatchers.IO) {
            val home = HomeSettingsStore.current(app)
            // Routed only with Today on and a feed (NewsAsk); Today turned off since, there's nothing to say.
            if (!home.today) return@withContext null
            // fix-today-tor: Tor still starting is said as such, not as old headlines or none.
            var refused: NewsRefusal? = null
            var failedAll = false
            if (home.news.on && home.news.mode == NewsMode.ASKED) when (val r = NewsStore.fetch(app, NewsMoment.TAP, home.news)) {
                is NewsStore.Fetched.Refused -> refused = r.why
                is NewsStore.Fetched.Done -> failedAll = r.failed == home.news.feeds.size
                NewsStore.Fetched.Waited -> Unit
            }
            instead = NewsAsk.instead(refused)
            if (instead != null) return@withContext null
            val ms = System.currentTimeMillis()
            val st = NewsStore.current(app)
            // today-news-off: no headlines, and the reason the page gives (no feeds, Tor off, Tor down, no feed answered).
            BriefComposer.newsText(st.pick(home.news, ms), st.updated(home.news, ms), ZonedDateTime.now(), l.say)
                ?: null.also { instead = NewsAsk.nothing(home.news, torOff(app, home.news), refused, failedAll) }
        } ?: return reply(replyId, host, instead ?: RoutineStrings.newsNothing(), l)
        host.quoted(replyId)
        host.text(replyId, said.shown(l))
        host.say(said.said(l), personal = true)
        return true
    }

    /**
     * H4: the Today page's order (the locked phone was refused before this). No card: it's undoable and touches nothing
     * outside the page, so Robot does it and says so. The page redraws at once (ui/today TodayWatch).
     */
    private fun arrange(replyId: String, cmd: RoutineCommand.Arrange, host: Host, l: Langs): Boolean {
        // HS3: with Quick access as your home, "put my lists first" moves its button (core QuickArrange.onQuick).
        QuickArrange.onQuick(cmd, HomeSettingsStore.current(app).home)?.let { (i, m) -> return arrangeQuick(replyId, i, m, host, l) }
        val ok = HomeSettingsStore.update(app) { it.with(cmd.part, cmd.move) } != null
        reply(replyId, host, if (ok) TodayArrange.reply(cmd.part, cmd.move) else RoutineStrings.arrangeFailed(), l)
        return ok
    }

    /** HS3: Quick access's order; UP and DOWN step past the buttons this phone doesn't show. Undoable, so no card. */
    private fun arrangeQuick(replyId: String, item: QuickItem, move: TodayMove, host: Host, l: Langs): Boolean {
        val shown = QuickItems.shown(QuickItem.DEFAULT, QuickFacts.phone(app)).map { it.item }
        val ok = HomeSettingsStore.update(app) { it.copy(quick = QuickOrder.moved(it.quick, item, move, shown)) } != null
        reply(replyId, host, if (ok) QuickArrange.reply(item, move) else RoutineStrings.quickFailed(), l)
        return ok
    }

    /**
     * H3: the main app at History or the Calendar tab (the locked phone was refused before this). today-fix-d: the panel then
     * leaves, as for any app it opens, so the page is in front without a touch.
     */
    private suspend fun show(replyId: String, page: ShowPage, host: Host, l: Langs): Boolean {
        val i = Intent().setClassName(app.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
            .putExtra(AppPages.EXTRA, when (page) { ShowPage.HISTORY -> AppPages.HISTORY; ShowPage.CALENDAR -> AppPages.CALENDAR; ShowPage.QUICK -> AppPages.QUICK })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reply(replyId, host, when (page) { ShowPage.HISTORY -> RoutineStrings.showHistory(); ShowPage.CALENDAR -> RoutineStrings.showCalendar(); ShowPage.QUICK -> RoutineStrings.showQuick() }, l)
        return host.launch(i).also { if (it) host.leave() }
    }

    // ------------------------------------------------------------------ a routine made by voice

    /**
     * Its card: what starts it, every step and what it does by itself, where it speaks, what goes online. A yes or Save
     * saves it; never by itself. From the screen's text, only the tap.
     */
    private suspend fun create(replyId: String, draft0: RoutineDraft, host: Host, l: Langs): Boolean {
        var draft = draft0
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
        // auto-open-plan §1: an unticked box per app its open steps name; a tap ticks or unticks it, the card stays.
        val ticks = OpenTicks.of(OpenTicks.names(draft.routine),
            withContext(Dispatchers.IO) { LauncherApps.of(app) }, draft.fromContent)
        val tapOnly = if (draft.fromContent) RoutineStrings.tapSaveFromScreen("save" to UiLabel(UiStrings.save)).said(l) else null
        var yes: Boolean
        while (true) {
            val chips = ticks.map { OpenTicks.chip(it, it.pkg in draft.routine.opens).button(l) }
            val pick = host.pick(replyId, Card(id, null, card.title, ActionStatus.PENDING, card.footer, body = body, confirm = UiStrings.save().button(l),
                cancel = UiStrings.cancel().button(l), chips = chips), tapOnly)
            val i = pick - Card.CHIP
            if (ticks.isEmpty() || i !in ticks.indices) { yes = pick == Confirmations.YES; break }
            draft = draft.copy(routine = draft.routine.copy(opens = OpenTicks.toggle(draft.routine.opens, ticks[i])))
        }
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
        val ok = saved != null && withContext(Dispatchers.IO) { RoutineSkills.save(store(), SkillFiles.store(app), saved) }
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
        // F6: during someone else's lock the routine's own words and your reminders wait, as in the brief and the runner.
        val someoneElse = PhoneLockNow.someoneElse(app)
        val rs = SomeoneElseLock.routines(store().settings(), someoneElse)
        val locked = host.locked || someoneElse
        if (origin == RunOrigin.ASKED && !skillGo(replyId, r, host, l)) return
        host.text(replyId, RoutineStrings.runningNow("name" to RoutineTexts.name(r)).shown(l))
        for (step in r.steps) {
            val go = RoutineGate.decide(step, origin, locked, r, s, rs)
            VoiceLog.add(VoiceEvent.ROUTINE_STEP, step.code, go)
            when (go) {
                StepGo.NEVER -> host.append(replyId, RoutineStrings.cantDo("step" to step.summary()).shown(l))
                StepGo.DISABLED -> host.append(replyId, RoutineStrings.offIn("page" to UiStrings.inSettings(UiStrings.actions), "step" to step.summary()).shown(l))
                StepGo.WAIT_UNLOCK -> host.append(replyId, RoutineStrings.unlockFor("step" to step.summary()).shown(l))
                else -> if (step is RoutineStep.Tool) {
                    val e = host.tool(replyId, step) ?: continue
                    if (e.launchedApp && e.status.succeeded) return host.leave()
                    // 1.1: the result's words in the turn's reply language when it has them (core Executed's phrases).
                    val words = e.spoken?.let { en -> e.spokenText?.takeIf { it.en() == en } ?: Phrase.plain(en) }
                        ?: e.text?.takeIf { it.en() == e.detail } ?: Phrase.plain(e.detail)
                    host.say(words.said(l))
                } else own(replyId, r, step, host, origin, l, rs, append = r.steps.size > 1)
            }
        }
    }

    /** A routine's own step (the brief, a sentence, your reminders, the messages count) that its gate let run. */
    private suspend fun own(replyId: String, r: Routine, step: RoutineStep, host: Host, origin: RunOrigin, l: Langs, rs: RoutineSettings, append: Boolean) {
        // The routine's own sentence and your brief reminders go in the reply as written (by you, or by whoever
        // made the backup it came from): content, and personal from then on (core Quoted), as the brief's are.
        if (step is RoutineStep.Say || step == RoutineStep.Reminders) host.quoted(replyId)
        when (step) {
            RoutineStep.Brief -> brief(replyId, host, origin, l, r, append = append)
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
            RoutineStep.Messages, is RoutineStep.Tool -> {}
        }
    }

    /**
     * S3.2: a routine's own step in a skill's run (the brief, your reminders, the messages count): through RoutineGate as an
     * asked routine's step, with the routine the skill starts by itself as (the morning brief's for the built-in brief), and
     * run by the same code. What isn't run says why, as in an asked routine.
     */
    suspend fun skillStep(replyId: String, s: Skill, step: RoutineStep, host: Host, l: Langs): ActionStatus {
        val someoneElse = PhoneLockNow.someoneElse(app)
        val rs = SomeoneElseLock.routines(withContext(Dispatchers.IO) { store().settings() }, someoneElse)
        val r = withContext(Dispatchers.IO) { store().routines() }.firstOrNull { it.skill == s.id } ?: BriefRoutine.of(rs)
        val go = RoutineGate.decide(step, RunOrigin.ASKED, host.locked || someoneElse, r, VoiceHub.settings, rs)
        VoiceLog.add(VoiceEvent.ROUTINE_STEP, step.code, go)
        return when (go) {
            StepGo.NEVER -> { host.append(replyId, RoutineStrings.cantDo("step" to step.summary()).shown(l)); ActionStatus.FAILED }
            StepGo.DISABLED -> { host.append(replyId, RoutineStrings.offIn("page" to UiStrings.inSettings(UiStrings.actions), "step" to step.summary()).shown(l)); ActionStatus.FAILED }
            StepGo.WAIT_UNLOCK -> { host.append(replyId, RoutineStrings.unlockFor("step" to step.summary()).shown(l)); ActionStatus.NEEDS_UNLOCK }
            else -> { own(replyId, r, step, host, RunOrigin.ASKED, l, rs, append = true); ActionStatus.DONE }
        }
    }

    /** S3.2: an asked run of a routine with a skill takes the skill's own rule first (off, Ask first's card). True: go on. */
    private suspend fun skillGo(replyId: String, r: Routine, host: Host, l: Langs): Boolean {
        if (r.skill == null) return true
        val skills = withContext(Dispatchers.IO) { try { SkillFiles.store(app).skills() } catch (_: Exception) { emptyList() } }
        val s = skills.firstOrNull { it.id == r.skill } ?: return true
        // The conversation's messages: known in the panel's turn (PanelSkills.Host); otherwise a tap is the only yes.
        val messages = (host as? PanelSkills.Host)?.messages?.active ?: true
        val links = withContext(Dispatchers.IO) { PanelSkills.links() }
        return when (val go = RoutineSkills.go(r, skills, RunOrigin.ASKED, messages, links)) {
            null, SkillGo.Run -> true
            is SkillGo.Refused -> { reply(replyId, host, go.why, l); false }
            is SkillGo.Card -> {
                val id = id()
                val title = SkillStrings.runTitle("name" to s.name).shown(l)
                val run = UiLabel(UiStrings.run)
                val footer = when {
                    s.provenance.fromContent -> SkillStrings.runTapContent("run" to run)
                    go.tapOnly -> SkillStrings.runTapMessage("run" to run)
                    else -> SkillStrings.runSayYes("run" to run)
                }
                val body = SkillCards.run(s, emptyMap(), links).joinToString("\n") { it.shown(l) }
                host.text(replyId, footer.shown(l))
                host.say(footer.said(l))
                val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, footer.shown(l), body = body, confirm = UiStrings.run().button(l),
                    cancel = UiStrings.cancel().button(l)), tapOnly = if (go.tapOnly) footer.said(l) else null)
                host.show(replyId, Card(id, null, title, if (yes) ActionStatus.DONE else ActionStatus.DECLINED,
                    if (yes) null else SkillStrings.notRun("name" to s.name).shown(l), body = body))
                yes
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
        val ok = withContext(Dispatchers.IO) { RoutineSkills.delete(store(), SkillFiles.store(app), r.id) }
        RoutineHub.reload()
        host.show(replyId, Card(id, null, RoutineStrings.deletedTitle("name" to r.name).shown(l), if (ok) ActionStatus.DONE else ActionStatus.FAILED,
            if (ok) null else RoutineStrings.couldntChange().shown(l)))
        return ok
    }

    /** moment-reminders: kept with a card that says when it goes off, and when it can't yet (no car remembered). */
    private suspend fun remindWhen(replyId: String, cmd: RoutineCommand.RemindWhen, host: Host, l: Langs): Boolean {
        if (host.locked) return !reply(replyId, host, RoutineStrings.unlockMoment(), l)
        val r = MomentReminder(id(), cmd.text, cmd.moment, System.currentTimeMillis(), cmd.between)
        val (ok, memory) = withContext(Dispatchers.IO) {
            (store().editMoments { MomentReminders.add(it, r) } != null) to
                (DeviceMemory.decode(readOrNull(File(Paths.dir(app), "devices.json"))) as? DeviceFile.Ok)?.memory // l10n: not text (a file name)
        }
        if (!ok) {
            val newer = withContext(Dispatchers.IO) { store().momentsNewer() }
            return !reply(replyId, host, if (newer) RoutineStrings.momentsNewer() else RoutineStrings.couldntKeepReminder(), l)
        }
        RoutineHub.reload()
        val said = RoutineStrings.momentSet("moment" to MomentReminders.phrase(r), "text" to r.text)
        val cant = MomentReminders.cantFire(r.moment, memory)
        host.show(replyId, Card(id(), null, said.shown(l), ActionStatus.DONE, cant?.shown(l)))
        host.say((if (cant == null) said else Phrase.join(said, cant)).said(l))
        return true
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

    /** "cancel the timer" goes to [cancelTimer]; "cancel my alarm" to [cancelAlarm] (1.1 only said it couldn't, with a card for the clock). */
    private suspend fun cantCancel(replyId: String, cmd: RoutineCommand.CantCancel, host: Host, l: Langs): Boolean =
        if (cmd.timer) cancelTimer(replyId, host, l) else cancelAlarm(replyId, cmd, host, l)

    /**
     * fix-everyday-c ("cancel my alarm", Pixel 8, 5 Nov 2026: a card that only offered to open the clock app): AlarmClock's
     * ACTION_DISMISS_ALARM turns off an upcoming alarm where your clock takes it (core AlarmCancel: with no time said, a card
     * about the next alarm first, the one Robot sees; a spoken yes answers it). Robot then checks the next alarm moved on;
     * when it didn't, or Robot can't see (another time), your clock's alarms open (unlocked, not hands-free) with one line
     * saying why. Locked, nothing is turned off: one line.
     */
    private suspend fun cancelAlarm(replyId: String, cmd: RoutineCommand.CantCancel, host: Host, l: Langs): Boolean {
        if (host.locked) return !reply(replyId, host, ToolStrings.alarmOffUnlock(), l)
        val alarms = app.getSystemService(android.app.AlarmManager::class.java)
        val zone = java.time.ZoneId.systemDefault()
        val before = alarms?.nextAlarmClock?.triggerTime
        val nextTime = before?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) }
        var card: Pair<String, String>? = null // fix-alarm-moved: the card's id and title, settled once the read-back is in
        suspend fun settle(gone: Boolean) = card?.let { (id, title) -> host.show(replyId, Card(id, ToolName.SET_ALARM, title, AlarmCancel.cardStatus(gone), null)) }
        val (time, next) = when (val p = AlarmCancel.plan(cmd, nextTime)) {
            is AlarmCancel.Plan.Open -> return openAlarms(replyId, host, l, if (p.none) ToolStrings.alarmNoneSeen() else ToolStrings.alarmOneAtATime())
            is AlarmCancel.Plan.Dismiss -> p.time to p.next
            is AlarmCancel.Plan.Ask -> {
                val id = id()
                val title = ToolStrings.alarmTurnOffTitle("time" to EventText.clock(p.time.hour, p.time.minute)).shown(l)
                val yes = host.ask(replyId, Card(id, ToolName.SET_ALARM, title, ActionStatus.PENDING, null, confirm = ToolStrings.turnOff().button(l),
                    cancel = UiStrings.cancel().button(l)))
                // fix-batch-15 L1 (review-batch-15): the next alarm moved while the card waited (it rang, or another was set): ask about the one now next.
                if (yes && alarms?.nextAlarmClock?.triggerTime != before) {
                    // fix-batch-15-lows d: nothing was sent, so the card is declined, not failed.
                    host.show(replyId, Card(id, ToolName.SET_ALARM, title, ActionStatus.DECLINED, null))
                    return cancelAlarm(replyId, cmd, host, l)
                }
                host.show(replyId, Card(id, ToolName.SET_ALARM, title, if (yes) ActionStatus.PENDING else ActionStatus.DECLINED, null))
                if (!yes) return true
                card = id to title
                p.time to true
            }
        }
        val t = EventText.clock(time.hour, time.minute)
        val dismiss = android.content.Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, time.hour).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, time.minute)
        if (!host.launch(RoutineFacts.toChosenClock(app, dismiss))) { settle(false); return !reply(replyId, host, ToolStrings.noClockAlarms(), l) }
        // fix-batch-15 L2: eight reads 250 ms apart, then one last a second later (a slow clock).
        if (next && before != null && AlarmCancel.readBack(before, pause = { kotlinx.coroutines.delay(it) }, read = { alarms?.nextAlarmClock?.triggerTime })) {
            settle(true); return reply(replyId, host, ToolStrings.alarmOff("time" to t), l)
        }
        settle(false)
        return openAlarms(replyId, host, l, if (next) ToolStrings.alarmStillOn("time" to t) else ToolStrings.alarmOffAsked("time" to t), still = time.takeIf { next })
    }

    /** fix-cancel-alarm: "what alarms do I have": the next alarm Android shows Robot, in words, with no card to answer. */
    private fun nextAlarm(replyId: String, host: Host, l: Langs): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val at = app.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        return reply(replyId, host, AlarmCancel.nextLine(at?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }, java.time.ZonedDateTime.now(zone)), l)
    }

    /** fix-note-delete: the notes deleted by voice in this process, for their cards' Undo. */
    private val noteUndos = NoteDelete.Undos()

    /**
     * fix-note-delete ("delete the note about the gate code" only searched, Pixel 8, phone-1.2.3-b): the note found
     * (core NoteDelete), a card naming it (several: the card asks which), deleted on your yes or tap, with Undo
     * ([undoNoteDelete]) that puts it back whole. None: Robot says so.
     */
    private suspend fun deleteNote(replyId: String, cmd: RoutineCommand.DeleteNote, host: Host, l: Langs): Boolean {
        val store = NotesFiles.store(app)
        val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.read() }
        val notes = read.notes
        val note = when (val p = NoteDelete.plan(cmd.query, notes)) {
            NoteDelete.Plan.None -> return !reply(replyId, host, noteDeleteNoneLine(read, cmd.query), l)
            is NoteDelete.Plan.One -> {
                val id = id()
                val title = ToolStrings.noteDeleteTitle("title" to p.note.displayTitle).shown(l)
                // review-1.2.4 finding 1: the note is named aloud before a spoken yes can delete it.
                val ask = ToolStrings.noteDeleteAsk("title" to p.note.displayTitle)
                host.say(ask.said(l))
                val yes = host.ask(replyId, Card(id, ToolName.ADD_NOTE, ask.shown(l), ActionStatus.PENDING, null, confirm = UiStrings.delete().button(l),
                    cancel = UiStrings.cancel().button(l)))
                if (!yes) { host.show(replyId, Card(id, ToolName.ADD_NOTE, title, ActionStatus.DECLINED, null)); return true }
                host.show(replyId, Card(id, ToolName.ADD_NOTE, title, ActionStatus.DONE, null))
                p.note
            }
            is NoteDelete.Plan.Several -> {
                val id = id()
                val which = ToolStrings.noteDeleteWhich().shown(l)
                // A choice's action is never run here: the pick's index says which note.
                val choices = p.notes.map { Choice(it.displayTitle, Action.OpenApp(it.displayTitle)) }
                host.say(ToolStrings.noteDeleteWhich().said(l))
                val pick = host.pick(replyId, Card(id, ToolName.ADD_NOTE, which, ActionStatus.PENDING, null, choices = choices,
                    cancel = UiStrings.cancel().button(l)))
                val chosen = p.notes.getOrNull(if (pick >= Confirmations.REMEMBER) pick - Confirmations.REMEMBER else pick)
                host.show(replyId, Card(id, ToolName.ADD_NOTE, chosen?.let { ToolStrings.noteDeleteTitle("title" to it.displayTitle).shown(l) } ?: which,
                    if (chosen != null) ActionStatus.DONE else ActionStatus.DECLINED, null))
                chosen ?: return true
            }
        }
        val key = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { noteUndos.delete(store, note) }
            ?: return !reply(replyId, host, ToolStrings.noteDeleteFailed(), l)
        val done = ToolStrings.noteDeleted("title" to note.displayTitle)
        host.show(replyId, Card(id(), ToolName.ADD_NOTE, done.shown(l), ActionStatus.DONE, null, undo = key))
        host.say(done.said(l), personal = true)
        return true
    }

    /** A deleted note's Undo ([deleteNote]'s card): the line its card shows after. */
    /** lists-undo-fix: the deleted note's Undo can still be tried (its restore couldn't be written). */
    fun canUndoNote(key: String): Boolean = noteUndos.has(key)

    fun undoNoteDelete(key: String, l: Langs): String =
        (if (noteUndos.restore(NotesFiles.store(app), key)) ToolStrings.noteRestored() else ToolStrings.noteNotRestored()).render(l)

    /** Your clock's alarms, after [why]: opened unlocked and not hands-free (a line says where to look otherwise). */
    private suspend fun openAlarms(replyId: String, host: Host, l: Langs, why: Phrase, still: java.time.LocalTime? = null): Boolean {
        val opened = !host.handsFree && host.launch(RoutineFacts.toChosenClock(app, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)))
        val lines = AlarmCancel.openLines(why, opened, still)
        host.text(replyId, lines.joinToString(" ") { it.shown(l) })
        lines.forEach { host.say(it.said(l)) }
        if (opened) host.leave()
        return opened
    }

    /**
     * fix-timers-1.2.1, "cancel the timer", « arrête la minuterie »: AlarmClock's ACTION_DISMISS_TIMER stops the timers that
     * are ringing (nothing when none is), and Android has nothing public for a running one. So Robot sends the dismiss when
     * your clock takes it, then, unlocked, opens your clock's timers at once, with no card, so a running one is a tap away;
     * locked, it says how. Robot's words say only what it asked for: the clock's answer isn't seen.
     * fix-1.2.1-b: hands-free (car mode, the wake word) no clock page comes up in front of you: the dismiss and one short line.
     * fix-everyday-e: a timer Robot set that still runs (sys RobotTimers) is named first, with its time left; while none of
     * Robot's own may be ringing, no dismiss is sent, so the clock's timers are the one page that opens. The running ones are
     * marked as asked to cancel: whether you did, in the clock, Robot can't see.
     */
    private suspend fun cancelTimer(replyId: String, host: Host, l: Langs): Boolean {
        val now = System.currentTimeMillis()
        val mine = RobotTimers.load(app)
        val known = TimerAsk.running(mine, now).take(3).map { TimerAsk.leftLine(it, now, java.time.ZoneId.systemDefault()) }
        if (known.isNotEmpty()) RobotTimers.cancelAsked(app, now)
        val dismissed = (known.isEmpty() || TimerAsk.mayRing(mine, now)) &&
            host.launch(RoutineFacts.toChosenClock(app, android.content.Intent(android.provider.AlarmClock.ACTION_DISMISS_TIMER)))
        if (dismissed) TodaySource.timersStopped(app)
        if (host.handsFree) {
            val line = if (dismissed) ToolStrings.timerHandsFree() else if (known.isNotEmpty()) ToolStrings.timerRunningClock() else ToolStrings.cantCancelTimers()
            host.text(replyId, (known + line).joinToString(" ") { it.shown(l) })
            (known + line).forEach { host.say(it.said(l)) }
            return dismissed
        }
        val opened = !host.locked && host.launch(RoutineFacts.toChosenClock(app, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)))
        val lines = known + listOfNotNull(
            ToolStrings.timerDismissAsked().takeIf { dismissed },
            when {
                opened -> ToolStrings.timerRunningOpened()
                host.locked -> if (dismissed || known.isNotEmpty()) ToolStrings.timerRunningUnlock() else ToolStrings.cantCancelTimers()
                dismissed -> ToolStrings.timerRunningClock()
                else -> ToolStrings.noClockTimers()
            },
        )
        host.text(replyId, lines.joinToString(" ") { it.shown(l) })
        lines.forEach { host.say(it.said(l)) }
        if (opened) host.leave()
        return dismissed || opened
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
        // F6: as runAsked below decides, or a step it won't run would use up its card.
        val someoneElse = PhoneLockNow.someoneElse(app)
        val rs = SomeoneElseLock.routines(store().settings(), someoneElse)
        for (c in cards) {
            // Locked (the notification's tap needs no unlock): a step that needs the unlock keeps its card for after it.
            val routine = store().get(c.routineId)
            if (routine != null && ReadyCards.keptOnTap(c, RoutineGate.decide(c.step, RunOrigin.READY_TAP, host.locked || someoneElse, routine, VoiceHub.settings, rs))) {
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

/** fix-seal-lows-c 4, fix-seal-lows-d 1: delete a note, none found. Some locked: they open once unlocked, as findNotes says;
 *  only a newer Robot's: update Robot or Delete these in Notes; never "no notes" while any is held. */
internal fun noteDeleteNoneLine(read: app.heyrobot.sys.NotesRead, query: String): Phrase = when {
    read.notNow > 0 -> ToolStrings.notesLocked()
    read.notes.isEmpty() && read.tooNew > 0 -> ToolStrings.notesTooNew()
    read.notes.isEmpty() || query == "last" -> ToolStrings.noteDeleteNoNotes()
    else -> ToolStrings.noteDeleteNone("what" to query)
}
