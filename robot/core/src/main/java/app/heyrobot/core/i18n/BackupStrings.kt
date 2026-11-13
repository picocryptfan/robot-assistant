// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Back up and restore's catalog (1.1): the sentences core's backup code returns as
// codes (OpenProblem, PlanSkip, SkipWhy, the categories), worded once here. English and French; ids start with "backup.". core/backup/BackupWords.kt picks the entry for a code.

object BackupStrings : Strings("backup", area = "backup") {
    // The categories, as Back up now's list and the preview name them.
    val settings = s("cat_settings", en = "Settings", fr = "Réglages")
    val people = s("cat_people", en = "People", fr = "Personnes")
    val memory = s("cat_memory", en = "Memory", fr = "Mémoire")
    val handsfree = s("cat_handsfree", en = "Headset, car and places", fr = "Écouteurs, auto et lieux")
    val routines = s("cat_routines", en = "Routines and the morning brief", fr = "Routines et le résumé du matin")
    val skills = s("cat_skills", en = "Skills", fr = "Compétences")
    val phoneLock = s("cat_phone_lock", en = "Phone lock", fr = "Blocage du téléphone")
    val notes = s("cat_notes", en = "Notes", fr = "Notes")
    val history = s("cat_history", en = "History", fr = "Historique")
    val transcripts = s("cat_transcripts", en = "Transcripts", fr = "Transcriptions")
    /** 1.1 (15 Oct decision): your voiceprint, optional and off. */
    val voiceprint = s("cat_voiceprint", en = "Your voiceprint", fr = "Ton empreinte vocale")
    val keys = s("cat_keys", en = "Keys and private codes", fr = "Clés et codes privés")
    /** 1.4, Robot Files (F11): your files, in the backup's own encryption. */
    val files = s("cat_files", en = "Files", fr = "Fichiers")
    /** 1.4, Robot Lists (LS7): your lists and Settings › Lists, in the backup's own encryption. */
    val lists = s("cat_lists", en = "Lists", fr = "Listes")
    val logbook = s("cat_logbook", en = "Logbook", fr = "Carnet")
    /** Robot to Robot (L12): pairings without keys, permissions, what passed between Robots. */
    val links = s("cat_links", en = "Robot to Robot", fr = "Robot à Robot")
    /** The everyday set (everyday-set-plan.md §2.4): everyday_settings.json, music and what other apps may use. */
    /** H3: private events. */
    val calendar = s("cat_calendar", en = "Private events", fr = "Événements privés")
    /** H5: the Today page's order and whether it is your home page. */
    val home = s("cat_home", en = "Today page", fr = "Page Aujourd’hui")
    /** earbud-headsup-c: the calendar heads-up in earbuds (Settings › Headset). */
    val headsUp = s("cat_headsup", en = "Calendar heads-up", fr = "Rappels d’agenda")
    val everyday = s("cat_everyday", en = "Music and other apps", fr = "Musique et autres applis")

    // Opening a file (§9.4).
    val locked = s("locked", en = "Unlock your phone to back up or restore.", fr = "Déverrouille ton téléphone pour sauvegarder ou restaurer.")
    val notABackup = s("not_a_backup", en = "This file isn't a Robot backup, or it's damaged.", fr = "Ce fichier n’est pas une sauvegarde de Robot, ou il est endommagé.")
    val newerFormat = s("newer_format", en = "This backup was made by a newer Robot. Update Robot, then restore it.", fr = "Cette sauvegarde a été faite par un Robot plus récent. Mets Robot à jour, puis restaure-la.")
    val kdfRefused = s("kdf_refused", en = "This backup asks for more memory than Robot allows, so it can't be opened.", fr = "Cette sauvegarde demande plus de mémoire que Robot le permet : elle ne peut pas être ouverte.")
    val wrongPassphrase = s("wrong_passphrase", en = "That passphrase doesn't open this backup. Check it and try again.", fr = "Cette phrase de passe n’ouvre pas cette sauvegarde. Vérifie-la et réessaie.")
    val changed = s("changed", en = "This backup was changed or damaged after it was made, so Robot won't restore any of it.", fr = "Cette sauvegarde a été modifiée ou endommagée après sa création : Robot n’en restaurera rien.")
    val incomplete = s("incomplete", en = "This backup is incomplete: it ends too early. Copy it again from where you saved it.", fr = "Cette sauvegarde est incomplète : elle se termine trop tôt. Copie-la de nouveau depuis l’endroit où tu l’as enregistrée.")
    val tooBig = s("too_big", en = "This backup is larger than Robot can restore.", fr = "Cette sauvegarde est plus grosse que ce que Robot peut restaurer.")
    val damaged = s("damaged", en = "This backup is damaged.", fr = "Cette sauvegarde est endommagée.")
    val noSpace = s("no_space", en = "There isn't enough free space: Robot needs about {size} more to restore this backup.", fr = "Il n’y a pas assez d’espace libre : Robot a besoin d’environ {size} de plus pour restaurer cette sauvegarde.")
    val recording = s("recording", en = "Stop the transcription first.", fr = "Arrête d’abord la transcription.")

