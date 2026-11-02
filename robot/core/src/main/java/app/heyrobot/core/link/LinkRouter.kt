// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.Read
import app.heyrobot.core.WhenParser
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.i18n.Phrase
import java.text.Normalizer
import java.time.ZonedDateTime
import java.util.Locale

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.3, D15; job L7): your words to another Robot, in English and Québec French,
// heard without the model. The panel asks it after Robot Files' router and before hands-free (PanelController); it claims only a
// phrase that names someone's Robot ("ask Sam's robot …", « demande au robot de Sam … ») or a Robot to Robot verb with someone
// you're paired with ([paired] says who is), so "is the pool free Friday" or "plan a trip with Mom" (not paired) stays ordinary
// talk, and texting, notes, files, skills, routines, the web and hands-free keep their own words (LinkRouterTest's misfires).
//
//   Free/busy   "ask Sam's robot when Sam is free Friday evening", "is Sam free tomorrow", "when is Sam free next week";
//               « demande au robot de Sam s'il est libre vendredi soir », « est-ce que Sam est libre demain »,
//               « quand est-ce que Sam est libre »
//   A plan      "propose dinner with Sam Friday at 7 or Saturday at 6", "invite Sam to lunch tomorrow at noon"; « propose un
//               souper avec Sam vendredi à 19 h ou samedi à 18 h », « invite Sam à dîner demain à midi ». Only with a clock
//               time in the words (else it isn't a plan for another Robot).
//   Cancel      "cancel my plan with Sam", "cancel dinner with Sam"; « annule mon plan avec Sam »
//   Tighten     "stop answering Sam on your own", « arrête de répondre à Sam tout seul »: done at once (§4.2), never a card
//   L10         "tell Sam I'm 10 minutes away", « dis à Sam que je suis à 10 minutes » (an ETA: only with an amount);
//               "remind Sam to bring the charger", « rappelle à Sam d'apporter le chargeur »; "send Sam Mom's contact",
//               « envoie à Sam le contact de maman »; "ask Sam's robot where the party is", « demande au robot de Sam où est la fête »
//   Not yet     "ask Mom's robot for the lasagna recipe": a request Robot to Robot can't make yet is said so, never guessed
//
// Each sends nothing itself: a request becomes one ask_robot call of your own words (the card shows exactly what goes), and
// tightening goes through LinkService's SET_LEVEL.

/** What your words ask of another Robot. [who]: the person as you named them (your words, never logged). */
sealed class LinkCommand {
    abstract val who: String
    abstract val lang: Lang

    /** An ask_robot request: [details] are the words its kind reads (LinkAsk). */
    data class Ask(override val who: String, val kind: LinkAskKind, val details: String, override val lang: Lang) : LinkCommand() {
        override fun toString(): String = "Ask($kind, $lang)" // l10n: not text (never your words in a log)
    }

    /** "Stop answering Sam on your own": every capability Robot answers on its own for them goes back to Ask me first. */
    data class StopAlone(override val who: String, override val lang: Lang) : LinkCommand() {
        override fun toString(): String = "StopAlone($lang)" // l10n: not text (never your words in a log)
    }

    /** Something asked of another Robot that Robot to Robot can't ask yet (a recipe, a file: later jobs). */
    /** L16: their Robot named, and you aren't paired with it. */
    data class NotPaired(override val who: String, override val lang: Lang) : LinkCommand() {
        override fun toString(): String = "NotPaired($lang)" // l10n: not text (never your words in a log)
    }

    data class NotYet(override val who: String, override val lang: Lang) : LinkCommand() {
        override fun toString(): String = "NotYet($lang)" // l10n: not text (never your words in a log)
    }
}

object LinkRouter {
    private val LEAD = listOf("hey robot, ", "robot, ", "hey robot ", "robot ", "please ", "can you ", "could you ", "would you ", "s'il te plait ", "stp ", "peux-tu ", "peux tu ", // l10n: not text (words Robot hears)
        "est-ce que tu peux ", "ok ", "okay ") // l10n: not text (words Robot hears)
    private val TAIL = listOf(" please", " s'il te plait", " stp", " for me", " pour moi") // l10n: not text (words Robot hears)

