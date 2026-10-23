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
    val memoryUnreadable = s("memory_unreadable", en = "Robot couldn't open its memory just now. Back up without Memory, or unlock and try again.", fr = "Robot n’a pas pu ouvrir sa mémoire pour l’instant. Sauvegarde sans la Mémoire, ou déverrouille et réessaie.")
    /** 1.4: Files was chosen and its list can't be opened now (locked, its key gone, damaged). */
    val filesUnreadable = s("files_unreadable", en = "Robot couldn't open your files just now. Back up without Files, or unlock and try again.",
        fr = "Robot n’a pas pu ouvrir tes fichiers pour l’instant. Sauvegarde sans les Fichiers, ou déverrouille et réessaie.")
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
    val historyOff = s("history_off", en = "History is off on this phone: turn it on to restore your conversations.", fr = "L’historique est désactivé sur ce téléphone : active-le pour restaurer tes conversations.")
    val memoryOff = s("memory_off", en = "Memory is off on this phone: turn it on to restore what Robot remembers.", fr = "La Mémoire est désactivée sur ce téléphone : active-la pour restaurer ce dont Robot se souvient.")
    val needsLockCode = s("needs_lock_code", en = "This phone has a lock code: enter it to restore Phone lock.",
        fr = "Ce téléphone a un code de blocage : entre-le pour restaurer le blocage du téléphone.")
    val voiceprintHere = s("voiceprint_here", en = "This phone already has a voiceprint of its own, so Robot keeps it.",
        fr = "Ce téléphone a déjà sa propre empreinte vocale : Robot la garde.")
    /** 1.4: this phone's list of files can't be read (damaged, a newer Robot's), and a restore never writes over it. */
    val filesUnreadableHere = s("files_unreadable_here", en = "Robot can't read the list of files on this phone, so it leaves Files out. {page} can start over; then restore again.",
        fr = "Robot ne peut pas lire la liste des fichiers de ce téléphone : il laisse les Fichiers de côté. {page} permet de recommencer; restaure ensuite de nouveau.")
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
