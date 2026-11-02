// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.PreRouter
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase

// Pure. Who answers a question (docs/ask-claude.md "Who answers"): the kind of question it is, as far as a check without the
// model can tell; "try it here or ask your helper" and its spoken answers; "always ask the helper for these" and its undoing;
// "ask the helper instead" and "ask Robot instead", and what they may re-send. The panel's side is PanelHelperChoice's.
//
// A question's kind is PreRouter's signal, plus many-part questions, and none for anything that acts on the phone. The check
// reads words, so it catches what is worded like a hard question and misses what isn't: no model here knows its limits, and
// after the model has answered, the hedge check (core Hedge) is what catches the rest.

/** A kind of question a choice can be remembered for ([text]: how Robot says it). */
enum class QuestionKind(private val msg: Msg) {
    THINKING(HelperStrings.kindThinking),
    WRITING(HelperStrings.kindWriting),
    CODE(HelperStrings.kindCode),
    MATH(HelperStrings.kindMath),
    CURRENT(HelperStrings.kindCurrent),
    /** Long or in many parts. */
    BIG(HelperStrings.kindBig);

    val text: Phrase get() = msg()

    /** The memory item's words. [name]: the helper you named; null: "your helper". */
    fun item(robot: Boolean, name: String? = null): Phrase =
        if (robot) HelperStrings.choiceItemRobot("kind" to text) else HelperStrings.choiceItemHelper("name" to (name ?: HelperStrings.choiceYourHelper()), "kind" to text)

    companion object {
        fun of(name: String?): QuestionKind? = entries.firstOrNull { it.name == name }

        fun of(s: PreRouter.Signal): QuestionKind = when (s) {
            PreRouter.Signal.REASONING -> THINKING
            PreRouter.Signal.WRITING -> WRITING
            PreRouter.Signal.CODE -> CODE
            PreRouter.Signal.MATH -> MATH
            PreRouter.Signal.CURRENT_INFO -> CURRENT
            PreRouter.Signal.LONG -> BIG
        }
    }
}

object HelperChoice {
    /** Silence on "try it here or ask your helper": after this long, Robot tries. */
    const val WAIT_MS = 20_000L

    /** Words of acting on the phone beyond PreRouter's: a question with any has no kind, so no choice ever covers it. */
    private val ACTS = Regex("""\b(send|play|pause|turn on|turn off|switch on|switch off|set|dial|phone|reply|respond|delete|remove|add|share|post|buy|order|pay|book|lock|unlock|record|install|envoie|envoyer|joue|allume|eteins|supprime|efface|ajoute|mets|partage|achete|commande|paie|reserve|compose|reponds|verrouille|enregistre)\b""")
    private val WH_JOINED = Regex("""\b(and|also|then|et|puis|aussi) (what|whats|why|how|who|whos|when|where|which|pourquoi|comment|quand|ou|qui|quel|quelle|quels|quelles|combien|quest ce)\b""")
    private val ORDINALS = Regex("""\b(first|firstly|premierement)\b.*\b(second|secondly|deuxiemement|then|ensuite)\b.*\b(third|thirdly|finally|troisiemement|enfin)\b""")
    private const val MANY_PARTS_MIN_WORDS = 10

    private fun norm(text: String): String = HelperRouter.plain(text).replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Three questions or more in one: three question marks, two more joined on with a question word, or first, second, third. */
    fun manyParts(question: String): Boolean {
        val t = norm(question)
        if (t.split(' ').size < MANY_PARTS_MIN_WORDS) return false
        return question.count { it == '?' } >= 3 || WH_JOINED.findAll(t).count() >= 2 || ORDINALS.containsMatchIn(t)
    }

    /**
     * The kind of [question], or null: none Robot can tell, or it acts on the phone (a call, a text, a timer, a list: those stay
     * with Robot and its cards, whatever you chose). [localWeather], [localWeb]: as [PreRouter.signal].
     */
    fun kindOf(question: String, localWeather: Boolean = false, localWeb: Boolean = false): QuestionKind? {
        val t = norm(question)
        if (t.isEmpty() || PreRouter.local(t) || ACTS.containsMatchIn(t)) return null
        PreRouter.signal(question, localWeather, localWeb)?.let { return QuestionKind.of(it) }
        return if (manyParts(question)) QuestionKind.BIG else null
    }

