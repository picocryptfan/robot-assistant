// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Handler
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.Cue
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ShowSource
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// VOICE PROCESS. Answering a card by voice ("Answer cards by voice", 0.4.0): when a card waits, Robot says how to answer
// it and listens once for a yes or no; what is heard answers the newest waiting card, or Robot asks once more. What
// counts as a yes or a no is core CardAnswer's. It keeps only its listening's own state, over PanelController's state:
// the microphone, the voice, the settings, the routine card only a tap answers and the card buttons are PanelController's,
// through [PanelCardVoice.Port]; the waiting card is found through PanelReplies, and how the panel was opened through
// PanelScreenShare. Its callers and the speech callbacks run on the main thread, as before.

/** Listening for the waiting card's yes or no, over PanelController's [state]; [port]: what it needs of PanelController. */
internal class PanelCardVoice(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val screenShare: PanelScreenShare,
    private val port: Port,
) {
    /** What answering a card by voice needs of PanelController (VoiceHub's microphone, voice and settings, read live). */
    interface Port {
        /** The main thread's handler, where the speech callbacks are posted. */
        val main: Handler
        val stt: SpeechToText
        /** The settings as the turns use them now (a phone lock changes them). */
        val settings: RobotSettings
        /** Robot's voice is talking. */
        val speaking: Boolean
        /** The panel is open. */
        val shown: Boolean
        /** The turn under way began as a spoken question. */
        val spokenTurn: Boolean
        /** The turn's languages (why a tap is needed is said in its reply language). */
        val turnLangs: Langs
        /** Robot's screens' languages, for a notice. */
        fun screenLangs(): Langs
        /** 0.8: the routine card waiting that only a tap answers, by card id, and what Robot says to a spoken yes. */
        val routineTapOnly: Pair<String, String>?
        /** The wake word is not listened for while the microphone is open for the card. */
        fun wakePaused(paused: Boolean)
        fun notify(text: String)
        /** Robot's own words aloud, in the turn's reply language ([said]'s form). */
        fun speakRobot(text: String)
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** [text] aloud in Robot's own voice, whatever the reply language. */
        fun speak(text: String)
        fun cue(c: Cue)
        /** As a tap on "Do it" (true) or "Cancel". */
        fun confirm(actionId: String, yes: Boolean)
        /** As a tap on a card's choice [index]. */
        fun choose(actionId: String, index: Int)
    }

    /**
     * Listen for the waiting card's yes or no once Robot has stopped talking. [listening] tells the callbacks of a
     * listening that was stopped from the current one; [retry] asks again once.
     */
    private var listenAfterSpeech = false
    private var listening = 0
    private var retry = 0
    /** PanelHelperTurn's askToShare said its own words about the card: no "Say yes or no." on top. */
    private var promptSaid = false

    /** PanelHelperTurn's askToShare said its own words about the card that is about to wait. */
    fun saidPrompt() {
        promptSaid = true
    }

    /** Robot's voice stopped (a sentence ends between two): listen only once it has really stopped talking. */
    fun speakingStopped() {
        if (listenAfterSpeech) port.main.postDelayed({ if (listenAfterSpeech && !port.speaking) listen() }, 350)
    }

    /** The mic was pressed while a card waits, with "Answer cards by voice" on: listen for its answer, with a retry again. */
    fun micPressed() {
        retry = 0
        listen()
    }

    /**
     * A card now waits. With "Answer cards by voice" on, when replies are spoken or you are talking to Robot (a spoken
     * question, a wake-word panel), Robot says how to answer and listens for "yes" or "no" once it has stopped talking.
     */
    fun waiting() {
        val st = state.value
        val s = port.settings
        val said = promptSaid
        promptSaid = false
        retry = 0
        if (!s.voiceCards || !port.shown || !port.stt.available || !(st.speak || port.spokenTurn || screenShare.from == ShowSource.WAKE)) return
        val card = replies.pendingCard() ?: return
        if (st.speak) {
            // "Say yes or no.", or why it needs a tap (a learned name, not sure who, online the first time) and that "no" works.
            if (!said) port.speakRobot(port.said(CardAnswer.promptText(card.waiting)))
            listenAfterSpeech = true
        } else {
            listen()
        }
    }

    /** Listens for the waiting card's answer; the phase stays CONFIRMING (the turn is still waiting on the card). */
    private fun listen() {
        listenAfterSpeech = false
        val st = state.value
        if (st.phase != Phase.CONFIRMING || st.cardHearing != null || !port.shown || replies.pendingCard() == null) return
        if (!port.stt.available) return port.notify(port.stt.hint ?: PanelStrings.noVoiceInput().render(port.screenLangs()))
        val token = ++listening
        port.wakePaused(true)
        state.update { it.copy(cardHearing = Phase.LISTENING, partial = "", notice = null) }
        val main = port.main
        port.stt.start(object : SpeechToText.Listener {
            fun mine() = token == listening
            override fun onPartial(text: String) { main.post { if (mine()) state.update { if (it.cardHearing == Phase.LISTENING) it.copy(partial = text) else it } } }
            override fun onEndOfSpeech() {
                main.post {
                    if (!mine()) return@post
                    port.wakePaused(false)
                    state.update { if (it.cardHearing != null) it.copy(cardHearing = Phase.TRANSCRIBING) else it }
                }
            }
            override fun onFinal(text: String) {
                main.post {
                    if (!mine()) return@post
                    endListening()
                    heard(text)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    if (!mine()) return@post
                    endListening()
                    port.notify(if (reason == SpeechToText.Reason.NO_SPEECH) PanelStrings.cardHint().render(port.screenLangs()) else message)
                }
            }
        }, shortAnswer = true)
    }

    private fun endListening() {
        port.wakePaused(false)
        state.update { it.copy(cardHearing = null, partial = "") }
    }

    /** The card was answered or the turn stopped: no more listening for it. */
    fun stop() {
        listenAfterSpeech = false
        if (state.value.cardHearing == null) return
        listening++
        port.stt.cancel()
        endListening()
    }

    /** A notice in Robot's screens' language (the menus'). */
    private fun notify(p: Phrase) = port.notify(p.render(port.screenLangs()))

    /** What was said while the card waited: its answer, or (once) a reminder and another listen. */
    private fun heard(text: String) {
        if (text.isNotBlank() && answer(text, spoken = true)) return
        if (replies.pendingCard() == null) return
        if (retry++ < 1) {
            notify(PanelStrings.cardHint())
            if (state.value.speak) {
                port.speakRobot(port.said(replies.pendingCard()?.waiting?.let(CardAnswer::promptText) ?: TurnStrings.sayYesOrNo()))
                listenAfterSpeech = true
            } else {
                listen()
            }
        } else {
            notify(PanelStrings.cardHint())
            port.cue(Cue.CHIME)
        }
    }

    /**
     * [text] answers the waiting card (core CardAnswer.reply, tested): "no" declines it; "yes" confirms it, except a card
     * that needs a tap (a learned name, not sure who or which, online the first time), one with several choices, or one
     * that sends personal content to a helper (unless "Voice yes can send personal content"): Robot says why. False: not a
     * yes or no, no card waits, or it was [spoken] with "Answer cards by voice" off (a typed answer still works then).
     */
    fun answer(text: String, spoken: Boolean): Boolean {
        val card = replies.pendingCard() ?: return false
        val s = port.settings
        // 0.8: a routine from the screen's text, or deleting one: only a tap (a "no" still declines).
        port.routineTapOnly?.takeIf { it.first == card.id }?.let { (_, words) ->
            if (CardAnswer.of(text, s.wakePhrases.filter { it.enabled }.map { it.text }) == CardAnswer.Kind.YES) {
                port.notify(words)
                if (state.value.speak) port.speak(words)
                return true
            }
        }
        val r = CardAnswer.reply(text, card.waiting, s.voiceCards, s.voiceYesPersonal, spoken, s.wakePhrases.filter { it.enabled }.map { it.text })
            ?: return false
        RLog.i("card_answer", "yes" to (r.does != CardAnswer.Does.DECLINE), "spoken" to spoken)
        when (r.does) {
            CardAnswer.Does.DECLINE -> port.confirm(card.id, false)
            CardAnswer.Does.CONFIRM -> port.confirm(card.id, true)
            CardAnswer.Does.CHOOSE_ONLY -> port.choose(card.id, 0)
            // 1.1: why a tap is needed, in the turn's reply language (core CardAnswer.Reply.text).
            CardAnswer.Does.TAP -> r.text?.let { words ->
                port.notify(words.render(port.turnLangs))
                if (state.value.speak) port.speakRobot(port.said(words))
            }
        }
        return true
    }
}
