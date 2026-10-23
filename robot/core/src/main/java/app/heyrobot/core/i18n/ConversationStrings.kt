// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP5's catalog: conversation mode and translation: what core says or labels about transcripts and their
// exports, speakers, summaries and what they send to a helper, the interpreter's two halves, the Transcription check and
// the fast translation engine. Ids start with "conv."; every entry has its French. What a model or a helper reads (the
// summary's prompts, the lines Gemma reads, the request sent to Claude), the copied reports (the check's and the speed
// test's, diagnostics) and the sentences you read aloud (one list per language) stay as they are, outside this catalog.

object ConversationStrings : Strings("conv", area = "conversation") {
    // A transcript's title, its length, and your own lines.
    val titleDefault = s("title_default", "Conversation", "Conversation")
    val titleDated = s("title_dated", "Conversation · {day}, {time}", "Conversation · {day}, {time}")
    val underAMinute = s("under_a_minute", "under a minute", "moins d'une minute")
    val minutes = s("minutes", "{n} min", "{n} min")
    val hours = s("hours", "{h} h {mm}", "{h} h {mm}")
    val you = s("you", "You", "Moi")

    // An export's header and lines (Markdown or text, in the menus' language when you export it). Times stay as the lines
    // show them ("14:03"), so a summary's [14:03] still points at its line.
    val credit = s("credit", "Transcribed on this phone by Robot (Parakeet v3).", "Transcrit sur ce téléphone par Robot (Parakeet v3).")
    val creditTranslations = s("credit_translations", "Translations: Robot's model / Bergamot (marked \"fast\").",
        "Traductions : le modèle de Robot / Bergamot (marquées « rapide »).")
    val headerDate = s("header_date", "Date: {day}, {from}–{to} ({min} min)", "Date : {day}, {from}–{to} ({min} min)")
    val headerSpeakers = s("header_speakers", "Speakers: {list}", "Intervenants : {list}")
    val headerLanguages = s("header_languages", "Languages: {list}", "Langues : {list}")
    val noSpeakers = s("no_speakers", "none", "aucun")
    val noLanguages = s("no_languages", "none", "aucune")
    val edited = s("edited", "edited", "modifiée")
    val fast = s("fast", "fast", "rapide")
    val fileName = s("file_name", "Transcript {stamp}", "Transcription {stamp}")
    val langFr = s("lang_fr", "French", "français")
    val langEn = s("lang_en", "English", "anglais")
    val pairFrEn = s("pair_fr_en", "French ↔ English", "français ↔ anglais")

    // A pause or a stop in a transcript: "[phone call 14:03–14:07]".
    val gap = s("gap", "[{why} {from}–{to}]", "[{why} {from}–{to}]")
    val gapMic = s("gap_mic", "microphone unavailable", "micro indisponible")
    val gapPause = s("gap_pause", "paused", "en pause")
    val gapCall = s("gap_call", "phone call", "appel téléphonique")
    val gapCrash = s("gap_crash", "stopped unexpectedly", "arrêt inattendu")
    val gapBehind = s("gap_behind", "not transcribed", "non transcrit")
    val gapOther = s("gap_other", "nothing recorded", "rien d'enregistré")

    // The interpreter: each half in its own language, whatever the menus say.
    val heardYou = s("heard_you", "Robot heard you", "Robot t'a entendu")
    val couldNotTranslate = s("could_not_translate", "Robot couldn't translate that.", "Robot n'a pas pu traduire cela.")
    val speakRobotTranslates = s("speak_robot_translates", "Speak: Robot translates.", "Parle : Robot traduit.")
    val speakThisLanguage = s("speak_this_language", "Speak English", "Parler en français")
    val speakThisLanguageOn = s("speak_this_language_on", "Speak English, on", "Parler en français, activé")

