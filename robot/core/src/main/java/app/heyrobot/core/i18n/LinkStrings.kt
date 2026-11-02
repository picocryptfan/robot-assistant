// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// 1.5, Robot to Robot's catalog (robot-to-robot-plan.md §4.2, §4.4, §6.4, §6.5; jobs L5, L6): the sentences core's link
// code would return as codes, worded once here, ids starting with "link.". L5's screens are the main app's and use its
// resources (values/strings_link.xml). L6: the cards (the panel, the notifications, Requests), what became of a request,
// the notices, Robot's proposal and the brief's part. L13: Québec French, glossary in l10n/glossary.tsv. {name} is always your People name for that person (core LinkNames), never one their Robot sent
// unless you haven't said who they are, and then it is shown in quotes as their claim.

object LinkStrings : Strings("link", area = "link") {
    // A card's summary, drawn by code from its typed fields; the other person's own words go in the framed box under it.
    val askFreeBusy = s("ask_fb", "{name}'s Robot asks when you're busy, {from_day} {from_time} to {to_day} {to_time}. Only busy times would go back.", "Le Robot de {name} demande tes moments occupés, de {from_day} {from_time} à {to_day} {to_time}. Seuls les moments occupés lui seraient renvoyés.")
    val askEvent = s("ask_event", "{name}'s Robot proposes a plan: {times}.", "Le Robot de {name} propose un plan : {times}.")
    val askEventChange = s("ask_event_change", "{name}'s Robot changes a plan: {times}.", "Le Robot de {name} change un plan : {times}.")
    val askSplit = s("ask_split", "{name}'s Robot asks you to split a bill.", "Le Robot de {name} te demande de partager une facture.")
    val askFile = s("ask_file", "{name}'s Robot offers you a file.", "Le Robot de {name} t\u2019offre un fichier.")
    val askFileRequest = s("ask_file_request", "{name}'s Robot asks for a file.", "Le Robot de {name} te demande un fichier.")
    val sendFile = s("send_file", "Send a file", "Envoyer un fichier")
    val sendToRobot = s("send_to_robot", "Send to a Robot", "Envoyer \u00e0 un Robot")
    val sendToWhom = s("send_to_whom", "Send {file} to whose Robot?", "Envoyer {file} \u00e0 quel Robot ?")
    val sendToNamed = s("send_to_named", "Send to {name}'s Robot", "Envoyer au Robot de {name}")
    val fileOffered = s("file_offered", "Offered to {name}'s Robot. It goes when they accept.", "Offert au Robot de {name}. Il part quand cette personne accepte.")
    val fileTooBig = s("file_too_big", "That file is too big to send to a Robot. 200 MB at most.", "Ce fichier est trop gros pour un Robot. 200 Mo au plus.")
    val fileOfferFailed = s("file_offer_failed", "Robot couldn't offer that file. Try again.", "Robot n\u2019a pas pu offrir ce fichier. R\u00e9essaie.")
    val robotSays = s("robot_says", "{name}'s Robot says {said}.", "Le Robot de {name} dit {said}.")
    val robotSaysReads = s("robot_says_reads", "{name}'s Robot says {said}; Robot reads {reads}.", "Le Robot de {name} dit {said} ; Robot lit {reads}.")
    val fromRobot = s("from_robot", "From {name}'s Robot", "Du Robot de {name}")
    val fromARobot = s("from_a_robot", "From another Robot", "D\u2019un autre Robot")
    val pickForRobot = s("pick_for_robot", "{name}'s Robot asked for a file. Open the one you want to send, then tap Send to {name}'s Robot.", "Le Robot de {name} demande un fichier. Ouvre celui que tu veux envoyer, puis touche \u00ab Envoyer au Robot de {name} \u00bb.")
    val sentAndReceived = s("sent_and_received", "Sent and received", "Envoy\u00e9s et re\u00e7us")
    val xferLine = s("xfer_line", "{what} \u00b7 {day}", "{what} \u00b7 {day}")
    val xferOffered = s("xfer_offered", "Sent, waiting for an answer", "Envoy\u00e9, en attente d\u2019une r\u00e9ponse")
    val xferSending = s("xfer_sending", "Sending", "Envoi en cours")
    val xferSent = s("xfer_sent", "Sent", "Envoy\u00e9")
    val xferNotSent = s("xfer_not_sent", "Not sent", "Pas envoy\u00e9")
    val xferComing = s("xfer_coming", "Coming in", "R\u00e9ception en cours")
    val xferWaiting = s("xfer_waiting", "Arrived, goes in Robot Files at your next unlock", "Arriv\u00e9, ira dans les Fichiers de Robot \u00e0 ton prochain d\u00e9verrouillage")
    val xferFiled = s("xfer_filed", "Received, in Robot Files", "Re\u00e7u, dans les Fichiers de Robot")
    val xferNotReceived = s("xfer_not_received", "Not received, nothing kept", "Pas re\u00e7u, rien de conserv\u00e9")
    val askForFile = s("ask_for_file", "Ask for a file", "Demander un fichier")
    val askForFileWhat = s("ask_for_file_what", "Which file?", "Quel fichier ?")
    val askedForFile = s("asked_for_file", "Asked {name}'s Robot.", "Demande envoy\u00e9e au Robot de {name}.")
    val fileArrived = s("file_arrived", "A file from {name}'s Robot is in Robot Files.", "Le Robot de {name} t\u2019a envoy\u00e9 un fichier. Il est dans les Fichiers de Robot.")
    val fileFailed = s("file_failed", "A file from {name}'s Robot didn't arrive whole, so none of it was kept.", "Un fichier du Robot de {name} n\u2019est pas arriv\u00e9 entier, alors rien n\u2019a \u00e9t\u00e9 conserv\u00e9.")
    val askContact = s("ask_contact", "{name}'s Robot sends you a contact card.", "Le Robot de {name} t’envoie une carte de contact.")
    val askEta = plural("ask_eta", "n", Plural("{name} is about {n} minute away.", "{name} is about {n} minutes away."), Plural("{name} est à environ {n} minute.", "{name} est à environ {n} minutes.", "{name} est à environ {n} de minutes."))
    val askReminder = s("ask_reminder", "{name}'s Robot offers a reminder for {day} at {time}.", "Le Robot de {name} offre un rappel pour {day} à {time}.")
    val askQuestion = s("ask_question", "{name}'s Robot asks you a question.", "Le Robot de {name} te pose une question.")
    val askFolder = s("ask_folder", "{name}'s Robot invites you to a shared folder. If you say yes, it can add files without asking each time, up to 500 files and 1 GiB, and you can add yours. Its files arrive locked and nothing opens by itself. You can leave at any time and keep your copies.",
        "Le Robot de {name} t’invite dans un dossier partagé. Si tu acceptes, il peut y ajouter des fichiers sans te demander chaque fois, jusqu’à 500 fichiers et 1 Gio, et tu peux y ajouter les tiens. Ses fichiers arrivent verrouillés et rien ne s’ouvre tout seul. Tu peux partir à tout moment et garder tes copies.")
    val addToFolder = s("add_to_folder", "Add to a shared folder", "Ajouter à un dossier partagé")
    val folderAdded = s("folder_added", "Adding it to {folder}.", "Ajout au dossier {folder} en cours.")
    val folderAddFailed = s("folder_add_failed", "That file couldn't go in the folder. A folder holds up to 500 files and 1 GiB.", "Ce fichier n’a pas pu aller dans le dossier. Un dossier contient jusqu’à 500 fichiers et 1 Gio.")
    val folderNeedsStrip = s("folder_needs_strip", "Robot can't take the location out of this file, so it stays out of the folder.", "Robot ne peut pas retirer l’emplacement de ce fichier, alors il reste hors du dossier.")
    val folderEnded = s("folder_ended", "A folder is no longer shared with {name}. Your copies stay.", "Un dossier n’est plus partagé avec {name}. Tes copies restent.")
    val askList = s("ask_list", "{name}'s Robot shares a list with you.", "Le Robot de {name} partage une liste avec toi.")
    val askOther = s("ask_other", "{name}'s Robot sent a request.", "Le Robot de {name} a envoyé une demande.")
    /** One time of a plan. */
    val time = s("time", "{day} at {time}", "{day} à {time}")
    val orTimes = s("or_times", "{a} or {b}", "{a} ou {b}")
    /** Over the framed box: whose words these are. */
    val theirWords = s("their_words", "In {name}'s words:", "Dans les mots de {name} :")

