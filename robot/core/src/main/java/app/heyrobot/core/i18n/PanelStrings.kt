// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP2a's catalog: the panel and Home: PanelController's own lines (what Robot says without the model, in the turn's reply
// language; its notices, in the menus' language), the panel's status line, field hints and buttons that pure functions
// give the screen, MemoryPanel's, BrainLink's, VoiceHub's, BargeTest's and the capture's words. Ids
// start with "panel."; the area is "panel". The French is Québec French (tu), written with the glossary
// (robot/l10n/glossary.tsv); render() adds its spaces. The panel's Compose-only words are in values/strings_panel.xml.

object PanelStrings : Strings("panel", area = "panel") {
    // PanelController: notices (the menus' language).
    val unlockOpenApp = s("unlock_open_app", "Unlock your phone, then open the Robot app.", "Déverrouille ton téléphone, puis ouvre l'appli Robot.")
    val unlockOpenPage = s("unlock_open_robot_page", "Unlock your phone, then open the Robot app › {page}.",
        "Déverrouille ton téléphone, puis ouvre l'appli Robot › {page}.")
    val unlockTapAgain = s("unlock_tap_again", "Unlock your phone, then tap {button} again.", "Déverrouille ton téléphone, puis touche {button} de nouveau.")
    val unlockOpenSettings = s("unlock_open_settings", "Unlock your phone, then open {settings}.", "Déverrouille ton téléphone, puis ouvre {settings}.")
    val settingsDidntOpenGoTo = s("settings_didnt_open_go_to", "{settings} didn't open. Go to {path}.",
        "L'appli {settings} ne s'est pas ouverte. Va dans {path}.")
    val settingsDidntOpen = s("settings_didnt_open", "{settings} didn't open.", "L'appli {settings} ne s'est pas ouverte.")
    val unlockForCamera = s("unlock_for_camera", "Unlock to use the camera or your photos.", "Déverrouille pour utiliser l'appareil photo ou tes photos.")
    val cameraDidntOpen = s("camera_didnt_open", "Couldn't open the camera or the photo picker.",
        "Impossible d'ouvrir l'appareil photo ou le sélecteur de photos.")
    val noVoiceInput = s("no_voice_input", "Voice input isn't available. Tap a button on the card.",
        "La saisie vocale n'est pas disponible. Touche un bouton sur la carte.")
    val stillOnLastAskAgain = s("still_on_last_ask_again", "Robot is still on the last question. Ask again when it's done.",
        "Robot est encore sur la dernière question. Redemande quand il aura fini.")
    val stillOnLast = s("still_on_last", "Robot is still on the last question.", "Robot est encore sur la dernière question.")
    val notReadAloud = s("not_read_aloud", "Not read aloud: the answer holds something private from your memory.",
        "Pas lu à voix haute : la réponse contient quelque chose de privé de ta mémoire.")
    val messageCut = s("message_cut", "Your message was too long, so Robot read only its first part.",
        "Ton message était trop long, alors Robot n'en a lu que le début.")
    val screenCut = s("screen_cut", "The screen had a lot of text, so Robot read only the first part.",
        "L'écran avait beaucoup de texte, alors Robot n'en a lu que le début.")
    val unlockToOpenApp = s("unlock_to_open_app", "Unlock your phone to open the {appName} app.", "Déverrouille ton téléphone pour ouvrir l'appli {appName}.")
    val answeredInstead = s("answered_instead", "{why} Robot answered instead.", "{why} Robot a répondu à la place.")
    val claudeAnswerNotFound = s("claude_answer_not_found", "Robot couldn't find Claude's answer on this screen. You can ask about the screen instead.",
        "Robot n'a pas trouvé la réponse de Claude sur cet écran. Tu peux plutôt poser une question sur l'écran.")
    val unlockOpenWebPage = s("unlock_open_web_page", "Unlock your phone, then open the page.", "Déverrouille ton téléphone, puis ouvre la page.")
    val noBrowser = s("no_browser", "No browser opened the page.", "Aucun navigateur n'a ouvert la page.")
    val unlockOpenCheogram = s("unlock_open_cheogram", "Unlock your phone, then open your messaging app.", "Déverrouille ton téléphone, puis ouvre ton appli de messagerie.")
    val cheogramNotInstalled = s("cheogram_not_installed", "Your messaging app isn't installed.", "Ton appli de messagerie n'est pas installée.")
    val cardHint = s("card_hint", "A card is waiting: say yes or no, or tap a button on it.",
        "Une carte attend : dis oui ou non, ou touche un de ses boutons.")
    val cardTypeHint = s("card_type_hint", "A card is waiting: tap a button on it, or type yes or no.",
        "Une carte attend : touche un de ses boutons, ou écris oui ou non.")
    val textOnlyPhoto = s("text_only_photo", "This model can't see photos: it reads text only. Choose Fast or Smarter in the Robot app › {page} to ask about a photo.",
        "Ce modèle ne voit pas les photos : il lit seulement du texte. Choisis Rapide ou Plus futé dans l'appli Robot › {page} pour poser une question sur une photo.")
    val textOnlyScreen = s("text_only_screen", "This model can't see the screen: it reads only the screen's text.",
        "Ce modèle ne voit pas l'écran : il lit seulement le texte de l'écran.")
    val noVisionInFile = s("no_vision_in_file", "This model can't look at photos: its file has no vision part. {page} says which ones can.",
        "Ce modèle ne peut pas regarder les photos : son fichier n'a pas de partie vision. {page} indique lesquels le peuvent.")
    val costUnknown = s("cost_unknown", "cost unknown", "coût inconnu")
    val shareWith = s("share_with", "Share with {name}", "Partager avec {name}")
    val include = s("include", "Include", "Inclure")
    val startFresh = s("start_fresh", "Start fresh", "Repartir à zéro")
    val send = s("send", "Send", "Envoyer")
    val cancel = s("cancel", "Cancel", "Annuler")

