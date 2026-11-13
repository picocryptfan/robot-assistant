// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// The logbook's catalog (docs/plans/spoken-log-plan.md): what Robot says when you log something and ask about it, with
// your words, counts and times in it. A screen's fixed words are in values/strings_logbook.xml. Ids start with "logbook.";
// Québec French (tu), « carnet » for the logbook. render() adds the French spaces.

object LogbookStrings : Strings("logbook", area = "logbook") {
    val logged = s("logged", "Logged: {what}, {when}.", "C'est inscrit : {what}, {when}.")
    val loggedLocked = s("logged_locked", "Logged: {what}, {when}. It joins your logbook when you unlock.",
        "C'est inscrit : {what}, {when}. Ça rejoint ton carnet quand tu déverrouilles.")
    val last = s("last", "Last logged: {what}, {when}.", "Dernière inscription : {what}, {when}.")
    val noneFound = s("none_found", "Nothing in your logbook matches {what}.", "Rien dans ton carnet ne correspond à {what}.")
    val empty = s("empty", "Your logbook is empty. Start with: log that I took my pills.", "Ton carnet est vide. Commence par : inscris que j'ai pris mes pilules.")
    val count = plural("count", "n", Plural("{n} time {period}.", "{n} times {period}."), Plural("{n} fois {period}.", "{n} fois {period}."))
    val total = plural("total", "n", Plural("{x} {unit} {period}, from {n} entry.", "{x} {unit} {period}, from {n} entries."),
        Plural("{x} {unit} {period}, sur {n} inscription.", "{x} {unit} {period}, sur {n} inscriptions."))
    val recent = s("recent", "Newest in your logbook: {list}.", "Les plus récentes dans ton carnet : {list}.")
    val item = s("item", "{what}, {when}", "{what}, {when}")
    val deleted = s("deleted", "Deleted from your logbook: {what}.", "Effacé de ton carnet : {what}.")
    val deleteAsk = s("delete_ask", "Delete the log entry {what}, {when}?", "Effacer l'inscription {what}, {when} ?")
    val deleteTitle = s("delete_title", "Delete the log entry: {what}, {when}", "Effacer l'inscription : {what}, {when}")
    val deleteFailed = s("delete_failed", "Robot couldn't delete that entry.", "Robot n'a pas pu effacer cette inscription.")
    val restored = s("restored", "The entry is back in your logbook.", "L'inscription est de retour dans ton carnet.")
    val notRestored = s("not_restored", "Robot couldn't put the entry back.", "Robot n'a pas pu remettre l'inscription.")
    val unlockFirst = s("unlock_first", "Unlock your phone first: your logbook opens only then.", "Déverrouille ton téléphone d'abord : ton carnet s'ouvre seulement à ce moment-là.")
    val full = s("full", "Your logbook is full. Delete some entries on its page first.", "Ton carnet est plein. Efface d'abord des inscriptions sur sa page.")
    val notNow = s("not_now", "Robot can't open your logbook right now. Try again in a moment.", "Robot ne peut pas ouvrir ton carnet en ce moment. Réessaie dans un instant.")
    val failed = s("failed", "That wasn't logged. Try again in a moment.", "Ce n'est pas inscrit. Réessaie dans un instant.")
    val today = s("today", "today", "aujourd'hui")
    val yesterday = s("yesterday", "yesterday", "hier")
    val day = s("day", "{day}", "{day}")
    val dayYear = s("day_year", "{day} {year}", "{day} {year}")
    val at = s("at", "{day} at {time}", "{day} à {time}")
    val inAll = s("in_all", "in all", "en tout")
    val periodToday = s("period_today", "today", "aujourd'hui")
    val periodYesterday = s("period_yesterday", "yesterday", "hier")
    val thisWeek = s("this_week", "this week", "cette semaine")
    val lastWeek = s("last_week", "last week", "la semaine dernière")
    val thisMonth = s("this_month", "this month", "ce mois-ci")
    val lastMonth = s("last_month", "last month", "le mois dernier")
    val thisYear = s("this_year", "this year", "cette année")
    val lastYear = s("last_year", "last year", "l'année dernière")
    val inMonth = s("in_month", "in {month}", "en {month}")
}
