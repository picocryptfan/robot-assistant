// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. HS2 (home-screens plan, "What's new" 4): Quick access's buttons, each opening its feature itself, and only what
// this phone has. A feature that is missing but can be downloaded shows with "Set up" under its name. Nothing here goes
// online: the page reads only which features are installed and on (QuickPhone, filled in by the app).

/** A Quick access button. [key]: its name in home_settings.json. Entries are in the plan's order, a new page's. */
enum class QuickItem(val key: String) {
    TALK("talk"), CAR("car"), LINK("link"), SKILLS("skills"), LISTS("lists"), NOTES("notes"), FILES("files"),
    CALENDAR("calendar"), HISTORY("history"), TRANSCRIBE("transcribe"), INTERPRETER("interpreter"), ROUTINES("routines");

    companion object {
        val DEFAULT: List<QuickItem> = entries.toList()
        fun of(key: String): QuickItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What this phone has, for Quick access: [link] Robot to Robot is on; [skills] how many skills you have; [speech] the
 * speech model for Transcribe is installed, [speechToGet] it can be downloaded here; [translation] translation models
 * for the Interpreter are installed, [translationToGet] they can be downloaded here.
 */
data class QuickPhone(val link: Boolean = false, val skills: Int = 0, val speech: Boolean = false, val speechToGet: Boolean = false,
    val translation: Boolean = false, val translationToGet: Boolean = false)

/** How a button shows: ready to open, or with "Set up" under its name. */
enum class QuickState { READY, SET_UP }

data class QuickButton(val item: QuickItem, val state: QuickState)

object QuickItems {
    /** [item] on [phone]: READY, SET_UP (missing, downloadable), or null when this phone hasn't it (not shown). */
    fun state(item: QuickItem, phone: QuickPhone): QuickState? = when (item) {
        QuickItem.TALK, QuickItem.CAR, QuickItem.LISTS, QuickItem.NOTES, QuickItem.FILES, QuickItem.CALENDAR,
        QuickItem.HISTORY, QuickItem.ROUTINES -> QuickState.READY
        QuickItem.LINK -> QuickState.READY.takeIf { phone.link }
        QuickItem.SKILLS -> QuickState.READY.takeIf { phone.skills > 0 }
        QuickItem.TRANSCRIBE -> got(phone.speech, phone.speechToGet)
        QuickItem.INTERPRETER -> got(phone.translation, phone.translationToGet)
    }

    private fun got(installed: Boolean, toGet: Boolean): QuickState? =
        if (installed) QuickState.READY else if (toGet) QuickState.SET_UP else null

    /** The page's buttons: [order] (QuickOrder's rules), less what [phone] hasn't. */
    fun shown(order: List<QuickItem>, phone: QuickPhone): List<QuickButton> =
        QuickOrder.of(order.map { it.key }).mapNotNull { i -> state(i, phone)?.let { QuickButton(i, it) } }
}