    // A summary: what its check couldn't find, the stronger AI's offer and the consent card (what goes, and where).
    val missTime = s("miss_time", "Robot couldn't find a line at {time}.", "Robot n'a trouvé aucune ligne à {time}.")
    val missWord = s("miss_word", "Robot couldn't find “{text}” in the transcript.", "Robot n'a pas trouvé « {text} » dans la transcription.")
    val offerCard = s("offer_card", "This conversation is long; a stronger AI will summarize it more accurately.",
        "Cette conversation est longue; une IA plus puissante la résumera plus fidèlement.")
    val discloseTo = s("disclose_to", "Goes to: {to}, {network}.", "Destination : {to}, {network}.")
    val discloseModel = s("disclose_model", "{provider} ({model})", "{provider} ({model})")
    val costSentence = s("cost_sentence", "{cost}.", "{cost}.")
    val networkApi = s("network_api", "over the internet, with your API key", "par Internet, avec ta clé d'API")
    val networkApp = s("network_app", "with your claude.ai account, when you press Send there",
        "avec ton compte claude.ai, quand tu touches Envoyer dans l'appli")
    val theClaudeApp = s("the_claude_app", "the Claude app", "l'appli Claude")
    // 1.0.0: a summary by any helper (Summary.kt Disclosure, SummaryCost).
    val theApp = s("the_app", "the {name} app", "l'appli {name}")
    val networkOwnApp = s("network_own_app", "with your own account there, when you press Send there",
        "avec ton propre compte dans l'appli, quand tu touches Envoyer dans l'appli")
    val yourOwnServer = s("your_own_server", "your own server", "ton propre serveur")
    val networkHome = s("network_home", "over your network", "par ton réseau")
    val costUnknown = s("cost_unknown", "cost unknown: Robot has no price for this model (you can enter it on the helper's page)",
        "coût inconnu : Robot n'a pas de prix pour ce modèle (tu peux l'entrer sur la page de l'IA externe)")
    val costUnknownCard = s("cost_unknown_card", "Cost unknown: Robot has no price for this model (you can enter it on the helper's page)",
        "Coût inconnu : Robot n'a pas de prix pour ce modèle (tu peux l'entrer sur la page de l'IA externe)")
    val overBudget = s("over_budget", "Today's budget for helpers is used up: this adds to it.",
        "Le budget du jour pour les IA externes est épuisé : ceci s'y ajoute.")
    val helperSummary = s("helper_summary", "{name}'s summary — sent to {company} with your OK on {when}.",
        "Résumé de {name} — envoyé à {company} avec ton accord le {when}.")
    val consentTitle = s("consent_title", "Send this conversation to {to}?", "Envoyer cette conversation à {to} ?")
    val consentStats = s("consent_stats", "{min} min · {speakers} · {words}.", "{min} min · {speakers} · {words}.")
    val consentStatsLangs = s("consent_stats_langs", "{min} min · {speakers} · {words} · {langs}.", "{min} min · {speakers} · {words} · {langs}.")
    val speakersCount = plural("speakers", "n", Plural("{n} speaker", "{n} speakers"), Plural("{n} intervenant", "{n} intervenants"))
    /** 1.0's English says "1 words" for a single word: kept as it was (byte for byte). */
    val wordsCount = plural("words", "n", Plural("{n} words", "{n} words"), Plural("{n} mot", "{n} mots"))
    val includesSaid = s("includes_said", "It includes what other people said.", "Elle contient ce que les autres personnes ont dit.")
    val includesNames = s("includes_names", "It includes what other people said and the names you gave them.",
        "Elle contient ce que les autres personnes ont dit et les noms que tu leur as donnés.")
    val replaceNames = s("replace_names", "Replace names with “Speaker 1, 2”", "Remplacer les noms par « Speaker 1, 2 »")
    val tooLongForApp = s("too_long_for_app", "Too long for the app; export and attach the file.",
        "Trop long pour l'appli; exporte la transcription et joins le fichier.")
    val tooLongForApi = s("too_long_for_api", "Too long for one request; export it and attach the file.",
        "Trop long pour une seule requête; exporte-la et joins le fichier.")
    val nothingSentTooLong = s("nothing_sent_too_long", "Nothing was sent. Too long for one request; export it and attach the file.",
        "Rien n'a été envoyé. Trop long pour une seule requête; exporte-la et joins le fichier.")
    val dayTime = s("day_time", "{day}, {time}", "{day} à {time}")
    val claudeSummary = s("claude_summary", "Claude's summary — sent to Anthropic with your OK on {when}.",
        "Résumé de Claude — envoyé à Anthropic avec ton accord le {when}.")
    val robotSummary = s("robot_summary", "Robot's summary — made on this phone by {model}. It can miss or mix up details; check the transcript.",
        "Résumé de Robot — fait sur ce téléphone par {model}. Il peut oublier ou mélanger des détails; vérifie la transcription.")
    val robotsModel = s("robots_model", "Robot's model", "le modèle de Robot")
    val ownModelFile = s("own_model_file", "your own model file", "ton propre fichier de modèle")

