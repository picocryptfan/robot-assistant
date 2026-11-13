// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// "When did I last …" from everything on the phone (docs/plans/last-time-plan.md): the date, how long ago and where it
// came from. A calendar entry is a plan, so it "was on your calendar", never something you did. Ids start with
// "lasttime."; the area is the everyday set's; the French is Québec French (tu), from the glossary (« agenda », « reçu », « Fichiers de Robot »).

object LastTimeStrings : Strings("lasttime", area = "everyday") {
    /** {date} the day, {ago} [ago]'s phrase, {what} the receipt as Files names it. */
    val receipt = s("receipt", "Last time: {date}, {ago}. From a receipt in Files: {what}.",
        "La dernière fois : le {date}, {ago}. D'après un reçu dans les Fichiers de Robot : {what}.")
    /** {what} the event's title. */
    val calendar = s("calendar", "“{what}” was on your calendar on {date}, {ago}.",
        "« {what} » était à ton agenda le {date}, {ago}.")
    val note = s("note", "Last time: {date}, {ago}. From your notes: {what}.",
        "La dernière fois : le {date}, {ago}. D'après tes notes : {what}.")
    val logbook = s("logbook", "Last time: {date}, {ago}. From your Logbook: {what}.",
        "La dernière fois : le {date}, {ago}. D'après ton carnet : {what}.")
    /** A second match close to the newest: {rest} is its own sentence. */
    val also = s("also", "Also: {rest}", "Aussi : {rest}")
    val none = s("none", "I found nothing about that in your receipts, calendar or notes.",
        "Je n'ai rien trouvé là-dessus dans tes reçus, ton agenda ou tes notes.")
    /** As [none], when Files isn't set up. */
    val noneCalendarNotes = s("none_calendar_notes", "I found nothing about that in your calendar or notes.",
        "Je n'ai rien trouvé là-dessus dans ton agenda ou tes notes.")
    /** As [none], when Robot may not read the calendar. */
    val noneReceiptsNotes = s("none_receipts_notes", "I found nothing about that in your receipts or notes.",
        "Je n'ai rien trouvé là-dessus dans tes reçus ou tes notes.")
    val noneNotes = s("none_notes", "I found nothing about that in your notes.", "Je n'ai rien trouvé là-dessus dans tes notes.")
    /** As [none], when the notes couldn't be read (and with them what else couldn't be). */
    val noneReceiptsCalendar = s("none_receipts_calendar", "I found nothing about that in your receipts or calendar.",
        "Je n'ai rien trouvé là-dessus dans tes reçus ou ton agenda.")
    val noneReceipts = s("none_receipts", "I found nothing about that in your receipts.", "Je n'ai rien trouvé là-dessus dans tes reçus.")
    val noneCalendar = s("none_calendar", "I found nothing about that in your calendar.", "Je n'ai rien trouvé là-dessus dans ton agenda.")
    /** A source whose reading failed this time (review-last-time-b L1, L2). */
    val unreadReceipts = s("unread_receipts", "I couldn't read your receipts in Files just now.",
        "Je n'ai pas pu lire tes reçus dans les Fichiers de Robot cette fois-ci.")
    val unreadCalendar = s("unread_calendar", "I couldn't read your calendar just now.", "Je n'ai pas pu lire ton agenda cette fois-ci.")
    val unreadNotes = s("unread_notes", "I couldn't read your notes just now.", "Je n'ai pas pu lire tes notes cette fois-ci.")
    /** {path} where the permission is given (AndroidPath.APP_PERMISSIONS). */
    val calendarOff = s("calendar_off", "I couldn't look in your calendar: Robot isn't allowed to read it ({path}).",
        "Je n'ai pas pu regarder ton agenda : Robot n'a pas l'autorisation de le lire ({path}).")
    val filesOff = s("files_off", "Files isn't set up yet, so there were no receipts to look at.",
        "Les Fichiers de Robot ne sont pas encore configurés, alors je n'avais aucun reçu à regarder.")
    val locked = s("locked", "Unlock your phone first: your receipts, calendar and notes stay private while it's locked.",
        "Déverrouille ton téléphone d'abord : tes reçus, ton agenda et tes notes restent privés tant qu'il est verrouillé.")

    // How long ago.
    val today = s("today", "today", "aujourd'hui")
    val yesterday = s("yesterday", "yesterday", "hier")
    val days = plural("days", "n", Plural("{n} day ago", "{n} days ago"), Plural("il y a {n} jour", "il y a {n} jours", "il y a {n} de jours"))
    val weeks = plural("weeks", "n", Plural("{n} week ago", "{n} weeks ago"), Plural("il y a {n} semaine", "il y a {n} semaines", "il y a {n} de semaines"))
    val months = plural("months", "n", Plural("{n} month ago", "{n} months ago"), Plural("il y a {n} mois", "il y a {n} mois", "il y a {n} de mois"))
    val years = plural("years", "n", Plural("{n} year ago", "{n} years ago"), Plural("il y a {n} an", "il y a {n} ans", "il y a {n} d'ans"))
}