    // Saving one.
    val cantWrite = s("cant_write", en = "Robot couldn't save the backup there. Try another place, like Downloads or a USB drive.", fr = "Robot n’a pas pu enregistrer la sauvegarde à cet endroit. Essaie un autre endroit, comme Téléchargements ou une clé USB.")
    val readBackFailed = s("read_back_failed", en = "The saved file doesn't read back correctly, so Robot deleted it. Try another place.", fr = "Le fichier enregistré ne se relit pas correctement : Robot l’a supprimé. Essaie un autre endroit.")
    val readBackRefused = s("read_back_refused", en = "A restore would leave out {category} from the saved file, so Robot deleted it. Back up again without {category}.", fr = "Une restauration laisserait de côté la partie {category} du fichier enregistré : Robot l’a supprimé. Sauvegarde de nouveau sans cette partie.")
    val overCap = s("over_cap", en = "{category} holds more than a backup can bring back. Back up without {category}, or delete some first.", fr = "La partie {category} contient plus qu’une sauvegarde peut ramener. Sauvegarde sans cette partie, ou supprimes-en d’abord.")
    val memoryUnreadable = s("memory_unreadable", en = "Robot couldn't open its memory just now. Back up without Memory, or unlock and try again.", fr = "Robot n’a pas pu ouvrir sa mémoire pour l’instant. Sauvegarde sans la Mémoire, ou déverrouille et réessaie.")
    /** 1.4: Files was chosen and its list can't be opened now (locked, its key gone, damaged). */
    val filesUnreadable = s("files_unreadable", en = "Robot couldn't open your files just now. Back up without Files, or unlock and try again.",
        fr = "Robot n’a pas pu ouvrir tes fichiers pour l’instant. Sauvegarde sans les Fichiers, ou déverrouille et réessaie.")
    /** 1.4, Lists: Lists was chosen and the lists can't be opened now (locked, their key gone, damaged). */
    val listsUnreadable = s("lists_unreadable", en = "Robot couldn't open your lists just now. Back up without Lists, or unlock and try again.",
        fr = "Robot n’a pas pu ouvrir tes listes pour l’instant. Sauvegarde sans les Listes, ou déverrouille et réessaie.")
    /** spoken-log: the Logbook was chosen and can't be opened now. */
    val logbookUnreadable = s("logbook_unreadable", en = "Robot couldn't open your logbook just now. Back up without Logbook, or unlock and try again.",
        fr = "Robot n’a pas pu ouvrir ton carnet pour l’instant. Sauvegarde sans le Carnet, ou déverrouille et réessaie.")
    /** seal-history-fix-b: History was chosen and a conversation can't be opened now. */
    val historyNotNow = s("history_not_now", en = "Robot couldn't open part of your History just now. Unlock, wait a minute and try again, or back up without History.",
        fr = "Robot n’a pas pu ouvrir une partie de ton historique pour l’instant. Déverrouille, attends une minute et réessaie, ou sauvegarde sans l’Historique.")
    /** fix-seal-transcripts 6: a transcript sealed under the unlock key met while the phone is locked. */
    val transcriptsLocked = s("transcripts_locked", en = "Robot can't open your transcripts while the phone is locked. Unlock it and try again, or back up without Transcripts.",
        fr = "Robot ne peut pas ouvrir tes transcriptions pendant que le téléphone est verrouillé. Déverrouille-le et réessaie, ou sauvegarde sans les Transcriptions.")
    /** fix-seal-transcripts-b, Medium 1: the phone is unlocked and Robot's key for transcripts still failed (logged). */
    val transcriptsUnreadable = s("transcripts_unreadable", en = "Robot couldn't open your transcripts, though the phone is unlocked. Restart the phone and try again, or back up without Transcripts.",
        fr = "Robot n’a pas pu ouvrir tes transcriptions, même si le téléphone est déverrouillé. Redémarre le téléphone et réessaie, ou sauvegarde sans les Transcriptions.")
    /** seal-history-fix-b: History holds a newer Robot's file, which this one can't read. */
    val historyTooNew = s("history_too_new", en = "Part of your History was saved by a newer Robot, and this one can't read it. Update Robot, or delete those conversations with Delete these in History, then try again. Or back up without History.",
        fr = "Une partie de ton historique a été enregistrée par un Robot plus récent et ne peut pas être lue ici. Mets Robot à jour, ou supprime ces conversations avec Supprimer celles-ci dans Historique, puis réessaie. Ou sauvegarde sans l’Historique.")
    /** seal-notes-b: Notes holds a newer Robot's note, which this one can't read (the Notes card counts them, with Delete these). */
    val notesTooNew = s("notes_too_new", en = "Some of your notes were saved by a newer Robot, and this one can't read them. Update Robot, or delete those notes with Delete these in Notes, then try again. Or back up without Notes.",
        fr = "Certaines de tes notes ont été enregistrées par un Robot plus récent et ne peuvent pas être lues ici. Mets Robot à jour, ou supprime ces notes avec Supprimer celles-ci dans Notes, puis réessaie. Ou sauvegarde sans les Notes.")
    /** seal-people: People was chosen and can't be opened now. */
    val peopleUnreadable = s("people_unreadable", en = "Robot couldn't open People just now. Unlock, wait a minute and try again, or back up without People.",
        fr = "Robot n’a pas pu ouvrir tes Personnes pour l’instant. Déverrouille, attends une minute et réessaie, ou sauvegarde sans les Personnes.")
    /** seal-people: People's file is a newer Robot's, or can't be read (its card in People offers Start over). */
    val peopleTooNew = s("people_too_new", en = "Robot can't read your People file: a newer Robot saved it, or it is damaged. Update Robot, or use Start over in People, then try again. Or back up without People.",
        fr = "Robot ne peut pas lire ton fichier de Personnes : un Robot plus récent l’a enregistré, ou il est endommagé. Mets Robot à jour, ou utilise Recommencer dans les Personnes, puis réessaie. Ou sauvegarde sans les Personnes.")
    /** seal-notes: Notes was chosen and a note can't be opened now. */
    val notesUnreadable = s("notes_unreadable", en = "Robot couldn't open part of your notes just now. Unlock, wait a minute and try again, or back up without Notes.",
        fr = "Robot n’a pas pu ouvrir une partie de tes notes pour l’instant. Déverrouille, attends une minute et réessaie, ou sauvegarde sans les Notes.")
    /** 1.4: refused before anything is written, with the size (BackupCaps.MAX_TOTAL). */
    val tooBigToSave = s("too_big_to_save", en = "This backup would be about {size}, more than the {max} a backup can hold. Leave out videos, recordings or another part.",
        fr = "Cette sauvegarde ferait environ {size}, plus que les {max} qu’une sauvegarde peut contenir. Laisse de côté les vidéos, les enregistrements ou une autre partie.")
    val keyUnreadable = s("key_unreadable", en = "{helper}'s key couldn't be read just now, so it isn't in the backup.", fr = "La clé de {helper} n’a pas pu être lue pour l’instant : elle n’est pas dans la sauvegarde.")