    // Why a summary stopped, or wasn't made or sent.
    val nothingToSummarize = s("nothing_to_summarize", "There is nothing in this transcript to summarize.", "Il n'y a rien à résumer dans cette transcription.")
    val modelTookTooLong = s("model_took_too_long", "Robot's model took too long.", "Le modèle de Robot a pris trop de temps.")
    val modelBusy = s("model_busy", "Robot's model was busy.", "Le modèle de Robot était occupé.")
    val modelStopped = s("model_stopped", "Robot's model stopped (it was unloaded or restarted).",
        "Le modèle de Robot s'est arrêté (il a été déchargé ou redémarré).")
    val modelNotReadyOpen = s("model_not_ready_open", "Robot's model isn't ready. Open the assistant once, or check {page}.",
        "Le modèle de Robot n'est pas prêt. Ouvre l'assistant une fois, ou vérifie {page}.")
    val partTooLong = s("part_too_long", "A part was too long for Robot's model.", "Une partie était trop longue pour le modèle de Robot.")
    val modelProblem = s("model_problem", "Robot's model ran into a problem.", "Le modèle de Robot a eu un problème.")
    val modelNotReady = s("model_not_ready", "Robot's model isn't ready.", "Le modèle de Robot n'est pas prêt.")
    val transcriptDeleted = s("transcript_deleted", "This transcript was deleted.", "Cette transcription a été supprimée.")
    val summaryNotSaved = s("summary_not_saved", "The summary couldn't be saved on this phone.", "Le résumé n'a pas pu être enregistré sur ce téléphone.")
    val failedKept = s("failed_kept", "{why} What was done is kept: try again to go on.", "{why} Ce qui a été fait est gardé : réessaie pour continuer.")
    val stoppedKept = s("stopped_kept", "Stopped. What was done is kept: summarize again to go on.",
        "Arrêté. Ce qui a été fait est gardé : relance le résumé pour continuer.")
    val summaryProblem = s("summary_problem", "Robot's summary ran into a problem.", "Le résumé de Robot a eu un problème.")
    val helperNotSaved = s("helper_not_saved", "{name}'s summary came, but it couldn't be saved on this phone.",
        "Le résumé de {name} est arrivé, mais il n'a pas pu être enregistré sur ce téléphone.")
    val stopped = s("stopped", "Stopped.", "Arrêté.")
    val needsYourOk = s("needs_your_ok", "Nothing was sent: Robot needs your OK on the card.", "Rien n'a été envoyé : Robot a besoin de ton accord sur la carte.")
    val helperSentNothing = s("helper_sent_nothing", "{name} sent nothing back.", "{name} n'a rien renvoyé.")
    val helperDeclined = s("helper_declined", "{name} declined to summarize this conversation.", "{name} a refusé de résumer cette conversation.")
    val helperUnusable = s("helper_unusable", "{name} answered with something Robot can't use.", "{name} a répondu quelque chose que Robot ne peut pas utiliser.")
    val appGetsAShare = s("app_gets_a_share", "The {appName} app gets a share, not a request.", "L'appli {appName} reçoit un partage, pas une requête.")

