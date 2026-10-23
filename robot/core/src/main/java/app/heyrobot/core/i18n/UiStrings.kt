// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Pure. Robot's own screens and buttons, as sentences name them ("Delete things in Settings › Memory.", « Touche
// Retenir. »), in English and Québec French. The app's screens take their titles from here
// as each area moves to resources, so a sentence and the screen it names can't disagree. WP0 made this file final from
// the 56 paths the scan found in sentences (§3.4) and the buttons replies tell you to tap; a package that needs another
// adds it at the end of its group. The French follows the glossary (l10n/glossary.tsv).

/**
 * A Robot screen or button named in a sentence: one title, or a path of them ("Settings › Memory › Import"). Rendered in
 * the app's language ([Langs.ui]); aloud, "›" is a pause ("Settings, Memory").
 */
class UiLabel(val parts: List<Msg>) {
    constructor(vararg parts: Msg) : this(parts.toList())

    /** This path, one level down. */
    operator fun div(next: Msg): UiLabel = UiLabel(parts + next)

    fun render(lang: Lang, medium: Medium = Medium.SCREEN, pseudo: Pseudo? = null): String =
        parts.joinToString(if (medium == Medium.VOICE) ", " else " › ") { Phrase.M(it).render(Langs(lang, lang, lang, medium, pseudo)) }

    /** Its id for [PhraseWire]: the entries' ids joined by "/". */
    val id: String get() = parts.joinToString("/") { it.id }

    override fun equals(other: Any?): Boolean = other is UiLabel && other.parts == parts
    override fun hashCode(): Int = parts.hashCode()
    override fun toString(): String = "UiLabel($id)"
}

/** The titles and buttons a [UiLabel] is made of. Area "language" (WP0's): every entry has its French. */
object UiStrings : Strings("ui", area = "language") {
    // The tabs and the screens opened from them.
    val settings = s("settings", "Settings", "Réglages")
    val home = s("home", "Home", "Accueil")
    val notes = s("notes", "Notes", "Notes")
    val history = s("history", "History", "Historique")
    val model = s("model", "Model", "Modèle")
    val people = s("people", "People", "Personnes")
    val transcripts = s("transcripts", "Transcripts", "Transcriptions")

    // Settings' rows, in Settings' order.
    val wakeWord = s("wake_word", "Wake word", "Mot d'éveil")
    val voice = s("voice", "Voice", "Voix")
    val actions = s("actions", "Actions", "Actions")
    val safety = s("safety", "Safety & privacy", "Sécurité et confidentialité")
    val routines = s("routines", "Routines", "Routines")
    val skills = s("skills", "Skills", "Compétences")
    val headset = s("headset", "Headset", "Écouteurs")
    val carMode = s("car_mode", "Car mode", "Mode auto")
    val peopleNotesCalendar = s("people_notes_calendar", "People, notes and calendar", "Personnes, notes et agenda")
    val messages = s("messages", "Messages", "Messages")
    val weather = s("weather", "Weather", "Météo")
    val webSearch = s("web_search", "Web search", "Recherche web")
    val helpers = s("helpers", "Helpers", "IA externes")
    val conversation = s("conversation", "Conversation", "Conversation")
    val termux = s("termux", "Termux", "Termux")
    val memory = s("memory", "Memory", "Mémoire")
    val display = s("display", "Display", "Affichage")
    val assistantSetup = s("assistant_setup", "Assistant setup", "Configuration de l'assistant")
    val advanced = s("advanced", "Advanced", "Avancé")
    val about = s("about", "About", "À propos")
    val language = s("language", "Language", "Langue")
    // 1.1: Settings › Phone lock.
    val phoneLock = s("phone_lock", "Phone lock", "Blocage du téléphone")
    /** 1.1: Settings › Back up and restore (docs/plans/backup-1.1-design.md §9.1). */
    val backup = s("backup", "Back up and restore", "Sauvegarde et restauration")
    /** 1.4: Settings › Files (docs/plans/files-1.4-plan.md §6.6). */
    val files = s("files", "Files", "Fichiers")

