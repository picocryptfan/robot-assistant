// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP4a's catalog: routines (core Routine.kt, RoutineGate.kt, RoutineRouter.kt's replies and cards, RoutineRuntime.kt) and
// what the panel says about them (assistant/routines PanelRoutines), and the wake phrases' words (WakePhrases.kt)
//. Ids start with "routine."; the area is "routines". Québec French (tu), the glossary's words
// (robot/l10n/glossary.tsv: « Réglages », « appli », « alarme » on screens, « résumé du matin »); render() adds the French
// spaces. A routine's name, your sentence, a reminder and an app's name are arguments, never translated.

object RoutineStrings : Strings("routine", area = "routines") {
    // Where a routine speaks, and what goes online (Routine.kt).
    val headsetOnly = s("headset_only", "Headset only", "Écouteurs seulement")
    val headsetOrSpeaker = s("headset_or_speaker", "Headset, else the phone speaker", "Écouteurs, sinon le haut-parleur du téléphone")
    val silent = s("silent", "Don't speak (notification only)", "Ne pas parler (notification seulement)")
    val openMeteo = s("open_meteo", "Open-Meteo (the weather)", "Open-Meteo (la météo)")
    val nominatim = s("nominatim", "OpenStreetMap's Nominatim (the nearest place)", "Nominatim d'OpenStreetMap (le lieu le plus proche)")

    // The routine-only steps.
    val stepBrief = s("step_brief", "Read your morning brief", "Lire ton résumé du matin")
    val stepSay = s("step_say", "Say “{text}”", "Dire « {text} »")
    val stepReminders = s("step_reminders", "Read your brief reminders", "Lire les rappels de ton résumé")
    val stepMessages = s("step_messages", "Say how many unread messages you have", "Dire combien de messages non lus tu as")

    // What can't be saved (RoutineCheck).
    val noId = s("no_id", "The routine has no valid id.", "La routine n'a pas d'identifiant valide.")
    val noName = s("no_name", "Give the routine a name.", "Donne un nom à la routine.")
    val nameTooLong = s("name_too_long", "A routine's name is at most {max} characters.", "Le nom d'une routine a au plus {max} caractères.")
    val nameOneLine = s("name_one_line", "A routine's name is one line of text.", "Le nom d'une routine tient sur une ligne.")
    val noSteps = s("no_steps", "Add at least one step.", "Ajoute au moins une étape.")
    val tooManySteps = s("too_many_steps", "A routine has at most {max} steps.", "Une routine a au plus {max} étapes.")
    val briefOnce = s("brief_once", "The morning brief can be in a routine once.", "Le résumé du matin ne peut être qu'une fois dans une routine.")
    val cantDo = s("cant_do", "Routines can't do this: {step}.", "Les routines ne peuvent pas faire ceci : {step}.")
    val sayWhat = s("say_what", "Say what Robot should say.", "Indique ce que Robot doit dire.")
    val sayTooLong = s("say_too_long", "A sentence to say is at most {max} characters.", "Une phrase à dire a au plus {max} caractères.")
    val sayOneLine = s("say_one_line", "A sentence to say is one line of text.", "Une phrase à dire tient sur une ligne.")
    val stepUnusable = s("step_unusable", "A step's details can't be used: {step}.", "Les détails d'une étape ne peuvent pas servir : {step}.")
    val pickDaysOrDate = s("pick_days_or_date", "Pick the days, or a date.", "Choisis les jours, ou une date.")
    val wholeMinutes = s("whole_minutes", "A routine's time is in whole minutes.", "L'heure d'une routine est en minutes entières.")
    val notBoth = s("not_both", "Pick the days or a date, not both.", "Choisis les jours ou une date, pas les deux.")
    val timeHasDays = s("time_has_days", "A routine at a time has its days already.", "Une routine à une heure précise a déjà ses jours.")
    val alarmHoursSame = s("alarm_hours_same", "The alarm's hours can't start and end at the same time.",
        "Les heures de l'alarme ne peuvent pas commencer et finir au même moment.")
    val deviceAgain = s("device_again", "Pick the device again.", "Choisis l'appareil de nouveau.")
    val hoursSame = s("hours_same", "The hours can't start and end at the same time.", "Les heures ne peuvent pas commencer et finir au même moment.")
    val pickADay = s("pick_a_day", "Pick at least one day.", "Choisis au moins un jour.")

