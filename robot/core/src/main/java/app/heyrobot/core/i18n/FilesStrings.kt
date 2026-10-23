// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileLimits
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.IntakeLimits
import app.heyrobot.core.files.IntakeProblem

// 1.4, Robot Files (files-1.4-plan.md §3, F5): what Robot says when a file comes in, by the share sheet, a scan, the
// panel's "file this" or a transcript; the same words in the share window, the scan screen, the panel and a transcript's
// page. Ids start with "files."; the area is "files". The French is Québec French (tu), from the glossary
// (robot/l10n/glossary.tsv: « Fichiers de Robot », « fichier », « boîte de réception », « numériser »); render() adds its
// spaces. The screens' own buttons and titles are in values/strings_files.xml.

object FilesStrings : Strings("files", area = "files") {
    // What came in.
    val added = plural("added", "n", Plural("Added to Robot Files: {n} file is in your Inbox.", "Added to Robot Files: {n} files are in your Inbox."),
        Plural("Ajouté aux Fichiers de Robot : {n} fichier est dans ta boîte de réception.",
            "Ajoutés aux Fichiers de Robot : {n} fichiers sont dans ta boîte de réception.",
            "Ajoutés aux Fichiers de Robot : {n} de fichiers sont dans ta boîte de réception."))
    val addedOne = s("added_one", "Added to Robot Files. It's in your Inbox.", "Ajouté aux Fichiers de Robot. Il est dans ta boîte de réception.")
    val dangerous = s("dangerous", "This could install or run something. Robot won't open it.",
        "Ce fichier pourrait installer ou lancer quelque chose. Robot ne l'ouvrira pas.")
    val leftOut = plural("left_out", "n",
        Plural("Robot takes {max} files at a time, so {n} more was left out.", "Robot takes {max} files at a time, so {n} more were left out."),
        Plural("Robot prend {max} fichiers à la fois, alors {n} autre a été laissé de côté.",
            "Robot prend {max} fichiers à la fois, alors {n} autres ont été laissés de côté.",
            "Robot prend {max} fichiers à la fois, alors {n} d'autres ont été laissés de côté."))

    // A duplicate (§4.1 step 1): the same bytes, by SHA-256.
    val duplicate = s("duplicate", "You already have this file: {where}. Add it anyway?", "Tu as déjà ce fichier : {where}. L'ajouter quand même?")
    /** FX2: {where} is the drawer or [yourInbox], {name} the file as [FileMoneyText.said] says it. */
    val duplicateSay = s("duplicate_say", "You already have this file in {where}: {name}. To add it again, say “file it anyway”.",
        "Tu as déjà ce fichier dans {where} : {name}. Pour l'ajouter de nouveau, dis « classe-le quand même ».")
    val inbox = s("inbox", "Inbox", "Boîte de réception")
    val noName = s("no_name", "a file with no name", "un fichier sans nom")
    val notAdded = s("not_added", "Not added.", "Pas ajouté.")
    val noneAdded = s("none_added", "Nothing was added.", "Rien n'a été ajouté.")
    val nothingShared = s("nothing_shared", "There's nothing here Robot can add.", "Il n'y a rien ici que Robot peut ajouter.")
    /** One file's line in the share window: its name, then what happened to it. */
    val fileLine = s("file_line", "{name}: {what}", "{name} : {what}")

    // Who sent it: Android's word, or no one's.
    val sharedFrom = s("shared_from", "Shared from {sender}", "Partagé depuis {sender}")
    val sharedFromAnother = s("shared_from_another", "Shared from another app", "Partagé depuis une autre appli")

    // Why not (IntakeProblem).
    val locked = s("locked", "Unlock your phone to use your files.", "Déverrouille ton téléphone pour utiliser tes fichiers.")
    // F9: the model's two tools (find_files, read_file) and its answers from a file.
    val toolsOff = s("tools_off", "Robot Files is off.", "Les Fichiers de Robot sont désactivés.")
    val privateFile = s("private_file", "a private file", "un fichier privé")
    // A file's helper card (§5.2): what it sends, or why it never goes.
    val helperKeeps = s("helper_keeps", "Robot keeps medical files and IDs on the phone.",
        "Robot garde les documents médicaux et les pièces d'identité sur le téléphone.")
    val helperKeepsSecret = s("helper_keeps_secret", "Robot keeps this file on the phone: it holds a card number, an identity number or a password.",
        "Robot garde ce fichier sur le téléphone : il contient un numéro de carte, un numéro d'identité ou un mot de passe.")
    val helperNoText = s("helper_no_text", "Robot has no words from this file to send.", "Robot n'a aucun mot de ce fichier à envoyer.")
    val sendsFileText = s("sends_file_text", "the text of the file {name}", "le texte du fichier {name}")
    val notReadAloud = s("not_read_aloud", "Not read aloud: the answer comes from a private file.", "Pas lu à voix haute : la réponse vient d'un fichier privé.")
    val privateAnswer = s("private_answer", "[An answer from a private file in Robot Files: not kept in History.]",
        "[Une réponse tirée d'un fichier privé des Fichiers de Robot : pas gardée dans l'Historique.]")
    // FX1: History's line for Robot Files' answer while travel mode is on (core TravelMode.history); the turn is kept.
    val travelAnswer = s("travel_answer", "[An answer from Robot Files, out of sight while travel mode is on.]",
        "[Une réponse des Fichiers de Robot, masquée pendant que le mode voyage est activé.]")
    // FX3: your turn in a conversation holding Robot Files' answer while travel mode is on (core TravelMode.history), and so
    // the conversation's title; the turn is kept.
    val travelQuestion = s("travel_question", "[Out of sight while travel mode is on.]", "[Masqué pendant que le mode voyage est activé.]")
    val keyGone = s("key_gone", "Robot's files key is gone, so it can't add files. {page} says what you can do.",
        "La clé des fichiers de Robot n'existe plus, alors il ne peut pas ajouter de fichiers. {page} indique quoi faire.")
    val index = s("index", "Robot couldn't read your files' list, so it can't add files. {page} can start over.",
        "Robot n'a pas pu lire la liste de tes fichiers, alors il ne peut pas ajouter de fichiers. {page} permet de recommencer.")
    val full = s("full", "Robot Files is full: {max} files.", "Les Fichiers de Robot sont pleins : {max} fichiers.")
    val tooBig = s("too_big", "This file is too big for Robot Files: {max} at most.", "Ce fichier est trop gros pour les Fichiers de Robot : {max} au maximum.")
    val lowSpace = s("low_space", "Not enough space on the phone to add this file: about {need} more needed.",
        "Pas assez d'espace sur le téléphone pour ajouter ce fichier : il manque environ {need}.")
    val timedOut = s("timed_out", "The app sharing this file stopped sending it, so Robot left it out.",
        "L'appli qui partage ce fichier a cessé de l'envoyer, alors Robot l'a laissé de côté.")
    val unreadable = s("unreadable", "Robot couldn't read this file from the app sharing it.", "Robot n'a pas pu lire ce fichier depuis l'appli qui le partage.")
    val empty = s("empty", "This file is empty, so Robot left it out.", "Ce fichier est vide, alors Robot l'a laissé de côté.")
    val io = s("io", "Robot couldn't save this file. Try again.", "Robot n'a pas pu enregistrer ce fichier. Réessaie.")
    val notAllowed = s("not_allowed", "Robot can't take this file: the app shared it in a way Robot doesn't accept.",
        "Robot ne peut pas prendre ce fichier : l'appli l'a partagé d'une façon que Robot n'accepte pas.")
    val off = s("off", "Robot Files is off, so Robot can't take files now.", "Les Fichiers de Robot sont désactivés, alors Robot ne peut pas prendre de fichiers.")

