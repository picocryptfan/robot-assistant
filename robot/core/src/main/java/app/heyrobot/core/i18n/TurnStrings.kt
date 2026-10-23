// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP1's catalog: what the turn says about itself: Turn.kt's skipped and invalid actions, Approval.kt's card states and
// questions, Safety.kt's lock-screen lines and spoken card answers, ScreenAccess.kt, the panel's tips, ChatOnly.kt,
// Conversation.kt, ConversationState.kt and Quoted.kt. Ids start with "turn."; the area is "cards".
// The French is Québec French (tu), written with the glossary (robot/l10n/glossary.tsv); render() adds its spaces.

object TurnStrings : Strings("turn", area = "cards") {
    // Turn.kt: a call that doesn't run.
    val couldntDo = s("couldnt_do", "Couldn't do “{tool}”", "Impossible de faire « {tool} »")
    val webFirst = s("web_first", "Robot answers from the web search first.", "Robot répond d'abord à partir de la recherche web.")
    val fileFirst = s("file_first", "Robot answers from the file first.", "Robot répond d'abord à partir du fichier.")
    val maxCalls = plural("max_calls", "n",
        Plural(one = "Robot does at most {n} action per answer.", other = "Robot does at most {n} actions per answer."),
        Plural(one = "Robot fait au plus {n} action par réponse.", other = "Robot fait au plus {n} actions par réponse."))
    val turnedOff = s("turned_off", "{what} are turned off in Robot's settings.", "{what} : action désactivée dans les réglages de Robot.")
    val stopped = s("stopped", "Stopped.", "Arrêté.")

    // ActionOutcome.kt (1.0.2): Robot's own line when an action you asked for didn't happen (or, after a reply that claims
    // one was done, what really happened). Each not-done line says "rien" or "pas", so it never reads as a claim itself.
    val outcomeCancelled = s("outcome_cancelled", "You cancelled it, so nothing was done.", "Tu as annulé, alors rien n'a été fait.")
    val outcomeNotDone = s("outcome_not_done", "Nothing was done.", "Rien n'a été fait.")
    val outcomeNoOne = s("outcome_no_one", "Nothing was done: say who it's for.", "Rien n'a été fait : dis pour qui c'est.")
    val outcomeDone = s("outcome_done", "Done.", "Fait.")
    // 1.1: set_alarm without a time in your words (core AlarmWords): Robot asks instead of the model.
    val alarmWhatTime = s("alarm_what_time", "What time should the alarm ring?", "À quelle heure l'alarme doit-elle sonner?")

    // Approval.kt: the lock, and a card's state (in the menus' language on the card; English for the model's replay).
    val unlockThenAsk = s("unlock_then_ask", "Unlock your phone, then ask again.", "Déverrouille ton téléphone, puis redemande.")
    val statusPending = s("status_pending", "Waiting for you", "En attente de toi")
    val statusDone = s("status_done", "Done", "Fait")
    val statusDraftOpened = s("status_draft_opened", "Draft opened", "Brouillon ouvert")
    val statusDeclined = s("status_declined", "Cancelled", "Annulé")
    val statusNeedsUnlock = s("status_needs_unlock", "Unlock to do this", "Déverrouille pour le faire")
    val statusDisabled = s("status_disabled", "Turned off in settings", "Désactivé dans les réglages")
    val statusFailed = s("status_failed", "Didn't work", "N'a pas fonctionné")
    val statusInvalid = s("status_invalid", "Couldn't understand the request", "Demande incomprise")
    val statusSkipped = s("status_skipped", "Not done", "Pas fait")
    val statusNeedsPermission = s("status_needs_permission", "Needs your permission", "Ton autorisation est requise")
    val statusNotFound = s("status_not_found", "Not found", "Introuvable")
    val statusNoApp = s("status_no_app", "App not installed", "Appli non installée")
    val statusUndone = s("status_undone", "Undone", "Annulé")

