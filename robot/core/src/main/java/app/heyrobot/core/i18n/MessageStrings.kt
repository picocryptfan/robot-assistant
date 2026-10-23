// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP3's catalog: messages: messages/* (reading, replies, summaries) and what the panel says about them
// (assistant/PanelMessages.kt). Ids start with "msg."; the area is "messages". Québec French (tu), the
// glossary's words (robot/l10n/glossary.tsv); render() adds the French spaces. A message's words, a sender's name and an
// app's name are arguments, never translated.

object MessageStrings : Strings("msg", area = "messages") {
    /** [cardLine]'s English, a constant: Safety.kt's TAP_MESSAGE is one. */
    const val CARD_LINE_EN = "A message is in this conversation: check this, then tap."

    // Reading (MessageReader, MessageReading): the count, a conversation's intro, its messages, the prompt after it.
    val nothing = s("nothing", "No new messages.", "Aucun nouveau message.")
    val noneFrom = s("none_from", "No message from {who} right now.", "Aucun message de {who} en ce moment.")
    val done = s("done", "That's all.", "C'est tout.")
    val stopped = s("stopped", "OK.", "D'accord.")
    val noneRead = s("none_read", "I haven't read you a message in this conversation.", "Je n'ai lu aucun message dans cette conversation.")
    val noApps = s("no_apps", "No app is turned on in {page}.", "Aucune appli n'est choisie dans {page}.")
    val noAccess = s("no_access", "Robot doesn't have notification access. {page} shows how to allow it.",
        "Robot n'a pas accès aux notifications. {page} montre comment l'autoriser.")
    val replyOrStop = s("reply_or_stop", "Reply, or stop?", "Répondre ou arrêter ?")
    val replyNextOrStop = s("reply_next_or_stop", "Reply, next, or stop?", "Répondre, suivant ou arrêter ?")
    val unlockToHear = s("unlock_to_hear", "Unlock your phone to hear them.", "Déverrouille le téléphone pour les entendre.")
    val keptNames = s("kept_names", "Robot named who messaged you ({n} conversations)", "Robot a nommé qui t'a écrit ({n} conversations)")
    val count = plural("count", "n",
        Plural(one = "{n} conversation: {names}.", other = "{n} conversations: {names}."),
        Plural(one = "{n} conversation : {names}.", other = "{n} conversations : {names}."))
    val intro = plural("intro", "n",
        Plural(one = "{name}, in {appName}, {n} message:", other = "{name}, in {appName}, {n} messages:"),
        Plural(one = "{name}, dans {appName}, {n} message :", other = "{name}, dans {appName}, {n} messages :"))
    val introGroup = plural("intro_group", "n",
        Plural(one = "{name}, a group in {appName}, {n} message:", other = "{name}, a group in {appName}, {n} messages:"),
        Plural(one = "{name}, un groupe dans {appName}, {n} message :", other = "{name}, un groupe dans {appName}, {n} messages :"))
    val earlier = s("earlier", "The newest {shown}, and {more} earlier.", "Les {shown} plus récents, et {more} plus anciens.")
    val sent = s("sent", "{who} sent {what}.", "{who} a envoyé {what}.")
    val withAttachment = s("with_attachment", "{text} (with {what})", "{text} (avec {what})")
    val groupLine = s("group_line", "{who}: {line}", "{who} : {line}")
    val photo = s("photo", "a photo", "une photo")
    val video = s("video", "a video", "une vidéo")
    val voiceMessage = s("voice_message", "a voice message", "un message vocal")
    val file = s("file", "a file", "un fichier")
    val hiddenCode = s("hidden_code", "This one looks like a code; Android hides it from Robot. Open the app to see it.",
        "Celui-ci ressemble à un code : Android le cache à Robot. Ouvre l'appli pour le voir.")
    val hiddenSignal = s("hidden_signal",
        "{appName} only shows “New message” to other apps. To let Robot read it, set {appName} › Settings › Notifications › Show to “Name and message”.",
        "{appName} ne montre que « Nouveau message » aux autres applis. Pour que Robot le lise, règle {appName} › Paramètres › Notifications › Afficher sur « Nom et message ».")
    val hiddenApp = s("hidden_app", "{appName} hides this message from other apps.", "{appName} cache ce message aux autres applis.")
    val wroteIn = s("wrote_in", "{who} wrote in {lang}. In English:", "{who} a écrit en {lang}. En français :")
    val wroteOther = s("wrote_other", "{who} wrote in another language. In English:", "{who} a écrit dans une autre langue. En français :")
    val french = s("french", "French", "français")
    val english = s("english", "English", "anglais")
    val appMessaging = s("app_messaging", "Messaging (SMS)", "Messagerie (SMS)")