    // A step's class (RoutineGate), the brief on the lock screen.
    val runsByItself = s("runs_by_itself", "runs by itself", "se fait seule")
    val waitsForYou = s("waits_for_you", "waits for you", "t'attend")
    val notOffered = s("not_offered", "not offered", "pas offerte")
    val lockTimeWeather = s("lock_time_weather", "Time and weather", "L'heure et la météo")
    val lockEverything = s("lock_everything", "Everything", "Tout")
    val lockNothing = s("lock_nothing", "Nothing until I unlock", "Rien avant que je déverrouille")

    // Ready cards and the run's summary (RoutineGate).
    val readyMissed = s("ready_missed", "Your “{name}” routine didn't run at {time}.", "Ta routine « {name} » ne s'est pas faite à {time}.")
    val readyStep = s("ready_step", "Your “{name}” routine is ready to: {step}.", "Ta routine « {name} » est prête à faire ceci : {step}.")
    val tapToRunNow = s("tap_to_run_now", "Tap to run it now.", "Touche pour la lancer maintenant.")
    val tapToDoIt = s("tap_to_do_it", "Tap to do it.", "Touche pour le faire.")
    val tapToDoItNow = s("tap_to_do_it_now", "Tap to do it now", "Touche pour le faire maintenant")
    val readyCount = plural("ready_count", "n", Plural(one = "Robot has {n} thing ready for you", other = "Robot has {n} things ready for you"),
        Plural(one = "Robot a {n} chose prête pour toi", other = "Robot a {n} choses prêtes pour toi"))
    val readyCountSentence = plural("ready_count_sentence", "n",
        Plural(one = "Robot has {n} thing ready for you.", other = "Robot has {n} things ready for you."),
        Plural(one = "Robot a {n} chose prête pour toi.", other = "Robot a {n} choses prêtes pour toi."))
    val ran = s("ran", "Ran “{name}”", "Routine « {name} » faite")
    val ranSummary = s("ran_summary", "Ran “{name}” · {counts}", "Routine « {name} » faite · {counts}")
    val nDone = plural("n_done", "n", Plural(one = "{n} done", other = "{n} done"), Plural(one = "{n} faite", other = "{n} faites"))
    val nReady = plural("n_ready", "n", Plural(one = "{n} ready", other = "{n} ready"), Plural(one = "{n} prête", other = "{n} prêtes"))
    val nWaitingUnlock = plural("n_waiting_unlock", "n", Plural(one = "{n} waiting for unlock", other = "{n} waiting for unlock"),
        Plural(one = "{n} en attente du déverrouillage", other = "{n} en attente du déverrouillage"))
    val nFailed = plural("n_failed", "n", Plural(one = "{n} didn't work", other = "{n} didn't work"),
        Plural(one = "{n} n'a pas fonctionné", other = "{n} n'ont pas fonctionné"))
    val nWaitedUnlock = plural("n_waited_unlock", "n", Plural(one = "{n} waited for the unlock", other = "{n} waited for the unlock"),
        Plural(one = "{n} a attendu le déverrouillage", other = "{n} ont attendu le déverrouillage"))