    // Safety.kt: Settings › Safety & privacy's "Safety cards".
    val cardsAfterReading = s("cards_after_reading", "Always after Robot reads something", "Toujours après que Robot a lu quelque chose")
    val cardsAfterReadingDetail = s("cards_after_reading_detail",
        "Once a conversation has screen text, a photo, or something Robot read or a helper said, every action that writes or leaves the phone asks first.",
        "Dès qu'une conversation contient le texte d'un écran, une photo, ou quelque chose que Robot a lu ou qu'une IA externe a dit, chaque action qui écrit ou qui sort du téléphone demande d'abord.")
    val cardsUnasked = s("cards_unasked", "When you didn't ask for it", "Quand tu ne l'as pas demandé")
    val cardsUnaskedDetail = s("cards_unasked_detail",
        "Content Robot read makes an action ask first when you didn't ask for that kind of action yourself this time, and always when it would change or open something. Text on a screen, in a photo or in what Robot found never counts as your words.",
        "Ce que Robot a lu force une action à demander d'abord quand tu n'as pas demandé ce genre d'action toi-même cette fois-ci, et toujours quand elle changerait ou ouvrirait quelque chose. Le texte d'un écran, d'une photo ou de ce que Robot a trouvé ne compte jamais comme tes mots.")
    val cardsUnsureOnly = s("cards_unsure_only", "Only when Robot isn't sure who", "Seulement quand Robot n'est pas sûr de qui")
    val cardsUnsureOnlyDetail = s("cards_unsure_only_detail",
        "Content never makes an action ask first. Cards still show for actions set to Ask first, and when Robot isn't sure who or which.",
        "Le contenu ne force jamais une action à demander d'abord. Les cartes s'affichent quand même pour les actions réglées à Demander d'abord, et quand Robot n'est pas sûr de qui ou de laquelle.")
    val safetyNotice = s("safety_notice", "New: {page}. Cards now appear when you didn't ask for an action.",
        "Nouveau : {page}. Les cartes s'affichent maintenant pour une action que tu n'as pas demandée.")

    // Safety.kt: what the empty panel says on the lock screen.
    val lockQuestions = s("lock_questions", "questions", "questions")
    val lockQuestionsOnly = s("lock_questions_only", "questions only", "questions seulement")
    val lockAskOrTimer = s("lock_ask_or_timer", "Ask a question, or set a timer or alarm.", "Pose une question, ou règle une minuterie ou une alarme.")
    val lockAsk = s("lock_ask", "Ask a question.", "Pose une question.")
    val lockAskOrUse = s("lock_ask_or_use", "Ask a question, or use: {list}.", "Pose une question, ou utilise : {list}.")

    // Safety.kt: a card answered by voice (CardAnswer), said in the reply language.
    val sayYesOrNo = s("say_yes_or_no", "Say yes or no.", "Dis oui ou non.")
    val tapOne = s("tap_one", "Tap the one you mean on the card.", "Touche ton choix sur la carte.")
    val tapFiles = s("tap_files", "This would send what Robot read from your files: check the card, then tap.",
        "Ceci enverrait ce que Robot a lu dans tes fichiers : vérifie la carte, puis touche-la.")
    val tapLearned = s("tap_learned", "Robot learned that name from a notification: check who it goes to on the card, then tap.",
        "Robot a appris ce nom dans une notification : vérifie à qui ça va sur la carte, puis touche.")
    val tapUnsure = s("tap_unsure", "Robot isn't sure who or which it is: check the card, then tap.",
        "Robot n'est pas sûr de qui ou de quoi il s'agit : vérifie la carte, puis touche.")
    val tapOnline = s("tap_online", "Tap {always}, {once} or {no} on the card.", "Touche {always}, {once} ou {no} sur la carte.")
    val tapSearch = s("tap_search", "This search has words from something Robot read on your phone: check it on the card, then tap.",
        "Cette recherche contient des mots de quelque chose que Robot a lu sur ton téléphone : vérifie-la sur la carte, puis touche.")
    val tapFromContent = s("tap_from_content", "Robot read this from content it didn't write: check the card, then tap {run}.",
        "Robot a lu ceci dans un contenu qu'il n'a pas écrit : vérifie la carte, puis touche {run}.")
    val tapCommand = s("tap_command", "This command has warnings: check the card, then tap {run}.",
        "Cette commande a des avertissements : vérifie la carte, puis touche {run}.")
    val orSayNo = s("or_say_no", "{why} Or say no.", "{why} Ou dis non.")
    val tapOneOrNo = s("tap_one_or_no", "Tap the one you mean on the card, or say no.", "Touche ton choix sur la carte, ou dis non.")
    val tapToShare = s("tap_to_share", "Tap {button} to share it.", "Touche {button} pour le partager.")
    val tapButtonToShare = s("tap_button_to_share", "Tap the button to share it.", "Touche le bouton pour le partager.")

    // ui/common Chat.kt: the line under a helper's reply that searched the web (the menus' language).
    val searchedWeb = s("searched_web", "{name} searched the web · {sites}", "{name} a cherché sur le web · {sites}")

