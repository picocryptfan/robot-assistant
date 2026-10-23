// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Attachment
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.Tip
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.ui.common.RobotFace

// VOICE PROCESS. The assistant panel's state as the panel shows it (PanelController keeps it, PanelUi draws it), and
// what the session offers the controller.

/**
 * TRANSCRIBING: the person stopped talking and the speech is being written down. CONFIRMING: a card waits for
 * "Do it" or "Cancel"; the turn is still under way (no new question until it ends; Stop declines the card).
 */
enum class Phase { IDLE, LISTENING, TRANSCRIBING, THINKING, ANSWERING, CONFIRMING, SPEAKING }

/**
 * The phase once a turn has ended ([speaking]: the voice is still reading the reply). Only a turn's own phases
 * give way: listening for the next question ("hey robot" during the answer) or anything else already shown stays.
 */
fun Phase.afterTurn(speaking: Boolean): Phase = when (this) {
    Phase.THINKING, Phase.ANSWERING, Phase.CONFIRMING -> if (speaking) Phase.SPEAKING else Phase.IDLE
    else -> this
}

/**
 * "Stop" said, a tap on the conversation, a volume key or "Stop speaking" in this phase, with a turn under way
 * ([turnRunning]): true when it ends the turn as the Stop button does, not only the voice (0.4.6). While the model (or
 * a helper) is still writing the reply, stopping only the voice left the turn running: a long story went on being written
 * for up to a minute, the mic button did nothing and a new question got "Robot is still on the last question". A card
 * waiting for a yes or no stays: "stop" then only quiets Robot's "Say yes or no.".
 */
fun Phase.stopEndsTurn(turnRunning: Boolean): Boolean = turnRunning && (this == Phase.THINKING || this == Phase.ANSWERING)

/**
 * 1.0: which robot the panel's orb shows in this phase. Listening wins, a card's "yes" or "no" included
 * ([cardHearing]); then Robot's voice ([talking]: reading aloud, also while the reply is still being written or a card
 * is read out); then writing the reply; otherwise idle, a card waiting for a tap included.
 */
fun Phase.face(cardHearing: Phase?, talking: Boolean): RobotFace =
    if (cardHearing != null) RobotFace.LISTENING
    else when (this) {
        Phase.LISTENING, Phase.TRANSCRIBING -> RobotFace.LISTENING
        Phase.SPEAKING -> RobotFace.SPEAKING
        Phase.THINKING, Phase.ANSWERING -> if (talking) RobotFace.SPEAKING else RobotFace.TYPING
        Phase.IDLE, Phase.CONFIRMING -> if (talking) RobotFace.SPEAKING else RobotFace.IDLE
    }

/**
 * [icon]: the real app's launcher icon on an open_app card. [body]: the full message, email or note, or what a read
 * tool found. [choices]: the people or numbers to tap while it waits. [confirm]: the button ("Open in Cheogram").
 * [undo]: a saved note's key for Undo. [content]: a read tool brought content into the conversation.
 */
data class UiAction(
    val id: String,
    val tool: ToolName?,
    val summary: String,
    val status: ActionStatus,
    val detail: String?,
    val icon: Bitmap? = null,
    val body: String? = null,
    val choices: List<String> = emptyList(),
    val confirm: String? = null,
    val undo: String? = null,
    val content: Boolean = false,
    /** More people or numbers fitted than [choices] lists. */
    val more: Int = 0,
    /** The Cancel button's own words ("Start fresh"). */
    val cancel: String? = null,
    /** An app the card offers to open (Cheogram when no contact matched), by package. */
    val handOff: String? = null,
    /** The picture a share card would send. */
    val picture: Bitmap? = null,
    /** The tick box next to the choices that remembers the one tapped ("Always use this for “mom”"). */
    val remember: String? = null,
    /** While it waits, an "Allow contacts" button too. */
    val allowContacts: Boolean = false,
    /** Its button sends personal content to a helper: a spoken or typed yes doesn't, unless "Voice yes can send personal content". */
    val personal: Boolean = false,
    /** Only a tap answers it, for this reason (core Card.tapFor: a learned name, not sure who, online the first time); "no" still declines. */
    val tapFor: AskWhy? = null,
    /** 0.9: a Termux command or script's card (TermuxPanel); its line ([detail]) says why only a tap runs it. */
    val termux: app.heyrobot.ui.common.TermuxCardView? = null,
    /** 1.1: a skill's save card's chips (core Card.chips). */
    val chips: List<String> = emptyList(),
    /** 1.4: a Robot Files card (the folder's symbol; no tool, so History keeps none of it). */
    val files: Boolean = false,
) {
    /** What a spoken or typed answer needs to know about it (core CardAnswer). */
    val waiting: CardAnswer.Waiting get() = CardAnswer.Waiting(choices.size, tapFor, personal, confirm, say = detail.takeIf { termux != null && tapFor != null })
}

/**
 * "Want me to check with Jarvis?" under a reply (docs/ask-claude.md "When Robot needs help"; any helper since 0.6.0, by
 * its [name]): a tap (or a yes, when nothing personal would go) asks the helper through its API ([api], listing what
 * goes: [sends], with the start of the screen's text in [preview]) or opens its [app] with [text]. Nothing happens
 * without it. [asWell]: it sits under Robot's own answer, given after the local model first handed the question over
 * ("Want Jarvis's take as well?").
 */