    // Summaries (MessageSummaryText, MessageSummaries).
    val summaryLabel = s("summary_label", "Robot's summary, made on this phone.", "Résumé de Robot, fait sur ce téléphone.")
    val notFoundIn = s("not_found_in", "Robot couldn't find “{text}” in the messages.", "Robot n'a pas trouvé « {text} » dans les messages.")
    val summaryKept = plural("summary_kept", "n",
        Plural(one = "Summary of {n} conversation", other = "Summary of {n} conversations"),
        Plural(one = "Résumé de {n} conversation", other = "Résumé de {n} conversations"))

    // What History keeps instead of a message's words (never the words).
    val readFrom = plural("read_from", "n",
        Plural(one = "Robot read {n} message from {name} ({appName})", other = "Robot read {n} messages from {name} ({appName})"),
        Plural(one = "Robot a lu {n} message de {name} ({appName})", other = "Robot a lu {n} messages de {name} ({appName})"))
    val readFromSeveral = plural("read_from_several", "n",
        Plural(one = "Robot read {n} message from {count} conversations", other = "Robot read {n} messages from {count} conversations"),
        Plural(one = "Robot a lu {n} message de {count} conversations", other = "Robot a lu {n} messages de {count} conversations"))
    val keptCard = s("kept_card", "A reply to {name}", "Une réponse à {name}")
    val keptSent = s("kept_sent", "Sent your reply to {name} ({appName})", "Ta réponse à {name} envoyée ({appName})")
    val keptCancelled = s("kept_cancelled", "Reply to {name} cancelled", "Réponse à {name} annulée")
    val keptOpened = s("kept_opened", "Opened a draft to {name} ({appName})", "Brouillon pour {name} ouvert ({appName})")
    val notKept = s("not_kept", "Messages Robot read you (not kept)", "Messages que Robot t'a lus (pas gardés)")
    val answerNotKept = s("answer_not_kept", "Robot's answer isn't kept: this conversation holds your messages.",
        "La réponse de Robot n'est pas gardée : cette conversation contient tes messages.")
    val heardBack = s("heard_back", "(A message's words, heard back while Robot read it: not kept.)",
        "(Les mots d'un message, réentendus pendant que Robot le lisait : pas gardés.)")

    // A reply's card (ReplyPlanner) and its notes.
    val replyTo = s("reply_to", "Reply to **{to}** · {appName}", "Répondre à **{to}** · {appName}")
    val replyToGroup = s("reply_to_group", "Reply to the group **{to}** · {appName}", "Répondre au groupe **{to}** · {appName}")
    val replyQuote = s("reply_quote", "«{text}»", "« {text} »")
    /** The reply card's tick box: "Send in French: «…»", in the menus' language. */
    val replyTick = s("reply_tick", "{label}: «{text}»", "{label} : « {text} »")
    val spokenSendIt = s("spoken_send_it", "Reply to {to}: {said} Send it?", "Répondre à {to} : {said} Je l'envoie ?")
    val spokenTapSend = s("spoken_tap_send", "Reply to {to}: {said} Tap {send} to send it.", "Répondre à {to} : {said} Touche {send} pour l'envoyer.")
    val noteGroup = s("note_group", "Everyone in the group will see it. Tap {send} to send it.", "Tout le groupe le verra. Touche {send} pour l'envoyer.")
    val noteEmoji = s("note_emoji", "This app sends an emoji alone as a reaction.", "Cette appli envoie un émoji seul comme réaction.")
    val noteSms = s("note_sms", "This is an SMS: carrier charges may apply.", "C'est un texto : des frais de l'opérateur peuvent s'appliquer.")
    val noteMarksRead = s("note_marks_read", "This app marks the chat read and may send a read receipt.",
        "Cette appli marque la conversation comme lue et peut envoyer un accusé de lecture.")
    val noteUnverified = s("note_unverified", "Robot hasn't checked yet how this app sends a reply.",
        "Robot n'a pas encore vérifié comment cette appli envoie une réponse.")

    // The quarantine (MessageQuarantine): a message's words never become an action.
    val cardLine = s("card_line", CARD_LINE_EN, "Un message fait partie de cette conversation : vérifie, puis touche.")
    val notFromMessages = s("not_from_messages", "I don't act on what a message says.", "Je n'agis pas selon ce que dit un message.")
    val sayYesAgain = s("say_yes_again", "Tap {send}, or say yes again.", "Touche {send}, ou redis oui.")
    val notRemembered = s("not_remembered",
        "A message is in this conversation, so Robot keeps nothing the model proposes to remember. To keep something, say “remember that …” yourself: a card shows the words, and only a tap keeps them.",
        "Un message fait partie de cette conversation : Robot ne garde rien de ce que le modèle propose de retenir. Pour garder quelque chose, dis toi-même « souviens-toi que … » : une carte montre les mots, et seule ta touche les garde.")
    val noSecretHere = s("no_secret_here", "A message is in this conversation, so Robot doesn't keep a code or a password from it. Add it in {page} instead.",
        "Un message fait partie de cette conversation : Robot n'y garde ni code ni mot de passe. Ajoute-le plutôt dans {page}.")
    val checkAndTap = s("check_and_tap", "Check the words on the card, then tap {remember} to keep them.",
        "Vérifie les mots sur la carte, puis touche {remember} pour les garder.")

