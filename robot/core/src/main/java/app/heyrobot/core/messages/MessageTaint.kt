package app.heyrobot.core.messages

import app.heyrobot.core.Action
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.AskWhy
import app.heyrobot.core.GateDecision
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.memory.MemoryCommand
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryRoute
import app.heyrobot.core.memory.MemoryText
import app.heyrobot.core.memory.Until
import java.util.Locale

// Pure. Messages are written by anyone who can reach you, so their text is quarantined (design §B10), by code and
// without a switch, because you asked that messages can never trigger actions:
// - Text that came from a message never becomes an action: not through a router, not read back by the microphone,
//   not as a tool call in a summary (summaries and translations are one-shot requests with no tools). Such an action
//   is FORBIDDEN, not even a card.
// - Once a conversation holds message text ([MessageTaint]), every action that writes or leaves the phone asks first,
//   whatever Safety cards says; one that would send anything to someone other than that conversation's sender (a
//   text, an email, a helper, an online lookup) needs a tap: a spoken yes doesn't count. Reads that stay on the phone
//   don't ask (they only bring more to read).
// - A reply's recipient is the notification's own conversation (ReplyPlan.kt).
// - Memory (0.7, with Robot's memory): nothing from a message, or from a conversation that holds one, becomes memory by
//   itself. What the model proposes to remember there is FORBIDDEN (its words may be the message's); your own
//   "remember that …" there is never saved at once: a card shows exactly the words kept and only a tap keeps them, as
//   content ([rememberAfterMessages]); a code or a password is refused there (Settings › Memory › Add something takes
//   it); and suggestions never read such a conversation (memory Suggestions.turnsOf).
//
// Wired in 0.7: ChatTurn.messages marks a turn that holds message text and TurnRules.untrusted counts it; the panel's
// TurnRunner carries the conversation's [MessageTaint], so ApprovalGate.decide(…, messages = true) asks before the
// contentAsks branch and [MessageQuarantine.why] adds AskWhy.MESSAGE (a tap) for anything that leaves for someone else.

/**
 * The marker a conversation carries once it holds text other people wrote: a message read, summarized or translated.
 * [conversations]: which ones ([MessageThread.key]); [senderKeys]: their senders, so a text back to the same person is
 * told apart from a text to anyone else. Never their words.
 */
data class MessageTaint(val conversations: Set<String> = emptySet(), val senderKeys: Set<String> = emptySet()) {
    val active: Boolean get() = conversations.isNotEmpty()

    /** The conversation now holds [thread]'s messages too. */
    fun with(thread: MessageThread): MessageTaint =
        MessageTaint(conversations + thread.key, senderKeys + thread.incoming.mapNotNull { it.senderKey?.lowercase(Locale.ROOT) })

    /** A recipient that is one of the senders (the caller resolves the address the action would use). */
    fun isSender(address: String?): Boolean = address != null && address.trim().lowercase(Locale.ROOT) in senderKeys

    /**
     * [isSender], or the same phone number: a JMP address in Cheogram ("+15145550101@cheogram.com") and that number in
     * the SMS app ("+1 514-555-0101") are one person. At least 7 digits on both sides, the last 10 equal.
     */
    fun isSenderAddress(address: String?): Boolean {
        if (address == null) return false
        if (isSender(address)) return true
        val d = phoneDigits(address) ?: return false
        return senderKeys.any { k -> phoneDigits(k)?.let { it.takeLast(10) == d.takeLast(10) } == true }
    }

    override fun toString(): String = "MessageTaint(conversations=${conversations.size}, senders=${senderKeys.size})"

    companion object {
        val NONE = MessageTaint()

        /** The digits of a phone number, or of a JMP address's local part ("+15145550101@cheogram.com"); null otherwise. */
        private fun phoneDigits(s: String): String? {
            val local = s.trim().substringBefore('@')
            if (!Regex("^\\+?[0-9 ()./-]{7,}$").matches(local)) return null
            return local.filter(Char::isDigit).takeIf { it.length >= 7 }
        }
    }
}

/** Where an action's request came from. */
enum class ActionOrigin {
    /** Your own words this turn (said or typed). */
    USER_WORDS,
    /** The model proposed it. */
    MODEL,
    /** A message's text: read off a notification, or heard back while Robot read it aloud. */
    INCOMING_TEXT,
}

/** What the quarantine adds to the gate's decision. */
enum class QuarantineVerdict {
    /** Nothing: the gate decides as usual. */
    NONE,
    /** A card, whatever Safety cards says; a spoken yes answers it. */
    CARD,
    /** A card answered only by a tap. */
    TAP_ONLY,
    /** Never: no card, no action. */
    FORBIDDEN,
}