    // A name: one to three words of letters ("Sam", "Marie-Ève", "Uncle Bob"); a word never ends on an apostrophe or a hyphen,
    // so "Sam's robot" reads as Sam.
    private const val WORD = "\\p{L}(?:[\\p{L}'-]*\\p{L})?" // l10n: not text (a parser's pattern)
    private const val NAME = "($WORD(?: $WORD){0,2}?)" // l10n: not text (a parser's pattern)
    private const val ROBOT_EN = "(?:'s|s'|s) robot" // l10n: not text (a parser's pattern)
    private const val ROBOT_FR = "robot (?:de |d')" // l10n: not text (a parser's pattern)
    private const val FREE_EN = "(?:free|available)" // l10n: not text (a parser's pattern)
    private const val FREE_FR = "(?:libre|libres|disponible|disponibles|dispo)" // l10n: not text (a parser's pattern)

    private enum class Needs { ROBOT, PAIRED }

    private class Rule(val re: Regex, val lang: Lang, val needs: Needs, val make: (who: String, rest: String, lang: Lang) -> LinkCommand?)

    private fun fb(who: String, rest: String, lang: Lang) = LinkCommand.Ask(who, LinkAskKind.FREE_BUSY, rest, lang)
    private fun stop(who: String, @Suppress("UNUSED_PARAMETER") rest: String, lang: Lang) = LinkCommand.StopAlone(who, lang)
    private fun cancel(who: String, rest: String, lang: Lang) = LinkCommand.Ask(who, LinkAskKind.CANCEL_PLAN, rest, lang)
    /** L10: an ETA only when the words say an amount and how far ("tell Mom I'm available Friday" isn't one). */
    private fun eta(who: String, rest: String, lang: Lang) = if (LinkAsk.eta(rest) is Read.Ok) LinkCommand.Ask(who, LinkAskKind.ETA, rest, lang) else null
    private fun remind(who: String, rest: String, lang: Lang) = LinkCommand.Ask(who, LinkAskKind.REMINDER, rest, lang)
    private fun question(who: String, rest: String, lang: Lang) = LinkCommand.Ask(who, LinkAskKind.QUESTION, rest, lang)
    private fun contact(who: String, rest: String, lang: Lang) = LinkCommand.Ask(who, LinkAskKind.CONTACT, rest, lang)