    // The panel's "file this" (§3.3).
    val nothingToFile = s("nothing_to_file", "There's nothing to file yet: ask about the screen or take a photo first.",
        "Il n'y a rien à classer pour l'instant : pose une question sur l'écran ou prends une photo d'abord.")
    val photoTooBig = s("photo_too_big", "This photo is too big for Robot Files.", "Cette photo est trop grosse pour les Fichiers de Robot.")
    val nothingWaiting = s("nothing_waiting", "There's no file waiting to be added.", "Il n'y a aucun fichier en attente d'être ajouté.")

    // The names Robot gives what it adds itself ({when}: "2026-10-22 14.05"); F6 renames them from what it reads.
    val nameSharedText = s("name_shared_text", "Shared text {when}", "Texte partagé {when}")
    val nameScan = s("name_scan", "Scan {when}", "Numérisation {when}")
    val nameScreenshot = s("name_screenshot", "Screenshot {when}", "Capture d'écran {when}")
    val namePhoto = s("name_photo", "Photo {when}", "Photo {when}")

    // Scanning (§3.2): what :files' answers mean.
    val scanUnreadable = s("scan_unreadable", "Robot couldn't read this photo. Take it again.", "Robot n'a pas pu lire cette photo. Reprends-la.")
    val scanCorners = s("scan_corners", "Those corners don't make a page. Move them to the page's four corners.",
        "Ces coins ne forment pas une page. Place-les aux quatre coins de la page.")
    val scanReaderDown = s("scan_reader_down", "Robot's file reader stopped. Try again.", "Le lecteur de fichiers de Robot s'est arrêté. Réessaie.")
    // F13: the scan screen has no Done button while it waits; its button is Add to Robot Files (files_scan_add).
    val scanFull = s("scan_full", "That's as many pages as one scan holds: tap Add to Robot Files.",
        "C'est le maximum de pages pour une numérisation : touche Ajouter aux Fichiers de Robot.")

    // Filing (F6, §4): the starter drawers' names (core files Drawers' ids), and the kinds as a question offers them.
    val drawerReceipts = s("drawer_receipts", "Receipts", "Reçus")
    val drawerBills = s("drawer_bills", "Bills", "Factures")
    val drawerDocuments = s("drawer_documents", "Documents", "Documents")
    val drawerMedical = s("drawer_medical", "Medical", "Santé")
    val drawerTaxes = s("drawer_taxes", "Taxes", "Impôts")
    val drawerTrips = s("drawer_trips", "Trips and events", "Voyages et événements")
    val drawerPhotos = s("drawer_photos", "Photos and media", "Photos et médias")
    val drawerConversations = s("drawer_conversations", "Conversations", "Conversations")

    val kindReceipt = s("kind_receipt", "a receipt", "un reçu")
    val kindBill = s("kind_bill", "a bill", "une facture")
    val kindDocument = s("kind_document", "a document", "un document")
    val kindId = s("kind_id", "an ID", "une pièce d'identité")
    val kindTaxSlip = s("kind_tax_slip", "a tax slip", "un feuillet d'impôt")
    val kindTicket = s("kind_ticket", "a ticket or a booking", "un billet ou une réservation")
    val kindMedical = s("kind_medical", "a medical file", "un document médical")
    val kindPhoto = s("kind_photo", "a photo", "une photo")
    val kindVideo = s("kind_video", "a video", "une vidéo")
    val kindNote = s("kind_note", "a note", "une note")
    val kindTranscript = s("kind_transcript", "a transcript", "une transcription")
    val kindOther = s("kind_other", "something else", "autre chose")

    /** A bill's name: "2026-09 Hydro-Québec 142.00 CAD due 2026-10-17". */
    val nameDue = s("name_due", "due", "échéance")

