package app.heyrobot.core.messages

import app.heyrobot.core.LangCode
import app.heyrobot.core.LogCode
import app.heyrobot.core.MtEngine
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry

// Pure. The voice log's codes for messages (VoiceEvent 190 to 199, design §C2 renumbered into 0.7's range): which app,
// how a reply went, how a card was answered. Codes and counts only: never a name, an address or a word of a message
// (VoiceLogTest checks the recording API takes nothing else).

/** An app, as the log stores it ([MessageApp] by a fixed number). */
enum class MsgAppCode(override val id: Int, override val title: String) : LogCode {
    CHEOGRAM(1, "Cheogram"), SIGNAL(2, "Signal"), MOLLY(3, "Molly"), MESSAGING(4, "Messaging (SMS)"), GOOGLE_MESSAGES(5, "Google Messages");

    companion object {
        fun of(app: MessageApp): MsgAppCode = when (app) {
            MessageApp.CHEOGRAM -> CHEOGRAM
            MessageApp.SIGNAL -> SIGNAL
            MessageApp.MOLLY -> MOLLY
            MessageApp.MESSAGING -> MESSAGING
            MessageApp.GOOGLE_MESSAGES -> GOOGLE_MESSAGES
        }

        /** One app's code, or 0 for several. */
        fun idOf(apps: Collection<MessageApp>): Int = apps.distinct().singleOrNull()?.let { of(it).id } ?: 0
    }
}

/** How a reply went out. */
enum class ReplyRouteCode(override val id: Int, override val title: String) : LogCode {
    REMOTE_INPUT(1, "through the app's reply action"), HAND_OFF(2, "as a draft in the app"),
}

/** How a reply ended. */
enum class ReplyEnd(override val id: Int, override val title: String) : LogCode {
    SENT(1, "sent"),
    CANCELED_PI(2, "the app's reply action had gone"),
    GONE(3, "the notification had gone"),
    MISMATCH(4, "not the same conversation any more"),
    FAILED(5, "failed"),
    NEW_MESSAGES(6, "a new message came first"),
    LOCKED(7, "the phone was locked"),
    OPENED(8, "the chat opened with the draft"),
}

/** How a reply card was answered. */
enum class CardEnd(override val id: Int, override val title: String) : LogCode {
    TAP(1, "Send tapped"),
    VOICE_YES(2, "a spoken yes"),
    CANCELLED(3, "cancelled"),
    CLOSED(4, "the panel closed"),
    NO_CARD(5, "no card: sent after the 3-second stop"),
    STOPPED(6, "stopped in the 3 seconds"),
    OPEN_CHAT(7, "Open chat"),
}

enum class MarkKind(override val id: Int, override val title: String) : LogCode {
    READ(1, "marked as read"), CLEAR(2, "notification cleared"),
}

enum class HiddenCode(override val id: Int, override val title: String) : LogCode {
    OTP_REDACTED(1, "Android hid a code"), APP_PRIVACY(2, "the app shows only “New message”");

    companion object {
        fun of(h: HiddenWhy): HiddenCode = if (h == HiddenWhy.OTP_REDACTED) OTP_REDACTED else APP_PRIVACY
    }
}

enum class ListenerEvent(override val id: Int, override val title: String) : LogCode {
    CONNECTED(1, "connected"), DISCONNECTED(2, "disconnected"), REBIND(3, "asked to bind again"), UNBIND(4, "unbound: messages and name learning are off"),
    /** MessageSigners PIN_NOW: an app that was on before the pins (messages.json version 1), trusted at its first notification. */
    PINNED_FIRST_USE(5, "kept an app's signing key at its first notification (it was on before Robot checked keys)"),
    /** MessageSigners CHANGED: ignored until you turn the app on again. */
    SIGNER_CHANGED(6, "ignored an app whose signing key isn't the one kept"),
    /** MessageSigners NOT_PINNED: a package installed after its app was turned on. */
    NOT_PINNED(7, "ignored a build of an app installed after you turned it on"),
    /** Notification access taken back, or Read my messages turned off: every message held is dropped. */
    DROPPED(8, "dropped every message held"),
}