    // When a routine runs (RoutineCards): the days, the trigger, its conditions.
    val everyDay = s("every_day", "every day", "tous les jours")
    val everyWeekday = s("every_weekday", "every weekday", "en semaine")
    val onWeekends = s("on_weekends", "on weekends", "la fin de semaine")
    val everyDays = s("every_days", "every {days}", "chaque {days}")
    val monday = s("monday", "Monday", "lundi")
    val tuesday = s("tuesday", "Tuesday", "mardi")
    val wednesday = s("wednesday", "Wednesday", "mercredi")
    val thursday = s("thursday", "Thursday", "jeudi")
    val friday = s("friday", "Friday", "vendredi")
    val saturday = s("saturday", "Saturday", "samedi")
    val sunday = s("sunday", "Sunday", "dimanche")
    val daysAt = s("days_at", "{days} at {time}", "{days} à {time}")
    val onDateAt = s("on_date_at", "on {date} at {time}", "le {date} à {time}")
    val alarmBetween = s("alarm_between", "when you turn off an alarm between {from} and {to}", "quand tu éteins une alarme entre {from} et {to}")
    val firstUnlock = s("first_unlock", "the first time you unlock after {time}", "la première fois que tu déverrouilles après {time}")
    val headphonesConnect = s("headphones_connect", "when your headphones connect", "quand tes écouteurs se connectent")
    val headphonesDisconnect = s("headphones_disconnect", "when your headphones disconnect", "quand tes écouteurs se déconnectent")
    val carConnects = s("car_connects", "when your car connects", "quand ton auto se connecte")
    val carDisconnects = s("car_disconnects", "when your car disconnects", "quand ton auto se déconnecte")
    val chargingStarts = s("charging_starts", "when charging starts", "quand la recharge commence")
    val chargingStops = s("charging_stops", "when charging stops", "quand la recharge s'arrête")
    val onlyAsked = s("only_asked", "only when you ask", "seulement quand tu le demandes")
    val between = s("between", "between {from} and {to}", "entre {from} et {to}")
    val oncePerDay = s("once_per_day", "at most once a day", "au plus une fois par jour")
    /** Two items of a list joined by a semicolon (French puts its narrow space before it). */
    val semicolon = s("semicolon", "{a}; {b}", "{a}; {b}")
    val quoted = s("quoted", "“{name}”", "« {name} »")

    // The routine's card (RoutineCards.card): the same by voice and in Settings.
    val saveTitle = s("save_title", "Save the routine “{name}”?", "Enregistrer la routine « {name} » ?")
    val cardWhen = s("card_when", "When: {when}", "Quand : {when}")
    val cardStep = s("card_step", "{i}. {step} · {badge}", "{i}. {step} · {badge}")
    val badgeOnline = s("badge_online", "{badge} · goes online: {service}", "{badge} · va en ligne : {service}")
    val cardSpeaks = s("card_speaks", "Speaks: {output}", "Parle : {output}")
    val nothingOnline = s("nothing_online", "Nothing goes online.", "Rien ne va en ligne.")
    val onlineByItself = s("online_by_itself", "Online by itself: {services}. Nothing about you is sent.",
        "En ligne d'elle-même : {services}. Rien sur toi n'est envoyé.")
    val notUnderstood = s("not_understood", "I didn't understand “{words}”: edit it in {page}.", "Je n'ai pas compris « {words} » : modifie-la dans {page}.")
    val leftOut = s("left_out", "Routines can't free memory or storage, delete things or ask a helper: “{words}” was left out.",
        "Les routines ne peuvent pas libérer de mémoire ou d'espace, supprimer des choses ni demander à une IA externe : « {words} » a été laissé de côté.")
    val readAs = s("read_as", "Read “{said}” as {time}; change it in {page} if that's wrong.",
        "J'ai compris « {said} » comme {time}; change-le dans {page} si c'est faux.")
    val editToSave = s("edit_to_save", "Edit it in {page} to save it.", "Modifie-la dans {page} pour l'enregistrer.")
    val tapSaveFromScreen = s("tap_save_from_screen", "Tap {save}: this routine came from the screen.", "Touche {save} : cette routine vient de l'écran.")
    val sayYesOrTapSave = s("say_yes_or_tap_save", "Say yes or tap {save}.", "Dis oui ou touche {save}.")

    // "List my routines".
    val listNone = s("list_none", "You have no routines yet. Say, for example: every weekday at 7, tell me the weather and my calendar.",
        "Tu n'as pas encore de routine. Dis par exemple : en semaine à 7 h, dis-moi la météo et mon agenda.")
    val list = plural("list", "n", Plural(one = "You have {n} routine: {list}.", other = "You have {n} routines: {list}."),
        Plural(one = "Tu as {n} routine : {list}.", other = "Tu as {n} routines : {list}."))
    val listItem = s("list_item", "{name}, {when}", "{name}, {when}")
    val listItemOff = s("list_item_off", "{name}, {when} (off)", "{name}, {when} (désactivée)")

