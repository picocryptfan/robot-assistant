// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.StepPhrases.Period
import app.heyrobot.core.StepPhrases.Words
import app.heyrobot.core.StepPhrases.actions
import app.heyrobot.core.StepPhrases.clock
import app.heyrobot.core.StepPhrases.clockPeriod
import app.heyrobot.core.StepPhrases.resolve
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

// Pure. Routines by voice, English and Québec French, decided by code before anything
// reaches Gemma, anchored at the start of what you said (only "hey robot", "please" and the like in front), like
// HelperRouter. "Every weekday at 7, tell me the weather and my calendar" becomes a draft routine shown on its card; it is
// never saved without your yes (or a tap, when it came from content). Steps map through the same words as the tools, "pis"
// counts as "et", and a part not understood is never guessed: the card names it and points to Routines. It must not
// misfire on ordinary talk ("every time I call Bob he…", "when I was at work", "chaque fois que…") or on a reminder with a
// time and no day, which stays the model's. 1.1: a reminder with a day ("remind me to call the bank on the 20th", with or
// without a time) is a dated reminder, an event with an alert on add_event's card (RoutineCommand.RemindOn).

sealed class RoutineCommand {
    /** "morning brief", "what's my day", "mon résumé du matin", "bon matin" (as a whole utterance). */
    data object BriefNow : RoutineCommand()
    /** "How's my day", « comment est ma journée »: Today's day line (core Today), said and shown. */
    data object Today : RoutineCommand()
    /** H3: "show my history", "show my calendar" (and French): the main app at that page, on an unlocked phone. */
    data class Show(val page: ShowPage) : RoutineCommand()
    /** H4: "put my calendar first" and its kin (TodayArrange): the Today page's order, on an unlocked phone. */
    data class Arrange(val part: TodayPart, val move: TodayMove) : RoutineCommand()
    /** H7b: "read me the news" (as a whole utterance): Today's headlines, said and shown, on an unlocked phone. */
    data object News : RoutineCommand()

    /** A routine to show on its card ([RoutineDraft.card]); saved only by your answer. */
    data class Create(val draft: RoutineDraft) : RoutineCommand()

    /** "run my evening routine": [name] as saved when it matched one of yours, else as said ("" for "run my routine"). */
    data class Run(val name: String) : RoutineCommand()

    /** "list my routines", "quelles sont mes routines". */
    data object ListAll : RoutineCommand()

    /** "delete my evening routine": deleting always needs a tap on its card. */
    data class Delete(val name: String) : RoutineCommand()

    /** "remind me in the morning to …", "add to my brief: …": a brief reminder ([tomorrow]: "tomorrow morning" was said). */
    data class Remind(val text: String, val tomorrow: Boolean) : RoutineCommand()

    /**
     * 1.1, a dated reminder: "remind me to call the bank on the twentieth of November", « rappelle-moi d'appeler le dentiste
     * le vingt novembre », with or without a time. It becomes an event with an alert, on add_event's card ([title], and
     * [whenText] as you said it).
     */
    data class RemindOn(val title: String, val whenText: String) : RoutineCommand()

    /**
     * 1.1 (phone, 22 Oct): "remind me", "remind me about it", « rappelle-moi ça »: nothing to remind and no time. In a new
     * conversation Robot asks what and when (PanelRoutines), rather than the model saving a note of your words.
     */
    data object RemindWhat : RoutineCommand()

    /**
     * 1.1 (phone, 22 Oct: "Cancel my 6:30 alarm" became a card to set one for 23:30): "cancel my alarm", « annule ma
     * minuterie ». Android lets an app set alarms and timers, not cancel them: Robot says so and offers your clock app, opened
     * only by a tap ([timer]: a timer was named; [french]: said in French).
     */
    /** fix-everyday-c: an alarm's [hour] (0 to 23) and [minute] when you said its time; [exact], "am", "pm" or 13 to 23 said; [all], "all my alarms". */
    data class CantCancel(val timer: Boolean, val french: Boolean, val hour: Int? = null, val minute: Int? = null, val exact: Boolean = false,
        val all: Boolean = false) : RoutineCommand() {
        /** As before fix-everyday-c, so the routing corpora (EverydayCorpusTest, RoutingCorpusTest) stay as they are. */
        override fun toString(): String = "CantCancel(timer=$timer, french=$french)" // l10n: not text (the routing corpora's key)
    }

    /**
     * fix-everyday-e: "how much time is left on my timer", « il reste combien de temps à ma minuterie »: answered from the
     * timers Robot set (core TimerAsk), never the model's to guess ([french]: a French timer word was said).
     */
    data class TimerLeft(val french: Boolean) : RoutineCommand()

    /**
     * fix-cancel-alarm (phone-1.2.4: "what alarms do I have" was the model's, an "Open Clock" card that then held the next
     * request back): "what alarms do I have", « c'est quand ma prochaine alarme »: the next alarm, the only one Android
     * shows Robot, in words (core AlarmCancel.nextLine), no card ([french]: a French alarm word was said).
     */
    data class NextAlarm(val french: Boolean) : RoutineCommand()

    /**
     * fix-note-delete: "delete the note about the gate code", "delete my last note", « supprime la note sur le code »:
     * [query], the words that say which note for NoteSearch ("last" for the newest); [french]: said in French.
     */
    data class DeleteNote(val query: String, val french: Boolean) : RoutineCommand()
}

/**
 * A routine made by voice, waiting on its card. [unparsed]: your words not understood; [refused]: steps routines never
 * do; [notes]: what was read in a way you may want to change ("7" as 7:00). [fromContent]: the request came from content
 * (screen text, a photo, something Robot read): then only a tap saves it.
 */
data class RoutineDraft(
    val routine: Routine,
    val lang: Lang,
    val unparsed: List<String> = emptyList(),
    val refused: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val fromContent: Boolean = false,
    /** 1.1: [notes] without a language yet (the card shows them in the turn's reply language). */
    val noteTexts: List<Phrase> = notes.map(Phrase::plain),
) {
    val problems: List<String> get() = RoutineCheck.problems(routine)
    val canSave: Boolean get() = problems.isEmpty()

    /** A spoken "yes" may save it: it can be saved and it didn't come from content. */
    val yesSaves: Boolean get() = canSave && !fromContent

    /** The routine as saved at [nowMs]: the online services its card listed become its consent. Null if it can't be saved. */
    fun save(nowMs: Long): Routine? = if (canSave) routine.copy(online = routine.needsOnline, created = nowMs, edited = nowMs) else null

    fun card(): RoutineCard = RoutineCards.card(this)

    fun cardText(): RoutineCardText = RoutineCards.cardText(this)
}

/** The routine card: what starts it, every step with its class, where it speaks, what goes online. */
data class RoutineCard(val title: String, val lines: List<String>, val footer: String)

/** 1.1: [RoutineCard] in words without a language yet: by voice in the turn's reply language, in Settings in the menus'. */
data class RoutineCardText(val title: Phrase, val lines: List<Phrase>, val footer: Phrase) {
    /** In [l] (English: [RoutineCards.card]'s words). */
    fun render(l: Langs): RoutineCard = RoutineCard(title.render(l), lines.map { it.render(l) }, footer.render(l))
}

object RoutineCards {
    private val DAYS = listOf(RoutineStrings.monday, RoutineStrings.tuesday, RoutineStrings.wednesday, RoutineStrings.thursday, RoutineStrings.friday,
        RoutineStrings.saturday, RoutineStrings.sunday)

    /** "07:00", "17:30" (a 24-hour clock) / « 7 h », « 17 h 30 » (core EventText's). */
    private fun hm(t: LocalTime): Phrase = EventText.clock(t.hour, t.minute)

    /** "every day", "every weekday", "on weekends", "every Monday and Friday". */
    fun days(d: Set<DayOfWeek>): String = daysText(d).en()

    /** [days] without a language yet (« tous les jours », « en semaine », « chaque lundi et vendredi »). */
    fun daysText(d: Set<DayOfWeek>): Phrase = when (d) {
        DayOfWeek.entries.toSet() -> RoutineStrings.everyDay()
        RoutineWords.WEEKDAYS -> RoutineStrings.everyWeekday()
        RoutineWords.WEEKEND -> RoutineStrings.onWeekends()
        else -> RoutineStrings.everyDays("days" to Phrase.ListOf(d.sortedBy { it.value }.map { dayName(it) }))
    }

    /** "Monday" / « lundi ». */
    fun dayName(d: DayOfWeek): Phrase = DAYS[d.value - 1]()