    // Settings › Messages' choices (core MessageSettings), the lock screen and the off switch (MessageAccess).
    val keepUntilGone = s("keep_until_gone", "Until the notification goes away", "Jusqu'à ce que la notification disparaisse")
    val keepHour = s("keep_hour", "1 hour", "1 heure")
    val keepDay = s("keep_day", "24 hours", "24 heures")
    val routeSend = s("route_send", "Robot sends after you confirm", "Robot envoie après ta confirmation")
    val routeOpen = s("route_open", "Open the chat with my reply; I press Send", "Ouvrir la conversation avec ma réponse; je touche Envoyer")
    val afterLeave = s("after_leave", "Leave it", "La laisser")
    val afterClear = s("after_clear", "Clear the notification", "Effacer la notification")
    val afterMarkRead = s("after_mark_read", "Mark as read", "Marquer comme lu")
    val lockedOff = s("locked_off", "Off", "Désactivé")
    val lockedWho = s("locked_who", "Who messaged", "Qui a écrit")
    val lockedRead = s("locked_read", "Read and reply", "Lire et répondre")
    val unlockFirst = s("unlock_first", "Unlock first: your messages stay private while the phone is locked.",
        "Déverrouille d'abord : tes messages restent privés quand le téléphone est verrouillé.")
    val off = s("off", "Reading messages is off. Turn it on in {page}.", "La lecture des messages est désactivée. Active-la dans {page}.")

    // Settings' Messages row: "Off", "On · Cheogram, Signal", "On · no app yet".
    val summaryOff = s("summary_off", "Off", "Désactivé")
    val summaryOn = s("summary_on", "On · {appNames}", "Activé · {appNames}")
    val summaryOnNone = s("summary_on_none", "On · no app yet", "Activé · aucune appli pour l'instant")

    // Settings › Messages: an app whose signing key Robot no longer trusts (MessageSigners).
    val playStoreBuild = s("play_store_build", "{appName} (Play Store build)", "{appName} (version Play Store)")
    val signerChanged = s("signer_changed", "{appName} changed its signing key: turn it on again to trust it",
        "{appName} a changé de clé de signature : réactive-la pour lui faire confiance")
    val installedAfter = s("installed_after", "{appName} was installed after you turned it on: turn it on again to trust it",
        "{appName} a été installée après que tu l'as activée : réactive-la pour lui faire confiance")

