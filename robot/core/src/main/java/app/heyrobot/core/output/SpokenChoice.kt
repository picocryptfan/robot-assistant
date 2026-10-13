package app.heyrobot.core.output

import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.LogCode
import app.heyrobot.core.TextCommand
import app.heyrobot.core.ToolName

// Pure. Spoken cards and spoken choices (design §1.4): in a headset session or car mode you can't look at the card, so a
// card is read as one short question with at most three choices ("Call Mom: Mom Martin, mobile, or Mom Martin, work?")
// and answered by voice. It builds on the panel's CardAnswer: a plain yes or no is CardAnswer.of (the same words, the
// same wake-phrase handling, everything said used up), and this adds choices by number or name ("the second one", "la
// deuxième", "the work one", "le cell"), a card's own verb ("call", "appelle"), "repeat", and Robot's own prompt heard
// back by the microphone (an echo is never an answer).
//
// What may be answered by voice, by where you are (§1.4, §4.5, §4.9):
// - On the phone (the panel): CardAnswer as today; nothing here changes it.
// - Headset or car: a card that must be seen stays tap-only and waits for you ("I'll keep that for when you can look at
//   the phone." in a headset, "I'll keep that for when you park." in the car): a name learned from Cheogram, the nearest
//   place's first-use card, deleting, a card that sends personal content to a helper (unless "Voice yes can send personal
//   content"), not being sure who (except for a call, where up to three spoken choices of who are allowed), more than
//   three choices, and "Answer cards by voice" off.
// - Car: what needs the screen (opening an app, a new text, an email or event draft, freeing space) waits for parking;
//   the Claude app hand-off is refused ("I can't open the Claude app while you drive"), as car mode never hands off to it.
// - Locked: a card whose question names people or places is answered after the unlock, except car functions in a trusted
//   car session.

/** A choice read aloud: [label] as said ("Mom Martin, mobile"), [keys] the words that name it alone ("mobile", "cell"). */
data class SpokenOption(val label: String, val keys: Set<String> = emptySet())

/**
 * A card as hands-free speech sees it. [action]: its spoken summary, already in the language spoken ("Call Mom, mobile,
 * ending 0101"). [options]: its choices. [waiting]: the panel's own CardAnswer.Waiting (why it needs a tap, whether its
 * tap sends personal content). [tool]: the action it runs. [deletes]: it deletes something. [spokenWho]: who it goes to
 * may be chosen by voice (calls only). [confirmWords]: verbs that confirm it besides yes ("call", "appelle"), in plain
 * words. [personal]: its question names people or places. [carFunction]: one of car mode's own functions (calls,
 * messages, what's next, saved places), which a trusted car session allows while locked.
 */
data class SpokenCard(
    val action: String,
    val options: List<SpokenOption> = emptyList(),
    val waiting: CardAnswer.Waiting = CardAnswer.Waiting(options.size),
    val tool: ToolName? = null,
    val deletes: Boolean = false,
    val spokenWho: Boolean = false,
    val confirmWords: List<String> = emptyList(),
    val personal: Boolean = false,
    val carFunction: Boolean = false,
) {
    val picks: Boolean get() = options.size > 1
}

/** Where the card is answered, and the switches that matter. */
data class CardContext(
    val session: SessionKind,
    val locked: Boolean = false,
    val carTrusted: Boolean = false,
    /** Safety & privacy › "Answer cards by voice". */
    val voiceCards: Boolean = true,
    /** Safety & privacy › "Voice yes can send personal content". */
    val voiceYesPersonal: Boolean = false,
)

/** Why a card wasn't asked by voice. Also part of the voice log's SPOKEN_CARD. */
enum class CardVoiceCode(override val id: Int, override val title: String) : LogCode {
    ASKED(1, "asked by voice"), PANEL(2, "the panel's card"), MUST_SEE(3, "must be seen"), WHO_UNSURE(4, "not sure who"),
    TOO_MANY(5, "too many choices to read"), NEEDS_SCREEN(6, "needs the screen (driving)"), LOCKED(7, "locked"), VOICE_OFF(8, "answer cards by voice is off"),
    REFUSED(9, "not done while driving"),
}