    /** "every weekday at 07:00", "when you turn off an alarm between 04:00 and 12:00", "when your car connects". */
    fun trigger(t: RoutineTrigger): String = triggerText(t).en()

    /** [trigger] without a language yet. */
    fun triggerText(t: RoutineTrigger): Phrase = when (t) {
        is RoutineTrigger.At -> t.once?.let { RoutineStrings.onDateAt("date" to EventText.dayText(it, it.year), "time" to hm(t.time)) }
            ?: RoutineStrings.daysAt("days" to daysText(t.days), "time" to hm(t.time))
        is RoutineTrigger.AlarmDismissed -> RoutineStrings.alarmBetween("from" to hm(t.window.from), "to" to hm(t.window.to))
        is RoutineTrigger.Unlock -> RoutineStrings.firstUnlock("time" to hm(t.after))
        is RoutineTrigger.Device -> when {
            t.type == DeviceKind.HEADSET && t.connect -> RoutineStrings.headphonesConnect()
            t.type == DeviceKind.HEADSET -> RoutineStrings.headphonesDisconnect()
            t.connect -> RoutineStrings.carConnects()
            else -> RoutineStrings.carDisconnects()
        }
        is RoutineTrigger.Charging -> if (t.start) RoutineStrings.chargingStarts() else RoutineStrings.chargingStops()
        RoutineTrigger.Asked -> RoutineStrings.onlyAsked()
    }

    /** The trigger with the routine's conditions ("when charging starts, between 18:00 and 04:00"). */
    fun whenText(r: Routine): String = whenPhrase(r).en()

    /** [whenText] without a language yet. */
    fun whenPhrase(r: Routine): Phrase {
        val c = r.conditions
        if (r.trigger is RoutineTrigger.At || r.trigger == RoutineTrigger.Asked) return triggerText(r.trigger)
        return Phrase.Join(listOfNotNull(triggerText(r.trigger), c.between?.let { RoutineStrings.between("from" to hm(it.from), "to" to hm(it.to)) },
            c.days?.let { daysText(it) }, if (c.oncePerDay) RoutineStrings.oncePerDay() else null), ", ")
    }

    /** "runs by itself", "waits for you", and "goes online: Open-Meteo (the weather)" for the weather. */
    fun badgeText(s: RoutineStep): Phrase {
        val badge = RoutineGate.classOf(s).badgeText
        return s.online?.let { RoutineStrings.badgeOnline("badge" to badge, "service" to it.text) } ?: badge
    }

    /** The card in English (as 1.0). */
    fun card(d: RoutineDraft): RoutineCard = cardText(d).render(Langs.EN)

    /** [card] without a language yet. */
    fun cardText(d: RoutineDraft): RoutineCardText {
        val r = d.routine
        val page = UiLabel(UiStrings.routines)
        val lines = buildList {
            add(RoutineStrings.cardWhen("when" to whenPhrase(r)))
            r.steps.forEachIndexed { i, s -> add(RoutineStrings.cardStep("i" to (i + 1).toString(), "step" to s.summary(), "badge" to badgeText(s))) }
            add(RoutineStrings.cardSpeaks("output" to r.output.text))
            val online = r.needsOnline
            add(if (online.isEmpty()) RoutineStrings.nothingOnline() else RoutineStrings.onlineByItself("services" to Phrase.Join(online.map { it.text }, ", ")))
            for (u in d.unparsed) add(RoutineStrings.notUnderstood("words" to u, "page" to page))
            for (x in d.refused) add(RoutineStrings.leftOut("words" to x))
            addAll(d.noteTexts)
            addAll(RoutineCheck.problemTexts(r))
        }
        val footer = when {
            !d.canSave -> RoutineStrings.editToSave("page" to page)
            d.fromContent -> RoutineStrings.tapSaveFromScreen("save" to UiLabel(UiStrings.save))
            else -> RoutineStrings.sayYesOrTapSave("save" to UiLabel(UiStrings.save))
        }
        return RoutineCardText(RoutineStrings.saveTitle("name" to r.name), lines, footer)
    }

    /** What Robot says for "list my routines". */
    fun list(routines: List<Routine>): String = listText(routines).en()

    /** [list] without a language yet (the built-in brief by its name in that language). */
    fun listText(routines: List<Routine>): Phrase {
        if (routines.isEmpty()) return RoutineStrings.listNone()
        val items = routines.map { r ->
            val name = RoutineTexts.name(r.id, r.name)
            if (r.enabled) RoutineStrings.listItem("name" to name, "when" to whenPhrase(r)) else RoutineStrings.listItemOff("name" to name, "when" to whenPhrase(r))
        }
        return RoutineStrings.list(routines.size, "list" to RoutineTexts.semicolons(items))
    }
}

/** Day sets the router and the card share. */
object RoutineWords {
    val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val ALL: Set<DayOfWeek> = DayOfWeek.entries.toSet()
}

object RoutineRouter {
    // ------------------------------------------------------------------ words

    private val FILLER = listOf("hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "he robot", "dis robot", "robot", "please",
        "can you", "could you", "would you", "will you", "i want to", "i want", "i would like to", "id like to", "i d like to",
        "peux tu", "pourrais tu", "est ce que tu peux", "s il te plait", "s il vous plait", "ok", "okay", "alors", "um", "uh", "euh", "hey", ",",
        "make a routine", "create a routine", "new routine", "set up a routine", "make me a routine", "cree une routine", "fais une routine",
        "nouvelle routine", "je veux", "je voudrais", "j aimerais")
    /** Allowed after a whole-utterance command. */
    private val TAIL = listOf("please", "now", "robot", "s il te plait", "s il vous plait", "maintenant", "today", ",")