    // The panel's "file this" (§3.3): filed, or why it waits in Inbox.
    val filedIn = s("filed_in", "Filed in {drawer}.", "Classé dans {drawer}.")
    val filedInSay = s("filed_in_say", "Filed in {drawer}: {name}.", "Classé dans {drawer} : {name}.")
    val move = s("move", "Move", "Déplacer")
    val moveAsk = s("move_ask", "Where should it go? Say or type a drawer: {drawers}.", "Où le ranger? Dis ou tape le nom d'un tiroir : {drawers}.")
    val moved = s("moved", "Moved to {drawer}.", "Déplacé dans {drawer}.")
    val moveNoDrawer = s("move_no_drawer", "Robot didn't catch a drawer. Tap Move again and say one of these: {drawers}.",
        "Robot n'a pas compris le tiroir. Touche Déplacer de nouveau et dis l'un de ceux-ci : {drawers}.")
    val backInInbox = s("back_in_inbox", "Back in your Inbox.", "De retour dans ta boîte de réception.")
    val putBack = s("put_back", "Put back where it was.", "Remis où il était.")
    val notUndone = s("not_undone", "Robot couldn't undo that: the file changed since.", "Robot n'a pas pu annuler : le fichier a changé depuis.")
    val inInbox = s("in_inbox", "It's in your Inbox. {why}", "Il est dans ta boîte de réception. {why}")
    val askKind = s("ask_kind", "What kind of file is this?", "Quel genre de fichier est-ce?")
    val askKindOf = s("ask_kind_of", "Is this {a} or {b}?", "Est-ce {a} ou {b}?")
    val askTotal = s("ask_total", "Check the total: Robot couldn't be sure of it.", "Vérifie le total : Robot n'a pas pu en être sûr.")
    val askFileThem = s("ask_file_them", "Robot will ask before filing it.", "Robot demandera avant de le classer.")
    val heldOff = s("held_off", "Filing this kind of file is off in {page}.", "Le classement de ce genre de fichier est désactivé dans {page}.")
    val heldUnreadable = s("held_unreadable", "Robot couldn't read it.", "Robot n'a pas pu le lire.")
    val heldTooBig = s("held_too_big", "It's too big for Robot to read.", "Il est trop gros pour que Robot le lise.")
    val waitNoVision = s("wait_no_vision", "Reading pictures needs Fast or Smarter.", "Pour lire des images, il faut Rapide ou Plus futé.")
    val waitNoModel = s("wait_no_model", "Robot will file it once its model is ready.", "Robot le classera quand son modèle sera prêt.")
    val waitSmallContext = s("wait_small_context", "This model can't read a whole picture of a page: Fast or Smarter can.",
        "Ce modèle ne peut pas lire toute l'image d'une page : Rapide ou Plus futé le peuvent.")
    val waitLater = s("wait_later", "Robot will file it shortly.", "Robot le classera sous peu.")
    // FX2 (F13's note): a file as Robot says it aloud, never a name like "2026-08-14 Costco 84.12 CAD" read as it is
    // (FileMoneyText.said): its kind, its merchant and its day.
    val saidFrom = s("said_from", "{kind} from {merchant}", "{kind} {merchant}")
    val saidFromOn = s("said_from_on", "{kind} from {merchant} on {date}", "{kind} {merchant} du {date}")
    val saidOn = s("said_on", "{kind} from {date}", "{kind} du {date}")
    // FX2 (files-1.4-review.md finding 12): a duplicate's reply. Shown (History keeps it): where, never the file's name (the
    // sender's words); said: its name as Robot says it ([said]); in a drawer travel mode hides: neither.
    val duplicateShown = s("duplicate_shown", "You already have this file in {where}. To add it again, say “file it anyway”.",
        "Tu as déjà ce fichier dans {where}. Pour l'ajouter de nouveau, dis « classe-le quand même ».")
    val duplicateHidden = s("duplicate_hidden", "You already have this file. To add it again, say “file it anyway”.",
        "Tu as déjà ce fichier. Pour l'ajouter de nouveau, dis « classe-le quand même ».")
    val yourInbox = s("your_inbox", "your Inbox", "ta boîte de réception")

    /** F11: a file from someone else's backup waits in Inbox for you; Robot never files it on its own. */
    val askNotYours = s("ask_not_yours", "From a backup that isn't yours: file it where you want it.", "D'une sauvegarde qui n'est pas la tienne : classe-le où tu veux.")

    // The notifications (§4.1's table, §5.3): never on the lock screen; their actions need the phone unlocked.
    val channel = s("channel", "Robot Files", "Fichiers de Robot")
    val notePublic = s("note_public", "Robot", "Robot")
    val filedThings = plural("filed_things", "n", Plural("Robot filed {n} thing", "Robot filed {n} things"),
        Plural("Robot a classé {n} fichier", "Robot a classé {n} fichiers", "Robot a classé {n} de fichiers"))
    val newFiles = plural("new_files", "n", Plural("{n} new file: {kinds}. File it?", "{n} new files: {kinds}. File them?"),
        Plural("{n} nouveau fichier : {kinds}. Le classer?", "{n} nouveaux fichiers : {kinds}. Les classer?", "{n} de nouveaux fichiers : {kinds}. Les classer?"))
    val needYou = plural("need_you", "n", Plural("{n} file in your Inbox needs you.", "{n} files in your Inbox need you."),
        Plural("{n} fichier de ta boîte de réception a besoin de toi.", "{n} fichiers de ta boîte de réception ont besoin de toi.",
            "{n} de fichiers de ta boîte de réception ont besoin de toi."))
    val countReceipts = plural("count_receipts", "n", Plural("{n} receipt", "{n} receipts"), Plural("{n} reçu", "{n} reçus", "{n} de reçus"))
    val countBills = plural("count_bills", "n", Plural("{n} bill", "{n} bills"), Plural("{n} facture", "{n} factures", "{n} de factures"))
    val countDocuments = plural("count_documents", "n", Plural("{n} document", "{n} documents"), Plural("{n} document", "{n} documents", "{n} de documents"))
    val countPhotos = plural("count_photos", "n", Plural("{n} photo or video", "{n} photos and videos"),
        Plural("{n} photo ou vidéo", "{n} photos et vidéos", "{n} de photos et vidéos"))
    val countOther = plural("count_other", "n", Plural("{n} other file", "{n} other files"), Plural("{n} autre fichier", "{n} autres fichiers", "{n} d'autres fichiers"))
    val undo = s("undo", "Undo", "Annuler")
    val fileThem = s("file_them", "File them", "Les classer")
    val undoneThings = plural("undone_things", "n", Plural("{n} file is back in your Inbox.", "{n} files are back in your Inbox."),
        Plural("{n} fichier est de retour dans ta boîte de réception.", "{n} fichiers sont de retour dans ta boîte de réception.",
            "{n} de fichiers sont de retour dans ta boîte de réception."))

