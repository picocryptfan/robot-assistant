// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP8's catalog: Settings' summaries and enum titles: the choices of Settings.kt (approval levels,
// when Robot speaks, the voices, the texting and email apps, "Find the nearest place online"), Licences.kt's section
// titles, and the one-line states under Settings' rows (ui/settings SettingsRoot, Display, Actions). Ids start with
// "settings."; the area is "settings". Québec French (tu), the glossary's words (robot/l10n/glossary.tsv); render() adds
// the French spaces. The pages' own words are resources (values/strings_settings.xml).

object SettingsStrings : Strings("settings", area = "settings") {
    // Approval: how freely Robot may run an action (Settings › Actions).
    val approvalAuto = s("approval_auto", "Just do it", "Faire sans demander")
    val approvalAsk = s("approval_ask", "Ask first", "Demander d'abord")
    val approvalOff = s("approval_off", "Off", "Désactivé")

    // SpeakReplies: when Robot speaks its replies (Settings › Voice).
    val speakVoice = s("speak_voice", "When I ask by voice", "Quand je demande à voix haute")
    val speakAlways = s("speak_always", "Always", "Toujours")
    val speakNever = s("speak_never", "Never", "Jamais")

    // VoiceChoice: the voice that reads replies. Heart, Siwis and Kitten are the voices' names.
    val voiceHeart = s("voice_heart", "Heart", "Heart")
    val voiceHeartDetail = s("voice_heart_detail", "English, natural (Kokoro)", "Anglais, naturelle (Kokoro)")
    val voiceSiwis = s("voice_siwis", "Siwis", "Siwis")
    // One clause, no closing period: Settings › Voice adds ". Not downloaded: …" after a voice's line.
    val voiceSiwisDetail = s("voice_siwis_detail", "French, natural (Kokoro): it reads French replies with Heart too and, " +
        "picked here, reads English with French pronunciation",
        "Français, naturelle (Kokoro) : elle lit aussi les réponses en français avec Heart et, " +
            "choisie ici, lit l'anglais avec une prononciation française")
    val voiceKitten = s("voice_kitten", "Kitten", "Kitten")
    val voiceKittenDetail = s("voice_kitten_detail", "English, faster and smaller, less natural", "Anglais, plus rapide et plus petite, moins naturelle")
    val voiceSystem = s("voice_system", "Phone's voice", "Voix du téléphone")
    /** 1.2: the phone's voice when Android doesn't name its text-to-speech engine (its name shows otherwise, core SystemVoice). */
    val voiceSystemDetail = s("voice_system_detail", "The phone's text-to-speech engine", "Le moteur de synthèse vocale du téléphone")

    // BargeIn, Playback and StopKey (BargeIn.kt): hearing you while Robot talks, how its voice plays, the key that stops it.
    val bargeEcho = s("barge_echo", "Echo cancellation", "Annulation d'écho")
    val bargeEchoDetail = s("barge_echo_detail",
        "While Robot talks the microphone switches to the phone's call mode with echo cancellation, so “stop” and your wake words get " +
            "through. Back to the best mode for questions when she's done.",
        "Pendant que Robot parle, le micro passe au mode d'appel du téléphone avec annulation d'écho, pour que « stop » et tes mots " +
            "d'éveil passent. Il revient au meilleur mode pour les questions quand Robot a fini.")
    val bargeOff = s("barge_off", "Off", "Désactivé")
    val bargeOffDetail = s("barge_off_detail", "The old way: the microphone hears Robot over you. Tap the conversation or press the volume key to stop her.",
        "Comme avant : le micro entend Robot par-dessus toi. Touche la conversation ou presse la touche de volume pour l'arrêter.")
    val bargeAlways = s("barge_always", "Always echo cancellation", "Toujours l'annulation d'écho")
    val bargeAlwaysDetail = s("barge_always_detail",
        "Call mode with echo cancellation the whole time the panel is open: no switching, questions a little less accurate.",
        "Le mode d'appel avec annulation d'écho tout le temps que le panneau est ouvert : pas de changement de mode, des questions un peu moins précises.")
    val playbackAssistant = s("playback_assistant", "Assistant audio", "Audio de l'assistant")
    val playbackAssistantDetail = s("playback_assistant_detail",
        "As before: the media volume. On Pixel the echo canceller hears everything the speaker plays.",
        "Comme avant : le volume des médias. Sur un Pixel, l'annulation d'écho entend tout ce que joue le haut-parleur.")
    val playbackCall = s("playback_call", "Call audio", "Audio d'appel")
    val playbackCallDetail = s("playback_call_detail",
        "Like a call on the loudspeaker (communication mode), the way calling apps get the phone's echo cancellation. Uses the call volume.",
        "Comme un appel sur haut-parleur (mode communication), comme les applis d'appel obtiennent l'annulation d'écho du téléphone. Utilise le volume d'appel.")
    val stopVolumeDown = s("stop_volume_down", "Volume down", "Volume bas")
    val stopEither = s("stop_either", "Either volume key", "L'une ou l'autre touche de volume")
    val stopOff = s("stop_off", "Off", "Désactivé")

