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
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
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
    val langs = LocalLangs.current
    val cardsTitle = stringResource(R.string.safety_cards)
    val lockedTitle = stringResource(R.string.safety_locked)
    val helpersTitle = UiStrings.helpers().str()
    val answersTitle = stringResource(R.string.safety_answers)
    val historyTitle = UiStrings.history().str()
    val footer = stringResource(R.string.safety_footer)
    val cardDetails = SafetyCards.entries.associateWith { (if (it == SafetyCards.UNASKED) SettingsStrings.theDefault("detail" to it.detailText) else it.detailText).str() }
    PreferenceScreen(UiStrings.safety().str(), onBack, snackbar = snackbar) {
        item(key = "intro") {
            IntroPreference(stringResource(R.string.safety_intro))
        }
        preferenceGroup(cardsTitle, "cards") {
            ListPreference(stringResource(R.string.safety_when_asks), s.safetyCards, SafetyCards.entries, { it.titleText.render(langs) },
                { cardDetails.getValue(it) }) { update(s.copy(safetyCards = it)) }
            SwitchPreference(stringResource(R.string.safety_helper_cards), stringResource(R.string.safety_helper_cards_detail),
                s.helperActionCards) { update(s.copy(helperActionCards = it)) }
            SwitchPreference(stringResource(R.string.safety_who_cards), stringResource(R.string.safety_who_cards_detail),
                s.whoCards) { update(s.copy(whoCards = it)) }
            SwitchPreference(stringResource(R.string.safety_learned_cards), stringResource(R.string.safety_learned_cards_detail),
                s.learnedCards) { update(s.copy(learnedCards = it)) }
            SwitchPreference(stringResource(R.string.safety_voice_cards), stringResource(R.string.safety_voice_cards_detail), s.voiceCards) {
                update(s.copy(voiceCards = it))
            }
            SwitchPreference(stringResource(R.string.safety_delete_cards), stringResource(R.string.safety_delete_cards_detail),
                s.deleteCards) { update(s.copy(deleteCards = it)) }
        }
        preferenceGroup(lockedTitle, "locked") {
            val byDefault = stringResource(R.string.safety_locked_default)
            for (t in LockScreen.CHOICES) {
                val on = t in s.lockedTools
                SwitchPreference(t.titleText.str(), if (t in LockScreen.DEFAULT) byDefault else null, on, icon = toolIcon(t)) { now ->
                    update(s.copy(lockedTools = if (now) s.lockedTools + t else s.lockedTools - t))
                }
            }
            if (s.lockedTools != LockScreen.DEFAULT) {
                PreferenceItem(stringResource(R.string.safety_locked_reset), stringResource(R.string.safety_locked_reset_detail), RIcons.Refresh,
                    onClick = { update(s.copy(lockedTools = LockScreen.DEFAULT)) })
            }
        }
        preferenceGroup(helpersTitle, "helpers") {
            SwitchPreference(UiStrings.sendPersonalContent().str(),
                stringResource(if (s.personalToHelpers) R.string.safety_personal_on else R.string.safety_personal_off), s.personalToHelpers) {
                update(s.copy(personalToHelpers = it))
            }
            SwitchPreference(stringResource(R.string.safety_voice_yes),
                stringResource(if (s.voiceYesPersonal) R.string.safety_voice_yes_on else R.string.safety_voice_yes_off),
                s.voiceYesPersonal) { update(s.copy(voiceYesPersonal = it)) }
        }
        preferenceGroup(answersTitle, "answers") {
            SwitchPreference(stringResource(R.string.safety_direct), stringResource(R.string.safety_direct_detail), s.directAnswers) {
                update(s.copy(directAnswers = it))
            }
        }
        preferenceGroup(historyTitle, "history") {
            SwitchPreference(stringResource(R.string.safety_keep_media),
                stringResource(if (!s.saveHistory) R.string.safety_keep_media_no_history else if (s.keepMedia) R.string.safety_keep_media_on
                    else R.string.safety_keep_media_off),
                s.keepMedia, enabled = s.saveHistory) { update(s.copy(keepMedia = it)) }
            PreferenceItem(stringResource(R.string.safety_remove_media), stringResource(R.string.safety_remove_media_detail), RIcons.Delete,
                onClick = { confirmRemove = true })
        }
        preferenceFooter(footer)
    }
    if (confirmRemove) {
        ConfirmDialog(stringResource(R.string.safety_remove_title), stringResource(R.string.safety_remove_text),
            stringResource(R.string.safety_remove_confirm), onDismiss = { confirmRemove = false }) { confirmRemove = false; a.removeSavedMedia() }
    }
}

/**
 * The one-time line after updating to 0.4.0 (core RobotSettings.safetyNotice, SafetyNotice.text), on Home and Settings:
 * Open shows Safety & privacy, Dismiss closes it; either way it never shows again. Nothing is logged. A neutral notice row
 * with the shield symbol (0.4.x redesign; before, a tinted banner).
 */
@Composable
fun SafetyNoticeLine(onOpen: () -> Unit, onDismiss: () -> Unit) {
    NoticeRow(SafetyNotice.text.str(), onDismiss = onDismiss, icon = RIcons.Shield, action = UiStrings.open().str(), onAction = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}