    // A default name for a routine made by voice, in the language it was said in.
    val nameMorning = s("name_morning", "Morning routine", "Routine du matin")
    val nameAfternoon = s("name_afternoon", "Afternoon routine", "Routine de l'après-midi")
    val nameEvening = s("name_evening", "Evening routine", "Routine du soir")
    val nameAfterAlarm = s("name_after_alarm", "After my alarm", "Après mon cadran")
    val nameFirstUnlock = s("name_first_unlock", "First unlock", "Premier déverrouillage")
    val nameHeadphonesOn = s("name_headphones_on", "Headphones on", "Écouteurs branchés")
    val nameHeadphonesOff = s("name_headphones_off", "Headphones off", "Écouteurs débranchés")
    val nameInCar = s("name_in_car", "In the car", "Dans l'auto")
    val nameOutOfCar = s("name_out_of_car", "Out of the car", "Sortie de l'auto")
    val nameCharging = s("name_charging", "Charging", "Recharge")
    val nameUnplugged = s("name_unplugged", "Unplugged", "Débranché")
    val nameMine = s("name_mine", "My routine", "Ma routine")

    // The locked phone (RoutineLockedPhone).
    val unlockChange = s("unlock_change", "Unlock first: routines are only changed with the phone unlocked.",
        "Déverrouille d'abord : les routines ne se changent que téléphone déverrouillé.")
    val unlockList = s("unlock_list", "Unlock first: your routines are only listed with the phone unlocked.",
        "Déverrouille d'abord : la liste de tes routines n'est donnée que téléphone déverrouillé.")
    val unlockRemind = s("unlock_remind", "Unlock first: reminders for your brief are only added with the phone unlocked.",
        "Déverrouille d'abord : les rappels de ton résumé ne s'ajoutent que téléphone déverrouillé.")
    // 1.1: a dated reminder (an event with an alert) on the locked phone.
    val unlockRemindOn = s("unlock_remind_on", "Unlock first: reminders only go in your calendar with the phone unlocked.",
        "Déverrouille d'abord : les rappels ne s'ajoutent à ton agenda que téléphone déverrouillé.")
    val remindOnNotAdded = s("remind_on_not_added", "Nothing was added to your calendar.", "Rien n'a été ajouté à ton agenda.")
    /** 1.1: "remind me about it" with nothing to remind and no time (RoutineCommand.RemindWhat). */
    val remindWhat = s("remind_what", "What should I remind you about, and when?", "Qu'est-ce que je dois te rappeler, et quand?")

    // The built-in brief, its choices of when, a run's last lines (RoutineRuntime).
    val morningBrief = s("morning_brief", "Morning brief", "Résumé du matin")
    val whenAlarm = s("when_alarm", "When I turn off my alarm", "Quand j'éteins mon alarme")
    val whenTime = s("when_time", "At a time", "À une heure précise")
    val whenUnlock = s("when_unlock", "At the first unlock", "Au premier déverrouillage")
    val whenAsked = s("when_asked", "Only when I ask", "Seulement quand je le demande")
    val skippedNoConsent = s("skipped_no_consent", "Weather skipped: this routine's card didn't list it.",
        "Météo sautée : la carte de cette routine ne l'indiquait pas.")
    val readyOnPhone = plural("ready_on_phone", "n", Plural(one = "{n} thing is ready for you on the phone.", other = "{n} things are ready for you on the phone."),
        Plural(one = "{n} chose t'attend sur le téléphone.", other = "{n} choses t'attendent sur le téléphone."))
    val unlockRest = s("unlock_rest", "Unlock to hear the rest.", "Déverrouille pour entendre le reste.")