data class Offer(val api: Boolean, val text: String, val sends: List<String>, val preview: String? = null, val asWell: Boolean = false,
    val name: String = "Claude", val app: String = "Claude")

data class UiTurn(
    val id: String,
    val role: TurnRole,
    val text: String,
    val attachment: Attachment = Attachment.NONE,
    val thumb: Bitmap? = null,
    val actions: List<UiAction> = emptyList(),
    val streaming: Boolean = false,
    val stopped: Boolean = false,
    val error: Boolean = false,
    /** A helper wrote it: its name as a label ([by]) and the tertiary accent. [readBack]: read off the Claude app's screen. */
    val fromHelper: Boolean = false,
    val readBack: Boolean = false,
    /** "Claude searched the web" (in the helper's name) and these domains. */
    val sources: List<String> = emptyList(),
    /** "≈ 1¢" (only with "Show cost" on), or "cost unknown". */
    val cost: String? = null,
    val offer: Offer? = null,
    /** 0.6.0: which helper wrote it (its id and its name at the time), and whether it is a server on your network. */
    val helper: String? = null,
    val by: String? = null,
    val home: Boolean = false,
    /** 0.7: the reply card under it (PanelMessages). */
    val reply: app.heyrobot.ui.common.ReplyCardView? = null,
    /** 0.9: a web answer's card (WebAnswerCard): shown, never saved (History keeps its saved line). */
    val web: AnswerCard? = null,
    /** 1.0: said while the phone was locked (core ChatTurn.locked): under "Said while the phone was locked". */
    val locked: Boolean = false,
)

data class Draft(val kind: Attachment, val thumb: Bitmap?)

/**
 * OPEN_ASSIST_SETTINGS: the Digital assistant app page, where "Use text from screen" and "Use screenshot" are.
 * OPEN_MODEL (0.4.3): Robot's Settings › Model, to choose a model that can do actions.
 */
enum class NoticeAction { NONE, OPEN_APP, OPEN_ASSIST_SETTINGS, OPEN_MODEL }

data class Notice(val text: String, val action: NoticeAction = NoticeAction.NONE)

data class PanelState(
    val convId: String,
    val turns: List<UiTurn> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val notice: Notice? = null,
    val draft: Draft? = null,
    /** "Ask about this screen" (core ScreenAccess): hidden, or what a tap does. */
    val screenChip: ScreenChip = ScreenChip.HIDDEN,
    val locked: Boolean = false,
    val speak: Boolean = false,
    /** Saved to the history (a conversation that used the screen is saved only when the person keeps it). */
    val keep: Boolean = true,
    val sttAvailable: Boolean = false,
    /** Why voice input is unavailable (the mic button's tooltip, e.g. "Download voice models"); null when it works. */
    val micHint: String? = null,
    /** Bumped to ask the panel to focus the text field and show the keyboard. */
    val focusRequest: Int = 0,
    /** Bumped on every show: the panel's own state (a drag offset, the attach row) starts fresh each time. */
    val showId: Int = 0,
    val brain: BrainKeeper.State = BrainKeeper.State(),
    /** A helper's conversation ("go get …"): the following turns go to it ("Talking with Jarvis · Back to Robot"). Its id and name. */
    val helperMode: String? = null,
    val helperName: String? = null,
    /** That helper is a server on your network (the chip says where questions go). */
    val helperHome: Boolean = false,
    /** "ChatGPT is thinking…", "Checking with ChatGPT…" or "Jarvis is loading its model…" until the helper's first words. */
    val helperStatus: String? = null,
    /** The corner swipe came from the Claude app: "Read Claude's answer". */
    val claudeScreen: Boolean = false,
    /** What works on the lock screen (Settings › Safety & privacy), for the panel's words. */
    val lockedTools: Set<ToolName> = LockScreen.DEFAULT,
    /** Listening for "yes" or "no" to the waiting card (LISTENING, then TRANSCRIBING); null: not. The phase stays CONFIRMING. */
    val cardHearing: Phase? = null,
    /** The empty panel's three tips (core Tips), a different three each time it opens. */
    val tips: List<Tip> = emptyList(),
    /** 1.0: Robot's voice is reading aloud right now (VoiceHub's TTS), whatever the phase: the header's robot speaks. */
    val talking: Boolean = false,
) {
    /** The robot in the header. */
    val face: RobotFace get() = phase.face(cardHearing, talking)

    /** A helper's conversation is on. */
    val inHelperMode: Boolean get() = helperMode != null

    /** A turn is under way, a confirmation card included: no new question, no mic, no camera until it ends. */
    val busy: Boolean get() = phase == Phase.THINKING || phase == Phase.ANSWERING || phase == Phase.CONFIRMING
    /** The screen's text or a screenshot came with this panel. */
    val screenAvailable: Boolean get() = screenChip == ScreenChip.READY
}

/** What the session offers the controller. */
interface PanelHost {
    fun hidePanel()
    /** Starts another app's activity from the panel; false if it could not. */
    fun launch(intent: Intent): Boolean
    /** Starts Robot's capture activity as an assistant activity; false if it could not. */
    fun startCapture(intent: Intent): Boolean
    /**
     * 1.0 (secrets-memory-private-items-not-secure): the panel shows something private from memory: its window is kept out
     * of screenshots, recordings and casting ([on]) until the conversation changes.
     */
    fun secure(on: Boolean) {}
}