    // ScreenAccess.kt: "Ask about this screen" without the screen. Android's own settings keep Android's words.
    val askAboutScreen = s("ask_about_screen", "Ask about this screen", "Poser une question sur cet écran")
    val screenWaiting = s("screen_waiting", "Android is still sending your screen. Try again in a moment.",
        "Android envoie encore ton écran. Réessaie dans un instant.")
    val screenSwipe = s("screen_swipe",
        "Android shares your screen with Robot only when you open it by swiping up from a bottom corner. Swipe up from a corner over the screen, then ask about it.",
        "Android partage ton écran avec Robot seulement quand tu l'ouvres en balayant vers le haut depuis un coin du bas. Balaie vers le haut depuis un coin par-dessus l'écran, puis pose ta question.")
    val screenSwipeTextOff = s("screen_swipe_text_off", "{swipe} “Use text from screen” is off: turn it on first in {where}.",
        "{swipe} « Utiliser le texte de l'écran » est désactivé : active-le d'abord dans {where}.")
    val screenNotShared = s("screen_not_shared", "Android didn't share this screen with Robot.", "Android n'a pas partagé cet écran avec Robot.")
    val screenPrivate = s("screen_private", "this app may keep its screen private (banking apps and private tabs do)",
        "cette appli garde peut-être son écran privé (les applis bancaires et les onglets privés le font)")
    val screenTextOff = s("screen_text_off",
        "{start} Turn on “Use text from screen” in {where}, then swipe up from a bottom corner again. Without it, Android sends no screenshot either.",
        "{start} Active « Utiliser le texte de l'écran » dans {where}, puis balaie de nouveau vers le haut depuis un coin du bas. Sans ce réglage, Android n'envoie pas de capture d'écran non plus.")
    val screenBothOff = s("screen_both_off",
        "{start} Turn on “Use text from screen” and “Use screenshot” in {where}, then swipe up from a bottom corner again.",
        "{start} Active « Utiliser le texte de l'écran » et « Utiliser une capture d'écran » dans {where}, puis balaie de nouveau vers le haut depuis un coin du bas.")
    val screenUnknown = s("screen_unknown",
        "{start} Check that “Use text from screen” and “Use screenshot” are on in {where}, then swipe up from a bottom corner again. Some apps, like banking apps and private tabs, never share their screen.",
        "{start} Vérifie que « Utiliser le texte de l'écran » et « Utiliser une capture d'écran » sont activés dans {where}, puis balaie de nouveau vers le haut depuis un coin du bas. Certaines applis, comme les applis bancaires et les onglets privés, ne partagent jamais leur écran.")
    val screenShotOff = s("screen_shot_off",
        "{start} “Use text from screen” is on, so {private}. “Use screenshot” is off in {where}: turn it on to send a picture of the screen as well.",
        "{start} « Utiliser le texte de l'écran » est activé, donc {private}. « Utiliser une capture d'écran » est désactivé dans {where} : active-le pour envoyer aussi une image de l'écran.")
    val screenBothOn = s("screen_both_on", "{start} “Use text from screen” and “Use screenshot” are on, so {private}.",
        "{start} « Utiliser le texte de l'écran » et « Utiliser une capture d'écran » sont activés, donc {private}.")
    val screenTextOn = s("screen_text_on", "{start} “Use text from screen” is on, so {private}.",
        "{start} « Utiliser le texte de l'écran » est activé, donc {private}.")
    val screenReady = s("screen_ready", "Screen ready", "Écran prêt")
    val screenComing = s("screen_coming", "Screen still coming", "Écran pas encore arrivé")
    val screenNotSharedTap = s("screen_not_shared_tap", "Screen not shared: tap to see why", "Écran non partagé : touche pour savoir pourquoi")
    val screenNoneTap = s("screen_none_tap", "No screen this time: tap to see how to share it",
        "Pas d'écran cette fois : touche pour voir comment le partager")
    val screenSettings = s("screen_settings", "Use text from screen {text} · Use screenshot {shot}.",
        "Utiliser le texte de l'écran {text} · Utiliser une capture d'écran {shot}.")
    val screenSetupTextOff = s("screen_setup_text_off",
        "{both} Turn on Use text from screen for “{ask}”: without it, Android sends no screenshot either.",
        "{both} Active Utiliser le texte de l'écran pour « {ask} » : sans ce réglage, Android n'envoie pas de capture d'écran non plus.")
    val screenSetupUnknown = s("screen_setup_unknown",
        "Android didn't say. For “{ask}”, turn on “Use text from screen” and “Use screenshot” in {where}.",
        "Android ne l'a pas dit. Pour « {ask} », active « Utiliser le texte de l'écran » et « Utiliser une capture d'écran » dans {where}.")
    val screenSetupShotOff = s("screen_setup_shot_off",
        "{both} “{ask}” sends the screen's text; turn on Use screenshot to send a picture of it too.",
        "{both} « {ask} » envoie le texte de l'écran; active Utiliser une capture d'écran pour en envoyer aussi une image.")
    val screenSetupOk = s("screen_setup_ok", "{both} Swipe up from a bottom corner, then tap “{ask}”.",
        "{both} Balaie vers le haut depuis un coin du bas, puis touche « {ask} ».")

