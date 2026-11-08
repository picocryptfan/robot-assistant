// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// 1.1's catalog for Skills (docs/plans/skills-1.1-plan.md §10): core/skills/ and what the panel says about skills
// (assistant/skills PanelSkills, SkillRunner). Ids start with "skill."; the area is "skills". Québec French (tu), the
// glossary's words: « compétence » (never « habileté » or « aptitude »), « étape », « IA externe », « appli ». A skill's
// name, a value and a step's summary are arguments, never translated. render() adds the French spaces.

object SkillStrings : Strings("skill", area = "skills") {
    // Teaching and "save that" (plan §6): the save card.
    val whichSteps = s("which_steps", "What should “{name}” do? Say the steps, like “text Sam I'm on my way, then open the route home.”",
        "Que doit faire « {name} » ? Dis les étapes, par exemple « texte à Sam que je m'en viens, puis ouvre l'itinéraire vers la maison ».")
    val notUnderstood = s("not_understood", "Robot didn't understand: “{words}”", "Robot n'a pas compris : « {words} »")
    val cantDo = s("cant_do", "Skills can't do this: {step}.", "Les compétences ne peuvent pas faire ceci : {step}.")
    val leftOutHelper = s("left_out_helper", "Left out: what a helper asked for.", "Laissé de côté : ce qu'une IA externe a demandé.")
    val leftOutFailed = s("left_out_failed", "Left out: what didn't happen.", "Laissé de côté : ce qui ne s'est pas fait.")
    val nothingToSave = s("nothing_to_save", "Nothing to save yet. Ask me to do something, then say “save that as a skill.”",
        "Rien à enregistrer pour l'instant. Demande-moi de faire quelque chose, puis dis « enregistre ça comme compétence ».")
    val tapToSave = s("tap_to_save", "Tap {save}: this skill came from the screen or something Robot read.",
        "Touche {save} : cette compétence vient de l'écran ou de quelque chose que Robot a lu.")
    val sayYesOrSave = s("say_yes_or_save", "Say yes or tap {save}.", "Dis oui ou touche {save}.")
    val commonWord = s("common_word", "“{name}” alone will run this skill.", "« {name} » tout seul lancera cette compétence.")
    val moreApps = s("more_apps", "You'll tap a notification to go on after the first app opens.",
        "Tu toucheras une notification pour continuer après l'ouverture de la première appli.")
    val waitTip = s("wait_tip", "Long pauses? In {page}, set Wait before answering to Long.",
        "De longues pauses ? Dans {page}, mets Attendre avant de répondre à Long.")
    val cardNew = s("card_new", "New skill: {name}", "Nouvelle compétence : {name}")
    val cardSave = s("card_save", "Save as a skill: {name}", "Enregistrer comme compétence : {name}")
    val fixed = s("fixed", "fixed", "fixe")
    val asked = s("asked", "asked each time", "demandé chaque fois")
    val valueChip = s("value_chip", "{value} · {state}", "{value} · {state}")
    val stepLine = s("step_line", "{i}. {step}", "{i}. {step}")
    val askedLine = s("asked_line", "“{ask}” Default: {value}", "« {ask} » Par défaut : {value}")
    val level = s("level", "Level: {level}", "Niveau : {level}")
    val rename = s("rename", "Change the name", "Changer le nom")
    val renameAsk = s("rename_ask", "What should the skill be called?", "Comment veux-tu appeler la compétence ?")
    val askDuration = s("ask_duration", "How long?", "Combien de temps ?")
    val askTime = s("ask_time", "What time?", "À quelle heure ?")
    val askPerson = s("ask_person", "Who to?", "À qui ?")
    val askApp = s("ask_app", "Which app?", "Quelle appli ?")
    val askPlace = s("ask_place", "Where to?", "Où ?")
    val askText = s("ask_text", "What should it say?", "Qu'est-ce que ça doit dire ?")
    val stepSay = s("step_say", "Say “{text}”", "Dire « {text} »")
    val saved = s("saved", "Saved “{name}”. Say “{key}” to run it.", "« {name} » est enregistrée. Dis « {key} » pour la lancer.")
    val notSaved = s("not_saved", "Not saved.", "Pas enregistrée.")
    val couldntSave = s("couldnt_save", "Robot couldn't save the skill.", "Robot n'a pas pu enregistrer la compétence.")

