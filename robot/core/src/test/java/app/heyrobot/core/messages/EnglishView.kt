// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs

// Tests only (see core/EnglishView.kt): the messages' quarantine words in English, or in French.

private val FR = Langs.of(Lang.FR)

val MessageQuarantine.SAY_YES_AGAIN_FR: String get() = sayYesAgainText().render(FR)
fun MessageQuarantine.forbidden(tool: ToolName): String = forbiddenText(tool).en()
val MessageQuarantine.CHECK_AND_TAP: String get() = checkAndTapText().en()
val MessageQuarantine.CHECK_AND_TAP_FR: String get() = checkAndTapText().render(FR)
