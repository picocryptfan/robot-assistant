// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.assistant.car.CarCardVoice
import app.heyrobot.assistant.car.CoveredLine
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
        /** fix-car-long-card: a turn with no panel, the car screen up (CarModeController.quietTurn). */
        val carQuiet: Boolean get() = false
        /** The car screen may show and say private words (CarModeController.trusted). */
        val carTrusted: Boolean get() = false
    }

    /**
     * Listen for the waiting card's yes or no once Robot has stopped talking. [listening] tells the callbacks of a
     * listening that was stopped from the current one; [retry] asks again once.
     */
    private var listenAfterSpeech = false
    private var listening = 0
    private var retry = 0
    /** PanelHelperTurn's askToShare said its own words about the card: no "Say yes or no." on top. */
    /** fix-car-133: the card whose own words were said ([saidPrompt]): honoured for that card only. */
    private var promptSaid: String? = null

    /** "Try it here or ask" waits: its card's id, the names heard as "the helper", and what silence does. */
    private var choiceCard: String? = null
    private var choiceNames: List<app.heyrobot.core.HelperName> = emptyList()
    private var choiceHelper: String? = null
    private var choiceSilent: () -> Unit = {}

    fun choiceWaits(id: String, names: List<app.heyrobot.core.HelperName>, helper: String, silent: () -> Unit) {
        choiceCard = id
        choiceNames = names
        choiceHelper = helper
        choiceSilent = silent
    }

    fun choiceDone() {
        choiceCard = null
        choiceSilent = {}
    }

    /** Robot's voice was stopped while talking (VoiceHub.stopVoice: muted, a new question, any stop): a read-out under way isn't whole. */
    fun voiceStopped() {
        reading = null
    }

    /**
     * fix-car-long-card-d: askToShare may say its own words (the helper's name, the app on screen, the kinds of personal
     * content in the conversation): not with the car screen up while it isn't trusted, where a card gets only the tap line
     * ([carWaiting]'s rule, as CarMessages.announce skips a message while locked and untrusted).
     */
    val ownWordsAloud: Boolean get() = !port.carQuiet || port.carTrusted

    /** PanelHelperTurn's askToShare said its own words about the card that is about to wait. */
    fun saidPrompt(id: String) {
        promptSaid = id
    }

    /** Robot's voice stopped (a sentence ends between two): listen only once it has really stopped talking. */
    fun speakingStopped() {
        if (listenAfterSpeech) port.main.postDelayed({
            if (listenAfterSpeech && !port.speaking) {
                // The read-out ran to its end (a stop or the Stop button ends the wait first, in stop()).
                readWhole = reading ?: readWhole
                reading = null
                listen()
            }
        }, 350)
    }

    /** The mic was pressed while a card waits, with "Answer cards by voice" on: listen for its answer, with a retry again. */
    fun micPressed() {
        retry = 0
        // fix-car-long-card-b: it stops the voice, so a read-out under way never ends whole (a yes then takes a tap, said aloud).
        reading = null
        listen()
    }

    /**
     * A card now waits. With "Answer cards by voice" on, when replies are spoken or you are talking to Robot (a spoken
     * question, a wake-word panel), Robot says how to answer and listens for "yes" or "no" once it has stopped talking.
     */
    fun waiting() {
        val st = state.value
        val s = port.settings
        val saidFor = promptSaid
        promptSaid = null
        retry = 0
        // fix-car-long-card-b: a card made with the car screen up that can't be read out well keeps that mark (UiAction.carTap):
        // no later show of it (the lock screen's button, a capture, trust lapsing) takes a voice yes for it.
        replies.pendingCard()?.takeIf { port.carQuiet && !it.carTap && CarCardVoice.tapOnly(it) }?.let { c -> replies.updateCard(c.id) { it.copy(carTap = true) } }
        if (!s.voiceCards || !port.shown || !port.stt.available || !(st.speak || port.spokenTurn || screenShare.from == ShowSource.WAKE)) return
        val card = replies.pendingCard() ?: return
        val said = saidFor == card.id
        if (port.carQuiet) return carWaiting(card, st.speak, said)
        if (st.speak) {
            // "Say yes or no.", or why it needs a tap (a learned name, not sure who, online the first time) and that "no" works.
            // A card marked in the car (UiAction.carTap) shown on the panel: its tap line, not "Say yes or no.".
            if (!said) port.speakRobot(port.said(if (card.carTap) TurnStrings.cardTap() else CardAnswer.promptText(card.waiting)))
            listenAfterSpeech = true
        } else {
            listen()
        }
    }

    /** The card Robot is reading out with the car screen up, and the one it has read out whole (fix-car-long-card). */
    private var reading: String? = null
    private var readWhole: String? = null

    /**
     * A card waits with the car screen up and no panel: Robot reads it out whole (what it does, who to, the words) and
     * listens only after; a card it can't read out well, or any while the phone isn't trusted, gets one line: it needs a
     * tap, on the car screen. A no still declines it. [said]: askToShare or the choice card said its own words about it
     * (fix-car-long-card-c): they are its read-out, whole once Robot has said them to the end, and never said twice.
     */
    private fun carWaiting(card: UiAction, speak: Boolean, said: Boolean) {
        reading = null
        readWhole = null
        if (!speak) return listen()
        // fix-car-134: the choice card's own words were its read-out ("here", or nothing, works by voice): no tap line after them.
        val choiceSaid = said && card.id == choiceCard
        if (!choiceSaid && (card.carTap || CarCardVoice.tapOnly(card) || !port.carTrusted)) port.speakRobot(port.said(TurnStrings.carTap()))
        else {
            if (!said) port.speakRobot(CarCardVoice.text(card) + "\n" + port.said(CardAnswer.promptText(card.waiting)))
            reading = card.id
        }
        listenAfterSpeech = true
    }

    /**
     * fix-covered-partial M2: car mode's Yes for card [id] was refused for another app's window over the screen. The line
     * aloud, then one listen: a spoken yes where the card takes one (read out, trusted, not a tap-only card), else the line
     * says it needs a tap, and a no still declines it; with nothing listening, only that it needs a tap (fix-covered-partial-b L2).
     */
    fun covered(id: String): CoveredLine {
        val card = replies.pendingCard()?.takeIf { it.id == id } ?: return CoveredLine.TAP
        // fix-covered-partial-b M2: Robot's own line never goes into a listen still open (echo cancellation runs in calls only).
        if (state.value.cardHearing != null) { listening++; port.stt.cancel(); endListening() }
        val hears = port.settings.voiceCards && port.stt.available
        val line = CoveredLine.of(hears, port.carTrusted && !card.carTap && !CarCardVoice.tapOnly(card) && (readWhole == id || reading == id))
        port.speakRobot(port.said(line.phrase))
        // The line's listen is once only: a non-answer gets the tap hint, never a reminder and a second listen.
        retry = 1
        if (hears) listenAfterSpeech = true
        return line
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
            @Volatile private var cut = false
            override fun onCapReached() { cut = true }
            override fun onPartial(text: String) { main.post { if (mine()) state.update { if (it.cardHearing == Phase.LISTENING) it.copy(partial = text) else it } } }
            override fun onEndOfSpeech() {
                main.post {
                    if (!mine()) return@post
                    port.wakePaused(false)
                    state.update { if (it.cardHearing != null) it.copy(cardHearing = Phase.TRANSCRIBING) else it }
                }
            }
            override fun onSpeechBack() {
                main.post {
                    if (!mine()) return@post
                    port.wakePaused(true)
                    state.update { if (it.cardHearing != null) it.copy(cardHearing = Phase.LISTENING) else it }
                }
            }
            override fun onFinal(text: String) {
                main.post {
                    if (!mine()) return@post
                    endListening()
                    // Cut off at the cap, it's no answer: the card waits.
                    if (cut) port.notify(PanelStrings.cardHint().render(port.screenLangs())) else heard(text)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    if (!mine()) return@post
                    endListening()
                    // Silence isn't an answer: once more, then the card waits for a tap until its time is up, as for a typed question.
                    if (reason == SpeechToText.Reason.NO_SPEECH && choiceCard != null) return@post run { if (retry++ < 1) listen() }
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
        reading = null
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
        // "Try it here or ask" takes no yes or no: heard again once, then Robot tries.
        if (choiceCard != null) return if (retry++ < 1) listen() else choiceSilent()
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
        if (card.id == choiceCard) {
            if (spoken && !s.voiceCards) return false
            val picked = app.heyrobot.core.helpers.HelperChoice.answer(text, choiceNames, choiceHelper) ?: return false
            val helper = picked == app.heyrobot.core.helpers.HelperChoice.Picked.HELPER
            // fix-car-long-card-c: asking the helper is its yes, through the same car gate; "here" is its no and always works.
            if (helper && spoken && carHeld(card)) return true
            port.confirm(card.id, helper)
            return true
        }
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
        if (spoken && (r.does == CardAnswer.Does.CONFIRM || r.does == CardAnswer.Does.CHOOSE_ONLY) && carHeld(card)) return true
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

    /**
     * The car gate (fix-car-long-card) for a spoken yes, in a car quiet turn or on a card marked there: true if it holds
     * the yes back (nothing is approved). It counts only once Robot has read the card out whole, trusted, and not tap-only.
     */
    private fun carHeld(card: UiAction): Boolean {
        if (!port.carQuiet && !card.carTap) return false
        val yes = CarCardVoice.yes(card.carTap || CarCardVoice.tapOnly(card), port.carTrusted, reading == card.id, readWhole == card.id)
        // Robot is still reading it out: nothing is approved; it listens once the read-out is over.
        if (yes == CarCardVoice.Yes.NOT_YET && port.speaking) return true
        if (yes == CarCardVoice.Yes.COUNTS) return false
        // Not read out whole, or a tap-only card: never silence, the one line that says why.
        // fix-car-long-card-c: off the car screen (a marked card on the panel) the line doesn't send you to it.
        val line = if (port.carQuiet) TurnStrings.carTap() else TurnStrings.cardTap()
        port.notify(line.render(port.turnLangs))
        if (state.value.speak) port.speakRobot(port.said(line))
        return true
    }
}