    private val DAYS_EN = mapOf("monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY)
    private val DAYS_FR = mapOf("lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY, "dimanche" to DayOfWeek.SUNDAY)

    private val BRIEF_NOW = listOf("morning brief", "my morning brief", "the morning brief", "brief me", "give me my morning brief", "give me my brief",
        "give me the morning brief", "read my morning brief", "read me my morning brief", "read me my brief", "read my brief", "my brief",
        "whats my day", "what is my day", "whats my day like", "what does my day look like", "whats on today", "what is on today", "whats on for today",
        "mon resume du matin", "mon resume", "le resume du matin", "resume du matin", "donne moi mon resume", "donne moi mon resume du matin",
        "lis moi mon resume", "lis moi mon resume du matin", "c est quoi ma journee", "quest ce que j ai aujourdhui", "qu est ce que j ai aujourdhui",
        "qu est ce que j ai de prevu aujourdhui", "c est quoi mon programme aujourdhui", "bon matin")
    // Today (home-design H0): the day line, not the brief. Before BRIEF_NOW, which keeps "what's my day" and its kin.
    /** H7b: the news, as a whole utterance only ("read me the news about …" is a question, not this). */
    private val NEWS_NOW = listOf("read me the news", "read the news", "read me my news", "read my news", "read me the headlines",
        "read the headlines", "whats in the news", "what is in the news", "whats the news", "what is the news", "tell me the news",
        "the news", "my news", "the headlines", "lis moi les nouvelles", "lis les nouvelles", "lis moi mes nouvelles", "lis moi les manchettes",
        "c est quoi les nouvelles", "quelles sont les nouvelles", "quoi de neuf dans les nouvelles", "les nouvelles", "mes nouvelles")

    private val DAY_NOW = listOf("hows my day", "how is my day", "hows my day looking", "how is my day looking", "how does my day look",
        "how does my day look today", "hows my day going", "how is my day going", "hows today", "how is today", "hows today looking",
        "how is today looking", "whats left today", "what is left today", "whats left for today", "whats next today", "what is next today",
        "comment est ma journee", "comment va ma journee", "comment se presente ma journee", "comment s annonce ma journee",
        "c est quoi le reste de ma journee", "qu est ce qu il me reste aujourdhui", "quest ce qu il me reste aujourdhui", "il me reste quoi aujourdhui",
        "quoi d autre aujourdhui", "ma journee")
    // H3: the pages behind the clock icon and the Calendar tab, as a whole utterance.
    private val SHOW_HISTORY = listOf("show my history", "show me my history", "open my history", "show history", "open history",
        "show my conversations", "show me my conversations", "show my past conversations", "open my conversations",
        "montre moi mon historique", "montre mon historique", "ouvre mon historique", "affiche mon historique", "ouvre l historique",
        "montre moi mes conversations", "ouvre mes conversations", "affiche mes conversations")
    private val SHOW_CALENDAR = listOf("show my calendar", "show me my calendar", "open my calendar", "show calendar", "open calendar",
        "show my agenda", "open my agenda", "montre moi mon agenda", "montre mon agenda", "ouvre mon agenda", "affiche mon agenda",
        "montre moi mon calendrier", "ouvre mon calendrier", "affiche mon calendrier")
    private val LIST = listOf("list my routines", "list routines", "list the routines", "what are my routines", "what routines do i have",
        "which routines do i have", "show my routines", "show me my routines", "my routines", "tell me my routines", "liste mes routines",
        "quelles sont mes routines", "c est quoi mes routines", "montre moi mes routines", "mes routines", "quelles routines j ai")
    private val RUN = listOf("run", "start", "do", "launch", "play", "begin", "trigger", "lance", "demarre", "fais", "execute", "part", "roule")
    private val DELETE = listOf("delete", "remove", "erase", "supprime", "efface", "enleve", "retire")
    private val NAME_DROP = setOf("my", "the", "a", "ma", "la", "le", "l", "mon", "du", "de", "d", "routine", "routines")

    // ------------------------------------------------------------------ entry

    /**
     * The routine command at the start of [text], or null (ordinary talk, left to the rest of Robot). [names]: your
     * routines' names, so "start leaving work" runs one while "start Signal" stays an app. [id] and [now]: a draft's id and
     * time. [fromContent]: the words came from content, so a draft needs a tap.
     */
    fun route(text: String, now: ZonedDateTime, names: List<String> = emptyList(), id: String = UUID.randomUUID().toString(),
        fromContent: Boolean = false, alarmWindow: TimeWindow = TimeWindow.ALARM): RoutineCommand? {
        val p = Words(text)
        var i = 0
        while (true) {
            val f = p.at(i, FILLER)
            if (f == 0) break
            i += f
        }
        if (i >= p.size) return null
        create(p, i, now, names, id, fromContent, alarmWindow)?.let { return RoutineCommand.Create(it) }
        remind(p, i)?.let { return it }
        remindOn(p, i, now)?.let { return it }
        if (remindWhat(p, i)) return RoutineCommand.RemindWhat
        if (whole(p, i, DAY_NOW)) return RoutineCommand.Today
        if (whole(p, i, BRIEF_NOW)) return RoutineCommand.BriefNow
        if (whole(p, i, NEWS_NOW)) return RoutineCommand.News
        if (whole(p, i, LIST)) return RoutineCommand.ListAll
        if (whole(p, i, SHOW_HISTORY)) return RoutineCommand.Show(ShowPage.HISTORY)
        if (whole(p, i, SHOW_CALENDAR)) return RoutineCommand.Show(ShowPage.CALENDAR)
        TodayArrange.PHRASES.firstOrNull { whole(p, i, listOf(it.first)) }?.let { (_, a) -> return RoutineCommand.Arrange(a.first, a.second) }
        deleteNote(p, i)?.let { return it }
        named(p, i, DELETE, names)?.let { return RoutineCommand.Delete(it) }
        named(p, i, RUN, names)?.let { return RoutineCommand.Run(it) }
        timerLeft(p, i)?.let { return it }
        nextAlarm(p, i)?.let { return it }
        cantCancel(p, i)?.let { return it }
        return null
    }

    /** The French phrases a command other than a new routine starts with (a new routine's draft has its own language). */
    private val FRENCH_LEAD = listOf("liste mes routines", "quelles sont mes routines", "c est quoi mes routines", "montre moi mes routines",
        "mes routines", "quelles routines j ai", "supprime", "efface", "enleve", "retire", "lance", "demarre", "fais", "execute", "part", "roule",
        "rappelle moi", "ajoute", "mets", "fais moi penser", "comment est ma journee", "comment va ma journee", "comment se presente ma journee",
        "comment s annonce ma journee", "c est quoi le reste de ma journee", "qu est ce qu il me reste", "quest ce qu il me reste", "il me reste quoi",
        "quoi d autre aujourdhui", "ma journee", "montre moi mon", "montre mon", "ouvre mon", "affiche mon", "ouvre l historique",
        "montre moi mes conversations", "ouvre mes conversations", "affiche mes conversations", "lis moi les nouvelles", "lis les nouvelles",
        "lis moi mes nouvelles", "lis moi les manchettes", "c est quoi les nouvelles", "quelles sont les nouvelles", "quoi de neuf dans les nouvelles",
        "les nouvelles", "mes nouvelles") + TodayArrange.FRENCH_LEAD

    /**
     * 1.0: [cmd], routed from [text], was said in French, so Robot answers it in French (the locked phone's "Unlock first"
     * lines): a new routine by its draft's language; « liste mes routines », « supprime … », « rappelle-moi … », « ajoute à
     * mon résumé … » by the words they start with; a brief reminder also when its own words read as French.
     */
    /**
     * 1.1: the language [cmd] was surely said in, for the reply language (core ReplyLang's router match): French when
     * [french] says so, English for a new routine read in English, else null (not sure: LangId decides).
     */
    fun lang(text: String, cmd: RoutineCommand): Lang? = when {
        french(text, cmd) -> Lang.FR
        cmd is RoutineCommand.Create -> Lang.EN
        // 1.1: "remind me …", "set a reminder …" (the French leads are French's).
        cmd is RoutineCommand.CantCancel -> if (cmd.french) Lang.FR else Lang.EN
        cmd is RoutineCommand.TimerLeft -> if (cmd.french) Lang.FR else Lang.EN
        cmd is RoutineCommand.NextAlarm -> if (cmd.french) Lang.FR else Lang.EN
        cmd is RoutineCommand.DeleteNote -> if (cmd.french) Lang.FR else Lang.EN
        cmd is RoutineCommand.RemindOn || cmd is RoutineCommand.RemindWhat -> Lang.EN
        else -> null
    }

    fun french(text: String, cmd: RoutineCommand): Boolean {
        if (cmd is RoutineCommand.Create) return cmd.draft.lang == Lang.FR
        val p = Words(text)
        var i = 0
        while (true) {
            val f = p.at(i, FILLER)
            if (f == 0) break
            i += f
        }
        return p.at(i, FRENCH_LEAD) > 0 || (cmd is RoutineCommand.Remind && SpeechLang.french(cmd.text))
    }

    /** The routine of [routines] that [said] names ("evening" is "Evening routine", "soir" is "Routine du soir"), or null. */
    fun <T> find(said: String, routines: List<T>, name: (T) -> String): T? {
        val k = nameKey(said)
        if (k.isEmpty()) return null
        return routines.firstOrNull { nameKey(name(it)) == k }
    }

    private fun nameKey(s: String): String = Words(s).t.map { it.w }.filter { it != "," && it !in NAME_DROP }.joinToString(" ")

    private fun whole(p: Words, i: Int, phrases: List<String>): Boolean {
        val n = p.at(i, phrases)
        if (n == 0) return false
        var j = i + n
        while (j < p.size) { val f = p.at(j, TAIL); if (f == 0) return false; j += f }
        return true
    }

    // ------------------------------------------------------------------ run, delete

    private fun named(p: Words, i: Int, verbs: List<String>, names: List<String>): String? {
        val v = p.at(i, verbs)
        if (v == 0) return null
        var j = i + v
        while (p.w(j) in setOf("my", "the", "ma", "la", "mon", ",")) j++
        var end = p.size
        while (true) {
            val k = (j until end).firstOrNull { p.at(it, TAIL) == end - it } ?: break
            end = k
        }
        if (j >= end) return null
        val words = (j until end).map { p.w(it) }
        val saidRoutine = "routine" in words || "routines" in words
        // "my routine", "ma routine": which one is for the panel to ask, unless there is only one.
        if (words.all { it in NAME_DROP }) return if (saidRoutine) names.singleOrNull() ?: "" else null
        // "my evening routine" / "the routine Leaving work" / "ma routine du soir": the words around "routine" are the name.
        var a = j
        var b = end
        if (p.w(b - 1) == "routine") b--
        if (p.w(a) == "routine") { a++; while (p.w(a) in setOf("du", "de", "d", "la", "l")) a++ }
        val said = p.span(a, b).trim().trim('«', '»', '"', '“', '”').trim()
        if (said.isEmpty()) return null
        val known = find(said, names) { it }
        return when {
            known != null -> known
            saidRoutine -> said
            else -> null
        }
    }

    // ------------------------------------------------------------------ brief reminders

    private val REMIND_LEAD = listOf("remind me", "rappelle moi")
    private val MORNING = mapOf("in the morning" to false, "tomorrow morning" to true, "first thing tomorrow" to true,
        "first thing in the morning" to false, "demain matin" to true, "le matin" to false, "au matin" to false, "demain au matin" to true,
        // "remind me in my brief to …", "rappelle-moi dans mon résumé de …": the next morning's brief.
        "in my brief" to false, "in my morning brief" to false, "in the brief" to false, "in the morning brief" to false,
        "dans mon resume" to false, "dans mon resume du matin" to false)
    private val ADD_BRIEF = listOf("add to my brief", "add to my morning brief", "add to the brief", "put in my brief", "ajoute a mon resume",
        "ajoute a mon resume du matin", "mets dans mon resume")
    private val TO_MY_BRIEF = listOf("to my brief", "to my morning brief", "in my brief", "a mon resume", "a mon resume du matin", "dans mon resume")
    /** A clock time in a reminder's words: then it's an alarm or an event, as today. */
    private val HAS_TIME = Regex("""\d{1,2}\s*(?::\d{2}|h\d{0,2}|\s?[ap]\.?m\b)|\bat \d{1,2}\b|\bà \d{1,2}\b|\bnoon\b|\bmidi\b""", RegexOption.IGNORE_CASE)

    /** The first word from [i] that isn't a comma. */
    private fun pastCommas(p: Words, i: Int): Int {
        var k = i
        while (p.w(k) == ",") k++
        return k
    }

    /**
     * MORNING's longest phrase at [i], with the recognizer's stray comma inside it not counting (1 Nov, the spoken run:
     * « Rappelle-moi demain, matin d'apporter … »): how many tokens it spans, and whether it's tomorrow.
     */
    private fun morningAt(p: Words, i: Int): Pair<Int, Boolean>? {
        val said = mutableListOf<String>()
        var best: Pair<Int, Boolean>? = null
        var k = i
        while (k < p.size && said.size < MORNING_MAX) {
            val w = p.w(k++)!!
            if (w == ",") continue
            said += w
            MORNING[said.joinToString(" ")]?.let { best = (k - i) to it }
        }
        return best
    }
    private const val MORNING_MAX = 5

    private fun remind(p: Words, i: Int): RoutineCommand? {
        val lead = p.at(i, REMIND_LEAD)
        if (lead > 0) {
            var j = pastCommas(p, i + lead)
            // "remind me in the morning to …", "rappelle-moi demain matin d'apporter …".
            val m = morningAt(p, j)
            if (m != null) {
                val tomorrow = m.second
                j = pastCommas(p, j + m.first)
                val to = p.at(j, "to", "de", "d")
                if (to == 0) return null
                return reminder(p.span(j + to, p.size), tomorrow)
            }
            // "remind me to … in the morning".
            val to = p.at(j, "to", "de", "d")
            if (to == 0) return null
            for (k in p.size - 1 downTo j + to + 1) {
                val n = p.at(k, MORNING.keys)
                if (n > 0 && k + n == p.size) return reminder(p.span(j + to, if (p.w(k - 1) == ",") k - 1 else k), MORNING.getValue((k until k + n).joinToString(" ") { p.w(it)!! }))
            }
            return null
        }
        // "add to my brief: …", "ajoute à mon résumé : …".
        val add = p.at(i, ADD_BRIEF)
        if (add > 0) return reminder(p.span(i + add, p.size), false)
        // "add bring the passport to my brief".
        if (p.at(i, "add", "ajoute", "put", "mets") > 0) {
            for (k in p.size - 1 downTo i + 2) if (p.at(k, TO_MY_BRIEF) > 0 && k + p.at(k, TO_MY_BRIEF) == p.size) return reminder(p.span(i + 1, k), false)
        }
        return null
    }

    // ------------------------------------------------------------------ dated reminders (1.1)

    private val DATED_LEAD = listOf("remind me", "rappelle moi", "set a reminder", "set me a reminder", "add a reminder", "make a reminder",
        "fais moi penser", "mets un rappel", "ajoute un rappel")
    /** Between the lead and the thing: "to", "that", « de », « d' », « que », « à ». */
    private val DATED_TO = listOf("to", "that", "about", "de", "d", "que", "qu", "a", "pour")

    /**
     * "Remind me to … on <date> [at <time>]" or "remind me on <date> to …", in English and French: what to remember and
     * the words that say when, which must name a day (today, a weekday, a date, "in 3 days") and either a clock time or
     * none ("tonight" alone or "Friday evening" is a time you didn't say: left to the model). Brief reminders came first.
     */
    private fun remindOn(p: Words, i: Int, now: ZonedDateTime): RoutineCommand.RemindOn? {
        val lead = p.at(i, DATED_LEAD)
        if (lead == 0) return null
        val j = i + lead
        if (j >= p.size) return null
        val lang = if (p.at(i, "rappelle moi", "fais moi penser", "mets un rappel", "ajoute un rappel") > 0) Lang.FR else Lang.EN
        fun dated(a: Int, b: Int): Boolean {
            val m = WhenParser.reads(p.span(a, b), now, lang) ?: return false
            return m.dated && !m.relative && (m.span.allDay || m.clock)
        }
        fun made(title: String, `when`: String): RoutineCommand.RemindOn? {
            val t = title.trim().trimStart(',', ':', ' ').trimEnd(',', '.', '!', '?', ' ').trim()
            if (t.isEmpty() || t.equals("it", true) || t.equals("ça", true) || t.equals("ca", true)) return null
            return RoutineCommand.RemindOn(t.replaceFirstChar { it.uppercase() }, `when`.trim().trimEnd('.', '!', '?', ' '))
        }
        val to = p.at(j, DATED_TO)
        if (to > 0) {
            // "… to call the bank on the 20th": the longest end that reads as a day, after at least one word.
            for (k in j + to + 1 until p.size) if (dated(k, p.size)) return made(p.span(j + to, k), p.span(k, p.size))
            return null
        }
        // "… on the 20th to call the bank": the longest start that reads as a day, then "to" and the thing.
        for (k in p.size - 2 downTo j + 1) {
            val n = p.at(k, DATED_TO)
            if (n > 0 && k + n < p.size && dated(j, k)) return made(p.span(k + n, p.size), p.span(j, k))
        }
        return null
    }

    // ------------------------------------------------------------------ alarms and timers Robot can't cancel (1.1)

    private val CANCEL_EN = listOf("cancel", "delete", "remove", "turn off", "switch off", "shut off", "disable", "stop", "dismiss", "clear",
        "get rid of", "unset", "silence", "mute", "end", "kill")
    private val CANCEL_FR = listOf("annule", "annuler", "supprime", "supprimer", "efface", "effacer", "enleve", "enlever", "retire", "retirer",
        "desactive", "desactiver", "arrete", "arreter", "eteins", "eteindre", "ferme", "fermer", "stoppe", "coupe", "couper")
    private val ALARM_NOUNS = setOf("alarm", "alarms", "alarme", "alarmes", "reveil", "reveils", "cadran", "cadrans")
    private val TIMER_NOUNS = setOf("timer", "timers", "countdown", "minuterie", "minuteries", "minuteur", "minuteurs", "rebours")
    private val OFF_VERBS = setOf("turn", "switch", "shut")
    /** Words that ask for something else as well ("cancel the meeting and set an alarm"): left to the model. */
    private val NOT_ONLY_CANCEL = setOf("set", "add", "create", "make", "new", "remind", "wake", "mets", "ajoute", "cree", "rappelle", "reveille", "programme")

    /** Between the verb and the alarm or timer: "the", "my", « toutes mes », and its time ("7 am", "6:30", « 6 h 30 »). */
    private val BEFORE_NOUN = setOf("the", "my", "all", "a", "an", "this", "that", "these", "those", "your", "every", "mon", "ma", "mes", "ton", "ta",
        "tes", "l", "la", "le", "les", "un", "une", "ce", "cette", "ces", "tous", "toutes", "h", "am", "pm", "m", "p")
    /** Right after the noun, these make it a name of something else: "my alarm routine", "the timer app". */
    private val NOT_THE_CLOCK = setOf("routine", "routines", "skill", "skills", "competence", "competences", "note", "notes", "app", "appli",
        "application", "code", "sound", "son")

    /**
     * "cancel my 6:30 alarm", "turn off the timer", « annule mon alarme de 6 h 30 »: a cancel verb first, then the alarm or the
     * timer itself (only an article, a possessive or its time between them). After the routines' delete and run, so "delete my
     * alarm routine" stays a routine's; "delete the note about my alarm code" is never one.
     */
    private fun cantCancel(p: Words, i: Int): RoutineCommand.CantCancel? {
        val fr = p.at(i, CANCEL_FR)
        var n = maxOf(p.at(i, CANCEL_EN), fr)
        // fix-timers-1.2.1: "turn the timer off", "shut my alarm off": the verb, then "off" last.
        val off = n == 0 && p.w(i) in OFF_VERBS && p.size - i > 2 && p.w(p.size - 1) == "off"
        if (off) n = 1
        if (n == 0) return null
        val rest = (i + n until if (off) p.size - 1 else p.size).mapNotNull { p.w(it) }
        if (rest.size > 10 || rest.any { it in NOT_ONLY_CANCEL }) return null
        val k = rest.indexOfFirst { w -> w !in BEFORE_NOUN && !w.matches(Regex("""\d{1,2}(?::\d{2})?""")) }
        val noun = rest.getOrNull(k) ?: return null
        val timer = noun in TIMER_NOUNS || (noun == "compte" && rest.getOrNull(k + 1) == "a" && rest.getOrNull(k + 2) == "rebours")
        if (!timer && noun !in ALARM_NOUNS) return null
        if (rest.getOrNull(k + 1) in NOT_THE_CLOCK) return null
        if (timer) return RoutineCommand.CantCancel(timer = true, french = fr == n)
        // fix-everyday-c: the alarm's time as you said it ("6:30", "7 am", « 6 h 30 », « 7 h »), and "all".
        val t = rest.indexOfFirst { it.matches(Regex("""\d{1,2}(?::\d{2})?""")) }
        var hour = rest.getOrNull(t)?.substringBefore(':')?.toInt()
        var minute = rest.getOrNull(t)?.substringAfter(':', "")?.toIntOrNull()
        val after = rest.getOrNull(t + 1)
        if (hour != null && minute == null && after == "h") minute = rest.getOrNull(t + 2)?.takeIf { it.matches(Regex("""\d{2}""")) }?.toInt()
        val pm = after == "pm" || after == "p"
        val exact = hour != null && (pm || after == "am" || after == "a" || hour == 0 || hour > 12)
        if (hour != null && hour == 12 && (after == "am" || after == "a")) hour = 0
        if (hour != null && pm && hour < 12) hour += 12
        if (hour != null && (hour > 23 || (minute ?: 0) > 59)) { hour = null; minute = null }
        val all = rest.any { it == "all" || it == "every" || it == "tous" || it == "toutes" }
        return RoutineCommand.CantCancel(timer = false, french = fr == n, hour = hour, minute = minute?.takeIf { hour != null },
            exact = exact && hour != null, all = all)
    }

    // ------------------------------------------------------------------ deleting a note (fix-note-delete)

    private val DELETE_FR = setOf("supprime", "efface", "enleve", "retire") // l10n: not text (words a router reads)
    private val NOTE_DET = setOf("my", "the", "this", "that", "ma", "la", "cette", "mon", "ce") // l10n: not text (words a router reads)
    /** "the last note", "my most recent note", « ma dernière note », « la note la plus récente ». */
    private val NOTE_LAST = listOf("last", "latest", "newest", "most recent", "derniere", "dernier", "plus recente", "la plus recente") // l10n: not text (words a router reads)
    private val NOTE_ABOUT = listOf("about", "on", "for", "called", "named", "titled", "that says", "saying", "with", "sur", "a propos de",
        "a propos du", "a propos des", "qui parle de", "qui parle du", "intitulee", "appelee", "de", "du", "des", "d", "avec") // l10n: not text (words a router reads)
    private val NOTE_SUBJECT_DROP = setOf("the", "a", "an", "my", "le", "la", "l", "les", "un", "une", "mon", "ma", "mes") // l10n: not text (words a router reads)
    /** "… and set an alarm for 7": a second request after the note is the model's. */
    private val NOTE_THEN = setOf("and", "then", "et", "puis") // l10n: not text (words a router reads)
    private val NOTE_THEN_VERB = setOf("set", "add", "send", "text", "call", "remind", "delete", "remove", "open", "play", "turn", "start", "make",
        "create", "tell", "read", "show", "save", "write", "take", "mets", "ajoute", "envoie", "appelle", "rappelle", "supprime", "efface", "ouvre",
        "joue", "lis", "montre", "ecris", "cree", "fais") // l10n: not text (words a router reads)

    /**
     * fix-note-delete: a delete verb, an article or possessive, "note" (singular: "delete my notes" stays the model's), then
     * "about …" (« sur … ») or "last" before it. "delete the note" alone, or with a second request after it, is null.
     */
    private fun deleteNote(p: Words, i: Int): RoutineCommand.DeleteNote? {
        val v = p.at(i, DELETE)
        if (v == 0) return null
        val fr = p.w(i) in DELETE_FR
        var j = i + v
        while (p.w(j) in NOTE_DET) j++
        var last = false
        p.at(j, NOTE_LAST).takeIf { it > 0 }?.let { last = true; j += it }
        if (p.w(j) != "note") return null
        j++
        p.at(j, NOTE_LAST).takeIf { it > 0 }?.let { last = true; j += it }
        var end = p.size
        while (true) {
            val k = (j until end).firstOrNull { p.at(it, TAIL) == end - it } ?: break
            end = k
        }
        if (j >= end) return if (last) RoutineCommand.DeleteNote("last", fr) else null
        val a = p.at(j, NOTE_ABOUT)
        if (a == 0) return null
        var k = j + a
        while (k < end - 1 && p.w(k) in NOTE_SUBJECT_DROP) k++
        if (k >= end) return null
        if ((k until end).any { p.w(it) in NOTE_THEN && p.w(it + 1) in NOTE_THEN_VERB }) return null
        val subject = p.span(k, end).trim().trim('«', '»', '"', '“', '”', '.', '?', '!').trim()
        if (subject.isEmpty()) return null
        return RoutineCommand.DeleteNote(if (last) "last $subject" else subject, fr)
    }

    // ------------------------------------------------------------------ time left on Robot's timers (fix-everyday-e)

    private val LEFT_EN = listOf(
        Regex("""^(?:how (?:much (?:more )?time|long|many (?:minutes|seconds|mins))|whats|what is|time|is there (?:any )?time) .*\b(?:left|remaining|to go)\b.*"""),
        Regex("""^how much longer\b.*"""),
        Regex("""^(?:when|how long (?:until|till|til|before)|how soon) .*\b(?:go(?:es)? off|rings?|ends?|finish(?:es)?|done|up)$"""),
        Regex("""^(?:is|are) (?:the|my|a|any) (?:timers?|countdown) (?:still )?(?:running|going|on|set)$"""),
        Regex("""^(?:check|check on|hows|how is) (?:the|my) (?:timers?|countdown)(?: doing| going)?$"""),
    )
    private val LEFT_FR = listOf(
        Regex("""^(?:.* )?(?:combien|quel temps|temps|qu est ce qui|il) .*\b(?:reste|restant|restent|reste t il)\b.*"""),
        Regex("""^(?:.* )?(?:combien|temps) (?:de temps )?(?:il )?(?:reste|restant|reste t il)\b.*"""),
        Regex("""^(?:quand|dans combien de temps|a quelle heure|combien de temps avant que) .*\b(?:sonne|sonner|sonnera|finit|finir|finira|termine|terminer|terminera|fini|finie)\b.*"""),
        Regex("""^(?:est ce que )?(?:ma|la|mon|le) (?:minuterie|minuteur) (?:est encore|roule|tourne|marche)\b.*"""),
    )

    /** "How much time is left on my timer", « il reste combien de temps à ma minuterie »: a question about a timer, nothing else asked. */
    private fun timerLeft(p: Words, i: Int): RoutineCommand.TimerLeft? {
        val ws = (i until p.size).mapNotNull { p.w(it) }.filter { it != "," }
        if (ws.isEmpty() || ws.size > 16 || p.at(i, CANCEL_EN) > 0 || p.at(i, CANCEL_FR) > 0) return null
        if (ws.any { it in NOT_ONLY_CANCEL || it in ALARM_NOUNS }) return null
        val k = ws.indexOfFirst { it in TIMER_NOUNS }
        if (k < 0 || ws.getOrNull(k + 1) in NOT_THE_CLOCK) return null
        val s = ws.joinToString(" ")
        val french = ws[k] != "timer" && ws[k] != "timers" && ws[k] != "countdown"
        return if ((if (french) LEFT_FR else LEFT_EN).any { it.matches(s) }) RoutineCommand.TimerLeft(french) else null
    }

    // ------------------------------------------------------------------ the next alarm (fix-cancel-alarm)

    private val NEXT_EN = listOf(
        Regex("""^(?:what|which) alarms? (?:do i have|have i got|have i set|are (?:set|on)|is set)(?: set| on)?(?: for tomorrow)?$"""),
        Regex("""^(?:do|have) i (?:have|got|set) (?:an|any) alarms?(?: set| on)?(?: for tomorrow)?$"""),
        Regex("""^(?:is|are) (?:my|an|any|the) alarms? (?:set|on)(?: for tomorrow)?$"""),
        Regex("""^(?:when|what time) (?:is|does) (?:my|the) (?:next )?alarm(?: set for| for| set| go off| ring)?$"""),
        Regex("""^(?:whats|what is|what s) my (?:next )?alarm(?: set for| at)?$"""),
        Regex("""^(?:list|tell me) (?:my|all my) alarms$"""),
    )
    private val NEXT_FR = listOf(
        Regex("""^quelles? alarmes? (?:j ai|ai je|est ce que j ai)(?: de)?(?: reglees?)?(?: pour demain)?$"""),
        Regex("""^(?:est ce que )?j ai (?:une|des) alarmes?(?: de)?(?: reglees?)?(?: pour demain)?$"""),
        Regex("""^(?:c est )?quand (?:sonne )?(?:ma|mon) (?:prochaine |prochain )?(?:alarme|reveil|cadran)(?: sonne)?$"""),
        Regex("""^a quelle heure (?:sonne )?(?:ma|mon) (?:prochaine |prochain )?(?:alarme|reveil|cadran)(?: sonne| est reglee?)?$"""),
        Regex("""^(?:c est quoi|quelle est) (?:ma|mon) (?:prochaine |prochain )?(?:alarme|reveil|cadran)$"""),
    )

    /** "What alarms do I have", "when is my next alarm", « quelles alarmes j'ai »: a question about the alarms, nothing else asked. */
    private fun nextAlarm(p: Words, i: Int): RoutineCommand.NextAlarm? {
        val ws = (i until p.size).mapNotNull { p.w(it) }.filter { it != "," && it != "?" }
        if (ws.isEmpty() || ws.size > 12 || ws.none { it in ALARM_NOUNS }) return null
        val s = ws.joinToString(" ")
        return when {
            NEXT_EN.any { it.matches(s) } -> RoutineCommand.NextAlarm(false)
            NEXT_FR.any { it.matches(s) } -> RoutineCommand.NextAlarm(true)
            else -> null
        }
    }

    /** 1.1 (phone, 22 Oct): a reminder that names nothing to remind and no time ([RoutineCommand.RemindWhat]), as a whole utterance. */
    private val REMIND_WHAT = listOf("remind me", "remind me about it", "remind me about that", "remind me about this", "remind me of it",
        "remind me of that", "remind me of this", "remind me it", "remind me that", "set a reminder", "set me a reminder", "add a reminder",
        "make a reminder", "rappelle moi", "rappelle moi ca", "rappelle moi cela", "rappelle moi le", "rappelle moi la", "rappelle le moi",
        "rappelle la moi", "fais moi penser", "fais moi penser a ca", "fais moi penser a cela", "fais moi y penser", "mets un rappel",
        "ajoute un rappel")
    /** What may follow it: not "today" or "now", which say when. */
    private val REMIND_WHAT_TAIL = listOf("please", "robot", "s il te plait", "s il vous plait", ",")

    private fun remindWhat(p: Words, i: Int): Boolean {
        var j = i + p.at(i, REMIND_WHAT)
        if (j == i) return false
        while (j < p.size) { val f = p.at(j, REMIND_WHAT_TAIL); if (f == 0) return false; j += f }
        return true
    }

    private fun reminder(raw: String, tomorrow: Boolean): RoutineCommand? {
        val t = raw.trim().trimStart(',', ':', ' ').trimEnd('.', '!', '?', ' ').trim()
        if (t.isEmpty() || HAS_TIME.containsMatchIn(t)) return null
        return RoutineCommand.Remind(t, tomorrow)
    }

    // ------------------------------------------------------------------ making a routine

    /** What starts the routine, read at the start: the trigger, its conditions, where the action part begins. */
    private class TriggerRead(val trigger: RoutineTrigger, val end: Int, val lang: Lang, val conditions: RoutineConditions = RoutineConditions(),
        val notes: List<Phrase> = emptyList())

    private fun create(p: Words, i: Int, now: ZonedDateTime, names: List<String>, id: String, fromContent: Boolean, window: TimeWindow): RoutineDraft? {
        val trig = timeTrigger(p, i) ?: eventTrigger(p, i, window) ?: return null
        var j = trig.end
        val comma = p.w(j) == ","
        while (p.w(j) == ",") j++
        if (j >= p.size) return null
        val acts = actions(p, j)
        if (acts.steps.isEmpty() && acts.refused.isEmpty() && !(comma || acts.commandLike)) return null
        val name = unique(defaultName(trig.trigger, trig.lang), names)
        val routine = Routine(id, name, trig.trigger, acts.steps, created = now.toInstant().toEpochMilli(), conditions = trig.conditions)
        val notes = trig.notes + acts.notes
        return RoutineDraft(routine, trig.lang, acts.unparsed, acts.refused, notes.map { it.en() }, fromContent, notes)
    }

    private fun unique(name: String, names: List<String>): String {
        val taken = names.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (name.lowercase(Locale.ROOT) !in taken) return name
        return (2..99).map { "$name $it" }.first { it.lowercase(Locale.ROOT) !in taken }
    }

    /** A new routine's name, in the language it was said in (it is then yours: never translated again). */
    private fun defaultName(t: RoutineTrigger, lang: Lang): String {
        val m = when (t) {
            is RoutineTrigger.At -> when (t.time.hour) {
                in 0..11 -> RoutineStrings.nameMorning
                in 12..16 -> RoutineStrings.nameAfternoon
                else -> RoutineStrings.nameEvening
            }
            is RoutineTrigger.AlarmDismissed -> RoutineStrings.nameAfterAlarm
            is RoutineTrigger.Unlock -> RoutineStrings.nameFirstUnlock
            is RoutineTrigger.Device -> when (t.type) {
                DeviceKind.HEADSET -> if (t.connect) RoutineStrings.nameHeadphonesOn else RoutineStrings.nameHeadphonesOff
                DeviceKind.CAR -> if (t.connect) RoutineStrings.nameInCar else RoutineStrings.nameOutOfCar
            }
            is RoutineTrigger.Charging -> if (t.start) RoutineStrings.nameCharging else RoutineStrings.nameUnplugged
            RoutineTrigger.Asked -> RoutineStrings.nameMine
        }
        // Your words from then on: the plain apostrophe, as typed names have it.
        return Phrase.M(m).render(Langs.of(lang)).replace('’', '\'')
    }

    // ------------------------------------------------------------------ times and days

    private class Days(val days: Set<DayOfWeek>, val end: Int, val period: Period?, val lang: Lang)

    private val PERIOD_WORDS = mapOf("morning" to Period.AM, "mornings" to Period.AM, "evening" to Period.PM, "evenings" to Period.PM,
        "night" to Period.PM, "nights" to Period.PM, "afternoon" to Period.PM, "afternoons" to Period.PM,
        "matin" to Period.AM, "matins" to Period.AM, "soir" to Period.PM, "soirs" to Period.PM, "nuit" to Period.PM, "apres midi" to Period.PM)

    private fun period(p: Words, j: Int): Pair<Period?, Int> {
        val n = p.at(j, PERIOD_WORDS.keys)
        return if (n == 0) null to 0 else PERIOD_WORDS.getValue((j until j + n).joinToString(" ") { p.w(it)!! }) to n
    }

    /** "monday", "monday and friday", "monday, wednesday and friday" ([plural]: "mondays", "lundis"). */
    private fun dayList(p: Words, j0: Int, map: Map<String, DayOfWeek>, plural: Boolean): Pair<Set<DayOfWeek>, Int>? {
        fun day(w: String?): DayOfWeek? = w?.let { map[it] ?: if (plural && it.endsWith("s")) map[it.dropLast(1)] else null }
        var j = j0
        val out = mutableSetOf(day(p.w(j)) ?: return null)
        j++
        while (true) {
            var k = j
            if (p.w(k) == "," ) k++
            if (p.w(k) in setOf("and", "et", "pis")) k++
            if (p.w(k) in setOf("le", "les")) k++
            val d = day(p.w(k)) ?: break
            out += d
            j = k + 1
        }
        return out to j
    }

    private fun daysEn(p: Words, i: Int): Days? {
        var j = i
        val every = p.at(j, "every", "each")
        val set: Set<DayOfWeek>
        var per: Period? = null
        if (every > 0) {
            j += every
            when {
                p.at(j, "day") > 0 -> { set = RoutineWords.ALL; j++ }
                p.at(j, "weekday", "workday", "work day", "week day", "school day") > 0 -> { val n = p.at(j, "weekday", "workday", "work day", "week day", "school day"); set = RoutineWords.WEEKDAYS; j += n }
                p.at(j, "weekend day", "weekend") > 0 -> { val n = p.at(j, "weekend day", "weekend"); set = RoutineWords.WEEKEND; j += n }
                period(p, j).first != null -> { val (pp, n) = period(p, j); set = RoutineWords.ALL; per = pp; j += n }
                else -> { val (d, e) = dayList(p, j, DAYS_EN, plural = false) ?: return null; set = d; j = e }
            }
        } else {
            val on = p.at(j, "on")
            j += on
            when {
                p.at(j, "weekdays", "workdays", "week days") > 0 -> { set = RoutineWords.WEEKDAYS; j += p.at(j, "weekdays", "workdays", "week days") }
                p.at(j, "weekends") > 0 -> { set = RoutineWords.WEEKEND; j++ }
                p.at(j, "weekday mornings", "weekday evenings") > 0 -> { set = RoutineWords.WEEKDAYS; per = period(p, j + 1).first; j += 2 }
                on == 0 && p.at(j, "daily") > 0 -> { set = RoutineWords.ALL; j++ }
                else -> {
                    // "on mondays and fridays": plural only (a single "on monday" is one day, not a routine).
                    if (p.w(j)?.endsWith("s") != true || p.w(j) !in DAYS_EN.keys.map { it + "s" }) return null
                    val (d, e) = dayList(p, j, DAYS_EN, plural = true) ?: return null
                    set = d; j = e
                }
            }
        }
        if (per == null) { val (pp, n) = period(p, j); if (pp != null) { per = pp; j += n } }
        return Days(set, j, per, Lang.EN)
    }

    private fun daysFr(p: Words, i: Int): Days? {
        var j = i
        var per: Period? = null
        val set: Set<DayOfWeek>
        fun take(vararg ph: String): Boolean { val n = p.at(j, *ph); if (n > 0) j += n; return n > 0 }
        when {
            take("chaque") -> when {
                take("jour de semaine", "matin de semaine", "soir de semaine") -> { set = RoutineWords.WEEKDAYS; per = semainePeriod(p, j) }
                take("fin de semaine", "week end", "weekend") -> set = RoutineWords.WEEKEND
                take("jour") -> set = RoutineWords.ALL
                period(p, j).first != null -> { val (pp, n) = period(p, j); per = pp; j += n; set = RoutineWords.ALL }
                else -> { val (d, e) = dayList(p, j, DAYS_FR, plural = false) ?: return null; set = d; j = e }
            }
            take("tous les", "toutes les") -> when {
                take("jours de semaine", "matins de semaine", "soirs de semaine") -> { set = RoutineWords.WEEKDAYS; per = semainePeriod(p, j) }
                take("fins de semaine", "week ends", "weekends") -> set = RoutineWords.WEEKEND
                take("jours") -> set = RoutineWords.ALL
                take("matins") -> { set = RoutineWords.ALL; per = Period.AM }
                take("soirs") -> { set = RoutineWords.ALL; per = Period.PM }
                else -> { val (d, e) = dayList(p, j, DAYS_FR, plural = true) ?: return null; set = d; j = e }
            }
            take("en semaine", "la semaine", "les jours de semaine", "les matins de semaine", "les soirs de semaine", "du lundi au vendredi") -> {
                set = RoutineWords.WEEKDAYS; per = semainePeriod(p, j)
            }
            take("la fin de semaine", "les fins de semaine", "en fin de semaine", "le week end", "les week ends", "le weekend", "les weekends") -> set = RoutineWords.WEEKEND
            p.at(j, "le", "les") > 0 -> {
                // "le lundi", "les lundis": every Monday ("lundi" alone is one day, not a routine).
                val plural = p.w(j) == "les"
                val (d, e) = dayList(p, j + 1, DAYS_FR, plural) ?: return null
                set = d; j = e
            }
            else -> return null
        }
        if (per == null) { val (pp, n) = period(p, j); if (pp != null) { per = pp; j += n } }
        return Days(set, j, per, Lang.FR)
    }

    /** The period said in "matin de semaine" / "soirs de semaine", looking back from after it. */
    private fun semainePeriod(p: Words, after: Int): Period? = when {
        p.w(after - 3)?.startsWith("matin") == true -> Period.AM
        p.w(after - 3)?.startsWith("soir") == true -> Period.PM
        else -> null
    }


    /** "every weekday at 7", "on weekends at 9:30", "chaque matin de semaine à 7 h", "à 7 h tous les jours". */
    private fun timeTrigger(p: Words, i: Int): TriggerRead? {
        val notes = mutableListOf<Phrase>()
        fun withTime(d: Days, at: Int): TriggerRead? {
            val lead = p.at(at, "at", "a", "vers", "around")
            if (lead == 0) return null
            val c = clock(p, at + lead) ?: return null
            val (cp, n) = clockPeriod(p, c.end)
            val t = resolve(c, cp ?: d.period, notes)
            return TriggerRead(RoutineTrigger.At(t, d.days), c.end + n, d.lang, notes = notes)
        }
        (daysEn(p, i) ?: daysFr(p, i))?.let { d -> return withTime(d, d.end) }
        // The time first: "at 7 every weekday", "à 7 h tous les jours".
        val lead = p.at(i, "at", "a")
        if (lead > 0) {
            val c = clock(p, i + lead) ?: return null
            val (cp, n) = clockPeriod(p, c.end)
            val d = daysEn(p, c.end + n) ?: daysFr(p, c.end + n) ?: return null
            return TriggerRead(RoutineTrigger.At(resolve(c, cp ?: d.period, notes), d.days), d.end, d.lang, notes = notes)
        }
        return null
    }

    private val WHEN_EN = listOf("when i", "whenever i", "after i", "once i", "as soon as i", "each time i", "every time i")
    private val WHEN_FR = listOf("quand je", "quand j", "lorsque je", "lorsque j", "des que je", "des que j", "chaque fois que je", "chaque fois que j", "apres que je")
    private val ALARM_EN = listOf("turn off my alarm", "turn my alarm off", "turn off the alarm", "turn the alarm off", "dismiss my alarm", "dismiss the alarm",
        "stop my alarm", "stop the alarm", "switch off my alarm", "shut off my alarm", "silence my alarm", "turn off my alarm clock")
    private val ALARM_EN_SUBJECT = listOf("when my alarm goes off", "when my alarm rings", "when my alarm stops", "after my alarm goes off",
        "after my alarm", "when the alarm goes off")
    private val ALARM_FR_VERB = listOf("ferme", "eteins", "arrete", "coupe", "desactive")
    private val ALARM_FR_OBJ = listOf("mon cadran", "le cadran", "mon reveil", "le reveil", "mon alarme", "l alarme", "mon reveille matin")
    private val ALARM_FR_SUBJECT = listOf("quand mon cadran sonne", "quand le cadran sonne", "quand mon reveil sonne", "apres mon cadran", "apres mon reveil")
    private val UNLOCK_EN = listOf("first unlock my phone", "unlock my phone", "unlock the phone", "first unlock", "unlock")
    private val UNLOCK_FR = listOf("deverrouille mon telephone", "deverrouille le telephone", "deverrouille mon cell", "deverrouille mon cellulaire",
        "debarre mon telephone", "debarre mon cell", "deverrouille")
    private val HEADSET_EN = listOf("my headphones", "the headphones", "my earbuds", "my headset", "my buds", "my earphones", "my airpods")
    private val CAR_EN = listOf("my car", "the car", "to my car", "to the car")
    private val HEADSET_FR = listOf("mes ecouteurs", "les ecouteurs", "mon casque")
    private val CAR_FR = listOf("a l auto", "a mon auto", "au char", "a mon char", "a la voiture", "a ma voiture", "dans l auto", "dans le char",
        "dans mon auto", "dans mon char", "dans la voiture", "dans ma voiture")
    private val CHARGE_EN = listOf("plug in my phone", "plug my phone in", "plug in the phone", "plug the phone in", "plug in", "start charging",
        "charge my phone", "put my phone on charge", "put my phone on the charger", "put the phone on charge", "put my phone to charge")
    private val UNPLUG_EN = listOf("unplug my phone", "unplug the phone", "unplug", "stop charging", "take my phone off the charger")
    private val CHARGE_FR = listOf("mets le telephone a charger", "mets mon telephone a charger", "met le telephone a charger", "met mon telephone a charger",
        "mets mon cell a charger", "mets le cell a charger", "branche mon telephone", "branche le telephone", "branche mon cell", "branche le chargeur",
        "mets mon telephone sur le chargeur", "mets le telephone sur le chargeur")
    private val UNPLUG_FR = listOf("debranche mon telephone", "debranche le telephone", "debranche mon cell", "enleve mon telephone du chargeur")
    private val NIGHT_WORDS = listOf("at night", "in the evening", "tonight", "for the night", "le soir", "la nuit", "pour la nuit", "au coucher")

    /** "when I turn off my alarm", "quand je ferme mon cadran", "when I connect my headphones", "quand je me connecte à l'auto", … */
    private fun eventTrigger(p: Words, i: Int, window: TimeWindow): TriggerRead? {
        // English.
        p.at(i, ALARM_EN_SUBJECT).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), i + it, Lang.EN) }
        val we = p.at(i, WHEN_EN)
        if (we > 0) {
            val j = i + we
            p.at(j, ALARM_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), j + it, Lang.EN) }
            p.at(j, UNLOCK_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Unlock(), j + it, Lang.EN) }
            val connect = p.at(j, "connect to", "connect", "plug in", "put on", "pair", "turn on")
            val disconnect = p.at(j, "disconnect from", "disconnect", "unplug", "take off", "turn off")
            for ((n, on) in listOf(connect to true, disconnect to false)) if (n > 0) {
                p.at(j + n, HEADSET_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET, connect = on), j + n + it, Lang.EN) }
                p.at(j + n, CAR_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = on), j + n + it, Lang.EN) }
            }
            p.at(j, "get in the car", "get in my car", "get into the car", "get into my car").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR), j + it, Lang.EN)
            }
            p.at(j, "get out of the car", "get out of my car", "leave the car").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = false), j + it, Lang.EN)
            }
            for ((list, start) in listOf(CHARGE_EN to true, UNPLUG_EN to false)) {
                val n = p.at(j, list)
                if (n > 0) return charging(p, j + n, start, Lang.EN)
            }
            return null
        }
        // "when my headphones connect", "when the car disconnects".
        if (p.w(i) == "when") for ((list, kind) in listOf(HEADSET_EN to DeviceKind.HEADSET, CAR_EN to DeviceKind.CAR)) {
            val n = p.at(i + 1, list)
            if (n == 0) continue
            val v = p.at(i + 1 + n, "connect", "connects", "disconnect", "disconnects")
            if (v > 0) return TriggerRead(RoutineTrigger.Device(kind, connect = p.w(i + 1 + n)!!.startsWith("connect")), i + 1 + n + v, Lang.EN)
        }
        // French.
        p.at(i, ALARM_FR_SUBJECT).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), i + it, Lang.FR) }
        val wf = p.at(i, WHEN_FR)
        if (wf > 0) {
            val j = i + wf
            p.at(j, ALARM_FR_VERB).takeIf { it > 0 }?.let { v ->
                p.at(j + v, ALARM_FR_OBJ).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), j + v + it, Lang.FR) }
            }
            p.at(j, UNLOCK_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Unlock(), j + it, Lang.FR) }
            p.at(j, "branche", "connecte", "mets").takeIf { it > 0 }?.let { v ->
                p.at(j + v, HEADSET_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET), j + v + it, Lang.FR) }
            }
            p.at(j, "debranche", "deconnecte", "enleve").takeIf { it > 0 }?.let { v ->
                p.at(j + v, HEADSET_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET, connect = false), j + v + it, Lang.FR) }
            }
            p.at(j, "me connecte", "embarque", "monte", "rentre").takeIf { it > 0 }?.let { v ->
                p.at(j + v, CAR_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR), j + v + it, Lang.FR) }
            }
            p.at(j, "sors de l auto", "sors du char", "sors de la voiture", "debarque de l auto", "debarque du char").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = false), j + it, Lang.FR)
            }
            for ((list, start) in listOf(CHARGE_FR to true, UNPLUG_FR to false)) {
                val n = p.at(j, list)
                if (n > 0) return charging(p, j + n, start, Lang.FR)
            }
            return null
        }
        return null
    }

    /** Charging starts or stops; "at night" or "le soir" after it: only between 18:00 and 04:00. */
    private fun charging(p: Words, j: Int, start: Boolean, lang: Lang): TriggerRead {
        val night = p.at(j, NIGHT_WORDS)
        val morning = p.at(j, "in the morning", "le matin")
        val cond = when {
            night > 0 -> RoutineConditions(between = TimeWindow.NIGHT)
            morning > 0 -> RoutineConditions(between = TimeWindow.ALARM)
            else -> RoutineConditions()
        }
        return TriggerRead(RoutineTrigger.Charging(start), j + maxOf(night, morning), lang, cond)
    }

}

