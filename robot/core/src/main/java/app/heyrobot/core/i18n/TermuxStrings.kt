// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP6's catalog: Termux: termux/*, TermuxActions.kt's visible lines. What core says or labels about
// Termux: Robot's warnings on a command, why a command runs, shows its card or is refused, the set-up checklist, where the
// installed Termux came from, how a run ended, the card's lines, History's saved line, Settings › Termux's choices and its
// row's summary. Ids start with "termux."; every entry has its French (tu: TermuxPolicy's « vous » became « tu » in 1.1).
// The model's prompt line, the shell scripts Robot sends Termux and what the model reads of the output stay English.

object TermuxStrings : Strings("termux", area = "termux") {
    // Robot's warnings on a command (the card's chips), and the same as a verb after "This command …".
    val warnUnreadable = s("warn_unreadable", "Robot couldn't read this command fully", "Robot n'a pas pu lire cette commande en entier")
    val warnHidden = s("warn_hidden", "Has hidden or look-alike characters", "Contient des caractères cachés ou trompeurs")
    val warnHiddenWhat = s("warn_hidden_what", "has hidden or look-alike characters", "contient des caractères cachés ou trompeurs")
    val warnDeletes = s("warn_deletes", "Deletes files or empties them", "Supprime ou vide des fichiers")
    val warnDeletesWhat = s("warn_deletes_what", "deletes files or empties them", "supprime ou vide des fichiers")
    val warnOverwrites = s("warn_overwrites", "Changes, overwrites or moves files", "Modifie, écrase ou déplace des fichiers")
    val warnOverwritesWhat = s("warn_overwrites_what", "changes, overwrites or moves files", "modifie, écrase ou déplace des fichiers")
    val warnOutsideHome = s("warn_outside_home", "Changes files outside Termux's home folder", "Modifie des fichiers hors du dossier personnel de Termux")
    val warnOutsideHomeWhat = s("warn_outside_home_what", "changes files outside Termux's home folder", "modifie des fichiers hors du dossier personnel de Termux")
    val warnInternet = s("warn_internet", "Uses the internet", "Utilise Internet")
    val warnInternetWhat = s("warn_internet_what", "uses the internet", "utilise Internet")
    val warnInternetCode = s("warn_internet_code", "Runs code from the internet", "Exécute du code venu d'Internet")
    val warnInternetCodeWhat = s("warn_internet_code_what", "runs code from the internet", "exécute du code venu d'Internet")
    val warnPackages = s("warn_packages", "Changes installed packages", "Modifie les paquets installés")
    val warnPackagesWhat = s("warn_packages_what", "changes installed packages", "modifie les paquets installés")
    val warnRoot = s("warn_root", "Asks for root rights", "Demande les droits root")
    val warnRootWhat = s("warn_root_what", "asks for root rights", "demande les droits root")
    val warnAdbRights = s("warn_adb_rights", "Runs with ADB's rights through Shizuku", "S'exécute avec les droits d'ADB via Shizuku")
    val warnAdbRightsWhat = s("warn_adb_rights_what", "runs with ADB's rights through Shizuku", "s'exécute avec les droits d'ADB via Shizuku")
    val warnKeepsRunning = s("warn_keeps_running", "Keeps running later, without Robot", "Continue de tourner ensuite, sans Robot")
    val warnKeepsRunningWhat = s("warn_keeps_running_what", "keeps running later, without Robot", "continue de tourner ensuite, sans Robot")
    val warnRunsForever = s("warn_runs_forever", "May never end by itself: the time limit stops it", "Peut ne jamais finir : la limite de temps l'arrête")
    val warnRunsForeverWhat = s("warn_runs_forever_what", "may never end by itself", "peut ne jamais finir")
    val warnForkBomb = s("warn_fork_bomb", "Starts copies of itself without end until the phone stalls", "Se recopie sans fin jusqu'à bloquer le téléphone")
    val warnForkBombWhat = s("warn_fork_bomb_what", "starts copies of itself without end until the phone stalls", "se recopie sans fin jusqu'à bloquer le téléphone")
    val warnSends = s("warn_sends", "Sends a text or places a call right away. Robot's own texting only opens a draft.",
        "Envoie un texto ou passe un appel tout de suite. Robot, lui, ouvre seulement un brouillon.")
    val warnSendsWhat = s("warn_sends_what", "sends a text or places a call right away", "envoie un texto ou passe un appel tout de suite")
    val warnPersonalData = s("warn_personal_data", "Reads your personal phone data", "Lit tes données personnelles du téléphone")
    val warnPersonalDataWhat = s("warn_personal_data_what", "reads your personal phone data", "lit tes données personnelles du téléphone")
    val warnCaptures = s("warn_captures", "Uses the camera or the microphone", "Utilise l'appareil photo ou le micro")
    val warnCapturesWhat = s("warn_captures_what", "uses the camera or the microphone", "utilise l'appareil photo ou le micro")
    val warnPrivateFiles = s("warn_private_files", "Reads private files", "Lit des fichiers privés")
    val warnPrivateFilesWhat = s("warn_private_files_what", "reads private files", "lit des fichiers privés")
    val warnSecret = s("warn_secret", "Contains what looks like a password or key: Termux's process list and error reports would show it",
        "Contient ce qui ressemble à un mot de passe ou une clé : la liste des processus et les rapports d'erreur de Termux l'afficheraient")
    val warnSecretWhat = s("warn_secret_what", "contains what looks like a password or key", "contient ce qui ressemble à un mot de passe ou une clé")
    val warnNeedsTerminal = s("warn_needs_terminal", "Needs a terminal: open it in Termux", "A besoin d'un terminal : ouvre-la dans Termux")
    val warnNeedsTerminalWhat = s("warn_needs_terminal_what", "needs a terminal", "a besoin d'un terminal")