/** Where a card that waits for you goes: the Ready list (headset, §2.5) or the When-you-park list (car, §4.8). */
enum class DeferTo { READY, PARK }

sealed class CardVoice {
    /** Not hands-free: the panel's card and CardAnswer, as today. */
    data object Panel : CardVoice()

    /** Ask: say [prompt] and listen; [choices] is 0 for a yes or no. */
    data class Ask(val prompt: String, val choices: Int, val personal: Boolean) : CardVoice()

    /** Kept for when you can look: [say] now, the card goes to [to]. */
    data class Defer(val say: String, val to: DeferTo, val why: CardVoiceCode) : CardVoice()

    /** Not done, [say] says why. */
    data class Refuse(val say: String, val why: CardVoiceCode) : CardVoice()
}

/** What a spoken answer was. Also the voice log's SPOKEN_CARD c. */
enum class AnswerCode(override val id: Int, override val title: String) : LogCode {
    YES(1, "yes"), NO(2, "no"), PICK(3, "a choice"), REPEAT(4, "repeat"), UNCLEAR(5, "unclear"), ECHO(6, "Robot's own words"), SILENCE(7, "nothing said"),
}

/** One answer: [index] for [AnswerCode.PICK]; [confidence] 0 to 1 ([sure] at 0.8 and up). */
data class SpokenAnswer(val kind: AnswerCode, val index: Int = -1, val confidence: Double = 0.0) {
    val sure: Boolean get() = confidence >= SpokenChoice.SURE
}

/** What to do after an answer. */
sealed class SpokenStep {
    data object Confirm : SpokenStep()
    data object Decline : SpokenStep()
    data class Choose(val index: Int) : SpokenStep()
    /** Say the prompt again (you asked to hear it). */
    data object Reread : SpokenStep()
    /** Not understood: say [say] and listen again. */
    data class AskAgain(val say: String) : SpokenStep()
    /** Twice nothing usable: the card waits for you ([say], then [to]). */
    data class Hold(val say: String, val to: DeferTo) : SpokenStep()
}

object SpokenCards {
    /** Choices read aloud at most (§1.4). */
    const val MAX_SPOKEN = 3

    /** What the car keeps for parking: they open a screen or a draft you finish by tapping. */
    val DRIVING_HOLD: Set<ToolName> = setOf(ToolName.OPEN_APP, ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE)

    val UNLOCK_TO_ANSWER = Line("Unlock to answer that.", "Déverrouille pour répondre.")
    val NO_CLAUDE_DRIVING = Line("I can't open the Claude app while you drive.", "Je ne peux pas ouvrir l'app Claude pendant que tu conduis.")

    /** How [card] is answered where [ctx] says, in [lang]. */
    fun plan(card: SpokenCard, ctx: CardContext, lang: SpokenLang): CardVoice {
        if (ctx.session == SessionKind.PHONE) return CardVoice.Panel
        val car = ctx.session == SessionKind.CAR
        val to = if (car) DeferTo.PARK else DeferTo.READY
        val keep = (if (car) OutputLines.KEEP_FOR_PARK else OutputLines.KEEP_FOR_LOOK).get(lang)
        fun defer(why: CardVoiceCode) = CardVoice.Defer(keep, to, why)
        if (car && card.tool == ToolName.ASK_HELPER) return CardVoice.Refuse(NO_CLAUDE_DRIVING.get(lang), CardVoiceCode.REFUSED)
        if (!ctx.voiceCards) return defer(CardVoiceCode.VOICE_OFF)
        val tap = card.waiting.tapFor
        if (tap == AskWhy.LEARNED || tap == AskWhy.ONLINE || card.deletes || (card.waiting.personal && !ctx.voiceYesPersonal)) return defer(CardVoiceCode.MUST_SEE)
        if (car && card.tool in DRIVING_HOLD) return defer(CardVoiceCode.NEEDS_SCREEN)
        if (tap == AskWhy.WHO && !card.spokenWho) return defer(CardVoiceCode.WHO_UNSURE)
        if (card.options.size > MAX_SPOKEN) return defer(CardVoiceCode.TOO_MANY)
        if (ctx.locked && card.personal && !(car && ctx.carTrusted && card.carFunction)) return CardVoice.Defer(UNLOCK_TO_ANSWER.get(lang), to, CardVoiceCode.LOCKED)
        return CardVoice.Ask(prompt(card, lang), if (card.picks) card.options.size else 0, card.personal)
    }