    // TextApp and EmailApp: which app opens with a text or an email (Settings › People, notes and calendar).
    val textCheogram = s("text_cheogram", "Cheogram", "Cheogram")
    val textSms = s("text_sms", "SMS app", "Appli SMS")
    val textOff = s("text_off", "Off", "Désactivé")
    val emailProton = s("email_proton", "Proton Mail", "Proton Mail")
    val emailAsk = s("email_ask", "Ask each time", "Demander chaque fois")

    // NearestOnline: Settings › Actions › Find the nearest place online.
    val nearestAsk = s("nearest_ask", "Ask the first time", "Demander la première fois")
    val nearestAskDetail = s("nearest_ask_detail", "The first “nearest …” shows a card that says what would go online; you choose there.",
        "La première demande « le plus proche … » affiche une carte qui dit ce qui serait envoyé en ligne; tu choisis à ce moment-là.")
    val nearestAlways = s("nearest_always", "Always", "Toujours")
    val nearestAlwaysDetail = s("nearest_always_detail",
        "Robot asks the search server below (OpenStreetMap's Nominatim unless you choose another) for that kind of place around where " +
            "you are (about 1 km) and opens your maps app's route to the closest. It uses the internet.",
        "Robot demande ce genre d'endroit autour de toi (à environ 1 km près) au serveur de recherche ci-dessous (le Nominatim " +
            "d'OpenStreetMap, sauf si tu en choisis un autre) et ouvre l'itinéraire de ton appli de cartes vers le plus proche. Ça utilise Internet.")
    val nearestNever = s("nearest_never", "Never", "Jamais")
    val nearestNeverDetail = s("nearest_never_detail", "Your maps app's search, which ranks places around you. Nothing goes online.",
        "La recherche de ton appli de cartes, qui classe les endroits autour de toi. Rien n'est envoyé en ligne.")

    /** A choice's line in a list when it is the default: "The default. Robot asks …". */
    val theDefault = s("the_default", "The default. {detail}", "Par défaut. {detail}")

    // Settings › Advanced › ADB fixes (core AdbFixes.kt): the page's words before the fixes, each fix and its state.
    val adbHeader = s("adb_header",
        "Some things Android only lets a computer change. Each fix below is done once, with adb. On the phone: {about}, tap {build} " +
            "seven times; then {developer}, turn on {usb} and connect the phone to a computer with adb. Run the commands there, " +
            "then turn {usb} off again.",
        "Android ne laisse qu'un ordinateur changer certaines choses. Chaque correctif ci-dessous se fait une seule fois, avec adb. Sur " +
            "le téléphone : {about}, touche {build} sept fois; puis {developer}, active {usb} et branche le téléphone à un " +
            "ordinateur avec adb. Lance les commandes à cet endroit, puis désactive de nouveau {usb}.")
    val adbRecognizer = s("adb_recognizer", "Stay the assistant after a restart", "Rester l'assistant après un redémarrage")
    val adbRecognizerWhy = s("adb_recognizer_why",
        "At every restart Android keeps a non-system assistant only if a default speech recognizer is set, and GrapheneOS has no screen " +
            "to set one. This sets Robot's.",
        "À chaque redémarrage, Android ne garde un assistant qui ne fait pas partie du système que si une reconnaissance vocale par " +
            "défaut est choisie, et GrapheneOS n'a pas d'écran pour en choisir une. Ce correctif choisit celle de Robot.")
    val adbRepair = s("adb_repair", "Let Robot repair that itself", "Laisser Robot réparer ça lui-même")
    val adbRepairWhy = s("adb_repair_why",
        "Lets Robot set the speech recognizer again if it is ever cleared (Robot changes nothing else). Android allows this permission " +
            "only through adb.",
        "Permet à Robot de choisir de nouveau la reconnaissance vocale si elle est effacée (Robot ne change rien d'autre). Android " +
            "n'accorde cette autorisation que par adb.")
    val adbDone = s("adb_done", "Done", "Fait")
    val adbNotDone = s("adb_not_done", "Not done", "Pas fait")
    val adbNeedsNewer = s("adb_needs_newer", "Needs Robot {version}+", "Nécessite Robot {version}+")
    /** 1.2: the recognizer fix where another installed speech recognizer is set (it would replace it). */
    val adbNotNeeded = s("adb_not_needed", "Not needed", "Pas nécessaire")

