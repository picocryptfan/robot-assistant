package app.heyrobot.core.car

import app.heyrobot.core.LogCode
import app.heyrobot.core.headset.AnnounceCode
import app.heyrobot.core.messages.VoiceReply
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.OutputLines
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.ReplyResult
import app.heyrobot.core.output.ReplyWords
import app.heyrobot.core.output.SessionKind
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
// text that was read back ([ReplyDraft.text]), never a model's draft. Apps without a Reply action, groups, "Replies by
// voice" off or "Send a reply after a spoken yes" off: the reply is kept for parking. 0.7's reply rules hold too (core
// messages HandsFreeReplies): a reply that only a tap may send (Settings › Messages' card, a locked phone) or that Robot
// never sends itself ("Open the chat; I press Send") is kept for parking with your words, and asked again then. New texts
// ("Text Bob I'm late") are kept for parking too, since Cheogram only takes a draft that needs Send ("New texts while
// driving").

/** How a hands-free reply ended. Also the voice log's CAR_REPLY b when nothing was handed over. */
enum class ReplyEnd(override val id: Int, override val title: String) : LogCode {
    SENT(1, "handed to the app"), DECLINED(2, "you said no"), HELD(3, "kept for parking"), NOT_REPLYABLE(4, "no Reply action"), GROUP(5, "a group"),
    VOICE_OFF(6, "replies by voice off"), LOCKED(7, "locked"), SILENCE(8, "no answer"), TOO_LONG(9, "too long to read back"),
    /** 0.7's rules: only a tap sends it, or Robot never sends it itself ("Open the chat; I press Send"). */
    NEEDS_TAP(10, "needs a tap: kept for parking"),
    /** A newer message came while you dictated: nothing sent. */
    NEW_MESSAGES(11, "a newer message came"),
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
    /** Kept for parking ([say]); [text]: your words, asked again once you park ("" when there were none yet). */
    data class Hold(val say: String, val why: ReplyEnd, val text: String = "") : ReplyStart()
}

sealed class ReplyNext {
    /** Hand exactly [text] to [conversationKey]'s Reply action. */
    data class Send(val conversationKey: String, val text: String) : ReplyNext()
    data class Done(val say: String, val why: ReplyEnd) : ReplyNext()
    data object Reread : ReplyNext()
    data class AskAgain(val say: String) : ReplyNext()
}

object CarMessages {
    /** A reply longer than this isn't read back in the car: kept for parking. */
    const val MAX_REPLY = 300

    /** What confirms a reply besides yes (plain words). */
    val SEND_WORDS = listOf("send", "send it", "send that", "envoie", "envoie le", "envoie la", "envoyer")

    val ASK_REPLY = Line("Reply?", "Tu veux répondre?")
    val WHAT_REPLY = Line("What should the reply say?", "Qu'est-ce que je réponds?")
    val NOT_SENT = Line("Okay, not sent.", "D'accord, pas envoyé.")
    val CANT_REPLY = Line("I can't reply to that app hands-free. I'll keep it for when you park.",
        "Je ne peux pas répondre à cette app sans les mains. Je le garde pour quand tu seras stationné.")
    val KEEP_TEXT = Line("I'll keep that text for when you park.", "Je garde ce texto pour quand tu seras stationné.")

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
     * A reply to [n] from what you said ([dictated]: "Tell her I'm 10 minutes away" or just "I'm 10 minutes away").
     * Locked without a trusted session: kept. Not replyable, a group, replies by voice off or "Send a reply after a
     * spoken yes" off, or too long to read back: kept for parking. Empty: asks what it should say. [voice]: 0.7's rules for
     * this conversation (core messages HandsFreeReplies); anything but [VoiceReply.BY_VOICE] is kept for parking with your
     * words, which a tap sends once you park.
     */
    fun startReply(n: MessageNotice, dictated: String, s: CarSettings, safety: HandsFreeSafety, locked: Boolean, trusted: Boolean, lang: SpokenLang,
        voice: VoiceReply): ReplyStart {
        val words = ReplyWords.body(dictated)
        if (!n.replyable) return ReplyStart.Hold(CANT_REPLY.get(lang), ReplyEnd.NOT_REPLYABLE, words)
        if (n.isGroup) return ReplyStart.Hold(OutputLines.KEEP_FOR_PARK.get(lang), ReplyEnd.GROUP, words)
        if (!s.voiceReplies || !safety.replyAfterSpokenYes) return ReplyStart.Hold(OutputLines.KEEP_FOR_PARK.get(lang), ReplyEnd.VOICE_OFF, words)
        if (locked && !trusted) return ReplyStart.Hold(CarGate.UNLOCK_ONCE.get(lang), ReplyEnd.LOCKED, words)
        val text = words
        if (text.isEmpty()) return ReplyStart.AskText(WHAT_REPLY.get(lang))
        if (text.length > MAX_REPLY) return ReplyStart.Hold(OutputLines.KEEP_FOR_PARK.get(lang), ReplyEnd.TOO_LONG, text)
        if (voice != VoiceReply.BY_VOICE) return ReplyStart.Hold(OutputLines.KEEP_FOR_PARK.get(lang),
            if (voice == VoiceReply.REFUSED) ReplyEnd.LOCKED else ReplyEnd.NEEDS_TAP, text)
        val draft = ReplyDraft(n.conversationKey, n.sender, n.app, text)
        return ReplyStart.ReadBack(draft, readBack(draft, lang))
    }

