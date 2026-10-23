// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP8's catalog: the updater's words: core update/UpdateLogic.kt's UpdateText (its notifications, a
// check's or a download's failure, how an install ended, the last check's line) and the Updates row's state in
// Settings › About. Ids start with "update."; the area is "updates". Québec French (tu), the glossary's words
// (robot/l10n/glossary.tsv); render() adds the French spaces. A GitHub release is « publication » in French, so it never
// reads like Robot's own « version ». The Updates page's own words are resources (values/strings_updates.xml).

object UpdateStrings : Strings("update", area = "updates") {
    // The "Updates" notification channel and its notifications.
    val channelDescription = s("channel_description", "Tells you when a new version of Robot is available or ready to install.",
        "Te dit quand une nouvelle version de Robot est disponible ou prête à installer.")
    val availableTitle = s("available_title", "Robot {version} is available", "Robot {version} est disponible")
    val readyTitle = s("ready_title", "Robot {version} is ready to install", "Robot {version} est prêt à installer")
    val availableText = s("available_text", "Tap to see what's new in {page}.", "Touche pour voir les nouveautés dans {page}.")
    val readyText = s("ready_text", "Downloaded and checked. Tap to install it from {page}.",
        "Téléchargée et vérifiée. Touche pour l'installer depuis {page}.")
    val updatedTitle = s("updated_title", "Robot updated to {version}", "Robot a été mis à jour vers {version}")
    val updatedLine = s("updated_line", "Robot updated to {version}.", "Robot a été mis à jour vers {version}.")
    val updatedText = s("updated_text", "Robot restarted its services after the update.", "Robot a redémarré ses services après la mise à jour.")
    val confirmTitle = s("confirm_title", "Finish updating Robot", "Terminer la mise à jour de Robot")
    val confirmText = s("confirm_text", "Tap to confirm the update in Android's window.", "Touche pour confirmer la mise à jour dans la fenêtre d'Android.")
    val failedTitle = s("failed_title", "Robot's update wasn't installed", "La mise à jour de Robot n'a pas été installée")

    // What the Updates page says after you asked for something.
    val upToDate = s("up_to_date", "You're up to date.", "Tu es à jour.")
    val downloadFirst = s("download_first", "Download the update first.", "Télécharge d'abord la mise à jour.")
    val allowFirst = s("allow_first", "Allow Robot to install apps first (the button above).",
        "Permets d'abord à Robot d'installer des applis (le bouton ci-dessus).")

