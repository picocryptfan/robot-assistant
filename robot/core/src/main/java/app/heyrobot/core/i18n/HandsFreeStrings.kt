// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP4b's catalog: headset and car mode (core car/*, headset/*, output/*) and what the panel says in a hands-free turn
// (assistant/handsfree, assistant/car). Ids start with "handsfree."; the area is "handsfree". Québec
// French (tu, « auto », « stationné », « Écouteurs », « appli »); render() adds the French spaces, and a spoken line gets the
// voice's form (core VoiceForm). A sender's, a device's and a place's name, a message's words and a number are arguments,
// never translated.

object HandsFreeStrings : Strings("handsfree", area = "handsfree") {
    // What OutputGuard's decisions say (output/OutputGuard.kt), and "Read message text aloud when others may hear".
    val unlockToHear = s("unlock_to_hear", "Unlock to hear it.", "Déverrouille pour l'entendre.")
    val unlockToSee = s("unlock_to_see", "Unlock to see the answer.", "Déverrouille pour voir la réponse.")
    val readAloud = s("read_aloud", "Others may hear it. Read it aloud?", "D'autres pourraient l'entendre. Je le lis à voix haute ?")
    val keepForLook = s("keep_for_look", "I'll keep that for when you can look at the phone.", "Je garde ça pour quand tu pourras regarder le téléphone.")
    val privateQuestion = s("private_question", "Are these headphones only you can hear? Say yes or no.",
        "Est-ce que ce sont des écouteurs que toi seul peux entendre ? Dis oui ou non.")
    val privateQuestionNamed = s("private_question_named", "Is “{name}” headphones only you can hear? Say yes or no.",
        "Est-ce que « {name} », ce sont des écouteurs que toi seul peux entendre ? Dis oui ou non.")
    val askEachTime = s("ask_each_time", "Ask each time", "Demander chaque fois")
    val always = s("always", "Always", "Toujours")
    val never = s("never", "Never", "Jamais")

    // Spoken cards (output/SpokenChoice.kt).
    val unlockToAnswer = s("unlock_to_answer", "Unlock to answer that.", "Déverrouille pour répondre.")
    val sayYesNo = s("say_yes_no", "Say yes or no.", "Dis oui ou non.")
    val sayFirstOrSecond = s("say_first_or_second", "Say the first or the second, or no.", "Dis le premier ou le deuxième, ou non.")
    val sayFirstToThird = s("say_first_to_third", "Say the first, the second or the third, or no.", "Dis le premier, le deuxième ou le troisième, ou non.")

    // Calls (car/CallTarget.kt, car/CarFlows.kt).
    val unlockToCall = s("unlock_to_call", "Unlock to call.", "Déverrouille pour appeler.")
    val noXmpp = s("no_xmpp", "Robot can't call that address.", "Robot ne peut pas appeler cette adresse.")
    val noCall = s("no_call", "Okay, no call.", "D'accord, pas d'appel.")
    val tooMany = s("too_many", "Several people match. Pick one on the screen.",
        "Plusieurs personnes correspondent. Choisis à l'écran.")
    val checkOnScreen = s("check_on_screen", "Check who it is on the screen.", "Vérifie qui c'est à l'écran.")
    val onScreen = s("on_screen", "It's on the screen.", "C'est à l'écran.")
    val noNumber = s("no_number", "I don't have a number for {name}.", "Je n'ai pas de numéro pour {name}.")
    val emergency = s("emergency", "I can't place emergency calls. The dialer is open with {number}; tap Call.",
        "Je ne peux pas faire d'appels d'urgence. Le composeur est ouvert avec le {number}; touche Appeler.")
    val callWho = s("call_who", "Call {who}", "Appeler {who}")
    val readBack = s("read_back", "Call {who}, {ending}", "Appeler {who}, {ending}")
    val readBackLabel = s("read_back_label", "Call {who}, {label}, {ending}", "Appeler {who}, {label}, {ending}")
    val whoLabel = s("who_label", "{who}, {label}", "{who}, {label}")
    val ending = s("ending", "ending {digits}", "qui finit par {digits}")
    val mobile = s("mobile", "mobile", "cellulaire")
    val work = s("work", "work", "travail")
    val home = s("home", "home", "domicile")
    val other = s("other", "other", "autre")

    // Messages in the car and the headset (car/CarMessages.kt, headset/Announce.kt).
    val askReply = s("ask_reply", "Reply?", "Tu veux répondre ?")
    val whatReply = s("what_reply", "What should the reply say?", "Qu'est-ce que je réponds ?")
    val notSent = s("not_sent", "Okay, not sent.", "D'accord, pas envoyé.")
    val messageFrom = s("message_from", "Message from {name}.", "Message de {name}.")
    val messageFromIn = s("message_from_in", "Message from {name} in {group}.", "Message de {name} dans {group}.")
    val readIt = s("read_it", "Read it?", "Je te le lis ?")
    val noText = s("no_text", "The app doesn't show the text.", "L'appli ne montre pas le texte.")
    val says = s("says", "{name} says: {text}", "{name} dit : {text}")
    val replyReadBack = s("reply_read_back", "Reply to {name}: {text} Send?", "Réponse à {name} : {text} J'envoie ?")
    val handedTo = s("handed_to", "Handed to {appName}.", "Remis à {appName}.")
    val didntWork = s("didnt_work", "That didn't work. Your reply is on the screen.", "Ça n'a pas marché. Ta réponse est à l'écran.")
    val newMessageCame = s("new_message_came", "A new message just came. Your reply is on the screen.",
        "Un nouveau message vient d'arriver. Ta réponse est à l'écran.")
    val replyOnScreen = s("reply_on_screen", "Your reply is on the screen.", "Ta réponse est à l'écran.")
    val cantSendByVoice = s("cant_send_by_voice", "I can't send that by voice. Your reply is on the screen.",
        "Je ne peux pas l'envoyer à la voix. Ta réponse est à l'écran.")