    // A card's buttons.
    val gotIt = s("got_it", "Got it", "Compris")
    val accept = s("accept", "Accept", "Accepter")
    val decline = s("decline", "Decline", "Refuser")
    val undo = s("undo", "Undo", "Annuler")
    val turnOn = s("turn_on", "Turn on", "Activer")
    val notNow = s("not_now", "Not now", "Pas maintenant")
    /** Under a card no job answers yet. */
    val onlyDecline = s("only_decline", "Robot can't answer this kind of request yet. You can decline it or leave it.", "Robot ne peut pas encore répondre à ce genre de demande. Tu peux la refuser ou la laisser là.")

    // What became of a request.
    val handledOne = s("handled_one", "Robot handled this on its own.", "Robot s’en est occupé sans te demander.")
    val answered = s("answered", "You answered.", "Tu as répondu.")
    val alreadyHave = s("already_have", "You already have them in People. Nothing was added.", "Cette personne est déjà dans tes Personnes. Rien n’a été ajouté.")
    val declined = s("declined", "You declined.", "Tu as refusé.")
    val expired = s("expired", "It expired before you answered.", "Cette demande a expiré avant ta réponse.")
    val undone = s("undone", "Undone.", "Annulé.")
    val couldntUndo = s("couldnt_undo", "Robot couldn't undo this.", "Robot n’a pas pu annuler ça.")
    val couldntAnswer = s("couldnt_answer", "Robot couldn't answer it. Try again from Requests.", "Robot n’a pas pu y répondre. Réessaie dans Demandes.")
    val sent = s("sent", "Sent", "Envoyée")
    val delivered = s("delivered", "Delivered", "Livrée")
    val waitingFor = s("waiting_for", "Waiting for {name}", "En attente de {name}")
    val answeredByThem = s("answered_by_them", "Answered", "Réponse reçue")
    val declinedByThem = s("declined_by_them", "Declined", "Refusée")
    val couldntReach = s("couldnt_reach", "Couldn't reach", "Impossible à joindre")

