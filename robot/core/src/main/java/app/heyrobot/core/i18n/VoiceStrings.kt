// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP7b's catalog: the engines and the voice: the voice models' names and descriptions, their downloads
// and installs, the voice engines' states and the voice speed test, and the words :brain and :voice send to the app (as
// PhraseWire, so the app says them in your language). Ids start with "voice."; every entry has its French. What stays
// English, outside this catalog: the voice log (a diagnostic), the speed test's sentences (the clip it reads), the
// sentence Robot reads in the barge-in test, and the engines' and models' names.

object VoiceStrings : Strings("voice", area = "voice") {
    // The voice models: names, what each does, sizes.
    val titleStt = s("title_stt", "Speech recognition", "Reconnaissance vocale")
    val titleKokoro = s("title_kokoro", "Voice", "Voix")
    val titleKitten = s("title_kitten", "Fast voice (fallback)", "Voix rapide (de secours)")
    val titleSpeakers = s("title_speakers", "Speaker labels", "Étiquettes des intervenants")
    val aboutStt = s("about_stt", "NVIDIA Parakeet TDT 0.6B v3 (int8) turns what you say into text, on this phone, in English or French.",
        "NVIDIA Parakeet TDT 0.6B v3 (int8) transforme ce que tu dis en texte, sur ce téléphone, en anglais ou en français.")
    val aboutKokoro = s("about_kokoro", "Kokoro 82M v1.0 reads replies aloud in a natural voice: “Heart” in English, “Siwis” in French. " +
        "Siwis reads French replies when Robot's voice is Heart or Siwis.",
        "Kokoro 82M v1.0 lit les réponses à voix haute d'une voix naturelle : « Heart » en anglais, « Siwis » en français. " +
            "Siwis lit les réponses en français quand la voix de Robot est Heart ou Siwis.")
    val aboutKitten = s("about_kitten", "Kitten TTS nano 0.8: a small English voice, about four times faster than Kokoro and less natural.",
        "Kitten TTS nano 0.8 : une petite voix anglaise, environ quatre fois plus rapide que Kokoro et moins naturelle.")
    val aboutSpeakers = s("about_speakers", "NVIDIA TitaNet-S tells the people in a conversation apart by their voices, on this phone. " +
        "Only transcripts use it.",
        "NVIDIA TitaNet-S distingue les personnes d'une conversation par leur voix, sur ce téléphone. Seules les transcriptions l'utilisent.")
    /** A download's size as 1.0 writes it: "670 MB" / « 670 Mo » (a gigabyte and up: core ModelStrings.gb, "1.02 GB"). */
    val sizeMb = s("size_mb", "{n} MB", "{n} Mo")

    // A voice model's download and install.
    val leftPending = s("left_pending", "only {size} will be left once the downloads already under way finish",
        "il ne restera que {size} une fois les téléchargements déjà en cours terminés")
    val notEnoughSpace = s("not_enough_space", "Not enough free space: “{title}” needs {need} while it downloads and installs, and {left}.",
        "Pas assez d'espace libre : « {title} » a besoin de {need} pendant son téléchargement et son installation, et {left}.")
    val beingInstalled = s("being_installed", "The files are being checked and installed.",
        "Les fichiers sont en cours de vérification et d'installation.")
    val alreadyHere = s("already_here", "Already on this phone.", "Déjà sur ce téléphone.")
    val copying = s("copying", "Copying", "Copie")
    val installingNext = s("installing_next", "Downloaded, installing next", "Téléchargé, installation à suivre")
    val installing = s("installing", "Checking and installing", "Vérification et installation")
    val damaged = s("damaged", "A file was damaged", "Un fichier était endommagé")
    val checkingNext = s("checking_next", "Downloaded. Checking the files next.", "Téléchargé. Vérification des fichiers à suivre.")
    val installPercent = s("install_percent", "Checking and installing: {percent}%", "Vérification et installation : {percent} %")
    val checksumWrong = s("checksum_wrong", "A downloaded file didn't match its checksum, so Robot deleted it. Tap {button} to fetch it again.",
        "Un fichier téléchargé ne correspondait pas à sa somme de contrôle, alors Robot l'a supprimé. Touche {button} pour le télécharger de nouveau.")
    val storageUnavailable = s("storage_unavailable", "The phone's storage isn't available right now.",
        "Le stockage du téléphone n'est pas disponible pour l'instant.")
    val downloadCouldntStart = s("download_couldnt_start", "The download couldn't start.", "Le téléchargement n'a pas pu démarrer.")
    val copyFailed = s("copy_failed", "Robot couldn't copy the files into its storage (is the phone full?). Free some space, then tap {button} to try again.",
        "Robot n'a pas pu copier les fichiers dans son stockage (le téléphone est-il plein ?). Libère de l'espace, puis touche {button} pour réessayer.")
    val installFailed = s("install_failed", "Installing failed. Tap {button} to try again.", "L'installation a échoué. Touche {button} pour réessayer.")