    // UpdateFail: why a check or a download didn't work.
    val failNoNetwork = s("fail_no_network", "Robot's Network permission is off. Turn it on in {path} to check for updates.",
        "L'autorisation Réseau de Robot est désactivée. Active-la dans {path} pour vérifier les mises à jour.")
    val failNoInternet = s("fail_no_internet", "No internet. Robot will try again later.", "Pas d'Internet. Robot réessaiera plus tard.")
    val failRateLimited = s("fail_rate_limited", "GitHub's limit on checks from your network was reached (60 an hour without a token). Try again in an hour.",
        "La limite de vérifications de GitHub depuis ton réseau est atteinte (60 par heure sans jeton). Réessaie dans une heure.")
    val failRateLimitedAt = s("fail_rate_limited_at", "GitHub's limit on checks from your network was reached (60 an hour without a token). Try again after {time}.",
        "La limite de vérifications de GitHub depuis ton réseau est atteinte (60 par heure sans jeton). Réessaie après {time}.")
    val failNotFoundNoToken = s("fail_not_found_no_token",
        "GitHub didn't find the repository. If it's private, add an access token in Advanced below; otherwise check its name.",
        "GitHub n'a pas trouvé le dépôt. S'il est privé, ajoute un jeton d'accès dans Avancé ci-dessous; sinon, vérifie son nom.")
    val failNotFound = s("fail_not_found", "GitHub didn't find the repository or its file. Check the repository's name and that the token can read it.",
        "GitHub n'a pas trouvé le dépôt ou son fichier. Vérifie le nom du dépôt et que le jeton peut le lire.")
    val failTokenRefused = s("fail_token_refused", "GitHub refused the access token. It may have expired: add a new one, or remove it for a public repository.",
        "GitHub a refusé le jeton d'accès. Il est peut-être expiré : ajoutes-en un nouveau, ou retire-le pour un dépôt public.")
    val failForbidden = s("fail_forbidden", "GitHub refused the request. If you use an access token, check that it can read this repository's contents.",
        "GitHub a refusé la demande. Si tu utilises un jeton d'accès, vérifie qu'il peut lire le contenu de ce dépôt.")
    val failService = s("fail_service", "GitHub didn't answer properly. Robot will try again later.", "GitHub n'a pas répondu correctement. Robot réessaiera plus tard.")
    val failBadAnswer = s("fail_bad_answer", "GitHub's answer couldn't be read. Robot will try again later.",
        "La réponse de GitHub n'a pas pu être lue. Robot réessaiera plus tard.")
    val failTooBig = s("fail_too_big", "The file is larger than Robot accepts, so it wasn't downloaded.",
        "Le fichier est plus gros que ce que Robot accepte : il n'a pas été téléchargé.")
    val failRedirectRefused = s("fail_redirect_refused", "GitHub sent the download somewhere Robot doesn't download from, so it stopped.",
        "GitHub a envoyé le téléchargement vers un endroit d'où Robot ne télécharge pas : il s'est arrêté.")
    val failNoRepository = s("fail_no_repository", "No repository to check. Set one in Advanced below.", "Aucun dépôt à vérifier. Choisis-en un dans Avancé ci-dessous.")
    val failNoSpace = s("fail_no_space", "Not enough free space for the update. Free some space and try again.",
        "Pas assez d'espace libre pour la mise à jour. Libère de l'espace et réessaie.")
    val failStorage = s("fail_storage", "The update couldn't be saved on this phone.", "La mise à jour n'a pas pu être enregistrée sur ce téléphone.")
    val failSumsBad = s("fail_sums_bad", "The release's SHA256SUMS file is missing or damaged, so Robot won't install this update.",
        "Le fichier SHA256SUMS de la publication manque ou est endommagé : Robot n'installera pas cette mise à jour.")
    val failSumsNoLine = s("fail_sums_no_line", "The release's SHA256SUMS file doesn't list the APK, so Robot won't install this update.",
        "Le fichier SHA256SUMS de la publication ne mentionne pas l'APK : Robot n'installera pas cette mise à jour.")
    val failChecksum = s("fail_checksum", "The downloaded file doesn't match its SHA-256 in SHA256SUMS, so Robot deleted it and won't install it.",
        "Le fichier téléchargé ne correspond pas à son SHA-256 dans SHA256SUMS : Robot l'a supprimé et ne l'installera pas.")
    val failSize = s("fail_size", "The download didn't have the size GitHub gave for it. Try again.",
        "Le téléchargement n'avait pas la taille donnée par GitHub. Réessaie.")
    val failNotAnApk = s("fail_not_an_apk", "The downloaded file isn't an app Android can read, so Robot won't install it.",
        "Le fichier téléchargé n'est pas une appli qu'Android peut lire : Robot ne l'installera pas.")
    val failWrongPackage = s("fail_wrong_package", "The downloaded app isn't Robot, so it won't be installed.",
        "L'appli téléchargée n'est pas Robot : elle ne sera pas installée.")
    val failWrongVersion = s("fail_wrong_version", "The downloaded app's version isn't the release's, so it won't be installed.",
        "La version de l'appli téléchargée n'est pas celle de la publication : elle ne sera pas installée.")
    val failNotNewer = s("fail_not_newer", "The downloaded app isn't newer than the Robot you have, so it won't be installed.",
        "L'appli téléchargée n'est pas plus récente que ton Robot : elle ne sera pas installée.")
    val failUnsigned = s("fail_unsigned", "The downloaded app isn't signed, so it won't be installed.",
        "L'appli téléchargée n'est pas signée : elle ne sera pas installée.")
    val failOtherSigner = s("fail_other_signer", "The downloaded app isn't signed with Robot's key, so it won't be installed. Android would refuse it too.",
        "L'appli téléchargée n'est pas signée avec la clé de Robot : elle ne sera pas installée. Android la refuserait aussi.")
    val failStopped = s("fail_stopped", "Stopped. Robot keeps what it downloaded and continues next time.",
        "Arrêté. Robot garde ce qu'il a téléchargé et continuera la prochaine fois.")
    val failBusy = s("fail_busy", "Robot is already working on the update.", "Robot s'occupe déjà de la mise à jour.")
    val failTokenUnavailable = s("fail_token_unavailable", "Robot couldn't read your access token just now. It's still saved, and Robot will try again later.",
        "Robot n'a pas pu lire ton jeton d'accès à l'instant. Il est toujours enregistré, et Robot réessaiera plus tard.")