    // "Save that" without a name: its first steps' kinds ("Timer and text").
    val defaultName = s("default_name", "{first} and {second}", "{first} et {second}")
    val kindTimer = s("kind_timer", "Timer", "Minuterie")
    val kindAlarm = s("kind_alarm", "Alarm", "Alarme")
    val kindFlashlight = s("kind_flashlight", "Flashlight", "Lampe de poche")
    val kindApp = s("kind_app", "App", "Appli")
    val kindMaps = s("kind_maps", "Maps", "Carte")
    val kindText = s("kind_text", "Text", "Texto")
    val kindEmail = s("kind_email", "Email", "Courriel")
    val kindNote = s("kind_note", "Note", "Note")
    val kindEvent = s("kind_event", "Event", "Événement")
    val kindWeather = s("kind_weather", "Weather", "Météo")
    val kindCalendar = s("kind_calendar", "Calendar", "Agenda")
    val kindNotes = s("kind_notes", "Notes", "Notes")
    val kindPhone = s("kind_phone", "Phone", "Téléphone")
    val kindContact = s("kind_contact", "Contact", "Contact")
    val kindSay = s("kind_say", "Words", "Mots")

    // Names (D15): what a name may not be.
    val nameRobot = s("name_robot", "Robot already uses “{name}” for {what}. Pick another name.",
        "Robot utilise déjà « {name} » pour {what}. Choisis un autre nom.")
    val nameSkill = s("name_skill", "You already have a skill called “{name}”.", "Tu as déjà une compétence qui s'appelle « {name} ».")
    val nameRoutine = s("name_routine", "A routine is called “{name}”.", "Une routine s'appelle « {name} ».")
    val nameLock = s("name_lock", "A phone lock is called “{name}”.", "Un blocage du téléphone s'appelle « {name} ».")
    val nameHelper = s("name_helper", "“{name}” is a helper's name.", "« {name} » est le nom d'une IA externe.")
    val nameWake = s("name_wake", "“{name}” is a wake phrase.", "« {name} » est une phrase d'éveil.")
    val nameScript = s("name_script", "A Termux script is called “{name}”.", "Un script Termux s'appelle « {name} ».")
    val nameCommand = s("name_command", "“{word}” is a command word.", "« {word} » est un mot de commande.")
    val nameRules = s("name_rules", "A skill's name is 1 to 40 characters on one line.", "Le nom d'une compétence a de 1 à 40 caractères sur une ligne.")
    val otherNameRules = s("other_name_rules", "Other names are 1 to 5 words.", "Les autres noms ont de 1 à 5 mots.")
    val tooManyNames = s("too_many_names", "A skill has at most 6 other names in each language.", "Une compétence a au plus 6 autres noms dans chaque langue.")
    val whatMessages = s("what_messages", "your messages", "tes messages")
    val whatConversation = s("what_conversation", "conversation mode", "le mode conversation")
    val whatFeedback = s("what_feedback", "feedback", "les commentaires")
    val whatLock = s("what_lock", "the phone lock", "le blocage du téléphone")
    val whatRoutines = s("what_routines", "routines", "les routines")
    val whatHandsFree = s("what_hands_free", "headset and car mode", "les écouteurs et le mode auto")
    val whatWeb = s("what_web", "web search", "la recherche web")
    val whatMemory = s("what_memory", "memory", "la mémoire")
    val whatFreeUp = s("what_free_up", "freeing up memory", "libérer de la mémoire")
    val whatHelpers = s("what_helpers", "helpers", "les IA externes")
    val whatSkills = s("what_skills", "skills", "les compétences")

