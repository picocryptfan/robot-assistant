// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.core.LockScreen
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.SafetyNotice
import app.heyrobot.ui.common.NoticeRow
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.toolIcon
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › Safety & privacy (0.4.0): every rule about when Robot asks first, what works on the lock
// screen, what helpers (Claude, or any other since 0.6.0) may get without a tap, how direct the local model is, and what
// History keeps. Nothing is fixed in code: each is a switch here with a sensible default (core Safety.kt, ApprovalGate).
// Built like Android's Settings (0.4.x redesign): one line under each switch, the explanations in the footer.

@Composable
fun SafetyScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    PreferenceScreen("Safety & privacy", onBack, snackbar = snackbar) {
        item(key = "intro") {
            IntroPreference("How careful Robot is. Each action's own level (Just do it, Ask first, Off) is in Settings › Actions; " +
                "these rules decide when “Just do it” still shows a card first.")
        }
        preferenceGroup("Cards", "cards") {
            ListPreference("When Robot asks first", s.safetyCards, SafetyCards.entries, { it.title },
                { (if (it == SafetyCards.UNASKED) "The default. " else "") + it.detail }) { update(s.copy(safetyCards = it)) }
            SwitchPreference("Card when a helper asks Robot to do something", "A helper's input may come from web pages",
                s.helperActionCards) { update(s.copy(helperActionCards = it)) }
            SwitchPreference("Card when who-to isn't certain", "Several matches, a near match, or a number the model wrote",
                s.whoCards) { update(s.copy(whoCards = it)) }
            SwitchPreference("Card for names learned from notifications", "Whoever sent the message chose that name",
                s.learnedCards) { update(s.copy(learnedCards = it)) }
            SwitchPreference("Answer cards by voice", "Say yes or no while a card waits", s.voiceCards) { update(s.copy(voiceCards = it)) }
            SwitchPreference("Card before deleting files", "Large photos and videos, and Robot's cache",
                s.deleteCards) { update(s.copy(deleteCards = it)) }
        }
        preferenceGroup("Works on the lock screen", "locked") {
            for (t in LockScreen.CHOICES) {
                val on = t in s.lockedTools
                SwitchPreference(t.title, if (t in LockScreen.DEFAULT) "On by default" else null, on, icon = toolIcon(t)) { now ->
                    update(s.copy(lockedTools = if (now) s.lockedTools + t else s.lockedTools - t))
                }
            }
            if (s.lockedTools != LockScreen.DEFAULT) {
                PreferenceItem("Back to timers and alarms", "Only the default actions work locked", RIcons.Refresh,
                    onClick = { update(s.copy(lockedTools = LockScreen.DEFAULT)) })
            }
        }
        preferenceGroup("Helpers", "helpers") {
            SwitchPreference("Send personal content to helpers automatically", if (s.personalToHelpers) "Screen text, photos and what Robot read may go"
                else "Only your question goes by itself", s.personalToHelpers) { update(s.copy(personalToHelpers = it)) }
            SwitchPreference("Voice yes can send personal content", if (s.voiceYesPersonal) "A spoken or typed yes can send it" else "Only a tap sends it",
                s.voiceYesPersonal) { update(s.copy(voiceYesPersonal = it)) }
        }
        preferenceGroup("Answers", "answers") {
            SwitchPreference("Direct answers", "No refusals or added warnings", s.directAnswers) { update(s.copy(directAnswers = it)) }
        }
        preferenceGroup("History", "history") {
            SwitchPreference("Keep screenshots and photos in history",
                if (!s.saveHistory) "Saving conversations is off" else if (s.keepMedia) "A thumbnail and the screen's text" else "Only that there was one",
                s.keepMedia, enabled = s.saveHistory) { update(s.copy(keepMedia = it)) }
            PreferenceItem("Remove saved screenshots and photos", "From every saved conversation", RIcons.Delete, onClick = { confirmRemove = true })
        }
        preferenceFooter(SAFETY_FOOTER)
    }
    if (confirmRemove) {
        ConfirmDialog("Remove saved screenshots and photos?", "The conversations stay; their thumbnails and screen text are removed. This can't be undone.",
            "Remove", onDismiss = { confirmRemove = false }) { confirmRemove = false; a.removeSavedMedia() }
    }
}

/** What each rule on the page does, at length (the rows say it in a line). */
internal val SAFETY_FOOTER =
    "When Robot asks first: when something Robot read (a screen, a photo, a note, an event, a contact, the weather, a helper's " +
        "answer) makes an action ask first.\n\n" +
        "Who-to isn't certain: several people or numbers fit, a name only nearly matches, the model wrote a number or address " +
        "itself, or an app name matched loosely. Off: Robot takes the best match.\n\n" +
        "Answer cards by voice: while a card waits, say “yes”, “do it”, “send it” or “oui”, or “no”, “cancel” or “annule” (or " +
        "type it). Robot listens when replies are spoken or you asked by voice; the mic answers the card too. A card for a name " +
        "learned from a notification, or when Robot isn't sure who or which, still needs a tap (a no works). Off: a tap, or a typed yes or no.\n\n" +
        "Card before deleting files: a large photo or video (Android still asks too), or Robot's cache. Off: Robot goes straight " +
        "to Android's confirmation for the largest one.\n\n" +
        "Works on the lock screen: a locked phone can't tell who is asking. Questions always work; the actions turned on above too. " +
        "Everything else asks you to unlock.\n\n" +
        "Send personal content to helpers automatically, for every helper: when Robot checks with a helper by itself, it may " +
        "include the conversation's screen text, photos and what it read (your calendar, notes, contacts, location), and what " +
        "Robot reads for a helper goes without “Share with …”. Off: only your question goes, and anything more needs a tap. " +
        "Bringing a helper into the conversation and “ask a helper about my screen” always ask first. Voice yes can send personal content, for every " +
        "helper: off, a spoken or typed “yes” never sends your screen, a photo or what Robot read to a helper; only a tap does. " +
        "A helper you trust with personal content (on its page in Settings › Helpers) gets what Robot read without asking, but " +
        "your screen and photos still need a tap. These two switches go further, for every helper.\n\n" +
        "Direct answers: Robot helps with whatever you ask without refusing or adding warnings; it still says when something " +
        "isn't possible.\n\n" +
        "Keep screenshots and photos in history: off, Robot saves only that a question had a screen or a photo. On, a small " +
        "thumbnail and the screen's text are saved with the conversation, in Robot's private storage on this phone, for you to " +
        "see in History (a screen conversation is still saved only after Keep). They are never sent to a helper or read to the " +
        "model again. Saving conversations itself is in Settings › History."

/**
 * The one-time line after updating to 0.4.0 (core RobotSettings.safetyNotice, SafetyNotice.TEXT), on Home and Settings:
 * Open shows Safety & privacy, Dismiss closes it; either way it never shows again. Nothing is logged. A neutral notice row
 * with the shield symbol (0.4.x redesign; before, a tinted banner).
 */
@Composable
fun SafetyNoticeLine(onOpen: () -> Unit, onDismiss: () -> Unit) {
    NoticeRow(SafetyNotice.TEXT, onDismiss = onDismiss, icon = RIcons.Shield, action = "Open", onAction = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}