    // InstallOutcome: how Android's install session ended.
    val installed = s("installed", "Robot {version} is installed.", "Robot {version} est installé.")
    val installedNoVersion = s("installed_no_version", "The update is installed.", "La mise à jour est installée.")
    val installCancelled = s("install_cancelled", "The update was cancelled. Robot stays as it is.", "La mise à jour a été annulée. Robot reste tel quel.")
    val installConflict = s("install_conflict",
        "Android refused the update: it conflicts with the Robot installed (another key, or another copy of Robot).",
        "Android a refusé la mise à jour : elle entre en conflit avec la copie de Robot installée (une autre clé, ou une autre copie de Robot).")
    val installStorage = s("install_storage", "Not enough storage to install the update. Free some space and try again.",
        "Pas assez d'espace de stockage pour installer la mise à jour. Libère de l'espace et réessaie.")
    val installIncompatible = s("install_incompatible", "Android says this update isn't compatible with this phone.",
        "Android dit que cette mise à jour n'est pas compatible avec ce téléphone.")
    val installInvalid = s("install_invalid", "Android found the update's file invalid, so it wasn't installed.",
        "Android a jugé le fichier de la mise à jour invalide : elle n'a pas été installée.")
    val installBlocked = s("install_blocked", "Android blocked the update (a policy or another app store keeps Robot's updates).",
        "Android a bloqué la mise à jour (une règle, ou une autre boutique d'applis gère les mises à jour de Robot).")
    val installTimeout = s("install_timeout", "The update took too long and Android stopped it. Try again.",
        "La mise à jour a pris trop de temps et Android l'a arrêtée. Réessaie.")
    val installFailed = s("install_failed", "The update couldn't be installed. Try again.", "La mise à jour n'a pas pu être installée. Réessaie.")

    // When a check was, and its line on the Updates page: "Last checked today at 14:05. You're up to date."
    val today = s("today", "Today", "Aujourd'hui")
    val yesterday = s("yesterday", "Yesterday", "Hier")
    val dayAt = s("day_at", "{day} at {time}", "{day} à {time}")
    val neverChecked = s("never_checked", "Not checked yet", "Pas encore vérifié")
    val lastChecked = s("last_checked", "Last checked {when}. {result}", "Dernière vérification : {when}. {result}")
    val lastCheckedOnly = s("last_checked_only", "Last checked {when}.", "Dernière vérification : {when}.")
    val availableLine = s("available_line", "Robot {version} is available.", "Robot {version} est disponible.")
    val availableAny = s("available_any", "An update is available.", "Une mise à jour est disponible.")
    val skippedLine = s("skipped_line", "Robot {version} is available (you skipped it).", "Robot {version} est disponible (tu l'as ignorée).")
    val skippedAny = s("skipped_any", "You skipped the latest version.", "Tu as ignoré la dernière version.")
    val checkFailed = s("check_failed", "The check didn't work.", "La vérification n'a pas fonctionné.")
    val bytes = plural("bytes", "n", Plural(one = "{n} bytes", other = "{n} bytes"), Plural(one = "{n} octet", other = "{n} octets"))

    // The Updates row in Settings › About.
    val summaryCheckedOff = s("summary_checked_off", "Checked {when} · automatic checks off", "Vérifié {when} · vérifications automatiques désactivées")
    val summaryAutoOff = s("summary_auto_off", "Automatic checks are off", "Les vérifications automatiques sont désactivées")
    val summaryChecked = s("summary_checked", "Checked {when}", "Vérifié {when}")
    val summaryDaily = s("summary_daily", "Checks once a day", "Vérifie une fois par jour")
}