    // Why a command runs, shows its card or is refused (TermuxPolicy).
    val reasonOff = s("reason_off", "Termux is off in {path}.", "Termux est désactivé dans {path}.")
    val reasonCommandsOff = s("reason_commands_off", "Termux commands are off in {path}.", "Les commandes Termux sont désactivées dans {path}.")
    val reasonScriptOff = s("reason_script_off", "That script is off in {path}.", "Ce script est désactivé dans {path}.")
    val reasonHelpersOff = s("reason_helpers_off", "Helpers may not ask for Termux commands ({path}).",
        "Les IA externes ne peuvent pas demander de commandes Termux ({path}).")
    val reasonLocked = s("reason_locked", "Unlock your phone to run this in Termux.", "Déverrouille ton téléphone pour lancer ceci dans Termux.")
    val reasonApiOff = s("reason_api_off", "Termux:API commands are off in {path}.", "Les commandes Termux:API sont désactivées dans {path}.")
    val reasonOwnSecret = s("reason_own_secret", "This holds one of Robot's own keys. Robot never puts them in a command.",
        "Ceci contient une des clés de Robot. Robot ne les met jamais dans une commande.")
    val reasonKeyShape = s("reason_key_shape", "This holds what looks like an API key, and you didn't type it. Robot won't run it.",
        "Ceci contient ce qui ressemble à une clé d'API, et tu ne l'as pas tapée. Robot ne lancera pas la commande.")
    val reasonForkBomb = s("reason_fork_bomb", "This would start copies of itself until the phone stalls. Robot won't run it.",
        "Ceci se recopierait sans fin jusqu'à bloquer le téléphone. Robot ne le lancera pas.")
    val reasonTooLong = s("reason_too_long", "This command is too long for Robot to check (at most {n} characters).",
        "Cette commande est trop longue pour que Robot la vérifie ({n} caractères au plus).")
    val reasonEmpty = s("reason_empty", "There is no command to run.", "Il n'y a pas de commande à lancer.")
    val reasonCantSend = s("reason_cant_send", "This command holds a character Termux can't receive.", "Cette commande contient un caractère que Termux ne peut pas recevoir.")
    val reasonNotAScript = s("reason_not_a_script", "That isn't one of your scripts in ~/robot.", "Ce n'est pas un de tes scripts dans ~/robot.")
    val reasonNotAScriptNone = s("reason_not_a_script_none", "That isn't one of your scripts in ~/robot. You have none yet.",
        "Ce n'est pas un de tes scripts dans ~/robot. Tu n'en as pas encore.")
    val reasonNotAScriptYours = s("reason_not_a_script_yours", "That isn't one of your scripts in ~/robot. Yours: {list}.",
        "Ce n'est pas un de tes scripts dans ~/robot. Les tiens : {list}.")
    val reasonBadArguments = s("reason_bad_arguments", "Robot couldn't split the arguments: check the quotes; no line breaks.",
        "Robot n'a pas pu séparer les arguments : vérifie les guillemets; pas de retour à la ligne.")
    val reasonAskFirst = s("reason_ask_first", "Every command shows its card first.", "Chaque commande montre d'abord sa carte.")
    val reasonFromContent = s("reason_from_content", "Robot read this from content it didn't write: check the card, then tap {run}.",
        "Robot a lu ceci dans un contenu qu'il n'a pas écrit : vérifie la carte, puis touche {run}.")
    val reasonWarnings = s("reason_warnings", "This command has warnings: check the card, then tap {run}.",
        "Cette commande a des avertissements : vérifie la carte, puis touche {run}.")
    val reasonUnreadable = s("reason_unreadable", "Robot couldn't read this command fully: check the card, then tap {run}.",
        "Robot n'a pas pu lire cette commande en entier : vérifie la carte, puis touche {run}.")
    val reasonTapSetting = s("reason_tap_setting", "Commands run only with a tap ({path}).", "Les commandes ne se lancent que d'une touche ({path}).")
    val reasonReadOnly = s("reason_read_only", "A read-only command on Robot's list.", "Une commande en lecture seule de la liste de Robot.")
    val reasonYourScript = s("reason_your_script", "Your script, set to {level}.", "Ton script, réglé sur {level}.")
    val commandWarns = s("command_warns", "This command {what}: check the card, then tap {run}.", "Cette commande {what} : vérifie la carte, puis touche {run}.")
    val prompt = s("prompt", "Run this in Termux? Say yes or no.", "Lancer ceci dans Termux? Dis oui ou non.")
    val orSayNo = s("or_say_no", "{text} Or say no.", "{text} Ou dis non.")