    // The voice engines' states (Settings › Voice).
    val engineMissing = s("engine_missing", "Not downloaded", "Pas téléchargé")
    val engineIdle = s("engine_idle", "Ready (loads when first used)", "Prêt (se charge à la première utilisation)")
    val engineLoading = s("engine_loading", "Loading", "Chargement")
    val engineReady = s("engine_ready", "Loaded", "Chargé")
    val engineFailed = s("engine_failed", "Couldn't start. Robot uses the stand-in for now.",
        "N'a pas pu démarrer. Robot utilise le remplaçant pour l'instant.")
    val engineBroken = s("engine_broken", "Turned off after it crashed twice while loading. Robot uses the stand-in.",
        "Désactivé après avoir planté deux fois pendant le chargement. Robot utilise le remplaçant.")

    // The voice speed test's results (decimals as 1.0 writes them, "0.50 s": a whole part and its decimals).
    val benchStt = s("bench_stt", "Speech recognition: {clip}.{clipD} s of speech written down in {decode}.{decodeD} s (real-time factor " +
        "{rtf}.{rtfD}), {words}% of the words right. Loading took {load}.{loadD} s.",
        "Reconnaissance vocale : {clip},{clipD} s de parole transcrites en {decode},{decodeD} s (facteur temps réel {rtf},{rtfD}), " +
            "{words} % des mots justes. Le chargement a pris {load},{loadD} s.")
    val benchVoice = s("bench_voice", "{voice}: first words after {first}.{firstD} s (a whole sentence: {sentence}.{sentenceD} s), " +
        "real-time factor {rtf}.{rtfD}. Loading took {load}.{loadD} s.",
        "{voice} : premiers mots après {first},{firstD} s (une phrase entière : {sentence},{sentenceD} s), facteur temps réel " +
            "{rtf},{rtfD}. Le chargement a pris {load},{loadD} s.")
    val kokoroSlow = s("kokoro_slow", "Kokoro is slow on this phone: pick Kitten for quicker replies.",
        "Kokoro est lent sur ce téléphone : choisis Kitten pour des réponses plus rapides.")
    val kokoroFast = s("kokoro_fast", "Kokoro is fast enough on this phone.", "Kokoro est assez rapide sur ce téléphone.")
    val benchMemory = s("bench_memory", "Memory used by the voice process: {mb} MB.", "Mémoire utilisée par le processus de la voix : {mb} Mo.")

    // The voice speed test's steps, as the voice process writes them (PhraseWire: the app says them in your language).
    val benchNoModels = s("bench_no_models", "Download the voice models first ({page}).", "Télécharge d'abord les modèles de voix ({page}).")
    val benchLoadingStt = s("bench_loading_stt", "Loading speech recognition and writing down a test sentence…",
        "Chargement de la reconnaissance vocale et transcription d'une phrase de test…")
    val benchSttFailed = s("bench_stt_failed", "Robot was listening, or speech recognition couldn't load. Try again in a moment.",
        "Robot était à l'écoute, ou la reconnaissance vocale n'a pas pu se charger. Réessaie dans un instant.")
    val benchLoadingVoice = s("bench_loading_voice", "Loading the voice “{voice}” and timing it…", "Chargement de la voix « {voice} » et chronométrage…")
    val benchVoiceFailed = s("bench_voice_failed", "Robot was speaking, or the voice “{voice}” couldn't load. Try again in a moment.",
        "Robot parlait, ou la voix « {voice} » n'a pas pu se charger. Réessaie dans un instant.")
    val benchError = s("bench_error", "The test stopped with an error ({error}).", "Le test s'est arrêté sur une erreur ({error}).")

    // Voice input: what the speech recognition tells the panel (in the menus' language, as the panel's notices).
    val sttHintDownload = s("stt_hint_download", "Download voice models", "Télécharger les modèles de voix")
    val sttHintBroken = s("stt_hint_broken", "Voice input is off after two crashes ({page})", "La saisie vocale est désactivée après deux plantages ({page})")
    val sttHintFailed = s("stt_hint_failed", "Voice input couldn't start ({page})", "La saisie vocale n'a pas pu démarrer ({page})")
    val sttBroken = s("stt_broken", "Voice input is turned off: it crashed twice while loading. Tap “{tryAgain}” in the Robot app ({page}), or type " +
        "your question.",
        "La saisie vocale est désactivée : elle a planté deux fois pendant le chargement. Touche « {tryAgain} » dans l'appli Robot " +
            "({page}), ou écris ta question.")
    val sttFailed = s("stt_failed", "Voice input couldn't start. Tap “{tryAgain}” in the Robot app ({page}), or type your question.",
        "La saisie vocale n'a pas pu démarrer. Touche « {tryAgain} » dans l'appli Robot ({page}), ou écris ta question.")
    val sttNotReady = s("stt_not_ready", "Voice input isn't ready. Type your question for now.",
        "La saisie vocale n'est pas prête. Écris ta question pour l'instant.")
    val sttCouldntStart = s("stt_couldnt_start", "Voice input couldn't start. Type your question for now.",
        "La saisie vocale n'a pas pu démarrer. Écris ta question pour l'instant.")
    val sttNoMic = s("stt_no_mic", "Robot can't use the microphone. Allow it in the Robot app, or close the app that is recording.",
        "Robot ne peut pas utiliser le micro. Autorise-le dans l'appli Robot, ou ferme l'appli qui enregistre.")
    val sttMicBusy = s("stt_mic_busy", "Another app is using the microphone.", "Une autre appli utilise le micro.")
    val sttMicStopped = s("stt_mic_stopped", "The microphone stopped before you spoke. Tap the mic and try again.",
        "Le micro s'est arrêté avant que tu parles. Touche le micro et réessaie.")
    val sttNothingHeard = s("stt_nothing_heard", "I didn't hear anything. Tap the mic and try again.",
        "Je n'ai rien entendu. Touche le micro et réessaie.")
    val sttDecodeFailed = s("stt_decode_failed", "Voice input failed. Type your question for now.", "La saisie vocale a échoué. Écris ta question pour l'instant.")
    val sttNeedsModels = s("stt_needs_models", "Voice input needs the voice models: download them in the Robot app ({page}). Type your question for now.",
        "La saisie vocale a besoin des modèles de voix : télécharge-les dans l'appli Robot ({page}). Écris ta question pour l'instant.")