    // PanelController: what Robot says (the turn's reply language).
    val sorry = s("sorry", "Sorry, I didn't catch that.", "Désolé, je n'ai pas compris.")
    val sorryNoAnswer = s("sorry_no_answer", "Sorry, I couldn't answer that.", "Désolé, je n'ai pas pu répondre à ça.")
    val wentWrongTryAgain = s("went_wrong_try_again", "Something went wrong. Please try again.", "Quelque chose n'a pas marché. Réessaie.")
    val wentWrong = s("went_wrong", "Something went wrong.", "Quelque chose n'a pas marché.")
    val modelNotReady = s("model_not_ready", "Robot's model isn't ready.", "Le modèle de Robot n'est pas prêt.")
    val notSure = s("not_sure", "I'm not sure about that one.", "Là-dessus, je ne suis pas sûr.")
    val helperCouldHelp = s("helper_could_help", "{name} could help with that.", "{name} pourrait t'aider avec ça.")
    val unlockFirst = s("unlock_first", "Unlock your phone first.", "Déverrouille ton téléphone d'abord.")
    val wantOpenApp = s("want_open_app", "Want me to open the {appName} app?", "Veux-tu que j'ouvre l'appli {appName} ?")
    val helperGone = s("helper_gone", "That helper isn't in Robot's settings any more.", "Cette IA externe n'est plus dans les réglages de Robot.")
    // 1.0.0: "ask the helper …" with no helper to ask.
    val noHelper = s("no_helper", "No helper is set up yet. Add one in Robot's settings, under Helpers.",
        "Aucune IA externe n'est encore configurée. Ajoutes-en une dans les réglages de Robot, sous IA externes.")
    val noHelperOn = s("no_helper_on", "Every helper is off. Turn one on in Robot's settings, under Helpers.",
        "Toutes les IA externes sont désactivées. Actives-en une dans les réglages de Robot, sous IA externes.")
    val noScreenOrPhoto = s("no_screen_or_photo",
        "Robot doesn't have your screen or a photo this time. Swipe up from the corner over what {name} should see, or add a photo, then ask again.",
        "Robot n'a ni ton écran ni de photo cette fois. Balaie vers le haut depuis le coin par-dessus ce que {name} devrait voir, ou ajoute une photo, puis redemande.")
    val sendTo = s("send_to", "Send {what} to {name}?", "Envoyer {what} à {name} ?")
    val thePhoto = s("the_photo", "the photo", "la photo")
    val screenText = s("screen_text", "your screen's text", "le texte de ton écran")
    val aScreenshot = s("a_screenshot", "a screenshot", "une capture d'écran")
    val aScreenshotOf = s("a_screenshot_of", "a screenshot of {appName}", "une capture d'écran de {appName}")
    val startFreshDetail = s("start_fresh_detail", "Start fresh: {name} sees only what you ask from now on.",
        "Repartir à zéro : {name} voit seulement ce que tu demandes à partir de maintenant.")
    val nothingGoesUntil = s("nothing_goes_until", "Nothing goes to {name} until you tap {button}.", "Rien ne va à {name} tant que tu ne touches pas {button}.")
    val shareTapOrCancel = s("share_tap_or_cancel", "{summary} Tap {confirm} or {cancel}.", "{summary} Touche {confirm} ou {cancel}.")
    val shareSayYesOrNo = s("share_say_yes_or_no", "{summary} Say yes or no.", "{summary} Dis oui ou non.")
    val shareTapOrNo = s("share_tap_or_no", "{summary} Tap {confirm}, or say no.", "{summary} Touche {confirm}, ou dis non.")
    val sharedWith = s("shared_with", "Shared with {name}.", "Partagé avec {name}.")
    val notShared = s("not_shared", "Not shared.", "Pas partagé.")
    val noNetwork = s("no_network", "Robot can't use the network", "Robot ne peut pas utiliser le réseau")
    val alreadyTranslating = s("already_translating", "Robot is already translating.", "Robot traduit déjà.")
    val alreadyTranscribing = s("already_transcribing", "Robot is already transcribing.", "Robot transcrit déjà.")
    val translatingNow = s("translating_now", "Translating now: the interpreter is on the screen.", "Je traduis maintenant : l'interprète est à l'écran.")
    val backToTranscript = s("back_to_transcript", "Back to the transcript.", "Retour à la transcription.")
    val unlockToStart = s("unlock_to_start", "Unlock your phone to start ({page} › Start from the lock screen is off).",
        "Déverrouille ton téléphone pour démarrer (l'option Démarrer depuis l'écran de verrouillage est désactivée dans {page}).")
    val unlockToStartSpoken = s("unlock_to_start_spoken", "Unlock your phone to start: starting from the lock screen is off in Robot's conversation settings.",
        "Déverrouille ton téléphone pour démarrer : le démarrage depuis l'écran de verrouillage est désactivé dans les réglages de conversation de Robot.")
    val startedTranslating = s("started_translating", "Translating. {consent}", "Je traduis. {consent}")
    val startedTranscribing = s("started_transcribing", "Transcribing. {consent}", "Je transcris. {consent}")
    val openingToStart = s("opening_to_start", "Opening the screen to start.", "J'ouvre l'écran pour démarrer.")
    val tellPeopleFirst = s("tell_people_first", "First, a word about telling the people you're with: it's on the screen.",
        "D'abord, un mot sur le fait de prévenir les gens qui sont avec toi : c'est à l'écran.")
    val stoppedSaved = s("stopped_saved", "Stopped. The transcript is saved in Robot › {page}.", "Arrêté. La transcription est enregistrée dans Robot › {page}.")
    val stoppedSavedSpoken = s("stopped_saved_spoken", "Stopped. The transcript is saved in Robot's transcripts.",
        "Arrêté. La transcription est enregistrée dans les transcriptions de Robot.")
    val paused = s("paused", "Paused. Say “resume transcribing”, or tap {button} in the notification.",
        "En pause. Dis « reprends la transcription », ou touche {button} dans la notification.")
    val goingOn = s("going_on", "Going on.", "Je continue.")
    val stoppedUnlockToSummarize = s("stopped_unlock_to_summarize", "Stopped. Unlock your phone, then open the transcript in Robot › {page} to summarize it.",
        "Arrêté. Déverrouille ton téléphone, puis ouvre la transcription dans Robot › {page} pour la résumer.")
    val stoppedUnlockToSummarizeSpoken = s("stopped_unlock_to_summarize_spoken",
        "Stopped. Unlock your phone, then open the transcript in Robot's transcripts to summarize it.",
        "Arrêté. Déverrouille ton téléphone, puis ouvre la transcription dans les transcriptions de Robot pour la résumer.")
    val stoppedSummaryOpening = s("stopped_summary_opening", "Stopped. The summary screen is opening.", "Arrêté. L'écran du résumé s'ouvre.")
    val feedbackUnlock = s("feedback_unlock", "Unlock your phone, then say it again, or open Robot › {page}.",
        "Déverrouille ton téléphone, puis redis-le, ou ouvre Robot › {page}.")
    val feedbackUnlockSpoken = s("feedback_unlock_spoken", "Unlock your phone, then say it again.", "Déverrouille ton téléphone, puis redis-le.")
    val openingFeedback = s("opening_feedback", "Opening {page}. Nothing is sent until you choose an app.",
        "J'ouvre {page}. Rien n'est envoyé tant que tu ne choisis pas une appli.")
    val unlockToText = s("unlock_to_text", "Unlock your phone to send a text.", "Déverrouille ton téléphone pour envoyer un texto.")
    val texting = s("texting", "Texting {name}.", "Texto à {name}.")
    val yourScript = s("your_script", "Your script “{name}”.", "Ton script « {name} ».")
    val openedOrganicMaps = s("opened_organic_maps", "Opened Organic Maps.", "J'ai ouvert Organic Maps.")
    val modelDidntAnswer = s("model_didnt_answer", "The model didn't answer.", "Le modèle n'a pas répondu.")
    val modelTimedOut = s("model_timed_out", "Robot's model didn't answer in time. Please try again.", "Le modèle de Robot n'a pas répondu à temps. Réessaie.")
    val removedFromNotes = s("removed_from_notes", "Removed from Robot Notes.", "Retiré des notes de Robot.")
    val answerWentWrong = s("answer_went_wrong", "(The answer went wrong, so Robot stopped it.)", "(La réponse a déraillé, alors Robot l'a arrêtée.)")
    val noAnswer = s("no_answer", "Robot had no answer. Try asking another way.", "Robot n'avait pas de réponse. Essaie de demander autrement.")

