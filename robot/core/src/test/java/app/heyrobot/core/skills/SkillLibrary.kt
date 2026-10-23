// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.ToolCalls

/**
 * Phase B's labelled set (plan §11, SkillSelectTest): 20 skills someone might teach, and 200 things they might say, in
 * English and Québec French. Each line names the skill it is about, or none: a request no skill is for. Written before
 * the threshold was set, and kept as written: a line that fails is a miss, never edited to pass.
 */
object SkillLibrary {
    private var n = 0

    /** A step with fixed values, as a saved skill holds it (the tool's own reading, RoutineCodec.args). */
    fun fixed(kind: StepKind, args: Map<String, Any?>): SkillStep {
        val a = (ToolCalls.parse(RawToolCall(kind.tool!!.id, args)) as ParsedCall.Ok).action
        return SkillStep.of(kind, RoutineCodec.args(a))
    }

    fun step(kind: StepKind, args: Map<String, Any?>): SkillStep = SkillStep.of(kind, args)

    private fun skill(key: String, name: String, en: List<String>, fr: List<String>, params: List<SkillParam>, vararg steps: SkillStep,
        level: SkillLevel = SkillLevel.ASK_FIRST): Skill {
        n++
        return Skill("s-lib%03d".format(n), 1, name, en, fr, params, steps.toList(), level,
            SkillProvenance(SkillMade.VOICE, false, 1_760_800_000_000L, 1_760_800_000_000L))
    }

    private fun p(name: String, type: ParamType, default: String, en: String, fr: String) = SkillParam(name, type, default, en, fr)