    // Your filing rules (§4.5, F8): their words in Settings › Memory, in the language you used ("Esso receipts go in Car").
    val ruleThingsOf = s("rule_things_of", "{merchant} {kinds}", "les {kinds} de {merchant}")
    val ruleThingsOfVowel = s("rule_things_of_vowel", "{merchant} {kinds}", "les {kinds} d'{merchant}")
    val ruleThingsWith = s("rule_things_with", "{kinds} with {words}", "les {kinds} avec {words}")
    val ruleThingsAll = s("rule_things_all", "{kinds}", "les {kinds}")
    val ruleDrawer = s("rule_drawer", "{things} go in {drawer}.", "{things} vont dans {drawer}.")
    val ruleTags = s("rule_tags", "{things} get the tag {tags}.", "{things} reçoivent l'étiquette {tags}.")
    val ruleNameFirst = s("rule_name_first", "{things} are named name first.", "Pour {things}, le nom vient avant la date.")
    val ruleDateFirst = s("rule_date_first", "{things} are named date first.", "Pour {things}, la date vient avant le nom.")
    val kindsReceipt = s("kinds_receipt", "receipts", "reçus")
    val kindsBill = s("kinds_bill", "bills", "factures")
    val kindsDocument = s("kinds_document", "documents", "documents")
    val kindsId = s("kinds_id", "IDs", "pièces d'identité")
    val kindsTaxSlip = s("kinds_tax_slip", "tax slips", "feuillets d'impôt")
    val kindsTicket = s("kinds_ticket", "tickets and bookings", "billets et réservations")
    val kindsMedical = s("kinds_medical", "medical files", "documents médicaux")
    val kindsPhoto = s("kinds_photo", "photos", "photos")
    val kindsVideo = s("kinds_video", "videos", "vidéos")
    val kindsNote = s("kinds_note", "notes", "notes")
    val kindsTranscript = s("kinds_transcript", "transcripts", "transcriptions")
    val kindsFile = s("kinds_file", "files", "fichiers")

    // Saving one: your words in the panel, a move or tags in Files. Confirmed once, with Undo.
    val ruleSaved = s("rule_saved", "Got it: from now on, {rule}", "Compris : à partir de maintenant, {rule}")
    val ruleKept = s("rule_kept", "Robot already does that: {rule}", "Robot le fait déjà : {rule}")
    val ruleDropped = s("rule_dropped", "Rule dropped. The file stays where you put it.", "Règle retirée. Le fichier reste où tu l'as mis.")
    val ruleDroppedSaid = s("rule_dropped_said", "Rule dropped.", "Règle retirée.")
    val ruleMemoryOff = s("rule_memory_off", "Memory is off, so Robot won't keep filing rules.", "La mémoire est désactivée, alors Robot ne garde pas de règles de classement.")
    val ruleMemoryFull = s("rule_memory_full", "Memory is full, so Robot can't keep this rule: delete some in {page}.",
        "La mémoire est pleine, alors Robot ne peut pas garder cette règle : supprimes-en dans {page}.")
    val ruleNoMemory = s("rule_no_memory", "Robot couldn't open its memory, so it didn't keep this rule.",
        "Robot n'a pas pu ouvrir sa mémoire, alors il n'a pas gardé cette règle.")
    val ruleCount = plural("rule_count", "n", Plural("{n} filing rule, in {page}", "{n} filing rules, in {page}"),
        Plural("{n} règle de classement, dans {page}", "{n} règles de classement, dans {page}", "{n} de règles de classement, dans {page}"))
    val ruleLabel = s("rule_label", "Filing rule", "Règle de classement")
    val rules = s("rules", "Filing rules", "Règles de classement")
    val ruleFollowed = s("rule_followed", "Robot Files follows it when it files. Delete it here and Robot stops.",
        "Les Fichiers de Robot la suivent pour classer. Supprime-la ici et Robot arrête.")
    val ruleNotYours = s("rule_not_yours", "Robot doesn't follow it: it isn't from your own words.", "Robot ne la suit pas : elle ne vient pas de tes propres mots.")
    val ruleNewer = s("rule_newer", "Robot doesn't follow it: a newer Robot wrote it.", "Robot ne la suit pas : un Robot plus récent l'a écrite.")
    val ruleSavedText = s("rule_saved_text", "Got it: Robot will file them that way from now on.", "Compris : Robot les classera ainsi à partir de maintenant.")
    val ruleKeptText = s("rule_kept_text", "Robot already files them that way.", "Robot les classe déjà ainsi.")
    val ruleIn = s("rule_in", "A filing rule, in {page}", "Une règle de classement, dans {page}")

    // The panel's other files phrases (FilesRouter): find, Inbox, move, delete. The text names drawers and counts only (History
    // keeps it); a file's name is on its card.
    val found = plural("found", "n", Plural("Found {n} file. It's in {drawer}.", "Found {n} files. The best match is in {drawer}."),
        Plural("{n} fichier trouvé. Il est dans {drawer}.", "{n} fichiers trouvés. Le plus pertinent est dans {drawer}.", "{n} de fichiers trouvés. Le plus pertinent est dans {drawer}."))
    val foundInInbox = plural("found_in_inbox", "n", Plural("Found {n} file. It's in your Inbox.", "Found {n} files. The best match is in your Inbox."),
        Plural("{n} fichier trouvé. Il est dans ta boîte de réception.", "{n} fichiers trouvés. Le plus pertinent est dans ta boîte de réception.",
            "{n} de fichiers trouvés. Le plus pertinent est dans ta boîte de réception."))
    val foundNone = s("found_none", "Robot found no file for that.", "Robot n'a trouvé aucun fichier pour ça.")
    val inboxEmpty = s("inbox_empty", "Your Inbox is empty.", "Ta boîte de réception est vide.")
    val inboxHolds = plural("inbox_holds", "n", Plural("{n} file is in your Inbox.", "{n} files are in your Inbox."),
        Plural("{n} fichier est dans ta boîte de réception.", "{n} fichiers sont dans ta boîte de réception.", "{n} de fichiers sont dans ta boîte de réception."))
    val showInFiles = s("show_in_files", "Show in Files", "Voir dans les Fichiers")
    val several = plural("several", "n", Plural("{n} file matches. Show it in Files to choose.", "{n} files match. Show them in Files to choose one."),
        Plural("{n} fichier correspond. Affiche-le dans les Fichiers pour choisir.", "{n} fichiers correspondent. Affiche-les dans les Fichiers pour en choisir un.",
            "{n} de fichiers correspondent. Affiche-les dans les Fichiers pour en choisir un."))
    val whichFile = s("which_file", "Robot doesn't know which file you mean.", "Robot ne sait pas de quel fichier tu parles.")
    val deleteAsk = s("delete_ask", "To delete it, tap Delete on the card. A spoken yes won't delete it.",
        "Pour le supprimer, touche Supprimer sur la carte. Un oui à voix haute ne le supprimera pas.")
    val deleteCard = s("delete_card", "Delete “{name}”? It stays in backups you made before.", "Supprimer « {name} »? Il reste dans les sauvegardes que tu as déjà faites.")
    val deleteButton = s("delete_button", "Delete", "Supprimer")
    val keep = s("keep", "Keep", "Garder")
    val kept = s("kept", "Kept.", "Gardé.")
    val deleted = s("deleted", "Deleted.", "Supprimé.")
    /** FX2: a delete card's Undo, in time: nothing was deleted. */
    val undeleted = s("undeleted", "The file is back.", "Le fichier est revenu.")
    val notDeleted = s("not_deleted", "Robot couldn't delete it: the file changed since.", "Robot n'a pas pu le supprimer : le fichier a changé depuis.")

