package app.heyrobot.core

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

// Pure. Routines by voice (design §2.3 `RoutineRouter`), English and Québec French, decided by code before anything
// reaches Gemma, anchored at the start of what you said (only "hey robot", "please" and the like in front), like
// ClaudeRouter. "Every weekday at 7, tell me the weather and my calendar" becomes a draft routine shown on its card; it is
// never saved without your yes (or a tap, when it came from content). Steps map through the same words as the tools, "pis"
// counts as "et", and a part not understood is never guessed: the card names it and points to Routines. It must not
// misfire on ordinary talk ("every time I call Bob he…", "when I was at work", "chaque fois que…") or on a reminder with a
// time, which stays an alarm or an event as today.

sealed class RoutineCommand {
    /** "morning brief", "what's my day", "mon résumé du matin", "bon matin" (as a whole utterance). */
    data object BriefNow : RoutineCommand()

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
}

/**
 * A routine made by voice, waiting on its card. [unparsed]: your words not understood; [refused]: steps routines never
 * do; [notes]: what was read in a way you may want to change ("7" as 7:00). [fromContent]: the request came from content
 * (screen text, a photo, something Robot read): then only a tap saves it.
 */
data class RoutineDraft(
    val routine: Routine,
    val lang: BriefLang,
    val unparsed: List<String> = emptyList(),
    val refused: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val fromContent: Boolean = false,
) {
    val problems: List<String> get() = RoutineCheck.problems(routine)
    val canSave: Boolean get() = problems.isEmpty()

    /** A spoken "yes" may save it: it can be saved and it didn't come from content. */
    val yesSaves: Boolean get() = canSave && !fromContent

    /** The routine as saved at [nowMs]: the online services its card listed become its consent. Null if it can't be saved. */
    fun save(nowMs: Long): Routine? = if (canSave) routine.copy(online = routine.needsOnline, created = nowMs, edited = nowMs) else null

    fun card(): RoutineCard = RoutineCards.card(this)
}

/** The routine card (design §2.5 "Saving always shows a card"): what starts it, every step with its class, where it speaks, what goes online. */
data class RoutineCard(val title: String, val lines: List<String>, val footer: String)

object RoutineCards {
    private fun hm(t: LocalTime) = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

    /** "every day", "every weekday", "on weekends", "every Monday and Friday". */
    fun days(d: Set<DayOfWeek>): String = when (d) {
        DayOfWeek.entries.toSet() -> "every day"
        RoutineWords.WEEKDAYS -> "every weekday"
        RoutineWords.WEEKEND -> "on weekends"
        else -> d.sortedBy { it.value }.map { BriefClock.dayEn(it) }.let { n ->
            "every " + if (n.size == 1) n[0] else n.dropLast(1).joinToString(", ") + " and " + n.last()
        }
    }

    /** "every weekday at 07:00", "when you turn off an alarm between 04:00 and 12:00", "when your car connects". */
    fun trigger(t: RoutineTrigger): String = when (t) {
        is RoutineTrigger.At -> (t.once?.let { "on ${EventText.day(it, it.year)}" } ?: days(t.days)) + " at " + hm(t.time)
        is RoutineTrigger.AlarmDismissed -> "when you turn off an alarm between ${hm(t.window.from)} and ${hm(t.window.to)}"
        is RoutineTrigger.Unlock -> "the first time you unlock after ${hm(t.after)}"
        is RoutineTrigger.Device -> {
            val what = when (t.type) { DeviceKind.HEADSET -> "your headphones"; DeviceKind.CAR -> "your car" }
            val verb = when { t.type == DeviceKind.HEADSET && t.connect -> "connect"; t.type == DeviceKind.HEADSET -> "disconnect"; t.connect -> "connects"; else -> "disconnects" }
            "when $what $verb"
        }
        is RoutineTrigger.Charging -> if (t.start) "when charging starts" else "when charging stops"
        RoutineTrigger.Asked -> "only when you ask"
    }

    /** The trigger with the routine's conditions ("when charging starts, between 18:00 and 04:00"). */
    fun whenText(r: Routine): String {
        val c = r.conditions
        if (r.trigger is RoutineTrigger.At || r.trigger == RoutineTrigger.Asked) return trigger(r.trigger)
        return listOfNotNull(trigger(r.trigger), c.between?.let { "between ${hm(it.from)} and ${hm(it.to)}" }, c.days?.let { days(it) },
            if (c.oncePerDay) "at most once a day" else null).joinToString(", ")
    }

    fun card(d: RoutineDraft): RoutineCard {
        val r = d.routine
        val lines = buildList {
            add("When: ${whenText(r)}")
            r.steps.forEachIndexed { i, s ->
                add("${i + 1}. ${s.describe()} · ${RoutineGate.classOf(s).badge}" + (s.online?.let { " · goes online: ${it.title}" } ?: ""))
            }
            add("Speaks: ${r.output.title}")
            val online = r.needsOnline
            add(if (online.isEmpty()) "Nothing goes online." else "Online by itself: " + online.joinToString(", ") { it.title } + ". Nothing about you is sent.")
            for (u in d.unparsed) add("I didn't understand “$u”: edit it in Routines.")
            for (x in d.refused) add("Routines can't free memory or storage, delete things or ask Claude: “$x” was left out.")
            addAll(d.notes)
            addAll(d.problems)
        }
        val footer = when {
            !d.canSave -> "Edit it in Routines to save it."
            d.fromContent -> "Tap Save: this routine came from the screen."
            else -> "Say yes or tap Save."
        }
        return RoutineCard("Save the routine “${r.name}”?", lines, footer)
    }