    // Settings › Termux's choices.
    val levelAuto = s("level_auto", "Just do it", "Faire sans demander")
    val levelAsk = s("level_ask", "Ask first", "Demander d'abord")
    val levelOff = s("level_off", "Off", "Désactivé")
    val spokenWithoutWarnings = s("spoken_without_warnings", "Only commands without warnings", "Seulement les commandes sans avertissement")
    val spokenNever = s("spoken_never", "Never (tap only)", "Jamais (d'une touche seulement)")
    val apiWithCard = s("api_with_card", "Allowed, with a card", "Permises, avec une carte")
    val apiNotAllowed = s("api_not_allowed", "Not allowed", "Interdites")

    // Settings' row.
    val summaryOff = s("summary_off", "Off", "Désactivé")
    val summaryScripts = plural("summary_scripts", "n", Plural("On · {n} script", "On · {n} scripts"), Plural("Activé · {n} script", "Activé · {n} scripts"))
    val summaryNoScripts = s("summary_no_scripts", "On · no scripts, commands off", "Activé · aucun script, commandes désactivées")
    val summaryAskFirst = s("summary_ask_first", "On · commands ask first", "Activé · les commandes demandent d'abord")

    // The set-up checklist.
    val stepInstalled = s("step_installed", "Termux installed", "Termux installé")
    val stepOpened = s("step_opened", "Opened once", "Ouvert une fois")
    val stepPermission = s("step_permission", "Robot may run commands in Termux", "Robot peut lancer des commandes dans Termux")
    val stepExternalApps = s("step_external_apps", "Termux allows other apps", "Termux autorise les autres applis")
    val stepTest = s("step_test", "Test", "Test")
    val stepFolder = s("step_folder", "Your scripts folder", "Ton dossier de scripts")
    val stepTrust = s("step_trust", "Trust this Termux", "Faire confiance à ce Termux")
    val setupNotInstalled = s("setup_not_installed", "Not installed. Get it from F-Droid (not Google Play: that version can't take commands from other apps).",
        "Pas installé. Obtiens-le sur F-Droid (pas sur Google Play : cette version n'accepte pas de commandes d'autres applis).")
    val setupDone = s("setup_done", "Termux is set up", "Termux est configuré")
    val setupOpenAgain = s("setup_open_again", "Open Termux once so it can finish installing, then Test again.",
        "Ouvre Termux une fois pour qu'il termine son installation, puis teste de nouveau.")
    val setupOpenOnce = s("setup_open_once", "Open Termux once so it finishes installing. The Test checks it.",
        "Ouvre Termux une fois pour qu'il termine son installation. Le test le vérifie.")
    val setupAllowed = s("setup_allowed", "Allowed", "Autorisé")
    val setupAndroidAsks = s("setup_android_asks", "Android asks you. If it no longer does: {path}.",
        "Android te le demande. S'il ne le fait plus : {path}.")
    val setupExternalOn = s("setup_external_on", "{setting} is on", "{setting} est activé")
    val setupExternalOff = s("setup_external_off", "{setting} is off: paste this line in Termux, then Test again.",
        "{setting} est désactivé : colle cette ligne dans Termux, puis teste de nouveau.")
    val setupPasteLine = s("setup_paste_line", "Paste this line in Termux:", "Colle cette ligne dans Termux :")
    val setupTesting = s("setup_testing", "Testing…", "Test en cours…")
    val setupNoTimeout = s("setup_no_timeout", "Termux answered in {ms} ms, but GNU timeout is missing: run pkg install coreutils in Termux.",
        "Termux a répondu en {ms} ms, mais GNU timeout manque : lance pkg install coreutils dans Termux.")
    val setupAnswered = s("setup_answered", "Termux answered in {ms} ms", "Termux a répondu en {ms} ms")
    val setupRobotRuns = s("setup_robot_runs", "Robot runs: {command}", "Robot lance : {command}")
    val setupLooking = s("setup_looking", "Looking…", "Recherche…")
    val setupNotListed = s("setup_not_listed", "~/robot: not listed yet (optional)", "~/robot : pas encore listé (facultatif)")
    val setupFolderMissing = s("setup_folder_missing", "~/robot doesn't exist yet (optional)", "~/robot n'existe pas encore (facultatif)")
    val setupNoScripts = s("setup_no_scripts", "~/robot: no scripts yet", "~/robot : pas encore de scripts")
    val setupScripts = plural("setup_scripts", "n", Plural("~/robot: {n} script", "~/robot: {n} scripts"), Plural("~/robot : {n} script", "~/robot : {n} scripts"))
    val setupReinstalled = s("setup_reinstalled", "Termux was reinstalled or replaced: check where it came from, then set it up again.",
        "Termux a été réinstallé ou remplacé : vérifie sa provenance, puis configure-le de nouveau.")
    val setupTrusted = s("setup_trusted", "Trusted · signer {signer}", "Approuvé · signataire {signer}")
    val setupTrustedHistory = s("setup_trusted_history", "Trusted · signer {signer} (in its signing history)",
        "Approuvé · signataire {signer} (dans son historique de signature)")
    val setupRecordsSigner = s("setup_records_signer", "Robot records this Termux's signer {signer} and checks it before every run.",
        "Robot enregistre le signataire {signer} de ce Termux et le vérifie avant chaque lancement.")
    val setupInstallFirst = s("setup_install_first", "Install Termux first", "Installe d'abord Termux")
    val allowWarning = s("allow_warning", "With this, any app you give Robot's Termux permission to can run commands in Termux. Give it to Robot only.",
        "Avec ceci, toute appli à qui tu donnes l'autorisation de Termux peut y lancer des commandes. Donne-la seulement à Robot.")
    val listShown = s("list_shown", "{command}  (and each script's first # comment line)", "{command}  (et la première ligne de commentaire # de chaque script)")

