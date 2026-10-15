// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig

// The 0.3 to 0.5 Ask Claude fields, which are the Claude helper's since 0.6.0 (schema 9): the older tests build and read
// settings with them as they did, so they still check the same behaviour.

fun claudeSettings(
    claudeEnabled: Boolean? = null,
    claudeRoute: AskRoute? = null,
    claudeModel: String = AnthropicModel.DEFAULT.id,
    claudeEffort: HelperEffortSetting = HelperEffortSetting.AUTO,
    claudeSearch: Boolean = true,
    claudeLocked: Boolean = false,
    claudeVoice: String = HelperVoices.DEFAULT,
    claudeShowCost: Boolean = true,
    claudeHelp: Escalation? = null,
    claudeBudgetCents: Int = 100,
): RobotSettings = RobotSettings(
    helpers = listOf(HelperConfig.claude().copy(enabled = claudeEnabled, route = claudeRoute, model = claudeModel, effort = claudeEffort,
        search = claudeSearch, locked = claudeLocked, voice = claudeVoice)),
    help = claudeHelp, helperBudgetCents = claudeBudgetCents, helperShowCost = claudeShowCost,
)

val RobotSettings.claudeEnabled: Boolean? get() = claude?.enabled
val RobotSettings.claudeRoute: AskRoute? get() = claude?.route
val RobotSettings.claudeModel: String? get() = claude?.model
val RobotSettings.claudeSearch: Boolean get() = claude?.search == true
val RobotSettings.claudeLocked: Boolean get() = claude?.locked == true
val RobotSettings.claudeVoice: String? get() = claude?.voice
val RobotSettings.claudeShowCost: Boolean get() = helperShowCost
val RobotSettings.claudeHelp: Escalation? get() = help
val RobotSettings.claudeBudgetCents: Int get() = helperBudgetCents