    // The editor's steps (StepChoice): titles, the field each asks for, its hint, what is wrong with it.
    val chooseSay = s("choose_say", "Say a sentence", "Dire une phrase")
    val fieldSay = s("field_say", "What Robot says", "Ce que Robot dit")
    val hintSay = s("hint_say", "One line, up to {max} characters.", "Une ligne, jusqu'à {max} caractères.")
    val chooseWeather = s("choose_weather", "Tell the weather here today", "Dire la météo d'ici aujourd'hui")
    val chooseCalendar = s("choose_calendar", "Read today's calendar", "Lire l'agenda d'aujourd'hui")
    val choosePhone = s("choose_phone", "Say the phone's status", "Dire l'état du téléphone")
    val chooseNotes = s("choose_notes", "Find notes", "Trouver des notes")
    val fieldNotes = s("field_notes", "Words to look for", "Mots à chercher")
    val chooseTimer = s("choose_timer", "Set a timer", "Régler une minuterie")
    val fieldTimer = s("field_timer", "Minutes", "Minutes")
    val hintTimer = s("hint_timer", "1 to 1440.", "De 1 à 1440.")
    val chooseAlarm = s("choose_alarm", "Set an alarm", "Régler une alarme")
    val fieldAlarm = s("field_alarm", "Time", "Heure")
    val hintAlarm = s("hint_alarm", "As 06:30, on a 24-hour clock.", "Comme 6 h 30, sur 24 heures.")
    val chooseFlashOn = s("choose_flash_on", "Turn the flashlight on", "Allumer la lampe de poche")
    val chooseFlashOff = s("choose_flash_off", "Turn the flashlight off", "Éteindre la lampe de poche")
    val chooseNote = s("choose_note", "Add a note", "Ajouter une note")
    val fieldNote = s("field_note", "The note's text", "Le texte de la note")
    val chooseOpenApp = s("choose_open_app", "Open an app", "Ouvrir une appli")
    val fieldOpenApp = s("field_open_app", "The app's name", "Le nom de l'appli")
    val hintReady = s("hint_ready", "It waits for you: a Ready card you tap.", "Elle t'attend : une carte Prêt à toucher.")
    val chooseMapsSearch = s("choose_maps_search", "Search Organic Maps", "Chercher dans Organic Maps")
    val fieldMapsSearch = s("field_maps_search", "What to search for", "Quoi chercher")
    val chooseMapsNavigate = s("choose_maps_navigate", "Directions in Organic Maps", "Itinéraire dans Organic Maps")
    val fieldMapsNavigate = s("field_maps_navigate", "Where to", "Vers où")
    val typeField = s("type_field", "Type {what}.", "Écris {what}.")
    val something = s("something", "something", "quelque chose")
    val problemTimer = s("problem_timer", "A number of minutes, 1 to 1440.", "Un nombre de minutes, de 1 à 1440.")
    val problemAlarm = s("problem_alarm", "A time such as 06:30.", "Une heure comme 6 h 30.")
    val problemUnusable = s("problem_unusable", "Robot can't use that.", "Robot ne peut pas utiliser ça.")

