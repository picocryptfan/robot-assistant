// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP7a's catalog: the model and its state: the built-in models' names and descriptions, a download's
// or an import's state and why one can't start, the space it needs, what Robot's check found in your own model file,
// why :brain refuses a file, why Robot uses Fast instead, a model problem's headline, this phone's TPU and the speeds,
// the mobile-data question before a big download and the model's errors. Ids start with "model."; every entry has its
// French. "Fast", "Smarter" and "Uncensored" are « Rapide », « Plus futé » and « Sans censure » in French (the user, 15 and 19 Oct 2026). What stays English,
// outside this catalog: the problem report (a diagnostic you copy or share), the voice log's titles (LogCode), a model
// file's parser details (shown in French as « détail technique : … »), a publisher's own conditions (quoted), and the
// models' and phones' names.

object ModelStrings : Strings("model", area = "model") {
    // The built-in models' names in Settings › Model, and your own file.
    val titleFast = s("title_fast", "Fast", "Rapide")
    val titleFastBeta = s("title_fast_beta", "Fast (beta)", "Rapide (bêta)")
    val titleSmarter = s("title_smarter", "Smarter, slower", "Plus futé, plus lent")
    val titleUncensored = s("title_uncensored", "Uncensored, slower", "Sans censure, plus lent")
    val titleOwn = s("title_own", "Your own model file", "Ton propre fichier de modèle")
    val titleFallback = s("title_fallback", "Fast (general build, fallback)", "Rapide (version générale, de secours)")
    val ownModelName = s("own_model_name", "A .litertlm or .gguf file you picked", "Un fichier .litertlm ou .gguf que tu as choisi")
    val ownFileName = s("own_file_name", "Your model file", "Ton fichier de modèle")
    val publisherOlekk = s("publisher_olekk", "olekk (community build, not from Google)", "olekk (version communautaire, pas de Google)")
    val publisherYou = s("publisher_you", "you", "toi")
    val licenceOwn = s("licence_own", "the file's own", "celle du fichier")

    // How fast each model is, in a line.
    val speedFast = s("speed_fast", "Writes about 17 tokens (word pieces) a second; a new conversation starts answering in about 3 s. " +
        "About 2 GB of memory, light on the battery. Sees photos.",
        "Écrit environ 17 jetons (des morceaux de mots) par seconde; une nouvelle conversation commence à répondre en environ 3 s. " +
            "Environ 2 Go de mémoire, léger pour la batterie. Voit les photos.")
    val speedFastG6 = s("speed_fast_g6", "Beta. Estimated from the Tensor G5 build (not measured on a Tensor G6): about 17 tokens (word " +
        "pieces) a second, a new conversation starting to answer in about 3 s, about 2 GB of memory. Sees photos.",
        "Bêta. Estimé à partir de la version Tensor G5 (pas mesuré sur un Tensor G6) : environ 17 jetons (des morceaux de mots) par " +
            "seconde, une nouvelle conversation qui commence à répondre en environ 3 s, environ 2 Go de mémoire. Voit les photos.")
    val speedGeneral = s("speed_general", "The same model as the Pixel 10's Fast, built to run on the GPU or the CPU. About 2.6 GB of " +
        "memory. Sees photos.",
        "Le même modèle que le Rapide du Pixel 10, conçu pour fonctionner sur le GPU ou le CPU. Environ 2,6 Go de mémoire. Voit les photos.")
    val speedSmarter = s("speed_smarter", "Better answers, slower: about 8 tokens a second, and about 21 s before the first word of a new " +
        "conversation (later answers about 1 to 3 s). About 4 GB of memory and more battery (about 9 W while it writes, against 3.5 W). " +
        "Sees photos, more slowly.",
        "De meilleures réponses, plus lentes : environ 8 jetons par seconde, et environ 21 s avant le premier mot d'une nouvelle " +
            "conversation (les réponses suivantes, environ 1 à 3 s). Environ 4 Go de mémoire et plus de batterie (environ 9 W pendant " +
            "qu'il écrit, contre 3,5 W). Voit les photos, plus lentement.")
    val speedUncensored = s("speed_uncensored", "Like Smarter: about 8 tokens a second, about 21 s before the first word of a new " +
        "conversation, about 4 GB of memory and more battery.",
        "Comme Plus futé : environ 8 jetons par seconde, environ 21 s avant le premier mot d'une nouvelle conversation, environ 4 Go de " +
            "mémoire et plus de batterie.")
    val speedOwn = s("speed_own", "Runs on the CPU (a .gguf with llama.cpp), unless Robot's check proves a .litertlm was compiled for " +
        "the TPU of the phone's own chip (a Tensor G5, or in beta a Tensor G6) and you allow that.",
        "Fonctionne sur le CPU (un .gguf avec llama.cpp), sauf si la vérification de Robot prouve qu'un .litertlm a été compilé pour le " +
            "TPU de la puce du téléphone (un Tensor G5, ou en bêta un Tensor G6) et que tu le permets.")

