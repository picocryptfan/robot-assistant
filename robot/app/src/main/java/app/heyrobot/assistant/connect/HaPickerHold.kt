// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import app.heyrobot.core.connect.HaPickers

// VOICE PROCESS. Home Assistant's pickers as Test last read them (docs/plans/skills-connections-plan.md §3.1; C5): one
// connection's lists, in this process's memory only, while its window is open. Never written to a file, logged, put in an
// intent or sent to the main process; the window forgets them when it closes, and a Test of another connection replaces
// them. The editor that picks from them (C9) runs where they are.

object HaPickerHold {
    @Volatile private var held: Pair<String, HaPickers>? = null

    /** Keeps [p], read by Test for connection [id]. */
    fun keep(id: String, p: HaPickers) {
        held = id to p
    }

    /** Connection [id]'s lists, if Test read them since its window opened. */
    fun get(id: String): HaPickers? = held?.takeIf { it.first == id }?.second

    /** Forgets them (the window closed). */
    fun forget(id: String? = null) {
        if (id == null || held?.first == id) held = null
    }
}
