// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.util.Locale

// Pure. Settings › Safety & privacy (0.4.0): every rule that makes an action ask first, or keeps something on the phone,
// is a switch with a sensible default, so each person picks their own safety level. ApprovalGate reads them.

/**
 * "Safety cards": when content Robot didn't write (screen text, a photo, what a read tool found, a helper's words) makes an
 * action that writes or leaves the phone show its card even under "Just do it".
 */
enum class SafetyCards(private val titleMsg: Msg, private val detailMsg: Msg) {
    /** Before 0.4.0's switch, the only rule: any content in the conversation, every such action asks. */
    AFTER_READING(TurnStrings.cardsAfterReading, TurnStrings.cardsAfterReadingDetail),
    /**
     * The default: content forces a card for an action you didn't ask for in your own words this time, and (1.0.1) for
     * any action that changes or opens something, since content could have chosen its details.
     */
    UNASKED(TurnStrings.cardsUnasked, TurnStrings.cardsUnaskedDetail),
    /** Content never forces a card; only not being sure who (or which) does. */
    UNSURE_ONLY(TurnStrings.cardsUnsureOnly, TurnStrings.cardsUnsureOnlyDetail);

    /** The choice and what it means, in words without a language yet (1.1: Settings shows them with `str()`). */
    val titleText: Phrase get() = titleMsg()
    val detailText: Phrase get() = detailMsg()

    /** The same in English. */
    val title: String get() = titleText.en()
    val detail: String get() = detailText.en()
}

/**
 * Why a prepared action shows its card even under "Just do it", besides the conversation's content. Each has its switch
 * in Settings › Safety & privacy except [PICK].
 */
enum class AskWhy {
    /** Not certain who or which: several people or places, a near match, a number or address the model wrote, an app name that matched loosely. */
    WHO,
    /** The one place it would go belongs to a name learned from a Cheogram notification (whoever sent it chose it). */
    LEARNED,
    /** It deletes files: a photo or video (Android still asks too), or Robot's cache. */
    DELETE,
    /** A list to choose from that isn't about safety (which app's cache to clear): always a card. */
    PICK,
    /**
     * It would go online for the first time ("Find the nearest place online: Ask the first time"): always a card that
     * says what would leave the phone, answered by a tap.
     */
    ONLINE,
    /**
     * 0.7: a message other people wrote is in the conversation, and this would send something to someone other than its
     * sender (a text, an email, a helper, anything online): always a card, answered by a tap (core messages
     * MessageQuarantine). Not a switch: you asked that messages can never trigger actions.
     */
    MESSAGE,
    /**
     * 0.9, a web search's own rule: "Ask before searching: Always ask", or a query with words that aren't yours (core web
     * QueryCheck: from a web page, the screen or another AI). Always a card; a yes may answer it.
     */
    SEARCH,
    /** 0.9: a web search with words from something Robot read on your phone (QueryCheck's personal words): a card answered by a tap. */
    SEARCH_PERSONAL,
    /** 0.9: a Termux command or script shows its Run / Edit / Cancel card (TermuxPolicy's CARD): a yes may answer it. */
    TERMUX,
    /** 0.9: a Termux command with a warning, one Robot couldn't read fully, or "Never (tap only)": always a card, answered by a tap. */
    COMMAND,
    /** 0.9: a Termux command in a conversation with content Robot didn't write (rule 3): always a card, answered by a tap. */
    FROM_CONTENT,
    /**
     * 1.4, Robot Files (F9): a helper's card that would send what Robot found in or read from your files (Personal.FILES):
     * answered only by a tap, never a spoken yes, whatever "Voice yes can send personal content" says. A helper card's
     * reason only, never the approval gate's (ApprovalGate.decide never sees it).
     */
    FILES,
    /**
     * 1.1: a dated reminder ("remind me to … on the 20th", or an alarm offered as one): an event with an alert, always on
     * add_event's card, which a yes may answer, whatever Settings › Actions says for new events.
     */
    REMINDER,
}

/** The one-time line after updating to 0.4.0 ([RobotSettings.safetyNotice]); tapping it opens Settings › Safety & privacy. */
object SafetyNotice {
    /** The line, in words without a language yet (1.1). */
    val text: Phrase get() = TurnStrings.safetyNotice("page" to UiStrings.inSettings(UiStrings.safety))

    val TEXT: String get() = text.en()
}