    // What each model is.
    val aboutFast = s("about_fast", "Gemma 4 E2B, compiled by Google for the Tensor G5's TPU (the Pixel 10 series). Robot's model since 0.1.",
        "Gemma 4 E2B, compilé par Google pour le TPU du Tensor G5 (la série Pixel 10). Le modèle de Robot depuis la 0.1.")
    val aboutFastG6 = s("about_fast_g6", "Gemma 4 E2B, compiled by Google for the Tensor G6's TPU (the Pixel 11 series). A beta: Robot " +
        "hasn't run it on a Pixel 11 yet. If it doesn't load on the TPU, Robot switches to the general build by itself (download it too, " +
        "to have it ready) and remembers that until you choose {tryAgain}.",
        "Gemma 4 E2B, compilé par Google pour le TPU du Tensor G6 (la série Pixel 11). Une bêta : Robot ne l'a pas encore fait " +
            "fonctionner sur un Pixel 11. S'il ne se charge pas sur le TPU, Robot passe de lui-même à la version générale (télécharge-la " +
            "aussi, pour qu'elle soit prête) et s'en souvient jusqu'à ce que tu choisisses {tryAgain}.")
    val tryTpuAgain = s("try_tpu_again", "Try the TPU again", "Réessayer le TPU")
    val aboutGeneral = s("about_general", "Gemma 4 E2B from Google, the general build of the model the Pixel 10 runs on its TPU: Google " +
        "publishes no TPU build for this phone's chip, so it runs on the GPU or the CPU. The first load writes a cache next to it (about " +
        "0.8 GB on the GPU, up to 2 GB on the CPU).",
        "Gemma 4 E2B de Google, la version générale du modèle que le Pixel 10 fait fonctionner sur son TPU : Google ne publie aucune " +
            "version TPU pour la puce de ce téléphone, alors il fonctionne sur le GPU ou le CPU. Son premier chargement écrit un cache à " +
            "côté de lui (environ 0,8 Go sur le GPU, jusqu'à 2 Go sur le CPU).")
    val aboutSmarter = s("about_smarter", "Gemma 4 E4B from Google, the larger of the two small Gemma 4 models, run on the CPU: no Tensor " +
        "TPU build of it exists. The first load writes about 2.5 GB of cache next to it.",
        "Gemma 4 E4B de Google, le plus grand des deux petits modèles Gemma 4, sur le CPU : il n'en existe aucune version pour le TPU " +
            "d'un Tensor. Son premier chargement écrit environ 2,5 Go de cache à côté de lui.")
    val aboutUncensored = s("about_uncensored", "A community build of Gemma 4 E4B with the model's own refusals removed (olekk; not from " +
        "Google). Robot's own cards and switches still apply: actions still ask you first as {actions} and {safety} say. Its maker " +
        "spot-checked it but ran no benchmark, and it may still refuse now and then; the publisher asks that it be used for research and " +
        "personal experimentation, says you own its outputs, and says Google's Gemma Prohibited Use Policy still applies.",
        "Une version communautaire de Gemma 4 E4B dont on a retiré les refus du modèle (olekk; pas de Google). Les cartes et les " +
            "interrupteurs de Robot s'appliquent toujours : les actions te demandent encore d'abord, comme le disent {actions} et " +
            "{safety}. Son auteur l'a vérifié par sondage, sans banc d'essai, et il peut encore refuser de temps en temps; l'éditeur " +
            "demande qu'on l'utilise pour la recherche et l'expérimentation personnelle, dit que ses résultats t'appartiennent, et dit " +
            "que la politique d'utilisation interdite de Gemma de Google s'applique toujours.")
    val aboutOwn = s("about_own", "A LiteRT-LM (.litertlm) or GGUF (.gguf) file. Robot copies it into its own storage, shows its size " +
        "and SHA-256, and checks what is inside before it can be chosen. Robot is written for Gemma 4: another model may answer oddly " +
        "and actions may not work.",
        "Un fichier LiteRT-LM (.litertlm) ou GGUF (.gguf). Robot le copie dans son propre stockage, montre sa taille et son SHA-256, " +
            "et vérifie ce qu'il contient avant qu'on puisse le choisir. Robot est écrit pour Gemma 4 : un autre modèle peut répondre " +
            "bizarrement et les actions peuvent ne pas fonctionner.")

    // A publisher's conditions: Robot's words around the publisher's own (which stay as the publisher wrote them).
    val termsIntro = s("terms_intro", "Its publisher asks everyone who downloads it to confirm two things first:",
        "Son éditeur demande à tous ceux qui le téléchargent de confirmer d'abord deux choses, dans ses propres mots (en anglais) :")
    val termsAlso = s("terms_also", "Google's Gemma Prohibited Use Policy still applies, as its publisher says, and Robot's cards and " +
        "switches still decide what asks you first.",
        "La politique d'utilisation interdite de Gemma de Google s'applique toujours, comme le dit son éditeur, et les cartes et les " +
            "interrupteurs de Robot décident toujours de ce qui te demande d'abord.")
    val termsRefused = s("terms_refused", "Tick its publisher's two boxes on its card first.",
        "Coche d'abord les deux cases de son éditeur sur sa page.")

    // Why a download paused or failed (Android's download manager).
    val reasonRetry = s("reason_retry", "waiting to retry: couldn't reach the server", "en attente d'un nouvel essai : le serveur était injoignable")
    val reasonNetwork = s("reason_network", "waiting for a network connection", "en attente d'une connexion réseau")
    val reasonWifi = s("reason_wifi", "waiting for Wi-Fi (too large for mobile data)", "en attente du Wi-Fi (trop gros pour les données mobiles)")
    val reasonPaused = s("reason_paused", "paused", "en pause")
    val reasonHttp = s("reason_http", "the server answered with error {code}", "le serveur a répondu par l'erreur {code}")
    val reasonWrite = s("reason_write", "the phone couldn't write the file", "le téléphone n'a pas pu écrire le fichier")
    val reasonConnection = s("reason_connection", "the connection broke", "la connexion a été coupée")
    val reasonSpace = s("reason_space", "the phone ran out of space", "le téléphone a manqué d'espace")
    val reasonStorage = s("reason_storage", "no storage was available", "aucun stockage n'était disponible")
    val reasonResume = s("reason_resume", "the download couldn't resume and must start again",
        "le téléchargement n'a pas pu reprendre et doit recommencer")
    val reasonExists = s("reason_exists", "the file already exists", "le fichier existe déjà")
    val reasonUnknown = s("reason_unknown", "an unknown error", "une erreur inconnue")

    // The space a download or an import needs.
    val leftPending = s("left_pending", "only {size} will be left once the downloads under way finish",
        "il ne restera que {size} une fois les téléchargements en cours terminés")
    val leftFree = s("left_free", "{size} is free", "il y a {size} de libre")
    val needWithCache = s("need_with_cache", "the {file} file, about {cache} for the cache the CPU writes on its first load, and 1 GB to spare",
        "le fichier de {file}, environ {cache} pour le cache que le CPU écrit à son premier chargement, et 1 Go de marge")
    val needFile = s("need_file", "the file plus 1 GB to spare", "le fichier plus 1 Go de marge")
    val notEnoughSpace = s("not_enough_space", "Not enough free space: {name} needs {need} ({what}) and {left}.",
        "Pas assez d'espace libre : {name} a besoin de {need} ({what}) et {left}.")

    // Why a download or an import can't start.
    val noDownload = s("no_download", "This one has no download: import your file.", "Celui-ci ne se télécharge pas : importe ton fichier.")
    val busyWith = s("busy_with", "“{model}” is downloading or being copied. Wait for it to finish, or cancel it first.",
        "« {model} » est en cours de téléchargement ou de copie. Attends qu'il finisse, ou annule-le d'abord.")
    val alreadyDownloading = s("already_downloading", "The download is already running.", "Le téléchargement est déjà en cours.")
    val importRunning = s("import_running", "An import is running.", "Une importation est en cours.")
    val importAlreadyRunning = s("import_already_running", "An import is already running.", "Une importation est déjà en cours.")
    val beingChecked = s("being_checked", "The model is being checked.", "Le modèle est en cours de vérification.")
    val alreadyHere = s("already_here", "The model is already on this phone.", "Le modèle est déjà sur ce téléphone.")
    val alreadyChecked = s("already_checked", "The model is already on this phone and checked.", "Le modèle est déjà sur ce téléphone et vérifié.")
    val wrongSize = s("wrong_size", "That file isn't the model: it has {bytes} bytes and {file} has {expected}.",
        "Ce fichier n'est pas le modèle : il fait {bytes} octets et {file} en fait {expected}.")
    val tooSmall = s("too_small", "That file is too small to be a model ({bytes} bytes).", "Ce fichier est trop petit pour être un modèle ({bytes} octets).")