    // F10: money and dates, answered by code (core files FileMoney). The reply's text, which History keeps, names no file or
    // merchant from a file: those go on the cards and in what Robot says (F8's rule). Amounts are Robot's sums.
    val spent = s("spent", "You spent {amount} {what} {when}, from {count}.", "Tu as dépensé {amount} {what} {when}, d'après {count}.")
    val spentNoWhen = s("spent_no_when", "You spent {amount} {what}, from {count}.", "Tu as dépensé {amount} {what}, d'après {count}.")
    val spentNoWhat = s("spent_no_what", "You spent {amount} {when}, from {count}.", "Tu as dépensé {amount} {when}, d'après {count}.")
    val spentAll = s("spent_all", "You spent {amount} in all, from {count}.", "Tu as dépensé {amount} en tout, d'après {count}.")
    val spentNone = s("spent_none", "Robot found no receipts for that.", "Robot n'a trouvé aucun reçu pour ça.")
    val spentNoneBills = s("spent_none_bills", "Robot found no bills for that.", "Robot n'a trouvé aucune facture pour ça.")
    val spentNoneBoth = s("spent_none_both", "Robot found no receipts or bills for that.", "Robot n'a trouvé aucun reçu ni aucune facture pour ça.")
    val spentCheck = plural("spent_check", "n", Plural("{n} total needs checking, so it's left out.", "{n} totals need checking, so they're left out."),
        Plural("{n} total doit être vérifié, alors il est laissé de côté.", "{n} totaux doivent être vérifiés, alors ils sont laissés de côté.",
            "{n} de totaux doivent être vérifiés, alors ils sont laissés de côté."))
    val countBoth = plural("count_both", "n", Plural("{n} receipt or bill", "{n} receipts and bills"), Plural("{n} reçu ou facture", "{n} reçus et factures",
        "{n} de reçus et factures"))
    val money = s("money", "{m}", "{m}")
    val atMerchant = s("at_merchant", "at {merchant}", "chez {merchant}")
    val onFuel = s("on_fuel", "on gas", "en essence")
    val onGroceries = s("on_groceries", "on groceries", "en épicerie")
    val onRestaurants = s("on_restaurants", "at restaurants", "au restaurant")
    val onPharmacy = s("on_pharmacy", "at the pharmacy", "à la pharmacie")
    val onHardware = s("on_hardware", "on hardware", "en quincaillerie")
    val onClothing = s("on_clothing", "on clothing", "en vêtements")
    val onElectronics = s("on_electronics", "on electronics", "en électronique")
    val onTransit = s("on_transit", "on transit", "en transport en commun")
    val onUtilities = s("on_utilities", "on utilities", "en électricité, Internet et téléphone")
    val onOther = s("on_other", "on other things", "en autres choses")
    val inMonth = s("in_month", "in {month}", "en {month}")
    val inYear = s("in_year", "in {year}", "en {year}")
    val onDay = s("on_day", "on {day}", "le {day}")
    val fromTo = s("from_to", "from {from} to {to}", "du {from} au {to}")
    /** F13: a date after a verb (« est due le 17 octobre »); today and tomorrow go without it. */
    val onDate = s("on_date", "{d}", "le {d}")
    /** F13: a merchant after « facture », « garantie » (« la facture de Bell », « d'Hydro-Québec »); the English is the name alone. */
    val ofMerchant = s("of_merchant", "{m}", "de {m}")
    val ofMerchantVowel = s("of_merchant_vowel", "{m}", "d'{m}")

    // What's coming up (§7.2): bills due, returns ending, warranties ending, expiry.
    val today = s("today", "today", "aujourd'hui")
    val tomorrow = s("tomorrow", "tomorrow", "demain")
    val thisWeek = s("this_week", "this week", "cette semaine")
    val nextWeek = s("next_week", "next week", "la semaine prochaine")
    val thisMonth = s("this_month", "this month", "ce mois-ci")
    val nextMonth = s("next_month", "next month", "le mois prochain")
    val nextDays = plural("next_days", "n", Plural("in the next {n} day", "in the next {n} days"), Plural("d'ici {n} jour", "dans les {n} prochains jours", "dans les {n} prochains jours"))
    val dueCount = plural("due_count", "n", Plural("{n} bill is due {span}.", "{n} bills are due {span}."),
        Plural("{n} facture est due {span}.", "{n} factures sont dues {span}.", "{n} de factures sont dues {span}."))
    val expiryCount = plural("expiry_count", "n", Plural("{n} document expires {span}.", "{n} documents expire {span}."),
        Plural("{n} document expire {span}.", "{n} documents expirent {span}.", "{n} de documents expirent {span}."))
    val returnCount = plural("return_count", "n", Plural("{n} return window ends {span}.", "{n} return windows end {span}."),
        Plural("{n} délai de retour se termine {span}.", "{n} délais de retour se terminent {span}.", "{n} de délais de retour se terminent {span}."))
    val warrantyCount = plural("warranty_count", "n", Plural("{n} warranty ends {span}.", "{n} warranties end {span}."),
        Plural("{n} garantie prend fin {span}.", "{n} garanties prennent fin {span}.", "{n} de garanties prennent fin {span}."))
    val noneDue = s("none_due", "No bills are due {span}.", "Aucune facture n'est due {span}.")
    val noneExpiry = s("none_expiry", "Nothing in your files expires {span}.", "Rien dans tes fichiers n'expire {span}.")
    val noneReturn = s("none_return", "No return windows end {span}.", "Aucun délai de retour ne se termine {span}.")
    val noneWarranty = s("none_warranty", "No warranties end {span}.", "Aucune garantie ne prend fin {span}.")
    val noneNamed = s("none_named", "Robot found no date for that in your files.", "Robot n'a trouvé aucune date pour ça dans tes fichiers.")
    val firstOn = s("first_on", "The soonest is {date}.", "La date la plus proche : {date}.")
    val dateDue = s("date_due", "Due {date}", "Échéance : {date}")
    val dateReturn = s("date_return", "Last day to return it: {date}", "Dernier jour pour le retourner : {date}")
    val dateWarranty = s("date_warranty", "Warranty until {date}", "Garantie jusqu'au {date}")
    val dateExpiry = s("date_expiry", "Expires {date}", "Expire le {date}")
    val dateAmount = s("date_amount", "{date}: {amount}", "{date}, {amount}")
    val sentence = s("sentence", "{s}.", "{s}.")
    /** Spoken, one file: "Hydro-Québec. Due October 17, 2026: $142.00". */
    val namedDate = s("named_date", "{name}. {date}", "{name}. {date}")

