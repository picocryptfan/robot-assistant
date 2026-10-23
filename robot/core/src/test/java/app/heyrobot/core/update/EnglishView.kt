// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.update

import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.UpdateStrings

// Tests only (see core/EnglishView.kt): the updater's words in English, each the Phrase's `.en()`.

val UpdateText.CHANNEL_NAME: String get() = UiStrings.updates().en()
val UpdateText.CHANNEL_DESCRIPTION: String get() = UpdateStrings.channelDescription().en()
val UpdateText.UP_TO_DATE: String get() = UpdateStrings.upToDate().en()
val UpdateText.NEVER_CHECKED: String get() = UpdateStrings.neverChecked().en()
fun UpdateText.availableTitle(v: String) = availableTitleText(v).en()
fun UpdateText.readyTitle(v: String) = readyTitleText(v).en()
val UpdateText.AVAILABLE_TEXT: String get() = availableText.en()
val UpdateText.READY_TEXT: String get() = readyText.en()
fun UpdateText.updatedTitle(v: String) = updatedTitleText(v).en()
val UpdateText.UPDATED_TEXT: String get() = UpdateStrings.updatedText().en()
val UpdateText.CONFIRM_TITLE: String get() = UpdateStrings.confirmTitle().en()
val UpdateText.CONFIRM_TEXT: String get() = UpdateStrings.confirmText().en()
fun UpdateText.install(o: InstallOutcome, v: String?): String = installText(o, v).en()
