// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP1's catalog: the actions' cards: Tools.kt's ToolName titles, descriptions (the card's summary, an infinitive in
// French), confirm labels and invalid-call reasons, BrowserActions.kt, and the results ToolExecutor, WebTool,
// BrowserSearchTool and CallPlacer give (the card's line in the reply language; the model reads their English)
// Ids start with "tool."; the area is "cards". Québec French (tu), the glossary's words.

object ToolStrings : Strings("tool", area = "cards") {
    // ToolName titles (Settings › Actions, Safety & privacy's lock-screen list, "… are turned off").
    val titleSetTimer = s("title_set_timer", "Timers", "Minuteries")
    val titleSetAlarm = s("title_set_alarm", "Alarms", "Alarmes")
    val titleOpenApp = s("title_open_app", "Open apps", "Ouvrir des applis")
    val titleMapsSearch = s("title_maps_search", "Maps search", "Recherche sur la carte")
    val titleMapsNavigate = s("title_maps_navigate", "Maps directions", "Itinéraires")
    val titleFlashlight = s("title_flashlight", "Flashlight", "Lampe de poche")
    val titleSendText = s("title_send_text", "Texts", "Textos")
    val titleDraftEmail = s("title_draft_email", "Email drafts", "Brouillons de courriel")
    val titleLookupContact = s("title_lookup_contact", "Contact lookups", "Recherches de contacts")
    val titleCall = s("title_call", "Calls", "Appels")
    val titleAddNote = s("title_add_note", "New notes", "Nouvelles notes")
    val titleFindNotes = s("title_find_notes", "Note searches", "Recherches de notes")
    val titleAddEvent = s("title_add_event", "New events", "Nouveaux événements")
    val titleReadCalendar = s("title_read_calendar", "Calendar lookups", "Consultations de l'agenda")
    val titleWeather = s("title_weather", "Weather lookups", "Consultations de la météo")
    val titlePhoneStatus = s("title_phone_status", "Phone status checks", "Vérifications de l'état du téléphone")
    val titleFreeMemory = s("title_free_memory", "Memory clean-ups", "Libération de mémoire")
    val titleFreeStorage = s("title_free_storage", "Storage clean-ups", "Libération d'espace de stockage")
    val titleWebSearch = s("title_web_search", "Web searches", "Recherches web")
    val titleBrowserSearch = s("title_browser_search", "Searches in your browser", "Recherches dans ton navigateur")
    val titleAskClaude = s("title_ask_claude", "Ask a helper", "Demander à une IA externe")
    val titleTermuxScript = s("title_termux_script", "Your Termux scripts", "Tes scripts Termux")
    val titleTermuxCommand = s("title_termux_command", "Termux commands", "Commandes Termux")
    // 1.4, Robot Files (F9): the model's two read tools (internal: Settings › Files governs them).
    val titleFindFiles = s("title_find_files", "File searches", "Recherches de fichiers")
    val titleReadFile = s("title_read_file", "Questions about a file", "Questions sur un fichier")