    // A category that won't be restored (the preview and the result).
    val categoryNewer = s("category_newer", en = "{category} needs Robot {version} or later: update Robot, then restore again to bring it.", fr = "La partie {category} demande Robot {version} ou plus récent : mets Robot à jour, puis restaure de nouveau pour l’ajouter.")
    val categoryUnknown = s("category_unknown", en = "Part of this backup needs a newer Robot: update Robot, then restore again to bring it.", fr = "Une partie de cette sauvegarde demande un Robot plus récent : mets Robot à jour, puis restaure de nouveau pour l’ajouter.")
    val categoryRefused = s("category_refused", en = "{category} in this backup is damaged, so Robot leaves it out.", fr = "Dans cette sauvegarde, la partie {category} est endommagée : Robot la laisse de côté.")
    val categoryTooBig = s("category_too_big", en = "{category} in this backup is larger than Robot can restore, so Robot leaves it out.", fr = "Dans cette sauvegarde, la partie {category} est trop grosse pour que Robot la restaure : Robot la laisse de côté.")
    val categoryFailed = s("category_failed", en = "{category}: not restored, and nothing of it changed. Try again.", fr = "{category} : rien n’a été restauré, et rien n’a changé. Réessaie.")
    val transcriptsRecording = s("transcripts_recording", en = "A transcription started during the restore, so Robot left your transcripts as they are. Restore them again once it ends.", fr = "Une transcription a commencé pendant la restauration : Robot a laissé tes transcriptions telles quelles. Restaure-les de nouveau quand elle sera finie.")
    val historyOff = s("history_off", en = "History is off on this phone: turn it on to restore your conversations.", fr = "L’historique est désactivé sur ce téléphone : active-le pour restaurer tes conversations.")
    val memoryOff = s("memory_off", en = "Memory is off on this phone: turn it on to restore what Robot remembers.", fr = "La Mémoire est désactivée sur ce téléphone : active-la pour restaurer ce dont Robot se souvient.")
    val needsLockCode = s("needs_lock_code", en = "This phone has a lock code: enter it to restore Phone lock.",
        fr = "Ce téléphone a un code de blocage : entre-le pour restaurer le blocage du téléphone.")
    val voiceprintHere = s("voiceprint_here", en = "This phone already has a voiceprint of its own, so Robot keeps it.",
        fr = "Ce téléphone a déjà sa propre empreinte vocale : Robot la garde.")
    /** 1.4: this phone's list of files can't be read (damaged, a newer Robot's), and a restore never writes over it. */
    val filesUnreadableHere = s("files_unreadable_here", en = "Robot can't read the list of files on this phone, so it leaves Files out. {page} can start over; then restore again.",
        fr = "Robot ne peut pas lire la liste des fichiers de ce téléphone : il laisse les Fichiers de côté. {page} permet de recommencer; restaure ensuite de nouveau.")
    /** F46: Keys, some of which couldn't be saved on this phone. */
    val keysMissed = plural("keys_missed", "n", Plural("restored, except {n} key that couldn't be saved. Enter it again in Settings.", "restored, except {n} keys that couldn't be saved. Enter them again in Settings."),
        Plural("restaurées, sauf {n} clé qui n’a pas pu être enregistrée. Saisis-la de nouveau dans les Réglages.", "restaurées, sauf {n} clés qui n’ont pas pu être enregistrées. Saisis-les de nouveau dans les Réglages.", "restaurées, sauf {n} de clés qui n’ont pas pu être enregistrées. Saisis-les de nouveau dans les Réglages."))
    /** 1.4: Files with Replace on: how many of this phone's files go (the preview, before your tap). */
    val filesDeleted = plural("files_deleted", "n", Plural("{n} file here will be deleted.", "{n} files here will be deleted."),
        Plural("{n} fichier ici sera supprimé.", "{n} fichiers ici seront supprimés.", "{n} de fichiers ici seront supprimés."))
    /** 1.4: Files when robot_files_v1 is gone: the files here can never be read again, so a restore replaces them. */
    /** 1.4 F12: travel mode is on here, so a restore leaves Robot Files as it is. */
    val filesTravelHere = s("files_travel_here", en = "Travel mode is on, so Robot leaves Files as they are. Turn it off in {page}, then restore again.",
        fr = "Le mode voyage est activé, alors Robot laisse les Fichiers tels quels. Désactive-le dans {page}, puis restaure de nouveau.")
    val filesKeyGone = plural("files_key_gone", "n", Plural("Robot's files key is gone, so {n} file here can't be read: it will be deleted.",
        "Robot's files key is gone, so {n} files here can't be read: they will be deleted."),
        Plural("La clé des fichiers de Robot n’existe plus, alors {n} fichier ici ne peut pas être lu : il sera supprimé.",
            "La clé des fichiers de Robot n’existe plus, alors {n} fichiers ici ne peuvent pas être lus : ils seront supprimés.",
            "La clé des fichiers de Robot n’existe plus, alors {n} de fichiers ici ne peuvent pas être lus : ils seront supprimés."))
    /** H3: private events (the Calendar tab). */
    val calendarUnreadable = s("calendar_unreadable", en = "Robot couldn't open your private events just now. Back up without them, or unlock and try again.",
        fr = "Robot n’a pas pu ouvrir tes événements privés pour l’instant. Sauvegarde sans eux, ou déverrouille et réessaie.")
    val calendarUnreadableHere = s("calendar_unreadable_here", en = "Robot can't read the private events on this phone, so it leaves them as they are.",
        fr = "Robot ne peut pas lire les événements privés de ce téléphone : il les laisse tels quels.")
    /** 1.4, Lists: this phone's lists can't be read (damaged, a newer Robot's), and a restore never writes over them. */
    val listsUnreadableHere = s("lists_unreadable_here", en = "Robot can't read the lists on this phone, so it leaves Lists out. {page} can start over; then restore again.",
        fr = "Robot ne peut pas lire les listes de ce téléphone : il laisse les Listes de côté. {page} permet de recommencer; restaure ensuite de nouveau.")
    /** seal-people-fix 1: this phone's People can't be opened just now, and a restore leaves it as it is. */
    val peopleNotNowHere = s("people_not_now_here", en = "Robot can't open People on this phone just now, so it leaves People out. Unlock, wait a minute and restore again.",
        fr = "Robot ne peut pas ouvrir les Personnes de ce téléphone pour l’instant : il les laisse de côté. Déverrouille, attends une minute et restaure de nouveau.")
    /** seal-people-fix 1: this phone's People is damaged or a newer Robot's, and a restore never writes over it. */
    val peopleUnreadableHere = s("people_unreadable_here", en = "Robot can't read People on this phone: a newer Robot saved it, or it is damaged. It leaves People out. Update Robot, or use Start over in People, then restore again.",
        fr = "Robot ne peut pas lire les Personnes de ce téléphone : un Robot plus récent les a enregistrées, ou elles sont endommagées. Il les laisse de côté. Mets Robot à jour, ou utilise Recommencer dans les Personnes, puis restaure de nouveau.")
    /** 1.4, Lists with Replace on: how many of this phone's lists go (the preview, before your tap). */
    // fix-seal-14-e 1: People's entries the backup holds that don't read; the rest restore, these stay in the file.
    val peopleSetAside = plural("people_set_aside", "n", Plural("{n} entry in People can't be read and won't come back. The backup still has it.",
        "{n} entries in People can't be read and won't come back. The backup still has them."),
        Plural("{n} entrée dans Personnes ne peut pas être lue et ne reviendra pas. La sauvegarde la garde.",
            "{n} entrées dans Personnes ne peuvent pas être lues et ne reviendront pas. La sauvegarde les garde.",
            "{n} d’entrées dans Personnes ne peuvent pas être lues et ne reviendront pas. La sauvegarde les garde."))
    val listsReplaced = plural("lists_replaced", "n", Plural("{n} list here will be replaced.", "{n} lists here will be replaced."),
        Plural("{n} liste ici sera remplacée.", "{n} listes ici seront remplacées.", "{n} de listes ici seront remplacées."))
    /** 1.4, Lists when their key is gone: the lists here can't be read, so a restore keeps them aside and brings the backup's. */
    val listsKeyGone = s("lists_key_gone", en = "Robot's lists key is gone, so the lists here can't be read. Robot keeps them aside and brings back the backup's.",
        fr = "La clé des listes de Robot n’existe plus, alors les listes ici ne peuvent pas être lues. Robot les met de côté et ramène celles de la sauvegarde.")
    val notYours = s("not_yours", en = "Keys, private codes and your voiceprint come back only from a backup marked as your own.", fr = "Les clés, les codes privés et l’empreinte vocale reviennent seulement d’une sauvegarde marquée comme la tienne.")