    // The standard look's status line (PanelParts.panelStatus), in the menus' language.
    val listening = s("listening", "Listening…", "À l'écoute…")
    val waitingFor = s("waiting_for", "Waiting for {name}…", "En attente de {name}…")
    val helperAnswering = s("helper_answering", "{name} is answering…", "{name} répond…")
    // 1.0.0: a helper without a name yet ("The helper is answering…", "Talking with the helper", the reply's label).
    val theHelperStart = s("the_helper_start", "The helper", "L'IA externe")
    val theHelper = s("the_helper", "the helper", "l'IA externe")
    val helperLabel = s("helper_label", "Helper", "IA externe")
    val answering = s("answering", "Answering…", "Réponse en cours…")
    val withHelperServer = s("with_helper_server", "Talking with {name}: questions go to your server", "Avec {name} : les questions vont à ton serveur")
    val lockedAllowed = s("locked_allowed", "Locked: {allowed}", "Verrouillé : {allowed}")

    // The Classic look's status line (PanelUi.classicStatusLine), 0.6.0's words.
    val writingItDown = s("writing_it_down", "Writing it down…", "Je note…")
    val wakingModel = s("waking_model", "Waking up the model…", "Je réveille le modèle…")
    val thinking = s("thinking", "Thinking…", "Je réfléchis…")
    val sayYesOrNoDots = s("say_yes_or_no_dots", "Say yes or no…", "Dis oui ou non…")
    val waitingForYou = s("waiting_for_you", "Waiting for you: tap the card, or answer yes or no", "Je t'attends : touche la carte ou dis oui ou non")
    val speakingTapToStop = s("speaking_tap_to_stop", "Speaking · tap to stop", "Je parle · touche pour arrêter")
    val withHelperYoursOnline = s("with_helper_yours_online", "Talking with {name}: your questions go online", "Avec {name} : tes questions vont en ligne")
    val withHelperYoursServer = s("with_helper_yours_server", "Talking with {name}: your questions go to your server",
        "Avec {name} : tes questions vont à ton serveur")
    val gettingReady = s("getting_ready", "Getting ready…", "Je me prépare…")
    val onThisPhonePrivate = s("on_this_phone_private", "On this phone, private", "Privé, sur l'appareil")