/** The gate's decision with the quarantine applied. [decision] is null when the action is [forbidden]. */
data class QuarantinedDecision(val decision: GateDecision?, val tapOnly: Boolean, val verdict: QuarantineVerdict) {
    val forbidden: Boolean get() = verdict == QuarantineVerdict.FORBIDDEN
    /** It runs without anyone tapping or saying yes. */
    val runsAlone: Boolean get() = !forbidden && decision == GateDecision.RUN
}

/** Something Robot read aloud from a message, and when it finished. */
class ReadAloud(val text: String, val endedAt: Long) {
    override fun toString(): String = "ReadAloud(chars=${text.length}, endedAt=$endedAt)"
}

object MessageQuarantine {
    const val CARD_LINE = "A message is in this conversation: check this, then tap."
    const val CARD_LINE_FR = "Un message fait partie de cette conversation : vérifie, puis touche."
    /** What Robot says when a message's words would have been an action. */
    const val NOT_FROM_MESSAGES = "I don't act on what a message says."
    const val NOT_FROM_MESSAGES_FR = "Je n'agis pas selon ce que dit un message."
    /** What the microphone hears this soon after Robot read a message aloud may be the message itself. */
    const val ECHO_MS = 15_000L
    /**
     * A spoken yes on a reply card counts only this long after Robot finished saying a message's words (a message, its
     * translation, a summary, a draft, "Mom wrote again: «…»"): a "yes" in them, heard back by the microphone, would be
     * Robot's own voice. A tap always counts.
     */
    const val YES_AFTER_MS = 3_000L
    const val SAY_YES_AGAIN = "Tap Send, or say yes again."
    const val SAY_YES_AGAIN_FR = "Touche Envoyer, ou redis oui."

    /** A spoken yes may answer a reply card [now]: Robot isn't saying a message's words, and finished them [YES_AFTER_MS] ago or more. */
    fun voiceYesNow(readAloud: List<ReadAloud>, now: Long): Boolean = readAloud.none { now - it.endedAt < YES_AFTER_MS }

    /** Actions whose effect reaches someone else: a text, an email, a helper, or anything that goes online. */
    fun leavesForOthers(tool: ToolName): Boolean =
        tool == ToolName.SEND_TEXT || tool == ToolName.DRAFT_EMAIL || tool == ToolName.ASK_CLAUDE || tool.online

    /**
     * The quarantine's verdict for [tool] asked for by [origin] in a conversation marked [taint]. [toSender]: the action
     * goes back to that conversation's sender (a text to Mom after Mom's message), so it isn't "someone else".
     */
    fun verdict(tool: ToolName, origin: ActionOrigin, taint: MessageTaint, toSender: Boolean = false): QuarantineVerdict = when {
        origin == ActionOrigin.INCOMING_TEXT -> QuarantineVerdict.FORBIDDEN
        !taint.active -> QuarantineVerdict.NONE
        // 0.7 memory: the model's words may be the message's, so nothing it proposes is remembered here; your own
        // "remember that …" is a card a tap answers, showing the words kept.
        tool == ToolName.REMEMBER && origin != ActionOrigin.USER_WORDS -> QuarantineVerdict.FORBIDDEN
        tool == ToolName.REMEMBER -> QuarantineVerdict.TAP_ONLY
        tool.reads && !tool.online -> QuarantineVerdict.NONE
        leavesForOthers(tool) && !(toSender && tool == ToolName.SEND_TEXT) -> QuarantineVerdict.TAP_ONLY
        else -> QuarantineVerdict.CARD
    }

    /** A text back to the conversation's own sender (resolved by the app before the card), not to "someone else". */
    fun toSender(a: Action, taint: MessageTaint): Boolean = a is Action.SendText && taint.isSenderAddress(a.target?.address)

    /**
     * What the gate's reasons gain for [a] in a conversation marked [taint]: [AskWhy.MESSAGE] (a card answered by a tap)
     * when it would send something to anyone but the message's sender, or go online; nothing otherwise (the gate's
     * `messages` flag already makes every write ask).
     */
    fun why(a: Action, taint: MessageTaint, origin: ActionOrigin = ActionOrigin.MODEL): Set<AskWhy> {
        if (!taint.active) return emptySet()
        val v = verdict(a.tool, origin, taint, toSender(a, taint))
        return if (v == QuarantineVerdict.TAP_ONLY || v == QuarantineVerdict.FORBIDDEN || a.online) setOf(AskWhy.MESSAGE) else emptySet()
    }

    /** What a forbidden action's card says: a memory the model proposed, or anything else a message's words asked for. */
    fun forbidden(tool: ToolName): String = if (tool == ToolName.REMEMBER) NOT_REMEMBERED else NOT_FROM_MESSAGES

    /** The model proposed a memory in a conversation that holds a message: not kept, and how to keep something yourself. */
    const val NOT_REMEMBERED = "A message is in this conversation, so Robot keeps nothing the model proposes to remember. " +
        "To keep something, say “remember that …” yourself: a card shows the words, and only a tap keeps them."