    // Where the installed Termux came from, and Settings › Termux's first line.
    val fromFdroid = s("from_fdroid", "from F-Droid", "depuis F-Droid")
    val fromGithub = s("from_github", "from GitHub (public test key: anyone can make an update that installs over it)",
        "depuis GitHub (clé de test publique : n'importe qui peut faire une mise à jour qui s'installe par-dessus)")
    val fromPlay = s("from_play", "from Google Play", "depuis Google Play")
    val fromDevs = s("from_devs", "signed by the Termux developers", "signé par les développeurs de Termux")
    val fromUnknown = s("from_unknown", "signed by an unknown key", "signé par une clé inconnue")
    val nameFrom = s("name_from", "{name} {from}", "{name} {from}")
    val notInstalled = s("not_installed", "Termux isn't installed.", "Termux n'est pas installé.")
    val notSupported = s("not_supported", "{who}: this version can't take commands from other apps; install Termux from F-Droid.",
        "{who} : cette version n'accepte pas de commandes d'autres applis; installe Termux depuis F-Droid.")
    val permissionTaken = s("permission_taken", "Another app defines Termux's permission: uninstall it before trusting Termux.",
        "Une autre appli définit l'autorisation de Termux : désinstalle-la avant de faire confiance à Termux.")
    val tooOld = s("too_old", "{who} is too old: update it to {min} or newer.", "{who} est trop ancien : mets-le à jour vers {min} ou plus récent.")