    // Action summaries: what a card does (light Markdown: the person or title in bold, the calendar in italics).
    val learned = s("learned", "{what} · name seen in a notification", "{what} · nom vu dans une notification")
    val setTimer = s("set_timer", "Set a timer for {duration}", "Régler une minuterie de {duration}")
    val setAlarm = s("set_alarm", "Set an alarm for {time}", "Régler une alarme pour {time}")
    val openApp = s("open_app", "Open {appName}", "Ouvrir {appName}")
    val mapsSearch = s("maps_search", "Search Organic Maps for “{query}”", "Chercher « {query} » dans Organic Maps")
    val mapsNavigate = s("maps_navigate", "Search Organic Maps for “{query}”, then tap Route",
        "Chercher « {query} » dans Organic Maps, puis toucher Itinéraire")
    val routeHome = s("route_home", "Open the route home in Organic Maps", "Ouvrir l'itinéraire vers la maison dans Organic Maps")
    val routeWork = s("route_work", "Open the route to work in Organic Maps", "Ouvrir l'itinéraire vers le travail dans Organic Maps")
    val flashlightOn = s("flashlight_on", "Turn the flashlight on", "Allumer la lampe de poche")
    val flashlightOff = s("flashlight_off", "Turn the flashlight off", "Éteindre la lampe de poche")
    val textTo = s("text_to", "Text **{to}**", "Texter **{to}**")
    val textChoose = s("text_choose", "{why} Choose who to text in Cheogram", "{why} Choisir à qui texter dans Cheogram")
    val textPick = s("text_pick", "{why} Pick who to text in Cheogram", "{why} Sélectionner à qui texter dans Cheogram")
    val textNumber = s("text_number", "Text **{to}** in {appName}", "Texter **{to}** dans {appName}")
    val textPerson = s("text_person", "Text **{name}** ({to}) in {appName}", "Texter **{name}** ({to}) dans {appName}")
    val chooseInCheogram = s("choose_in_cheogram", "Choose in Cheogram", "Choisir dans Cheogram")
    val pickInCheogram = s("pick_in_cheogram", "Pick in Cheogram", "Sélectionner dans Cheogram")
    val openInFor = s("open_in_for", "Open in {appName} for {who}", "Ouvrir dans {appName} pour {who}")
    val chooseEmailAppFor = s("choose_email_app_for", "Choose the email app for {who}", "Choisir l'appli de courriel pour {who}")
    val emailTo = s("email_to", "Email **{to}**", "Écrire un courriel à **{to}**")
    val emailPerson = s("email_person", "Email **{name}** ({address})", "Écrire un courriel à **{name}** ({address})")
    val emailToIn = s("email_to_in", "Email **{to}** in {appName}", "Écrire un courriel à **{to}** dans {appName}")
    val emailPersonIn = s("email_person_in", "Email **{name}** ({address}) in {appName}", "Écrire un courriel à **{name}** ({address}) dans {appName}")
    val subject = s("subject", "Subject: {subject}", "Objet : {subject}")
    val lookUp = s("look_up", "Look up **{name}** in your contacts", "Chercher **{name}** dans tes contacts")
    val callTo = s("call_to", "Call **{to}**", "Appeler **{to}**")
    val callPerson = s("call_person", "Call **{name}** ({number})", "Appeler **{name}** ({number})")
    val callPersonLabel = s("call_person_label", "Call **{name}** ({label}, {number})", "Appeler **{name}** ({label}, {number})")
    val openDialer = s("open_dialer", "Open the dialer", "Ouvrir le composeur")
    val saveNote = s("save_note", "Save a note in Robot Notes", "Enregistrer une note dans Notes de Robot")
    val saveNoteTitled = s("save_note_titled", "Save the note **{title}** in Robot Notes", "Enregistrer la note **{title}** dans Notes de Robot")
    val findNotes = s("find_notes", "Search your notes for “{query}”", "Chercher « {query} » dans tes notes")
    // 1.4, Robot Files (F9): the cards' fixed lines (never the words searched for: History keeps a card's line in plain).
    val findFiles = s("find_files", "Search your files", "Chercher dans tes fichiers")
    val readFile = s("read_file", "Read a file to answer", "Lire un fichier pour répondre")
    val foundFiles = plural("found_files", "n", Plural("Found {n} file.", "Found {n} files."),
        Plural("{n} fichier trouvé.", "{n} fichiers trouvés.", "{n} de fichiers trouvés."))
    val noFileMatches = s("no_file_matches", "No file matches.", "Aucun fichier ne correspond.")
    val fileFindFirst = s("file_find_first", "Robot needs to find the file first.", "Robot doit d'abord trouver le fichier.")
    val fileWhich = s("file_which", "Robot couldn't tell which file you mean.", "Robot n'a pas pu savoir de quel fichier tu parles.")
    val fileNoText = s("file_no_text", "Robot has no words from that file to read.", "Robot n'a aucun mot de ce fichier à lire.")
    /** read_file's card once its file was found (History keeps it): the answer follows in the reply. */
    val readFileDone = s("read_file_done", "Robot read the file to answer.", "Robot a lu le fichier pour répondre.")
    val fileNotAnswered = s("file_not_answered", "Robot couldn't answer from the file. Try again in a moment.",
        "Robot n'a pas pu répondre à partir du fichier. Réessaie dans un moment.")
    val addEventAsSaid = s("add_event_as_said", "Add **{title}** ({when})", "Ajouter **{title}** ({when})")
    val addEventTo = s("add_event_to", "Add **{title}** on {when} to *{calendar}*", "Ajouter **{title}** le {when} à *{calendar}*")
    val addEventInApp = s("add_event_in_app", "Add **{title}** on {when} in your calendar app", "Ajouter **{title}** le {when} dans ton appli d'agenda")
    // 1.1, dated reminders: the event with its alert ({alert}: a time, EventText.clock).
    val addEventToAlert = s("add_event_to_alert", "Add **{title}** on {when} to *{calendar}*, with an alert at {alert}",
        "Ajouter **{title}** le {when} à *{calendar}*, avec une alerte à {alert}")
    val addEventInAppAlert = s("add_event_in_app_alert", "Add **{title}** on {when} in your calendar app, with an alert at {alert}",
        "Ajouter **{title}** le {when} dans ton appli d'agenda, avec une alerte à {alert}")
    val addAlertInApp = s("add_alert_in_app", "Your calendar app opens with the event. Robot can't set the alert there: add it before you save.",
        "Ton appli d'agenda s'ouvre avec l'événement. Robot ne peut pas y régler l'alerte : ajoute-la avant d'enregistrer.")
    val openCalendarApp = s("open_calendar_app", "Open the calendar app", "Ouvrir l'appli d'agenda")
    val addTo = s("add_to", "Add to {calendar}", "Ajouter à {calendar}")
    // 1.0.0: any helper's app ({app}: theNamedApp or theHelpersApp).
    val openHelperApp = s("open_helper_app", "Open {appName}", "Ouvrir {appName}")
    val openHelperAppWith = s("open_helper_app_with", "Open {appName} with: «{text}»", "Ouvrir {appName} avec : « {text} »")
    val theNamedApp = s("the_named_app", "the {name} app", "l'appli {name}")
    val theHelpersApp = s("the_helpers_app", "the helper's app", "l'appli de l'IA externe")
    val openTheApp = s("open_the_app", "Open the app", "Ouvrir l'appli")
    val weatherHere = s("weather_here", "Check the weather here", "Vérifier la météo ici")
    val weatherHereFor = s("weather_here_for", "Check the weather here for {when}", "Vérifier la météo ici pour {when}")
    val weatherHome = s("weather_home", "Check the weather at home", "Vérifier la météo à ton domicile")
    val weatherHomeFor = s("weather_home_for", "Check the weather at home for {when}", "Vérifier la météo à ton domicile pour {when}")
    val weatherIn = s("weather_in", "Check the weather in **{place}**", "Vérifier la météo à **{place}**")
    val weatherInFor = s("weather_in_for", "Check the weather in **{place}** for {when}", "Vérifier la météo à **{place}** pour {when}")
    val rememberPreference = s("remember_preference", "Remember a preference", "Retenir une préférence")
    val rememberThis = s("remember_this", "Remember this", "Retenir ceci")
    val forgetThings = plural("forget_things", "n", Plural(one = "Forget {n} thing from memory", other = "Forget {n} things from memory"),
        Plural(one = "Oublier {n} chose de la mémoire", other = "Oublier {n} choses de la mémoire"))
    val forgetOne = s("forget_one", "Forget from memory", "Oublier de la mémoire")
    val forget = s("forget", "Forget", "Oublier")
    val openUsageAccess = s("open_usage_access", "Open Usage access, so Robot can list the apps you used lately",
        "Ouvrir l'accès aux données d'utilisation, pour que Robot puisse lister les applis que tu as utilisées récemment")
    val unloadModel = s("unload_model", "Unload Robot's model to free about 2 GB of memory (4 GB for an E4B model)",
        "Décharger le modèle de Robot pour libérer environ 2 Go de mémoire (4 Go pour un modèle E4B)")
    val forceStop = s("force_stop", "Open **{appName}**'s App info to force stop it", "Ouvrir Infos sur l'appli de **{appName}** pour forcer son arrêt")
    val unload = s("unload", "Unload", "Décharger")
    val openAppInfo = s("open_app_info", "Open App info", "Ouvrir Infos sur l'appli")
    val openFreeUp = s("open_free_up", "Open Android's Free up space screen", "Ouvrir l'écran Libérer de l'espace d'Android")
    val chooseApp = s("choose_app", "Choose an app to clear its cache", "Choisir une appli pour vider son cache")
    val clearAppCache = s("clear_app_cache", "Open **{appName}**'s App info to clear its cache", "Ouvrir Infos sur l'appli de **{appName}** pour vider son cache")
    val chooseFile = s("choose_file", "Choose a large photo or video to delete", "Choisir une grosse photo ou vidéo à supprimer")
    val clearRobotCache = s("clear_robot_cache", "Clear Robot's cache", "Vider le cache de Robot")
    val clearRobotCacheSize = s("clear_robot_cache_size", "Clear Robot's cache ({size})", "Vider le cache de Robot ({size})")
    val clear = s("clear", "Clear", "Vider")
    val searchWeb = s("search_web", "Search the web for “{query}”", "Chercher « {query} » sur le web")
    val search = s("search", "Search", "Chercher")
    val checkCalendar = s("check_calendar", "Check your calendar for {when}", "Consulter ton agenda pour {when}")
    val browserSearch = s("browser_search", "Search {engine} for “{query}” in your browser", "Chercher « {query} » avec {engine} dans ton navigateur")
    val browserSearchWeb = s("browser_search_web", "Search the web for “{query}” in your browser", "Chercher « {query} » sur le web dans ton navigateur")