/**
 * 1.0 audit (physical-routines-writable-locked): routines and brief reminders are instructions that run later by
 * themselves, in Robot's own voice, so the locked phone neither changes nor lists them ("Unlock first"), as memory's
 * writes: someone holding it (or a TV in the room) can't plant a routine, delete yours, add a reminder your brief reads,
 * or hear what your routines are. The brief and running a routine keep their own lock rules.
 */
object RoutineLockedPhone {
    val UNLOCK_CHANGE: String get() = RoutineStrings.unlockChange().en()
    val UNLOCK_LIST: String get() = RoutineStrings.unlockList().en()
    val UNLOCK_REMIND: String get() = RoutineStrings.unlockRemind().en()
    val UNLOCK_CHANGE_FR: String get() = RoutineStrings.unlockChange().render(Langs.of(Lang.FR))
    val UNLOCK_LIST_FR: String get() = RoutineStrings.unlockList().render(Langs.of(Lang.FR))
    val UNLOCK_REMIND_FR: String get() = RoutineStrings.unlockRemind().render(Langs.of(Lang.FR))

    /** What Robot says instead of [cmd] on a [locked] phone, in French when [fr] ([RoutineRouter.french]); null when it may go on. */
    fun refuse(cmd: RoutineCommand, locked: Boolean, fr: Boolean = false): String? = refuseText(cmd, locked)?.render(Langs.of(if (fr) Lang.FR else Lang.EN))