    /** "Call Mom, mobile, ending 0101?"; with choices "Call Mom: Mom Martin, mobile, or Mom Martin, work?". */
    fun prompt(card: SpokenCard, lang: SpokenLang): String {
        val action = card.action.trim().trimEnd('.', '?', '!', ':', ' ')
        if (!card.picks) return "$action?"
        val labels = card.options.map { it.label.trim().trimEnd('.', '?', '!', ',', ';') }
        // Labels with commas of their own ("Mom Martin, mobile") are kept apart by a comma before "or" (two) or semicolons (three).
        val commas = labels.any { it.contains(',') }
        val or = if (lang == SpokenLang.FR) "ou" else "or"
        val list = when {
            labels.size == 2 -> labels[0] + (if (commas) ", " else " ") + "$or " + labels[1]
            commas -> labels.dropLast(1).joinToString("; ") + "; $or " + labels.last()
            else -> labels.dropLast(1).joinToString(", ") + ", $or " + labels.last()
        }
        val colon = if (lang == SpokenLang.FR) " :" else ":"
        return "$action$colon $list?"
    }

    /** The hint after an answer that wasn't one ("Say yes or no.", "Say the first or the second, or no."). */
    fun hint(choices: Int, lang: SpokenLang): String = when {
        choices <= 1 -> if (lang == SpokenLang.FR) "Dis oui ou non." else "Say yes or no."
        choices == 2 -> if (lang == SpokenLang.FR) "Dis le premier ou le deuxième, ou non." else "Say the first or the second, or no."
        else -> if (lang == SpokenLang.FR) "Dis le premier, le deuxième ou le troisième, ou non." else "Say the first, the second or the third, or no."
    }
}

object SpokenChoice {
    /** An answer this confident is acted on. */
    const val SURE = 0.8
    /** Tries before a card waits for you ("silence twice leads to held", §4.11). */
    const val MAX_TRIES = 2
    /** "Repeat" re-reads the prompt at most this often. */
    const val MAX_REREADS = 2
    private const val MAX_WORDS = 10

    private val REPEAT = phrases(
        "repeat", "repeat that", "repeat it", "repeat please", "say that again", "say it again", "say again", "again", "come again", "what", "what was that",
        "pardon", "sorry", "excuse me", "huh", "repete", "repete ca", "repete moi ca", "redis", "redis le", "redis ca", "quoi", "comment", "hein", "encore",
    )
    private val NONE = phrases("none", "none of them", "neither", "neither one", "neither of them", "aucun", "aucune", "aucun des deux", "aucune des deux",
        "ni lun ni lautre", "personne")
    /** Words that carry no choice: articles, "one", politeness, "number", the wake phrase, a leading yes. */
    private val FILLER = setOf(
        "the", "a", "an", "one", "ones", "number", "numero", "no", "option", "choice", "please", "thanks", "le", "la", "l", "les", "celui", "celle", "du", "de", "d",
        "des", "au", "sur", "son", "sa", "ses", "his", "her", "their", "on", "at", "yes", "oui", "yeah", "yep", "ok", "okay", "pick", "take", "choose", "prends",
        "choisis", "i", "want", "je", "veux", "that", "it", "this", "go", "with", "for", "pour", "avec", "um", "uh", "euh", "hmm", "robot", "hey", "merci",
        "sil", "te", "vous", "plait", "is", "its", "cest", "ca", "lui", "est",
    )
    /** A word naming a choice by number. "one" and "un" count only after "number" or alone (else "the work one"). */
    private val ORDINALS: Map<String, Int> = mapOf(
        "first" to 0, "1st" to 0, "premier" to 0, "premiere" to 0, "1" to 0,
        "second" to 1, "2nd" to 1, "two" to 1, "deuxieme" to 1, "seconde" to 1, "deux" to 1, "2" to 1,
        "third" to 2, "3rd" to 2, "three" to 2, "troisieme" to 2, "trois" to 2, "3" to 2,
    )
    private val CARDINAL_ONE = setOf("one", "un", "une")
    private val NEGATION = setOf("dont", "not", "pas", "never", "jamais")
    private val LAST = setOf("last", "dernier", "derniere")
    /** Labels' words that mean the same thing (Québec "cell", "cellulaire"; "travail", "bureau"). */
    private val SYNONYMS: Map<String, String> = mapOf(
        "cell" to "mobile", "cellphone" to "mobile", "cellular" to "mobile", "cellulaire" to "mobile", "portable" to "mobile", "mobile" to "mobile",
        "work" to "work", "office" to "work", "job" to "work", "travail" to "work", "bureau" to "work",
        "home" to "home", "house" to "home", "maison" to "home",
    )

