// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

// Pure. The words Robot hears for skills (plan §5), English and Québec French, written as RoutineRouter compares them:
// no accents, no case, apostrophes and hyphens as spaces ("qu'est-ce que" is "qu est ce que"). « skill » is heard in place
// of « compétence ». Words you say, never shown.

object SkillWords {
    /** Where the name goes in [NAME_FORMS]. */
    const val NAME_SLOT = "<name>"

    /** Every form that names a skill (D15): checked against Robot's other phrases before a name is taken. */
    val NAME_FORMS = listOf("<name>", "run <name>", "do <name>", "start <name>", "lance <name>", "fais <name>", "part <name>",
        "<name> for 5 minutes", "<name> saying hi", "<name> pour 5 minutes", "<name> qui dit salut",
        "what does <name> do", "qu'est-ce que fait <name>", "delete the skill <name>", "efface la competence <name>")

    /** Allowed in front of any skill phrase. */
    val FILLER = listOf("hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "he robot", "dis robot", "robot", "please",
        "can you", "could you", "would you", "will you", "peux tu", "pourrais tu", "est ce que tu peux", "s il te plait", "s il vous plait",
        "ok", "okay", "alors", "um", "uh", "euh", "hey", ",")

    /** Allowed after a whole-utterance command. */
    val TAIL = listOf("please", "now", "robot", "s il te plait", "s il vous plait", "maintenant", ",")

    /** A skill, said: « compétence » or « skill ». */
    val SKILL = listOf("skill", "skills", "competence", "competences")

    // Teaching (plan §5): the words before the name. "When I say <name>, <steps>" needs a step that parses.
    val TEACH_EN = listOf("teach a skill called", "teach a skill named", "teach robot a skill called", "teach robot a skill named",
        "teach you a skill called", "teach you a skill named", "teach me a skill called", "learn a skill called", "learn a skill named",
        "new skill called", "new skill named", "new skill", "make a skill called", "make a skill named", "create a skill called",
        "create a skill named", "add a skill called", "add a skill named")
    val TEACH_FR = listOf("apprends une competence qui s appelle", "apprends une competence appelee", "apprends une competence nommee",
        "apprends une competence", "apprends une skill qui s appelle", "apprends un skill qui s appelle", "apprends une skill appelee",
        "apprends un skill appele", "nouvelle competence qui s appelle", "nouvelle competence appelee", "nouvelle competence",
        "nouvelle skill", "nouveau skill", "cree une competence qui s appelle", "cree une competence appelee", "cree une competence",
        "ajoute une competence qui s appelle", "ajoute une competence")
    val WHEN_I_SAY_EN = listOf("when i say", "whenever i say", "if i say")
    val WHEN_I_SAY_FR = listOf("quand je dis", "lorsque je dis", "si je dis")

    // "Save that as a skill" (plan §5, D12), with or without a name after it.
    val SAVE_EN = listOf("save that as a skill called", "save that as a skill named", "save that as a skill", "save this as a skill called",
        "save this as a skill", "save those as a skill called", "save those as a skill", "save these as a skill called", "save these as a skill",
        "save it as a skill called", "save it as a skill", "make that a skill called", "make that a skill named", "make that a skill",
        "make this a skill called", "make this a skill", "make those a skill called", "make those a skill", "make that into a skill called",
        "make that into a skill", "turn that into a skill called", "turn that into a skill")
    val SAVE_FR = listOf("enregistre ca comme competence", "enregistre ca comme une competence", "enregistre ca comme skill",
        "enregistre ca en competence", "garde ca comme competence", "garde ca comme une competence", "garde ca comme skill",
        "fais en une competence", "fais en une skill", "fais en un skill", "fais de ca une competence")
    /** Between the save words and a name: "called", « qui s'appelle ». */
    val CALLED = listOf("called", "named", "qui s appelle", "appelee", "nommee", "appele", "nomme")