    // Notifications (never on the lock screen: their public version says "Robot" only).
    val channel = s("channel", "Robot to Robot", "Robot à Robot")
    val notePublic = s("note_public", "Robot", "Robot")
    val cardsFrom = plural("cards_from", "n", Plural("{name}'s Robot has {n} request for you.", "{name}'s Robot has {n} requests for you."), Plural("Le Robot de {name} a {n} demande pour toi.", "Le Robot de {name} a {n} demandes pour toi.", "Le Robot de {name} a {n} de demandes pour toi."))
    val waiting = plural("waiting", "n", Plural("Robot: {n} request waiting", "Robot: {n} requests waiting"), Plural("Robot : {n} demande en attente", "Robot : {n} demandes en attente", "Robot : {n} de demandes en attente"))
    val handled = plural("handled", "n", Plural("Robot handled {n} thing", "Robot handled {n} things"), Plural("Robot a géré {n} chose", "Robot a géré {n} choses", "Robot a géré {n} de choses"))
    val handledFrom = s("handled_from", "For {names}. Open Requests to see or undo them.", "Pour {names}. Ouvre Demandes pour les voir ou les annuler.")
    val more = plural("more", "n", Plural("{n} more from {name}", "{n} more from {name}"), Plural("{n} de plus de {name}", "{n} de plus de {name}"))
    val paired = s("paired", "Paired with {name}'s Robot", "Jumelage fait avec le Robot de {name}")
    val unpaired = s("unpaired", "{name}'s Robot unpaired", "Le Robot de {name} s’est dissocié")
    val moved = s("moved", "{name}'s Robot says it moved to a new phone. Compare your safety number before you accept it.", "Le Robot de {name} dit avoir changé de téléphone. Compare ton numéro de sécurité avant de l’accepter.")
    val lowerAsked = s("lower_asked", "{name}'s Robot asks to switch to Private.", "Le Robot de {name} demande de passer au mode privé.")
    val lowered = s("lowered", "You and {name} are Private now.", "{name} et toi êtes maintenant en mode privé.")
    val raised = s("raised", "{name}'s Robot switched your link to Anonymous.", "Le Robot de {name} a fait passer ton lien au mode anonyme.")
    val busy = s("busy", "{name}'s Robot sent too many requests: paused until {time}.", "Le Robot de {name} a envoyé trop de demandes : en pause jusqu’à {time}.")
    val gaveUp = s("gave_up", "Couldn't reach {name}'s Robot. Try again from Requests.", "Impossible de joindre le Robot de {name}. Réessaie dans Demandes.")
    val unlock = s("unlock", "Unlock your phone to see requests from other Robots.", "Déverrouille ton téléphone pour voir les demandes des autres Robots.")
    val openRequests = s("open_requests", "Open Requests", "Ouvrir Demandes")
    /** The panel's turn that holds the cards (what History keeps of it too: no request's words). */
    val inPanel = plural("in_panel", "n", Plural("{n} request from another Robot.", "{n} requests from other Robots."), Plural("{n} demande d’un autre Robot.", "{n} demandes d’autres Robots.", "{n} de demandes d’autres Robots."))
    val stays = s("stays", "It stays in Requests until you answer it.", "Elle reste dans Demandes jusqu’à ce que tu y répondes.")
    /** L7: the panel's turn that holds their Robots' answers (what History keeps of it too). */
    /** L10: ETAs and reminders their Robots' requests had handled on its own, said once. */
    val inPanelHandled = plural("in_panel_handled", "n", Plural("{n} update from another Robot.", "{n} updates from other Robots."), Plural("{n} mise à jour d’un autre Robot.", "{n} mises à jour d’autres Robots.", "{n} de mises à jour d’autres Robots."))
    val inPanelReplies = plural("in_panel_replies", "n", Plural("{n} answer from another Robot.", "{n} answers from other Robots."), Plural("{n} réponse d’un autre Robot.", "{n} réponses d’autres Robots.", "{n} de réponses d’autres Robots."))
    val requests = s("requests", "Requests", "Demandes")