    // LicenceWhere: Settings › About › Licences' sections.
    val licencesApp = s("licences_app", "In the app", "Dans l'appli")
    val licencesDownload = s("licences_download", "Downloaded when you ask", "Téléchargé quand tu le demandes")
    val licencesService = s("licences_service", "Online services Robot asks, when you ask", "Services en ligne que Robot consulte, quand tu le demandes")
    val licencesBuild = s("licences_build", "Used only to build and test Robot (not in the app)",
        "Utilisé seulement pour compiler et tester Robot (pas dans l'appli)")

    // Settings' rows: the state under each title.
    val off = s("off", "Off", "Désactivé")
    // Settings › Actions: an action's state under its title ("Ask first · works locked · uses the internet").
    val worksLocked = s("works_locked", "works locked", "fonctionne verrouillé")
    val usesInternet = s("uses_internet", "uses the internet", "utilise Internet")
    val wakeOnNone = s("wake_on_none", "On · no phrase on", "Activé · aucune phrase activée")
    val wakeOnOne = s("wake_on_one", "On · “{phrase}”", "Activé · « {phrase} »")
    val wakeOnMore = plural("wake_on_more", "n",
        Plural(one = "On · “{phrase}” and {n} other", other = "On · “{phrase}” and {n} others"),
        Plural(one = "Activé · « {phrase} » et {n} autre", other = "Activé · « {phrase} » et {n} autres"))
    val voiceWhenYouTalk = s("voice_when_you_talk", "{voice} · speaks when you talk", "{voice} · parle quand tu parles")
    val voiceAlways = s("voice_always", "{voice} · always speaks", "{voice} · parle toujours")
    val voiceNever = s("voice_never", "{voice} · doesn't speak", "{voice} · ne parle pas")
    val actionsNoneAsk = s("actions_none_ask", "None ask first", "Aucune ne demande d'abord")
    val actionsAsk = plural("actions_ask", "n",
        Plural(one = "{n} ask first", other = "{n} ask first"),
        Plural(one = "{n} demande d'abord", other = "{n} demandent d'abord"))
    val actionsOff = plural("actions_off", "n",
        Plural(one = "{n} off", other = "{n} off"),
        Plural(one = "{n} désactivée", other = "{n} désactivées"))
    val safety = s("safety", "{cards} · locked: {allowed}", "{cards} · verrouillé : {allowed}")
    val helpersNone = s("helpers_none", "None", "Aucune")
    val helpersNoneOn = s("helpers_none_on", "None on", "Aucune activée")
    val helpersMore = plural("helpers_more", "n",
        Plural(one = "{names} and {n} more", other = "{names} and {n} more"),
        Plural(one = "{names} et {n} autre", other = "{names} et {n} autres"))
    val escalationOff = s("escalation_off", "Off", "Désactivé")
    val escalationAsk = s("escalation_ask", "Ask first", "Demander d'abord")
    val escalationAuto = s("escalation_auto", "Automatic", "Automatique")
    val liveWordsOn = s("live_words_on", "Live words on", "Mots en direct activés")
    val liveWordsOff = s("live_words_off", "Live words off", "Mots en direct désactivés")
    val translating = s("translating", "translating", "traduction activée")
    val modelOn = s("model_on", "{model} on the {backend}", "{model} sur le {backend}")
    val modelAlways = s("model_always", "{model} · always loaded", "{model} · toujours chargé")
    val modelStays = s("model_stays", "{model} · stays loaded {n} min", "{model} · reste chargé {n} min")
    val wallpaperColours = s("wallpaper_colours", "Wallpaper colours", "Couleurs du fond d'écran")
    val robotColours = s("robot_colours", "Robot's colours", "Couleurs de Robot")
}
