// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ContactName
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.web.WebRouter
import java.text.Normalizer

// Pure. When a question goes to the copy of Wikipedia on this phone (docs/plans/wikipedia-offline-plan.md §6, job P2):
// only a question of fact, in English or French ("Who was Louis Riel?", "Tell me about the Halifax Explosion", « Qui
// était René Lévesque? », « Parle-moi de la Révolution tranquille »). Never a command, a personal question (my, our,
// I, your, mon, ma, mes…: memory and people answer those) or a fresh one (latest, news, today, who won, price of, the
// weather, the time: web search when it is on, else the model says the copy can't know). The P0 desk filter
// (wikipedia-offline-measurements.md) runs first, as there; the app's routers run before all of it.

object WikiRoute {
    enum class Verdict { FACT, PERSONAL, FRESH, COMMAND, OTHER }

    /** Politeness and wake words before the question. */
    private val POLITE = Regex(
        "^(?:(?:hey|hi|ok|okay|dis|salut) robot[,.!]?\\s+)?(?:(?:please|can you|could you|would you|will you|peux[- ]tu|pourrais[- ]tu|stp|s'il te pla[iî]t)[,]?\\s+)*",
        RegexOption.IGNORE_CASE)

    /** A command's first word (P0's list and more): the app's routers take these before any search. */
    private val COMMAND = Regex(
        "^(?:set|text|call|send|open|play|remind|turn|start|stop|add|delete|remove|cancel|show|find|search|look|google|navigate|take|read|" +
            "write|note|save|pause|resume|skip|mute|unmute|wake|mets|mettre|envoie|envoyer|appelle|appeler|ouvre|ouvrir|joue|jouer|rappelle|" +
            "ajoute|ajouter|efface|supprime|annule|montre|trouve|cherche|recherche|lis|d[ée]marre|arr[êe]te|[ée]cris|sauvegarde|baisse|monte)\\b",
        RegexOption.IGNORE_CASE)

    /** Your own things: P0's words, and you and yours (Robot itself). */
    private val PERSONAL = Regex("(?<![\\p{L}'])(?:(?:my|mine|our|ours|me|you|your|yours|mon|ma|mes|notre|nos|je|ton|ta|tes|votre|vos|toi|tu|this|these)(?![\\p{L}])|[jmt]')|(?<=^| )(?:ce|cette|ces) ",
        RegexOption.IGNORE_CASE)

