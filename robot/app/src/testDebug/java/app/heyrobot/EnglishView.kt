// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.AdbFix
import app.heyrobot.core.Approval
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BrainModel
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.EmailApp
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.Playback
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.StopKey
import app.heyrobot.core.TextApp
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.helpers.Preset
import app.heyrobot.core.i18n.MemoryStrings
import app.heyrobot.core.memory.MemoryReplies
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.KeepSources

// Tests only: core's words in English, each the Phrase's `.en()` (the String wrappers l10n WP9 removed from the code the app runs).

val AdbFix.title: String get() = titleText.en()
val AdbFix.why: String get() = whyText.en()
val Approval.title: String get() = titleText.en()
val SpeakReplies.title: String get() = titleText.en()
val VoiceChoice.title: String get() = titleText.en()
val VoiceChoice.detail: String get() = detailText.en()
val BargeIn.title: String get() = titleText.en()
val Playback.title: String get() = titleText.en()
val StopKey.title: String get() = titleText.en()
val TextApp.title: String get() = titleText.en()
val EmailApp.title: String get() = titleText.en()
val NearestOnline.title: String get() = titleText.en()
val BrainModel.title: String get() = titleText.en()
val VoiceBundle.title: String get() = titleText.en()
val Preset.title: String get() = titleText.en()
fun HelperVoices.title(name: String): String = titleText(name).en()
fun ModelLogic.gb(bytes: Long): String = gbText(bytes).en()
val AnswerCard.readLine: String? get() = readText?.en()
fun AnswerCard.savedLine(keep: KeepSources): String = savedText(keep).en()
val MemoryReplies.CARD_FORGOTTEN: String get() = MemoryStrings.cardForgotten().en()
val MemoryReplies.CARD_WHICH: String get() = MemoryStrings.cardWhich().en()
val MemoryReplies.CARD_SAVED_DETAIL: String get() = cardSavedDetailText().en()
val MemoryReplies.CARD_FORGOTTEN_DETAIL: String get() = cardForgottenDetailText().en()