    // Invalid calls: what the model is told (its arguments keep their names), and the card shows.
    val unknownTool = s("unknown_tool", "unknown tool", "outil inconnu")
    val notOffered = s("not_offered", "this tool wasn't offered", "cet outil n'a pas été offert")
    val mustBeNumber = s("must_be_number", "{arg} must be a number", "{arg} doit être un nombre")
    val isRequired = s("is_required", "{arg} is required", "{arg} est requis")
    // 1.0.2: no one (or only a template's placeholder) to text, email or call.
    val noRecipient = s("no_recipient", "to is required: ask who it's for", "to est requis : demande pour qui c'est")
    val mustBeText = s("must_be_text", "{arg} must be text", "{arg} doit être du texte")
    val timerNegative = s("timer_negative", "the timer cannot be negative", "la minuterie ne peut pas être négative")
    val timerLength = s("timer_length", "give the timer's length in minutes or seconds", "donne la durée de la minuterie en minutes ou en secondes")
    val timerMax = s("timer_max", "timers can be at most 24 hours", "une minuterie peut durer au plus 24 heures")
    val wholeHourMinute = s("whole_hour_minute", "hour and minute must be whole numbers", "hour et minute doivent être des nombres entiers")
    val hourRange = s("hour_range", "hour must be 0 to 23", "hour doit être de 0 à 23")
    val minuteRange = s("minute_range", "minute must be 0 to 59", "minute doit être de 0 à 59")
    val trueOrFalse = s("true_or_false", "on must be true or false", "on doit être true ou false")
    val emailBody = s("email_body", "give the email a body", "donne un corps au courriel")
    val durationRange = s("duration_range", "duration_minutes must be a whole number from 1 to {max}",
        "duration_minutes doit être un nombre entier de 1 à {max}")
    val dayText = s("day_text", "day must be text like today or Friday", "day doit être du texte comme today ou Friday")
    val engineOneOf = s("engine_one_of", "engine must be one of {engines}", "engine doit être l'un de ceux-ci : {engines}")