/** What the quarantine did (design §B10). */
enum class GuardCode(override val id: Int, override val title: String) : LogCode {
    ASKED(1, "a message is in the conversation: an action asked first"),
    TAP_REQUIRED(2, "a message is in the conversation: an action for someone else needed a tap"),
    FROM_MESSAGE(3, "a message's own words came back as a request: nothing done"),
    RECIPIENT_MISMATCH(4, "the reply's conversation changed: not sent"),
    /** 0.7 with memory: the model proposed a memory once a message was in the conversation (never kept). */
    REFUSED(5, "a message is in the conversation: a memory the model proposed was not kept"),
}

/** Why "read my messages" read nothing. */
enum class MsgRefusal(override val id: Int, override val title: String) : LogCode {
    OFF(1, "Read my messages is off"),
    UNLOCK_FIRST(2, "locked: unlock first"),
    NAMES_ONLY(3, "locked: names only"),
    NO_ACCESS(4, "no notification access"),
    NO_APPS(5, "no app turned on"),
}

object MessageLog {
    private fun <E> code(entries: List<E>, id: Int): String where E : Enum<E>, E : LogCode = entries.firstOrNull { it.id == id }?.title ?: "#$id"

    private fun app(id: Int): String = if (id == 0) "several apps" else code(MsgAppCode.entries, id)

    /** One messages event in words (VoiceLogText.describe hands these over). */
    fun describe(e: VoiceLogEntry): String {
        val (a, b, c) = Triple(e.a, e.b, e.c)
        return when (e.event) {
            VoiceEvent.MSG_READ -> "Read $b message${if (b == 1) "" else "s"} from " + app(a) + if (c == 1) " (phone locked)" else ""
            VoiceEvent.MSG_SUMMARY -> "Summary of $a conversation${if (a == 1) "" else "s"} ($b messages) in $c s"
            VoiceEvent.MSG_TRANSLATE -> "Message translated to " + code(LangCode.entries, a) + " by " + (if (b == 0) "nothing (no engine answered)" else code(MtEngine.entries, b)) + " in $c ms"
            VoiceEvent.MSG_REPLY -> "Reply in " + app(a) + " " + code(ReplyRouteCode.entries, b) + ": " + code(ReplyEnd.entries, c)
            VoiceEvent.MSG_REPLY_CARD -> "Reply card: " + code(CardEnd.entries, a) + (if (b == 1) " · translated" else "") + (if (c == 1) " · group" else "")
            VoiceEvent.MSG_MARK -> "After reading, " + app(b) + ": " + code(MarkKind.entries, a)
            VoiceEvent.MSG_HIDDEN -> "Message hidden from Robot in " + app(a) + ": " + code(HiddenCode.entries, b)
            VoiceEvent.MSG_LISTENER -> when (a) {
                ListenerEvent.PINNED_FIRST_USE.id, ListenerEvent.SIGNER_CHANGED.id, ListenerEvent.NOT_PINNED.id ->
                    "Notification listener " + code(ListenerEvent.entries, a) + ": " + app(b)
                else -> "Notification listener " + code(ListenerEvent.entries, a) + " · $b conversations held"
            }
            VoiceEvent.MSG_GUARD -> "Messages guard: " + code(GuardCode.entries, a)
            VoiceEvent.MSG_REFUSED -> "Messages not read: " + code(MsgRefusal.entries, a)
            else -> "#${e.event.id}"
        }
    }

    /** Every code list here, for VoiceLogTest's uniqueness check. */
    val codes: List<List<LogCode>> = listOf(MsgAppCode.entries, ReplyRouteCode.entries, ReplyEnd.entries, CardEnd.entries, MarkKind.entries,
        HiddenCode.entries, ListenerEvent.entries, GuardCode.entries, MsgRefusal.entries)
}