    // The Transcription check (Settings › Voice › Transcription check): its results on screen.
    val micPhone = s("mic_phone", "phone mic", "micro du téléphone")
    val micHeadset = s("mic_headset", "headset mic", "micro des écouteurs")
    val modeRead = s("mode_read", "read", "lu")
    val modeFree = s("mode_free", "free", "libre")
    val modeClip = s("mode_clip", "clip", "extrait")
    val verdictPasses = s("verdict_passes", "passes", "réussi")
    val verdictFails = s("verdict_fails", "doesn't pass", "échoué")
    val verdictUnder = s("verdict_under", "under the mark so far", "sous le seuil jusqu'ici")
    val verdictOver = s("verdict_over", "over the mark so far", "au-dessus du seuil jusqu'ici")
    val checkLangFr = s("check_lang_fr", "French (Québec)", "français (Québec)")
    val checkWhoMic = s("check_who_mic", "{lang}, {mic}", "{lang}, {mic}")
    val checkSummary = s("check_summary", "{who}: {pct}% of words wrong over {lines}, {readers}. Pass mark {mark}%: {verdict}.",
        "{who} : {pct} % de mots erronés sur {lines}, {readers}. Seuil de réussite {mark} % : {verdict}.")
    val checkSummaryAccents = s("check_summary_accents",
        "{who}: {pct}% of words wrong ({plain}% with accents not counted) over {lines}, {readers}. Pass mark {mark}%: {verdict}.",
        "{who} : {pct} % de mots erronés ({plain} % sans compter les accents) sur {lines}, {readers}. Seuil de réussite {mark} % : {verdict}.")
    val linesCount = plural("lines", "n", Plural("{n} line", "{n} lines"), Plural("{n} ligne", "{n} lignes"))
    val readersCount = plural("readers", "n", Plural("{n} reader", "{n} readers"), Plural("{n} lecteur", "{n} lecteurs"))
    val checkNeed = s("check_need", "The gate needs {lines} lines from {readers} readers.", "Le test demande {lines} lignes de {readers} lecteurs.")
    val checkLineHead = s("check_line_head", "{n}. {lang} {mode}", "{n}. {lang} {mode}")
    val checkReader = s("check_reader", "reader {n}", "lecteur {n}")
    val checkWrong = plural("check_wrong", "errors", Plural("{errors} of {words} words wrong ({pct}%)", "{errors} of {words} words wrong ({pct}%)"),
        Plural("{errors} mot erroné sur {words} ({pct} %)", "{errors} mots erronés sur {words} ({pct} %)"))
    val accentsCount = plural("accents", "n", Plural("{n} accent", "{n} accents"), Plural("{n} accent", "{n} accents"))
    val checkHeardAs = s("check_heard_as", "heard as {lang}", "entendu comme {lang}")
    val checkDecoded = s("check_decoded", "decoded in {ms} ms", "décodé en {ms} ms")
    /** A length with one decimal: "3.1 s", « 3,1 s ». */
    val secondsOneDecimal = s("seconds_one_decimal", "{int}.{frac} s", "{int},{frac} s")
    val noClipsPicked = s("no_clips_picked", "No clips picked.", "Aucun extrait choisi.")
    val textFileEmpty = s("text_file_empty", "The text file has no lines.", "Le fichier texte n'a aucune ligne.")
    val noNameMatches = s("no_name_matches", "None of the names in the text file matches a clip.",
        "Aucun des noms du fichier texte ne correspond à un extrait.")
    val clipsWithoutLine = plural("clips_without_line", "n", Plural("{n} clip has no line in the text file.", "{n} clips have no line in the text file."),
        Plural("{n} extrait n'a pas de ligne dans le fichier texte.", "{n} extraits n'ont pas de ligne dans le fichier texte."))
    val clipsButLines = plural("clips_but_lines", "n",
        Plural("{n} clip but {lines}: the text file needs one line per clip, in the order of the clips' names.",
            "{n} clips but {lines}: the text file needs one line per clip, in the order of the clips' names."),
        Plural("{n} extrait, mais {lines} : le fichier texte doit avoir une ligne par extrait, dans l'ordre des noms des extraits.",
            "{n} extraits, mais {lines} : le fichier texte doit avoir une ligne par extrait, dans l'ordre des noms des extraits."))