    /** What Robot does with a question before its model runs. */
    enum class Step {
        /** As if nothing was seen. */
        NOTHING,
        /** The helper now, without asking. */
        GO,
        /** "Try it here or ask your helper", by voice and on screen. */
        CHOICE,
        /** Robot's answer first, the offer under it. */
        OFFER_AFTER,
        /** Says no helper is set up, then tries. */
        SAY_NO_HELPER,
        /** You chose Robot for these: Robot answers, and no helper is asked by itself. */
        ROBOT,
    }

    /**
     * [signal]: the pre-router saw one. [kind], [robot]: the question's kind and your choice for it (null: none). [decision]:
     * EscalationPolicy's now, so everything it refuses stays refused. [helperAround]: some helper could be asked. Only the
     * pre-router's own signal or your remembered choice ever goes by itself: a many-part question under Automatic asks first.
     */
    fun step(signal: Boolean, kind: QuestionKind?, robot: Boolean?, decision: EscalationDecision, helperAround: Boolean): Step = when {
        robot == true -> Step.ROBOT
        !signal && kind == null -> Step.NOTHING
        !helperAround -> if (kind != null) Step.SAY_NO_HELPER else Step.NOTHING
        decision == EscalationDecision.AUTO -> if (signal || robot == false) Step.GO else Step.CHOICE
        decision != EscalationDecision.OFFER -> Step.NOTHING
        kind != null && robot == null -> Step.CHOICE
        signal || robot == false -> Step.OFFER_AFTER
        else -> Step.NOTHING
    }

    // ------------------------------------------------------------------ the names, as these phrases hear them

    private class Who(val robot: Boolean, val id: String?, val sure: Boolean)

    private val ROBOT_FORMS = setOf("robot", "the robot")
    private val ANY_SURE = setOf("the helper", "a helper", "your helper", "helper", "lassistant", "l assistant", "un assistant", "lassistante", "assistant",
        "lia", "l ia", "lia externe", "l ia externe", "une ia", "une ia externe", "ton assistant", "ton ia", "ton ia externe")
    /** "My helper" may be a person who helps at home: never sent without a tap or a yes. */
    private const val MY_HELPER = "my helper" // l10n: not text (words Robot listens for)

    private fun who(heard: String, names: List<HelperName>, exactOnly: Boolean = false): Who? {
        if (heard in ROBOT_FORMS) return Who(true, null, true)
        names.firstOrNull { n -> n.forms.any { it.joinToString(" ") == heard } }?.let { return Who(false, it.id, true) }
        if (heard in ANY_SURE) return Who(false, null, true)
        if (heard == MY_HELPER) return Who(false, null, false)
        if (exactOnly) return null
        return names.firstOrNull { n -> n.misheard.any { it.joinToString(" ") == heard } }?.let { Who(false, it.id, false) }
    }

    // ------------------------------------------------------------------ "try it here or ask your helper": the spoken answer

    enum class Picked { HELPER, HERE }

    private val POLITE = Regex("""^(?:(?:please|ok|okay|yeah|yes|um|uh|oui|euh|stp|svp|sil te plait|just|juste)\s+)+|(?:\s+(?:please|thanks|thank you|merci|stp|svp|sil te plait))+$""")
    private val HERE = setOf("here", "try here", "try it", "try it here", "you", "you try", "you do it", "you can try", "no", "nope", "no thanks", "ici", "essaie", "essaie ici", "essayer", "essayer ici",
        "toi", "toi essaie", "non", "non merci", "keep it here", "stay here")
    private val ASK_WORD = Regex("""^(?:ask|try|use|demande a|demande|demander a|demander|essaie|utilise|a)\s+""")

    /**
     * What was said to "try it here or ask your helper", or null: neither (a bare yes doesn't say which). [asked]: the helper
     * the card or offer is for: another helper's name isn't an answer to it, it's a new request, and never sends to [asked].
     */
    fun answer(text: String, names: List<HelperName>, asked: String? = null): Picked? {
        var t = norm(text)
        while (true) { val n = POLITE.replace(t, "").trim(); if (n == t) break; t = n }
        if (t.isEmpty()) return null
        if (t in HERE) return Picked.HERE
        val w = who(t, names) ?: who(ASK_WORD.replace(t, ""), names) ?: return null
        return when {
            w.robot -> Picked.HERE
            w.id != null && asked != null && w.id != asked -> null
            else -> Picked.HELPER
        }
    }