    // A reminder from a file (§7.2, D11): Add to my brief (kept in the file's entry, said only unlocked) or Add to calendar (add_event's card).
    val addToBrief = s("add_to_brief", "Add to my brief", "Ajouter à mon résumé du matin")
    val addToCalendar = s("add_to_calendar", "Add to calendar", "Ajouter à l'agenda")
    val notNow = s("not_now", "Not now", "Pas maintenant")
    val remindAsk = s("remind_ask", "Remind you in your morning brief, or add it to your calendar?", "Te le rappeler dans ton résumé du matin, ou l'ajouter à ton agenda?")
    val inBriefOn = s("in_brief_on", "In your brief on {day}.", "Dans ton résumé du matin du {day}.")
    val alreadyInBrief = s("already_in_brief", "It's already in your brief.", "C'est déjà dans ton résumé du matin.")
    val datePassed = s("date_passed", "That date has passed.", "Cette date est passée.")
    val calendarCard = s("calendar_card", "Tap Add on the calendar card: a spoken yes won't add it.", "Touche Ajouter sur la carte de l'agenda : un oui à voix haute ne l'ajoutera pas.")
    val eventDue = s("event_due", "Pay {merchant} bill", "Payer la facture {merchant}")
    val eventDueAny = s("event_due_any", "Pay a bill", "Payer une facture")
    val eventReturn = s("event_return", "Last day to return {merchant} purchase", "Dernier jour pour retourner l'achat chez {merchant}")
    val eventReturnAny = s("event_return_any", "Last day for a return", "Dernier jour pour un retour")
    val eventWarranty = s("event_warranty", "{merchant} warranty ends", "Fin de la garantie de l'achat chez {merchant}")
    val eventWarrantyAny = s("event_warranty_any", "A warranty ends", "Fin d'une garantie")
    val eventExpiryId = s("event_expiry_id", "ID expires", "Expiration d'une pièce d'identité")
    val eventExpiryDoc = s("event_expiry_doc", "Document expires", "Expiration d'un document")

    // The morning brief's files part (§7.2, BriefItem.FILES): only while the phone is unlocked. Shown (kept as text) without a
    // merchant; said with it, unless the file is private.
    val briefFiled = plural("brief_filed", "n", Plural("Robot filed {n} thing yesterday: {kinds}.", "Robot filed {n} things yesterday: {kinds}."),
        Plural("Robot a classé {n} fichier hier : {kinds}.", "Robot a classé {n} fichiers hier : {kinds}.", "Robot a classé {n} de fichiers hier : {kinds}."))
    val briefDue = s("brief_due", "A bill is due {day}: {amount}.", "Une facture est due {day} : {amount}.")
    val briefDueNoAmount = s("brief_due_no_amount", "A bill is due {day}.", "Une facture est due {day}.")
    val briefDueSaid = s("brief_due_said", "Your {merchant} bill is due {day}: {amount}.", "Ta facture {merchant} est due {day} : {amount}.")
    val briefDueSaidNoAmount = s("brief_due_said_no_amount", "Your {merchant} bill is due {day}.", "Ta facture {merchant} est due {day}.")
    val briefReturn = s("brief_return", "The return window for a purchase ends {day}.", "Le délai de retour d'un achat se termine {day}.")
    val briefReturnSaid = s("brief_return_said", "The return window for your {merchant} purchase ends {day}.", "Le délai de retour de ton achat chez {merchant} se termine {day}.")
    val briefWarranty = s("brief_warranty", "A warranty ends {day}.", "Une garantie prend fin {day}.")
    val briefWarrantySaid = s("brief_warranty_said", "The warranty on your {merchant} purchase ends {day}.", "La garantie de ton achat chez {merchant} prend fin {day}.")
    val briefExpiryId = s("brief_expiry_id", "An ID in Robot Files expires {day}.", "Une pièce d'identité dans les Fichiers de Robot expire {day}.")
    val briefExpiryDoc = s("brief_expiry_doc", "A document in Robot Files expires {day}.", "Un document dans les Fichiers de Robot expire {day}.")
    val briefMore = plural("brief_more", "n", Plural("{n} more reminder is in Robot Files.", "{n} more reminders are in Robot Files."),
        Plural("{n} autre rappel est dans les Fichiers de Robot.", "{n} autres rappels sont dans les Fichiers de Robot.", "{n} d'autres rappels sont dans les Fichiers de Robot."))

    // Subscriptions in your bills (§7.2): listed, never acted on.
    val subscriptions = plural("subscriptions", "n", Plural("{n} subscription in your bills: {amounts} a month.", "{n} subscriptions in your bills: {amounts} a month."),
        Plural("{n} abonnement dans tes factures : {amounts} par mois.", "{n} abonnements dans tes factures : {amounts} par mois.",
            "{n} d'abonnements dans tes factures : {amounts} par mois."))
    val subscriptionsCount = plural("subscriptions_count", "n", Plural("{n} subscription in your bills.", "{n} subscriptions in your bills."),
        Plural("{n} abonnement dans tes factures.", "{n} abonnements dans tes factures.", "{n} d'abonnements dans tes factures."))
    val subscriptionsNone = s("subscriptions_none", "Robot found no subscriptions in your bills: a merchant has to bill you {n} months in a row.",
        "Robot n'a trouvé aucun abonnement dans tes factures : un commerçant doit te facturer {n} mois de suite.")
    val subscriptionLine = s("subscription_line", "{name}: {amount} a month", "{name} : {amount} par mois")
    val subscriptionsList = s("subscriptions_list", "Your subscriptions: {list}.", "Tes abonnements : {list}.")