    // What can't be saved (SkillCheck).
    val tooMany = s("too_many", "You have {max} skills, the most Robot keeps. Delete one first.",
        "Tu as {max} compétences, le maximum que Robot garde. Supprimes-en une d'abord.")
    val tooManySteps = s("too_many_steps", "A skill has at most 8 steps.", "Une compétence a au plus 8 étapes.")
    val noSteps = s("no_steps", "A skill needs at least one step.", "Une compétence a besoin d'au moins une étape.")
    val tooManyQuestions = s("too_many_questions", "A skill asks at most 3 questions.", "Une compétence pose au plus 3 questions.")
    val secret = s("secret", "This looks like a password or key. Skills can't keep one.",
        "Ça ressemble à un mot de passe ou à une clé. Les compétences ne peuvent pas en garder.")
    val stepUnusable = s("step_unusable", "A step's details can't be used: {step}.", "Les détails d'une étape ne peuvent pas servir : {step}.")
    val questionUnusable = s("question_unusable", "A question can't be used: {ask}", "Une question ne peut pas servir : {ask}")

    // Running (plan §7).
    val isOff = s("is_off", "“{name}” is off. Turn it on in {page}.", "« {name} » est désactivée. Active-la dans {page}.")
    val unlockChange = s("unlock_change", "Unlock your phone to change your skills.", "Déverrouille ton téléphone pour changer tes compétences.")
    val unlockSee = s("unlock_see", "Unlock your phone to see your skills.", "Déverrouille ton téléphone pour voir tes compétences.")
    val runTitle = s("run_title", "Run “{name}”?", "Lancer « {name} » ?")
    val runSayYes = s("run_say_yes", "Say yes or tap {run}.", "Dis oui ou touche {run}.")
    val runTapContent = s("run_tap_content", "Tap {run}: this skill came from the screen or something Robot read.",
        "Touche {run} : cette compétence vient de l'écran ou de quelque chose que Robot a lu.")
    val runTapMessage = s("run_tap_message", "Tap {run}: a message someone sent is in this conversation.",
        "Touche {run} : un message que quelqu'un a envoyé est dans cette conversation.")
    val running = s("running", "Running “{name}”.", "Je lance « {name} ».")
    val notRun = s("not_run", "Didn't run “{name}”.", "« {name} » n'a pas été lancée.")
    val done = s("done", "{name}: {done} of {total} done.", "{name} : {done} sur {total} faites.")
    val stopped = s("stopped", "{name} stopped at step {n}: {why}. The rest didn't run.",
        "{name} s'est arrêtée à l'étape {n} : {why}. Le reste ne s'est pas fait.")
    val stoppedLast = s("stopped_last", "{name} stopped at step {n}: {why}.", "{name} s'est arrêtée à l'étape {n} : {why}.")
    val continueTitle = s("continue_title", "Continue “{name}”", "Continuer « {name} »")
    val continueText = s("continue_text", "Next: {step}. Tap to go on.", "Prochaine étape : {step}. Touche pour continuer.")
    val continuePublic = s("continue_public", "A skill is waiting for you", "Une compétence t'attend")
    val continueAny = s("continue_any", "Continue a skill", "Continuer une compétence")
    val expired = s("expired", "That skill's next step has expired. Say its name to run it again.",
        "La prochaine étape de cette compétence a expiré. Dis son nom pour la relancer.")
    val noNotifications = s("no_notifications", "Step {n} can't wait for you: Robot's notifications are off.",
        "L'étape {n} ne peut pas t'attendre : les notifications de Robot sont désactivées.")

    // Skills connections (docs/plans/skills-connections-plan.md §5, C1): a step that reaches a connection, an app or a
    // script, as a skill's card and Settings list it until C5 to C9 name the operation and the connection, and a skill
    // whose link is gone (SkillCheck.attention).
    val stepConnection = s("step_connection", "Use one of your connections", "Utiliser une de tes connexions")
    val stepTravel = s("step_travel", "Check the travel time", "Vérifier le temps de trajet")
    val stepScript = s("step_script", "Run your script “{name}”", "Lancer ton script « {name} »")
    val stepHandOff = s("step_hand_off", "Open an app you picked", "Ouvrir une appli que tu as choisie")
    val needsAttention = s("needs_attention", "“{name}” needs attention. Check its steps in {page}.",
        "« {name} » a besoin de ton attention. Vérifie ses étapes dans {page}.")