    /** "Reply to Marie: I'm 10 minutes away. Send?" (the words exactly as they will be sent). */
    fun readBack(d: ReplyDraft, lang: SpokenLang): String {
        val body = d.text.trimEnd()
        val stop = if (body.last() in ".!?…") "" else "."
        return if (lang == SpokenLang.FR) "Réponse à ${d.to} : $body$stop J'envoie?" else "Reply to ${d.to}: $body$stop Send?"
    }

    /** The answer to the read-back: "send", "yes", "envoie" send exactly [draft]'s text; "no" sends nothing. */
    fun answer(draft: ReplyDraft, a: SpokenAnswer, tries: Int, rereads: Int, lang: SpokenLang): ReplyNext =
        when (val step = SpokenChoice.next(a, tries, rereads, 0, SessionKind.CAR, lang)) {
            SpokenStep.Confirm -> ReplyNext.Send(draft.conversationKey, draft.text)
            SpokenStep.Decline -> ReplyNext.Done(NOT_SENT.get(lang), ReplyEnd.DECLINED)
            SpokenStep.Reread -> ReplyNext.Reread
            is SpokenStep.AskAgain -> ReplyNext.AskAgain(step.say)
            is SpokenStep.Hold -> ReplyNext.Done(step.say, ReplyEnd.SILENCE)
            is SpokenStep.Choose -> ReplyNext.AskAgain(SpokenCards.hint(0, lang))
        }

    /**
     * What is asked again once you park, for a reply kept with your [text] ("reply to Marie: I'm 10 minutes away"): the
     * panel's 0.7 card then, which a tap sends. Null without words (the summary alone is kept).
     */
    fun parkedAsk(n: MessageNotice, text: String, lang: SpokenLang): String? = text.trim().takeIf { it.isNotEmpty() }?.let {
        if (lang == SpokenLang.FR) "réponds à ${n.sender} : $it" else "reply to ${n.sender}: $it"
    }

    /** What Robot says after the main process answered: "Handed to Cheogram." (never "delivered"). */
    fun result(r: ReplyResult, app: String, lang: SpokenLang): String = when (r) {
        ReplyResult.HANDED_OVER -> if (lang == SpokenLang.FR) "Remis à $app." else "Handed to $app."
        ReplyResult.NO_REPLY_ACTION, ReplyResult.GONE -> CANT_REPLY.get(lang)
        ReplyResult.LOCKED -> CarGate.UNLOCK_ONCE.get(lang)
        ReplyResult.FAILED -> if (lang == SpokenLang.FR) "Ça n'a pas marché. Je le garde pour quand tu seras stationné." else "That didn't work. I'll keep it for when you park."
        ReplyResult.NEW_MESSAGES -> if (lang == SpokenLang.FR) "Un nouveau message vient d'arriver. Je garde ta réponse pour quand tu seras stationné."
            else "A new message just came. I'll keep your reply for when you park."
    }

    /** "Text Bob I'm late" while driving: kept for parking, or Cheogram opened now (unlocked only; it needs a tap there). */
    fun newText(s: CarSettings, locked: Boolean): NewTexts = if (s.newTexts == NewTexts.OPEN_CHEOGRAM && !locked) NewTexts.OPEN_CHEOGRAM else NewTexts.KEEP_FOR_PARKING
}
