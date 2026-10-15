// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import java.text.Normalizer
import java.util.Locale

// Pure. How a reply to a message goes out (design §B5), decided by code. The recipient is the notification's own
// conversation: [ReplyPlanner.plan] takes the [MessageThread] and the words, and has no way to name anyone else, so
// nothing the model writes can choose or change who gets it. By default a card shows exactly what goes where, and a
// tap sends it: a spoken "yes" is not enough to send (it could be someone else's voice, or Robot's own reading the
// card), unless you turn "Answer reply cards by voice" on, and never for a group, a reply the model drafted or a
// translation. Just before sending, [ReplyGuard] reads the notification again: gone, changed or newer means the card
// updates or the chat opens with the reply instead.

/** Who wrote the reply's words. Only [DICTATED] is yours word for word. */
enum class ReplySource { DICTATED, MODEL_DRAFT, TRANSLATION }

/** What sends it. */
enum class Confirm {
    /** The default: the card's Send button. */
    TAP,
    /** "Answer reply cards by voice" on: a spoken yes or a tap. */
    VOICE_OR_TAP,
    /** "Send my replies to Mom without a card" (per person, off): Robot says it, and sends after 3 s unless you say stop. */
    STOP_WINDOW,
}

/** Lines the card adds. */
enum class ReplyNote {
    /** "to the group Famille": a tap is needed. */
    GROUP,
    /** Cheogram sends an emoji alone as a reaction. */
    EMOJI_REACTION,
    /** An SMS or MMS (carrier charges apply). */
    SMS,
    /** Signal marks the chat read and sends read receipts when you reply from a notification. */
    MARKS_READ,
    /** This app's reply wasn't checked in its source (Molly, Google Messages). */
    UNVERIFIED_APP,
    /** The app's reply action opens a screen of its own. */
    OPENS_APP,
}

/** Where a reply goes: everything from the notification, nothing from the model. */
data class ReplyTarget(val app: MessageApp, val conversationId: String, val notificationKey: String, val action: ActionRef, val to: String, val isGroup: Boolean)

enum class HandOffWhy {
    /** "How replies go: Open the chat with my reply; I press Send". */
    ROUTE_OPEN_CHAT,
    /** The notification has no reply action Robot can use (or it has gone). */
    NO_REPLY_ACTION,
}

enum class RefuseWhy { OFF, EMPTY, TOO_LONG, LOCKED, AUTH_REQUIRED }

sealed class ReplyPlan {
    /** A card (or the stop window) for [text] to [target]. [newestSeen]: the newest message the card was made with. */
    data class Send(val target: ReplyTarget, val text: String, val confirm: Confirm, val notes: Set<ReplyNote>, val newestSeen: Long?) : ReplyPlan() {
        val voiceYesCounts: Boolean get() = confirm != Confirm.TAP
        override fun toString(): String = "Send(app=${target.app}, group=${target.isGroup}, confirm=$confirm, notes=$notes, chars=${text.length})"
    }

    /** Open the chat with [text] as a draft: nothing is sent until you press Send there. */
    data class HandOff(val app: MessageApp, val conversationId: String, val text: String, val why: HandOffWhy) : ReplyPlan() {
        override fun toString(): String = "HandOff(app=$app, why=$why, chars=${text.length})"
    }

    data class Refused(val why: RefuseWhy) : ReplyPlan()
}

object ReplyPlanner {
    const val MAX_CHARS = 2_000
    const val STOP_WINDOW_MS = 3_000L

