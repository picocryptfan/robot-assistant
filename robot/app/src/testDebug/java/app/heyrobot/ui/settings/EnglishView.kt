// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.LookSettings
import app.heyrobot.core.Approval
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.ui.Snapshot

// Tests only: Settings' rows in English, each the Phrase's `.en()` (the String functions l10n WP9 removed from the code the app
// runs: Settings' root shows `…SummaryText(…).str()`).

internal fun approvalSummary(t: ToolName, level: Approval, locked: Boolean): String = approvalSummaryText(t, level, locked).en()
fun displaySummary(l: LookSettings): String = displaySummaryText(l).en()
fun headsetSummary(f: HeadsetFile, memory: DeviceMemory): String = headsetSummaryText(f, memory).en()
fun memorySummary(s: MemorySettings): String = memorySummaryText(s).en()
internal fun messagesSummary(f: MessagesFile): String = messagesSummaryText(f).en()
internal fun wakeSummary(s: RobotSettings): String = wakeSummaryText(s).en()
internal fun voiceSummary(s: RobotSettings): String = voiceSummaryText(s).en()
internal fun actionsSummary(s: RobotSettings): String = actionsSummaryText(s).en()
internal fun safetySummary(s: RobotSettings): String = safetySummaryText(s, Langs.EN).en()
internal fun peopleSummary(s: RobotSettings, f: AssistantFacts?): String = peopleSummaryText(s, f).en()
internal fun helpersSummary(s: RobotSettings, f: AssistantFacts?): String = helpersSummaryText(s, f).en()
internal fun conversationSummary(s: RobotSettings): String = conversationSummaryText(s).en()
internal fun modelSummary(s: RobotSettings, snap: Snapshot): String = modelSummaryText(s, snap).en()
internal fun termuxSummary(t: TermuxSettings, scripts: Int): String = termuxSummaryText(t, scripts).en()