    // "Robot proposes the switch" (§4.2): loosening is always your tap.
    val proposeEvents = s("propose_events", "Let Robot accept plans from {name} on its own, between {from} and {to} when you're free?", "Laisser Robot accepter sans demander les plans de {name}, entre {from} et {to}, quand tu es libre ?")
    val proposeFreeBusy = s("propose_fb", "Let Robot send {name} your busy times on its own? Never titles, places or who.", "Laisser Robot envoyer sans demander tes moments occupés à {name} ? Jamais les titres, les lieux ni les personnes.")
    val proposeEta = s("propose_eta", "Let Robot just show {name}'s arrival times?", "Laisser Robot afficher simplement les heures d’arrivée de {name} ?")
    val proposeReminders = s("propose_reminders", "Let Robot add {name}'s reminders to your brief on its own?", "Laisser Robot ajouter sans demander les rappels de {name} à ton résumé du matin ?")
    val proposeFiles = s("propose_files", "Let Robot accept files from {name} on its own, up to 50 MB?", "Laisser Robot accepter sans demander les fichiers de {name}, jusqu’à 50 Mo ?")
    val proposeSplits = s("propose_splits", "Let Robot record {name}'s splits on its own? It never pays anything.", "Laisser Robot noter sans demander les partages de {name} ? Il ne paie jamais rien.")
    val proposeShared = s("propose_shared", "Let Robot answer {name}'s requests on its own, only from what you shared with them?", "Laisser Robot répondre sans demander aux demandes de {name}, seulement à partir de ce que tu as partagé avec cette personne ?")

    // The morning brief's part (BriefItem.LINK), said only while the phone is unlocked.
    val briefWaiting = plural("brief_waiting", "n", Plural("{n} request from another Robot is waiting for you.", "{n} requests from other Robots are waiting for you."), Plural("{n} demande d’un autre Robot t’attend.", "{n} demandes d’autres Robots t’attendent.", "{n} de demandes d’autres Robots t’attendent."))
    val briefHandled = plural("brief_handled", "n", Plural("Robot handled {n} thing on its own since yesterday.", "Robot handled {n} things on its own since yesterday."), Plural("Robot a géré {n} chose sans demander depuis hier.", "Robot a géré {n} choses sans demander depuis hier.", "Robot a géré {n} de choses sans demander depuis hier."))