    // Settings' and the notifications' words (RoutineTexts).
    val summaryBriefOn = s("summary_brief_on", "Morning brief on · {routines}", "Résumé du matin activé · {routines}")
    val summaryBriefOff = s("summary_brief_off", "Morning brief off · {routines}", "Résumé du matin désactivé · {routines}")
    val noRoutines = s("no_routines", "no routines", "aucune routine")
    val routineCount = plural("routine_count", "n", Plural(one = "{n} routine", other = "{n} routines"), Plural(one = "{n} routine", other = "{n} routines"))
    val steps = plural("steps", "n", Plural(one = "{n} step", other = "{n} steps"), Plural(one = "{n} étape", other = "{n} étapes"))
    val row = s("row", "{when} · {steps}", "{when} · {steps}")
    val rowOff = s("row_off", "{when} · {steps} · off", "{when} · {steps} · désactivée")
    val notRunYet = s("not_run_yet", "Not run yet", "Pas encore lancée")
    val lastRun = s("last_run", "Last run: {run}", "Dernière fois : {run}")
    val run = s("run", "{day} {time} · {kind} · {counts}", "{day} {time} · {kind} · {counts}")
    val today = s("today", "today", "aujourd'hui")
    val yesterday = s("yesterday", "yesterday", "hier")
    val kindTime = s("kind_time", "a time", "une heure précise")
    val kindAlarm = s("kind_alarm", "an alarm turned off", "une alarme éteinte")
    val kindUnlock = s("kind_unlock", "the first unlock", "le premier déverrouillage")
    val kindDevice = s("kind_device", "a headset or car", "des écouteurs ou une auto")
    val kindCharging = s("kind_charging", "charging", "la recharge")
    val kindAsked = s("kind_asked", "you asked", "tu l'as demandé")
    val readingBrief = s("reading_brief", "Robot is reading your morning brief", "Robot lit ton résumé du matin")
    val running = s("running", "Robot is running “{name}”", "Robot fait « {name} »")
    val speaking = s("speaking", "Robot is speaking", "Robot parle")
    val briefReady = s("brief_ready", "Morning brief ready", "Résumé du matin prêt")
    val isReady = s("is_ready", "“{name}” is ready", "« {name} » est prête")
    val tapPlay = s("tap_play", "Tap {play} to hear it.", "Touche {play} pour l'entendre.")
    val continueBrief = s("continue_brief", "Continue your brief", "Continuer ton résumé")
    val continueText = s("continue_text", "Unlocked: tap to hear the rest.", "Déverrouillé : touche pour entendre le reste.")
    val briefIsReady = s("brief_is_ready", "Your morning brief is ready", "Ton résumé du matin est prêt")
    val routineIsReady = s("routine_is_ready", "Your “{name}” routine is ready", "Ta routine « {name} » est prête")
    val notAssistant = s("not_assistant", "Robot isn't your digital assistant right now, so it can't speak. Open Robot to set it up.",
        "Robot n'est pas ton assistant numérique en ce moment, alors il ne peut pas parler. Ouvre Robot pour le configurer.")
    val needsAssistant = s("needs_assistant", "Routines need Robot as the digital assistant.", "Les routines ont besoin de Robot comme assistant numérique.")
    val notLocked = s("not_locked", "Routines don't run while the phone is locked: they wait for the unlock.",
        "Les routines ne se font pas quand le téléphone est verrouillé : elles attendent le déverrouillage.")
    val damaged = s("damaged", "Robot can't read your routines file. Nothing runs until you start over; the file is kept aside.",
        "Robot ne peut pas lire ton fichier de routines. Rien ne se fait tant que tu ne recommences pas; le fichier est mis de côté.")
    val tooNew = s("too_new", "Your routines were saved by a newer Robot. Update Robot to use them.",
        "Tes routines ont été enregistrées par un Robot plus récent. Mets Robot à jour pour les utiliser.")