    /** 1.1: [refuse] in a turn whose languages are [l]: the words without a language yet, which the turn renders in [l]. */
    @Suppress("UNUSED_PARAMETER")
    fun refuse(cmd: RoutineCommand, locked: Boolean, l: Langs): Phrase? = refuseText(cmd, locked)

    /** 1.1: [refuse] without a language yet (the turn says it in its reply language). */
    fun refuseText(cmd: RoutineCommand, locked: Boolean): Phrase? = if (!locked) null else when (cmd) {
        is RoutineCommand.Create, is RoutineCommand.Delete -> RoutineStrings.unlockChange()
        is RoutineCommand.Remind -> RoutineStrings.unlockRemind()
        is RoutineCommand.RemindOn -> RoutineStrings.unlockRemindOn()
        is RoutineCommand.DeleteNote -> app.heyrobot.core.i18n.ToolStrings.noteDeleteUnlock()
        RoutineCommand.ListAll -> RoutineStrings.unlockList()
        is RoutineCommand.Show -> RoutineStrings.unlockShow()
        is RoutineCommand.Arrange -> RoutineStrings.unlockArrange()
        // H7b: which feeds you read is personal, as the brief's news waits for the unlock.
        RoutineCommand.News -> RoutineStrings.unlockNews()
        // 1.1: Robot only asks what and when.
        RoutineCommand.BriefNow, RoutineCommand.Today, is RoutineCommand.Run, RoutineCommand.RemindWhat, is RoutineCommand.CantCancel, is RoutineCommand.TimerLeft,
        is RoutineCommand.NextAlarm -> null
    }
}

/** H3: a page "show my …" opens. */
enum class ShowPage { HISTORY, CALENDAR }