    // Why Termux couldn't run a command.
    val failExternalApps = s("fail_external_apps", "Termux doesn't allow other apps yet: add {line} in {path}'s set-up.",
        "Termux n'autorise pas encore les autres applis : ajoute {line} dans la configuration de {path}.")
    val failNotSetUp = s("fail_not_set_up", "Termux isn't set up yet: open it once.", "Termux n'est pas encore configuré : ouvre-le une fois.")
    val failNoPermission = s("fail_no_permission", "Robot may not run commands in Termux yet: allow it in {path}.",
        "Robot ne peut pas encore lancer de commandes dans Termux : autorise-le dans {path}.")
    val failNoAnswer = s("fail_no_answer", "Termux didn't answer: check that its battery use isn't restricted.",
        "Termux n'a pas répondu : vérifie que son utilisation de la batterie n'est pas restreinte.")
    val failNotTrusted = s("fail_not_trusted", "Termux was reinstalled or replaced. Check where it came from, then set it up again in {path}.",
        "Termux a été réinstallé ou remplacé. Vérifie sa provenance, puis configure-le de nouveau dans {path}.")
    val failInternal = s("fail_internal", "Termux couldn't run it.", "Termux n'a pas pu la lancer.")
    val failNotInstalled = s("fail_not_installed", "Termux isn't installed, or can't take commands from other apps: see {path}.",
        "Termux n'est pas installé, ou n'accepte pas les commandes d'autres applis : vois {path}.")
    val failNotReady = s("fail_not_ready", "Finish setting up Termux in {path} first.", "Termine d'abord la configuration de Termux dans {path}.")

    // How a run ended.
    val noAnswer = s("no_answer", "Termux didn't answer.", "Termux n'a pas répondu.")
    val doneIn = s("done_in", "Done in {time}.", "Terminé en {time}.")
    val tookLonger = s("took_longer", "Took longer than {n} s and was stopped.", "A pris plus de {n} s et a été arrêtée.")
    val stopped = s("stopped", "Stopped.", "Arrêtée.")
    val notExecutable = s("not_executable", "Couldn't run it: not executable or refused (exit 126).", "Impossible de la lancer : pas exécutable ou refusée (code 126).")
    val notFound = s("not_found", "Command not found (exit 127).", "Commande introuvable (code 127).")
    val endedBySignal = s("ended_by_signal", "Ended by signal {signal} (exit {code}).", "Arrêtée par le signal {signal} (code {code}).")
    val finished = s("finished", "Finished with exit code {code} in {time}.", "Terminée avec le code {code} en {time}.")
    val secondsTenths = s("seconds_tenths", "{whole}.{tenth} s", "{whole},{tenth} s")
    val exitLine = s("exit_line", "Exit {code} · {time}", "Code {code} · {time}")
    val exitTookLonger = s("exit_took_longer", "Exit {code} · {time}: took longer than {n} s and was stopped",
        "Code {code} · {time} : a pris plus de {n} s et a été arrêtée")
    val exitStopped = s("exit_stopped", "Exit {code} · {time}: stopped", "Code {code} · {time} : arrêtée")
    val exitNotExecutable = s("exit_not_executable", "Exit {code} · {time}: couldn't run it: not executable or refused (exit 126)",
        "Code {code} · {time} : impossible de la lancer : pas exécutable ou refusée (code 126)")
    val exitNotFound = s("exit_not_found", "Exit {code} · {time}: command not found (exit 127)", "Code {code} · {time} : commande introuvable (code 127)")
    val exitSignal = s("exit_signal", "Exit {code} · {time}: ended by signal {signal} (exit {code})",
        "Code {code} · {time} : arrêtée par le signal {signal} (code {code})")
    val exitFinished = s("exit_finished", "Exit {code} · {time}: finished with exit code {code} in {time}",
        "Code {code} · {time} : terminée avec le code {code} en {time}")