    // What the panel says in a routine turn (assistant/routines PanelRoutines), in the turn's reply language.
    val couldntMake = s("couldnt_make", "I couldn't make that routine as you said it: edit it in Robot › {page}.",
        "Je n'ai pas pu faire cette routine comme tu l'as dite : modifie-la dans Robot › {page}.")
    val heresRoutine = s("heres_routine", "Here's the routine. {footer}", "Voici la routine. {footer}")
    /** 1.2: a routine made by voice to start after an alarm, on a phone without GrapheneOS Clock (core ClockNeed). */
    val clockMissing = s("clock_missing",
        "This phone doesn't have the GrapheneOS Clock app, so turning off an alarm won't start this routine. Support for other clock apps is planned.",
        "Ce téléphone n'a pas l'appli Horloge de GrapheneOS : arrêter une alarme ne lancera donc pas cette routine. La prise en charge " +
            "d'autres applis d'horloge est prévue.")
    val heresTapSave = s("heres_tap_save", "Here's the routine. Tap {save} to keep it.", "Voici la routine. Touche {save} pour la garder.")
    val heresSayYes = s("heres_say_yes", "Here's the routine. Say yes to save it.", "Voici la routine. Dis oui pour l'enregistrer.")
    val notSaved = s("not_saved", "Not saved.", "Pas enregistrée.")
    val couldntSave = s("couldnt_save", "Robot couldn't save it. {page} says why.", "Robot n'a pas pu l'enregistrer. {page} dit pourquoi.")
    val savedTitle = s("saved_title", "Saved “{name}”", "« {name} » enregistrée")
    val itRuns = s("it_runs", "It runs {when}.", "Elle se fait {when}.")
    val saved = s("saved", "Saved.", "Enregistrée.")
    val noneYet = s("none_yet", "none yet", "aucune pour l'instant")
    val which = s("which", "Which routine? Yours: {names}.", "Quelle routine ? Les tiennes : {names}.")
    val noneCalled = s("none_called", "You have no routine called “{name}”. Yours: {names}.",
        "Tu n'as pas de routine appelée « {name} ». Les tiennes : {names}.")
    val runningNow = s("running_now", "Running “{name}”.", "Je lance « {name} ».")
    val offIn = s("off_in", "Off in {page}: {step}.", "Désactivé dans {page} : {step}.")
    val unlockFor = s("unlock_for", "Unlock your phone for: {step}.", "Déverrouille ton téléphone pour : {step}.")
    val noReminders = s("no_reminders", "No brief reminders today.", "Aucun rappel pour ton résumé aujourd'hui.")
    val briefBuiltIn = s("brief_built_in", "The morning brief is built in: turn it off in Robot › {page}.",
        "Le résumé du matin est intégré : désactive-le dans Robot › {page}.")
    val tapDelete = s("tap_delete", "Tap {delete} to delete it.", "Touche {delete} pour la supprimer.")
    val tapDeleteOnCard = s("tap_delete_on_card", "Tap {delete} on the card to delete it.", "Touche {delete} sur la carte pour la supprimer.")
    val deleteTitle = s("delete_title", "Delete the routine “{name}”?", "Supprimer la routine « {name} » ?")
    val kept = s("kept", "Kept.", "Gardée.")
    val deletedTitle = s("deleted_title", "Deleted “{name}”", "« {name} » supprimée")
    val couldntChange = s("couldnt_change", "Robot couldn't change the file.", "Robot n'a pas pu modifier le fichier.")
    val couldntKeepReminder = s("couldnt_keep_reminder", "Robot couldn't keep that reminder.", "Robot n'a pas pu garder ce rappel.")
    val remindThisMorning = s("remind_this_morning", "I'll remind you in this morning's brief: {text}.", "C'est noté pour ton résumé de ce matin : {text}.")
    val remindTomorrow = s("remind_tomorrow", "I'll remind you in tomorrow's brief: {text}.", "C'est noté pour ton résumé de demain matin : {text}.")
    val turnOnBrief = s("turn_on_brief", "Turn on the morning brief in Robot › {page} to hear it.",
        "Active le résumé du matin dans Robot › {page} pour l'entendre.")
    val runLabel = s("run_label", "Run “{name}”", "Lancer « {name} »")
    val readyForYou = s("ready_for_you", "Ready for you", "Prêt pour toi")
    val nothingWaiting = s("nothing_waiting", "Nothing is waiting for you.", "Rien ne t'attend.")
    val unlockForStays = s("unlock_for_stays", "Unlock your phone for: {step}. It stays ready until the end of the day.",
        "Déverrouille ton téléphone pour : {step}. Ça reste prêt jusqu'à la fin de la journée.")
    val runIt = s("run_it", "Run it", "Lancer")
    // Running a routine that's off: its card, which only a tap answers.
    val offTitle = s("off_title", "Run “{name}” this once?", "Lancer « {name} » cette fois-ci ?")
    val offInPage = s("off_in_page", "It's off in {page}.", "Elle est désactivée dans {page}.")
    val offTapRun = s("off_tap_run", "“{name}” is off. Its steps are on the card: tap {run} to run it this once.",
        "« {name} » est désactivée. Ses étapes sont sur la carte : touche {run} pour la lancer cette fois-ci.")
    val offTapRunOnCard = s("off_tap_run_on_card", "Tap {run} on the card: this routine is off.", "Touche {run} sur la carte : cette routine est désactivée.")
    val offLocked = s("off_locked", "“{name}” is off: unlock to run it once, or turn it on in {page}.",
        "« {name} » est désactivée : déverrouille pour la lancer une fois, ou active-la dans {page}.")
    val notRun = s("not_run", "Not run.", "Pas lancée.")
    val notNow = s("not_now", "Not now", "Pas maintenant")
    val stillWaiting = s("still_waiting", "Still waiting until the end of the day.", "Toujours en attente jusqu'à la fin de la journée.")