    // The monthly reminder: a notification, nothing personal in it.
    val remindChannel = s("remind_channel", en = "Backup reminders", fr = "Rappels de sauvegarde")
    val remindChannelDetail = s("remind_channel_detail", en = "Once a month, if you turned it on in Settings › Back up and restore.",
        fr = "Une fois par mois, si tu l’as activé dans Réglages › Sauvegarde et restauration.")
    val remindTitle = s("remind_title", en = "Time to back up Robot", fr = "C’est le temps de sauvegarder Robot")
    val remindDays = plural("remind_days", "n", Plural("Your last backup was {n} day ago.", "Your last backup was {n} days ago."),
        Plural("Ta dernière sauvegarde date d’il y a {n} jour.", "Ta dernière sauvegarde date d’il y a {n} jours.", "Ta dernière sauvegarde date d’il y a {n} de jours."))
    val remindNever = s("remind_never", en = "You haven't backed up Robot yet.", fr = "Tu n’as pas encore sauvegardé Robot.")

    // What a restore leaves in your data.
    /** A routine whose name is taken here. */
    val restoredName = s("restored_name", en = "{name} (restored)", fr = "{name} (restaurée)")
    /** History's line under a conversation restored from someone else's file (its record is Quoted.RESTORED_SUMMARY). */
    val restoredRecord = s("restored_record", en = "Restored from a backup file", fr = "Restauré à partir d’un fichier de sauvegarde")
}
