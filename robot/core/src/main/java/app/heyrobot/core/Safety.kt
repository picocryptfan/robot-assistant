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
        "Once a conversation has screen text, a photo, or something Robot read or Claude said, every action that writes or leaves the phone asks first."),
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