    /** Rules on the folded words: group 1 the name; group 2 (when present) the rest (a window, a plan). */
    private val RULES: List<Rule> = listOf(
        // Free/busy, naming their Robot.
        Rule(Regex("^ask $NAME$ROBOT_EN (?:when|if|whether) .{0,40}?\\b$FREE_EN\\b ?(.*)$"), Lang.EN, Needs.ROBOT, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^ask $NAME$ROBOT_EN (?:for|about) (?:[\\p{L}']+ )?(?:free time|free busy|free/busy|availability|busy times) ?(.*)$"), Lang.EN, Needs.ROBOT, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^demande (?:au|a) $ROBOT_FR$NAME (?:s'il|si il|s'ils|si elle|si elles|si|quand) .{0,40}?\\b$FREE_FR\\b ?(.*)$"), Lang.FR, Needs.ROBOT, ::fb), // l10n: not text (a parser's pattern)
        // Free/busy, a person you're paired with.
        Rule(Regex("^(?:is|are) $NAME $FREE_EN ?(.*)$"), Lang.EN, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^is $NAME busy (.+)$"), Lang.EN, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^when (?:is|are|will) $NAME (?:be )?$FREE_EN ?(.*)$"), Lang.EN, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:check|find out|see) (?:when|if|whether) $NAME (?:is|'s|will be) $FREE_EN ?(.*)$"), Lang.EN, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:est-ce que|est ce que) $NAME (?:est|sera|serait) $FREE_FR ?(.*)$"), Lang.FR, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^$NAME (?:est-il|est-elle|est il|est elle) $FREE_FR ?(.*)$"), Lang.FR, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^quand (?:est-ce que |est ce que )?$NAME (?:est|sera) $FREE_FR ?(.*)$"), Lang.FR, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        Rule(Regex("^$NAME est $FREE_FR (.+)$"), Lang.FR, Needs.PAIRED, ::fb), // l10n: not text (a parser's pattern)
        // Tighten: at once.
        Rule(Regex("^stop answering $NAME(?:$ROBOT_EN)? (?:on your own|by yourself|automatically)$"), Lang.EN, Needs.PAIRED, ::stop), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:don't|dont|do not) answer $NAME(?:$ROBOT_EN)? (?:on your own|by yourself|automatically)(?: anymore| any more)?$"), Lang.EN, Needs.PAIRED, ::stop), // l10n: not text (a parser's pattern)
        Rule(Regex("^arrete de repondre (?:a |au $ROBOT_FR)$NAME (?:tout seul|toute seule|par toi-meme|automatiquement)$"), Lang.FR, Needs.PAIRED, ::stop), // l10n: not text (a parser's pattern)
        Rule(Regex("^arrete de repondre (?:tout seul|toute seule|automatiquement) (?:a |au $ROBOT_FR)$NAME$"), Lang.FR, Needs.PAIRED, ::stop), // l10n: not text (a parser's pattern)
        Rule(Regex("^ne reponds plus (?:a |au $ROBOT_FR)$NAME (?:tout seul|toute seule|automatiquement)$"), Lang.FR, Needs.PAIRED, ::stop), // l10n: not text (a parser's pattern)
        // L10: an ETA, a reminder, a contact card to someone paired; a question naming their Robot.
        Rule(Regex("^(?:tell|let) $NAME (?:know )?(?:that )?((?:i'm|i am|im|i'll|i will|ill|we're|we are|we'll|we will) .+)$"), Lang.EN, Needs.PAIRED, ::eta), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:dis|previens|avertis) (?:a |au )?$NAME (?:que |qu')((?:je |j'|on |nous ).+)$"), Lang.FR, Needs.PAIRED, ::eta), // l10n: not text (a parser's pattern)
        Rule(Regex("^remind $NAME (?:to|that|about|of) (.+)$"), Lang.EN, Needs.PAIRED, ::remind), // l10n: not text (a parser's pattern)
        Rule(Regex("^rappelle (?:a |au )$NAME (?:de |d'|qu'|que )(.+)$"), Lang.FR, Needs.PAIRED, ::remind), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:send|share|give) $NAME (?:the |a )?(?:contact|contact card|card|number|details) (?:for|of) (.+)$"), Lang.EN, Needs.PAIRED, ::contact), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:send|share|give) $NAME (.+?)(?:'s|s') (?:contact|contact card|card|number|details|info)$"), Lang.EN, Needs.PAIRED, ::contact), // l10n: not text (a parser's pattern)
        Rule(Regex("^(?:envoie|partage|donne) (?:a |au )$NAME (?:le contact|la fiche|le numero|les coordonnees) (?:de |d')(.+)$"), Lang.FR, Needs.PAIRED, ::contact), // l10n: not text (a parser's pattern)
        Rule(Regex("^ask $NAME$ROBOT_EN ((?:what|what's|whats|where|where's|when|who|why|how|which|whether|if|is|are|do|does|did|can|could|should|will|would|has|have)\\b.+)$"), Lang.EN, Needs.ROBOT, ::question), // l10n: not text (a parser's pattern)
        Rule(Regex("^demande (?:au|a) $ROBOT_FR$NAME ((?:ou|quand|qui|pourquoi|comment|quel|quelle|quels|quelles|combien|si|s'il|s'ils|s'elle|s'elles|ce que|ce qu'|est-ce que)\\b.+)$"), Lang.FR, Needs.ROBOT, ::question), // l10n: not text (a parser's pattern)
    )

    /** Plans: group 1 the title words, group 2 the name, group 3 the times (or group 1 the name, group 2 title and times). */
    private val PLAN_TITLE_FIRST = listOf(
        Regex("^(?:propose|suggest|plan) (.{1,60}?) with (.+)$") to Lang.EN, // l10n: not text (a parser's pattern)
        Regex("^(?:propose|suggere|planifie) (.{1,60}?) (?:avec|a) (.+)$") to Lang.FR, // l10n: not text (a parser's pattern)
    )
    private val PLAN_NAME_FIRST = listOf(
        Regex("^invite $NAME (?:to|for) (.+)$") to Lang.EN, // l10n: not text (a parser's pattern)
        Regex("^ask $NAME$ROBOT_EN (?:to plan|for|about) (.+)$") to Lang.EN, // l10n: not text (a parser's pattern)
        Regex("^invite $NAME (?:a|au|aux|pour) (.+)$") to Lang.FR, // l10n: not text (a parser's pattern)
    )
    private val CANCEL_EN = Regex("^cancel (?:my |the |our )?(.{1,40}?) with $NAME(?:$ROBOT_EN)?$") // l10n: not text (a parser's pattern)
    private val CANCEL_FR = Regex("^annule (?:mon |le |notre |nos |mes |la )?(.{1,40}?) avec $NAME$") // l10n: not text (a parser's pattern)
    private val OWN_ROBOT = Regex("(?i)(?:'s|s'|’s) robot$") // l10n: not text (a parser's pattern)
    /** Anything else asked of a Robot you name: "ask Mom's robot for …", « demande au robot de maman … ». */
    private val ASK_ROBOT_EN = Regex("^ask $NAME$ROBOT_EN .+$") // l10n: not text (a parser's pattern)
    private val ASK_ROBOT_FR = Regex("^demande (?:au|a) $ROBOT_FR$NAME .+$") // l10n: not text (a parser's pattern)

    /**
     * [text] as a Robot to Robot command at [now], or null when it isn't one. [paired] says whether words name someone you're
     * paired with (LinkPeople.isPaired over your People and pairings).
     */
    fun route(text: String, now: ZonedDateTime, paired: (String) -> Boolean): LinkCommand? {
        val (orig, f) = trimmed(text) ?: return null
        if (!f.contains("robot") && !f.contains(' ')) return null
        fun cut(r: IntRange) = orig.substring(r.first, r.last + 1).trim()
        // Plans first: their words hold a person and times, and only a clock time makes them a plan.
        for ((re, lang) in PLAN_TITLE_FIRST) re.find(f)?.let { m ->
            // "with Uncle Bob Friday at 7": the longest run of up to 3 words that names someone paired (or their Robot), then times.
            val after = m.groups[2]!!.range.first
            val words = Regex("\\S+").findAll(f.substring(after)).map { (it.range.first + after)..(it.range.last + after) }.toList()
            for (n in minOf(3, words.size - 1) downTo 1) {
                var end = words[n - 1].last
                var restFrom = n
                val robot = f.substring(words[n - 1].first, end + 1).let { it.endsWith("'s") || it.endsWith("s'") } &&
                    words.getOrNull(n)?.let { f.substring(it.first, it.last + 1) == "robot" } == true
                if (robot) { restFrom = n + 1; end = words[n - 1].last - 2 }
                if (restFrom >= words.size) continue
                val who = orig.substring(words[0].first, end + 1).trim()
                val rest = orig.substring(words[restFrom].first).trim()
                if ((robot || paired(who)) && hasClock(rest, now, lang)) return LinkCommand.Ask(who, LinkAskKind.PLAN, cut(m.groups[1]!!.range) + " " + rest, lang)
            }
        }
        for ((re, lang) in PLAN_NAME_FIRST) re.find(f)?.let { m ->
            val who = cut(m.groups[1]!!.range)
            val named = re.pattern.contains("robot")
            val rest = cut(m.groups[2]!!.range)
            if ((named || paired(who)) && hasClock(rest, now, lang)) return LinkCommand.Ask(who, LinkAskKind.PLAN, rest, lang)
        }
        for ((re, lang) in listOf(CANCEL_EN to Lang.EN, CANCEL_FR to Lang.FR)) re.find(f)?.let { m ->
            val who = cut(m.groups[2]!!.range)
            if (paired(who)) return cancel(who, cut(m.groups[1]!!.range), lang)
        }
        for (rule in RULES) {
            val m = rule.re.find(f) ?: continue
            // A name the pattern read with its Robot ("Sam's robot"): the person is Sam.
            val who = cut(m.groups[1]!!.range).replace(OWN_ROBOT, "")
            if (rule.needs == Needs.PAIRED && !paired(who)) continue
            if (rule.needs == Needs.ROBOT && !paired(who) && !nameLike(who, rule.lang)) continue
            val g2: MatchGroup? = if (m.groups.size > 2) m.groups[2] else null
            val rest = if (g2 == null || g2.value.isEmpty()) "" else cut(g2.range)
            rule.make(who, rest, rule.lang)?.let { return it }
        }
        // Their Robot named, but nothing Robot to Robot can ask yet.
        for ((re, lang) in listOf(ASK_ROBOT_EN to Lang.EN, ASK_ROBOT_FR to Lang.FR)) re.find(f)?.let { m ->
            val who = cut(m.groups[1]!!.range)
            if (paired(who)) return LinkCommand.NotYet(who, lang)
            if (nameLike(who, lang)) return LinkCommand.NotPaired(who, lang)
        }
        return null
    }

    /**
     * rc4: [text] when you can't be paired as far as Robot can tell (Robot to Robot off, nobody paired, or not read yet): anything
     * asked of a Robot you name is [LinkCommand.NotPaired], so nothing goes to the model, a helper or anyone else.
     */
    fun named(text: String, now: ZonedDateTime): LinkCommand.NotPaired? = route(text, now) { false }?.let { LinkCommand.NotPaired(it.who, it.lang) }

    /**
     * What Robot says to [LinkCommand.NotPaired]. [on]: Robot to Robot's switch (off, the pairings can't be read). [paired]: read just
     * now, whether [who] is paired; null when it couldn't be read. Paired after all (the words came before the first read): nothing went.
     */
    fun notPairedWords(who: String, on: Boolean, paired: Boolean?): Phrase = when {
        !on -> LinkStrings.notPairedOff("name" to who)
        paired == false -> LinkStrings.notPaired("name" to who)
        else -> LinkStrings.couldntSend("name" to who)
    }

    // A robot's owner, not "ask this robot …" (read as "thi") or « demande au robot de chercher … »: for words nobody is paired under.
    private val NOT_A_NAME_EN = setOf("thi", "hi", "it") // l10n: not text (words Robot hears)
    private val NOT_A_NAME_FR = Regex("^(?:me|te|se|nous|vous|lui|leur|le|la|les|ne|y|en|ce|ca|[\\p{L}']+(?:er|ir|re|oir))(?: |$)") // l10n: not text (a parser's pattern)

    private fun nameLike(who: String, lang: Lang): Boolean {
        val f = foldSame(who)
        return if (lang == Lang.FR) who.first().isUpperCase() || !NOT_A_NAME_FR.containsMatchIn(f) else f !in NOT_A_NAME_EN
    }

    /** At least one clock time in [words] (a plan needs one: "dinner with Sam on Friday" alone isn't a plan for their Robot). */
    private fun hasClock(words: String, now: ZonedDateTime, lang: Lang): Boolean = WhenParser.find(words, now, lang).any { it.clock }

    /**
     * [text] without lead-ins and tail words, both as said and folded (lower case, no accents, ’ as '), the same length so a
     * match's range on the folded words cuts the words as said. Null when nothing is left.
     */
    private fun trimmed(text: String): Pair<String, String>? {
        var o = text.replace(Regex("\\s+"), " ").trim().trimEnd('?', '!', '.', ',', ';', ':', '…').trim()
        var f = foldSame(o)
        var again = true
        while (again) {
            again = false
            for (l in LEAD) if (f.startsWith(l)) { o = o.substring(l.length); f = f.substring(l.length); again = true }
            for (t in TAIL) if (f.endsWith(t)) { o = o.substring(0, o.length - t.length); f = f.substring(0, f.length - t.length); again = true }
        }
        return if (f.isBlank()) null else o to f
    }

    /** Lower case without accents, one character for one (so ranges carry over to the words as said). */
    fun foldSame(s: String): String = buildString(s.length) {
        for (c in s) {
            val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            append(
                when {
                    c == '’' -> '\''
                    n.length == 1 -> n[0]
                    else -> c
                }
            )
        }
    }

    /** The language a command was matched in (its rule's). */
    fun lang(c: LinkCommand): Lang = c.lang
}