    // A model's state on its card.
    val notDownloaded = s("not_downloaded", "Not downloaded", "Pas téléchargé")
    val noFileYet = s("no_file_yet", "No file yet", "Aucun fichier pour l'instant")
    val downloading = s("downloading", "Downloading", "Téléchargement en cours")
    val copying = s("copying", "Copying the file", "Copie du fichier")
    val checking = s("checking", "Checking the file", "Vérification du fichier")
    val downloadedUnchecked = s("downloaded_unchecked", "Downloaded, not checked yet", "Téléchargé, pas encore vérifié")
    val copiedUnchecked = s("copied_unchecked", "Copied, not checked yet", "Copié, pas encore vérifié")
    val ready = s("ready", "Ready", "Prêt")
    val damaged = s("damaged", "The file is damaged", "Le fichier est endommagé")
    val cantUse = s("cant_use", "Robot can't use this file", "Robot ne peut pas utiliser ce fichier")

    // A download's progress.
    val waitingToStart = s("waiting_to_start", "Waiting to start", "En attente de démarrage")
    val starting = s("starting", "Starting", "Démarrage")
    val progress = s("progress", "{done} of {total} ({percent}%)", "{done} sur {total} ({percent} %)")
    val pausedAt = s("paused_at", "Paused at {done}: {reason}", "En pause à {done} : {reason}")
    val downloadFailed = s("download_failed", "The download failed: {reason}. Tap {button} to try again.",
        "Le téléchargement a échoué : {reason}. Touche {button} pour réessayer.")
    val downloaded = s("downloaded", "Downloaded", "Téléchargé")
    val statusUnknown = s("status_unknown", "Download status unknown", "État du téléchargement inconnu")

    /** A model's size as 1.0 writes it, two decimals: "3.11 GB" / « 3,11 Go ». */
    val gb = s("gb", "{int}.{frac} GB", "{int},{frac} Go")

    // Why :brain won't load a file (sent to the panel, which says it in your language).
    val changed = s("changed", "The model file changed since Robot checked it. Open the Robot app › {page} to check it again.",
        "Le fichier du modèle a changé depuis que Robot l'a vérifié. Ouvre l'appli Robot › {page} pour le vérifier de nouveau.")
    val noTpu = s("no_tpu", "This phone has no TPU Robot can use: the TPU builds run on a Tensor G5 (Pixel 10) and, in beta, a Tensor " +
        "G6 (Pixel 11).",
        "Ce téléphone n'a pas de TPU que Robot peut utiliser : les versions TPU fonctionnent sur un Tensor G5 (Pixel 10) et, en bêta, " +
            "sur un Tensor G6 (Pixel 11).")
    val otherChip = s("other_chip", "This file was compiled for the {built}'s TPU, and this phone has a {phone}: a TPU runs only the " +
        "build made for its own chip.",
        "Ce fichier a été compilé pour le TPU du {built}, et ce téléphone a un {phone} : un TPU ne fait fonctionner que la version " +
            "faite pour sa propre puce.")
    val otherChipNone = s("other_chip_none", "This file was compiled for the {built}'s TPU, and this phone has a chip without that TPU: " +
        "a TPU runs only the build made for its own chip.",
        "Ce fichier a été compilé pour le TPU du {built}, et la puce de ce téléphone n'a pas ce TPU : un TPU ne fait fonctionner que " +
            "la version faite pour sa propre puce.")
    val noGpu = s("no_gpu", "Robot doesn't use this phone's GPU.", "Robot n'utilise pas le GPU de ce téléphone.")
    val wrongContext = s("wrong_context", "Robot's prompts are made for {n} tokens of context with this model.",
        "Les consignes de Robot sont prévues pour {n} jetons de contexte avec ce modèle.")
    val isLiteRtLm = s("is_litertlm", "“{model}” is a LiteRT-LM file.", "« {model} » est un fichier LiteRT-LM.")
    val runsOnlyOn = s("runs_only_on", "“{model}” runs on the {backend} only.", "« {model} » ne fonctionne que sur le {backend}.")
    val ownWaitsTpu = s("own_waits_tpu", "Your model file waits for you to allow the TPU in the Robot app › {page}.",
        "Ton fichier de modèle attend que tu autorises le TPU dans l'appli Robot › {page}.")
    val ownCantRun = s("own_cant_run", "Robot can't run your model file on this phone.",
        "Robot ne peut pas faire fonctionner ton fichier de modèle sur ce téléphone.")
    val ownRunsOn = s("own_runs_on", "Your model file runs on the {where}, not the {backend}.",
        "Ton fichier de modèle fonctionne sur le {where}, pas sur le {backend}.")

    val medical = s("medical", "Not a medical device: check health information with a professional.",
        "Ce n'est pas un dispositif médical : vérifie les informations de santé auprès d'un professionnel.")

    // Which model the assistant loads, and why Robot uses Fast instead of the one you chose.
    val noModel = s("no_model", "Robot's model isn't on this phone yet. Open the Robot app to download it.",
        "Le modèle de Robot n'est pas encore sur ce téléphone. Ouvre l'appli Robot pour le télécharger.")
    val stopping = s("stopping", "The model is still stopping. Try again in a moment.",
        "Le modèle est encore en train de s'arrêter. Réessaie dans un instant.")
    val fastFailed = s("fast_failed", "The model didn't load in time.", "Le modèle ne s'est pas chargé à temps.")
    val whyNotDownloaded = s("why_not_downloaded", "“{model}” isn't on this phone yet", "« {model} » n'est pas encore sur ce téléphone")
    val whyNotChecked = s("why_not_checked", "“{model}” hasn't been checked yet", "« {model} » n'a pas encore été vérifié")
    val whyNeedsTpu = s("why_needs_tpu", "“{model}” waits for you to allow the TPU in the Robot app › {page}",
        "« {model} » attend que tu autorises le TPU dans l'appli Robot › {page}")
    val whyUnusable = s("why_unusable", "Robot can't use “{model}” (the Robot app › {page} says why)",
        "Robot ne peut pas utiliser « {model} » (l'appli Robot › {page} dit pourquoi)")
    val whyLoadFailed = s("why_load_failed", "“{model}” didn't load", "« {model} » ne s'est pas chargé")
    val whyLoadFailedDetail = s("why_load_failed_detail", "“{model}” didn't load ({detail})", "« {model} » ne s'est pas chargé ({detail})")
    val whyFailedEarlier = s("why_failed_earlier", "“{model}” didn't load earlier", "« {model} » ne s'est pas chargé plus tôt")
    val whyStopped = s("why_stopped", "“{model}” stopped unexpectedly", "« {model} » s'est arrêté de façon inattendue")
    val usingFast = s("using_fast", "{what}, so Robot is using Fast.", "{what}, alors Robot utilise Rapide.")
    val fastMissingToo = s("fast_missing_too", "{what}, and Fast isn't on this phone either. Open the Robot app › {page}.",
        "{what}, et Rapide n'est pas sur ce téléphone non plus. Ouvre l'appli Robot › {page}.")
    val downloadIt = s("download_it", "Download it in the Robot app › {page}.", "Télécharge-le dans l'appli Robot › {page}.")
    val importIt = s("import_it", "Import it in the Robot app › {page}.", "Importe-le dans l'appli Robot › {page}.")
    val checkIt = s("check_it", "Open the Robot app › {page} to check it.", "Ouvre l'appli Robot › {page} pour le vérifier.")
    val retryIt = s("retry_it", "Tap it again in the Robot app › {page} to retry.", "Touche-le de nouveau dans l'appli Robot › {page} pour réessayer.")