    // Why :brain didn't load a model (sent to the app as PhraseWire; the app says it in your language).
    val engineLiteRtOnly = s("engine_litertlm_only", "LiteRT-LM runs .litertlm files only.", "LiteRT-LM ne fait fonctionner que les fichiers .litertlm.")
    val engineFileMissing = s("engine_file_missing", "The model file is missing.", "Le fichier du modèle manque.")
    val engineCantRunOn = s("engine_cant_run_on", "Robot's check of the file says it can't run on the {backend}.",
        "La vérification du fichier par Robot dit qu'il ne peut pas fonctionner sur le {backend}.")
    val engineNoCacheSpace = s("engine_no_cache_space", "There isn't enough free space for the cache it writes on its first load (about {size}).",
        "Il n'y a pas assez d'espace libre pour le cache qu'il écrit à son premier chargement (environ {size}).")
    val engineDidntLoad = s("engine_didnt_load", "The model didn't load ({error}).", "Le modèle ne s'est pas chargé ({error}).")
    /** A cache's size with one decimal, as :brain writes it: "2.5 GB" / « 2,5 Go ». */
    val gbOne = s("gb_one", "{int}.{frac} GB", "{int},{frac} Go")
    val ggufCpuOnly = s("gguf_cpu_only", "A GGUF file runs on the CPU only.", "Un fichier GGUF ne fonctionne que sur le CPU.")
    val llamaTimeout = s("llama_timeout", "llama.cpp didn't finish loading the file.", "llama.cpp n'a pas fini de charger le fichier.")
    val llamaStopped = s("llama_stopped", "llama.cpp stopped while it read the file.", "llama.cpp s'est arrêté pendant qu'il lisait le fichier.")
    val llamaCouldntLoad = s("llama_couldnt_load", "llama.cpp couldn't load the file.", "llama.cpp n'a pas pu charger le fichier.")
    val llamaNoProcess = s("llama_no_process", "Robot couldn't start llama.cpp's process.", "Robot n'a pas pu démarrer le processus de llama.cpp.")
    val llamaNoContext = s("llama_no_context", "llama.cpp couldn't make room for its context (memory).",
        "llama.cpp n'a pas pu faire de place pour son contexte (mémoire).")
    val llamaNoTemplate = s("llama_no_template", "llama.cpp couldn't read the file's chat template.",
        "llama.cpp n'a pas pu lire le gabarit de conversation du fichier.")
    val llamaCpu = s("llama_cpu", "This phone's CPU lacks what Robot's llama.cpp is built for ({missing}).",
        "Il manque au CPU de ce téléphone ce pour quoi le llama.cpp de Robot est compilé ({missing}).")
    val llamaNotIsolated = s("llama_not_isolated", "llama.cpp's process isn't an isolated one, so Robot won't give it the file.",
        "Le processus de llama.cpp n'est pas isolé, alors Robot ne lui donnera pas le fichier.")
    val llamaDifferent = s("llama_different", "llama.cpp read this file's header differently from Robot's check, so Robot won't run it.",
        "llama.cpp a lu l'en-tête de ce fichier autrement que la vérification de Robot, alors Robot ne le fera pas fonctionner.")
    val llamaNotInBuild = s("llama_not_in_build", "GGUF files can't run in this build of Robot: its llama.cpp library wasn't built (tools/build_llama.sh).",
        "Les fichiers GGUF ne peuvent pas fonctionner dans cette version de Robot : sa bibliothèque llama.cpp n'a pas été compilée (tools/build_llama.sh).")
    val llamaOnlyIsolated = s("llama_only_isolated", "llama.cpp runs only in Robot's isolated process.",
        "llama.cpp ne fonctionne que dans le processus isolé de Robot.")
}