/** What Robot may do while the phone is locked, as the panel and the model are told. */
object LockScreen {
    /** Questions always; actions only those in Settings › Safety & privacy › "Works on the lock screen". */
    val DEFAULT: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM)

    /** The actions that may be allowed there (Robot's own internal tool never is). */
    val CHOICES: List<ToolName> get() = ToolName.entries.filter { !it.internal }

    /** "questions, timers and alarms" (the panel's status line), "questions only"; in [l] (1.1: the menus' language). */
    fun allowed(tools: Set<ToolName>, l: Langs = Langs.EN): String {
        val names = CHOICES.filter { it in tools }.map { lower(it, l) }
        if (names.isEmpty()) return TurnStrings.lockQuestionsOnly().render(l)
        return Fmt.list(listOf(TurnStrings.lockQuestions().render(l)) + names, ListKind.AND, l.say)
    }

    /** The empty panel's headline on the lock screen, in [l] (1.1: the menus' language). */
    fun headline(tools: Set<ToolName>, l: Langs = Langs.EN): String = when {
        tools == DEFAULT -> TurnStrings.lockAskOrTimer().render(l)
        tools.isEmpty() -> TurnStrings.lockAsk().render(l)
        else -> TurnStrings.lockAskOrUse("list" to CHOICES.filter { it in tools }.joinToString(", ") { lower(it, l) }).render(l)
    }

    /** A tool's title inside a sentence: "timers", « minuteries » (its first letter; a brand name keeps its capitals). */
    private fun lower(t: ToolName, l: Langs): String = t.titleText.render(l).replaceFirstChar { it.lowercase(Locale.ROOT) }

    /**
     * The system prompt's line while locked. The default set keeps the exact words Robot always used (the prompt's tokens
     * are counted and cached); another set names its actions. The model's words: English.
     */
    fun promptLine(tools: Set<ToolName>): String = when {
        tools == DEFAULT -> " The phone is locked: you can answer questions and set timers and alarms only." // l10n: not text (model prompt)
        tools.isEmpty() -> " The phone is locked: you can answer questions only." // l10n: not text (model prompt)
        else -> " The phone is locked: you can answer questions and use only these actions: " + // l10n: not text (model prompt)
            CHOICES.filter { it in tools }.joinToString(", ") { it.title.lowercase(Locale.ROOT) } + "."
    }
}

/**
 * A card answered in words (0.4.0, Settings › Safety & privacy › "Answer cards by voice"): while a card waits, a spoken
 * or typed "yes", "do it", "go ahead", "send it", "oui", "envoie" confirms it and "no", "cancel", "never mind", "non",
 * "annule" declines it ([TextCommand.cancelKind]'s words). Everything said must be used up, so "yes but at 5" or "send it
 * to Sam" is neither: the card keeps waiting for a tap.
 */
object CardAnswer {
    enum class Kind { YES, NO }

    /** The waiting card, as far as a spoken or typed answer goes ([Card.tapFor], [Card.choices], what its tap sends). */
    data class Waiting(val choices: Int = 0, val tapFor: AskWhy? = null, val personal: Boolean = false, val confirm: String? = null,
        /** 0.9: the card's own words for why a tap is needed (a Termux command's: "This command deletes files…"). */
        val say: String? = null)

    /** What an answer does: decline, confirm, take the card's only choice, or say why a tap is needed ([Reply.say]). */
    enum class Does { DECLINE, CONFIRM, CHOOSE_ONLY, TAP }
    /** [text]: 1.1, [say] in words without a language yet (Robot says it in the reply language). */
    data class Reply(val does: Does, val say: String? = null) {
        var text: Phrase? = say?.let(Phrase::plain)
            private set

        constructor(does: Does, text: Phrase) : this(does, text.en()) {
            this.text = text
        }
    }

    val PROMPT: String get() = TurnStrings.sayYesOrNo().en()
    val TAP_ONE: String get() = TurnStrings.tapOne().en()
    val TAP_LEARNED: String get() = TurnStrings.tapLearned().en()
    val TAP_UNSURE: String get() = TurnStrings.tapUnsure().en()
    val TAP_ONLINE: String get() = tapOnline().en()
    val TAP_SEARCH: String get() = TurnStrings.tapSearch().en()

    /** The first-use card's three buttons, by the names the card shows (the menus' language). */
    private fun tapOnline(): Phrase = TurnStrings.tapOnline("always" to UiLabel(UiStrings.allowAlways), "once" to UiLabel(InfoStrings.justThisTime),
        "no" to UiLabel(InfoStrings.no))

    /**
     * Why a card needs a tap, whatever is said ([Card.tapFor]): a name learned from a Cheogram notification (whoever sent
     * it chose it), not being sure who or which (several people, a near match, a number or address the model wrote, a
     * loose app name), or going online for the first time. Null: a yes may answer it.
     */
    fun tapFor(why: Set<AskWhy>): AskWhy? = listOf(AskWhy.MESSAGE, AskWhy.LEARNED, AskWhy.WHO, AskWhy.ONLINE, AskWhy.SEARCH_PERSONAL, AskWhy.FROM_CONTENT,
        AskWhy.COMMAND).firstOrNull { it in why }

    /** What Robot says when a yes can't answer a card that needs a tap for [why]. */
    fun tapWords(why: AskWhy): String = tapText(why).en()