    // A model problem: where it was, what failed, and what Robot did.
    val stageLoading = s("stage_loading", "loading", "chargement")
    val stageReading = s("stage_reading", "reading the prompt", "lecture de la requête")
    val stageWriting = s("stage_writing", "writing the answer", "écriture de la réponse")
    val stageIdle = s("stage_idle", "loaded, between answers", "chargé, entre deux réponses")
    val whileLoading = s("while_loading", "while loading", "pendant le chargement")
    val whileReading = s("while_reading", "while reading the prompt", "pendant la lecture de la requête")
    val whileWriting = s("while_writing", "while writing the answer", "pendant l'écriture de la réponse")
    val whileIdle = s("while_idle", "while loaded, between answers", "pendant qu'il était chargé, entre deux réponses")
    val failureNoFile = s("failure_no_file", "file missing", "fichier manquant")
    val failureNotAllowed = s("failure_not_allowed", "refused by Robot's check of the file", "refusé par la vérification du fichier")
    val failureNoSpace = s("failure_no_space", "no room for its cache", "pas de place pour son cache")
    val failureEngine = s("failure_engine", "engine error", "erreur du moteur")
    val failureStopping = s("failure_stopping", "the last model was still stopping", "le modèle précédent s'arrêtait encore")
    val failureTimeout = s("failure_timeout", "no answer in time", "pas de réponse à temps")
    val failureProcess = s("failure_process", "the model process stopped", "le processus du modèle s'est arrêté")
    val deviceDidntLoad = s("device_didnt_load", "The {device} didn't load ({failure})", "Le {device} ne s'est pas chargé ({failure})")
    val deviceFailedWhile = s("device_failed_while", "The {device} failed {while} ({failure})", "Le {device} a échoué {while} ({failure})")
    val deviceStoppedWhile = s("device_stopped_while", "The {device} stopped {while}", "Le {device} s'est arrêté {while}")
    // 1.2 (design Step 2): Robot's three-turn self-test of the GPU or a beta TPU build (core GpuCheck), and what it found.
    val deviceCheckFailed = s("device_check_failed", "The {device} failed Robot's check ({why})", "Le {device} a échoué à la vérification de Robot ({why})")
    val checkPassed = s("check_passed", "passed", "réussie")
    val checkNotLoaded = s("check_not_loaded", "the model didn't load", "le modèle ne s'est pas chargé")
    val checkGarbage = s("check_garbage", "garbled answer", "réponse brouillée")
    val checkEmpty = s("check_empty", "empty answer", "réponse vide")
    val checkWrong = s("check_wrong", "wrong answer", "mauvaise réponse")
    val checkNoCall = s("check_no_call", "no timer set", "aucune minuterie réglée")
    val checkSlow = s("check_slow", "too slow", "trop lent")
    val problemDidntLoad = s("problem_didnt_load", "{model} didn't load on the {backend}", "{model} ne s'est pas chargé sur le {backend}")
    val problemFailed = s("problem_failed", "{model} failed on the {backend}", "{model} a échoué sur le {backend}")
    val problemStopped = s("problem_stopped", "{model} stopped on the {backend}", "{model} s'est arrêté sur le {backend}")
    val problemEnd = s("problem_end", "{what}.", "{what}.")
    val problemSwitched = s("problem_switched", "{what}, so Robot switched to the {backend}.", "{what}, alors Robot est passé au {backend}.")
    val problemUsing = s("problem_using", "{what}, so Robot is using {then}.", "{what}, alors Robot utilise {then}.")
    val problemUsingOn = s("problem_using_on", "{what}, so Robot is using {then} on the {backend}.", "{what}, alors Robot utilise {then} sur le {backend}.")
    val problemRow = s("problem_row", "{process} · {stage} · {model} on the {backend}", "{process} · {stage} · {model} sur le {backend}")