    // ToolExecutor's results: the card's line once an action ran (the model reads the English).
    val theSmsApp = s("the_sms_app", "the SMS app", "l'appli SMS")
    val theEmailApp = s("the_email_app", "the email app", "l'appli de courriel")
    val noAppCalled = s("no_app_called", "No app called “{name}” is installed.", "Aucune appli nommée « {name} » n'est installée.")
    // 1.0.0: any helper's app (core ClaudeApp.kt AppHandOff).
    val installApp = s("install_app", "Install {appName}.", "Installe {appName}.")
    val noHelperApp = s("no_helper_app", "No helper opens an app. Set one up in Robot's settings, under Helpers.",
        "Aucune IA externe n'ouvre d'appli. Configures-en une dans les réglages de Robot, sous IA externes.")
    val handOffNote = s("hand_off_note", "Robot opens the {appName} app. Nothing goes to {appName} from Robot.",
        "Robot ouvre l'appli {appName}. Rien ne part vers {appName} depuis Robot.")
    val handOffNoteText = s("hand_off_note_text", "Robot opens the {appName} app with this text and copies it. Nothing is sent until you press Send in {appName}.",
        "Robot ouvre l'appli {appName} avec ce texte et le copie. Rien n'est envoyé tant que tu n'as pas touché Envoyer dans {appName}.")
    val somethingWrong = s("something_wrong", "Something went wrong.", "Quelque chose n'a pas fonctionné.")
    val timerSet = s("timer_set", "Timer set for {duration}.", "Minuterie de {duration} réglée.")
    val noClockTimers = s("no_clock_timers", "No clock app on this phone takes timers.", "Aucune appli d'horloge sur ce téléphone n'accepte les minuteries.")
    val alarmSet = s("alarm_set", "Alarm set for {time}.", "Alarme réglée pour {time}.")
    // 1.1: an alarm only for a time you said (core AlarmWords); a day it won't ring on is offered as a reminder titled so.
    val alarmNoTime = s("alarm_no_time", "Not set: you didn't say a time.", "Pas réglée : tu n'as pas dit d'heure.")
    val alarmOtherTime = s("alarm_other_time", "Not set: {time} isn't a time you said.", "Pas réglée : {time} n'est pas une heure que tu as dite.")
    val reminderTitle = s("reminder_title", "Reminder", "Rappel")
    // 1.1: what Robot says to "cancel my alarm" (RoutineCommand.CantCancel), and its card for the clock app.
    val cantCancelAlarms = s("cant_cancel_alarms", "Robot can't cancel or turn off alarms: do it in your clock app.",
        "Robot ne peut pas annuler ni désactiver les alarmes : fais-le dans ton appli d'horloge.")
    val cantCancelTimers = s("cant_cancel_timers", "Robot can't cancel or stop timers: do it in your clock app.",
        "Robot ne peut pas annuler ni arrêter les minuteries : fais-le dans ton appli d'horloge.")
    val openClockApp = s("open_clock_app", "Open your clock app", "Ouvrir ton appli d'horloge")
    val tapOpenClock = s("tap_open_clock", "Tap {open} to open your clock app.", "Touche {open} pour ouvrir ton appli d'horloge.")
    val noClockAlarms = s("no_clock_alarms", "No clock app on this phone takes alarms.", "Aucune appli d'horloge sur ce téléphone n'accepte les alarmes.")
    val opened = s("opened", "Opened {appName}.", "J'ai ouvert {appName}.")
    val didntOpen = s("didnt_open", "{appName} didn't open.", "Impossible d'ouvrir {appName}.")
    val mapsOpened = s("maps_opened", "Opened Organic Maps search.", "J'ai ouvert la recherche d'Organic Maps.")
    val mapsOpenedRoute = s("maps_opened_route", "Opened an Organic Maps search for it: pick the place, then tap Route for directions.",
        "J'ai ouvert une recherche d'Organic Maps : choisis l'endroit, puis touche Itinéraire pour avoir l'itinéraire.")
    val noOrganicMaps = s("no_organic_maps", "Organic Maps isn't installed.", "Organic Maps n'est pas installé.")
    val sentNear = s("sent_near", "Sent to {server}: “{what}” near {lat}, {lon}", "Envoyé à {server} : « {what} » près de {lat}, {lon}")
    val nearestOpened = s("nearest_opened", "Opened the route in Organic Maps to the nearest one OpenStreetMap knows, {far} away in a straight line.",
        "J'ai ouvert l'itinéraire d'Organic Maps vers le plus proche que connaît OpenStreetMap, à {far} à vol d'oiseau.")
    val placeFar = s("place_far", "{place} · {far}", "{place} · {far}")
    val noFlashlight = s("no_flashlight", "This phone has no flashlight.", "Ce téléphone n'a pas de lampe de poche.")
    val flashlightIsOn = s("flashlight_is_on", "Flashlight on.", "Lampe de poche allumée.")
    val flashlightIsOff = s("flashlight_is_off", "Flashlight off.", "Lampe de poche éteinte.")
    val allowContacts = s("allow_contacts", "Allow Robot to read your contacts in the Robot app.", "Autorise Robot à lire tes contacts dans l'appli Robot.")
    val textsOff = s("texts_off", "Texts are turned off in Robot's settings.", "Les textos sont désactivés dans les réglages de Robot.")
    val installCheogramOrSms = s("install_cheogram_or_sms", "Install Cheogram, or choose the SMS app in Robot's settings.",
        "Installe Cheogram, ou choisis l'appli SMS dans les réglages de Robot.")
    val noSmsApp = s("no_sms_app", "No SMS app is installed.", "Aucune appli SMS n'est installée.")
    val smsNoXmpp = s("sms_no_xmpp", "The SMS app can't text an XMPP address.", "L'appli SMS ne peut pas texter une adresse XMPP.")
    val badXmpp = s("bad_xmpp", "That XMPP address can't be used.", "Cette adresse XMPP ne peut pas être utilisée.")
    val badNumber = s("bad_number", "That phone number couldn't be read.", "Ce numéro de téléphone n'a pas pu être lu.")
    val noOneInPeople = s("no_one_in_people", "No one in {people} matches “{name}”, and Robot can't read your contacts.",
        "Personne dans {people} ne correspond à « {name} », et Robot ne peut pas lire tes contacts.")
    val noNumber = s("no_number", "No matching contact has a phone number.", "Aucun contact correspondant n'a de numéro de téléphone.")
    val noNumberOrXmpp = s("no_number_or_xmpp", "No matching contact has a phone number or XMPP address.",
        "Aucun contact correspondant n'a de numéro de téléphone ni d'adresse XMPP.")
    val noEmailApp = s("no_email_app", "No email app is installed.", "Aucune appli de courriel n'est installée.")
    val noEmailAddress = s("no_email_address", "No matching contact has an email address.", "Aucun contact correspondant n'a d'adresse courriel.")
    val noOneToText = s("no_one_to_text", "Robot didn't find who to text.", "Robot n'a pas trouvé à qui texter.")
    val draftOpened = s("draft_opened", "Opened a draft in {appName}: nothing is sent until Send is pressed there.",
        "J'ai ouvert un brouillon dans {appName} : rien n'est envoyé tant que tu n'as pas touché Envoyer là-bas.")
    val installCheogram = s("install_cheogram", "Install Cheogram.", "Installe Cheogram.")
    val cheogramDraftKept = s("cheogram_draft_kept",
        "Opened a draft in Cheogram: nothing is sent until Send is pressed there. If that chat already had a draft, the old one stays.",
        "J'ai ouvert un brouillon dans Cheogram : rien n'est envoyé tant que tu n'as pas touché Envoyer là-bas. Si cette conversation avait déjà un brouillon, l'ancien reste.")
    val cheogramDraftAdded = s("cheogram_draft_added",
        "Opened a draft in Cheogram: nothing is sent until Send is pressed there. With several accounts, choose the account first. If that chat already had a draft, the text is added after it.",
        "J'ai ouvert un brouillon dans Cheogram : rien n'est envoyé tant que tu n'as pas touché Envoyer là-bas. Avec plusieurs comptes, choisis d'abord le compte. Si cette conversation avait déjà un brouillon, le texte est ajouté après.")
    val cheogramList = s("cheogram_list",
        "Opened Cheogram's contact list: pick who it goes to, and their chat opens with the message. Nothing is sent until Send is pressed there.",
        "J'ai ouvert la liste de contacts de Cheogram : choisis à qui ça va, et sa conversation s'ouvre avec le message. Rien n'est envoyé tant que tu n'as pas touché Envoyer là-bas.")
    val cheogramShare = s("cheogram_share",
        "Opened Cheogram with the message: choose who it goes to there. Nothing is sent until Send is pressed.",
        "J'ai ouvert Cheogram avec le message : choisis à qui ça va là-bas. Rien n'est envoyé tant que tu n'as pas touché Envoyer.")
    val noOneToEmail = s("no_one_to_email", "Robot didn't find who to email.", "Robot n'a pas trouvé à qui écrire.")
    val foundContacts = plural("found_contacts", "n", Plural(one = "Found {n} contact.", other = "Found {n} contacts."),
        Plural(one = "{n} contact trouvé.", other = "{n} contacts trouvés."))
    val noteNotSaved = s("note_not_saved", "The note couldn't be saved.", "La note n'a pas pu être enregistrée.")
    val savedToNotes = s("saved_to_notes", "Saved to Robot Notes.", "Enregistré dans Notes de Robot.")
    val noNoteMatches = s("no_note_matches", "No note matches “{query}”.", "Aucune note ne correspond à « {query} ».")
    val foundNotes = plural("found_notes", "n", Plural(one = "Found {n} note.", other = "Found {n} notes."),
        Plural(one = "{n} note trouvée.", other = "{n} notes trouvées."))
    val found = s("found", "Found: {titles}.", "Trouvé : {titles}.")
    val allowCalendarWrite = s("allow_calendar_write", "Allow Robot to use your calendar in the Robot app.", "Autorise Robot à utiliser ton agenda dans l'appli Robot.")
    val noCalendarApp = s("no_calendar_app", "No calendar app is installed.", "Aucune appli d'agenda n'est installée.")
    val couldntReadDate = s("couldnt_read_date", "Robot couldn't read the date.", "Robot n'a pas pu lire la date.")
    val addedToCalendar = s("added_to_calendar", "Added to the calendar.", "Ajouté à l'agenda.")
    // 1.1: Robot's own line after an event it added, from its card (what Robot says, never the model's words).
    val eventAdded = s("event_added", "Added to {calendar}: “{title}”, {when}.", "Ajouté à {calendar} : « {title} », {when}.")
    val eventAddedAlert = s("event_added_alert", "Added to {calendar}: “{title}”, {when}, with an alert at {alert}.",
        "Ajouté à {calendar} : « {title} », {when}, avec une alerte à {alert}.")
    val eventAddedNoAlert = s("event_added_no_alert", "Added to {calendar}: “{title}”, {when}. Its alert couldn't be set: add one in your calendar app.",
        "Ajouté à {calendar} : « {title} », {when}. Son alerte n'a pas pu être réglée : ajoute-la dans ton appli d'agenda.")
    val calendarAppOpenedAlert = s("calendar_app_opened_alert", "Opened the calendar app with the event: add the alert there, then save it.",
        "J'ai ouvert l'appli d'agenda avec l'événement : ajoute l'alerte là-bas, puis enregistre-le.")
    val calendarAppOpened = s("calendar_app_opened", "Opened the calendar app with the event: it is added only when saved there.",
        "J'ai ouvert l'appli d'agenda avec l'événement : il est ajouté seulement quand tu l'enregistres là-bas.")
    val allowCalendarRead = s("allow_calendar_read", "Allow Robot to read your calendar in the Robot app.", "Autorise Robot à lire ton agenda dans l'appli Robot.")
    val couldntReadDay = s("couldnt_read_day", "Robot couldn't read the day.", "Robot n'a pas pu lire le jour.")
    val weatherNeedsLocation = s("weather_needs_location",
        "To check the weather where you are, allow location for Robot in the Robot app, or set a Home for weather in Robot's settings. You can also name a town.",
        "Pour vérifier la météo là où tu es, autorise la localisation pour Robot dans l'appli Robot, ou choisis un domicile pour la météo dans les réglages de Robot. Tu peux aussi nommer une ville.")
    val weatherPlaceNotSent = s("weather_place_not_sent", "That place looked like it contained personal details, so Robot didn't send it to Open-Meteo. Name a town instead.",
        "Ce lieu semblait contenir des renseignements personnels, alors Robot ne l'a pas envoyé à Open-Meteo. Nomme plutôt une ville.")
    val forecastHere = s("forecast_here", "Forecast for where you are, from Open-Meteo.", "Prévisions pour là où tu es, d'Open-Meteo.")
    val forecastHome = s("forecast_home", "Forecast for your Home for weather, from Open-Meteo.", "Prévisions pour ton domicile, d'Open-Meteo.")
    val forecastHomeNoFix = s("forecast_home_no_fix", "Robot couldn't tell where you are: forecast for your Home for weather, from Open-Meteo.",
        "Robot n'a pas pu savoir où tu es : prévisions pour ton domicile, d'Open-Meteo.")
    val forecastNamed = s("forecast_named", "Forecast from Open-Meteo.", "Prévisions d'Open-Meteo.")
    val whereYouAre = s("where_you_are", "Where you are", "Là où tu es")
    val sentToOpenMeteoNamed = s("sent_to_open_meteo_named", "Sent to Open-Meteo: “{place}” (to find it), then {coords}",
        "Envoyé à Open-Meteo : « {place} » (pour le trouver), puis {coords}")
    val sentToOpenMeteo = s("sent_to_open_meteo", "Sent to Open-Meteo: {coords}", "Envoyé à Open-Meteo : {coords}")
    val weatherSpoken = s("weather_spoken", "{place}. {days}", "{place}. {days}")
    val couldntReadPhone = s("couldnt_read_phone", "Robot couldn't read that on this phone.", "Robot n'a pas pu lire ça sur ce téléphone.")
    val readOnPhone = s("read_on_phone", "Read on this phone; nothing left it.", "Lu sur ce téléphone; rien n'en est sorti.")
    val cantStopItself = s("cant_stop_itself", "Robot can't stop itself. Say “free up memory” to unload its model.",
        "Robot ne peut pas s'arrêter lui-même. Dis « libère de la mémoire » pour décharger son modèle.")
    val usageAccessOpened = s("usage_access_opened", "Opened Usage access: turn it on for Robot, then ask again to see the apps you used lately.",
        "J'ai ouvert l'accès aux données d'utilisation : active-le pour Robot, puis redemande pour voir les applis que tu as utilisées récemment.")
    val usageAccessDidntOpen = s("usage_access_didnt_open", "Android's Usage access page didn't open.",
        "La page d'accès aux données d'utilisation d'Android ne s'est pas ouverte.")
    val appInfoForceStop = s("app_info_force_stop", "Opened {appName}'s App info: tap Force stop there. It may start again later.",
        "J'ai ouvert Infos sur l'appli de {appName} : touche Forcer l'arrêt là-bas. L'appli pourrait redémarrer plus tard.")
    val appInfoDidntOpen = s("app_info_didnt_open", "{appName}'s App info didn't open.", "Impossible d'ouvrir Infos sur l'appli de {appName}.")
    val turnOnUsageAccessForApps = s("turn_on_usage_access_for_apps",
        "Turn on Usage access for Robot ({page} in the Robot app) so it can list the apps using the most space.",
        "Active l'accès aux données d'utilisation pour Robot ({page} dans l'appli Robot) pour qu'il puisse lister les applis qui prennent le plus d'espace.")
    val couldntReadAppSizes = s("couldnt_read_app_sizes", "Robot couldn't read the apps' sizes.", "Robot n'a pas pu lire la taille des applis.")
    val allowMedia = s("allow_media", "Allow photos and videos for Robot in the Robot app, so it can find the largest ones.",
        "Autorise les photos et vidéos pour Robot dans l'appli Robot, pour qu'il puisse trouver les plus grosses.")
    val mediaPartial = s("media_partial", "Robot sees only the photos and videos you picked, and none of them is there.",
        "Robot voit seulement les photos et vidéos que tu as choisies, et aucune ne fait l'affaire.")
    val noMedia = s("no_media", "No photos or videos found.", "Aucune photo ni vidéo trouvée.")
    val freeUpOpened = s("free_up_opened", "Opened Android's Free up space screen.", "J'ai ouvert l'écran Libérer de l'espace d'Android.")
    val freeUpDidntOpen = s("free_up_didnt_open", "Android's Free up space screen didn't open.", "L'écran Libérer de l'espace d'Android ne s'est pas ouvert.")
    val cacheCleared = s("cache_cleared", "Cleared {size} of Robot's cache.", "J'ai vidé {size} du cache de Robot.")
    val appInfoClearCache = s("app_info_clear_cache", "Opened {appName}'s App info: tap Storage and cache, then Clear cache.",
        "J'ai ouvert Infos sur l'appli de {appName} : touche Stockage et cache, puis Vider le cache.")
    val appPageDidntOpen = s("app_page_didnt_open", "The app's info page didn't open.", "La page d'infos de l'appli ne s'est pas ouverte.")
    val deleteDidntOpen = s("delete_didnt_open", "Android's delete confirmation didn't open.", "La confirmation de suppression d'Android ne s'est pas ouverte.")
    val appDidntOpen = s("app_didnt_open", "{appName} didn't open.", "{appName} ne s'est pas ouvert.")
    val appOpened = s("app_opened", "Opened {appName}.", "J'ai ouvert {appName}.")
    val appPaste = s("app_paste", "Opened {appName}. Paste your question in {appName}: it's on the clipboard.",
        "J'ai ouvert {appName}. Colle ta question dans {appName} : elle est dans le presse-papiers.")
    val appWithQuestion = s("app_with_question", "Opened {appName} with your question: press Send there if it isn't sent. It's on the clipboard too.",
        "J'ai ouvert {appName} avec ta question : touche Envoyer là-bas si elle n'est pas envoyée. Elle est aussi dans le presse-papiers.")