    /**
     * Your own things (WebRouter's list, and what the app's routers keep: documents, receipts, warranties, skills), and
     * what someone said, wrote or thinks: messages and helpers answer those.
     */
    private val OWN = Regex(
        "(?<![\\p{L}])(?:notes|contacts|events|messages|texts|emails|photos|pictures|files|documents|receipts|warrant(?:y|ies)|subscriptions|bills|" +
            "reminders|alarms|timers|skills?|conversations|textos|courriels|fichiers|re[çc]us|factures|garanties?|abonnements|rappels|alarmes|" +
            "minuteries|[ée]v[ée]nements|say|said|says|write|wrote|think|thinks|a dit|a [ée]crit|pense|pensent)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    /**
     * H1 (review-wikipedia-p4): one of your things by itself, which OWN's plurals missed: the phone's and the clock's ("the
     * battery", "the timer", « la minuterie »), and someone's number or address ("Alex's phone number", « l'adresse de Marie »).
     */
    private val YOURS = Regex(
        "(?<![\\p{L}])(?:the|le|la|l')\\s*(?:timer|alarm|reminder|battery|storage|ram|wi-?fi|bluetooth|volume|brightness|flashlight|" +
            "calendar|schedule|agenda|calendrier|horaire|minuterie|alarme|rappel|batterie|stockage|luminosit[ée]|lampe de poche|" +
            // review-wikipedia-p4-b: one word may name it ("the dentist appointment"); « La Réunion » with its capital is the island.
            "(?:\\p{L}+\\s+)?(?:meeting|appointment|(?-i:r[ée]union)|rendez-vous))(?![\\p{L}])|" +
            // L2 (review-fix-wiki-phone-nouns): « le mot de passe du wifi », as "the Wi-Fi password"; not every « du »: L1
            // (review-fix-wiki-phone-nouns-b) « l'histoire du Bluetooth » or « la portée du Bluetooth » is a question of fact.
            "(?<![\\p{L}])(?:mot\\s+de\\s+passe|code|nom|r[ée]seau|signal|connexion)\\s+du\\s+(?:wi-?fi|bluetooth)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    /**
     * H1 (review-wiki-which-pick): which, what or « quel(le)(s) » right before one of the things Robot's tools keep or read
     * (ToolRouter's notes, lists, people, calendar, phone and file words, the clock's, and apps' use) asks about yours:
     * "Which app uses the most battery?", « Quelle note a le code? ». Event and document only in the plural, as OWN has
     * them, so "What event started the war?" stays a question of fact. L2 (review-fix-wiki-which-route): one text or SMS
     * (the messages tools), bill or screenshot (Robot Files reads both).
     */
    private const val THINGS = "apps?|appli(?:cation)?s?|notes?|lists?|checklists?|alarms?|timers?|reminders?|contacts?|files?|" +
        "receipts?|appointments?|meetings?|messages?|emails?|settings?|playlists?|wi-?fi|bluetooth|texts?|sms|bills?|screenshots?|" +
        "alarmes?|minuteries?|minuteurs?|rappels?|listes?|fichiers?|re[çc]us?|rendez-vous|courriels?|r[ée]glages?|param[èe]tres?|" +
        "textos?|factures?|captures? d'[ée]cran"
    private val PHONE_THING = Regex("^(?:which|what|quel(?:le)?s?)\\s+(?:$THINGS|documents|events|[ée]v[ée]nements)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    /**
     * M1 (review-fix-wiki-which-route): the same things a few words after the lead ("Which of the apps ...", "What's the app
     * that ...", "Which grocery list ...", « Quelle est l'appli qui ... »), never one on its own ("What is Bluetooth?") or a
     * list "of" something ("What is the list of prime ministers?"). A or an starts a definition ("What is a file system?").
     * L1 (review-fix-wiki-phone-nouns): a name or a compound after the thing ("What is the App Store?", "the first text
     * message", « les paramètres d'une loi », "contact lens", "Bill 21") stays a question of fact. H1 (review-fix-wiki-phone-
     * nouns-b): only those are kept out, so a time or any verb may follow ("the phone bill last month", "the apps crashed").
     */
    private val PHONE_THING_IN = Regex(
        "^(?!(?:$THINGS)(?![\\p{L}]))(?:(?:one\\s+)?of\\s+)?(?:(?:the|les|la|le|des)\\s+|l')?(?:(?!(?:a|an|un|une)\\s)\\p{L}+\\s+)?" +
            "(?:$THINGS)(?![\\p{L}])" +
            "(?!\\s+(?:(?:of|des?|du|and|et|ever|store|system|message|lens|lenses)(?![\\p{L}])|\\d+(?!\\d|\\s*(?:minutes?|min|hours?|h|days?|weeks?|months?|years?|jours?|semaines?|mois|ans?)(?![\\p{L}]))|d'une?\\s|" +
            "in\\s+(?!(?i:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?|keep|gmail|whatsapp|signal|messenger|outlook|telegram)(?![\\p{L}]))(?-i:\\p{Lu})))",
        RegexOption.IGNORE_CASE)

    /**
     * M1 (review-fix-wiki-phone-nouns): a thing on, in or from one of yours ("What's on the grocery list?", "the total on the
     * receipt", « le code dans la note »), never a list "of" something or one after "a" ("What's in a black hole?"). M1 and L2
     * (review-fix-wiki-phone-nouns-b): « la facture de cellulaire » is yours, « la liste de Schindler », « la liste rouge »
     * and "the dollar bill" or "the endangered list" are not.
     */
    private val PHONE_THING_ON = Regex(
        "(?<![\\p{L}])(?:on|in|from|inside|sur|dans)\\s+(?:(?:the|la|le|les)\\s+|l')(?:(?!(?:dollar|endangered|grey|gray|red|black|sanctions|farm)\\s)\\p{L}+\\s+)?(?:$THINGS)(?![\\p{L}])" +
            "(?!\\s+(?:(?:of|des|du|rouge|noire|grise|verte|secr[eè]te?)(?![\\p{L}])|de\\s+(?:l'|la\\s+)?(?-i:\\p{Lu})))",
        RegexOption.IGNORE_CASE)

    /**
     * M2 (review-fix-wiki-which-route): what uses, takes up or drains the battery, the space or the data is the phone's.
     * review-fix-wiki-phone-nouns: "so much" or "a lot of" it, and French « place »; not space in a cell (L1). M1 and L3
     * (review-fix-wiki-phone-nouns-b): « autant de batterie »; not space in the universe or « place dans le corps humain ».
     */
    private val DRAIN = Regex(
        "(?<![\\p{L}])(?:using|uses|draining|drains|eating|eats|hogging|hogs|taking|takes)\\s+(?:up\\s+)?(?:the\\s+|all\\s+(?:the\\s+)?|my\\s+|" +
            "so\\s+much\\s+|too\\s+much\\s+|a\\s+lot\\s+of\\s+|lots\\s+of\\s+)?(?:most\\s+)?" +
            "(?:battery|storage|space(?!\\s+(?:in\\s+(?:a|an)\\s|and\\s|in\\s+the\\s+(?!(?:phone|device|storage|memory|gallery|camera|cell|pixel|downloads?|photos?|videos?|files?|drive|cloud)(?![\\p{L}])|\\p{L}+\\s+(?:folder|app|card|storage|gallery|phone)(?![\\p{L}]))))|memory|ram|data)(?![\\p{L}])|" +
            "(?<![\\p{L}])(?:prennent|prend|occupent|occupe|bouffent|bouffe|grugent|gruge)\\s+" +
            "(?:le\\s+plus\\s+de|autant\\s+de|tant\\s+de|trop\\s+de|beaucoup\\s+de|toute\\s+la|de\\s+la)\\s+place(?![\\p{L}])(?!\\s+(?:dans\\s+(?:une?\\s|(?:le|la|les)\\s+(?!(?:t[ée]l(?:[ée]phone)?|cell(?:ulaire)?|appareil|m[ée]moire|stockage|galerie|t[ée]l[ée]chargements?|fichiers?|dossiers?|photos?|vid[ée]os?|pixel|nuage|carte)(?![\\p{L}]))|l'(?!appareil|espace\\s+de\\s+stockage|appli))|aux?\\s+(?!total)|en\\s+(?!m[ée]moire|ce\\s+moment)))|" +
            "(?<![\\p{L}])(?:utilisent|utilise|consomment|consomme|vident|vide|drainent|draine|prennent|prend|occupent|occupe|bouffent|bouffe|grugent|gruge)\\s+" +
            "(?:le\\s+plus\\s+|tout(?:e)?\\s+|autant\\s+|tant\\s+|trop\\s+|beaucoup\\s+)?(?:de\\s+(?:la\\s+|l')?|d'|la\\s+|le\\s+|l'|les\\s+)?(?:batterie|stockage|espace|m[ée]moire|ram|donn[ée]es)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    /** Low (review-wikipedia-p4-b): who invented or found a thing asks about the thing in general ("Who invented the battery?"). */
    private val INVENTED = Regex("^(?:who|when was) (?:invented|discovered|made)|^(?:when was|who) .* (?:invented|discovered)$|^qui a (?:invent|d[ée]couvert)", RegexOption.IGNORE_CASE)
    private val CONTACT_EN = Regex(
        // review-wikipedia-p4-d (Low): not after a capital word, so "Mark Twain's birthday" stays a question of fact.
        "(?<![\\p{L}'])(?<!(?-i:\\p{Lu})\\p{L}{0,30} )(?!(?:what|who|where|when|how|that|it|there|here|he|she|let)'s)\\p{L}+(?:'s|s') " +
            "(?:(?:phone|cell|mobile|home|work) number|phone|cell|email|e-mail|address|birthday)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)
    /** review-wikipedia-p4-b: "the phone number for Alex", « le numéro de marie » typed in lower case. */
    private val CONTACT_FOR = Regex(
        "(?<![\\p{L}])(?:(?:phone|cell|mobile|home|work) number|email|e-mail) (?:for|of) (?!(?:the|a|an)(?![\\p{L}]))\\p{L}|" +
            "(?<![\\p{L}])(?:num[ée]ro|t[ée]l[ée]phone|courriel|cellulaire)(?: de t[ée]l[ée]phone| de cellulaire)? (?:de |d')(?!(?:la|le|les|l'|un|une|s[ée]rie)(?![\\p{L}]))\\p{L}",
        RegexOption.IGNORE_CASE)
    private val CONTACT_FR = Regex("(?i:num[ée]ro|t[ée]l[ée]phone|adresse|courriel|anniversaire|cellulaire)(?i: de t[ée]l[ée]phone| de cellulaire)? (?:de |d')\\p{Lu}")

    /** "What does … do", "what did … say": what something does or did here, not an encyclopedia's subject. */
    private val WHAT_DOES = Regex("^what (?:does|did|do)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /** "I" as a word you say of yourself ("Where did I park"), not a numeral ("World War I"): written as a capital, a word after it. */
    private val I = Regex("(?<![\\p{L}'])I(?= \\p{L})|(?<![\\p{L}'])(?i:i'm|i've|i'd|i'll)(?![\\p{L}])")

    /** What the copy can't know: P0's words, and the time and date. */
    /** FRESH's times that read like "this" or "ce": « en ce moment » is a time, not one of your things. */
    private val TIMES = Regex("(?<![\\p{L}])(?:this (?:week|weekend|morning|evening|year)|these days|ce soir|cette semaine|cette ann[ée]e|ces jours[- ]ci|de nos jours|en ce moment)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    private val FRESH = Regex(
        "(?<![\\p{L}])(?:latest|news|today|tonight|tomorrow|yesterday|last night|this (?:week|weekend|morning|evening|year)|right now|currently|" +
            // M2 (review-wikipedia): what is so now, which a dated copy can't vouch for.
            "current|now|nowadays|these days|still|anymore|at the moment|at present|presently|so far|" +
            "actuel(?:le)?s?|actuellement|pr[ée]sentement|de nos jours|ces jours[- ]ci|cette ann[ée]e|encore|toujours|en date d'aujourd'hui|" +
            "who won|price of|prices of|score of|results of|weather|forecast|what time|what day|what's the date|what is the date|" +
            "aujourd'hui|hier|demain|ce soir|cette semaine|en ce moment|maintenant|nouvelles|actualit[ée]s?|qui a gagn[ée]|prix d[eu]s?|" +
            "score d[eu]s?|m[ée]t[ée]o|quel temps|quelle heure|quel jour|quelle date|soon|bient[ôo]t|" +
            // L2 (review-wikipedia-p4): what comes next or came last, which a dated copy can't know either.
            "next|upcoming|last|recent|recently|prochaine?s?|derni[eè]re?s?|r[ée]cente?s?|r[ée]cemment)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)

    /**
     * M1 (review-wikipedia): a subject that is a pronoun or "that" ("What is it?", "When was he born?", « C'est quoi ça? »)
     * is a follow-up on something said before, never a title: it isn't the copy's, as "It (novel)" would be.
     */
    private val PRONOUN = Regex("(?<![\\p{L}'])(?:he|she|it|its|they|him|her|hers|his|them|their|theirs|that|those|" +
        "il|ils|elle|elles|lui|leur|leurs|son|sa|ses|[çc]a|cela|celui|celle|ceux|celles)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /** The question words a question of fact starts with (P0's, with a few more); what follows is its subject. */
    private val LEAD_EN = listOf(
        "tell me about", "tell me who (?:was|is)", "who (?:wrote|painted|invented|founded|discovered|composed|built|designed)",
        // wiki-fix-stale-card: "Which planet ...", "Which river ..." as "What ..." (« Quel(le)(s) ... » already were).
        "(?:who|what|which|where|when)(?:'s| is| was| are| were| did| does)?",
    ).map(::leadRegex)
    private val LEAD_FR = listOf(
        "parle[- ]moi(?: de| du| des| d')?", "dis[- ]moi qui (?:était|est)", "c'est quoi", "c'est qui", "c'est quand", "qu'est[- ]ce que", "qu'est[- ]ce qu'",
        "qui (?:était|est|sont|étaient|a fondé|a écrit|a inventé|a peint|a découvert|a construit)", "où (?:se trouve|se trouvent|est|était|sont)",
        "quand (?:a eu lieu|était|est|a été)", "quel(?:le)?s? (?:est|était|sont|étaient)", "quel(?:le)?s?",
    ).map(::leadRegex)

    /** A lead at the start of the question, whole words only (an elided one, "qu'est-ce qu'", runs into its next word). */
    private fun leadRegex(lead: String) = Regex("^$lead" + (if (lead.endsWith("'")) "" else "(?![\\p{L}])") + "\\s*", RegexOption.IGNORE_CASE)

    private val STOP_EN = setOf("a", "an", "the", "of", "in", "on", "at", "is", "was", "are", "were", "did", "does", "do", "to", "for", "about")
    private val STOP_FR = setOf("le", "la", "les", "l'", "un", "une", "des", "de", "du", "d'", "est", "était", "au", "aux", "en", "ce", "c'", "se") // l10n: not text (words matched in your question, never said)

    private fun clean(question: String): String =
        POLITE.replace(question.trim().replace('’', '\'').replace(Regex("\\s+"), " "), "").trim().trimEnd('?', '!', '.', ' ', '…')

    /** What [question] is for the copy, checked in this order: command, personal, fresh, a question of fact, other. */
    fun check(question: String): Verdict {
        val q = clean(question)
        if (q.isEmpty()) return Verdict.OTHER
        if (COMMAND.containsMatchIn(q) || WebRouter.explicit(question) != null) return Verdict.COMMAND
        val rest = stripLead(q).replace(TIMES, " ")
        if (PERSONAL.containsMatchIn(rest) || I.containsMatchIn(rest) || OWN.containsMatchIn(rest) || PHONE_THING.containsMatchIn(q) ||
            PHONE_THING_IN.containsMatchIn(rest) || DRAIN.containsMatchIn(q) || PHONE_THING_ON.containsMatchIn(rest)) return Verdict.PERSONAL
        // H1 (review-wikipedia-p4): your things one at a time, and what memory and Robot links answer.
        if (YOURS.containsMatchIn(rest) && !INVENTED.containsMatchIn(q) || CONTACT_FOR.containsMatchIn(rest) || CONTACT_EN.containsMatchIn(rest) || CONTACT_FR.containsMatchIn(rest) ||
            ToolRouter.route(q).any { it == ToolGroup.MEMORY || it == ToolGroup.LINK }) return Verdict.PERSONAL
        if (FRESH.containsMatchIn(q)) return Verdict.FRESH
        if (WHAT_DOES.containsMatchIn(q)) return Verdict.OTHER
        val subject = subject(question) ?: return Verdict.OTHER
        if (PRONOUN.containsMatchIn(subject)) return Verdict.OTHER
        return if (lead(q) != null) Verdict.FACT else Verdict.OTHER
    }

    /**
     * H1 (review-wikipedia-p4): [question] asks about someone in People or the contacts ([names]): who they are ("Who is
     * Sarah?", « Qui est Sarah? »), by a name, first name, nickname or alias, or their things ("Alex's number").
     */
    fun someone(question: String, names: List<ContactName>): Boolean {
        if (names.isEmpty()) return false
        // review-wikipedia-p4-c: a first name can't tell people from topics (Mark Twain, Queen Victoria, Marie Curie), so only
        // a whole name (or a nickname or alias you gave) right beside a contact word: "Alex Martin's birthday", "the number
        // for alex martin", « l'anniversaire de jean côté ». The routers, "my" and check's contact words ran before.
        // review-wikipedia-p4-d (Medium): a first name too, still only beside a contact word ("What's Alex's number?").
        val q = fold(clean(question))
        return names.any { n -> (listOf(n.name, n.name.trim().substringBefore(' ')) + n.nicknames + n.aliases).distinct().map(::fold).filter { it.length > 1 }.any { k ->
            val name = Regex.escape(k)
            Regex("(?<![\\p{L}])$name(?:'s|')\\s+(?:\\p{L}+\\s+){0,2}$DATA(?![\\p{L}])").containsMatchIn(q) ||
                Regex("(?<![\\p{L}])$DATA\\s+(?:(?:for|of|de|a)\\s+|d')$name(?![\\p{L}'])(?!\\s+\\p{L})").containsMatchIn(q)
        } }
    }

    /** A contact's things, folded (no accents): what [someone] needs beside a name; Québécois « la fête à Pierre » too. */
    private const val DATA = "(?:number|phone|cell|mobile|address|birthday|email|e-mail|numero|telephone|cellulaire|adresse|anniversaire|courriel|fete)"

    private fun fold(s: String): String =
        Normalizer.normalize(s.replace('’', '\'').lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

    /**
     * Medium (review-wikipedia-p4-b): a memory [item] answers [question] only when it holds every word of its subject (a
     * word's stem for one over four letters), never one shared word: "I live in Halifax" isn't "What was the Halifax Explosion?".
     */
    fun covers(question: String, item: String): Boolean {
        val split = Regex("[^\\p{L}\\p{N}]+")
        val subject = subject(question)
        // review-wikipedia-p4-c (Low): what a kind of thing is ("What is a beagle?", "What are peanuts?", « C'est quoi une
        // batterie? »: lower case, after a, an, un, une, des or nothing) is the copy's, not "Our dog Max is a beagle".
        val q = clean(question)
        if (subject != null && subject == subject.lowercase() && !WHERE.containsMatchIn(q)) {
            val rest = stripLead(q).trim()
            if (rest.substringBefore(' ').lowercase() in INDEFINITE || rest == subject) return false
        }
        val words = fold(subject ?: q).split(split).filter { it.length > 1 && it !in STOP_FOLDED }
        if (words.isEmpty()) return true
        val have = fold(item).split(split).filter { it.isNotEmpty() }
        return words.all { w -> val stem = w.take(maxOf(4, w.length - 3)); have.any { it == w || w.length > 4 && it.startsWith(stem) } }
    }

    private val INDEFINITE = setOf("a", "an", "un", "une", "des") // l10n: not text (words matched in your question, never said)
    private val WHERE = Regex("^(?:where|when|o[uù]|quand)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val STOP_FOLDED by lazy { (STOP_EN + STOP_FR).map(::fold).toSet() }

    /** True when [question] goes to the copy. */
    fun goes(question: String): Boolean = check(question) == Verdict.FACT

    /** "fr" or "en": the question's language, as web search reads it. */
    fun lang(question: String): String = WebRouter.lang(question)

    private fun lead(q: String): Regex? = (LEAD_EN + LEAD_FR).firstOrNull { it.containsMatchIn(q) }

    /** The question without its question words ("Tell me about" keeps its "me" out of the personal check). */
    private fun stripLead(q: String): String = lead(q)?.replaceFirst(q, "") ?: q

    /**
     * The subject the copy is searched for: the question without its politeness, question words and leading articles
     * ("Who was Louis Riel?" gives "Louis Riel", « C'est quoi la poutine? » gives "poutine"). Null when nothing is left.
     */
    fun subject(question: String, lang: String = lang(question)): String? {
        val q = clean(question)
        val leads = if (lang == "fr") LEAD_FR + LEAD_EN else LEAD_EN + LEAD_FR
        val rest = leads.firstOrNull { it.containsMatchIn(q) }?.replaceFirst(q, "") ?: return null
        val stop = if (lang == "fr") STOP_FR + STOP_EN else STOP_EN + STOP_FR
        val words = rest.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty()) {
            val w = words[0].lowercase()
            when {
                w in stop -> words.removeAt(0)
                (w.startsWith("l'") || w.startsWith("d'")) && w.length > 2 -> { words[0] = words[0].substring(2); break }
                else -> break
            }
        }
        return words.joinToString(" ").trim().trim('"', '“', '”', '«', '»', ',').trim().ifEmpty { null }
    }
}