    // Car mode (car/CarMode.kt, car/Driving.kt, car/CarSetup.kt).
    val carOn = s("car_on", "Car mode on.", "Mode auto activé.")
    val carOff = s("car_off", "Car mode off.", "Mode auto désactivé.")
    val startNotice = s("start_notice", "Car connected · Start car mode", "Auto connectée · Démarrer le mode auto")
    val aDevice = s("a_device", "a device", "un appareil")
    val isThisYourCar = s("is_this_your_car", "Robot sees “{name}”{tail}. Is this your car?", "Robot voit « {name} »{tail}. Est-ce ton auto ?")
    val unlockOnce = s("unlock_once", "Unlock once to use car features.", "Déverrouille une fois pour utiliser les fonctions de l'auto.")
    val indicator = s("indicator", "Car mode · unlocked features", "Mode auto · déverrouillé")
    val carMode = s("car_mode", "Car mode", "Mode auto")
    val wantMore = s("want_more", "Want more?", "Tu en veux plus ?")
    val routeReady = s("route_ready", "Route ready in your maps app. Tap Start to go.",
        "L'itinéraire est prêt dans ton appli de cartes. Touche Démarrer pour partir.")
    val routeLocked = s("route_locked", "Route ready in your maps app. Unlock to see it, then tap Start.",
        "L'itinéraire est prêt dans ton appli de cartes. Déverrouille pour le voir, puis touche Démarrer.")
    val unlockToStart = s("unlock_to_start", "Unlock to start the route.", "Déverrouille pour lancer l'itinéraire.")
    val pickInMaps = s("pick_in_maps", "Pick it in your maps app.", "Choisis-le dans ton appli de cartes.")
    val here = s("here", "Here", "Ici")
    val noHome = s("no_home", "Save your home address in {page} first.", "Enregistre d'abord l'adresse de ton domicile dans {page}.")
    val noWork = s("no_work", "Save your work address in {page} first.", "Enregistre d'abord l'adresse de ton travail dans {page}.")
    val nothingElse = s("nothing_else", "Nothing else on your calendar today.", "Plus rien à ton agenda aujourd'hui.")
    val anEvent = s("an_event", "an event", "un événement")
    val nowEvent = s("now_event", "Now: {title}.", "En ce moment : {title}.")
    val nextEvent = s("next_event", "Next: {title} at {time}.", "Prochain : {title} à {time}.")
    val summaryOff = s("summary_off", "Off", "Désactivé")
    val summaryOn = s("summary_on", "On · {cars}", "Activé · {cars}")
    val noCar = s("no_car", "no car added", "aucune auto ajoutée")
    val cars = plural("cars", "n", Plural(one = "{n} car", other = "{n} cars"), Plural(one = "{n} auto", other = "{n} autos"))

    // Settings › Car mode's choices (car/CarSettings.kt).
    val lockAfterOne = s("lock_after_one", "After one unlock in the car", "Après un déverrouillage dans l'auto")
    val lockAlways = s("lock_always", "Always (not recommended)", "Toujours (pas recommandé)")
    val openDialer = s("open_dialer", "Open the dialer", "Ouvrir le composeur")
    val callAfterYes = s("call_after_yes", "Call directly after a spoken yes", "Appeler directement après un oui dit à voix haute")
    val announceOff = s("announce_off", "Off", "Désactivé")
    val senderThenAsk = s("sender_then_ask", "Sender only, then ask", "L'expéditeur seulement, puis demander")
    val readWhole = s("read_whole", "Read the whole message", "Lire tout le message")
    val fromDnd = s("from_dnd", "Whoever Do Not Disturb lets through", "Ceux que Ne pas déranger laisse passer")
    val everyone = s("everyone", "Everyone", "Tout le monde")
    val onlyChosen = s("only_chosen", "Only people you choose", "Seulement les personnes que tu choisis")