    // L7, free/busy and plans. A card's yes.
    val sendBusy = s("send_busy", "Send busy times", "Envoyer les moments occupés")
    val addToCalendar = s("add_to_calendar", "Add to my calendar", "Ajouter à mon agenda")
    val moveIt = s("move_it", "Move it", "Le déplacer")
    /** Under Sent: their Robot answered (the line's own words say what). */
    val replied = s("replied", "Their Robot answered.", "Son Robot a répondu.")
    /** 1.5: a shared list's other Robot left, declined or was unpaired. Your copy stays. */
    val listNoRoom = s("list_no_room", "Couldn't join the list from {name}: you already have {max} lists. Remove one, then ask them to share it again.", "Impossible de rejoindre la liste de {name} : tu as déjà {max} listes. Enlèves-en une, puis demande-lui de la partager de nouveau.")
    val listTaken = s("list_taken", "{name} removed or changed things on a shared list. You can put them back on the list's page.", "{name} a enlevé ou changé des choses dans une liste partagée. Tu peux les remettre sur la page de la liste.")
    val listFull = s("list_full", "Some changes from {name} didn't fit on a shared list: it's full here, so your copy and theirs now differ. Once there's room, they'll need to make those changes again.", "Des changements de {name} n’ont pas tenu dans une liste partagée : elle est pleine ici, alors ta copie et la sienne ne sont plus pareilles. Quand il y aura de la place, il faudra les refaire de son côté.")
    val listEnded = s("list_ended", "A list is no longer shared with {name}. Your copy stays.", "Une liste n’est plus partagée avec {name}. Ta copie reste.")

    // L7, their Robot's answers, drawn by code from the typed fields (said by :voice, shown under Sent).
    val fbBusy = s("fb_busy", "{name}'s Robot answered: busy {blocks}. Free the rest of {from_day} {from_time} to {to_day} {to_time}.", "Le Robot de {name} a répondu : moments occupés, {blocks}. Libre le reste du temps, de {from_day} {from_time} à {to_day} {to_time}.")
    /** More blocks than a card says: how many are left out, and never "free the rest". */
    val fbBusyMore = plural("fb_busy_more", "n", Plural("{name}'s Robot answered: busy {blocks}, and {n} more time between {from_day} {from_time} and {to_day} {to_time}.", "{name}'s Robot answered: busy {blocks}, and {n} more times between {from_day} {from_time} and {to_day} {to_time}."), Plural("Le Robot de {name} a répondu : moments occupés, {blocks}, et {n} autre moment entre {from_day} {from_time} et {to_day} {to_time}.", "Le Robot de {name} a répondu : moments occupés, {blocks}, et {n} autres moments entre {from_day} {from_time} et {to_day} {to_time}.", "Le Robot de {name} a répondu : moments occupés, {blocks}, et {n} d’autres moments entre {from_day} {from_time} et {to_day} {to_time}."))
    val fbFree = s("fb_free", "{name}'s Robot answered: free the whole time, {from_day} {from_time} to {to_day} {to_time}.", "Le Robot de {name} a répondu : libre tout le temps, de {from_day} {from_time} à {to_day} {to_time}.")
    /** One busy block: "Fri 24 Oct 18:00 to 19:30". */
    val block = s("block", "{day} {from} to {to}", "{day} de {from} à {to}")
    /** A busy block that ends on a later day: "Fri 24 Oct 18:00 to Sun 26 Oct 09:00". */
    val blockDays = s("block_days", "{day} {from} to {to_day} {to}", "de {day} {from} à {to_day} {to}")
    val evAccepted = s("ev_accepted", "{name}'s Robot accepted your plan for {day} at {time}.", "Le Robot de {name} a accepté ton plan pour {day} à {time}.")
    val evDeclined = s("ev_declined", "{name}'s Robot declined your plan.", "Le Robot de {name} a refusé ton plan.")
    val evCancelled = s("ev_cancelled", "{name}'s Robot cancelled a plan with you. If Robot had added it to your calendar, it's off now.", "Le Robot de {name} a annulé un plan avec toi. Si Robot l’avait ajouté à ton agenda, il n’y est plus.")
    val answeredNote = s("answered_note", "{name}'s Robot answered", "Le Robot de {name} a répondu")

    // L7, what you send (ask_robot's card says exactly what goes).
    val toFreeBusy = s("to_fb", "To {name}'s Robot: are you free {from_day} {from_time} to {to_day} {to_time}? Only busy times come back.", "Au Robot de {name} : es-tu libre de {from_day} {from_time} à {to_day} {to_time} ? Seuls les moments occupés reviennent.")
    val toPlan = s("to_plan", "To {name}'s Robot: “{title}”, {times}.", "Au Robot de {name} : « {title} », {times}.")
    val toCancel = s("to_cancel", "To {name}'s Robot: cancel “{title}”, {day} at {time}.", "Au Robot de {name} : annuler « {title} », {day} à {time}.")
    val askRobot = s("ask_robot", "Ask {name}'s Robot", "Demander au Robot de {name}")
    val sentTo = s("sent_to", "Sent to {name}'s Robot. Robot tells you when it answers.", "Envoyé au Robot de {name}. Robot te le dit quand il répond.")
    val cancelledPlan = s("cancelled_plan", "Cancelled, and {name}'s Robot is told. It's off your calendar.", "Annulé, et le Robot de {name} est avisé. Ce n’est plus dans ton agenda.")
    /** A plan's title when your words gave none. */
    val planTitle = s("plan_title", "Plan", "Plan")

