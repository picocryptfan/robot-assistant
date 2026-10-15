// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.LogCode
import app.heyrobot.core.headset.AnnounceCode
import app.heyrobot.core.messages.VoiceReply
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.ReplyResult
import app.heyrobot.core.output.ReplyWords
import app.heyrobot.core.output.SpokenAnswer
import app.heyrobot.core.output.SpokenCard
import app.heyrobot.core.output.SpokenCards
import app.heyrobot.core.output.SpokenChoice
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.SpokenStep

// Pure. Messages in the car (0.7; design §4.2 story 4, §4.5 flow 3, §4.9 rules 1–3): "Message from Marie. Read it?"
// "Yes." … "Reply?" "Tell her I'm 10 minutes away." "Reply to Marie: I'm 10 minutes away. Send?" "Send." "Handed to
// Cheogram." The reply goes to the conversation the notification belongs to (no name resolution, so a learned name
// can't redirect it), through its own Reply action (output ReplyChannel), and what that action receives is exactly the
// text that was read back ([ReplyDraft.text]), never a model's draft. That spoken read-back and send is only where 0.7's
// rules let a spoken yes send it (core messages HandsFreeReplies), with "Replies by voice" and "Send a reply after a
// spoken yes" on. Anything else (no Reply action, a group, too long to read back, only a tap sends it, the chat opens,
// the phone locked without a trusted session, 0.7's refusal, nothing heard) goes to the screen's own reply to the
// conversation, with your words: its card, its tap, its chat or its refusal, exactly as outside the car. 1.0 (the user,
// 13 Oct 2026): nothing is kept for when you park, and new texts ("Text Bob I'm late") go on as outside the car.

/** How a hands-free reply ended. Also the voice log's CAR_REPLY b when nothing was handed over. */
enum class ReplyEnd(override val id: Int, override val title: String) : LogCode {
    SENT(1, "handed to the app"), DECLINED(2, "you said no"), NOT_REPLYABLE(4, "no Reply action"), GROUP(5, "a group"),
    VOICE_OFF(6, "replies by voice off"), LOCKED(7, "locked"), SILENCE(8, "no answer"), TOO_LONG(9, "too long to read back"),
    /** 0.7's rules: only a tap sends it, or Robot never sends it itself ("Open the chat; I press Send"). */
    NEEDS_TAP(10, "needs a tap"),
    /** A newer message came while you dictated: nothing sent. */
    NEW_MESSAGES(11, "a newer message came"),
    /** The screen's own reply to the conversation took it (its card, its chat or its refusal), as outside the car. */
    ON_SCREEN(12, "on the screen"),
    /** The Reply action didn't take it. 3 was 1.0's test builds' "kept for parking": never used again. */
    FAILED(13, "didn't work"),
}

/** Whether a new message is announced in the car ([Skip.why]: the voice log's ANNOUNCE a). */
sealed class CarAnnouncement {
    data class Skip(val why: AnnounceCode) : CarAnnouncement()
    /** "Message from Marie. Read it?": a yes reads it ([CarMessages.says]). */
    data class Announce(val say: String) : CarAnnouncement()
    data class ReadNow(val say: String) : CarAnnouncement()
}

/** The reply, as it was read back and as it will be sent: [text] is both. */
data class ReplyDraft(val conversationKey: String, val to: String, val app: String, val text: String) {
    override fun toString(): String = "ReplyDraft(app=$app, chars=${text.length})"
}

sealed class ReplyStart {
    /** Say [say] ("Reply to Marie: … Send?") and wait for "send" or "yes". */
    data class ReadBack(val draft: ReplyDraft, val say: String) : ReplyStart()
    /** Ask what it should say ([say]). */
    data class AskText(val say: String) : ReplyStart()
    /**
     * Not read back and sent by voice here ([why]): the screen's own reply to the conversation takes your words [text] ("":
     * none yet), with its card, its tap, its chat and its rules, exactly as outside the car.
     */
    data class OnScreen(val why: ReplyEnd, val text: String) : ReplyStart()
}

sealed class ReplyNext {
    /** Hand exactly [text] to [conversationKey]'s Reply action. */
    data class Send(val conversationKey: String, val text: String) : ReplyNext()
    data class Done(val say: String, val why: ReplyEnd) : ReplyNext()
    data object Reread : ReplyNext()
    data class AskAgain(val say: String) : ReplyNext()
}