    // Asking about skills, deleting one.
    val none = s("none", "You have no skills yet. Say “teach a skill called …” to make one.",
        "Tu n'as pas encore de compétences. Dis « apprends une compétence qui s'appelle … » pour en créer une.")
    val list = s("list", "Your skills: {names}.", "Tes compétences : {names}.")
    val quoted = s("quoted", "“{name}”", "« {name} »")
    val about = s("about", "“{name}”: {steps}.", "« {name} » : {steps}.")
    val deleteCard = s("delete_card", "Delete the skill “{name}”?", "Supprimer la compétence « {name} » ?")
    val tapDelete = s("tap_delete", "Tap {delete} on the card to delete it.", "Touche {delete} sur la carte pour la supprimer.")
    val deleted = s("deleted", "Deleted “{name}”.", "« {name} » est supprimée.")
    val kept = s("kept", "Kept “{name}”.", "« {name} » est gardée.")
    val noSuch = s("no_such", "You have no skill called “{name}”.", "Tu n'as pas de compétence qui s'appelle « {name} ».")
    val couldntChange = s("couldnt_change", "Robot couldn't change your skills.", "Robot n'a pas pu changer tes compétences.")

    // Settings › Skills (plan §9) and the file's state.
    val madeVoice = s("made_voice", "Taught by voice on {date}", "Apprise de vive voix le {date}")
    val madeDemo = s("made_demo", "Saved from what Robot did on {date}", "Enregistrée à partir de ce que Robot a fait le {date}")
    val madeSettings = s("made_settings", "Made in Settings on {date}", "Créée dans les Réglages le {date}")
    // S3.2: the 1.3 origins (format v3), and a routine's skill.
    val madeRoutine = s("made_routine", "Made from your routine on {date}", "Créée à partir de ta routine le {date}")
    val madeDraftDevice = s("made_draft_device", "Made from a draft on this phone on {date}", "Créée d'un brouillon sur ce téléphone le {date}")
    val madeDraftHelper = s("made_draft_helper", "Made from a helper's draft on {date}", "Créée d'un brouillon d'une IA externe le {date}")
    val madeImport = s("made_import", "Imported on {date}", "Importée le {date}")
    val startsByItself = s("starts_by_itself", "Starts by itself: {when}", "Démarre toute seule : {when}")
    val routineCantRun = s("routine_cant_run", "“{name}” starts by itself as a routine, and a routine can't run these steps by itself.",
        "« {name} » démarre toute seule comme routine, et une routine ne peut pas faire ces étapes toute seule.")
    val routinesSkill = s("routines_skill", "“{name}” starts by itself as a routine. Delete the routine in {page}.",
        "« {name} » démarre toute seule comme routine. Supprime la routine dans {page}.")
    val madeContent = s("made_content", "From the screen or something Robot read", "Vient de l'écran ou de quelque chose que Robot a lu")
    val edited = s("edited", "edited {date}", "modifiée le {date}")
    val lastRun = s("last_run", "{day} {time} · {done} of {total} done", "{day} {time} · {done} sur {total} faites")
    val neverRun = s("never_run", "Never run", "Jamais lancée")
    val levelRow = s("level_row", "{level} · {run}", "{level} · {run}")
    val newer = s("newer", "Your skills are from a newer Robot. Update Robot to use them.",
        "Tes compétences viennent d'une version plus récente de Robot. Mets Robot à jour pour t'en servir.")
    val damaged = s("damaged", "Robot couldn't read your skills.", "Robot n'a pas pu lire tes compétences.")
    val dropped = plural("dropped", "n", Plural("{n} skill couldn't be read.", "{n} skills couldn't be read."),
        Plural("{n} compétence n'a pas pu être lue.", "{n} compétences n'ont pas pu être lues."))
    val shadowsRoutine = s("shadows_routine", "A routine with this name runs first.", "Une routine qui porte ce nom passe en premier.")
    val shadowsLock = s("shadows_lock", "A phone lock with this name runs first.", "Un blocage du téléphone qui porte ce nom passe en premier.")
    val shadowsScript = s("shadows_script", "This skill runs instead of the Termux script with this name.",
        "Cette compétence passe avant le script Termux qui porte ce nom.")
    val contentCheck = s("content_check", "Made from the screen or something Robot read. Check its steps.",
        "Faite à partir de l'écran ou de quelque chose que Robot a lu. Vérifie ses étapes.")