    // L7, why something couldn't go.
    val linkOff = s("link_off", "Robot to Robot is off. Turn it on in Settings › Robot to Robot.", "Robot à Robot est désactivé. Active-le dans Réglages › Robot à Robot.")
    val notPaired = s("not_paired", "You're not paired with {name}'s Robot. Pair in Settings › Robot to Robot.", "Tu n’as pas de jumelage avec le Robot de {name}. Fais-en un dans Réglages › Robot à Robot.")
    val notPairedOff = s("not_paired_off", "Robot to Robot is off, so you're not paired with {name}'s Robot right now. Turn it on and pair in Settings › Robot to Robot.",
        "Robot à Robot est désactivé, alors tu n’as pas de jumelage avec le Robot de {name} pour l’instant. Active-le et fais le jumelage dans Réglages › Robot à Robot.")
    val whoRobot = s("who_robot", "Whose Robot? Say a name, like “ask Sam's Robot when Sam is free Friday”.", "Le Robot de qui ? Dis un nom, comme « demande au robot de Sam quand Sam est libre vendredi ».")
    val whichOne = s("which_one", "More than one person is called {name}. Say their full name.", "Plus d’une personne s’appelle {name}. Dis son nom complet.")
    val unlockToAsk = s("unlock_to_ask", "Unlock your phone to ask {name}'s Robot.", "Déverrouille ton téléphone pour demander au Robot de {name}.")
    val unlockAny = s("unlock_any", "Unlock your phone to ask another Robot.", "Déverrouille ton téléphone pour demander à un autre Robot.")
    val phoneLocked = s("phone_locked", "Not during a phone lock.", "Pas pendant un blocage du téléphone.")
    val askWhen = s("ask_when", "When? Say a day, like Friday evening.", "Quand ? Dis un jour, comme vendredi soir.")
    val whenPassed = s("when_passed", "That time has passed.", "Ce moment est passé.")
    val planTimes = s("plan_times", "When? Say up to 3 times, like Friday at 7 or Saturday at 6.", "Quand ? Dis jusqu’à 3 moments, comme vendredi à 19 h ou samedi à 18 h.")
    val planTooMany = s("plan_too_many", "A plan can offer up to 3 times.", "Un plan peut offrir jusqu’à 3 moments.")
    val noPlan = s("no_plan", "You have no plan with {name} to cancel.", "Tu n’as aucun plan avec {name} à annuler.")
    val noSuchPlan = s("no_such_plan", "You have no plan like that with {name}, so nothing was cancelled.", "Tu n’as aucun plan de ce genre avec {name}, alors rien n’a été annulé.")
    val theyCantRead = s("they_cant_read", "{name}'s Robot can't read this kind of request yet.", "Le Robot de {name} ne peut pas encore lire ce genre de demande.")
    val couldntSend = s("couldnt_send", "Couldn't send it to {name}'s Robot. Try again.", "Impossible d’envoyer ça au Robot de {name}. Réessaie.")
    val notYet = s("not_yet", "Robot can ask another Robot when someone is free, propose or cancel a plan, say how far away you are, offer a reminder, send a contact card, ask a question, send a file or share a list or folder. Other requests come later.",
        "Robot peut demander à un autre Robot quand quelqu'un est libre, proposer ou annuler un plan, dire à combien de minutes tu es, offrir un rappel, envoyer une carte de contact, poser une question, envoyer un fichier ou partager une liste ou un dossier. Les autres demandes viendront plus tard.")
    val stoppedAlone = s("stopped_alone", "Robot won't answer {name}'s Robot on its own anymore. It asks you first.", "Robot ne répond plus de lui-même au Robot de {name}. Il te demande d’abord.")
    val noneAlone = s("none_alone", "Robot doesn't answer {name}'s Robot on its own.", "Robot ne répond pas de lui-même au Robot de {name}.")