    /** What Robot says for "list my routines". */
    fun list(routines: List<Routine>): String {
        if (routines.isEmpty()) return "You have no routines yet. Say, for example: every weekday at 7, tell me the weather and my calendar."
        val items = routines.map { r -> "${r.name}, ${whenText(r)}" + if (r.enabled) "" else " (off)" }
        return (if (routines.size == 1) "You have 1 routine: " else "You have ${routines.size} routines: ") + items.joinToString("; ") + "."
    }
}

/** Day sets the router and the card share. */
object RoutineWords {
    val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val ALL: Set<DayOfWeek> = DayOfWeek.entries.toSet()
}

object RoutineRouter {
    private enum class Period { AM, PM }

    /** A word of the utterance: plain (no accents, apostrophes or case) and where it is in what was said. */
    private class Tok(val w: String, val start: Int, val end: Int)

    /** French elisions split ("j'éteins" → j, eteins; "d'apporter"); English contractions stay one word ("what's" → whats). */
    private val ELISION = setOf("j", "l", "d", "qu", "c", "m", "t", "s", "n", "jusqu", "lorsqu", "puisqu")

    private fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace("'", "").replace("’", "")

    private class Words(val text: String) {
        val t: List<Tok> = tokens(text)
        val size: Int get() = t.size
        fun w(i: Int): String? = t.getOrNull(i)?.w

        /** The length of the longest of [phrases] (words separated by spaces) at [i], or 0. */
        fun at(i: Int, phrases: Collection<String>): Int = phrases.maxOfOrNull { ph ->
            val ws = ph.split(' ')
            if (ws.indices.all { w(i + it) == ws[it] }) ws.size else 0
        } ?: 0

        fun at(i: Int, vararg phrases: String): Int = at(i, phrases.asList())

        /** What was said from word [from] to before [to], as said (with closing brackets right after the last word). */
        fun span(from: Int, to: Int): String {
            if (from >= to || from >= size) return ""
            var end = t[minOf(to, size) - 1].end
            while (end < text.length && text[end] in ")]}") end++
            return text.substring(t[from].start, end)
        }

        private fun tokens(text: String): List<Tok> {
            val out = mutableListOf<Tok>()
            var i = 0
            fun digitAt(k: Int) = k in text.indices && text[k].isDigit()
            while (i < text.length) {
                val c = text[i]
                if (c == ',' || c == ';' || (c == ':' && !(digitAt(i - 1) && digitAt(i + 1)))) { out += Tok(",", i, i + 1); i++; continue }
                if (!c.isLetterOrDigit()) { i++; continue }
                val start = i
                while (i < text.length) {
                    val ch = text[i]
                    if (ch.isLetterOrDigit()) { i++; continue }
                    if ((ch == ':' || ch == '.') && digitAt(i - 1) && digitAt(i + 1)) { i++; continue }
                    if ((ch == '\'' || ch == '’') && i + 1 < text.length && text[i + 1].isLetter() && plain(text.substring(start, i)) !in ELISION) { i++; continue }
                    break
                }
                out += Tok(plain(text.substring(start, i)), start, i)
            }
            return out
        }
    }

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
    private val HOUR_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

    private val BRIEF_NOW = listOf("morning brief", "my morning brief", "the morning brief", "brief me", "give me my morning brief", "give me my brief",
        "give me the morning brief", "read my morning brief", "read me my morning brief", "read me my brief", "read my brief", "my brief",
        "whats my day", "what is my day", "whats my day like", "what does my day look like", "whats on today", "what is on today", "whats on for today",
        "mon resume du matin", "mon resume", "le resume du matin", "resume du matin", "donne moi mon resume", "donne moi mon resume du matin",
        "lis moi mon resume", "lis moi mon resume du matin", "c est quoi ma journee", "quest ce que j ai aujourdhui", "qu est ce que j ai aujourdhui",
        "qu est ce que j ai de prevu aujourdhui", "c est quoi mon programme aujourdhui", "bon matin")
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
        if (whole(p, i, BRIEF_NOW)) return RoutineCommand.BriefNow
        if (whole(p, i, LIST)) return RoutineCommand.ListAll
        named(p, i, DELETE, names)?.let { return RoutineCommand.Delete(it) }
        named(p, i, RUN, names)?.let { return RoutineCommand.Run(it) }
        return null
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

