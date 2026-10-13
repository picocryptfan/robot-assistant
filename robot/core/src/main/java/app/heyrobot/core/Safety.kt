package app.heyrobot.core

import java.util.Locale

// Pure. Settings › Safety & privacy (0.4.0): every rule that makes an action ask first, or keeps something on the phone,
// is a switch with a sensible default, so each person picks their own safety level. ApprovalGate reads them.

/**
 * "Safety cards": when content Robot didn't write (screen text, a photo, what a read tool found, Claude's words) makes an
 * action that writes or leaves the phone show its card even under "Just do it".
 */
enum class SafetyCards(val title: String, val detail: String) {
    /** Before 0.4.0's switch, the only rule: any content in the conversation, every such action asks. */
    AFTER_READING("Always after Robot reads something",
        "Once a conversation has screen text, a photo, or something Robot read or a helper said, every action that writes or leaves the phone asks first."),
    /** The default: content only forces a card for an action you didn't ask for in your own words this time. */
    UNASKED("When you didn't ask for it",
        "Content Robot read makes an action ask first only when you didn't ask for that kind of action yourself this time. Text on a screen, in a photo or in what Robot found never counts as your words."),
    /** Content never forces a card; only not being sure who (or which) does. */
    UNSURE_ONLY("Only when Robot isn't sure who",
        "Content never makes an action ask first. Cards still show for actions set to Ask first, and when Robot isn't sure who or which."),
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
}

/** The one-time line after updating to 0.4.0 ([RobotSettings.safetyNotice]); tapping it opens Settings › Safety & privacy. */
object SafetyNotice {
    const val TEXT = "New: Settings › Safety & privacy. Cards now appear when you didn't ask for an action."
}

/** What Robot may do while the phone is locked, as the panel and the model are told. */
object LockScreen {
    /** Questions always; actions only those in Settings › Safety & privacy › "Works on the lock screen". */
    val DEFAULT: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM)

    /** The actions that may be allowed there (Robot's own internal tool never is). */
    val CHOICES: List<ToolName> get() = ToolName.entries.filter { !it.internal }

    /** "questions, timers and alarms" (the panel's status line), "questions only". */
    fun allowed(tools: Set<ToolName>): String {
        val names = CHOICES.filter { it in tools }.map { it.title.lowercase(Locale.ROOT) }
        val all = listOf("questions") + names
        return if (all.size == 1) "questions only" else all.dropLast(1).joinToString(", ") + " and " + all.last()
    }

    /** The empty panel's headline on the lock screen. */
    fun headline(tools: Set<ToolName>): String = when {
        tools == DEFAULT -> "Ask a question, or set a timer or alarm."
        tools.isEmpty() -> "Ask a question."
        else -> "Ask a question, or use: " + CHOICES.filter { it in tools }.joinToString(", ") { it.title.lowercase(Locale.ROOT) } + "."
    }

    /**
     * The system prompt's line while locked. The default set keeps the exact words Robot always used (the prompt's tokens
     * are counted and cached); another set names its actions.
     */
    fun promptLine(tools: Set<ToolName>): String = when {
        tools == DEFAULT -> " The phone is locked: you can answer questions and set timers and alarms only."
        tools.isEmpty() -> " The phone is locked: you can answer questions only."
        else -> " The phone is locked: you can answer questions and use only these actions: " +
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
    data class Reply(val does: Does, val say: String? = null)

    const val PROMPT = "Say yes or no."
    const val TAP_ONE = "Tap the one you mean on the card."
    const val TAP_LEARNED = "Robot learned that name from a Cheogram notification: check who it goes to on the card, then tap."
    const val TAP_UNSURE = "Robot isn't sure who or which it is: check the card, then tap."
    const val TAP_ONLINE = "Tap Allow always, Just this time or No on the card."
    const val TAP_MESSAGE = app.heyrobot.core.messages.MessageQuarantine.CARD_LINE
    const val TAP_SEARCH = "This search has words from something Robot read on your phone: check it on the card, then tap."

    /**
     * Why a card needs a tap, whatever is said ([Card.tapFor]): a name learned from a Cheogram notification (whoever sent
     * it chose it), not being sure who or which (several people, a near match, a number or address the model wrote, a
     * loose app name), or going online for the first time. Null: a yes may answer it.
     */
    fun tapFor(why: Set<AskWhy>): AskWhy? = listOf(AskWhy.MESSAGE, AskWhy.LEARNED, AskWhy.WHO, AskWhy.ONLINE, AskWhy.SEARCH_PERSONAL, AskWhy.FROM_CONTENT,
        AskWhy.COMMAND).firstOrNull { it in why }

    /** What Robot says when a yes can't answer a card that needs a tap for [why]. */
    fun tapWords(why: AskWhy): String = when (why) {
        AskWhy.LEARNED -> TAP_LEARNED
        AskWhy.WHO -> TAP_UNSURE
        AskWhy.ONLINE -> TAP_ONLINE
        AskWhy.MESSAGE -> TAP_MESSAGE
        AskWhy.SEARCH_PERSONAL -> TAP_SEARCH
        AskWhy.FROM_CONTENT -> TAP_FROM_CONTENT
        AskWhy.COMMAND -> TAP_COMMAND
        else -> TAP_ONE
    }

    const val TAP_FROM_CONTENT = "Robot read this from content it didn't write: check the card, then tap Run."
    const val TAP_COMMAND = "This command has warnings: check the card, then tap Run."

    /** What Robot says when [card] starts waiting and replies are spoken: how it may be answered ("no" always works). */
    fun prompt(card: Waiting): String = when {
        card.tapFor != null -> (card.say ?: tapWords(card.tapFor)) + " Or say no."
        card.choices > 1 -> "Tap the one you mean on the card, or say no."
        else -> PROMPT
    }

    /**
     * What [text] does to the waiting [card]: null when it isn't a plain yes or no ([of]), or it was [spoken] with
     * "Answer cards by voice" off ([voiceCards]; a typed answer still works then). A no always declines. A yes confirms,
     * except a card that needs a tap ([Waiting.tapFor]), one with several choices, or one whose tap sends personal content
     * to Claude unless "Voice yes can send personal content" ([voiceYesPersonal]); a card with one choice takes it.
     */
    fun reply(text: String, card: Waiting, voiceCards: Boolean, voiceYesPersonal: Boolean, spoken: Boolean, wakePhrases: List<String> = emptyList()): Reply? {
        if (spoken && !voiceCards) return null
        val kind = of(text, wakePhrases) ?: return null
        return when {
            kind == Kind.NO -> Reply(Does.DECLINE)
            card.tapFor != null -> Reply(Does.TAP, card.say ?: tapWords(card.tapFor))
            card.choices > 1 -> Reply(Does.TAP, TAP_ONE)
            card.personal && !voiceYesPersonal -> Reply(Does.TAP, "Tap ${card.confirm ?: "the button"} to share it.")
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
        val w = ClaudeRouter.plain(TextCommand.stripWake(text, wakePhrases, loose = true)).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
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
