package app.heyrobot.core.headset

import app.heyrobot.core.LogCode
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.Trust

// Pure. New messages announced in the headset (0.7, design §3.2 story 5, §3.6, §3.7 rule 5): opt-in, private headphones
// only, never on the lock screen unless "Announce while locked" is on, only senders Do Not Disturb and "Announce from"
// let through, the sender's name first and the text only after a yes (or at once with "Read the text: always"). Never on
// the speaker, a car or a Bluetooth speaker: the car has its own announcements (car mode). Each sentence still passes
// OutputGuard as it plays.

/** Also the voice log's ANNOUNCE a. */
enum class AnnounceCode(override val id: Int, override val title: String) : LogCode {
    ANNOUNCED(1, "announced, asked to read"), READ(2, "read at once"), OFF(3, "announcements off"), NOT_ALLOWED(4, "sender not chosen"),
    NOT_PRIVATE(5, "no private headphones"), LOCKED(6, "locked"), DND(7, "Do Not Disturb"), BUSY(8, "Robot was busy"), CALL(9, "a call is on"),
    HIDDEN(10, "the app hides the text"),
}

sealed class AnnounceDecision {
    data class Skip(val why: AnnounceCode) : AnnounceDecision()

    /** Say [say] ("Message from Marie. Read it?"); a yes reads it ([HeadsetAnnounce.read]). */
    data class Announce(val say: String, val asks: Boolean) : AnnounceDecision()

    /** Read it at once ("Marie says: …"). */
    data class ReadNow(val say: String) : AnnounceDecision()
}

object HeadsetAnnounce {
    /**
     * Whether and how [n] is announced. [trust]: of the headset sound goes to now ([headsetRouted]: it is a headset at
     * all). [robotBusy]: Robot is talking or listening (the announcement waits; the caller queues it). [senderAllowed]:
     * the SenderFilter's answer.
     */
    fun decide(n: MessageNotice, s: HeadsetSettings, headsetRouted: Boolean, trust: Trust, phone: PhoneState, robotBusy: Boolean,
        senderAllowed: Boolean, lang: SpokenLang): AnnounceDecision = when {
        !s.announceMessages -> AnnounceDecision.Skip(AnnounceCode.OFF)
        !headsetRouted || trust != Trust.PRIVATE -> AnnounceDecision.Skip(AnnounceCode.NOT_PRIVATE)
        phone.callActive -> AnnounceDecision.Skip(AnnounceCode.CALL)
        phone.locked && !s.announceLocked -> AnnounceDecision.Skip(AnnounceCode.LOCKED)
        !n.dndAllows -> AnnounceDecision.Skip(AnnounceCode.DND)
        s.announceFrom == AnnounceFrom.CHOSEN_PEOPLE && !senderAllowed -> AnnounceDecision.Skip(AnnounceCode.NOT_ALLOWED)
        robotBusy -> AnnounceDecision.Skip(AnnounceCode.BUSY)
        n.hidden -> AnnounceDecision.Announce(announce(n, lang, ask = false), asks = false)
        s.announceRead == AnnounceRead.ALWAYS -> AnnounceDecision.ReadNow(read(n, lang))
        else -> AnnounceDecision.Announce(announce(n, lang, ask = true), asks = true)
    }

    /** "Message from Marie. Read it?", "Message from Marie in Famille. Read it?"; without the question for a hidden text. */
    fun announce(n: MessageNotice, lang: SpokenLang, ask: Boolean): String {
        val from = when (lang) {
            SpokenLang.EN -> "Message from ${n.sender}" + (n.group?.let { " in $it" } ?: "") + "."
            SpokenLang.FR -> "Message de ${n.sender}" + (n.group?.let { " dans $it" } ?: "") + "."
        }
        return when {
            ask -> from + if (lang == SpokenLang.FR) " Je te le lis?" else " Read it?"
            n.hidden -> from + if (lang == SpokenLang.FR) " L'app ne montre pas le texte." else " The app doesn't show the text."
            else -> from
        }
    }

    /** "Marie says: I'm on my way." / "Marie dit : J'arrive." */
    fun read(n: MessageNotice, lang: SpokenLang): String = when (lang) {
        SpokenLang.EN -> "${n.sender} says: ${n.text.trim()}"
        SpokenLang.FR -> "${n.sender} dit : ${n.text.trim()}"
    }
}