    // The field's hint (PanelParts.inputPlaceholder) and the main button's jobs (FabMode).
    val listeningYesOrNo = s("listening_yes_or_no", "Listening: yes or no?", "À l'écoute : oui ou non ?")

    // A notice's button (PanelParts.noticeAction) and a card's (PanelUi.toTurnUi).
    val openRobot = s("open_robot", "Open Robot", "Ouvrir Robot")
    val openSettings = s("open_settings", "Open settings", "Ouvrir les paramètres")
    val openModel = s("open_model", "Open Model", "Ouvrir Modèle")
    val openCheogram = s("open_cheogram", "Open Cheogram", "Ouvrir Cheogram")

    // MemoryPanel: memory's own words in the panel (the card's in the turn's reply language; the model reads English).
    val secretRefused = s("secret_refused",
        "Robot doesn't keep passwords, PINs, codes or card numbers from a conversation. To keep one, say “remember that …” yourself: it stays private, never spoken or sent.",
        "Robot ne garde pas les mots de passe, NIP, codes ou numéros de carte d'une conversation. Pour en garder un, dis toi-même « retiens que … » : il reste privé, jamais dit ni envoyé.")
    val deleteEverything = s("delete_everything", "Delete everything Robot remembers", "Supprimer tout ce que Robot retient")
    val onlySettingsDeletesAll = s("only_settings_deletes_all", "Only {page} deletes everything.", "Seule la page {page} supprime tout.")
    val whatRobotRemembers = s("what_robot_remembers", "What Robot remembers", "Ce que Robot retient")
    val thingsInSettings = plural("things_in_settings", "n", Plural(one = "{n} thing, in {page}.", other = "{n} things, in {page}."),
        Plural(one = "{n} chose, dans {page}.", other = "{n} choses, dans {page}."))
    val keepThem = s("keep_them", "Okay, I'll keep them.", "D'accord, je garde tout.")
    val memoryOff = s("memory_off", "Memory is off in Robot's settings.", "La mémoire est désactivée dans les réglages de Robot.")
    val nothingToRemember = s("nothing_to_remember", "There was nothing to remember.", "Il n'y avait rien à retenir.")
    val nothingMatches = s("nothing_matches", "Nothing in memory matches that.", "Rien dans la mémoire ne correspond à ça.")
    val fromMessageConversation = s("from_message_conversation", "your words in a conversation that held a message",
        "tes mots dans une conversation qui contenait un message")
    val fromContent = s("from_content", "something Robot read in a conversation", "quelque chose que Robot a lu dans une conversation")
    val memoryFull = s("memory_full", "Memory is full: delete some things in {page}.", "La mémoire est pleine : supprime des choses dans {page}.")
    val alreadyRemembered = s("already_remembered", "Robot already remembers that.", "Robot retient déjà ça.")
    val rememberedPinsFull = s("remembered_pins_full", "Remembered. Six things are pinned already, so it isn't pinned.",
        "C'est retenu. Six choses sont déjà épinglées, alors celle-ci ne l'est pas.")
    val rememberedPinned = s("remembered_pinned", "Remembered, and used in every answer.", "C'est retenu, et utilisé dans chaque réponse.")
    val remembered = s("remembered", "Remembered.", "C'est retenu.")
    val alreadyGone = s("already_gone", "It was already gone.", "C'était déjà parti.")
    val forgotten = s("forgotten", "Forgotten.", "C'est oublié.")
    val forgottenThings = plural("forgotten_things", "n", Plural(one = "Forgotten: {n} thing.", other = "Forgotten: {n} things."),
        Plural(one = "C'est oublié : {n} chose.", other = "C'est oublié : {n} choses."))
    val nothingToUndo = s("nothing_to_undo", "Nothing to undo.", "Rien à annuler.")
    val removedFromMemory = s("removed_from_memory", "Removed from memory.", "Retiré de la mémoire.")
    val putBack = s("put_back", "Put back in memory.", "Remis dans la mémoire.")

