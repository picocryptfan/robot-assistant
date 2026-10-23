// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// 1.1's catalog for Phone lock (docs/plans/phone-lock-design.md §14): the sentences with times, counts and names in them,
// shown by Settings › Phone lock (main), the cover and the notices (:lock), and the updater's line. A screen's fixed words
// are in values/strings_lock.xml. Ids start with "lock."; the area is "lock". Québec French (tu), the glossary's words
// (robot/l10n/glossary.tsv): « blocage du téléphone » for Phone lock, « code de blocage » for the lock code, « appli »;
// render() adds the French spaces. A lock's name and an app's name are arguments, never translated.

object LockStrings : Strings("lock", area = "lock") {
    // Settings › Phone lock's row in Settings.
    val summaryOff = s("summary_off", "Off", "Désactivé")
    val summaryOn = plural("summary_on", "n", Plural("On, {n} lock", "On, {n} locks"), Plural("Activé, {n} blocage", "Activé, {n} blocages", "Activé, {n} de blocages"))
    val summaryNow = s("summary_now", "On now, opens at {time}", "En cours, s'ouvre à {time}")
    val summaryDamaged = s("summary_damaged", "Paused: its file can't be read", "En pause : son fichier est illisible")

    // A lock's row: "Every day, 22:00 to 7:00".
    val timeRow = s("time_row", "{days}, {from} to {to}", "{days}, de {from} à {to}")
    // Phase B's rows: "Weekdays, 60 minutes after the alarm", "20 minutes in Libby first", "After 120 minutes of screen time, only Libby".
    val alarmRow = s("alarm_row", "{days}, {n} minutes after the alarm", "{days}, {n} minutes après l'alarme")
    val appFirstRow = s("app_first_row", "{n} minutes in {label} first", "{n} minutes dans {label} d'abord")
    val limitRow = s("limit_row", "After {n} minutes of screen time, only {label}", "Après {n} minutes de temps d'écran, seulement {label}")
    val byVoice = s("by_voice", "Started by voice", "Lancé à la voix")

    // The cover.
    val coverOpens = s("cover_opens", "Your phone opens at {time}", "Ton téléphone s'ouvre à {time}")
    val coverLeft = plural("cover_left", "n", Plural("{n} minute left", "{n} minutes left"), Plural("{n} minute restante", "{n} minutes restantes", "{n} de minutes restantes"))
    val coverWaitUntil = s("cover_wait_until", "You can end this lock at {time}.", "Tu pourras arrêter ce blocage à {time}.")
    val coverEndBy = s("cover_end_by", "End the lock now works until {time}.", "Arrêter le blocage maintenant fonctionne jusqu'à {time}.")
    val coverProgress = s("cover_progress", "{done} of {total} minutes in {label} done", "{done} minutes sur {total} passées dans {label}")
    val coverOpenApp = s("cover_open_app", "Open {label}", "Ouvrir {label}")

    // The code pad.
    val codeWait = s("code_wait", "Too many tries. Try again at {time}.", "Trop d'essais. Réessaie à {time}.")

    // The notices.
    val warnTitle = s("warn_title", "Phone lock at {time}: {name}", "Blocage du téléphone à {time} : {name}")
    val warnBody = s("warn_body", "Until {end}, only calls, alarms, Robot and the apps you chose stay open.",
        "Jusqu'à {end}, seuls les appels, les alarmes, Robot et les applis que tu as choisies restent ouverts.")
    val noticeOn = s("notice_on", "Phone lock: opens at {time}", "Blocage du téléphone : s'ouvre à {time}")
    val noticeProgress = s("notice_progress", "Phone lock: {done} of {total} minutes in {label}", "Blocage du téléphone : {done} minutes sur {total} dans {label}")
    val noticeOpen = plural("notice_open", "n", Plural("{n} minute in {label}: your phone is open.", "{n} minutes in {label}: your phone is open."),
        Plural("{n} minute dans {label} : ton téléphone est ouvert.", "{n} minutes dans {label} : ton téléphone est ouvert.", "{n} de minutes dans {label} : ton téléphone est ouvert."))
    val warnBodyApp = s("warn_body_app", "Until you've spent {n} minutes in {label}, only calls, alarms, Robot and the apps you chose stay open.",
        "Jusqu'à ce que tu aies passé {n} minutes dans {label}, seuls les appels, les alarmes, Robot et les applis que tu as choisies restent ouverts.")
    val noticeFailedName = s("notice_failed_name", "{name} couldn't start: Phone lock's access in Android's settings is off.",
        "{name} n'a pas pu commencer : l'accès du blocage du téléphone est désactivé dans les paramètres d'Android.")
    val noticeUnsaved = s("notice_unsaved", "Phone lock couldn't save its state", "Le blocage du téléphone n'a pas pu enregistrer son état")
    val noticeUnsavedText = s("notice_unsaved_text", "If your phone's storage is full, free up some space.", "Si le stockage de ton téléphone est plein, libère de l'espace.")