    /**
     * The plan for replying [text] to [thread]. [locked]: the phone is locked. [noCard]: "Send my replies to … without
     * a card" is on for this person (used only unlocked, for your own dictated words, in a 1:1 chat). [sure]: you chose
     * this conversation (you named it, picked it, or Robot just read it to you); false when Robot took the newest one for a
     * "reply …" that named no one, and then it never goes without a tap or a yes, whatever the switches say.
     */
    fun plan(thread: MessageThread, text: String, source: ReplySource, s: MessageSettings, locked: Boolean, noCard: Boolean = false,
        sure: Boolean = true): ReplyPlan {
        if (!s.on) return ReplyPlan.Refused(RefuseWhy.OFF)
        val t = text.trim()
        if (t.isEmpty()) return ReplyPlan.Refused(RefuseWhy.EMPTY)
        if (t.length > MAX_CHARS) return ReplyPlan.Refused(RefuseWhy.TOO_LONG)
        if (locked && s.locked != MessagesLocked.READ_AND_REPLY) return ReplyPlan.Refused(RefuseWhy.LOCKED)
        if (s.replyRoute == ReplyRoute.OPEN_CHAT) return ReplyPlan.HandOff(thread.app, thread.conversationId, t, HandOffWhy.ROUTE_OPEN_CHAT)
        val action = thread.reply ?: return ReplyPlan.HandOff(thread.app, thread.conversationId, t, HandOffWhy.NO_REPLY_ACTION)
        if (locked && action.authRequired) return ReplyPlan.Refused(RefuseWhy.AUTH_REQUIRED)
        val confirm = when {
            thread.isGroup || source != ReplySource.DICTATED || locked -> Confirm.TAP
            (noCard || !s.replyCard) && sure -> Confirm.STOP_WINDOW
            s.voiceYesSends -> Confirm.VOICE_OR_TAP
            else -> Confirm.TAP
        }
        val notes = buildSet {
            if (thread.isGroup) add(ReplyNote.GROUP)
            if (thread.app.emojiReplyIsReaction && ReplyText.emojiOnly(t)) add(ReplyNote.EMOJI_REACTION)
            if (thread.app.sms) add(ReplyNote.SMS)
            if (thread.app.replyMarksRead) add(ReplyNote.MARKS_READ)
            if (!thread.app.verified) add(ReplyNote.UNVERIFIED_APP)
            if (action.showsUserInterface) add(ReplyNote.OPENS_APP)
        }
        val target = ReplyTarget(thread.app, thread.conversationId, thread.notificationKey, action, thread.name, thread.isGroup)
        return ReplyPlan.Send(target, t, confirm, notes, thread.newestIncoming)
    }

    /**
     * "Reply to Mom": the conversations whose title or sender is called [name] (the caller adds People aliases by key).
     * Several make a pick card; none, "No message from Mom right now", and the usual texting route.
     */
    fun candidates(name: String, threads: List<MessageThread>): List<MessageThread> {
        val q = fold(name)
        if (q.isEmpty()) return emptyList()
        return threads.filter { t -> (listOfNotNull(t.title) + t.incoming.mapNotNull { it.senderName }).any { n -> fold(n).let { it == q || " $it ".contains(" $q ") } } }
    }

    /** The card's text: "Reply to **Mom** · Cheogram", the words in quotes, and the notes. */
    fun card(p: ReplyPlan.Send, lang: Lang): String = buildString {
        val to = p.target.to
        if (lang == Lang.FR) append(if (p.target.isGroup) "Répondre au groupe **$to**" else "Répondre à **$to**") else append(if (p.target.isGroup) "Reply to the group **$to**" else "Reply to **$to**")
        append(" · ").append(p.target.app.title).append('\n')
        append(if (lang == Lang.FR) "« ${p.text} »" else "«${p.text}»")
        for (n in p.notes) note(n, lang)?.let { append('\n').append(it) }
    }

    /** The card read aloud: "Reply to Mom: I'll be late. Tap Send to send it." (the words as they will go, then a full stop). */
    fun spoken(p: ReplyPlan.Send, lang: Lang): String {
        val said = if (p.text.last() in ".!?…") p.text else "${p.text}."
        return when {
            lang == Lang.FR && p.voiceYesCounts -> "Répondre à ${p.target.to} : $said Je l'envoie ?"
            lang == Lang.FR -> "Répondre à ${p.target.to} : $said Touche Envoyer pour l'envoyer."
            p.voiceYesCounts -> "Reply to ${p.target.to}: $said Send it?"
            else -> "Reply to ${p.target.to}: $said Tap Send to send it."
        }
    }

    fun note(n: ReplyNote, lang: Lang): String? = when (n) {
        ReplyNote.GROUP -> if (lang == Lang.FR) "Tout le groupe le verra. Touche Envoyer pour l'envoyer." else "Everyone in the group will see it. Tap Send to send it."
        ReplyNote.EMOJI_REACTION -> if (lang == Lang.FR) "Cette application envoie un émoji seul comme réaction." else "This app sends an emoji alone as a reaction."
        ReplyNote.SMS -> if (lang == Lang.FR) "C'est un texto : des frais de l'opérateur peuvent s'appliquer." else "This is an SMS: carrier charges may apply."
        ReplyNote.MARKS_READ -> if (lang == Lang.FR) "Cette application marque la conversation comme lue et peut envoyer un accusé de lecture." else "This app marks the chat read and may send a read receipt."
        ReplyNote.UNVERIFIED_APP -> if (lang == Lang.FR) "Robot n'a pas encore vérifié comment cette application envoie une réponse." else "Robot hasn't checked yet how this app sends a reply."
        ReplyNote.OPENS_APP -> null
    }

    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

/** The notification read again just before sending. */
sealed class Recheck {
    /** Send, with the action as it is now. */
    data class Ready(val target: ReplyTarget) : Recheck()
    /** [count] new messages since the card: the card shows them ("Mom wrote again: «…». Still send?"). */
    data class NewMessages(val count: Int, val thread: MessageThread) : Recheck()
    /** The notification or its reply action is gone: "Open the chat with your reply?" (the hand-off). */
    data object Gone : Recheck()
    /** Not the same conversation any more: never sent; the hand-off. */
    data object Changed : Recheck()
}

object ReplyGuard {
    /**
     * The conversation's mark-as-read action [ref] may fire now (the main process, "Mark it as read" or "After Robot reads
     * a message"): as with a reply, never one the app wants only from an unlocked phone while the phone is [locked].
     */
    fun markable(ref: ActionRef, locked: Boolean): Boolean = !(locked && ref.authRequired)

