// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. today-fix-b (review finding 5): "show my calendar" (RoutineRouter's SHOW_CALENDAR). With Today off it isn't a routine
// phrase, so it goes to the model with the calendar tools, as in 1.2. Hands-free (a headset or car session, car mode, a
// panel the wake word opened: PanelRoutines.Host.handsFree) Robot says the day line, as "how's my day", and opens nothing.
// Otherwise the Calendar tab opens, as H3 made it.

object ShowCalendar {
    private fun calendar(c: RoutineCommand?): Boolean = c is RoutineCommand.Show && c.page == ShowPage.CALENDAR

    /** [cmd] as the panel routes it: none for "show my calendar" while Today is off ([todayOn], read only then). */
    fun routed(cmd: RoutineCommand, todayOn: () -> Boolean): RoutineCommand? = if (calendar(cmd) && !todayOn()) null else cmd

    /** [cmd] as the panel answers it: hands-free, "show my calendar" is [RoutineCommand.Today]. */
    fun handsFree(cmd: RoutineCommand, handsFree: Boolean): RoutineCommand = if (handsFree && calendar(cmd)) RoutineCommand.Today else cmd
}