    /** A code or a password in your "remember that …" in a conversation that holds a message: not from here. */
    const val NO_SECRET_HERE = "A message is in this conversation, so Robot doesn't keep a code or a password from it. " +
        "Add it in Settings › Memory instead."
    const val NO_SECRET_HERE_FR = "Un message fait partie de cette conversation : Robot n'y garde ni code ni mot de passe. " +
        "Ajoute-le plutôt dans Réglages › Mémoire."
    /** The reply under that card. */
    const val CHECK_AND_TAP = "Check the words on the card, then tap Remember to keep them."
    const val CHECK_AND_TAP_FR = "Vérifie les mots sur la carte, puis touche Remember pour les garder."

    /** How your own "remember that …" goes in a conversation that holds a message ([rememberAfterMessages]). */
    sealed class AfterMessages {
        /** Through the turn runner as your words (ActionOrigin.USER_WORDS): a card with exactly [call]'s words, a tap only. */
        data class Card(val call: RawToolCall, val fr: Boolean) : AfterMessages()
        /** Not kept: [text] says why. */
        data class Refused(val text: String, val fr: Boolean) : AfterMessages()
    }

    /**
     * Your "remember that …" ([route]) in a conversation marked [taint]: null when no message is in it (memory's router
     * saves it at once, as always) or it isn't a remember; otherwise never saved at once. Its words go through the turn
     * runner as one remember call of your own words, so ApprovalGate and [verdict] give a card that only a tap answers,
     * showing exactly what is kept, kept as content; a code or a password is refused there.
     */
    fun rememberAfterMessages(route: MemoryRoute, taint: MessageTaint): AfterMessages? {
        if (!taint.active) return null
        val c = ((route as? MemoryRoute.Run)?.command as? MemoryCommand.Remember) ?: return null
        val fr = c.language == "fr"
        if (c.secret) return AfterMessages.Refused(if (fr) NO_SECRET_HERE_FR else NO_SECRET_HERE, fr)
        val args = linkedMapOf<String, Any?>("text" to c.text, "kind" to if (c.kind == MemoryKind.PREFERENCE) "preference" else "fact")
        c.until?.let { u -> args["until"] = when (u) { Until.TONIGHT -> "tonight"; Until.TOMORROW -> "tomorrow"; Until.END_OF_WEEK -> "this week" } }
        return AfterMessages.Card(RawToolCall(ToolName.REMEMBER.id, args), fr)
    }

    /** [gate]'s decision with [verdict] applied: a run becomes a card; unlock-first and off stay as they are. */
    fun apply(gate: GateDecision, verdict: QuarantineVerdict): QuarantinedDecision = when (verdict) {
        QuarantineVerdict.FORBIDDEN -> QuarantinedDecision(null, false, verdict)
        QuarantineVerdict.NONE -> QuarantinedDecision(gate, false, verdict)
        QuarantineVerdict.CARD, QuarantineVerdict.TAP_ONLY -> {
            val d = if (gate == GateDecision.RUN) GateDecision.CONFIRM else gate
            QuarantinedDecision(d, verdict == QuarantineVerdict.TAP_ONLY && d == GateDecision.CONFIRM, verdict)
        }
    }

    /**
     * The whole decision, as ApprovalGate will make it: the gate first (the conversation counts as content while it
     * holds a message), then the quarantine on top.
     */
    fun decide(tool: ToolName, locked: Boolean, settings: RobotSettings, origin: ActionOrigin, taint: MessageTaint,
        why: Set<AskWhy> = emptySet(), content: Boolean = false, asked: Boolean = origin == ActionOrigin.USER_WORDS,
        toSender: Boolean = false): QuarantinedDecision {
        val gate = ApprovalGate.decide(tool, locked, settings, why, content = content || taint.active, asked = asked)
        return apply(gate, verdict(tool, origin, taint, toSender))
    }

    /**
     * Where [heard] came from: if it repeats most of a message Robot read aloud in the last [ECHO_MS] (the speaker
     * reached the microphone, or someone read it back), it is that message's text, not your words. At least three
     * search terms, 80% of them found in one message.
     */
    fun originOf(heard: String, readAloud: List<ReadAloud>, now: Long): ActionOrigin {
        val h = MemoryText.tokens(heard).toSet()
        if (h.size < 3) return ActionOrigin.USER_WORDS
        for (r in readAloud) {
            if (now - r.endedAt > ECHO_MS) continue
            val m = MemoryText.tokens(r.text).toSet()
            if (h.count { it in m } * 10 >= h.size * 8) return ActionOrigin.INCOMING_TEXT
        }
        return ActionOrigin.USER_WORDS
    }
}