    /** [tapWords] in words without a language yet (1.1). */
    fun tapText(why: AskWhy): Phrase = when (why) {
        AskWhy.LEARNED -> TurnStrings.tapLearned()
        AskWhy.WHO -> TurnStrings.tapUnsure()
        AskWhy.ONLINE -> tapOnline()
        AskWhy.MESSAGE -> app.heyrobot.core.messages.MessageQuarantine.cardLineText()
        AskWhy.SEARCH_PERSONAL -> TurnStrings.tapSearch()
        AskWhy.FROM_CONTENT -> TurnStrings.tapFromContent("run" to UiLabel(UiStrings.run))
        AskWhy.COMMAND -> TurnStrings.tapCommand("run" to UiLabel(UiStrings.run))
        AskWhy.FILES -> TurnStrings.tapFiles()
        else -> TurnStrings.tapOne()
    }

    val TAP_FROM_CONTENT: String get() = tapText(AskWhy.FROM_CONTENT).en()
    val TAP_COMMAND: String get() = tapText(AskWhy.COMMAND).en()

    /** What Robot says when [card] starts waiting and replies are spoken: how it may be answered ("no" always works). */
    fun prompt(card: Waiting): String = promptText(card).en()

    /** [prompt] in words without a language yet (1.1). A card's own words ([Waiting.say]) are as the card has them. */
    fun promptText(card: Waiting): Phrase = when {
        card.tapFor != null -> TurnStrings.orSayNo("why" to (card.say?.let(Phrase::plain) ?: tapText(card.tapFor)))
        card.choices > 1 -> TurnStrings.tapOneOrNo()
        else -> TurnStrings.sayYesOrNo()
    }

    /**
     * What [text] does to the waiting [card]: null when it isn't a plain yes or no ([of]), or it was [spoken] with
     * "Answer cards by voice" off ([voiceCards]; a typed answer still works then). A no always declines. A yes confirms,
     * except a card that needs a tap ([Waiting.tapFor]), one with several choices, or one whose tap sends personal content
     * to a helper unless "Voice yes can send personal content" ([voiceYesPersonal]); a card with one choice takes it.
     */
    fun reply(text: String, card: Waiting, voiceCards: Boolean, voiceYesPersonal: Boolean, spoken: Boolean, wakePhrases: List<String> = emptyList()): Reply? {
        if (spoken && !voiceCards) return null
        val kind = of(text, wakePhrases) ?: return null
        return when {
            kind == Kind.NO -> Reply(Does.DECLINE)
            card.tapFor != null -> Reply(Does.TAP, card.say?.let(Phrase::plain) ?: tapText(card.tapFor))
            card.choices > 1 -> Reply(Does.TAP, TurnStrings.tapOne())
            card.personal && !voiceYesPersonal -> Reply(Does.TAP,
                card.confirm?.let { TurnStrings.tapToShare("button" to it) } ?: TurnStrings.tapButtonToShare())
            card.choices == 1 -> Reply(Does.CHOOSE_ONLY)
            else -> Reply(Does.CONFIRM)
        }
    }

    private val YES = phrases(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "do it", "do that", "go ahead", "go for it", "go", "send it", "send", "send that",
        "confirm", "confirmed", "absolutely", "definitely", "of course", "please do", "yes please", "sounds good", "right", "correct",
        "oui", "ouais", "daccord", "vas y", "allez y", "allez", "envoie", "envoie le", "envoie la", "envoyer", "fais le", "fais ca", "cest bon",
        "bien sur", "parfait", "exactement", "confirme",
    )
    /** Allowed around the yes: the wake phrase (heard whole while Robot waits), politeness, "it", "now". */
    private val AROUND = phrases(
        "hey robot", "hi robot", "ok robot", "okay robot", "robot", "um", "uh", "euh", "hmm", "please", "thanks", "thank you", "merci",
        "sil te plait", "sil vous plait", "it", "that", "now", "then", "alors", "maintenant", "oh", "well", "yes", "oui",
    )
    private const val MAX_WORDS = 8

    private fun phrases(vararg p: String): List<List<String>> = p.map { it.split(' ') }

    private fun at(w: List<String>, i: Int, list: List<List<String>>): List<String>? =
        list.filter { p -> i + p.size <= w.size && p.indices.all { w[i + it] == p[it] } }.maxByOrNull { it.size }

    /** [text] answers the waiting card, or null (it isn't a plain yes or no). [wakePhrases]: yours, said in front. */
    fun of(text: String, wakePhrases: List<String> = emptyList()): Kind? {
        if (text.isBlank()) return null
        if (TextCommand.cancelKind(text, wakePhrases) != null) return Kind.NO
        val w = HelperRouter.plain(TextCommand.stripWake(text, wakePhrases, loose = true)).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > MAX_WORDS) return null
        var i = 0
        var yes = false
        while (i < w.size) {
            val y = at(w, i, YES)
            val f = at(w, i, AROUND)
            when {
                y != null && y.size >= (f?.size ?: 0) -> { yes = true; i += y.size }
                f != null -> i += f.size
                else -> return null
            }
        }
        return if (yes) Kind.YES else null
    }
}
