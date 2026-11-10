package app.heyrobot.core.messages

/**
 * fix-car-132: a reply in its 3 seconds and the panel hiding for an app a tap opened (an app, Maps, the car screen's fourth
 * button): the tap's stop leaves the reply be, the hide waits for the 3 seconds' end, and while you're in that app the
 * reply's result names no one aloud. PanelMessages keeps one; ReplyHoldTest runs taps through it.
 */
class ReplyHold {
    /** The panel hid for an app while the 3 seconds ran: its hide waits for their end ([windowEnded]). */
    var hidden = false
        private set

    /**
     * fix-car-133: the panel hid while a reply was in its 3 seconds for an app, or by any hide while one was on its way:
     * its result names no one aloud ([aloud]) and its notices wait for the next open ([notice]).
     */
    var away = false
        private set

    private class Held(val card: String, val state: Any, val text: String)
    private var held: Held? = null

    /** The panel shows again: the notice held while it was away, to show now. */
    fun shown(): String? {
        hidden = false
        away = false
        return held?.text.also { held = null }
    }

    /** A stop during the 3 seconds ([inWindow]); [forApp]: by a tap that opens an app. True when it stops them. */
    fun stopStops(forApp: Boolean, inWindow: Boolean): Boolean = !forApp || !inWindow

    /** The panel hid: true when its work (the 3 seconds stopped, your messages' words let go) runs now, false when it waits. */
    fun hide(forApp: Boolean, inWindow: Boolean, sending: Boolean = false): Boolean {
        hidden = forApp && inWindow
        if (hidden || sending) away = true
        return !hidden
    }

    /** A notice about reply [card] in [state], made while away: shown again at the next open while that card stays so. */
    fun notice(card: String, state: Any, text: String) {
        if (away) held = Held(card, state, text)
    }

    /** Reply [card] is now in [state]: a notice held about it in another state goes (a LOCKED reply sent from the car screen). */
    fun card(card: String, state: Any) {
        if (held?.card == card && held?.state != state) held = null
    }

    /** The 3 seconds ended (the reply went or stopped): true when a hide waited for them and runs now. */
    fun windowEnded(): Boolean = hidden.also { hidden = false }

    /**
     * A result that names someone or quotes a message is said aloud: in Robot, not behind an app a tap opened nor closed on it ([away]),
     * and in a session ([inSession]) only while it lasts.
     */
    fun aloud(inSession: Boolean, sessionOn: Boolean): Boolean = !away && (!inSession || sessionOn)
}