object CarMessages {
    /** A reply longer than this isn't read back in the car: the screen's reply card takes it. */
    const val MAX_REPLY = 300

    /** What confirms a reply besides yes (plain words). */
    val SEND_WORDS = listOf("send", "send it", "send that", "envoie", "envoie le", "envoie la", "envoyer")

    val ASK_REPLY = Line("Reply?", "Tu veux répondre?")
    val WHAT_REPLY = Line("What should the reply say?", "Qu'est-ce que je réponds?")
    val NOT_SENT = Line("Okay, not sent.", "D'accord, pas envoyé.")
    val ON_SCREEN = Line("Your reply is on the screen.", "Ta réponse est à l'écran.")

    /**
     * Whether [n] is announced in the car, by Settings › Car mode: off; locked without a trusted session never (§4.6);
     * "Announce from" (whoever Do Not Disturb lets through, everyone, or people you choose: [senderAllowed]); then the
     * sender only and a question, or the whole message when "Read the whole message" and "Read message text aloud when
     * others may hear: always" both say so (the car is never private).
     */
    fun announce(n: MessageNotice, s: CarSettings, locked: Boolean, trusted: Boolean, senderAllowed: Boolean, lang: SpokenLang): CarAnnouncement = when {
        s.announce == CarAnnounce.OFF -> CarAnnouncement.Skip(AnnounceCode.OFF)
        locked && !trusted -> CarAnnouncement.Skip(AnnounceCode.LOCKED)
        s.announceFrom == CarAnnounceFrom.DND_ALLOWS && !n.dndAllows -> CarAnnouncement.Skip(AnnounceCode.DND)
        s.announceFrom == CarAnnounceFrom.CHOSEN_PEOPLE && !senderAllowed -> CarAnnouncement.Skip(AnnounceCode.NOT_ALLOWED)
        n.hidden -> CarAnnouncement.Announce(from(n, lang))
        s.announce == CarAnnounce.READ_WHOLE && s.readAloud == ReadAloud.ALWAYS -> CarAnnouncement.ReadNow(says(n, lang))
        else -> CarAnnouncement.Announce(from(n, lang) + if (lang == SpokenLang.FR) " Je te le lis?" else " Read it?")
    }

    /** The read-back as a spoken card: "send", "envoie" or a yes confirms it. */
    fun card(d: ReplyDraft, lang: SpokenLang): SpokenCard = SpokenCard(readBack(d, lang).removeSuffix("?"), confirmWords = SEND_WORDS, personal = true, carFunction = true)

    private fun from(n: MessageNotice, lang: SpokenLang): String = when (lang) {
        SpokenLang.EN -> "Message from ${n.sender}" + (n.group?.let { " in $it" } ?: "") + "."
        SpokenLang.FR -> "Message de ${n.sender}" + (n.group?.let { " dans $it" } ?: "") + "."
    }

    /** "Marie says: I'm on my way." */
    fun says(n: MessageNotice, lang: SpokenLang): String = if (lang == SpokenLang.FR) "${n.sender} dit : ${n.text.trim()}" else "${n.sender} says: ${n.text.trim()}"

    /** After reading: offer a reply only where one can be sent by voice. */
    fun offersReply(n: MessageNotice, s: CarSettings, safety: HandsFreeSafety): Boolean = n.replyable && !n.isGroup && s.voiceReplies && safety.replyAfterSpokenYes