    // Tips.kt: the empty panel's tips (the menus' language; tapping one asks it, or puts it in the text field).
    val tipScreen = s("tip_screen", "What's on my screen?", "Qu'est-ce qu'il y a sur mon écran?")
    val tipWeather = s("tip_weather", "What's the weather tomorrow?", "Quel temps fera-t-il demain?")
    val tipTimer = s("tip_timer", "Set a timer for 10 minutes", "Mets une minuterie de 10 minutes")
    val tipText = s("tip_text", "Text Mom I'm on my way", "Envoie un texto à maman : je suis en route")
    val tipCalendar = s("tip_calendar", "What's on my calendar today?", "Qu'est-ce que j'ai à mon agenda aujourd'hui?")
    val tipPhoto = s("tip_photo", "Ask about a photo", "Poser une question sur une photo")
    val tipRemember = s("tip_remember", "Remember that I parked on level 3", "Retiens que je suis stationné au niveau 3")
    val tipBattery = s("tip_battery", "How's my battery?", "Comment va ma batterie?")
    val tipClaude = s("tip_claude", "Ask a helper why the sky is blue", "Demande à un assistant pourquoi le ciel est bleu")
    val tipNearest = s("tip_nearest", "Directions to the nearest pharmacy", "Itinéraire vers la pharmacie la plus proche")
    val tipAlarm = s("tip_alarm", "Wake me up at 7:00", "Réveille-moi à 7 h")
    val tipSpace = s("tip_space", "Help me free up space", "Aide-moi à libérer de l'espace")
    val tipEmail = s("tip_email", "Email Sam about dinner on Friday", "Écris un courriel à Sam pour le souper de vendredi")
    val tipNote = s("tip_note", "Find my note about the wifi", "Trouve ma note sur le Wi-Fi")
    val tipFlashlight = s("tip_flashlight", "Turn on the flashlight", "Allume la lampe de poche")
    val tipEvent = s("tip_event", "Add lunch with Alex on Friday at noon", "Ajoute un dîner avec Alex vendredi à midi")
    val tipStorage = s("tip_storage", "How much storage is free?", "Combien d'espace de stockage est libre?")
    val tipFact = s("tip_fact", "Tell me a fun fact", "Dis-moi un fait amusant")

    // ChatOnly.kt: a model that can only chat.
    val chatOnly = s("chat_only",
        "This model can only chat: it can't do things on the phone (timers, alarms, texts, notes, the calendar, maps) or look anything up. To have Robot do that, choose Fast in {page}.",
        "Ce modèle peut seulement discuter : il ne peut rien faire sur le téléphone (minuteries, alarmes, textos, notes, agenda, cartes) ni rien chercher. Pour que Robot le fasse, choisis Rapide dans {page}.")
    val chatOnlyNotice = s("chat_only_notice", "For actions, choose Fast in {page}.", "Pour les actions, choisis Rapide dans {page}.")

    // Conversation.kt: History's titles.
    val newConversation = s("new_conversation", "New conversation", "Nouvelle conversation")
    val titlePhoto = s("title_photo", "Photo", "Photo")
    val titleScreen = s("title_screen", "Screen", "Écran")
    val titleConversation = s("title_conversation", "Conversation", "Conversation")

    // ConversationState.kt: the reminder before transcribing or translating a conversation.
    val consentText = s("consent_text",
        "Before you transcribe or translate a conversation, tell the people you're talking with. The laws on recording conversations differ by place, and some require everyone's agreement. Robot keeps the text on this phone only, unless you send it somewhere.",
        "Avant de transcrire ou de traduire une conversation, dis-le aux personnes avec qui tu parles. Les lois sur l'enregistrement des conversations varient selon l'endroit, et certaines exigent l'accord de tout le monde. Robot garde le texte sur ce téléphone seulement, sauf si tu l'envoies ailleurs.")
    val consentTick = s("consent_tick", "I'll tell people", "Je vais le dire aux gens")
    val consentLine = s("consent_line", "Recording — let others know.", "Enregistrement en cours — préviens les autres.")

    // Quoted.kt: the card of a reply that read out your calendar and reminders.
    val readCalendarAndReminders = s("read_calendar_and_reminders", "Read your calendar and reminders", "Lire ton agenda et tes rappels")
}