    // The card.
    val runCommand = s("run_command", "Run a command in Termux", "Lancer une commande dans Termux")
    val runScript = s("run_script", "Run your script “{name}”", "Lancer ton script « {name} »")
    val unprepared = s("unprepared", "Termux isn't ready: open {path}.", "Termux n'est pas prêt : ouvre {path}.")
    val whereLine = s("where_line", "In: {where} · Time limit: {limit}", "Dans : {where} · Limite de temps : {limit}")
    val arguments = plural("arguments", "n", Plural("{n} argument: {list}", "{n} arguments: {list}"), Plural("{n} argument : {list}", "{n} arguments : {list}"))
    val readsWith = plural("reads_with", "n", Plural("{prog} with {n} argument: {list}", "{prog} with {n} arguments: {list}"),
        Plural("{prog} avec {n} argument : {list}", "{prog} avec {n} arguments : {list}"))
    val savedCommand = s("saved_command", "Ran a command in Termux (exit {code})", "Commande lancée dans Termux (code {code})")
    val savedCommandStopped = s("saved_command_stopped", "Ran a command in Termux (stopped)", "Commande lancée dans Termux (arrêtée)")
    val savedCommandNoAnswer = s("saved_command_no_answer", "Ran a command in Termux (no answer)", "Commande lancée dans Termux (pas de réponse)")
    val savedScript = s("saved_script", "Ran your script “{script}” (exit {code})", "Script « {script} » lancé (code {code})")
    val savedScriptStopped = s("saved_script_stopped", "Ran your script “{script}” (stopped)", "Script « {script} » lancé (arrêté)")
    val savedScriptNoAnswer = s("saved_script_no_answer", "Ran your script “{script}” (no answer)", "Script « {script} » lancé (pas de réponse)")
    val sizeChars = s("size_chars", "{n} characters", "{n} caractères")
    val sizeKb = s("size_kb", "{whole}.{tenth} KB", "{whole},{tenth} ko")
    val notShown = s("not_shown", "… {size} not shown …", "… {size} non affichés …")
    val errorsLabel = s("errors_label", "[errors]", "[erreurs]")
    val hidden = s("hidden", "[hidden: {what}]", "[masqué : {what}]")
    val hiddenPrivateKey = s("hidden_private_key", "looks like a private key", "ressemble à une clé privée")
    val hiddenApiKey = s("hidden_api_key", "looks like an API key", "ressemble à une clé d'API")
    val hiddenToken = s("hidden_token", "looks like a token", "ressemble à un jeton")
    val hiddenPassword = s("hidden_password", "looks like a password", "ressemble à un mot de passe")
    val hiddenRobotKey = s("hidden_robot_key", "one of Robot's own keys", "une des clés de Robot")
    val editing = s("editing", "You're editing the command: tap {run} when it's ready.", "Tu modifies la commande : touche {run} quand elle est prête.")
    val notTermux = s("not_termux", "Not a Termux call.", "Pas un appel Termux.")
    val wentWrong = s("went_wrong", "Something went wrong.", "Quelque chose n'a pas fonctionné.")
    val openedInTermux = s("opened_in_termux", "Opened in Termux.", "Ouvert dans Termux.")
    val sentToSession = s("sent_to_session", "Sent to a new Termux session: open Termux to see it.", "Envoyé à une nouvelle session Termux : ouvre Termux pour la voir.")
}