    // BrainLink: the model's state, as the panel shows it (the menus' language).
    val gpuReport = s("gpu_report", "{problem} The Robot app › {page} has a report you can copy or share.",
        "{problem} L'appli Robot › {page} a un rapport que tu peux copier ou partager.")
    val tpuReport = s("tpu_report",
        "{problem} Robot uses the general build of Fast from now on; the Robot app › {page} can try the TPU again, and has a report you can copy or share.",
        "{problem} Robot utilise désormais la version générale de Rapide ; l'appli Robot › {page} peut réessayer le TPU, et a un rapport que tu peux copier ou partager.")
    val stoppedThrice = s("stopped_thrice", "The model stopped unexpectedly three times in the last hour. Try again later.",
        "Le modèle s'est arrêté de façon inattendue trois fois dans la dernière heure. Réessaie plus tard.")
    val noModelProcess = s("no_model_process", "Robot couldn't start its model process.", "Robot n'a pas pu démarrer son processus de modèle.")
    val gpuTimeout = s("gpu_timeout", "Fast didn't load on the GPU in time. Robot uses the CPU from the next question.",
        "Rapide ne s'est pas chargé sur le GPU à temps. Robot utilise le CPU dès la prochaine question.")
    val tpuTimeout = s("tpu_timeout", "Fast (beta) didn't load on the TPU in time. Robot uses the general build from the next question.",
        "Rapide (bêta) ne s'est pas chargé sur le TPU à temps. Robot utilise la version générale dès la prochaine question.")
    val tpuFailed = s("tpu_failed", "Fast (beta) didn't load on this phone's TPU. Download Fast (the general build) in the Robot app › {page}, or try the TPU again there.",
        "Rapide (bêta) ne s'est pas chargé sur le TPU de ce téléphone. Télécharge Rapide (la version générale) dans l'appli Robot › {page}, ou réessaie le TPU là.")
    val loadTimeout = s("load_timeout", "The model didn't load in time.", "Le modèle ne s'est pas chargé à temps.")
    // 1.2 (design Step 2): Robot's self-test of the GPU or a beta TPU build, once per Android build, before your first question.
    val checkingDevice = s("checking_device", "Checking this phone's {device}, about {seconds} s…", "Je vérifie le {device} de ce téléphone, environ {seconds} s…")
    val gpuCheckFailed = s("gpu_check_failed",
        "{problem}. Robot uses the CPU from now on, more slowly; the Robot app › {page} can try the GPU again.",
        "{problem}. Robot utilise désormais le CPU, plus lentement ; l'appli Robot › {page} peut réessayer le GPU.")
    val tpuCheckFailed = s("tpu_check_failed",
        "{problem}. Robot uses the general build of Fast from now on; the Robot app › {page} can try the {device} again.",
        "{problem}. Robot utilise désormais la version générale de Rapide ; l'appli Robot › {page} peut réessayer le {device}.")
    val checkStalled = s("check_stalled", "This phone's {device} stopped answering during Robot's check. From the next question, Robot uses {instead}.",
        "Le {device} de ce téléphone a cessé de répondre pendant la vérification de Robot. Dès la prochaine question, Robot utilise {instead}.")
    val insteadCpu = s("instead_cpu", "the CPU", "le CPU")
    val insteadGeneral = s("instead_general", "the general build of Fast", "la version générale de Rapide")

