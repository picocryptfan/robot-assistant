// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

/** Ready answers (idea 18): the draft offered when someone in People asks what your calendar or Files knows, and its notice. */
object ReadyStrings : Strings("ready", area = "messages") {
    val fromCalendar = s("from_calendar", "From my calendar: {facts}.", "D'après mon agenda : {facts}.")
    val fromFiles = s("from_files", "From my files: {facts}.", "D'après mes fichiers : {facts}.")
    val lands = s("lands", "{what}, lands {when}", "{what}, atterrit {when}")
    val leaves = s("leaves", "{what}, leaves {when}", "{what}, part {when}")
    val at = s("at", "{what}, {when}", "{what}, {when}")
    val dated = s("dated", "{what}, dated {date}", "{what}, daté du {date}")
    val todayAt = s("today_at", "at {time}", "à {time}")
    val tomorrowAt = s("tomorrow_at", "tomorrow at {time}", "demain à {time}")
    val readCalendar = s("read_calendar", "Read from your calendar at {time}.", "Lu dans ton agenda à {time}.")
    val readFiles = s("read_files", "Read from your Files at {time}.", "Lu dans tes Fichiers à {time}.")
    val asks = s("asks", "{who} asks: {question}", "{who} demande : {question}")
    val send = s("send", "Send", "Envoyer")
    val edit = s("edit", "Edit", "Modifier")
    val channel = s("channel", "Ready answers", "Réponses prêtes")
    val notSent = s("not_sent", "Not sent: the message has gone from {app}. Edit opens the chat with the answer.",
        "Pas envoyé : le message n'est plus dans {app}. Modifier ouvre la conversation avec la réponse.")
    val notSentNew = s("not_sent_new", "Not sent: {who} wrote again. Edit opens the chat with the answer.",
        "Pas envoyé : {who} a écrit de nouveau. Modifier ouvre la conversation avec la réponse.")
    val notSentChanged = s("not_sent_changed", "Not sent: that conversation changed. Edit opens the chat with the answer.",
        "Pas envoyé : cette conversation a changé. Modifier ouvre la conversation avec la réponse.")
    val notSentFailed = s("not_sent_failed", "Not sent: {app} didn't take the answer. Edit opens the chat with the answer.",
        "Pas envoyé : {app} n'a pas pris la réponse. Modifier ouvre la conversation avec la réponse.")
    val notSentLocked = s("not_sent_locked", "Not sent: your phone was locked.", "Pas envoyé : ton téléphone était verrouillé.")
    val notSentOff = s("not_sent_off", "Not sent: ready answers are off.", "Pas envoyé : les réponses prêtes sont désactivées.")
    val notSentReplies = s("not_sent_replies", "Not sent: your settings don't let Robot send replies. Edit opens the chat with the answer.",
        "Pas envoyé : tes réglages ne laissent pas Robot envoyer de réponses. Modifier ouvre la conversation avec la réponse.")
    val notSentExpired = s("not_sent_expired", "Not sent: that answer has expired.", "Pas envoyé : cette réponse a expiré.")
    val lockedPublic = s("locked_public", "A ready answer", "Une réponse prête")
}