    // Running (D10): a verb, then the name.
    val RUN_EN = listOf("run the skill", "do the skill", "start the skill", "run my skill", "start my skill", "run", "do", "start")
    val RUN_FR = listOf("lance la competence", "fais la competence", "part la competence", "lance ma competence", "lance la skill",
        "lance le skill", "lance", "fais", "part")

    // Asking about skills.
    val LIST = listOf("what skills do i have", "which skills do i have", "list my skills", "list skills", "list the skills", "show my skills",
        "show me my skills", "what are my skills", "tell me my skills", "my skills", "c est quoi mes competences", "c est quoi mes skills",
        "montre mes competences", "montre moi mes competences", "quelles competences j ai", "quelles competences est ce que j ai",
        "quelles sont mes competences", "liste mes competences", "mes competences")
    val ABOUT_LEAD = listOf("what does the skill", "what does my skill", "what does", "tell me about the skill", "what is the skill",
        "qu est ce que fait la competence", "qu est ce que fait", "que fait la competence", "que fait", "c est quoi la competence")
    val ABOUT_TAIL = listOf("do", "does")
    /** « qu'est-ce que <nom> fait ». */
    val ABOUT_FR_LEAD = listOf("qu est ce que la competence", "qu est ce que")
    val ABOUT_FR_TAIL = listOf("fait")

    // Deleting one (always a tap on its card).
    val DELETE = listOf("delete the skill", "delete my skill", "remove the skill", "remove my skill", "erase the skill", "forget the skill",
        "efface la competence", "efface ma competence", "supprime la competence", "supprime ma competence", "enleve la competence",
        "efface la skill", "supprime la skill", "efface le skill", "supprime le skill")

    /** The French leads, for the reply's language. */
    val FRENCH_LEAD = TEACH_FR + WHEN_I_SAY_FR + SAVE_FR + RUN_FR + DELETE.filter { it.startsWith("e") || it.startsWith("s") } +
        listOf("c est quoi", "montre", "quelles", "liste", "mes competences", "qu est ce que", "que fait")

    // Values after the name (plan §7.2, D11).
    /** Before a duration or a place: "pasta for 8 minutes", « pâtes pour 8 minutes ». */
    val FOR = listOf("for", "pour", "during", "pendant")
    /** Before a time. */
    val AT = listOf("at", "a", "vers", "around")
    /** Before a person. */
    val TO = listOf("to", "a", "au", "with", "avec")
    /** Before an app. */
    val APP_LEAD = listOf("with", "in", "on", "avec", "dans", "sur")
    /** Before a place. */
    val PLACE_LEAD = listOf("to", "at", "in", "near", "a", "au", "aux", "vers", "pres de")
    /** D11: before a text question's words, and only then. */
    val SAYING = listOf("that says", "saying", "to say", "say", "qui dit", "pour dire", "que")
    val UNITS = mapOf("second" to 1L, "seconds" to 1L, "sec" to 1L, "secs" to 1L, "seconde" to 1L, "secondes" to 1L, "minute" to 60L,
        "minutes" to 60L, "min" to 60L, "mins" to 60L, "hour" to 3600L, "hours" to 3600L, "heure" to 3600L, "heures" to 3600L, "h" to 3600L)
    val NUMBERS = mapOf("a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
        "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "fifteen" to 15L, "twenty" to 20L, "thirty" to 30L,
        "forty" to 40L, "forty five" to 45L, "sixty" to 60L, "ninety" to 90L, "un" to 1L, "une" to 1L, "deux" to 2L, "trois" to 3L,
        "quatre" to 4L, "cinq" to 5L, "six" to 6L, "sept" to 7L, "huit" to 8L, "neuf" to 9L, "dix" to 10L, "onze" to 11L, "douze" to 12L,
        "quinze" to 15L, "vingt" to 20L, "trente" to 30L, "quarante" to 40L, "quarante cinq" to 45L, "soixante" to 60L)
    /** Words around a value that change nothing: "pasta for 8 minutes please". */
    val VALUE_FILLER = listOf("please", "now", "s il te plait", "maintenant", "the", "my", "le", "la", "l", "mon", "ma", ",")
}