    // What happened (the record): "{day} at {time}: {what}".
    val recordLine = s("record_line", "{day} at {time}: {what}", "{day} à {time} : {what}")
    val recordStarted = s("record_started", "Started: {name}", "Commencé : {name}")
    val recordEnded = s("record_ended", "Ended: {name}", "Terminé : {name}")
    val recordEndedCode = s("record_ended_code", "Ended early with the code", "Arrêté plus tôt avec le code")
    val recordEndedWait = s("record_ended_wait", "Ended early after the wait", "Arrêté plus tôt après l'attente")
    val recordEndedApp = plural("record_ended_app", "n", Plural("Ended after {n} minute in its app: {name}", "Ended after {n} minutes in its app: {name}"),
        Plural("Terminé après {n} minute dans son appli : {name}", "Terminé après {n} minutes dans son appli : {name}", "Terminé après {n} de minutes dans son appli : {name}"))
    val recordSkipped = s("record_skipped", "Skipped: {name}", "Sauté : {name}")
    val recordAccessOff = s("record_access_off", "Access turned off", "Accès désactivé")
    val recordAccessOn = s("record_access_on", "Access back on", "Accès réactivé")
    val recordGap = plural("record_gap", "n", Plural("Robot wasn't running for {n} minute", "Robot wasn't running for {n} minutes"),
        Plural("Robot ne fonctionnait pas pendant {n} minute", "Robot ne fonctionnait pas pendant {n} minutes", "Robot ne fonctionnait pas pendant {n} de minutes"))
    val recordRestarted = s("record_restarted", "Phone lock restarted", "Le blocage du téléphone a redémarré")
    val recordCar = s("record_car", "Car mode during a lock", "Mode auto pendant un blocage")
    val recordWrongCodes = plural("record_wrong_codes", "n", Plural("{n} wrong code", "{n} wrong codes"), Plural("{n} mauvais code", "{n} mauvais codes", "{n} de mauvais codes"))
    val recordKeyLost = s("record_key_lost", "The lock code couldn't be checked any more: locks for someone else were turned off",
        "Le code de blocage ne pouvait plus être vérifié : les blocages pour quelqu'un d'autre ont été désactivés")
    val recordNotStarted = s("record_not_started", "Couldn't start: {name}", "N'a pas pu commencer : {name}")
    val recordLonger = s("record_longer", "Lock by voice made longer, until {time}", "Blocage lancé à la voix prolongé jusqu'à {time}")
    val recordDeleted = s("record_deleted", "A deleted lock", "Un blocage supprimé")

    // Lengths in Settings › Phone lock.
    val minutes = plural("minutes", "n", Plural("{n} minute", "{n} minutes"), Plural("{n} minute", "{n} minutes", "{n} de minutes"))
    val warnMinutes = plural("warn_minutes", "n", Plural("{n} minute before", "{n} minutes before"), Plural("{n} minute avant", "{n} minutes avant", "{n} de minutes avant"))