    // Phase B: the model picks and fills skills (plan §8).
    val changed = s("changed", "This skill changed. Ask again.", "Cette compétence a changé. Redemande-moi.")
    val unknownValue = s("unknown_value", "This skill has no question “{name}”.", "Cette compétence n'a pas de question « {name} ».")
    val notText = s("not_text", "The value of “{name}” isn't text.", "La valeur de « {name} » n'est pas du texte.")
    val fillRefused = s("fill_refused", "Robot's model gave a value Robot can't use for “{ask}”.",
        "Le modèle de Robot a donné une valeur que Robot ne peut pas utiliser pour « {ask} ».")
    val callNamed = s("call_named", "Skill: {name}", "Compétence : {name}")
    val callAny = s("call_any", "A skill", "Une compétence")
    val modelText = s("model_text", "written by Robot's model", "écrit par le modèle de Robot")
    val didYouMean = s("did_you_mean", "Did you mean “{name}”?", "Tu voulais dire « {name} » ?")
    val yesRun = s("yes_run", "Yes, run it", "Oui, lance-la")
    val no = s("no", "No", "Non")
    val didYouMeanSay = s("did_you_mean_say", "Say yes or tap {yes}.", "Dis oui ou touche {yes}.")
    val didYouMeanTap = s("did_you_mean_tap", "Tap {yes} to run it.", "Touche {yes} pour la lancer.")
    val filledBy = s("filled_by", "Filled by {model}", "Remplie par {model}")
    val ranFilled = s("ran_filled", "Ran “{name}”, filled by {model}.", "J'ai lancé « {name} », remplie par {model}.")
    val fills = s("fills", "On {model}: {right} of the last {n} right", "Avec {model} : {right} justes sur les {n} dernières")
    val changeValue = s("change_value", "Change: {ask}", "Changer : {ask}")
    val runTapModel = s("run_tap_model", "Tap {run}: Robot's model filled this skill.", "Touche {run} : le modèle de Robot a rempli cette compétence.")

    // Sharing (1.3 plan S3.3, design §11): why a file is refused, why a skill can't be shared, what the preview flags.
    val importTooLarge = s("import_too_large", "This file is too large to be a skill file.", "Ce fichier est trop gros pour être un fichier de compétences.")
    val importNotSkills = s("import_not_skills", "This isn't a Robot skill file, or it's damaged.",
        "Ce n'est pas un fichier de compétences de Robot, ou il est endommagé.")
    val importTooNew = s("import_too_new", "This file is from a newer Robot. Update Robot to import it.",
        "Ce fichier vient d'un Robot plus récent. Mets Robot à jour pour l'importer.")
    val importKey = s("import_key", "This file holds something shaped like a key or a password, so Robot won't import it.",
        "Ce fichier contient quelque chose qui a la forme d'une clé ou d'un mot de passe, alors Robot ne l'importera pas.")
    val importChanged = s("import_changed", "The signature doesn't match: the file was changed after it was signed.",
        "La signature ne correspond pas : le fichier a été modifié après avoir été signé.")
    val importNone = s("import_none", "None of the skills in this file can be used here.", "Aucune des compétences de ce fichier ne peut être utilisée ici.")
    val shareKey = s("share_key", "“{name}” holds something shaped like a key or a password, so Robot won't share it.",
        "« {name} » contient quelque chose qui a la forme d'une clé ou d'un mot de passe, alors Robot ne la partagera pas.")
    val shareConnection = s("share_connection", "“{name}” uses a connection Robot can't find.", "« {name} » utilise une connexion que Robot ne trouve pas.")
    val shareTooLarge = s("share_too_large", "These skills are too large for one file. Share fewer at a time.",
        "Ces compétences sont trop grosses pour un seul fichier. Partages-en moins à la fois.")
    val flagPerson = s("flag_person", "a person's name", "le nom d'une personne")
    val flagMessage = s("flag_message", "a message", "un message")
    val flagPrivate = s("flag_private", "something private", "quelque chose de privé")
    val flagEmail = s("flag_email", "an email address", "une adresse courriel")
    val flagAddress = s("flag_address", "a street address", "une adresse")
    val flagNumber = s("flag_number", "a number", "un numéro")