    // The wake phrases (WakePhrases.kt; Settings › Voice › Wake words shows them).
    val wakeLow = s("wake_low", "Low", "Faible")
    val wakeMedium = s("wake_medium", "Medium", "Moyenne")
    val wakeHigh = s("wake_high", "High", "Élevée")
    val wakeLowDetail = s("wake_low_detail", "Fewer accidental wakes; say it clearly", "Moins de déclenchements par accident; dis-le clairement")
    val wakeMediumDetail = s("wake_medium_detail", "As “hey robot” was tested: 10 of 10 close, 5 of 5 across the room, no false wake in 2 hours",
        "Comme « hey robot » a été testé : 10 sur 10 de près, 5 sur 5 à l'autre bout de la pièce, aucun faux déclenchement en 2 heures")
    val wakeHighDetail = s("wake_high_detail", "Hears you more easily, wakes by accident more often",
        "T'entend plus facilement, se déclenche par accident plus souvent")
    val interruptLowDetail = s("interrupt_low_detail", "Fewer false stops from Robot's own voice or the TV; say it clearly",
        "Moins de faux arrêts causés par la voix de Robot ou la télé; dis-le clairement")
    val interruptMediumDetail = s("interrupt_medium_detail", "As tested: “stop” over Robot's voice at arm's length",
        "Comme testé : « stop » par-dessus la voix de Robot, à bout de bras")
    val interruptHighDetail = s("interrupt_high_detail", "Hears you over Robot more easily, from further away; stops by accident more often",
        "T'entend plus facilement par-dessus Robot, de plus loin; s'arrête par accident plus souvent")
    val wakeShort = s("wake_short", "Short words like 'robot' trigger by accident more often.", "Les mots courts comme « robot » réveillent Robot par accident plus souvent.")
    val wakeType = s("wake_type", "Type a phrase.", "Écris une phrase.")
    val wakeTooLong = s("wake_too_long", "At most {max} characters.", "Au plus {max} caractères.")
    val wakeLetters = s("wake_letters", "Use only the letters A to Z, spaces and apostrophes.", "N'utilise que les lettres A à Z, des espaces et des apostrophes.")
    val wakeWords = s("wake_words", "Use 1 to {max} words.", "Utilise de 1 à {max} mots.")
    val wakeLetter = s("wake_letter", "Each word needs a letter.", "Chaque mot doit avoir une lettre.")
    val wakeStop = s("wake_stop", "“Stop” is reserved: saying it stops Robot talking.", "« Stop » est réservé : le dire arrête Robot de parler.")
    val wakeTaken = s("wake_taken", "That phrase is already in the list.", "Cette phrase est déjà dans la liste.")
    val wakeTooMany = s("wake_too_many", "At most {max} wake phrases.", "Au plus {max} phrases d'éveil.")
    val wakeOffOne = s("wake_off_one", "“{phrase}” is off, but {names} is on and still hears it. Turn off {names} too.",
        "« {phrase} » est désactivée, mais {names} est activée et l'entend quand même. Désactive {names} aussi.")
    val wakeOffMany = s("wake_off_many", "“{phrase}” is off, but {names} are on and still hear it. Turn them off too.",
        "« {phrase} » est désactivée, mais {names} sont activées et l'entendent quand même. Désactive-les aussi.")
    val wakeAlsoOne = s("wake_also_one", "Also hears {names}: while this is on, saying it wakes Robot even with it off.",
        "Entend aussi {names} : tant que celle-ci est activée, la dire réveille Robot même si elle est désactivée.")
    val wakeAlsoMany = s("wake_also_many", "Also hears {names}: while this is on, saying them wakes Robot even with them off.",
        "Entend aussi {names} : tant que celle-ci est activée, les dire réveille Robot même si elles sont désactivées.")
    val wakeCantSpell = s("wake_cant_spell", "Robot couldn't spell that for the wake-word model.", "Robot n'a pas pu épeler ça pour le modèle du mot d'éveil.")
    val wakeCantSpellToken = s("wake_cant_spell_token", "The wake-word model can't spell “{token}”.", "Le modèle du mot d'éveil ne peut pas épeler « {token} ».")
}