    // ------------------------------------------------------------------ "always ask the helper for these"

    /** [drop]: forget your choice. Else keep one: to Robot ([robot]) or the helper [helper] (null: the one Robot checks with). [kind]: named; null, the last question's. */
    data class Command(val drop: Boolean, val robot: Boolean = false, val helper: String? = null, val kind: QuestionKind? = null)

    private const val THESE = "these|those|them|this|that|these ones|those ones|questions like this|questions like that|things like this|things like that|this kind|that kind|this kind of (?:question|thing)|that kind of (?:question|thing)|ces questions|celles ci|ca|cela|ce genre|ce genre de questions?"
    private const val FOR_KINDS = "code|coding|programming|code questions|coding questions|programming questions|math|maths|math questions|calculations|writing|letters|drafts|news|the news|current events|current information|prices|long questions|big questions|long ones|planning|comparisons|reasoning|les questions de code|la programmation|les maths|maths|les calculs|la redaction|les nouvelles|nouvelles|lactualite|actualite|les questions longues|la reflexion" // l10n: not text (words Robot listens for)
    private const val K = "($THESE|$FOR_KINDS)"
    /** What a route needs without "always": more than this one question. "Ask Claude about that" is once (see [instead]). */
    private const val MANY = "these|those|them|these ones|those ones|questions like this|questions like that|things like this|things like that|this kind|that kind|this kind of (?:question|thing)|that kind of (?:question|thing)|ces questions|celles ci|ce genre|ce genre de questions?"
    private const val MANY_K = "($MANY|$FOR_KINDS)"
    private const val FOR = "(?:for|pour)"
    private const val PREP = "(?:for|with|on|about|pour)"
    private const val N = "(.+?)"

    private fun kindWords(k: String?): QuestionKind? = when (k) {
        null -> null
        "code", "coding", "programming", "code questions", "coding questions", "programming questions", "les questions de code", "la programmation" -> QuestionKind.CODE // l10n: not text (words Robot listens for)
        "math", "maths", "math questions", "calculations", "les maths", "les calculs" -> QuestionKind.MATH // l10n: not text (words Robot listens for)
        "writing", "letters", "drafts", "la redaction" -> QuestionKind.WRITING // l10n: not text (words Robot listens for)
        "news", "the news", "current events", "current information", "prices", "les nouvelles", "nouvelles", "lactualite", "actualite" -> QuestionKind.CURRENT // l10n: not text (words Robot listens for)
        "long questions", "big questions", "long ones", "les questions longues" -> QuestionKind.BIG // l10n: not text (words Robot listens for)
        "planning", "comparisons", "reasoning", "la reflexion" -> QuestionKind.THINKING // l10n: not text (words Robot listens for)
        else -> null
    }

    private val DROPS = listOf(
        Regex("^(?:stop|quit) (?:asking|using|sending (?:these|them|those) to) $N(?: $PREP $K)?$"),
        Regex("^dont always ask $N(?: $PREP $K)?$"),
        Regex("^(?:arrete|cesse) de (?:demander a|utiliser) $N(?: $PREP $K)?$"),
    )
    private val DROPS_NO_NAME = listOf(
        Regex("^forget (?:that|this|the|my) choice(?: $PREP $K)?$"),
        Regex("^(?:go )?back to (?:normal|asking me|the usual)(?: $PREP $K)?$"),
        Regex("^ask me (?:again|first)(?: $PREP $K)?$"),
        Regex("^oublie (?:ce|mon) choix(?: $PREP $K)?$"),
        Regex("^redemande moi(?: $PREP $K)?$"),
    )
    private val KEEPS = listOf(
        Regex("^always (?:ask|use|go to) $N(?: $PREP $K)?$"),
        Regex("^use $N $FOR $MANY_K$"),
        Regex("^ask $N $FOR ($MANY)$"),
        Regex("^(?:send|keep) (?:these|those|them) (?:to|on|with) $N$"),
        Regex("^(?:demande|demandes) toujours a $N(?: $PREP $K)?$"),
        Regex("^toujours $N $PREP $K$"),
        Regex("^utilise $N $FOR $MANY_K$"),
        Regex("^(?:garde|envoie) (?:ces questions|celles ci|ca|cela) (?:sur|avec|a) $N$"),
    )
    private val KEEP_HERE = listOf(
        Regex("^keep (?:these|those|them) here$"),
        Regex("^you (?:answer|do|handle|take) (?:these|those)$"),
        Regex("^garde (?:ces questions|celles ci) ici$"),
        Regex("^reponds (?:a ces questions|a celles ci) toi meme$"),
    )