    // Sharing screens (1.3 plan S3.4, design §11.2): who signed a file, a renamed import, the keys you trust.
    val signedNobody = s("signed_nobody", "From a file · not signed", "D'un fichier · non signé")
    val signedThisPhone = s("signed_this_phone", "From a file · signed by this phone's key", "D'un fichier · signé par la clé de ce téléphone")
    val signedTrusted = s("signed_trusted", "From a file · signed by your key “{name}”", "D'un fichier · signé par ta clé « {name} »")
    val signedUnknown = s("signed_unknown", "From a file · signed by {key} … (a key you don't trust)",
        "D'un fichier · signé par {key} … (une clé à laquelle tu ne fais pas confiance)")
    val importedName = s("imported_name", "{name} (imported)", "{name} (importée)")
    val importedNameN = s("imported_name_n", "{name} (imported {n})", "{name} (importée {n})")
    val keyNotFingerprint = s("key_not_fingerprint", "A key's fingerprint is 64 digits and letters from A to F.",
        "L'empreinte d'une clé compte 64 chiffres et lettres de A à F.")
    val keyIsYours = s("key_is_yours", "That's this phone's own key.", "C'est la clé de ce téléphone.")
    val keyAlready = s("key_already", "You already trust that key, as “{name}”.", "Tu fais déjà confiance à cette clé, sous le nom « {name} ».")
    val keyName = s("key_name", "Give it a name of up to 40 characters.", "Donne-lui un nom de 40 caractères au plus.")
    val keyFull = s("key_full", "You can trust up to {n} keys. Remove one first.", "Tu peux faire confiance à {n} clés au plus. Retires-en une d'abord.")

    // 1.3, plans (design §9): the plan card and its lines. A step's summary and the model's name are arguments.
    val planMaking = s("plan_making", "Making a plan…", "Je fais un plan…")
    val planTitle = s("plan_title", "Plan · {n} steps", "Plan · {n} étapes")
    val planStepNow = s("plan_step_now", "{n}. {step} · reads · runs now", "{n}. {step} · lecture · se fait tout de suite")
    val planStepWaits = s("plan_step_waits", "{n}. {step} · waits for you", "{n}. {step} · attend ton accord")
    val planStepUnasked = s("plan_step_unasked", "{n}. {step} · waits for you · you didn't ask for this",
        "{n}. {step} · attend ton accord · tu ne l'as pas demandé")
    val planStepDone = s("plan_step_done", "{n}. {step} · done", "{n}. {step} · fait")
    val planStepNotRun = s("plan_step_not_run", "{n}. {step} · didn't run", "{n}. {step} · pas fait")
    val planSimple = s("plan_simple", "Simple · planned by {model}", "Simple · planifié par {model}")
    val planCareful = s("plan_careful", "Careful · planned by {model}", "Délicat · planifié par {model}")
    val planRisky = s("plan_risky", "Risky · planned by {model}", "Risqué · planifié par {model}")
    val planModel = s("plan_model", "Robot's model", "le modèle de Robot")
    val planDoRest = s("plan_do_rest", "Do the rest", "Fais le reste")
    val planStepByStep = s("plan_step_by_step", "Step by step", "Étape par étape")
    val planSayYes = s("plan_say_yes", "Say yes or tap {go} for the rest.", "Dis oui ou touche {go} pour le reste.")
    val planTap = s("plan_tap", "Tap {go}: check each step first.", "Touche {go} : vérifie chaque étape d'abord.")
    val planDone = s("plan_done", "Plan done: {done} of {total} steps. Say “save that as a skill” to keep it.",
        "Plan terminé : {done} étapes sur {total}. Dis « enregistre ça comme compétence » pour le garder.")
    val planStopped = s("plan_stopped", "The plan stopped at step {n}: {why}. Didn't run: {left}.",
        "Le plan s'est arrêté à l'étape {n} : {why}. Pas fait : {left}.")
    val planStoppedLast = s("plan_stopped_last", "The plan stopped at step {n}: {why}.", "Le plan s'est arrêté à l'étape {n} : {why}.")
    val planCancelled = s("plan_cancelled", "Plan cancelled. Nothing else ran.", "Plan annulé. Rien d'autre ne s'est fait.")
    val planFailed = s("plan_failed", "I couldn't make a plan for that. Ask me one thing at a time.",
        "Je n'ai pas pu faire de plan pour ça. Demande-moi une chose à la fois.")
    val planAppLast = s("plan_app_last", "A plan can open an app only as its last step. Ask me one thing at a time.",
        "Un plan peut ouvrir une appli seulement à sa dernière étape. Demande-moi une chose à la fois.")
    val planOffer = s("plan_offer", "Make that a plan?", "On en fait un plan ?")
    val planOfferBody = s("plan_offer_body", "That was more than {n} actions at once, so the rest didn't run. A plan shows each one first.",
        "C'était plus de {n} actions à la fois, donc le reste ne s'est pas fait. Un plan montre chacune d'abord.")
    val planMake = s("plan_make", "Make a plan", "Faire un plan")