    private fun remind(p: Words, i: Int): RoutineCommand? {
        val lead = p.at(i, REMIND_LEAD)
        if (lead > 0) {
            var j = i + lead
            // "remind me in the morning to …", "rappelle-moi demain matin d'apporter …".
            val m = p.at(j, MORNING.keys)
            if (m > 0) {
                val tomorrow = MORNING.getValue((j until j + m).joinToString(" ") { p.w(it)!! })
                j += m
                val to = p.at(j, "to", "de", "d")
                if (to == 0) return null
                return reminder(p.span(j + to, p.size), tomorrow)
            }
            // "remind me to … in the morning".
            val to = p.at(j, "to", "de", "d")
            if (to == 0) return null
            for (k in p.size - 1 downTo j + to + 1) {
                val n = p.at(k, MORNING.keys)
                if (n > 0 && k + n == p.size) return reminder(p.span(j + to, k), MORNING.getValue((k until k + n).joinToString(" ") { p.w(it)!! }))
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

    private fun reminder(raw: String, tomorrow: Boolean): RoutineCommand? {
        val t = raw.trim().trimStart(',', ':', ' ').trimEnd('.', '!', '?', ' ').trim()
        if (t.isEmpty() || HAS_TIME.containsMatchIn(t)) return null
        return RoutineCommand.Remind(t, tomorrow)
    }

    // ------------------------------------------------------------------ making a routine

    /** What starts the routine, read at the start: the trigger, its conditions, where the action part begins. */
    private class TriggerRead(val trigger: RoutineTrigger, val end: Int, val lang: BriefLang, val conditions: RoutineConditions = RoutineConditions(),
        val notes: List<String> = emptyList())

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
        return RoutineDraft(routine, trig.lang, acts.unparsed, acts.refused, trig.notes + acts.notes, fromContent)
    }

    private fun unique(name: String, names: List<String>): String {
        val taken = names.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (name.lowercase(Locale.ROOT) !in taken) return name
        return (2..99).map { "$name $it" }.first { it.lowercase(Locale.ROOT) !in taken }
    }

    private fun defaultName(t: RoutineTrigger, lang: BriefLang): String {
        val en = lang == BriefLang.EN
        return when (t) {
            is RoutineTrigger.At -> when (t.time.hour) {
                in 0..11 -> if (en) "Morning routine" else "Routine du matin"
                in 12..16 -> if (en) "Afternoon routine" else "Routine de l'après-midi"
                else -> if (en) "Evening routine" else "Routine du soir"
            }
            is RoutineTrigger.AlarmDismissed -> if (en) "After my alarm" else "Après mon cadran"
            is RoutineTrigger.Unlock -> if (en) "First unlock" else "Premier déverrouillage"
            is RoutineTrigger.Device -> when (t.type) {
                DeviceKind.HEADSET -> if (t.connect) (if (en) "Headphones on" else "Écouteurs branchés") else (if (en) "Headphones off" else "Écouteurs débranchés")
                DeviceKind.CAR -> if (t.connect) (if (en) "In the car" else "Dans l'auto") else (if (en) "Out of the car" else "Sortie de l'auto")
            }
            is RoutineTrigger.Charging -> if (t.start) (if (en) "Charging" else "Recharge") else (if (en) "Unplugged" else "Débranché")
            RoutineTrigger.Asked -> if (en) "My routine" else "Ma routine"
        }
    }

    // ------------------------------------------------------------------ times and days

    private class Days(val days: Set<DayOfWeek>, val end: Int, val period: Period?, val lang: BriefLang)

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
        return Days(set, j, per, BriefLang.EN)
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
        return Days(set, j, per, BriefLang.FR)
    }

    /** The period said in "matin de semaine" / "soirs de semaine", looking back from after it. */
    private fun semainePeriod(p: Words, after: Int): Period? = when {
        p.w(after - 3)?.startsWith("matin") == true -> Period.AM
        p.w(after - 3)?.startsWith("soir") == true -> Period.PM
        else -> null
    }

    private enum class Kind { EXPLICIT, H24, BARE }

    /** A clock time as said: [h] already on 24 hours when [kind] is EXPLICIT (am/pm, noon). */
    private class Clock(val h: Int, val m: Int, val kind: Kind, val end: Int, val said: String)

    private val CLOCK_TOKEN = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(am|pm|h)?(\d{2})?$""")

    private fun clock(p: Words, i: Int): Clock? {
        val w = p.w(i) ?: return null
        when (w) {
            "noon", "midi" -> return Clock(12, 0, Kind.EXPLICIT, i + 1, p.span(i, i + 1))
            "midnight", "minuit" -> return Clock(0, 0, Kind.EXPLICIT, i + 1, p.span(i, i + 1))
        }
        val word = HOUR_WORDS.indexOf(w)
        if (word >= 0) {
            var j = i + 1
            if (p.w(j) == "oclock") j++
            return finishAmPm(p, word + 1, 0, j, i, Kind.BARE)
        }
        val m = CLOCK_TOKEN.matchEntire(w) ?: return null
        val h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: 0
        var j = i + 1
        when (m.groupValues[3]) {
            "am", "pm" -> return amPm(h, min, m.groupValues[3], j, p.span(i, j))
            "h" -> {
                if (m.groupValues[4].isEmpty() && p.w(j)?.matches(Regex("""\d{2}""")) == true) { min = p.w(j)!!.toInt(); j++ }
                return valid(h, min, Kind.H24, j, p.span(i, j))
            }
        }
        if (p.at(j, "h", "heure", "heures") > 0) {
            j++
            if (p.w(j)?.matches(Regex("""\d{1,2}""")) == true) { min = p.w(j)!!.toInt(); j++ }
            return valid(h, min, Kind.H24, j, p.span(i, j))
        }
        if (p.w(j) == "oclock") j++
        val h24 = h >= 13 || h == 0 || (m.groupValues[2].isNotEmpty() && m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0"))
        return finishAmPm(p, h, min, j, i, if (h24) Kind.H24 else Kind.BARE)
    }

    private fun finishAmPm(p: Words, h: Int, min: Int, j: Int, i: Int, kind: Kind): Clock? {
        val ap = p.at(j, "am", "pm", "a m", "p m")
        if (ap > 0) return amPm(h, min, if (p.w(j)!!.startsWith("a")) "am" else "pm", j + ap, p.span(i, j + ap))
        return valid(h, min, kind, j, p.span(i, j))
    }

    private fun amPm(h: Int, min: Int, ap: String, end: Int, said: String): Clock? {
        if (h !in 1..12 || min !in 0..59) return null
        val h24 = if (ap == "am") (if (h == 12) 0 else h) else (if (h == 12) 12 else h + 12)
        return Clock(h24, min, Kind.EXPLICIT, end, said)
    }

    private fun valid(h: Int, min: Int, kind: Kind, end: Int, said: String): Clock? =
        if (h in 0..23 && min in 0..59) Clock(h, min, kind, end, said) else null

    /** "in the morning", "at night", "du matin", "du soir", "de l'après-midi", "le soir" after a time. */
    private val CLOCK_PERIOD = mapOf("in the morning" to Period.AM, "in the evening" to Period.PM, "at night" to Period.PM, "in the afternoon" to Period.PM,
        "tonight" to Period.PM, "du matin" to Period.AM, "du soir" to Period.PM, "de l apres midi" to Period.PM, "le matin" to Period.AM,
        "le soir" to Period.PM)

    /**
     * The time [c] means. A bare 12-hour time ("at 7") takes the period said ("every morning", "in the evening"), else
     * 6 to 11 are morning hours, 12 is noon and 1 to 5 afternoon ones ("every Friday at 5" is 17:00); the card then says
     * how it was read ([note]).
     */
    private fun resolve(c: Clock, per: Period?, notes: MutableList<String>): LocalTime {
        var h = c.h
        when (c.kind) {
            Kind.EXPLICIT -> Unit
            Kind.H24 -> if (per == Period.PM && h in 1..11) h += 12
            Kind.BARE -> when (per) {
                Period.AM -> if (h == 12) h = 0
                Period.PM -> if (h < 12) h += 12
                null -> {
                    if (h in 1..5) h += 12
                    notes += "Read “${c.said}” as ${String.format(Locale.US, "%02d:%02d", h, c.m)}; change it in Routines if that's wrong."
                }
            }
        }
        return LocalTime.of(h, c.m)
    }

    private fun clockPeriod(p: Words, j: Int): Pair<Period?, Int> {
        val n = p.at(j, CLOCK_PERIOD.keys)
        return if (n == 0) null to 0 else CLOCK_PERIOD.getValue((j until j + n).joinToString(" ") { p.w(it)!! }) to n
    }

    /** "every weekday at 7", "on weekends at 9:30", "chaque matin de semaine à 7 h", "à 7 h tous les jours". */
    private fun timeTrigger(p: Words, i: Int): TriggerRead? {
        val notes = mutableListOf<String>()
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
        p.at(i, ALARM_EN_SUBJECT).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), i + it, BriefLang.EN) }
        val we = p.at(i, WHEN_EN)
        if (we > 0) {
            val j = i + we
            p.at(j, ALARM_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), j + it, BriefLang.EN) }
            p.at(j, UNLOCK_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Unlock(), j + it, BriefLang.EN) }
            val connect = p.at(j, "connect to", "connect", "plug in", "put on", "pair", "turn on")
            val disconnect = p.at(j, "disconnect from", "disconnect", "unplug", "take off", "turn off")
            for ((n, on) in listOf(connect to true, disconnect to false)) if (n > 0) {
                p.at(j + n, HEADSET_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET, connect = on), j + n + it, BriefLang.EN) }
                p.at(j + n, CAR_EN).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = on), j + n + it, BriefLang.EN) }
            }
            p.at(j, "get in the car", "get in my car", "get into the car", "get into my car").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR), j + it, BriefLang.EN)
            }
            p.at(j, "get out of the car", "get out of my car", "leave the car").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = false), j + it, BriefLang.EN)
            }
            for ((list, start) in listOf(CHARGE_EN to true, UNPLUG_EN to false)) {
                val n = p.at(j, list)
                if (n > 0) return charging(p, j + n, start, BriefLang.EN)
            }
            return null
        }
        // "when my headphones connect", "when the car disconnects".
        if (p.w(i) == "when") for ((list, kind) in listOf(HEADSET_EN to DeviceKind.HEADSET, CAR_EN to DeviceKind.CAR)) {
            val n = p.at(i + 1, list)
            if (n == 0) continue
            val v = p.at(i + 1 + n, "connect", "connects", "disconnect", "disconnects")
            if (v > 0) return TriggerRead(RoutineTrigger.Device(kind, connect = p.w(i + 1 + n)!!.startsWith("connect")), i + 1 + n + v, BriefLang.EN)
        }
        // French.
        p.at(i, ALARM_FR_SUBJECT).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), i + it, BriefLang.FR) }
        val wf = p.at(i, WHEN_FR)
        if (wf > 0) {
            val j = i + wf
            p.at(j, ALARM_FR_VERB).takeIf { it > 0 }?.let { v ->
                p.at(j + v, ALARM_FR_OBJ).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.AlarmDismissed(window), j + v + it, BriefLang.FR) }
            }
            p.at(j, UNLOCK_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Unlock(), j + it, BriefLang.FR) }
            p.at(j, "branche", "connecte", "mets").takeIf { it > 0 }?.let { v ->
                p.at(j + v, HEADSET_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET), j + v + it, BriefLang.FR) }
            }
            p.at(j, "debranche", "deconnecte", "enleve").takeIf { it > 0 }?.let { v ->
                p.at(j + v, HEADSET_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.HEADSET, connect = false), j + v + it, BriefLang.FR) }
            }
            p.at(j, "me connecte", "embarque", "monte", "rentre").takeIf { it > 0 }?.let { v ->
                p.at(j + v, CAR_FR).takeIf { it > 0 }?.let { return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR), j + v + it, BriefLang.FR) }
            }
            p.at(j, "sors de l auto", "sors du char", "sors de la voiture", "debarque de l auto", "debarque du char").takeIf { it > 0 }?.let {
                return TriggerRead(RoutineTrigger.Device(DeviceKind.CAR, connect = false), j + it, BriefLang.FR)
            }
            for ((list, start) in listOf(CHARGE_FR to true, UNPLUG_FR to false)) {
                val n = p.at(j, list)
                if (n > 0) return charging(p, j + n, start, BriefLang.FR)
            }
            return null
        }
        return null
    }

    /** Charging starts or stops; "at night" or "le soir" after it: only between 18:00 and 04:00. */
    private fun charging(p: Words, j: Int, start: Boolean, lang: BriefLang): TriggerRead {
        val night = p.at(j, NIGHT_WORDS)
        val morning = p.at(j, "in the morning", "le matin")
        val cond = when {
            night > 0 -> RoutineConditions(between = TimeWindow.NIGHT)
            morning > 0 -> RoutineConditions(between = TimeWindow.ALARM)
            else -> RoutineConditions()
        }
        return TriggerRead(RoutineTrigger.Charging(start), j + maxOf(night, morning), lang, cond)
    }

    // ------------------------------------------------------------------ steps

    private class Acts(val steps: List<RoutineStep>, val unparsed: List<String>, val refused: List<String>, val notes: List<String>, val commandLike: Boolean)

    /** Between steps: ",", "and", "then", "et", "pis", "puis", "ensuite". */
    private val SEP = setOf(",", "and", "then", "also", "et", "pis", "puis", "ensuite", "aussi")
    private val COMMAND = setOf("tell", "give", "read", "say", "set", "start", "open", "turn", "launch", "play", "text", "send", "show", "check",
        "call", "remind", "put", "switch", "wake", "search", "find", "navigate", "let", "whats", "what", "dis", "donne", "lis", "regle", "mets", "met",
        "ouvre", "allume", "eteins", "lance", "joue", "envoie", "appelle", "montre", "rappelle", "demarre", "programme", "annonce", "verifie",
        "cherche", "texte", "ecris", "fais")

    private fun actions(p: Words, from: Int): Acts {
        // Pieces between separators.
        val pieces = mutableListOf<Pair<Int, Int>>()
        var start = from
        for (j in from..p.size) {
            if (j == p.size || p.w(j) in SEP) {
                if (j > start) pieces += start to j
                start = j + 1
            }
        }
        val steps = mutableListOf<RoutineStep>()
        val unparsed = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var k = 0
        while (k < pieces.size) {
            val (a, b) = pieces[k]
            when (val r = piece(p, a, b, notes)) {
                is Piece.Step -> steps += r.step
                Piece.Refused -> refused += p.span(a, b)
                Piece.Unknown -> unparsed += p.span(a, b)
                is Piece.Say -> {
                    // A sentence runs on while what follows isn't a step of its own (or refused): "say good night and sweet
                    // dreams, then turn off the flashlight" says "good night and sweet dreams", exactly as on the card.
                    var end = b
                    while (k + 1 < pieces.size && piece(p, pieces[k + 1].first, pieces[k + 1].second, mutableListOf()) == Piece.Unknown) {
                        k++
                        end = pieces[k].second
                    }
                    val text = p.span(r.from, end).trim()
                    if (text.length > RoutineCheck.MAX_SAY) unparsed += p.span(a, end) else steps += RoutineStep.Say(text)
                }
            }
            k++
        }
        val first = pieces.firstOrNull()?.let { p.w(it.first) }
        return Acts(steps, unparsed, refused, notes, first in COMMAND)
    }

    private sealed class Piece {
        data class Step(val step: RoutineStep) : Piece()
        /** "say …": the sentence starts at word [from]. */
        data class Say(val from: Int) : Piece()
        data object Refused : Piece()
        data object Unknown : Piece()
    }

    private val LEAD = listOf("please", "also", "and", "then", "s il te plait", "aussi")
    private val NEVER = listOf("free up", "free memory", "free storage", "free space", "free some space", "clear the cache", "clear cache", "clean up",
        "delete", "erase", "wipe", "uninstall", "remove", "ask claude", "ask cloud", "ask clyde", "ask claud", "ask clod", "get claude",
        "libere", "liberer", "vide le cache", "supprime", "supprimer", "efface", "effacer", "nettoie", "desinstalle", "demande a claude",
        "demande a cloud", "va chercher claude")
    private val READ_VERBS = listOf("tell me about", "tell me", "give me", "read me", "read out", "read", "let me know", "show me", "whats on", "what is on",
        "whats", "what is", "check", "how is", "hows", "dis moi", "donne moi", "lis moi", "lis", "annonce moi", "c est quoi", "quelle est", "quel est",
        "verifie", "montre moi")

    private fun piece(p: Words, from: Int, to: Int, notes: MutableList<String>): Piece {
        var a = from
        while (a < to) { val n = p.at(a, LEAD); if (n == 0) break; a += n }
        if (a >= to) return Piece.Unknown
        if ((a until to).any { p.at(it, NEVER) in 1..(to - it) }) return Piece.Refused
        action(p, a, to, notes)?.let { return Piece.Step(it) }
        val v = p.at(a, READ_VERBS)
        read(p, a + v, to)?.let { return Piece.Step(it) }
        if (v == 0 && p.at(a, "say", "dis") == 1 && a + 1 < to) return Piece.Say(a + 1)
        return Piece.Unknown
    }

    private fun tool(name: String, vararg args: Pair<String, Any?>): RoutineStep? =
        (ToolCalls.parse(RawToolCall(name, args.filter { it.second != null }.toMap())) as? ParsedCall.Ok)?.let { RoutineStep.Tool(it.action) }

    private val ALARM_STEP = listOf("set an alarm for", "set an alarm at", "set the alarm for", "set the alarm at", "set my alarm for", "set my alarm at",
        "set alarm for", "set alarm at", "wake me up at", "wake me at", "regle un reveil a", "regle un reveil pour", "regle le reveil a", "regle mon reveil a",
        "mets un reveil a", "mets un reveil pour", "mets le reveil a", "mets un cadran a", "regle un cadran a", "regle mon cadran a", "regle le cadran a",
        "mets le cadran a", "mets mon cadran a", "regle une alarme a", "mets une alarme a", "programme un reveil a", "reveille moi a")
    private val TIMER_EN = listOf("set a timer for", "set the timer for", "start a timer for", "set timer for", "timer for", "a timer for")
    private val TIMER_FR = listOf("mets une minuterie de", "regle une minuterie de", "part une minuterie de", "lance une minuterie de",
        "demarre une minuterie de", "programme une minuterie de", "mets un minuteur de", "regle un minuteur de", "lance un minuteur de",
        "mets une minuterie pour", "minuterie de", "une minuterie de")
    private val UNITS = mapOf("minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60, "hour" to 3600, "hours" to 3600, "heure" to 3600,
        "heures" to 3600, "second" to 1, "seconds" to 1, "seconde" to 1, "secondes" to 1)
    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "une" to 1, "un" to 1, "deux" to 2, "trois" to 3, "cinq" to 5, "dix" to 10, "quinze" to 15, "vingt" to 20, "trente" to 30)
    private val LIGHT_ON = listOf("turn on the flashlight", "turn on the torch", "turn on the light", "turn the flashlight on", "turn the torch on",
        "switch on the flashlight", "flashlight on", "torch on", "allume la lampe de poche", "allume la lampe", "allume le flash", "ouvre la lampe de poche",
        "ouvre la lampe")
    private val LIGHT_OFF = listOf("turn off the flashlight", "turn off the torch", "turn off the light", "turn the flashlight off", "turn the torch off",
        "switch off the flashlight", "flashlight off", "torch off", "eteins la lampe de poche", "eteins la lampe", "eteins le flash", "ferme la lampe de poche",
        "ferme la lampe")
    private val NAVIGATE = listOf("directions to", "give me directions to", "get directions to", "navigate to", "the route to", "route to", "take me to",
        "itineraire vers", "itineraire pour", "itineraire jusqu a", "amene moi a", "emmene moi a", "amene moi au", "emmene moi au")
    private val SEARCH = listOf("search maps for", "search organic maps for", "search the map for", "cherche sur la carte")
    private val OPEN = listOf("open up", "open", "launch", "start", "ouvre", "ouvrir", "lance", "demarre")

    /** A step that does something: an alarm, a timer, the flashlight, maps, a text, an app. */
    private fun action(p: Words, a: Int, to: Int, notes: MutableList<String>): RoutineStep? {
        p.at(a, ALARM_STEP).takeIf { it > 0 }?.let { n ->
            val c = clock(p, a + n) ?: return null
            val (per, pn) = clockPeriod(p, c.end)
            if (c.end + pn != to) return null
            val t = resolve(c, per, notes)
            return tool("set_alarm", "hour" to t.hour, "minute" to t.minute)
        }
        timer(p, a, to)?.let { return it }
        if (p.at(a, LIGHT_ON) == to - a) return tool("flashlight", "on" to true)
        if (p.at(a, LIGHT_OFF) == to - a) return tool("flashlight", "on" to false)
        p.at(a, NAVIGATE).takeIf { it > 0 && a + it < to }?.let { return tool("maps_navigate", "destination" to p.span(a + it, to)) }
        p.at(a, SEARCH).takeIf { it > 0 && a + it < to }?.let { return tool("maps_search", "query" to p.span(a + it, to)) }
        if (p.at(a, "text", "send a text to", "send a message to", "message", "let") > 0) {
            val req = TextCommand.parse(p.span(a, to), typed = false) ?: return null
            return tool("send_text", "to" to req.name, "message" to (req.message ?: return null))
        }
        p.at(a, OPEN).takeIf { it > 0 && a + it < to }?.let { n ->
            if (p.at(a + n, "a timer", "the timer", "une minuterie", "un minuteur") > 0) return null
            var s = a + n
            while (p.w(s) in setOf("the", "my", "l", "la", "le", "mon", "ma") && s + 1 < to) s++
            var e = to
            if (p.w(e - 1) in setOf("app", "application", "appli") && e - 1 > s) e--
            return tool("open_app", "name" to p.span(s, e))
        }
        return null
    }

    private fun timer(p: Words, a: Int, to: Int): RoutineStep? {
        fun seconds(at: Int): Pair<Long, Int>? {
            val n = p.w(at)?.toIntOrNull() ?: NUMBER_WORDS[p.w(at)] ?: return null
            val unit = UNITS[p.w(at + 1)] ?: return null
            return n.toLong() * unit to at + 2
        }
        val lead = p.at(a, TIMER_EN + TIMER_FR)
        if (lead > 0) {
            val (s, end) = seconds(a + lead) ?: return null
            if (end != to) return null
            return tool("set_timer", "seconds" to s)
        }
        // "start a 10 minute timer".
        val pre = p.at(a, "set a", "start a", "set an", "start an")
        if (pre > 0) {
            val (s, end) = seconds(a + pre) ?: return null
            if (p.w(end) != "timer" || end + 1 != to) return null
            return tool("set_timer", "seconds" to s)
        }
        return null
    }

    private val DET = setOf("my", "the", "a", "an", "mon", "ma", "mes", "la", "le", "les", "l", "un", "une", "about")
    private val WEATHER_WORDS = listOf("weather", "forecast", "weather forecast", "weather report", "temperature", "meteo", "previsions",
        "previsions meteo", "temps qu il fait", "temps quil fait")
    private val DAY_TAIL = mapOf(
        "today" to "today", "for today" to "today", "for the day" to "today", "tomorrow" to "tomorrow", "for tomorrow" to "tomorrow",
        "this weekend" to "this weekend", "for the weekend" to "this weekend", "for this weekend" to "this weekend", "the weekend" to "this weekend",
        "on the weekend" to "this weekend", "this week" to "this week", "for the week" to "this week", "for this week" to "this week",
        "aujourdhui" to "today", "d aujourdhui" to "today", "pour aujourdhui" to "today", "de la journee" to "today", "pour la journee" to "today",
        "demain" to "tomorrow", "de demain" to "tomorrow", "pour demain" to "tomorrow", "de la fin de semaine" to "this weekend",
        "pour la fin de semaine" to "this weekend", "la fin de semaine" to "this weekend", "en fin de semaine" to "this weekend",
        "cette fin de semaine" to "this weekend", "du week end" to "this weekend", "pour le week end" to "this weekend", "ce week end" to "this weekend",
        "de la semaine" to "this week", "pour la semaine" to "this week", "cette semaine" to "this week")
    private val HOME = listOf("at home", "for home", "at my place", "at my house", "at the house", "home", "a la maison", "chez moi", "chez nous",
        "pour la maison", "de la maison")
    private val HERE = listOf("here", "outside", "around here", "for here", "out", "ici", "dehors", "d ici")
    private val PLACE_LEAD = setOf("in", "for", "at", "a", "au", "en", "pour", "de", "dans", "near")
    private val CALENDAR = listOf("calendar", "agenda", "schedule", "day", "events", "appointments", "meetings", "next meeting", "next appointment",
        "next event", "first meeting", "calendrier", "journee", "horaire", "rendez vous", "prochain rendez vous", "prochaine reunion", "reunions",
        "evenements", "rdv", "prochain rdv")
    private val BRIEF = listOf("morning brief", "brief", "briefing", "morning briefing", "daily brief", "resume", "resume du matin", "resume de la journee",
        "bilan du matin", "resume quotidien")
    private val REMINDERS = listOf("reminders", "brief reminders", "morning reminders", "rappels", "rappels du matin")
    private val MESSAGES = listOf("messages", "unread messages", "new messages", "messages non lus", "nouveaux messages")
    private val PHONE = listOf("battery", "battery level", "phone status", "storage", "batterie", "niveau de batterie", "stockage", "etat du telephone")

    /** Something to read or say: the weather, the calendar, the brief, reminders, messages (0.7), the phone, notes. */
    private fun read(p: Words, a0: Int, to: Int): RoutineStep? {
        var a = a0
        while (a < to && p.w(a) in DET) a++
        if (a >= to) return null
        p.at(a, WEATHER_WORDS).takeIf { it > 0 }?.let { return weather(p, a + it, to) }
        p.at(a, BRIEF).takeIf { it == to - a }?.let { return RoutineStep.Brief }
        p.at(a, REMINDERS).takeIf { it == to - a }?.let { return RoutineStep.Reminders }
        p.at(a, MESSAGES).takeIf { it == to - a }?.let { return RoutineStep.Messages }
        p.at(a, PHONE).takeIf { it == to - a }?.let { return tool("phone_status", "about" to p.span(a, to)) }
        p.at(a, CALENDAR).takeIf { it > 0 }?.let { n ->
            val rest = a + n
            if (rest == to) return tool("read_calendar", "day" to "today")
            val d = p.at(rest, DAY_TAIL.keys)
            if (d > 0 && rest + d == to) return tool("read_calendar", "day" to DAY_TAIL.getValue(key(p, rest, d)))
            return null
        }
        if (p.at(a, "notes about", "notes on", "notes sur", "notes a propos de") > 0 && a + 2 < to) {
            val n = p.at(a, "notes about", "notes on", "notes sur", "notes a propos de")
            return tool("find_notes", "query" to p.span(a + n, to))
        }
        return null
    }

    private fun key(p: Words, at: Int, n: Int): String = (at until at + n).joinToString(" ") { p.w(it)!! }

    /** The weather's place and day after "the weather": here, home, a named town, today, tomorrow, the weekend. */
    private fun weather(p: Words, from: Int, to: Int): RoutineStep? {
        var j = from
        var place: String? = null
        var day: String? = null
        while (j < to) {
            val d = p.at(j, DAY_TAIL.keys)
            if (d > 0) { day = DAY_TAIL.getValue(key(p, j, d)); j += d; continue }
            val h = p.at(j, HOME)
            if (h > 0) { place = "home"; j += h; continue }
            val here = p.at(j, HERE)
            if (here > 0) { place = null; j += here; continue }
            if (p.w(j) in PLACE_LEAD && j + 1 < to) {
                // The town runs to the end of the piece, or to a day said after it ("in Quebec City tomorrow").
                var e = to
                for (k in j + 2 until to) if (p.at(k, DAY_TAIL.keys) == to - k) { e = k; break }
                place = p.span(j + 1, e)
                j = e
                continue
            }
            return null
        }
        return tool("weather", "place" to place, "day" to day)
    }
}

/**
 * 1.0 audit (physical-routines-writable-locked): routines and brief reminders are instructions that run later by
 * themselves, in Robot's own voice, so the locked phone neither changes nor lists them ("Unlock first"), as memory's
 * writes: someone holding it (or a TV in the room) can't plant a routine, delete yours, add a reminder your brief reads,
 * or hear what your routines are. The brief and running a routine keep their own lock rules.
 */
object RoutineLockedPhone {
    const val UNLOCK_CHANGE = "Unlock first: routines are only changed with the phone unlocked."
    const val UNLOCK_LIST = "Unlock first: your routines are only listed with the phone unlocked."
    const val UNLOCK_REMIND = "Unlock first: reminders for your brief are only added with the phone unlocked."
    const val UNLOCK_CHANGE_FR = "Déverrouille d'abord : les routines ne se changent que téléphone déverrouillé."
    const val UNLOCK_REMIND_FR = "Déverrouille d'abord : les rappels de ton résumé ne s'ajoutent que téléphone déverrouillé."

    /** What Robot says instead of [cmd] on a [locked] phone; null when it may go on. */
    fun refuse(cmd: RoutineCommand, locked: Boolean, fr: Boolean = false): String? = if (!locked) null else when (cmd) {
        is RoutineCommand.Create, is RoutineCommand.Delete -> if (fr) UNLOCK_CHANGE_FR else UNLOCK_CHANGE
        is RoutineCommand.Remind -> if (fr) UNLOCK_REMIND_FR else UNLOCK_REMIND
        RoutineCommand.ListAll -> UNLOCK_LIST
        RoutineCommand.BriefNow, is RoutineCommand.Run -> null
    }
}