    // VoiceHub: the wake word's state (Settings › Voice shows it, in the menus' language), and the voice's test.
    val notAssistant = s("not_assistant", "Robot isn't the active assistant right now.", "Robot n'est pas l'assistant actif en ce moment.")
    val wakeWordOff = s("wake_word_off", "The wake word is off in settings.", "Le mot d'éveil est désactivé dans les réglages.")
    val needsMic = s("needs_mic", "Robot needs the microphone permission.", "Robot a besoin de l'autorisation du micro.")
    val onlyCharging = s("only_charging", "Listening only while charging.", "Écoute seulement pendant la recharge.")
    val micBusy = s("mic_busy", "The microphone couldn't be opened (another app may be using it).",
        "Le micro n'a pas pu être ouvert (une autre appli l'utilise peut-être).")
    val wakeCrashed = s("wake_crashed", "The wake word stopped after repeated crashes; it comes back after a restart.",
        "Le mot d'éveil s'est arrêté après des plantages répétés ; il revient après un redémarrage.")
    val voiceSample = s("voice_sample", "Hi, I'm Robot. This is how I sound.", "Salut, je suis Robot. Voici ma voix.")

    // BargeTest: the voice interruption test's notes (Settings › Voice shows them).
    val bargeNotListening = s("barge_not_listening",
        "Robot isn't listening for wake words right now. Turn the wake word on ({page}) and check {setup}, then try again.",
        "Robot n'écoute pas les mots d'éveil en ce moment. Active le mot d'éveil ({page}) et vérifie {setup}, puis réessaie.")
    val bargeClosePanel = s("barge_close_panel", "Close Robot's panel first, then start the test from here.",
        "Ferme d'abord le panneau de Robot, puis lance le test d'ici.")
    val bargeNoVoice = s("barge_no_voice", "Robot's voice didn't start. Check {page}.", "La voix de Robot n'a pas démarré. Vérifie {page}.")

    // CaptureActivity: a photo that didn't come.
    val noPicture = s("no_picture", "No picture came back.", "Aucune photo n'est revenue.")
    val unreadablePicture = s("unreadable_picture", "Couldn't read that picture.", "Impossible de lire cette photo.")
}