    // Headset mode (headset/*).
    val notAssistant = s("not_assistant", "Robot isn't the digital assistant", "Robot n'est pas l'assistant numérique")
    val headsetMic = s("headset_mic", "Headset microphone (if it has one)", "Micro des écouteurs (s'ils en ont un)")
    val phoneMic = s("phone_mic", "Phone microphone", "Micro du téléphone")
    val peopleYouChoose = s("people_you_choose", "People you choose", "Les personnes que tu choisis")
    val askFirst = s("ask_first", "Ask first", "Demander d'abord")
    val buttonTitle = s("button_title", "Headset button", "Bouton des écouteurs")
    val buttonOff = s("button_off", "Off: other apps get the headset's button ({page})", "Désactivé : les autres applis reçoivent le bouton des écouteurs ({page})")
    val buttonRobot = s("button_robot", "Headset button → Robot ✓", "Bouton des écouteurs → Robot ✓")
    val buttonAsk = s("button_ask", "Headset button → ask each time: press it, pick Robot and Always",
        "Bouton des écouteurs → demande chaque fois : presse-le, choisis Robot et Toujours")
    val buttonOther = s("button_other", "Headset button → another app answers it: pick Robot in Android's settings, or turn that app off",
        "Bouton des écouteurs → une autre appli y répond : choisis Robot dans les paramètres d'Android, ou désactive cette appli")
    val buttonUnknown = s("button_unknown", "Headset button → not known yet", "Bouton des écouteurs → pas encore connu")
    val devicePrivate = s("device_private", "Private: only you hear it", "Privés : toi seul les entends")
    val deviceNotPrivate = s("device_not_private", "Not private: personal answers stay on screen", "Pas privés : les réponses personnelles restent à l'écran")
    val deviceNotAsked = s("device_not_asked", "Not asked yet: Robot asks the first time a personal answer would play",
        "Pas encore demandé : Robot demande la première fois qu'une réponse personnelle jouerait")
    val onPhone = s("on_phone", "I'll listen on the phone.", "Je vais écouter sur le téléphone.")
    val noHeadsetMic = s("no_headset_mic", "The headset microphone didn't connect.", "Le micro des écouteurs ne s'est pas connecté.")
    val costSco = s("cost_sco", "Music pauses while Robot listens and answers; call-quality sound (8 to 32 kHz, by headset).",
        "La musique fait une pause pendant que Robot écoute et répond; son de qualité appel (8 à 32 kHz, selon les écouteurs).")
    val costLe = s("cost_le", "Music pauses while Robot listens and answers.", "La musique fait une pause pendant que Robot écoute et répond.")
    val costWired = s("cost_wired", "No switch needed; music lowers while Robot talks.", "Aucun changement nécessaire; la musique baisse pendant que Robot parle.")
    val costPhone = s("cost_phone", "The phone listens: keep it near you. Music keeps its quality.", "Le téléphone écoute : garde-le près de toi. La musique garde sa qualité.")

    // What the panel says in a hands-free turn (assistant/handsfree, assistant/car).
    val okay = s("okay", "Okay.", "D'accord.")
    val carOffInSettings = s("car_off_in_settings", "Car mode is off in {page}.", "Le mode auto est désactivé dans {page}.")
    val carAlreadyOn = s("car_already_on", "Car mode is already on.", "Le mode auto est déjà activé.")
    val startingCar = s("starting_car", "Starting car mode…", "Démarrage du mode auto…")
    val carNotOn = s("car_not_on", "Car mode isn't on.", "Le mode auto n'est pas activé.")
    val endingCar = s("ending_car", "Ending car mode.", "Fin du mode auto.")
    val noOrganicMaps = s("no_organic_maps", "Your maps app isn't installed.", "Ton appli de cartes n'est pas installée.")
    val allowCalendar = s("allow_calendar", "Allow Robot to read your calendar in the Robot app.", "Autorise Robot à lire ton agenda dans l'appli Robot.")
    val onlyYouHear = s("only_you_hear", "Okay: only you hear it.", "D'accord : toi seul l'entends.")
    val stayOnScreen = s("stay_on_screen", "Okay. Personal answers stay on the screen.", "D'accord. Les réponses personnelles restent à l'écran.")
    val whoToCall = s("who_to_call", "Who should I call?", "Qui veux-tu appeler ?")
    val saySendAgain = s("say_send_again", "Say send again.", "Redis « envoie ».")
    val drivingRobot = s("driving_robot", "Driving (Robot)", "Conduite (Robot)")

    // Settings' Headset row (ui/settings/HandsFreeSettings.kt).
    val buttonOpensSummary = s("button_opens_summary", "Button opens Robot · {devices}", "Le bouton ouvre Robot · {devices}")
    val buttonOffSummary = s("button_off_summary", "Button off · {devices}", "Bouton désactivé · {devices}")
    val noHeadphones = s("no_headphones", "no headphones yet", "aucuns écouteurs pour l'instant")
    val headphones = plural("headphones", "n", Plural(one = "{n} headphone", other = "{n} headphones"),
        Plural(one = "{n} paire d'écouteurs", other = "{n} paires d'écouteurs"))

    // The one-time notice after 0.8 (output/HandsFree.kt).
    val notice = s("notice", "New: routines, the morning brief, the headset button and car mode. Nothing runs until you turn it on; your headset's voice button opens Robot from now on.",
        "Nouveau : les routines, le résumé du matin, le bouton des écouteurs et le mode auto. Rien ne se fait tant que tu ne l'actives pas; le bouton vocal de tes écouteurs ouvre Robot à partir de maintenant.")
}