    /** "Always ask the helper for these", "use Robot for code", "stop asking the helper for these", French too; null: not one. */
    fun command(text: String, names: List<HelperName>): Command? {
        val t = norm(text)
        if (t.isEmpty()) return null
        fun k(m: MatchResult, at: Int) = kindWords(m.groupValues.getOrNull(at)?.takeIf { it.isNotEmpty() })
        DROPS_NO_NAME.firstNotNullOfOrNull { it.find(t) }?.let { return Command(drop = true, kind = k(it, 1)) }
        DROPS.firstNotNullOfOrNull { it.find(t) }?.let { m ->
            who(m.groupValues[1], names, exactOnly = true) ?: return null
            return Command(drop = true, kind = k(m, 2))
        }
        if (KEEP_HERE.any { it.containsMatchIn(t) }) return Command(drop = false, robot = true)
        KEEPS.firstNotNullOfOrNull { it.find(t) }?.let { m ->
            val w = who(m.groupValues[1], names, exactOnly = true) ?: return null
            return Command(drop = false, robot = w.robot, helper = w.id, kind = k(m, 2))
        }
        return null
    }

    // ------------------------------------------------------------------ "ask the helper instead", "ask Robot instead"

    /** Send the last question to Robot ([robot]) or a helper ([helper]; null: the one that can take it). [sure]: not a word that only sounds like its name, nor "my helper". */
    data class Instead(val robot: Boolean, val helper: String? = null, val sure: Boolean = true)

    private val LEAD = Regex("""^(?:(?:no|nah|nope|hmm|um|uh|well|actually|ok|okay|please|non|bof|euh|ben)\s+)+""")
    private val WRONG = Regex("""^(?:(?:thats|that is|that was|cest|ce nest|cetait) )?(?:wrong|not right|not it|not helpful|no good|faux|pas ca|pas bon|pas ca du tout)(?:\s+|$)|^i dont like that(?: answer| reply)?(?:\s+|$)|^that (?:isnt right|doesnt help|didnt help)(?:\s+|$)|^je naime pas ca(?:\s+|$)|^ca ne maide pas(?:\s+|$)""")
    private const val POLITE_TAIL = "please|then|for me|stp|svp|sil te plait" // l10n: not text (words Robot listens for)
    private const val MARK_TAIL = "instead|that|this|it|that one|the same thing|the same question|on this|on that|about this|about that|about it|with this|with that|for this|for that|plutot|a la place|la meme chose|la meme question|pour ca|pour cela|sur ca|sur cela" // l10n: not text (words Robot listens for)
    private val TAIL = Regex("""(?:\s+(?:$POLITE_TAIL|$MARK_TAIL))+$""")
    private val MARK = Regex("""\b(?:$MARK_TAIL)\b""")

    /** A helper's name after these needs a word that says it's the same question ("ask Claude" alone joins, "ask Claude what …" asks). */
    private val NEEDS_MARK = listOf(Regex("^ask $N$"), Regex("^use $N$"), Regex("^utilise $N$"), Regex("^demande(?: plutot)?(?: a)? $N$"), Regex("^(?:le|la|ca) demander a $N$"))
    private val ANYWAY = listOf(
        Regex("^try(?: asking| with)? $N$"), Regex("^go with $N$"), Regex("^switch to $N$"), Regex("^check with $N$"),
        Regex("^(?:send|give|pass|hand) (?:it|that|this|the question)(?: over)? to $N$"), Regex("^run (?:it|that|this) by $N$"),
        Regex("^see what $N (?:says|thinks)$"), Regex("^let $N (?:try|answer|handle it|handle that|take it|take this|have a go)$"),
        Regex("^get $N to (?:try|answer|do it|answer it)$"),
        Regex("^demande (?:plutot a|le a|la a|ca a|cela a) $N$"), Regex("^essaie(?: avec)? $N$"),
        Regex("^(?:envoie|passe) (?:le|la|ca|cela) a $N$"), Regex("^vois avec $N$"), Regex("^voyons ce que $N (?:dit|en pense)$"), Regex("^laisse $N (?:essayer|repondre)$"),
    )
    /** "What does the helper think": a name of yours, there, joins as it always has (HelperRouter.route). */
    private val WHAT_SAYS = Regex("^what does (the helper|your helper|lassistant) (?:think|say)$")
    private val TRY_HERE = Regex("^(?:(?:try|answer)(?: it| that)? here|essaie ici|reponds ici)$")

