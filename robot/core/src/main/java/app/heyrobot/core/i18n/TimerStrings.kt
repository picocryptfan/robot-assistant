// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// named-timers: Robot's own timers (core OwnTimers): what Robot says, and its ringing notification.
object TimerStrings : Strings("timer", area = "routines") {
    val channel = s("channel", "Timers", "Minuteries")
    val setNamed = s("set_named", "The {name} timer is set for {duration}. It ends at {time}.", "La minuterie {name} de {duration} est réglée. Elle finit à {time}.")
    val setPlain = s("set_plain", "Timer set for {duration}. It ends at {time}.", "Minuterie de {duration} réglée. Elle finit à {time}.")
    val leftNamed = s("left_named", "The {name} timer has {left} left. It ends at {time}.", "La minuterie {name} a encore {left}. Elle finit à {time}.")
    val leftPlain = s("left_plain", "Your timer has {left} left. It ends at {time}.", "Ta minuterie a encore {left}. Elle finit à {time}.")
    val ringingNamed = s("ringing_named", "The {name} timer is ringing. Say stop.", "La minuterie {name} sonne. Dis arrête.")
    val ringingPlain = s("ringing_plain", "Your timer is ringing. Say stop.", "Ta minuterie sonne. Dis arrête.")
    val none = s("none", "You have no timer running in Robot.", "Tu n'as aucune minuterie en cours dans Robot.")
    val noneCalled = s("none_called", "You have no {name} timer.", "Tu n'as pas de minuterie {name}.")
    val cancelledNamed = s("cancelled_named", "The {name} timer is cancelled.", "La minuterie {name} est annulée.")
    val cancelledPlain = s("cancelled_plain", "Your timer is cancelled.", "Ta minuterie est annulée.")
    val cancelledAll = s("cancelled_all", "All your timers are cancelled.", "Toutes tes minuteries sont annulées.")
    val which = s("which", "You have {count} timers. Say which one to cancel.", "Tu as {count} minuteries. Dis laquelle annuler.")
    val stopped = s("stopped", "Timer stopped.", "Minuterie arrêtée.")
    val full = s("full", "You already have 10 timers in Robot. Cancel one first.", "Tu as déjà 10 minuteries dans Robot. Annules-en une d'abord.")
    val tooLong = s("too_long", "A timer can last up to 24 hours.", "Une minuterie peut durer jusqu'à 24 heures.")
    val cantRing = s("cant_ring", "Robot can't ring because its notifications are off, so the timer goes to your clock app.",
        "Robot ne peut pas sonner, car ses notifications sont désactivées. La minuterie va donc dans ton appli d'horloge.")
    val noteTitleNamed = s("note_title_named", "{name} timer", "Minuterie {name}")
    val noteTitle = s("note_title", "Timer", "Minuterie")
    val noteText = s("note_text", "Time's up: {duration}.", "Temps écoulé : {duration}.")
    val noteLate = s("note_late", "Late: it ended at {time}, while Robot couldn't ring.", "En retard : elle a fini à {time}, quand Robot ne pouvait pas sonner.")
    val noteStop = s("note_stop", "Stop", "Arrêter")
    val noteMissed = s("note_missed", "Timer missed", "Minuterie manquée")
    val noteMissedText = s("note_missed_text", "It ended at {time}, while Robot couldn't ring.", "Elle a fini à {time}, quand Robot ne pouvait pas sonner.")
}