    // The fast translation engine: its name, why it didn't answer, and its speed test's result on screen.
    val fastEngine = s("fast_engine", "Fast translation engine", "Moteur de traduction rapide")
    val engineWithSize = s("engine_with_size", "{title} ({size})", "{title} ({size})")
    val mtNoEngine = s("mt_no_engine", "The fast translation engine isn't in this build of Robot.",
        "Le moteur de traduction rapide n'est pas dans cette version de Robot.")
    val mtNotDownloaded = s("mt_not_downloaded", "Download the fast translation engine first ({page}).",
        "Télécharge d'abord le moteur de traduction rapide ({page}).")
    val mtLoadFailed = s("mt_load_failed", "The fast translation engine's files didn't load. Delete them and download them again.",
        "Les fichiers du moteur de traduction rapide ne se sont pas chargés. Supprime-les et télécharge-les de nouveau.")
    val mtBadRequest = s("mt_bad_request", "Robot asked the fast translation engine for something it doesn't do.",
        "Robot a demandé au moteur de traduction rapide quelque chose qu'il ne fait pas.")
    val mtBusy = s("mt_busy", "The fast translation engine has too many lines waiting.", "Le moteur de traduction rapide a trop de lignes en attente.")
    val mtFailed = s("mt_failed", "The fast translation engine failed on a line.", "Le moteur de traduction rapide a échoué sur une ligne.")
    val mtDisconnected = s("mt_disconnected", "The fast translation engine stopped. Robot starts it again the next time.",
        "Le moteur de traduction rapide s'est arrêté. Robot le redémarre la prochaine fois.")
    val mtTimeout = s("mt_timeout", "The fast translation engine didn't answer in time.", "Le moteur de traduction rapide n'a pas répondu à temps.")
    val speedLine = s("speed_line", "Speed: median {median} ms, slowest {max} ms a line (the mark is {gate} ms or less): {mark}.",
        "Vitesse : médiane de {median} ms, ligne la plus lente {max} ms (seuil : {gate} ms ou moins) : {mark}.")
    val memoryLine = s("memory_line", "Memory: {memory} with both directions loaded (the mark is {gate} MB or less): {mark}.",
        "Mémoire : {memory} avec les deux directions chargées (seuil : {gate} Mo ou moins) : {mark}.")
    val memoryNow = s("memory_now", "{peak} MB at most, {now} MB now", "{peak} Mo au plus, {now} Mo maintenant")
    val memoryUnknown = s("memory_unknown", "unknown", "inconnue")
    val outputsLine = s("outputs_line", "Translations: {same} of {total} the same as on the build machine (the mark is all of them): {mark}.",
        "Traductions : {same} sur {total} identiques à celles de la machine de compilation (seuil : toutes) : {mark}.")
    val markPasses = s("mark_passes", "passes", "réussi")
    val markMisses = s("mark_misses", "misses", "échoué")
    val loadedAlready = s("loaded_already", "The engine was loaded already.", "Le moteur était déjà chargé.")
    val loadedIn = s("loaded_in", "Loaded in {list}.", "Chargé en {list}.")
    val loadTime = s("load_time", "{ms} ms ({direction})", "{ms} ms ({direction})")
    val agoNow = s("ago_now", "now", "à l'instant")
    val agoSeconds = s("ago_seconds", "{n} s ago", "il y a {n} s")
    val agoMinutes = s("ago_minutes", "{n} min ago", "il y a {n} min")
    val agoHours = s("ago_hours", "{n} h ago", "il y a {n} h")
    val agoDays = s("ago_days", "{n} d ago", "il y a {n} j")
}