    // The panel (assistant/PanelMessages.kt): what Robot says in a messages turn, and the reply card's lines.
    val couldntRead = s("couldnt_read", "Robot couldn't get to your messages. Please try again.", "Robot n'a pas pu lire tes messages. Réessaie.")
    val couldntReach = s("couldnt_reach", "Robot couldn't reach its messages. Please try again.", "Robot n'a pas pu accéder à ses messages. Réessaie.")
    val hintSummarize = s("hint_summarize", "Say “summarize” for a summary.", "Dis « résume » pour un résumé.")
    val thatConversation = s("that_conversation", "that conversation", "cette conversation")
    val markGone = s("mark_gone", "{name}'s notification isn't there any more.", "La notification de {name} n'est plus là.")
    val markedRead = s("marked_read", "Marked as read: {name} may see that you read it.", "Marqué comme lu : {name} peut voir que tu l'as lu.")
    val cleared = s("cleared", "Cleared {name}'s notification; the chat stays unread in the app.",
        "Notification de {name} effacée; la conversation reste non lue dans l'appli.")
    val summaryFailed = s("summary_failed", "Robot's model couldn't summarize them right now. Say “read my messages” to hear them.",
        "Le modèle de Robot n'a pas pu les résumer pour l'instant. Dis « lis mes messages » pour les entendre.")
    val alreadyYours = s("already_yours", "{name}'s message is already in English, or Robot couldn't translate it.",
        "Le message de {name} est déjà en français, ou Robot n'a pas pu le traduire.")
    val noMessageTo = s("no_message_to", "No message from {name} right now. To write to them, say “text {name} …”.",
        "Aucun message de {name} en ce moment. Pour lui écrire, dis « texte à {name} … ».")
    val severalFit = s("several_fit", "Several conversations fit. Tap the one you mean.", "Plusieurs conversations correspondent. Touche celle que tu veux.")
    val replyToWhom = s("reply_to_whom", "Reply to whom?", "Répondre à qui ?")
    val draftFailed = s("draft_failed", "Robot couldn't draft a reply. Say what it should say: “reply …”.",
        "Robot n'a pas pu écrire de réponse. Dis ce qu'elle doit dire : « réponds … ».")
    val replyEmpty = s("reply_empty", "What should the reply say?", "Que doit dire la réponse ?")
    val replyTooLong = s("reply_too_long", "That reply is too long ({max} characters at most).", "Cette réponse est trop longue ({max} caractères au plus).")
    val authRequired = s("auth_required", "Unlock first: this app needs the phone unlocked to reply.",
        "Déverrouille d'abord : cette appli demande le téléphone déverrouillé pour répondre.")
    val headline = s("headline", "Reply to {name} · {appName}", "Répondre à {name} · {appName}")
    val headlineGroup = s("headline_group", "Reply to the group {name} · {appName}", "Répondre au groupe {name} · {appName}")
    val headlineSaid = s("headline_said", "Reply to {name} · {appName}.", "Répondre à {name} · {appName}.")
    val headlineGroupSaid = s("headline_group_said", "Reply to the group {name} · {appName}.", "Répondre au groupe {name} · {appName}.")
    val drafted = s("drafted", "Robot drafted this. Check it, then tap {send}.", "Robot a écrit ce brouillon. Vérifie-le, puis touche {send}.")
    val tapOrYes = s("tap_or_yes", "Tap {send}, or say yes.", "Touche {send}, ou dis oui.")
    val tapSend = s("tap_send", "Tap {send} to send it.", "Touche {send} pour l'envoyer.")
    val tapSendOnCard = s("tap_send_on_card", "Tap {send} on the card to send it.", "Touche {send} sur la carte pour l'envoyer.")
    val sendIn = s("send_in", "Send in {lang}", "Envoyer en {lang}")
    val alwaysFor = s("always_for", "Always for {name}", "Toujours pour {name}")
    val replyToHead = s("reply_to_head", "Reply to {name}:", "Répondre à {name} :")
    val sendIt = s("send_it", "Send it?", "Je l'envoie ?")
    val sendingIn = s("sending_in", "Sending in 3 seconds. Say stop or tap {stop}.", "Envoi dans 3 secondes. Dis « arrête » ou touche {stop}.")
    val sendingTo = s("sending_to", "Sending to {name}:", "J'envoie à {name} :")
    val stoppedNothingSent = s("stopped_nothing_sent", "Stopped. Nothing was sent; tap {send} to send it anyway.",
        "Arrêté. Rien n'est envoyé; touche {send} pour l'envoyer quand même.")
    val cancelledNothingSent = s("cancelled_nothing_sent", "Cancelled. Nothing was sent.", "Annulé. Rien n'a été envoyé.")
    val sending = s("sending", "Sending…", "Envoi…")
    val unknownSent = s("unknown_sent", "Robot can't tell whether it went. Check the chat before sending it again.",
        "Robot ne sait pas si c'est parti. Regarde la conversation avant de renvoyer.")
    val sentIn = s("sent_in", "Sent in {appName}.", "Envoyé dans {appName}.")
    val sentShort = s("sent_short", "Sent.", "Envoyé.")
    val wroteAgainLine = s("wrote_again_line", "{name} wrote again: «{said}». Still send?", "{name} a écrit de nouveau : « {said} ». L'envoyer quand même ?")
    val wroteAgain = s("wrote_again", "{name} wrote again:", "{name} a écrit de nouveau :")
    val tapSendAnyway = s("tap_send_anyway", "Tap {send} to send it anyway.", "Touche {send} pour l'envoyer quand même.")
    val goneOpenChat = s("gone_open_chat", "{name}'s notification is gone. Open the chat with your reply?",
        "La notification de {name} n'est plus là. Ouvrir la conversation avec ta réponse ?")
    val unlockThenSend = s("unlock_then_send", "Unlock your phone, then tap {send}.", "Déverrouille le téléphone, puis touche {send}.")
    val notSentOpenChat = s("not_sent_open_chat", "Didn't send. Open the chat with your reply?", "Pas envoyé. Ouvrir la conversation avec ta réponse ?")
    val openChatButton = s("open_chat_button", "Open chat", "Ouvrir la conversation")
    val unlockThenOpen = s("unlock_then_open", "Unlock your phone, then tap {open}.", "Déverrouille le téléphone, puis touche {open}.")
    val chatDidntOpen = s("chat_didnt_open", "The chat didn't open in {appName}.", "La conversation ne s'est pas ouverte dans {appName}.")
    val draftOpened = s("draft_opened", "Opened a draft: nothing is sent until you press {send} there.",
        "Brouillon ouvert : rien n'est envoyé tant que tu ne touches pas {send} là-bas.")
}