    /**
     * A reply to [n] from what you said ([dictated]: "Tell her I'm 10 minutes away" or just "I'm 10 minutes away"), read
     * back for a spoken "send" only where everything allows it: "Replies by voice" and "Send a reply after a spoken yes" on,
     * unlocked or in a trusted session, a conversation with a Reply action that isn't a group, at most [MAX_REPLY]
     * characters, and 0.7's rules for this conversation ([voice], core messages HandsFreeReplies) at
     * [VoiceReply.BY_VOICE]. Empty (and nothing else in the way): asks what it should say. Anything else goes
     * [ReplyStart.OnScreen] with your words, where the screen's reply applies its own rules, as outside the car: locked,
     * that is 0.7's lock-screen rule ("Messages on the lock screen"); refused, 0.7's refusal.
     */
    fun startReply(n: MessageNotice, dictated: String, s: CarSettings, safety: HandsFreeSafety, locked: Boolean, trusted: Boolean, lang: SpokenLang,
        voice: VoiceReply): ReplyStart {
        val words = ReplyWords.body(dictated)
        if (!s.voiceReplies || !safety.replyAfterSpokenYes) return ReplyStart.OnScreen(ReplyEnd.VOICE_OFF, words)
        if (locked && !trusted) return ReplyStart.OnScreen(ReplyEnd.LOCKED, words)
        if (voice == VoiceReply.REFUSED) return ReplyStart.OnScreen(ReplyEnd.LOCKED, words)
        if (words.isEmpty()) return ReplyStart.AskText(WHAT_REPLY.get(lang))
        val why = when {
            !n.replyable -> ReplyEnd.NOT_REPLYABLE
            n.isGroup -> ReplyEnd.GROUP
            words.length > MAX_REPLY -> ReplyEnd.TOO_LONG
            voice != VoiceReply.BY_VOICE -> ReplyEnd.NEEDS_TAP
            else -> null
        }
        if (why != null) return ReplyStart.OnScreen(why, words)
        val draft = ReplyDraft(n.conversationKey, n.sender, n.app, words)
        return ReplyStart.ReadBack(draft, readBack(draft, lang))
    }

    /** "Reply to Marie: I'm 10 minutes away. Send?" (the words exactly as they will be sent). */
    fun readBack(d: ReplyDraft, lang: SpokenLang): String {
        val body = d.text.trimEnd()
        val stop = if (body.last() in ".!?…") "" else "."
        return if (lang == SpokenLang.FR) "Réponse à ${d.to} : $body$stop J'envoie?" else "Reply to ${d.to}: $body$stop Send?"
    }

    /**
     * The answer to the read-back: "send", "yes", "envoie" send exactly [draft]'s text; "no" sends nothing. Nothing usable
     * twice: [ReplyEnd.SILENCE], and the screen's reply card takes the words (MessageFlow).
     */
    fun answer(draft: ReplyDraft, a: SpokenAnswer, tries: Int, rereads: Int, lang: SpokenLang): ReplyNext =
        when (val step = SpokenChoice.next(a, tries, rereads, 0, lang)) {
            SpokenStep.Confirm -> ReplyNext.Send(draft.conversationKey, draft.text)
            SpokenStep.Decline -> ReplyNext.Done(NOT_SENT.get(lang), ReplyEnd.DECLINED)
            SpokenStep.Reread -> ReplyNext.Reread
            is SpokenStep.AskAgain -> ReplyNext.AskAgain(step.say)
            is SpokenStep.Hold -> ReplyNext.Done(ON_SCREEN.get(lang), ReplyEnd.SILENCE)
            is SpokenStep.Choose -> ReplyNext.AskAgain(SpokenCards.hint(0, lang))
        }

    /**
     * What Robot says after the main process answered: "Handed to Cheogram." (never "delivered"). Not handed over: why, and
     * (but for the lock screen) that the screen's reply card has your words, as outside the car.
     */
    fun result(r: ReplyResult, app: String, lang: SpokenLang): String = when (r) {
        ReplyResult.HANDED_OVER -> if (lang == SpokenLang.FR) "Remis à $app." else "Handed to $app."
        ReplyResult.NO_REPLY_ACTION, ReplyResult.GONE -> if (lang == SpokenLang.FR) "Je ne peux pas l'envoyer à la voix. Ta réponse est à l'écran."
            else "I can't send that by voice. Your reply is on the screen."
        ReplyResult.LOCKED -> CarGate.UNLOCK_ONCE.get(lang)
        ReplyResult.FAILED -> if (lang == SpokenLang.FR) "Ça n'a pas marché. Ta réponse est à l'écran." else "That didn't work. Your reply is on the screen."
        ReplyResult.NEW_MESSAGES -> if (lang == SpokenLang.FR) "Un nouveau message vient d'arriver. Ta réponse est à l'écran."
            else "A new message just came. Your reply is on the screen."
    }
}