    /**
     * "Ask the helper instead", "try the helper", "that's wrong, ask Claude", "send it to the helper", « demande plutôt à
     * l'assistant », and the reverse, "ask Robot instead", "try it here"; null: not one. A refusal ("don't ask the helper
     * instead"), a message for someone ("ask my helper to pick up the kids") and a new question ("ask Claude what time it is") are
     * never one.
     */
    fun instead(text: String, names: List<HelperName>): Instead? {
        if (HelperRouter.declines(text, names)) return null
        var t = lead(norm(text))
        val wrong = WRONG.find(t)
        if (wrong != null) t = lead(t.substring(wrong.value.length))
        if (t.isEmpty()) return null
        if (TRY_HERE.matches(t)) return Instead(robot = true)
        WHAT_SAYS.find(t)?.let { return Instead(robot = false) }
        val tail = TAIL.find(t)?.value.orEmpty()
        val core = t.removeSuffix(tail)
        // "Robot instead", « l'assistant plutôt »: the name and a word saying so, nothing else.
        val marked = wrong != null || MARK.containsMatchIn(tail)
        val heard = ANYWAY.firstNotNullOfOrNull { it.find(core) }?.groupValues?.get(1)
            ?: (if (marked) NEEDS_MARK.firstNotNullOfOrNull { it.find(core) }?.groupValues?.get(1) else null)
            ?: (if (MARK.containsMatchIn(tail)) core else null)
            ?: return null
        val w = who(heard, names) ?: return null
        return if (w.robot) Instead(robot = true) else Instead(robot = false, helper = w.id, sure = w.sure)
    }

    /** Filler off the front, and Robot's name when it's being spoken to ("Robot, ask the helper"), not named ("Robot instead"). */
    private fun lead(t: String): String {
        val a = LEAD.replace(t, "")
        val b = a.removePrefix("hey robot ").removePrefix("robot ")
        return if (b == a || TAIL.matches(" $b")) a else LEAD.replace(b, "")
    }

    /** The last question as it was asked, for a re-send: only one that went to Robot's model or to a helper is ever kept. */
    data class Asked(
        val question: String,
        /** The reply it got. */
        val replyId: String,
        /** The helper that answered it; null: Robot. */
        val helper: String?,
        /** A screen, a photo, a file or what another app shared went with it. */
        val attached: Boolean,
        /** Words heard back while Robot read a message: never your question. */
        val heardBack: Boolean,
        /** Asked on a locked phone. */
        val askedLocked: Boolean,
    )

    enum class Gate {
        /** Goes now. */
        GO,
        /** Goes only after a tap or a yes. */
        ASK_FIRST,
        /** No question to send. */
        NOTHING,
        /** It had something with it (or wasn't your words): a re-send would drop it, so Robot says so. */
        ATTACHED,
        /** It acted on the phone, and those stay with Robot and its cards. */
        ACTED,
        /** Asked unlocked, and the phone is locked now: whoever holds it can't send it off the phone. */
        UNLOCK,
        /** That answer came from where you're sending it. */
        SAME,
    }

    /**
     * May [asked] be sent again ([i]) now? Checked at the moment of the re-send, with the conversation as it is: [acted], its
     * reply holds a card or an action; [lockedNow], the phone; [tainted], the conversation holds content Robot didn't write or
     * anything personal, so only a tap or a yes sends it, and then only your question goes. A lock the helper isn't allowed
     * through, a helper that's off and everything else are refused where the question goes (PanelHelperTurn.run, .escalate).
     */
    fun gate(i: Instead, asked: Asked?, acted: Boolean, lockedNow: Boolean, tainted: Boolean, targetId: String?): Gate = when {
        asked == null -> Gate.NOTHING
        asked.attached || asked.heardBack -> Gate.ATTACHED
        acted -> Gate.ACTED
        i.robot && asked.helper == null -> Gate.SAME
        !i.robot && asked.helper != null && asked.helper == targetId -> Gate.SAME
        i.robot -> Gate.GO
        lockedNow && !asked.askedLocked -> Gate.UNLOCK
        !i.sure || tainted -> Gate.ASK_FIRST
        else -> Gate.GO
    }
}