    /** [fresh]: the same notification read again (getActiveNotifications([key])), null when it is gone. */
    fun recheck(p: ReplyPlan.Send, fresh: MessageThread?): Recheck {
        if (fresh == null) return Recheck.Gone
        if (fresh.app != p.target.app || fresh.conversationId != p.target.conversationId || fresh.isGroup != p.target.isGroup) return Recheck.Changed
        val action = fresh.reply ?: return Recheck.Gone
        val seen = p.newestSeen ?: Long.MIN_VALUE
        val newer = fresh.incoming.count { !it.historic && it.time > seen }
        if (newer > 0) return Recheck.NewMessages(newer, fresh)
        return Recheck.Ready(p.target.copy(action = action, notificationKey = fresh.notificationKey))
    }
}

/** What you say while a reply waits its 3 seconds ("Send my replies to Mom without a card", or "Card before sending a reply" off). */
enum class WindowAnswer {
    /** "Yes", "send it": it goes when the 3 seconds end, as it would anyway. */
    YES,
    /** "Stop", "no, no, don't send it", « non, attends »: it stops, and the card waits for Send. */
    STOP,
    /**
     * Anything else stops it too (you began saying something, and a sent message can't be recalled); the card waits for
     * Send and your words go on as a question.
     */
    OTHER,
}

object ReplyWindow {
    /** A word of an objection anywhere in what was said, so a hurried "no no no" or "wait, don't" counts as "stop". */
    private val OBJECTION = Regex("""(?<![\p{L}\p{N}])(?:stop|stoppe|wait|hold on|no|nope|nah|don't|dont|do not|cancel|never mind|arrete|attends|attendez|non|annule|annuler|laisse faire|laisse tomber)(?![\p{L}\p{N}])""")

    fun answer(text: String, wakePhrases: List<String> = emptyList()): WindowAnswer {
        when (app.heyrobot.core.CardAnswer.of(text, wakePhrases)) {
            app.heyrobot.core.CardAnswer.Kind.YES -> return WindowAnswer.YES
            app.heyrobot.core.CardAnswer.Kind.NO -> return WindowAnswer.STOP
            null -> {}
        }
        return if (OBJECTION.containsMatchIn(MessagesRouter.fold(text))) WindowAnswer.STOP else WindowAnswer.OTHER
    }
}

object ReplyText {
    private val THAT = Regex("^(?:that|que)\\s+|^qu['’](?=\\p{L})", RegexOption.IGNORE_CASE)

    /**
     * The words after "reply": "that I'm late" → "I'm late." and "que je vais être en retard" → "Je vais être en
     * retard." (the clause made a sentence); anything else verbatim ("reply I'll be late" sends "I'll be late").
     */
    fun of(words: String): String {
        val w = words.replace(Regex("\\s+"), " ").trim()
        val m = THAT.find(w) ?: return w
        val rest = w.substring(m.range.last + 1).trim()
        if (rest.isEmpty()) return w
        val cap = rest.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (cap.last() in ".!?…") cap else "$cap."
    }

    /** Only emoji (with their modifiers and joiners): Cheogram sends that as a reaction. */
    fun emojiOnly(s: String): Boolean {
        var emoji = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> {}
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> emoji++
                cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 -> {}
                Character.getType(cp) == Character.MODIFIER_SYMBOL.toInt() && cp >= 0x1F3FB -> {}
                else -> return false
            }
        }
        return emoji > 0
    }
}

/**
 * Where "Open the chat" goes (design §B5's hand-off, phase 2's drafts): Cheogram's chat by its shortcut id
 * "<account>#<address>", with your words in its box; other apps get the words shared to them, and you pick the chat
 * there. Nothing is sent until you press Send in the app.
 */
object ChatLinks {
    /** Cheogram's shortcut id as your account and the chat's address; null when it isn't one (quiet hours: no shortcut). */
    fun cheogram(conversationId: String): Pair<String, String>? {
        val i = conversationId.indexOf('#')
        if (i <= 0 || i == conversationId.lastIndex) return null
        val account = conversationId.substring(0, i).trim()
        val jid = conversationId.substring(i + 1).trim()
        if ('@' !in account || '@' !in jid || jid.any { it.isWhitespace() }) return null
        return account to jid
    }
}