    // What Robot's check found in your own file: a GGUF file.
    val ggufMemory = s("gguf_memory", "It needs about {need} of memory while it runs, and next to Robot's voice (about {voice}) and " +
        "Android this phone has about {room} for it. Choose a smaller file (fewer parameters, or a smaller quantisation such as Q4_K_M).",
        "Il a besoin d'environ {need} de mémoire pendant qu'il fonctionne, et à côté de la voix de Robot (environ {voice}) et " +
            "d'Android, ce téléphone a environ {room} pour lui. Choisis un fichier plus petit (moins de paramètres, ou une " +
            "quantification plus petite comme Q4_K_M).")
    val notGguf = s("not_gguf", "It isn't a GGUF model file.", "Ce n'est pas un fichier de modèle GGUF.")
    val noArchitecture = s("no_architecture", "It doesn't say what kind of model it is (general.architecture).",
        "Il ne dit pas quel genre de modèle il est (general.architecture).")
    val unknownArchitecture = s("unknown_architecture", "Its architecture “{arch}” isn't one that llama.cpp {tag} knows.",
        "llama.cpp {tag} ne connaît pas son architecture « {arch} ».")
    val notChat = s("not_chat", "It's a “{arch}” model, which doesn't write answers (an encoder, an embedding, a speech, a draft or a " +
        "diffusion model).",
        "C'est un modèle « {arch} », qui n'écrit pas de réponses (un modèle d'encodage, de plongement, de parole, de brouillon ou de " +
            "diffusion).")
    val noTokenizer = s("no_tokenizer", "It has no tokenizer (tokenizer.ggml.model).", "Il n'a pas de segmenteur (tokenizer.ggml.model).")
    val noContextLength = s("no_context_length", "It doesn't say its context length ({arch}.context_length).",
        "Il ne dit pas la longueur de son contexte ({arch}.context_length).")
    val trainedShort = s("trained_short", "It was trained for only {n} tokens of context; Robot needs at least {min} to chat ({max} for actions).",
        "Il a été entraîné pour seulement {n} jetons de contexte; Robot en a besoin d'au moins {min} pour converser ({max} pour les actions).")
    val noChatTemplate = s("no_chat_template", "It has no chat template ({key}), so Robot can't write a conversation for it.",
        "Il n'a pas de gabarit de conversation ({key}), alors Robot ne peut pas lui écrire une conversation.")
    val couldntReadIt = s("couldnt_read_it", "Robot couldn't read it.", "Robot n'a pas pu le lire.")
    val couldntReadFile = s("couldnt_read_file", "Robot couldn't read the file.", "Robot n'a pas pu lire le fichier.")
    val ggufUnreadable = s("gguf_unreadable", "It isn't a GGUF model file Robot can read.", "Ce n'est pas un fichier de modèle GGUF que Robot peut lire.")
    val ggufUnreadableDetail = s("gguf_unreadable_detail", "It isn't a GGUF model file Robot can read ({detail}).",
        "Ce n'est pas un fichier de modèle GGUF que Robot peut lire (détail technique : {detail}).")
    val bigEndian = s("big_endian", "It is a big-endian GGUF file; this phone reads little-endian ones.",
        "C'est un fichier GGUF gros-boutiste; ce téléphone lit les fichiers petit-boutistes.")
    val ggufVersionUnsupported = s("gguf_version_unsupported", "It is GGUF version {version}; llama.cpp reads versions 2 and 3.",
        "C'est un fichier GGUF version {version}; llama.cpp lit les versions 2 et 3.")
    val splitFile = s("split_file", "It is one part of a model split into {n} files; Robot needs the whole model in one file (llama.cpp's " +
        "llama-gguf-split --merge joins the parts).",
        "C'est une partie d'un modèle divisé en {n} fichiers; Robot a besoin du modèle entier dans un seul fichier (llama-gguf-split " +
            "--merge, de llama.cpp, réunit les parties).")
    val ggufFile = s("gguf_file", "GGUF file, version {version}.", "Fichier GGUF, version {version}.")
    val ggufFileNamed = s("gguf_file_named", "GGUF file, version {version}: “{name}”.", "Fichier GGUF, version {version} : « {name} ».")
    val ggufFileWhat = s("gguf_file_what", "GGUF file, version {version} ({what}).", "Fichier GGUF, version {version} ({what}).")
    val ggufFileNamedWhat = s("gguf_file_named_what", "GGUF file, version {version}: “{name}” ({what}).",
        "Fichier GGUF, version {version} : « {name} » ({what}).")
    val architecture = s("architecture", "architecture {arch}", "architecture {arch}")
    val aboutParameters = s("about_parameters", "about {n} parameters", "environ {n} de paramètres")
    val billions = plural("billions", "int", en = Plural("{int}.{frac} billion", "{int}.{frac} billion"),
        fr = Plural("{int},{frac} milliard", "{int},{frac} milliards"))
    val millions = plural("millions", "n", en = Plural("{n} million", "{n} million"), fr = Plural("{n} million", "{n} millions"))
    val quantisation = s("quantisation", "Quantisation: {q}.", "Quantification : {q}.")
    val quantType = s("quant_type", "type {n}", "type {n}")
    val notStated = s("not_stated", "not stated", "non indiquée")
    val shortContext = s("short_context", "Short context ({n} tokens): chat only — no actions, shorter memory.",
        "Contexte court ({n} jetons) : conversation seulement — pas d'actions, mémoire plus courte.")
    val contextLine = s("context_line", "Context: {n} tokens (Robot uses {used}).", "Contexte : {n} jetons (Robot en utilise {used}).")
    val licenceNamed = s("licence_named", "Licence the file names: “{licence}”.", "Licence indiquée par le fichier : « {licence} ».")
    val licenceCheck = s("licence_check", "Licence the file names: “{licence}”. Check its terms where you got it.",
        "Licence indiquée par le fichier : « {licence} ». Vérifie ses conditions là où tu l'as obtenu.")
    val ggufMemoryLine = s("gguf_memory_line", "Memory: about {need} while it runs (the file and the cache for Robot's {n} tokens), " +
        "beside about {voice} for Robot's voice.",
        "Mémoire : environ {need} pendant qu'il fonctionne (le fichier et le cache pour les {n} jetons de Robot), en plus d'environ " +
            "{voice} pour la voix de Robot.")
    val ggufCpu = s("gguf_cpu", "Runs on the CPU with llama.cpp: slower than Fast on the TPU, and more battery.",
        "Fonctionne sur le CPU avec llama.cpp : plus lent que Rapide sur le TPU, et plus de batterie.")
    val ggufNoPhotos = s("gguf_no_photos", "Can see photos: no. It reads text only: a photo question won't work, and a screen question " +
        "uses only the screen's text.",
        "Voit les photos : non. Il ne lit que du texte : une question sur une photo ne fonctionnera pas, et une question sur l'écran " +
            "n'utilise que le texte de l'écran.")
    val ggufActions = s("gguf_actions", "Actions (timers, texts, notes…) use Robot's text format: they may not work with every model.",
        "Les actions (minuteries, textos, notes…) utilisent le format texte de Robot : elles pourraient ne pas fonctionner avec tous les modèles.")
    val thinkSwitch = s("think_switch", "It can think before it answers: {page} › “Let the model think first” (off: it answers directly).",
        "Il peut réfléchir avant de répondre : {page} › « Laisser le modèle réfléchir d'abord » (désactivé : il répond directement).")
    val thinksAnyway = s("thinks_anyway", "It may think before it answers, whatever “Let the model think first” says (its template has no " +
        "switch for it): answers can take longer. The thinking is never shown or read out.",
        "Il peut réfléchir avant de répondre, quoi que dise « Laisser le modèle réfléchir d'abord » (son gabarit n'a pas " +
            "d'interrupteur pour ça) : les réponses peuvent prendre plus de temps. La réflexion n'est jamais montrée ni lue à voix haute.")

