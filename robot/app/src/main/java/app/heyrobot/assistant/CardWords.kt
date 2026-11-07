// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.UiStrings

/**
 * fix-124c (phone-1.2.4-c): a waiting card's question ("Do you want Robot to do this?") and buttons ("Cancel", "Do it",
 * "Turn off") follow the conversation's language, as its title does; they used to take the menus' language, so « annule mon
 * alarme » on English menus showed « Désactiver ton alarme de 12 h 31 » over "Cancel" and "Turn off".
 */
internal object CardWords {
    /** The languages a card's words take in a turn spoken in [turn]: its title's ([Langs.message]: English to French menus stays French). */
    fun langs(turn: Langs): Langs = turn.message

    /** [a] with its question and default buttons in [l], when it waits and has none of its own. */
    fun dress(a: UiAction, l: Langs): UiAction = if (a.status != ActionStatus.PENDING) a else a.copy(
        ask = a.ask ?: (if (a.choices.isEmpty()) UiStrings.doYouWant else UiStrings.whichOne)().render(l),
        cancel = a.cancel ?: UiStrings.cancel().render(l),
        doIt = a.doIt ?: UiStrings.doIt().render(l),
    )
}
