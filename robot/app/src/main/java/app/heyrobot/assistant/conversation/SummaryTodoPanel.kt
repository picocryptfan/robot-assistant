// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import java.util.UUID

// VOICE PROCESS. summary-todos (docs/plans/summary-todos-plan.md): a summary's to-do on its way to the panel's calendar
// card. SummaryActivity's Add to calendar puts the to-do's title and day here, in this process's memory only, under a
// one-time id, and shows the panel with the id alone ([ARG]): the words never go into a show's arguments. The panel takes
// it once (PanelController.onShow) and runs add_event's card, which only a tap adds; one left waiting longer than
// [WAIT_MS] is dropped, and none is taken over the lock screen or during a phone lock. Nothing here is written anywhere.
object SummaryTodoPanel {
    /** The show's argument: the one-time id (ShowTrust keeps "robot_" arguments on the assistant's own show only). */
    const val ARG = "robot_summary_todo" // l10n: not text (a show argument's key)

    /** How long a to-do waits for the panel. */
    const val WAIT_MS = 120_000L

    /** At most this many wait at once (each Add to calendar tap adds one). */
    const val MAX = 4

    /** add_event's arguments: the to-do's words and its day ("2026-11-14") or time ("2026-11-14 15:00"). */
    data class Ask(val title: String, val whenArg: String) {
        /**
         * add_event's arguments. The plan is silent on an alert, so the event keeps F10's (EventAlert: 9:00 on an all-day
         * to-do's day, else its time), and the card names that alert from the same EventPlan.alertAt: card and event agree.
         */
        fun args(): Map<String, Any> = mapOf("title" to title, "when" to whenArg, "alert" to true)
    }

    /** What a take found: the to-do, a refusal because the phone is locked (the to-do is dropped), or nothing waiting. */
    sealed interface Taken {
        data class Ready(val ask: Ask) : Taken
        object Locked : Taken
        object Gone : Taken
    }

    private class Waiting(val ask: Ask, val at: Long)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** Keeps [ask] for the panel; its one-time id. */
    @Synchronized
    fun offer(ask: Ask, now: Long): String {
        drop(now)
        while (waiting.size >= MAX) waiting.remove(waiting.keys.first())
        val id = UUID.randomUUID().toString()
        waiting[id] = Waiting(ask, now)
        return id
    }

    /** The to-do under [id], once; null when it is unknown, taken, too old, or the phone is [locked] (it stays dropped). */
    @Synchronized
    fun take(id: String?, locked: Boolean, now: Long): Ask? = (open(id, locked, now) as? Taken.Ready)?.ask

    /** As [take], saying why nothing came: [Taken.Locked] when the to-do was waiting but the phone is [locked]. */
    @Synchronized
    fun open(id: String?, locked: Boolean, now: Long): Taken {
        drop(now)
        val w = id?.let(waiting::remove) ?: return Taken.Gone
        return if (locked) Taken.Locked else Taken.Ready(w.ask)
    }

    private fun drop(now: Long) {
        waiting.entries.removeAll { now - it.value.at > WAIT_MS || now < it.value.at }
    }
}