    // What Robot's check found in your own file: a LiteRT-LM file.
    val notLiteRtLm = s("not_litertlm", "It isn't a LiteRT-LM model file (.litertlm).", "Ce n'est pas un fichier de modèle LiteRT-LM (.litertlm).")
    val noDecoder = s("no_decoder", "It holds no language model (no prefill_decode section).",
        "Il ne contient aucun modèle de langage (pas de section prefill_decode).")
    val partialTpu = s("partial_tpu", "It holds TPU code stamped for the {chip} but not everything a complete {chip} build has " +
        "({missing}), so Robot won't run it on the TPU, and the CPU can't run TPU code.",
        "Il contient du code TPU estampillé pour le {chip}, mais pas tout ce qu'une version complète pour le {chip} contient " +
            "({missing}), alors Robot ne le fera pas fonctionner sur le TPU, et le CPU ne peut pas exécuter de code TPU.")
    val auxSection = s("aux_section", "the aux section", "la section aux")
    val gemma4Metadata = s("gemma4_metadata", "Gemma 4 metadata", "les métadonnées de Gemma 4")
    val otherNpu = s("other_npu", "It was compiled for another chip's NPU, so this phone can run it on neither its TPU nor its CPU.",
        "Il a été compilé pour le NPU d'une autre puce, alors ce téléphone ne peut le faire fonctionner ni sur son TPU ni sur son CPU.")
    val otherNpuStamp = s("other_npu_stamp", "It was compiled for another chip's NPU ({stamp}), so this phone can run it on neither its " +
        "TPU nor its CPU.",
        "Il a été compilé pour le NPU d'une autre puce ({stamp}), alors ce téléphone ne peut le faire fonctionner ni sur son TPU ni " +
            "sur son CPU.")
    val builtShort = s("built_short", "It was built for only {n} tokens of context; Robot needs at least {min} to chat ({max} for actions).",
        "Il a été conçu pour seulement {n} jetons de contexte; Robot en a besoin d'au moins {min} pour converser ({max} pour les actions).")
    val liteRtLmFile = plural("litertlm_file", "n", en = Plural("LiteRT-LM file, format {format}, {n} sections.", "LiteRT-LM file, format {format}, {n} sections."),
        fr = Plural("Fichier LiteRT-LM, format {format}, {n} section.", "Fichier LiteRT-LM, format {format}, {n} sections."))
    val compiledTpu = s("compiled_tpu", "Compiled for the {chip}'s TPU ({series}): yes — aux section, DISPATCH_OP and the stamp “{stamp}”.",
        "Compilé pour le TPU du {chip} ({series}) : oui — section aux, DISPATCH_OP et l'estampille « {stamp} ».")
    val compiledNpu = s("compiled_npu", "Compiled for an NPU, but not as a complete Tensor TPU build.",
        "Compilé pour un NPU, mais pas comme une version TPU complète pour un Tensor.")
    val compiledNpuStamp = s("compiled_npu_stamp", "Compiled for an NPU (stamp “{stamp}”), but not as a complete Tensor TPU build.",
        "Compilé pour un NPU (estampille « {stamp} »), mais pas comme une version TPU complète pour un Tensor.")
    val notCompiledTpu = s("not_compiled_tpu", "Compiled for a TPU: no. It runs on the CPU.", "Compilé pour un TPU : non. Il fonctionne sur le CPU.")
    val gemma4 = s("gemma4", "Gemma 4.", "Gemma 4.")
    val typeNotGemmaShort = s("type_not_gemma_short", "Model type “{type}”, not Gemma 4: Robot is written for Gemma 4, so answers may be odd.",
        "Type de modèle « {type} », pas Gemma 4 : Robot est écrit pour Gemma 4, alors les réponses peuvent être bizarres.")
    val noMetadataShort = s("no_metadata_short", "No model metadata Robot recognises: answers may be odd.",
        "Aucune métadonnée de modèle que Robot reconnaît : les réponses peuvent être bizarres.")
    val gemma4Tools = s("gemma4_tools", "Gemma 4, with its tool-calling metadata: actions should work.",
        "Gemma 4, avec ses métadonnées d'appel d'outils : les actions devraient fonctionner.")
    val gemma4NoTools = s("gemma4_no_tools", "Gemma 4, but without its tool-calling metadata: actions (timers, texts, notes…) may not work.",
        "Gemma 4, mais sans ses métadonnées d'appel d'outils : les actions (minuteries, textos, notes…) pourraient ne pas fonctionner.")
    val typeNotGemma = s("type_not_gemma", "Model type “{type}”, not Gemma 4: Robot is written for Gemma 4, so answers may be odd and " +
        "actions may not work.",
        "Type de modèle « {type} », pas Gemma 4 : Robot est écrit pour Gemma 4, alors les réponses peuvent être bizarres et les " +
            "actions pourraient ne pas fonctionner.")
    val noMetadata = s("no_metadata", "No model metadata Robot recognises: answers may be odd and actions may not work.",
        "Aucune métadonnée de modèle que Robot reconnaît : les réponses peuvent être bizarres et les actions pourraient ne pas fonctionner.")
    val photosYes = s("photos_yes", "Can see photos: yes.", "Voit les photos : oui.")
    val photosNo = s("photos_no", "Can see photos: no (questions about a photo won't work).",
        "Voit les photos : non (les questions sur une photo ne fonctionneront pas).")
    val wontOpen = s("wont_open", "Robot won't open this file: {detail}.", "Robot n'ouvrira pas ce fichier (détail technique : {detail}).")
    val liteRtLmUnreadable = s("litertlm_unreadable", "It isn't a LiteRT-LM model file Robot can read.",
        "Ce n'est pas un fichier de modèle LiteRT-LM que Robot peut lire.")
    val liteRtLmUnreadableDetail = s("litertlm_unreadable_detail", "It isn't a LiteRT-LM model file Robot can read ({detail}).",
        "Ce n'est pas un fichier de modèle LiteRT-LM que Robot peut lire (détail technique : {detail}).")
    val isGguf = s("is_gguf", "It is a GGUF file, not a LiteRT-LM file.", "C'est un fichier GGUF, pas un fichier LiteRT-LM.")
    val notAModel = s("not_a_model", "It isn't a model file Robot can read (a .litertlm or .gguf file).",
        "Ce n'est pas un fichier de modèle que Robot peut lire (un fichier .litertlm ou .gguf).")