    // 1.3, drafts (design §10): a skill Robot's model wrote from what you described, on its save card.
    val draftMaking = s("draft_making", "Drafting a skill…", "J'écris un brouillon de compétence…")
    val cardDraft = s("card_draft", "New skill (draft): {name}", "Nouvelle compétence (brouillon) : {name}")
    val draftCheck = s("draft_check", "Robot's model drafted this: check every line.", "Le modèle de Robot a écrit ce brouillon : vérifie chaque ligne.")
    val tapDraft = s("tap_draft", "Tap {save}: Robot's model wrote this skill.", "Touche {save} : le modèle de Robot a écrit cette compétence.")
    val draftFromWeb = s("draft_from_web", "Made from a web page you asked about. It stays at Ask first, with no trigger, until you edit and save it in Settings.",
        "Faite à partir d'une page web que tu as demandée. Elle reste à Demander d'abord, sans déclencheur, jusqu'à ce que tu la modifies et l'enregistres dans les Réglages.")
    val draftFromMessage = s("draft_from_message", "Made from a message. It stays at Ask first, with no trigger, until you edit and save it in Settings.",
        "Faite à partir d'un message. Elle reste à Demander d'abord, sans déclencheur, jusqu'à ce que tu la modifies et l'enregistres dans les Réglages.")
    val draftFromScreen = s("draft_from_screen", "Made from your screen. It stays at Ask first, with no trigger, until you edit and save it in Settings.",
        "Faite à partir de ton écran. Elle reste à Demander d'abord, sans déclencheur, jusqu'à ce que tu la modifies et l'enregistres dans les Réglages.")
    val draftFromContent = s("draft_from_content", "Made from something Robot read. It stays at Ask first, with no trigger, until you edit and save it in Settings.",
        "Faite à partir de quelque chose que Robot a lu. Elle reste à Demander d'abord, sans déclencheur, jusqu'à ce que tu la modifies et l'enregistres dans les Réglages.")
    val draftLeftOut = s("draft_left_out", "Robot left out step {n}, {what}: {why}.", "Robot a laissé de côté l'étape {n}, {what} : {why}.")
    val draftWhyUnknown = s("draft_why_unknown", "Robot has no such action", "Robot n'a pas cette action")
    val draftWhyNever = s("draft_why_never", "skills never do that", "les compétences ne font jamais ça")
    val draftWhyOff = s("draft_why_off", "it's turned off", "c'est désactivé")
    val draftWhyConn = s("draft_why_conn", "it isn't one of your connections", "ce n'est pas une de tes connexions")
    val draftWhyValues = s("draft_why_values", "its values didn't check out", "ses valeurs ne sont pas valides")
    val draftWhySender = s("draft_why_sender", "after a message, a skill sends only to who wrote it", "après un message, une compétence envoie seulement à la personne qui l'a écrit")
    val draftFound = s("draft_found", "{who}: {name} in your contacts", "{who} : {name} dans tes contacts")
    val draftMissing = s("draft_missing", "{who}: not in your contacts", "{who} : pas dans tes contacts")
    val draftFailed = s("draft_failed", "I couldn't draft that skill. Try saying its steps: “teach a skill called …”.",
        "Je n'ai pas pu écrire cette compétence. Essaie de dire ses étapes : « apprends une compétence qui s'appelle … ».")
    val draftLimit = s("draft_limit", "That's {n} drafts this hour. Try again later.", "Ça fait {n} brouillons cette heure-ci. Réessaie plus tard.")
    val draftCheckHelper = s("draft_check_helper", "A helper drafted this: check every line.", "Une IA externe a écrit ce brouillon : vérifie chaque ligne.")