    private fun phrases(vararg p: String): List<List<String>> = p.map { it.split(' ') }

    private fun words(text: String): List<String> = HelperRouter.plain(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun canon(w: String): String = SYNONYMS[w] ?: w

    /**
     * What [text] answers to [card]. [robotSaid]: what Robot just said (its prompt): three or more words of it heard
     * back in order are an echo, never an answer. [wakePhrases]: yours, allowed in front (as CardAnswer).
     */
    fun answer(text: String, card: SpokenCard, robotSaid: String? = null, wakePhrases: List<String> = emptyList()): SpokenAnswer {
        val stripped = TextCommand.stripWake(text, wakePhrases, loose = true)
        val w = words(stripped)
        if (w.isEmpty()) return SpokenAnswer(AnswerCode.SILENCE, confidence = 1.0)
        if (robotSaid != null && echo(w, words(robotSaid))) return SpokenAnswer(AnswerCode.ECHO)
        if (w.size > MAX_WORDS) return SpokenAnswer(AnswerCode.UNCLEAR)
        if (whole(w, NONE)) return SpokenAnswer(AnswerCode.NO, confidence = 1.0)
        CardAnswer.of(text, wakePhrases)?.let { kind ->
            return if (kind == CardAnswer.Kind.NO) SpokenAnswer(AnswerCode.NO, confidence = 1.0)
            else if (card.picks) SpokenAnswer(AnswerCode.UNCLEAR, confidence = 0.5) else SpokenAnswer(AnswerCode.YES, confidence = 1.0)
        }
        if (whole(w, REPEAT)) return SpokenAnswer(AnswerCode.REPEAT, confidence = 0.9)
        // The card's own verb ("call", "appelle"), alone or with filler: a yes; with a choice: that choice.
        val verbs = card.confirmWords.map { words(it) }.filter { it.isNotEmpty() }
        var rest = w
        var verb = false
        for (v in verbs.sortedByDescending { it.size }) {
            val at = indexOf(rest, v)
            if (at >= 0) { rest = rest.take(at) + rest.drop(at + v.size); verb = true; break }
        }
        // "Don't call", "n'appelle pas": a no.
        val plainVerbs = verbs.filter { it.size == 1 }.map { it[0] }
        if (w.any { it in NEGATION } && (verb || w.any { x -> plainVerbs.any { x == "n$it" } })) return SpokenAnswer(AnswerCode.NO, confidence = 0.9)
        if (!card.picks) {
            return if (verb && rest.all { it in FILLER && it != "no" }) SpokenAnswer(AnswerCode.YES, confidence = 0.95) else SpokenAnswer(AnswerCode.UNCLEAR)
        }
        return pick(rest, card)
    }

    /** A choice by number or name among [card]'s options; everything said must be used up. */
    private fun pick(w: List<String>, card: SpokenCard): SpokenAnswer {
        val n = card.options.size
        var ordinal: Int? = null
        val content = mutableListOf<String>()
        for ((i, x) in w.withIndex()) {
            val prev = w.getOrNull(i - 1)
            val o = when {
                x in ORDINALS -> ORDINALS.getValue(x)
                x in LAST -> n - 1
                x in CARDINAL_ONE && (prev == "number" || prev == "numero" || prev == "option" || w.size == 1) -> 0
                else -> null
            }
            if (o != null) {
                if (ordinal != null && ordinal != o) return SpokenAnswer(AnswerCode.UNCLEAR)
                ordinal = o
            } else if (x !in FILLER) content += canon(x)
        }
        val named = if (content.isEmpty()) null else byName(content, card) ?: return SpokenAnswer(AnswerCode.UNCLEAR)
        return when {
            ordinal != null && ordinal !in 0 until n -> SpokenAnswer(AnswerCode.UNCLEAR)
            ordinal != null && named != null -> if (ordinal == named) SpokenAnswer(AnswerCode.PICK, ordinal, 0.95) else SpokenAnswer(AnswerCode.UNCLEAR)
            ordinal != null -> SpokenAnswer(AnswerCode.PICK, ordinal, 0.95)
            named != null -> SpokenAnswer(AnswerCode.PICK, named, 0.85)
            else -> SpokenAnswer(AnswerCode.UNCLEAR)
        }
    }

    /** The one option every word of [content] names (its keys, or words of its label no other label has), or null. */
    private fun byName(content: List<String>, card: SpokenCard): Int? {
        val labelWords = card.options.map { o -> (words(o.label).map(::canon) + o.keys.flatMap { words(it) }.map(::canon)).toSet() }
        val own = labelWords.mapIndexed { i, s -> s.filter { x -> labelWords.withIndex().none { (j, t) -> j != i && x in t } }.toSet() }
        var fits = card.options.indices.toSet()
        var distinct = false
        for (x in content) {
            val has = card.options.indices.filter { x in labelWords[it] }.toSet()
            if (has.isEmpty()) return null
            if (card.options.indices.any { x in own[it] }) distinct = true
            fits = fits intersect has
        }
        return if (distinct && fits.size == 1) fits.first() else null
    }

    private fun whole(w: List<String>, list: List<List<String>>): Boolean {
        val core = w.filter { it !in setOf("please", "robot", "hey", "sil", "te", "vous", "plait", "merci", "um", "uh", "euh") }
        return core.isNotEmpty() && list.any { it == core }
    }

    private fun indexOf(w: List<String>, p: List<String>): Int = (0..w.size - p.size).firstOrNull { i -> p.indices.all { w[i + it] == p[it] } } ?: -1

    /**
     * Robot's own voice through the microphone: three or more words of [said] heard in the same order, and either five
     * or more, most of what was said, or its "or" between choices ("Mom Martin, mobile" alone is an answer; "mobile or
     * Mom Martin, work" is the prompt coming back).
     */
    private fun echo(heard: List<String>, said: List<String>): Boolean =
        heard.size >= 3 && indexOf(said, heard) >= 0 && (heard.size >= 5 || heard.size * 5 >= said.size * 4 || "or" in heard || "ou" in heard)

    /**
     * What to do with [a] after [tries] unusable answers so far and [rereads] re-reads, for a card with [choices] (0 for
     * yes or no), in [lang]; [session] says where a held card goes. Nothing usable twice (silence, an echo, words that
     * aren't an answer): the card waits for you.
     */
    fun next(a: SpokenAnswer, tries: Int, rereads: Int, choices: Int, session: SessionKind, lang: SpokenLang): SpokenStep {
        val car = session == SessionKind.CAR
        val hold = SpokenStep.Hold((if (car) OutputLines.KEEP_FOR_PARK else OutputLines.KEEP_FOR_LOOK).get(lang), if (car) DeferTo.PARK else DeferTo.READY)
        if (a.sure) when (a.kind) {
            AnswerCode.YES -> return SpokenStep.Confirm
            AnswerCode.NO -> return SpokenStep.Decline
            AnswerCode.PICK -> return SpokenStep.Choose(a.index)
            else -> Unit
        }
        if (a.kind == AnswerCode.REPEAT && rereads < MAX_REREADS) return SpokenStep.Reread
        return if (tries + 1 >= MAX_TRIES) hold else SpokenStep.AskAgain(SpokenCards.hint(choices, lang))
    }
}