    // This phone, its TPU, and how fast the models are on it.
    val unknownPhone = s("unknown_phone", "Unknown phone", "Téléphone inconnu")
    val tpuSupported = s("tpu_supported", "Its TPU is supported: Fast is Google's Tensor G5 build of Gemma 4 E2B, on the TPU.",
        "Son TPU est pris en charge : Rapide est la version de Gemma 4 E2B pour le Tensor G5, de Google, sur le TPU.")
    val tpuBeta = s("tpu_beta", "Its TPU is supported in beta: Fast is Google's Tensor G6 build of Gemma 4 E2B, on the TPU. Robot hasn't " +
        "run it on a Pixel 11 yet; if it doesn't load, Robot switches to the general build on the CPU by itself.",
        "Son TPU est pris en charge en bêta : Rapide est la version de Gemma 4 E2B pour le Tensor G6, de Google, sur le TPU. Robot ne " +
            "l'a pas encore fait fonctionner sur un Pixel 11; s'il ne se charge pas, Robot passe de lui-même à la version générale sur le CPU.")
    val tpuNoneTensor = s("tpu_none_tensor", "Google publishes no TPU build of Gemma for the {chip}", "Google ne publie aucune version TPU de Gemma pour le {chip}")
    val tpuNoneOther = s("tpu_none_other", "Robot has no TPU build for this phone", "Robot n'a aucune version TPU pour ce téléphone")
    val tpuNoneNpu = s("tpu_none_npu", "Robot has no NPU build for this phone's {chip}", "Robot n'a aucune version NPU pour le {chip} de ce téléphone")
    val socNotGiven = s("soc_not_given", "not given", "non indiqué")
    val generalGpu = s("general_gpu", "{why}: Fast is Gemma 4 E2B's general build, on the GPU (recommended) or the CPU.",
        "{why} : Rapide est la version générale de Gemma 4 E2B, sur le GPU (recommandé) ou le CPU.")
    val generalCpu = s("general_cpu", "{why}: Fast is Gemma 4 E2B's general build, on the CPU.",
        "{why} : Rapide est la version générale de Gemma 4 E2B, sur le CPU.")
    val tpuListG5 = s("tpu_list_g5", "Tensor G5 (Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, Pixel 10 Pro Fold): TPU supported. Fast is " +
        "Google's Tensor G5 build.",
        "Tensor G5 (Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, Pixel 10 Pro Fold) : TPU pris en charge. Rapide est la version pour le " +
            "Tensor G5, de Google.")
    val tpuListG6 = s("tpu_list_g6", "Tensor G6 (Pixel 11, Pixel 11 Pro, Pixel 11 Pro XL, Pixel 11 Pro Fold): TPU supported in beta. " +
        "Fast is Google's Tensor G6 build; if it doesn't load, the general build on the CPU.",
        "Tensor G6 (Pixel 11, Pixel 11 Pro, Pixel 11 Pro XL, Pixel 11 Pro Fold) : TPU pris en charge en bêta. Rapide est la version " +
            "pour le Tensor G6, de Google; si elle ne se charge pas, la version générale sur le CPU.")
    val tpuListOthers = s("tpu_list_others", "Tensor G1 to G4 (Pixel 6 to 9 and their a models, Pixel 10a, Fold, Tablet), and other " +
        "phones: no TPU build exists. Fast is the general build, on the GPU (recommended) or the CPU.",
        "Tensor G1 à G4 (Pixel 6 à 9 et leurs modèles a, Pixel 10a, Fold, Tablet), et les autres téléphones : il n'existe aucune " +
            "version TPU. Rapide est la version générale, sur le GPU (recommandé) ou le CPU.")
    val memoryWarning = s("memory_warning", "This phone has {size} of memory: with the voice models loaded too, Android may stop this " +
        "model while it answers. Fast is the safer choice here.",
        "Ce téléphone a {size} de mémoire : avec les modèles de voix chargés aussi, Android pourrait arrêter ce modèle pendant qu'il " +
            "répond. Rapide est le choix le plus sûr ici.")
    // 1.2 (design Step 8): the warning on a slow or small phone, in the setup guide and Settings › Model (core PhoneFit).
    val fitSlowTitle = s("fit_slow_title", "Robot will be slow on this phone", "Robot sera lent sur ce téléphone")
    val fitTightTitle = s("fit_tight_title", "This phone's memory is tight for Robot", "La mémoire de ce téléphone est juste pour Robot")
    val fitSmallTitle = s("fit_small_title", "Robot's model may not fit in this phone's memory",
        "Le modèle de Robot pourrait ne pas tenir dans la mémoire de ce téléphone")
    val fitRunsOn = s("fit_runs_on", "Robot's model runs on this phone's {device}.", "Le modèle de Robot fonctionne sur le {device} de ce téléphone.")
    val fitEstimate = s("fit_estimate", "Estimate: a new conversation starts answering in about {first}\u00A0s, later answers in about {later}\u00A0s.",
        "Estimation : une nouvelle conversation commence à répondre en environ {first} s, les réponses suivantes en environ {later} s.")
    val fitMeasured = s("fit_measured", "Measured on this phone: a new conversation starts answering in about {first}\u00A0s, later answers in " +
        "about {later}\u00A0s.",
        "Mesuré sur ce téléphone : une nouvelle conversation commence à répondre en environ {first} s, les réponses suivantes en " +
            "environ {later} s.")
    val fitHistory = s("fit_history", "To answer sooner, Robot reads back fewer earlier exchanges of a conversation here.",
        "Pour répondre plus vite, Robot relit ici moins d'échanges précédents d'une conversation.")
    val fitCompact = s("fit_compact", "To answer sooner, Robot reads back fewer earlier exchanges of a conversation here, and gives its " +
        "model shorter instructions.",
        "Pour répondre plus vite, Robot relit ici moins d'échanges précédents d'une conversation, et donne des consignes plus courtes " +
            "à son modèle.")
    val fitPicture = s("fit_picture", "A question about a photo or the screen takes longer again: the model looks at the picture first.",
        "Une question sur une photo ou sur l'écran prend encore plus de temps : le modèle regarde d'abord l'image.")
    val fitMemoryTight = s("fit_memory_tight", "This phone has {size} of memory. Robot's model uses about {model} of it while it's loaded, " +
        "and the voice models need room too: Android may stop the model while it answers.",
        "Ce téléphone a {size} de mémoire. Le modèle de Robot en utilise environ {model} quand il est chargé, et les modèles de voix " +
            "ont aussi besoin de place : Android pourrait arrêter le modèle pendant qu'il répond.")
    val fitMemorySmall = s("fit_memory_small", "This phone has {size} of memory. Robot's model uses about {model} of it while it's " +
        "loaded, and the voice models need room too: it may not load here, or Android may stop it while it answers. Robot has no " +
        "smaller model yet.",
        "Ce téléphone a {size} de mémoire. Le modèle de Robot en utilise environ {model} quand il est chargé, et les modèles de voix " +
            "ont aussi besoin de place : il pourrait ne pas se charger ici, ou Android pourrait l'arrêter pendant qu'il répond. Robot " +
            "n'a pas encore de modèle plus petit.")
    val measured = s("measured", "Measured: a new conversation starts answering in about {first}, later answers in about {later}, then " +
        "about {words} words a second.",
        "Mesuré : une nouvelle conversation commence à répondre en environ {first}, les réponses suivantes en environ {later}, puis " +
            "environ {words} mots par seconde.")
    val estimate = s("estimate", "Estimate: a new conversation starts answering in about {first}, later answers in about {later}, then " +
        "about {words} words a second.",
        "Estimation : une nouvelle conversation commence à répondre en environ {first}, les réponses suivantes en environ {later}, " +
            "puis environ {words} mots par seconde.")
    // 1.2 (design Step 5): what Robot measured on this very phone, once it has enough answers (core SpeedLog).
    val measuredHere = s("measured_here", "Measured on this phone: a new conversation starts answering in about {first}, later answers in " +
        "about {later}, then about {words} words a second.",
        "Mesuré sur ce téléphone : une nouvelle conversation commence à répondre en environ {first}, les réponses suivantes en " +
            "environ {later}, puis environ {words} mots par seconde.")
    val measuredHereSource = plural("measured_here_source", "n",
        en = Plural(one = "Robot's medians over {n} recent answer on this phone: {read} tokens a second read, {written} written. These " +
            "numbers stay on this phone.",
            other = "Robot's medians over your last {n} answers on this phone: {read} tokens a second read, {written} written. These " +
                "numbers stay on this phone."),
        fr = Plural(one = "Médianes mesurées par Robot sur {n} réponse récente sur ce téléphone : {read} jetons par seconde lus, " +
            "{written} écrits. Ces chiffres restent sur ce téléphone.",
            other = "Médianes mesurées par Robot sur tes {n} dernières réponses sur ce téléphone : {read} jetons par seconde lus, " +
                "{written} écrits. Ces chiffres restent sur ce téléphone."))
    val googleCpu = s("google_cpu", "Google measured {read} tokens a second read and {written} written on a Galaxy S26 Ultra's CPU (its model card).",
        "Google a mesuré {read} jetons par seconde lus et {written} écrits sur le CPU d'un Galaxy S26 Ultra (selon la page du modèle).")
    val googleGpu = s("google_gpu", "Google measured {read} tokens a second read and {written} written on a Galaxy S26 Ultra's GPU (its model card).",
        "Google a mesuré {read} jetons par seconde lus et {written} écrits sur le GPU d'un Galaxy S26 Ultra (selon la page du modèle).")
    val googleGpuSmaller = s("google_gpu_smaller", "Google measured {read} tokens a second read and {written} written on a Galaxy S26 " +
        "Ultra's GPU (its model card); this phone's GPU is much smaller.",
        "Google a mesuré {read} jetons par seconde lus et {written} écrits sur le GPU d'un Galaxy S26 Ultra (selon la page du modèle); " +
            "le GPU de ce téléphone est beaucoup plus petit.")
    val olderChip = s("older_chip", "A {chip} is older than a Tensor G4: expect it to be slower than this.",
        "Un {chip} est plus ancien qu'un Tensor G4 : attends-toi à ce qu'il soit plus lent que ça.")
    val sourceTpu = s("source_tpu", "Robot measured this on a Pixel 10 Pro's TPU: about 500 to 560 tokens a second read, 17 to 18 written.",
        "Robot a mesuré ceci sur le TPU d'un Pixel 10 Pro : environ 500 à 560 jetons par seconde lus, 17 à 18 écrits.")
    val sourceG6 = s("source_g6", "Estimate, not measured: Robot hasn't run the Tensor G6 build yet. This is its measurement of the " +
        "Tensor G5 build on a Pixel 10 Pro's TPU (about 500 to 560 tokens a second read, 17 to 18 written); the Tensor G6 build has the " +
        "same compiled shapes. Google publishes no figure for it.",
        "Estimation, pas une mesure : Robot n'a pas encore fait fonctionner la version Tensor G6. Voici sa mesure de la version " +
            "Tensor G5 sur le TPU d'un Pixel 10 Pro (environ 500 à 560 jetons par seconde lus, 17 à 18 écrits); la version Tensor G6 a " +
            "les mêmes formes compilées. Google ne publie aucun chiffre pour elle.")
    val sourceGpuMali = s("source_gpu_mali", "Estimated for a Tensor G4's Mali GPU from Robot's run of this file on a Pixel 10 Pro's GPU " +
        "(about 540 tokens a second read, 12 written).",
        "Estimé pour le GPU Mali d'un Tensor G4 à partir de l'essai de ce fichier par Robot sur le GPU d'un Pixel 10 Pro (environ 540 " +
            "jetons par seconde lus, 12 écrits).")
    val sourceNoGpuFigure = s("source_no_gpu_figure", "No figure for this phone's GPU: this is the Tensor G4's estimate.",
        "Aucun chiffre pour le GPU de ce téléphone : voici l'estimation pour le Tensor G4.")
    val sourceCpu = s("source_cpu", "Robot measured this file on a Pixel 10 Pro's CPU: about 160 to 210 tokens a second read, 19 to 20 written.",
        "Robot a mesuré ce fichier sur le CPU d'un Pixel 10 Pro : environ 160 à 210 jetons par seconde lus, 19 à 20 écrits.")
    val sourceCpuG4 = s("source_cpu_g4", "Estimated for a Tensor G4 as two thirds to four fifths of Robot's Pixel 10 Pro measurement of " +
        "this file on the CPU (about 160 to 210 tokens a second read, 19 to 20 written).",
        "Estimé pour un Tensor G4 aux deux tiers ou aux quatre cinquièmes de la mesure de ce fichier par Robot sur le CPU d'un Pixel " +
            "10 Pro (environ 160 à 210 jetons par seconde lus, 19 à 20 écrits).")
    val sourceE4b = s("source_e4b", "Robot measured Gemma 4 E4B on a Pixel 10 Pro's CPU: about 70 tokens a second read, 8 written.",
        "Robot a mesuré Gemma 4 E4B sur le CPU d'un Pixel 10 Pro : environ 70 jetons par seconde lus, 8 écrits.")
    val sourceE4bG4 = s("source_e4b_g4", "Estimated for a Tensor G4 as two thirds to four fifths of Robot's Pixel 10 Pro measurement " +
        "(about 70 tokens a second read, 8 written).",
        "Estimé pour un Tensor G4 aux deux tiers ou aux quatre cinquièmes de la mesure de Robot sur un Pixel 10 Pro (environ 70 jetons " +
            "par seconde lus, 8 écrits).")