    // Clean-up suggestions (§7.4): why a file looks no longer needed; a delete is always your tap.
    val cleanDuplicate = s("clean_duplicate", "An exact copy of another file.", "Une copie exacte d'un autre fichier.")
    val cleanNear = s("clean_near", "The same merchant, date and total as another file.", "Le même commerçant, la même date et le même total qu'un autre fichier.")
    val cleanTicket = s("clean_ticket", "A ticket for a date that has passed.", "Un billet pour une date passée.")
    val cleanReturn = s("clean_return", "Its return window closed on {date}, and no warranty is running.", "Son délai de retour a pris fin le {date}, et aucune garantie ne court.")
    val cleanOldBill = s("clean_old_bill", "A bill more than {n} years old that isn't a tax file.", "Une facture de plus de {n} ans qui n'est pas un document fiscal.")
    val keepUntil = s("keep_until", "Keep until the end of {year}: the CRA and Revenu Québec ask you to keep tax records for six years.",
        "À garder jusqu'à la fin de {year} : l'ARC et Revenu Québec demandent de garder les documents fiscaux six ans.")

    // F12, travel mode: a file filed into a drawer it hides is said without its drawer or name.
    val filedQuiet = s("filed_quiet", "Filed.", "Classé.")
    /** Settings' Files row while travel mode is on: no counts. */
    val travelOnRow = s("travel_on_row", "Travel mode is on", "Le mode voyage est activé")

    // F12: the tags Robot knows (FileTags), as a file's page and a rule's words name them.
    val tagTax = s("tag_tax", "tax", "impôts")
    val tagBusiness = s("tag_business", "business", "affaires")
    val tagPersonal = s("tag_personal", "personal", "perso")

    // F12: the tax pack and the expense report (files-1.4-plan.md §7.2): what the panel says, the folder and the table.
    val packTax = s("pack_tax", "Your {year} tax pack: {what}, and a table of their details. Export it from Files; the copies won't be encrypted.",
        "Ton dossier d'impôts {year} : {what}, et un tableau de leurs détails. Exporte-le depuis les Fichiers; les copies ne seront pas chiffrées.")
    val packExpenses = s("pack_expenses", "Your {year} expense report: {what}, and a table of their details. Export it from Files; the copies won't be encrypted.",
        "Ton rapport de dépenses {year} : {what}, et un tableau de leurs détails. Exporte-le depuis les Fichiers; les copies ne seront pas chiffrées.")
    val packEmptyTax = s("pack_empty_tax", "No tax files for {year}. Slips, and files tagged tax or kept in Taxes, go in a tax pack.",
        "Aucun document fiscal pour {year}. Les feuillets, et les fichiers étiquetés impôts ou rangés dans Impôts, vont dans un dossier d'impôts.")
    val packEmptyExpenses = s("pack_empty_expenses", "No files tagged business for {year}.", "Aucun fichier étiqueté affaires pour {year}.")
    val packTitleTax = s("pack_title_tax", "Tax pack {year}", "Dossier d'impôts {year}")
    val packTitleExpenses = s("pack_title_expenses", "Expense report {year}", "Rapport de dépenses {year}")
    val packFolderTax = s("pack_folder_tax", "Robot tax pack {year}", "Dossier d'impôts {year} de Robot")
    val packFolderExpenses = s("pack_folder_expenses", "Robot expense report {year}", "Rapport de dépenses {year} de Robot")
    val csvFile = s("csv_file", "File", "Fichier")
    val csvKind = s("csv_kind", "Kind", "Genre")
    val csvDate = s("csv_date", "Date", "Date")
    val csvMerchant = s("csv_merchant", "Merchant", "Commerçant")
    val csvTotal = s("csv_total", "Total", "Total")
    val csvCurrency = s("csv_currency", "Currency", "Devise")
    val csvGst = s("csv_gst", "GST", "TPS")
    val csvHst = s("csv_hst", "HST", "TVH")
    val csvPst = s("csv_pst", "PST", "TVP")
    val csvQst = s("csv_qst", "QST", "TVQ")
    val csvSlip = s("csv_slip", "Slip", "Feuillet")
    val csvTaxYear = s("csv_tax_year", "Tax year", "Année d'imposition")
    val csvDrawer = s("csv_drawer", "Drawer", "Tiroir")
    val csvNote = s("csv_note", "Note", "Note")
    val csvCheckTotal = s("csv_check_total", "Check the total", "Vérifie le total")
    val csvNotCopied = s("csv_not_copied", "Not copied", "Pas copié")

    /** The table's column names, in [app.heyrobot.core.files.FilePack]'s order. */
    fun csvColumns(): List<Phrase> = listOf(csvFile(), csvKind(), csvDate(), csvMerchant(), csvTotal(), csvCurrency(), csvGst(), csvHst(), csvPst(), csvQst(),
        csvSlip(), csvTaxYear(), csvDrawer(), csvNote())

    /** [m] after « facture » or « garantie »: « de Bell », « d'Esso », « d'Hydro-Québec » (a vowel or a mute h elides); [m] in English. */
    fun of(m: String): Phrase = (if (elides(m)) ofMerchantVowel else ofMerchant)("m" to m)