    // L10: reminders for someone, contact cards, ETA, delegated questions, translation (English and Québec French).
    val addReminder = s("add_reminder", "Add to my brief", "Ajouter à mon résumé du matin")
    val addContact = s("add_contact", "Add to People", "Ajouter à Personnes")
    val etaHow = s("eta_how", "How far away? Say it in minutes, like “tell Sam I'm 10 minutes away”.", "À combien de minutes ? Dis par exemple « dis à Sam que je suis à 10 minutes ».")
    val remWhat = s("rem_what", "Remind them of what? Say it like “remind Sam to bring the charger”.", "Rappeler quoi ? Dis par exemple « rappelle à Sam d'apporter le chargeur ».")
    val remTooLong = s("rem_too_long", "A reminder can be up to 120 characters.", "Un rappel peut avoir jusqu'à 120 caractères.")
    val qWhat = s("q_what", "What should I ask? Say it like “ask Sam's Robot where the party is”.", "Je demande quoi ? Dis par exemple « demande au robot de Sam où est la fête ».")
    val qTooLong = s("q_too_long", "A question can be up to 200 characters.", "Une question peut avoir jusqu'à 200 caractères.")
    val whichContact = s("which_contact", "Whose contact card? Say a name from People.", "La carte de contact de qui ? Dis un nom de Personnes.")
    val toEta = plural("to_eta", "n", Plural("To {name}'s Robot: you're about {n} minute away.", "To {name}'s Robot: you're about {n} minutes away."),
        Plural("Au Robot de {name} : tu es à environ {n} minute.", "Au Robot de {name} : tu es à environ {n} minutes."))
    val toEtaPlace = plural("to_eta_place", "n", Plural("To {name}'s Robot: you're about {n} minute from {place}.", "To {name}'s Robot: you're about {n} minutes from {place}."),
        Plural("Au Robot de {name} : tu es à environ {n} minute de {place}.", "Au Robot de {name} : tu es à environ {n} minutes de {place}."))
    val toReminder = s("to_reminder", "To {name}'s Robot: a reminder for {day} at {time}, “{text}”, for their brief.", "Au Robot de {name} : un rappel pour {day} à {time}, « {text} », pour son résumé du matin.")
    val toQuestion = s("to_question", "To {name}'s Robot: “{q}” The answer comes from {name}, never made up by their Robot.", "Au Robot de {name} : « {q} » La réponse vient de {name}, jamais inventée par son Robot.")
    val toContact = s("to_contact", "To {name}'s Robot: the contact card “{who}”, {fields}.", "Au Robot de {name} : la carte de contact « {who} », {fields}.")
    val qAnswered = s("q_answered", "{name}'s Robot answered your question.", "Le Robot de {name} a répondu à ta question.")
    val qFileAnswer = s("q_file_answer", "{name}'s Robot answered your question with a file.", "Le Robot de {name} a répondu à ta question avec un fichier.")
    /** On a reminder's card past LinkLimits.REMINDERS_PER_DAY from one person: why it wasn't added on its own. */
    val remindersLimit = s("reminders_limit", "{name}'s Robot has sent 3 reminders today, so this one waits for you.", "Le Robot de {name} a envoyé 3 rappels aujourd'hui, alors celui-ci t'attend.")
    val handledReminder = s("handled_reminder", "{name}'s Robot added a reminder to your brief for {day}.", "Le Robot de {name} a ajouté un rappel à ton résumé du matin pour {day}.")
    /** A reminder from someone, as your brief says it: [text] is their words. */
    val reminderFrom = s("reminder_from", "From {name}: {text}", "De {name} : {text}")
    val typeAnswer = s("type_answer", "Your answer", "Ta réponse")
    val sendAnswer = s("send_answer", "Send answer", "Envoyer la réponse")
    val useNote = s("use_note", "Use a note", "Utiliser une note")
    /** Their words ([original]) with Robot's translation ([text]) under them, in a card's box. */
    val translated = s("translated", "{original}\nTranslated by Robot: {text}", "{original}\nTraduit par Robot : {text}")

        // Who: your People name, else what their Robot claims (in quotes), else no one in particular.
    val someone = s("someone", "Someone", "Quelqu’un")
    val says = s("says", "“{name}”", "« {name} »", voiceEn = "{name}", voiceFr = "{name}")
}