    // Pages and rows under them that sentences name.
    val updates = s("updates", "Updates", "Mises à jour")
    val sendFeedback = s("send_feedback", "Send feedback", "Envoyer un commentaire")
    val licences = s("licences", "Licences", "Licences")
    val setupGuide = s("setup_guide", "Setup guide", "Guide de configuration")
    val adbFixes = s("adb_fixes", "ADB fixes", "Correctifs ADB")
    val voiceLog = s("voice_log", "Voice log", "Journal vocal")
    val wakeWords = s("wake_words", "Wake words", "Mots d'éveil")
    val transcriptionCheck = s("transcription_check", "Transcription check", "Vérification de la transcription")
    val testTheVoice = s("test_the_voice", "Test the voice", "Tester la voix")
    val voiceModels = s("voice_models", "Voice models", "Modèles de voix")
    val suggestions = s("suggestions", "Suggestions", "Suggestions")
    val importRow = s("import", "Import", "Importer")
    val exportRow = s("export", "Export", "Exporter")
    val yourVoice = s("your_voice", "Your voice", "Ta voix")
    val fastTranslation = s("fast_translation", "Fast translation", "Traduction rapide")
    val testTranslationSpeed = s("test_translation_speed", "Test translation speed", "Tester la vitesse de traduction")
    val announceNewMessages = s("announce_new_messages", "Announce new messages", "Annoncer les nouveaux messages")
    val calls = s("calls", "Calls", "Appels")
    val addYourCar = s("add_your_car", "Add your car", "Ajouter ton auto")
    val addAHelper = s("add_a_helper", "Add a helper", "Ajouter une IA externe")
    val searchServer = s("search_server", "Search server", "Serveur de recherche")
    val sendPersonalContent = s("send_personal_content", "Send personal content to helpers automatically",
        "Envoyer du contenu personnel aux IA externes automatiquement")
    val menus = s("menus", "Menus", "Menus")
    val robotAnswersIn = s("robot_answers_in", "Robot answers in", "Robot répond en")

    // Buttons a reply or a card tells you to tap.
    val doIt = s("do_it", "Do it", "Fais-le")
    val cancel = s("cancel", "Cancel", "Annuler")
    val send = s("send", "Send", "Envoyer")
    val open = s("open", "Open", "Ouvrir")
    val undo = s("undo", "Undo", "Annuler")
    val remember = s("remember", "Remember", "Retenir")
    val keep = s("keep", "Keep", "Garder")
    val delete = s("delete", "Delete", "Supprimer")
    val allow = s("allow", "Allow", "Autoriser")
    val allowAlways = s("allow_always", "Allow always", "Toujours autoriser")
    val download = s("download", "Download", "Télécharger")
    val run = s("run", "Run", "Lancer")
    val call = s("call", "Call", "Appeler")
    val route = s("route", "Route", "Itinéraire")
    val save = s("save", "Save", "Enregistrer")
    val done = s("done", "Done", "Fait")
    val start = s("start", "Start", "Démarrer")
    val stop = s("stop", "Stop", "Arrêter")
    val resume = s("resume", "Resume", "Reprendre")
    val play = s("play", "Play", "Écouter")
    val tryAgain = s("try_again", "Try again", "Réessayer")
    val checkNow = s("check_now", "Check now", "Vérifier maintenant")
    val trustThisVersion = s("trust_this_version", "Trust this version", "Faire confiance à cette version")
    val testConnection = s("test_connection", "Test connection", "Tester la connexion")
    val shareWithClaude = s("share_with_claude", "Share with Claude", "Partager avec Claude")
    val talkToRobot = s("talk_to_robot", "Talk to Robot", "Parler à Robot")

    /** Settings › [page]. */
    fun inSettings(vararg page: Msg): UiLabel = UiLabel(listOf(settings) + page)
}
