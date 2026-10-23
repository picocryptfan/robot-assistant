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
}