    /** The 20 skills, by a short key the lines use. */
    val skills: Map<String, Skill> by lazy {
        n = 0
        linkedMapOf(
            "home" to skill("home", "Heading home", listOf("heading home", "on my way home"), listOf("je rentre"),
                listOf(p("who", ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
                step(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "On my way home.")),
                fixed(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))),
            "pasta" to skill("pasta", "Pasta", listOf("pasta"), listOf("pâtes"),
                listOf(p("length", ParamType.DURATION, "720", "How long?", "Combien de temps?")),
                step(StepKind.SET_TIMER, mapOf("seconds" to "{{length}}")),
                fixed(StepKind.SEND_TEXT, mapOf("to" to "Marie", "message" to "pasta's on"))),
            "team" to skill("team", "Team reminder", listOf("team reminder"), listOf("rappel à l'équipe"),
                listOf(p("message", ParamType.TEXT, "Standup at 9", "What should it say?", "Ça dit quoi?")),
                step(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{message}}"))),
            "night" to skill("night", "Good night", listOf("good night", "bedtime"), listOf("bonne nuit"), emptyList(),
                fixed(StepKind.SET_ALARM, mapOf("hour" to 7, "minute" to 0)),
                fixed(StepKind.FLASHLIGHT, mapOf("on" to false)),
                step(StepKind.SAY, mapOf("text" to "Sleep well."))),
            "morning" to skill("morning", "My morning", listOf("my morning", "start my day"), listOf("mon matin"), emptyList(),
                fixed(StepKind.WEATHER, mapOf("day" to "today")),
                fixed(StepKind.READ_CALENDAR, mapOf("day" to "today"))),
            "tea" to skill("tea", "Tea time", listOf("tea time", "steep the tea"), listOf("thé"), emptyList(),
                fixed(StepKind.SET_TIMER, mapOf("seconds" to 240))),
            "coffee" to skill("coffee", "Coffee run", listOf("coffee run"), listOf("café"),
                listOf(p("spot", ParamType.PLACE, "coffee", "Which coffee shop?", "Quel café?")),
                step(StepKind.MAPS_SEARCH, mapOf("query" to "{{spot}}"))),
            "workout" to skill("workout", "Workout", listOf("workout", "gym time"), listOf("entraînement"), emptyList(),
                fixed(StepKind.SET_TIMER, mapOf("seconds" to 1800)),
                fixed(StepKind.OPEN_APP, mapOf("name" to "Music"))),
            "parking" to skill("parking", "Parking meter", listOf("parking meter", "parked the car"), listOf("parcomètre"),
                listOf(p("length", ParamType.DURATION, "3600", "How long?", "Combien de temps?")),
                step(StepKind.SET_TIMER, mapOf("seconds" to "{{length}}"))),
            "dinner" to skill("dinner", "Dinner's ready", listOf("dinner's ready", "dinner is ready"), listOf("le souper est prêt"),
                listOf(p("who", ParamType.PERSON, "Alex", "Who should I tell?", "À qui je le dis?")),
                step(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "Dinner's ready, come eat."))),
            "late" to skill("late", "Running late", listOf("running late"), listOf("je suis en retard"),
                listOf(p("who", ParamType.PERSON, "Jordan", "Who should I tell?", "À qui je le dis?"),
                    p("message", ParamType.TEXT, "Running 10 minutes late.", "What should it say?", "Ça dit quoi?")),
                step(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "{{message}}")),
                fixed(StepKind.MAPS_NAVIGATE, mapOf("destination" to "work"))),
            "groceries" to skill("groceries", "Grocery list", listOf("grocery list", "shopping list"), listOf("liste d'épicerie"), emptyList(),
                fixed(StepKind.FIND_NOTES, mapOf("query" to "groceries"))),
            "battery" to skill("battery", "Battery check", listOf("battery check"), listOf("vérif batterie"), emptyList(),
                fixed(StepKind.PHONE_STATUS, mapOf("about" to "battery"))),
            "weekend" to skill("weekend", "Weekend weather", listOf("weekend weather"), listOf("météo du weekend"), emptyList(),
                fixed(StepKind.WEATHER, mapOf("day" to "Saturday"))),
            "laundry" to skill("laundry", "Laundry", listOf("laundry", "the wash"), listOf("lavage", "brassée"), emptyList(),
                fixed(StepKind.SET_TIMER, mapOf("seconds" to 2700))),
            "meditate" to skill("meditate", "Meditation", listOf("meditation", "meditate"), listOf("méditation"), emptyList(),
                fixed(StepKind.SET_TIMER, mapOf("seconds" to 600)),
                step(StepKind.SAY, mapOf("text" to "Breathe in, breathe out."))),
            "status" to skill("status", "Status update", listOf("status update"), listOf("suivi au boss"),
                listOf(p("body", ParamType.TEXT, "All on track.", "What should it say?", "Ça dit quoi?")),
                step(StepKind.DRAFT_EMAIL, mapOf("to" to "Jordan", "subject" to "Status", "body" to "{{body}}"))),
            "school" to skill("school", "School pickup", listOf("school pickup", "pick up the kids"), listOf("chercher les enfants"), emptyList(),
                fixed(StepKind.SET_ALARM, mapOf("hour" to 15, "minute" to 0)),
                fixed(StepKind.MAPS_NAVIGATE, mapOf("destination" to "Lakeview School"))),
            "photo" to skill("photo", "Quick photo", listOf("quick photo", "snap a photo"), listOf("photo rapide"), emptyList(),
                fixed(StepKind.OPEN_APP, mapOf("name" to "Camera"))),
            "agenda" to skill("agenda", "My day", listOf("my day", "what's on today"), listOf("ma journée"), emptyList(),
                fixed(StepKind.READ_CALENDAR, mapOf("day" to "today"))),
        )
    }

    /**
     * 200 lines: what was said, and the key of the skill it is for (null: none is). 140 about a skill, 7 for each (EN and
     * FR, its name or not), 60 about nothing you taught.
     */
    val lines: List<Pair<String, String?>> = listOf(
        // Heading home.
        "I'm heading home now" to "home", "on my way home, tell Sam" to "home", "heading home, let Alex know" to "home",
        "I'm going home" to "home", "home time, tell Sam I'm on my way" to "home", "je rentre à la maison" to "home",
        "je rentre, avertis Sam" to "home",
        // Pasta.
        "pasta for 8 minutes" to "pasta", "start the pasta" to "pasta", "the pasta is in the pot" to "pasta",
        "pasta timer please" to "pasta", "I'm making pasta tonight" to "pasta", "les pâtes pour 9 minutes" to "pasta",
        "je fais des pâtes" to "pasta",
        // Team reminder.
        "team reminder, the standup moved to 10" to "team", "remind the team the meeting is at 3" to "team",
        "send the team reminder" to "team", "tell the team standup moved" to "team", "team, lunch is on me today" to "team",
        "rappel à l'équipe que la réunion est à 10 h" to "team", "dis à l'équipe que le standup est déplacé" to "team",
        // Good night.
        "good night robot" to "night", "it's bedtime" to "night", "night night" to "night", "I'm going to bed, good night" to "night",
        "bedtime routine please" to "night", "bonne nuit" to "night", "bonne nuit Robot, je vais me coucher" to "night",
        // My morning.
        "start my day" to "morning", "my morning please" to "morning", "good morning, start my day" to "morning",
        "how does my morning look" to "morning", "run my morning" to "morning", "mon matin s'il te plaît" to "morning",
        "commence mon matin" to "morning",
        // Tea time.
        "tea time" to "tea", "steep the tea" to "tea", "I'm making tea" to "tea", "time for a cup of tea" to "tea",
        "tea please" to "tea", "c'est l'heure du thé" to "tea", "je me fais un thé" to "tea",
        // Coffee run.
        "coffee run" to "coffee", "coffee run to the Second Cup" to "coffee", "I need a coffee run" to "coffee",
        "where can I get coffee nearby" to "coffee", "find me a coffee shop" to "coffee", "un café près d'ici" to "coffee",
        "on va chercher un café" to "coffee",
        // Workout.
        "workout time" to "workout", "start my workout" to "workout", "gym time" to "workout", "I'm at the gym" to "workout",
        "let's do the workout" to "workout", "mon entraînement" to "workout", "je commence mon entraînement" to "workout",
        // Parking meter.
        "parking meter for 2 hours" to "parking", "I just parked the car" to "parking", "start the parking meter" to "parking",
        "parked the car for 90 minutes" to "parking", "the parking is paid for an hour" to "parking", "parcomètre pour une heure" to "parking",
        "j'ai mis de l'argent dans le parcomètre" to "parking",
        // Dinner's ready.
        "dinner's ready" to "dinner", "dinner is ready, tell Alex" to "dinner", "tell the kids dinner is ready" to "dinner",
        "let everyone know dinner's ready" to "dinner", "dinner time" to "dinner", "le souper est prêt" to "dinner",
        "dis à Alex que le souper est prêt" to "dinner",
        // Running late.
        "I'm running late" to "late", "running late, tell Jordan" to "late", "running late to work" to "late",
        "tell my boss I'm running late" to "late", "I'll be late for work" to "late", "je suis en retard" to "late",
        "je suis en retard au travail" to "late",
        // Grocery list.
        "grocery list" to "groceries", "what's on my grocery list" to "groceries", "show me the shopping list" to "groceries",
        "read my shopping list" to "groceries", "what do I need from the grocery store" to "groceries",
        "ma liste d'épicerie" to "groceries", "c'est quoi sur la liste d'épicerie" to "groceries",
        // Battery check.
        "battery check" to "battery", "check my battery" to "battery", "how much battery is left" to "battery",
        "is the battery low" to "battery", "battery level please" to "battery", "vérif batterie" to "battery",
        "il me reste combien de batterie" to "battery",
        // Weekend weather.
        "weekend weather" to "weekend", "what's the weather this weekend" to "weekend", "will it rain on the weekend" to "weekend",
        "weather for Saturday" to "weekend", "weekend forecast please" to "weekend", "la météo du weekend" to "weekend",
        "quel temps pour le weekend" to "weekend",
        // Laundry.
        "laundry" to "laundry", "the laundry is in" to "laundry", "start the laundry timer" to "laundry",
        "I put the wash on" to "laundry", "laundry's going" to "laundry", "le lavage est parti" to "laundry",
        "j'ai parti une brassée" to "laundry",
        // Meditation.
        "meditation" to "meditate", "let's meditate" to "meditate", "time to meditate for a bit" to "meditate",
        "start my meditation" to "meditate", "I want to meditate" to "meditate", "méditation s'il te plaît" to "meditate",
        "je veux faire ma méditation" to "meditate",
        // Status update.
        "status update" to "status", "status update, all on track this week" to "status", "send Jordan a status update" to "status",
        "email my status update" to "status", "write the weekly status update" to "status", "suivi au boss" to "status",
        "fais le suivi au boss" to "status",
        // School pickup.
        "school pickup" to "school", "time to pick up the kids" to "school", "pick up the kids from school" to "school",
        "school pickup reminder" to "school", "I need to get the kids at school" to "school", "chercher les enfants à l'école" to "school",
        "faut aller chercher les enfants" to "school",
        // Quick photo.
        "quick photo" to "photo", "snap a photo" to "photo", "I want to take a photo" to "photo", "photo please" to "photo",
        "quick, take a photo" to "photo", "photo rapide" to "photo", "prends une photo rapide" to "photo",
        // My day.
        "my day" to "agenda", "what's on today" to "agenda", "how's my day looking" to "agenda", "tell me about my day" to "agenda",
        "what's on my agenda today" to "agenda", "ma journée" to "agenda", "c'est quoi ma journée" to "agenda",

        // 60 requests no skill is for.
        "What's the capital of Peru?" to null, "tell me a joke" to null, "how tall is Mount Everest" to null,
        "who won the hockey game last night" to null, "what is 15 percent of 80" to null, "translate hello into Spanish" to null,
        "how do I boil an egg" to null, "what's a good name for a cat" to null, "explain photosynthesis" to null,
        "who wrote Les Misérables" to null, "set a timer for 5 minutes" to null, "set an alarm for 6 30" to null,
        "turn on the flashlight" to null, "open Signal" to null, "text Marie I love you" to null, "email Alex about Friday" to null,
        "what's the weather tomorrow" to null, "read my calendar for Friday" to null, "find my notes about the trip" to null,
        "how much storage do I have" to null, "remember that my locker is 42" to null, "what do you know about me" to null,
        "directions to the library" to null, "search maps for a pharmacy" to null, "add a note to buy batteries" to null,
        "is it going to snow" to null, "call me Captain" to null, "what time is it" to null, "how many days until Christmas" to null,
        "sing me a song" to null, "what's the meaning of life" to null, "convert 10 miles to kilometres" to null,
        "who is the prime minister" to null, "spell necessary" to null, "what rhymes with orange" to null,
        "how do you say thank you in Japanese" to null, "write a poem about the sea" to null, "why is the sky blue" to null,
        "what's the square root of 144" to null, "recommend a good book" to null,
        "c'est quoi la capitale du Pérou" to null, "raconte-moi une blague" to null, "mets une minuterie de 5 minutes" to null,
        "allume la lampe de poche" to null, "ouvre Signal" to null, "écris à Marie que je l'aime" to null,
        "quel temps fera-t-il demain" to null, "lis mon agenda de vendredi" to null, "trouve mes notes sur le voyage" to null,
        "combien d'espace il me reste" to null, "souviens-toi que mon casier est le 42" to null, "itinéraire vers la bibliothèque" to null,
        "pourquoi le ciel est bleu" to null, "quelle heure est-il" to null, "traduis bonjour en anglais" to null,
        "combien font 12 fois 8" to null, "qui a écrit Maria Chapdelaine" to null, "comment faire cuire un œuf" to null,
        "écris un poème sur l'hiver" to null, "donne-moi une idée de souper" to null,
    )
}