    // CallPlacer: a call's card.
    val noOneToCall = s("no_one_to_call", "Robot didn't find who to call.", "Robot n'a pas trouvé qui appeler.")
    val noPhoneApp = s("no_phone_app", "No phone app opened.", "Aucune appli de téléphone ne s'est ouverte.")
    val dialerWithNumber = s("dialer_with_number", "The dialer is open with the number: tap Call.", "Le composeur est ouvert avec le numéro : touche Appeler.")
    val dialerCovered = s("dialer_covered", "The phone lock covers the dialer: tap Emergency call on its screen.", "Le blocage couvre le composeur : touche Appel d’urgence sur son écran.")
    val calling = s("calling", "Calling.", "Appel en cours.")
    val dialerOpen = s("dialer_open", "The dialer is open: tap Call.", "Le composeur est ouvert : touche Appeler.")

    // WebTool and BrowserSearchTool: a web search's card, and a search in your browser.
    val sentTo = s("sent_to", "Sent to {who}: “{query}”", "Envoyé à {who} : « {query} »")
    val noWebAnswer = s("no_web_answer", "Robot's model couldn't answer from the web results. Please try again.",
        "Le modèle de Robot n'a pas pu répondre à partir des résultats web. Réessaie.")
    val browserOpened = s("browser_opened", "Opened {engine} in your browser.", "J'ai ouvert {engine} dans ton navigateur.")
    val noBrowser = s("no_browser", "No browser opened the search.", "Aucun navigateur n'a ouvert la recherche.")
}