    // 1.3 (S3.9, design §9 and §10.1): a plan or a draft by a helper, after the card that shows what would be sent.
    val helpPlanChip = s("help_plan_chip", "Let {name} plan it", "Laisse {name} planifier")
    val helpDraftChip = s("help_draft_chip", "Draft with {name}", "Laisse {name} l'écrire")
    val helpPlanInstead = s("help_plan_instead", "{name} plans it instead.", "{name} le planifie à la place.")
    val helpDraftInstead = s("help_draft_instead", "{name} drafts it instead.", "{name} en écrit le brouillon à la place.")
    val helpPlanOffer = s("help_plan_offer", "I couldn't make a plan for that on the phone. {name} can try.",
        "Je n'ai pas pu faire de plan pour ça sur le téléphone. {name} peut essayer.")
    val helpDraftOffer = s("help_draft_offer", "I couldn't draft that skill on the phone. {name} can try.",
        "Je n'ai pas pu écrire cette compétence sur le téléphone. {name} peut essayer.")
    val helpTitle = s("help_title", "What would go to {name}", "Ce qui irait à {name}")
    val helpTap = s("help_tap", "Nothing goes until you tap {send}.", "Rien ne part tant que tu ne touches pas {send}.")
    val helpSay = s("help_say", "Here's exactly what would go to {name}. Tap {send} to send it.",
        "Voici exactement ce qui irait à {name}. Touche {send} pour l'envoyer.")
    val helpCost = s("help_cost", "{cost} · today: {spent} of {budget} on helpers", "{cost} · aujourd'hui : {spent} sur {budget} pour les IA externes")
    val helpCostNoBudget = s("help_cost_no_budget", "{cost} · no daily budget for helpers", "{cost} · pas de budget quotidien pour les IA externes")
    val helpFree = s("help_free", "No cost: your own server", "Sans frais : ton propre serveur")
    val helpNotSent = s("help_not_sent", "Nothing went to {name}.", "Rien n'est allé à {name}.")
    val helpBudget = s("help_budget", "Today's budget for helpers is used up, so nothing went to {name}.",
        "Le budget du jour pour les IA externes est épuisé, alors rien n'est allé à {name}.")
    val helpAsking = s("help_asking", "Asking {name}…", "Je demande à {name}…")
    val helpParkTitle = s("help_park_title", "Waiting until you park", "En attendant que tu sois stationné")
    val helpPark = s("help_park", "Car mode is on: I'll show what would go to {name} once you're parked.",
        "Le mode auto est actif : je te montrerai ce qui irait à {name} quand tu seras stationné.")
    val helpPlanFailed = s("help_plan_failed", "{name}'s plan didn't check out, so nothing ran.", "Le plan de {name} n'était pas valide, alors rien ne s'est fait.")
    val helpDraftFailed = s("help_draft_failed", "{name}'s draft didn't check out, so nothing was saved.",
        "Le brouillon de {name} n'était pas valide, alors rien n'a été enregistré.")
}