    /**
     * Whether French elides « de » before [word]: a vowel (« d'Esso », « d'IKEA », « d'Yves Rocher »), or an h that is mute,
     * which an English or brand name's never is (FX2, F13's note: « de Home Depot », « de H&M », « de Hyundai »). Mute: a
     * French word (an accent right after the h, « d'Hôtel-Dieu ») or one of [MUTE_H]'s (« d'Hydro-Québec »). FilingRules too.
     */
    fun elides(word: String): Boolean {
        val w = word.trim()
        val first = java.text.Normalizer.normalize(w.take(1), java.text.Normalizer.Form.NFD).take(1).lowercase()
        if (first in setOf("a", "e", "i", "o", "u", "y")) return true
        if (first != "h") return false
        if (w.length > 1 && java.text.Normalizer.normalize(w.substring(1, 2), java.text.Normalizer.Form.NFD).length > 1) return true
        val folded = java.text.Normalizer.normalize(w.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") // l10n: not text (a pattern)
        return MUTE_H.any { folded.startsWith(it) }
    }

    /** French words with a mute h a merchant's name may start with (folded); every other h is aspirated (Home Depot, H&M, Hyundai). */
    private val MUTE_H = listOf("hydro", "hotel", "hopital", "huile", "habitation", "hebergement", "heritage", "histoire", "horaire", "hypotheque", // l10n: not text (words to match)
        "hygiene", "harmonie", "herboristerie", "horticulture", "humanite", "hippodrome", "hospitalite", "hema-quebec") // l10n: not text (words to match)

    /** A tag as Files shows it: a known tag's word in the menus' language, any other as you typed it. */
    fun tag(t: String): Phrase = when (t) {
        app.heyrobot.core.files.FileTags.TAX -> tagTax()
        app.heyrobot.core.files.FileTags.BUSINESS -> tagBusiness()
        app.heyrobot.core.files.FileTags.PERSONAL -> tagPersonal()
        else -> Phrase.plain(t)
    }

    /** A starter drawer's name for its id, or the drawer as you named it. */
    fun drawer(id: String): Phrase = when (id) {
        Drawers.RECEIPTS -> drawerReceipts()
        Drawers.BILLS -> drawerBills()
        Drawers.DOCUMENTS -> drawerDocuments()
        Drawers.MEDICAL -> drawerMedical()
        Drawers.TAXES -> drawerTaxes()
        Drawers.TRIPS -> drawerTrips()
        Drawers.PHOTOS -> drawerPhotos()
        Drawers.CONVERSATIONS -> drawerConversations()
        else -> Phrase.plain(id)
    }

    /** "Receipts, Bills, … or Conversations": the starter drawers, for Move's question. */
    fun drawers(): Phrase = Phrase.ListOf(Drawers.STARTER.map(::drawer), ListKind.OR)

    /** A kind as a question offers it: "a receipt". */
    fun kind(k: FileKind): Phrase = when (k) {
        FileKind.RECEIPT -> kindReceipt()
        FileKind.BILL -> kindBill()
        FileKind.DOCUMENT -> kindDocument()
        FileKind.ID -> kindId()
        FileKind.TAX_SLIP -> kindTaxSlip()
        FileKind.TICKET -> kindTicket()
        FileKind.MEDICAL -> kindMedical()
        FileKind.PHOTO -> kindPhoto()
        FileKind.VIDEO -> kindVideo()
        FileKind.NOTE -> kindNote()
        FileKind.TRANSCRIPT -> kindTranscript()
        else -> kindOther()
    }

    /** Robot's question or reason for a file that stays in Inbox ([choices]: the kinds a KIND question weighs). */
    fun why(why: FilingWhy?, choices: List<FileKind> = emptyList()): Phrase = when (why) {
        FilingWhy.KIND -> if (choices.size >= 2) askKindOf("a" to Arg.Sub(kind(choices[0])), "b" to Arg.Sub(kind(choices[1]))) else askKind()
        FilingWhy.TOTAL -> askTotal()
        FilingWhy.FILE_THEM -> askFileThem()
        FilingWhy.OFF -> heldOff("page" to Arg.Ui(UiStrings.inSettings(UiStrings.files)))
        FilingWhy.UNREADABLE -> heldUnreadable()
        FilingWhy.TOO_BIG -> heldTooBig()
        FilingWhy.DANGEROUS -> dangerous()
        FilingWhy.NO_VISION -> waitNoVision()
        FilingWhy.NO_MODEL -> waitNoModel()
        FilingWhy.SMALL_CONTEXT -> waitSmallContext()
        FilingWhy.NOT_YOURS -> askNotYours()
        FilingWhy.UNKNOWN, null -> waitLater()
    }

    /** "2 receipts, 1 bill and 1 photo": what a batch holds, by level group, in the order Files shows drawers. */
    fun kinds(of: List<FileKind>): Phrase {
        fun n(vararg ks: FileKind) = of.count { it in ks }.toLong()
        val parts = listOf(countReceipts to n(FileKind.RECEIPT), countBills to n(FileKind.BILL),
            countDocuments to n(FileKind.DOCUMENT, FileKind.ID, FileKind.TAX_SLIP, FileKind.TICKET, FileKind.MEDICAL),
            countPhotos to n(FileKind.PHOTO, FileKind.VIDEO), countOther to n(FileKind.NOTE, FileKind.TRANSCRIPT, FileKind.OTHER, FileKind.UNKNOWN, FileKind.DANGEROUS))
        return Phrase.ListOf(parts.filter { it.second > 0 }.map { (m, c) -> m(c) })
    }

    /** Why [p] stopped an add, in words; [need]: for LOW_SPACE, the bytes still needed. */
    fun problem(p: IntakeProblem, need: Long = 0): Phrase = when (p) {
        IntakeProblem.LOCKED -> locked()
        IntakeProblem.KEY_GONE -> keyGone("page" to Arg.Ui(UiStrings.inSettings(UiStrings.files)))
        IntakeProblem.INDEX -> index("page" to Arg.Ui(UiStrings.inSettings(UiStrings.files)))
        IntakeProblem.FULL -> full("max" to Arg.Count(FileLimits.MAX_FILES.toLong()))
        IntakeProblem.TOO_BIG -> tooBig("max" to Arg.Bytes(FileLimits.MAX_FILE))
        IntakeProblem.LOW_SPACE -> lowSpace("need" to Arg.Bytes(need.coerceAtLeast(1)))
        IntakeProblem.TIMED_OUT -> timedOut()
        IntakeProblem.UNREADABLE -> unreadable()
        IntakeProblem.EMPTY -> empty()
        IntakeProblem.IO -> io()
        IntakeProblem.NOT_ALLOWED -> notAllowed()
        IntakeProblem.OFF -> off()
    }

    /** "Robot takes 20 files at a time, so 3 more were left out." */
    fun leftOutOf(n: Int): Phrase = leftOut(n, "max" to Arg.Count(IntakeLimits.MAX_ITEMS.toLong()))

    /** Where a file is: "Inbox › receipt.pdf", or its drawer's name for a filed one. */
    fun where(drawer: String?, name: String): Phrase = Phrase.join(drawer?.let(::drawer) ?: inbox(),
        if (name.isBlank()) noName() else Phrase.plain(name), sep = " › ")
}