    // Phase B, by voice (LockRouter): the card, the answers.
    val voiceCard = s("voice_card", "Lock the phone until {time}?", "Bloquer le téléphone jusqu'à {time} ?")
    val voiceCardBody = s("voice_card_body", "Only calls, alarms, Robot and the always-open apps work until then.",
        "Jusque-là, seuls les appels, les alarmes, Robot et les applis toujours ouvertes fonctionnent.")
    val voiceCardWait = s("voice_card_wait", "You can end it early after a 10-minute wait.", "Tu pourras l'arrêter plus tôt après une attente de 10 minutes.")
    val voiceCardCode = s("voice_card_code", "Only the lock code ends it early.", "Seul le code de blocage l'arrête plus tôt.")
    val voiceLock = s("voice_lock", "Lock", "Bloquer")
    val voiceStarted = s("voice_started", "Locked until {time}.", "Bloqué jusqu'à {time}.")
    val voiceNotStarted = s("voice_not_started", "Not locked.", "Pas bloqué.")
    val voiceNeedSetup = s("voice_need_setup", "Set up Phone lock first, in Settings › Phone lock.",
        "Configure d'abord le blocage du téléphone, dans Réglages › Blocage du téléphone.")
    val voiceUnlockFirst = s("voice_unlock_first", "Unlock the phone first to start a lock.", "Déverrouille d'abord le téléphone pour commencer un blocage.")
    val voiceTooLong = s("voice_too_long", "A lock by voice lasts 12 hours at most.", "Un blocage lancé à la voix dure 12 heures au plus.")
    val voiceSomeoneElse = s("voice_someone_else", "A lock for someone else is on. Only the lock code ends or changes it.",
        "Un blocage pour quelqu'un d'autre est en cours. Seul le code de blocage l'arrête ou le change.")
    val voiceAlready = s("voice_already", "Your phone is already locked until {time}.", "Ton téléphone est déjà bloqué jusqu'à {time}.")
    val voiceLeft = plural("voice_left", "n", Plural("Your phone opens at {time}, in {n} minute.", "Your phone opens at {time}, in {n} minutes."),
        Plural("Ton téléphone s'ouvre à {time}, dans {n} minute.", "Ton téléphone s'ouvre à {time}, dans {n} minutes.", "Ton téléphone s'ouvre à {time}, dans {n} de minutes."))
    val voiceNone = s("voice_none", "There's no phone lock on now.", "Il n'y a pas de blocage du téléphone en ce moment.")
    val voiceNext = s("voice_next", "The next one is {name}, at {time}.", "Le prochain est {name}, à {time}.")
    val voiceNoEarly = s("voice_no_early", "This lock can't be ended early. Your phone opens at {time}.",
        "Ce blocage ne peut pas être arrêté plus tôt. Ton téléphone s'ouvre à {time}.")
    val voiceNoEarlyApp = s("voice_no_early_app", "This lock can't be ended early.", "Ce blocage ne peut pas être arrêté plus tôt.")
    val voiceWait = s("voice_wait", "Tap End early on the screen; the wait is {n} minutes.", "Touche Arrêter plus tôt à l'écran; l'attente est de {n} minutes.")
    val voiceCode = s("voice_code", "Enter the lock code on the screen.", "Entre le code de blocage à l'écran.")
    val voiceNoSkip = s("voice_no_skip", "This lock can't be skipped by voice. Change it in Settings › Phone lock.",
        "Ce blocage ne peut pas être sauté à la voix. Change-le dans Réglages › Blocage du téléphone.")
    val voiceNothingToSkip = s("voice_nothing_to_skip", "There's no lock coming up to skip.", "Il n'y a pas de blocage prévu à sauter.")

    // The panel, asked to open something that is covered during a lock.
    val appClosed = s("app_closed", "That app is closed during the phone lock.", "Cette appli est fermée pendant le blocage du téléphone.")
    val callClosed = s("call_closed", "Calls to that number are closed during the phone lock.", "Les appels à ce numéro sont fermés pendant le blocage du téléphone.")
    val closedNow = s("closed_now", "That's closed during the phone lock.", "C'est fermé pendant le blocage du téléphone.")

    // The updater waits during a lock (update/Updater).
    val updateWaits = s("update_waits", "Robot updates once the phone lock ends.", "Robot se met à jour une fois le blocage du téléphone terminé.")
}
