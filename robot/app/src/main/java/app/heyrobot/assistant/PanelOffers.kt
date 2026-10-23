// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Action
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.Executed
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperPayload
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TtsStop
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. The offer under a reply (docs/ask-claude.md "Ask me first"): "Want me to check with Jarvis?", "Want
// Jarvis's take as well?" or "Open the ChatGPT app with: «…»", what its tap or a yes sends (fixed when it was made), and
// when it goes. What a payload holds is core HelperContext's, what a yes may send HelperTrust's. The helper's turn is
// PanelHelperTurn's; the turn slot, the tools, the lock and the voice stay in PanelController, through
// [PanelOffers.Port]. Its callers run on the main thread, in the panel's scope, as before.

/** At most one offer at a time, by reply id, over PanelController's [state]; [scope]: the panel's own. */
internal class PanelOffers(private val scope: CoroutineScope, private val state: MutableStateFlow<PanelState>, private val port: Port) {
    /** What offers need of PanelController. */
    interface Port {
        /** The settings now (VoiceHub.settings). */
        val settings: RobotSettings
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** A turn is under way (the turn slot), a card included. */
        val running: Boolean
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** The phone is locked (Assistant.locked). */
        fun locked(): Boolean
        /** 1.1: during a phone lock, "closed now" in place of [p]. */
        fun unlockFirst(p: Phrase): Phrase
        /** A notice as it was rendered. */
        fun notify(text: String)
        /** A notice in Robot's screens' language. */
        fun notify(p: Phrase)
        /** Robot's own words aloud, in its voice for the turn's reply language. */
        fun speakRobot(text: String)
        fun stopVoice(why: TtsStop)
        /** The question Robot handed to a helper's app ([text]; null: none to find an answer after). */
        fun handedToApp(text: String?)
        /** [action] run by the phone side of a turn (PanelController's ToolExecutor); called off the main thread. */
        fun execute(action: Action): Executed
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
        /** A new, empty reply bubble of [h]'s at the end of the conversation (PanelHelperTurn.bubble); its id. */
        fun helperBubble(h: HelperConfig): String
        /** Runs [block] as the panel's one turn, on reply [replyId]. */
        fun startTurn(replyId: String, block: suspend () -> Unit)
        /** Robot checks with helper [h] with exactly [blocks] (PanelHelperTurn.escalate). */
        suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean, h: HelperConfig,
            fallBack: Boolean): Boolean
    }

    /**
     * What an offer's tap does, fixed when it was made: the API request's [payload], or the user's [question] for the
     * helper's app. It never picks up a screen or a picture that arrived later.
     */
    class Pending(val api: Boolean, val question: String, val payload: HelperPayload?, val voiceAsked: Boolean, val asWell: Boolean = false,
        val helper: String = HelperConfig.CLAUDE_ID)

    /** The offer waiting under a reply, by reply id, with exactly what a tap would send (at most one at a time). */
    private val offers = HashMap<String, Pending>()

    private fun words(h: HelperConfig) = HelperWords(HelperWho.of(h))

    /**
     * Shows "Want me to check with Jarvis?" under [replyId] (any helper, by its name), or "Want Jarvis's take as well?"
     * under Robot's own answer (a helper's app: "Open the ChatGPT app with: «…»").
     */
    fun show(replyId: String, o: Pending) {
        drop()
        val h = port.settings.helper(o.helper) ?: return
        offers[replyId] = o
        val text = if (o.api) o.question else AppHandOff.cap(o.question)
        state.update { st ->
            st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = Offer(o.api, text, o.payload?.sendsText.orEmpty().map { p -> p.render(port.turnLangs) }, o.payload?.preview, o.asWell, h.name,
                h.presetOf.defaultName)) else it })
        }
        // Said too, so you know a yes will do.
        if (state.value.speak) {
            port.speakRobot(port.said(if (!o.api) PanelStrings.wantOpenApp("appName" to h.presetOf.defaultName) else if (o.asWell) words(h).offerAsWellText else words(h).offerText))
        }
    }

    /** Every offer goes, from the panel too. */
    fun drop() {
        if (offers.isEmpty()) return
        offers.clear()
        state.update { st -> st.copy(turns = st.turns.map { if (it.offer != null) it.copy(offer = null) else it }) }
    }

    /** A new or loaded conversation: its offers are forgotten (its turns are replaced with them). */
    fun clear() = offers.clear()

    /** Every offer goes when one [match]es. */
    fun dropIf(match: (Pending) -> Boolean) {
        if (offers.values.any(match)) drop()
    }

    /** The reply id of the last offer whose reply [match]es, or null. */
    fun waiting(match: (String) -> Boolean): String? = offers.keys.lastOrNull(match)

    /**
     * The offer under [replyId] was accepted: [tapped] on its Send button, or a yes. A yes sends only an offer with
     * nothing personal in it (or, to a helper you trust, nothing from your screen or a photo); otherwise Robot says what
     * needs the tap and waits for it.
     */
    fun accept(replyId: String, tapped: Boolean) {
        if (state.value.busy || port.running) return
        val o = offers[replyId] ?: return
        val s = port.settings
        val h = s.helper(o.helper)
        // The helper was turned off or removed since the offer was made.
        if (h == null || !h.on) {
            drop()
            return port.notify((h?.let { words(it).offText } ?: PanelStrings.helperGone()).render(port.turnLangs))
        }
        val personal = o.payload?.personal.orEmpty()
        // "Voice yes can send personal content" (off by default): otherwise only the tap sends it.
        if (!tapped && !HelperTrust.voiceYes(s, h, personal)) {
            val say = words(h).tapToIncludeText(personal)
            port.notify(say.render(port.turnLangs))
            if (state.value.speak) port.speakRobot(port.said(say))
            return
        }
        offers.remove(replyId)
        state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
        port.stopVoice(TtsStop.NEW_QUESTION)
        RLog.i("helper_offer", "accepted" to true, "api" to o.api, "tapped" to tapped, "kind" to h.kind)
        if (!o.api) {
            // The offer was the card: the helper's app opens now (never on the lock screen).
            if (port.locked()) return port.notify(port.unlockFirst(PanelStrings.unlockToOpenApp("appName" to h.presetOf.defaultName)))
            port.handedToApp(o.question)
            scope.launch {
                val r = withContext(Dispatchers.IO) { port.execute(Action.AskHelper(o.question, helper = h.id)) }
                if (r.launchedApp && r.status.succeeded) port.leaveForApp() else port.notify(r.detail)
            }
            return
        }
        val p = o.payload ?: return
        val id = port.helperBubble(h)
        port.startTurn(id) { port.escalate(id, p.question, p.blocks, state.value.speak, reuseBubble = true, h = h, fallBack = false) }
    }

    /** Its X: the offer under [replyId] goes, unsent. */
    fun dismiss(replyId: String) {
        offers.remove(replyId) ?: return
        RLog.i("helper_offer", "accepted" to false)
        state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
    }
}
