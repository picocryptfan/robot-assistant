// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// correct-last (docs/plans/correct-last-plan.md): what Robot says after "undo that" or "no, 6:45" about its own last
// action (core CorrectRouter). Ids start with "correct."; the area is "cards". Québec French (tu), the glossary's words.
// No line names an event's or a note's title, so none says anything private while the phone is locked.

object CorrectStrings : Strings("correct", area = "cards") {
    val nothing = s("nothing", "There's nothing for Robot to undo.", "Il n'y a rien à annuler pour Robot.")
    val tooOld = s("too_old", "Robot can only undo what it did in the last 2 minutes.",
        "Robot peut seulement annuler ce qu'il a fait dans les 2 dernières minutes.")
    val cantTimer = s("cant_timer", "Robot can't stop a timer in your clock app. Open the clock to stop it.",
        "Robot ne peut pas arrêter une minuterie dans ton appli d'horloge. Ouvre l'horloge pour l'arrêter.")
    val cantSent = s("cant_sent", "Robot can't take back a message or a call.", "Robot ne peut pas reprendre un message ou un appel.")
    val cantElsewhere = s("cant_elsewhere", "That's in another app now, so Robot can't undo it from here.",
        "C'est dans une autre appli maintenant, alors Robot ne peut pas l'annuler d'ici.")
    val cantChange = s("cant_change", "Robot can only change the time of an alarm or an event it just set.",
        "Robot peut seulement changer l'heure d'une alarme ou d'un événement qu'il vient de créer.")
    val unlockUndo = s("unlock_undo", "Unlock your phone to undo that.", "Déverrouille ton téléphone pour annuler ça.")
    val unlockChange = s("unlock_change", "Unlock your phone to change that.", "Déverrouille ton téléphone pour changer ça.")
    val changedSince = s("changed_since", "That was changed since, so Robot left it as it is.",
        "Ça a été modifié depuis, alors Robot l'a laissé tel quel.")
    val goneSince = s("gone_since", "That's already gone, so Robot left it.", "C'est déjà supprimé, alors Robot n'a touché à rien.")
    // review Low J: no reason given (no next alarm, or an earlier one is next): only that Robot couldn't check.
    val offNotSent = s("off_not_sent", "Robot couldn't check the {time} alarm, so it left your alarms as they are. Check the alarms in your clock app.",
        "Robot n'a pas pu vérifier l'alarme de {time}, alors il n'a touché à aucune alarme. Vérifie les alarmes dans ton appli d'horloge.")
    // fix-alarm-moved: the clock left it on; its alarms page is where it goes off.
    val offStillOn = s("off_still_on", "Your clock app didn't turn off the {time} alarm. Turn it off in your clock app's alarms.",
        "Ton appli d'horloge n'a pas désactivé l'alarme de {time}. Désactive-la dans les alarmes de ton appli d'horloge.")
    // fix-batch-15 M1: the old one went, but the new one isn't Android's next alarm and nothing rings before it.
    val moveNotSeen = s("move_not_seen", "The {from} alarm is off, but Robot can't see a {to} alarm. Check the alarms page your clock app opened.",
        "L'alarme de {from} est désactivée, mais Robot ne voit pas d'alarme à {to}. Vérifie la page des alarmes que ton appli d'horloge a ouverte.")
    val moveLeftOn = s("move_left_on", "Robot couldn't check the {from} alarm, so it left it on and didn't set {to}. Check the alarms in your clock app.",
        "Robot n'a pas pu vérifier l'alarme de {from}, alors il l'a laissée activée et n'a pas mis d'alarme à {to}. Vérifie les alarmes dans ton appli d'horloge.")
    val moveStillOn = s("move_still_on", "The {from} alarm is still on, so Robot didn't set {to}. Check the alarms in your clock app.",
        "L'alarme de {from} est encore activée, alors Robot n'a pas mis d'alarme à {to}. Vérifie les alarmes dans ton appli d'horloge.")
    val moveOffOnly = s("move_off_only", "Robot turned off the {from} alarm, but your clock app didn't set {to}. Set it in your clock app.",
        "Robot a désactivé l'alarme de {from}, mais ton appli d'horloge n'a pas mis d'alarme à {to}. Crée-la dans ton appli d'horloge.")
    // review-e Low: a stop says it stopped, not that Robot couldn't check or the clock didn't set. review-f Low: and not who
    // stopped it, as the Stop button, "stop", the wake word, the panel closing, a new conversation or a lock starting all do.
    val offStopped = s("off_stopped", "Robot was stopped, so it didn't turn off the {time} alarm.",
        "Robot a été arrêté, alors il n'a pas désactivé l'alarme de {time}.")
    val moveStopped = s("move_stopped", "Robot was stopped, so it didn't turn off the {from} alarm or set {to}.",
        "Robot a été arrêté, alors il n'a pas désactivé l'alarme de {from} ni mis d'alarme à {to}.")
    val moveOffStopped = s("move_off_stopped", "Robot turned off the {from} alarm, but it was stopped before it set {to}. Set it in your clock app.",
        "Robot a désactivé l'alarme de {from}, mais il a été arrêté avant de mettre une alarme à {to}. Crée-la dans ton appli d'horloge.")
    val alarmChanged = s("alarm_changed", "Changed it to {time}.", "C'est changé pour {time}.")
    val eventUndone = s("event_undone", "Removed that event from your calendar.", "Événement retiré de ton agenda.")
    val eventMoved = s("event_moved", "Moved it to {time}.", "Déplacé à {time}.")
    val eventMovedDayBefore = s("event_moved_day_before", "Moved it to {time} the day before.", "Déplacé à {time} la veille.")
    val eventMovedNextDay = s("event_moved_next_day", "Moved it to {time} the next day.", "Déplacé à {time} le lendemain.")
    val noteUndone = s("note_undone", "Removed that note.", "Note supprimée.")
    val cantKept = s("cant_kept", "Robot can't undo that one from here.", "Robot ne peut pas annuler ça d'ici.")
    val cantAlarmShared = s("cant_alarm_shared", "You already had an alarm at that time, so Robot can't tell which one to turn off. Check the alarms in your clock app.",
        "Tu avais déjà une alarme à cette heure-là, alors Robot ne peut pas savoir laquelle désactiver. Vérifie les alarmes dans ton appli d'horloge.")
    val moveShared = s("move_shared", "You already had an alarm at that time, so Robot left it on and didn't set {to}. Check the alarms in your clock app.",
        "Tu avais déjà une alarme à cette heure-là, alors Robot l'a laissée activée et n'a pas mis d'alarme à {to}. Vérifie les alarmes dans ton appli d'horloge.")
    val undone = s("undone", "Undone.", "Annulé.")
    val undoAsked = s("undo_asked", "Robot asked to undo it.", "Robot a demandé de l'annuler.")
    val failed = s("failed", "Robot couldn't undo that.", "Robot n'a pas pu annuler ça.")
}