    // Before a big download: Robot can't see whether the phone is on Wi-Fi.
    val downloadQuestion = s("download_question", "Download {size}?", "Télécharger {size} ?")
    val downloadMobileData = s("download_mobile_data", "Robot can't tell whether you're on Wi-Fi or mobile data. On mobile data, {size} " +
        "may use up your plan or cost you.\n\nWait for Wi-Fi: the download starts once the phone is on Wi-Fi (or another network that " +
        "isn't metered).",
        "Robot ne peut pas savoir si tu es sur le Wi-Fi ou sur les données mobiles. Sur les données mobiles, {size} pourrait épuiser " +
            "ton forfait ou te coûter de l'argent.\n\nAttendre le Wi-Fi : le téléchargement commence dès que le téléphone est sur le " +
            "Wi-Fi (ou sur un autre réseau qui n'est pas facturé à l'usage).")
    val waitForWifi = s("wait_for_wifi", "Wait for Wi-Fi", "Attendre le Wi-Fi")
    val downloadAnyway = s("download_anyway", "Download anyway", "Télécharger quand même")

    // The model's errors, for the panel.
    val errorNotLoaded = s("error_not_loaded", "The model isn't loaded.", "Le modèle n'est pas chargé.")
    val errorNoVision = s("error_no_vision", "Pictures aren't available: the vision part of the model didn't load.",
        "Les images ne sont pas disponibles : la partie vision du modèle ne s'est pas chargée.")
    val errorBadRequest = s("error_bad_request", "Robot sent the model a request it couldn't read.",
        "Robot a envoyé au modèle une requête qu'il n'a pas pu lire.")
    val errorEngine = s("error_engine", "The model ran into a problem.", "Le modèle a eu un problème.")
    val errorControlQuestion = s("error_control_question",
        "Robot didn't read your message: it holds text this model treats as a command. Try asking without that part.",
        "Robot n'a pas lu ton message : il contient du texte que ce modèle traite comme une commande. Essaie de le demander sans cette partie.")
    val errorControlEarlier = s("error_control_earlier",
        "Part of this conversation holds text this model treats as a command, so Robot leaves it out from now on. Ask again.",
        "Une partie de cette conversation contient du texte que ce modèle traite comme une commande, alors Robot la laisse de côté à partir de maintenant. Redemande.")
    val errorControlUnknown = s("error_control_unknown",
        "This conversation holds text this model treats as a command, so Robot couldn't read it. Start a new conversation.",
        "Cette conversation contient du texte que ce modèle traite comme une commande, alors Robot n'a pas pu la lire. Commence une nouvelle conversation.")
    val errorBusy = s("error_busy", "The model is busy with another answer.", "Le modèle est occupé avec une autre réponse.")
    val errorTooLong = s("error_too_long", "That's too long for Robot to read at once. Try a shorter question.",
        "C'est trop long pour que Robot le lise d'un coup. Essaie une question plus courte.")
    val errorStopped = s("error_stopped", "The model stopped unexpectedly.", "Le modèle s'est arrêté de façon inattendue.")
}
