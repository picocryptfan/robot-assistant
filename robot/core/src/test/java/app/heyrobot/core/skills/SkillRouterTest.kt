// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.lock.LockRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Plan §5: every skill phrase in English and Québec French, and the misfires it must never take. */
class SkillRouterTest {
    private val home = SkillFixtures.headingHome()
    private val pasta = SkillFixtures.pasta()
    private val skills = listOf(home, pasta)
    private val now = ZonedDateTime.parse("2026-10-19T10:00:00-04:00[America/Toronto]")

    private fun route(t: String) = SkillRouter.route(t, skills, people = listOf("Marie", "Sam"))

    /** Fix 1.1: a skill with a later Robot's Spanish and German names runs here by its English and French ones only. */
    @Test fun moreLanguagesRunInEnglishAndFrenchOnly() {
        val more = SkillFixtures.headingHomeMore()
        val list = listOf(more, pasta)
        assertEquals(more, (SkillRouter.route("heading home", list) as SkillCommand.Run).skill)
        val fr = SkillRouter.route("je rentre", list) as SkillCommand.Run
        assertEquals(more, fr.skill)
        assertEquals(Lang.FR, SkillRouter.lang("je rentre", fr))
        assertNull(SkillRouter.route("voy a casa", list))
        assertNull(SkillRouter.route("ich fahre heim", list))
    }

    @Test fun teach() {
        val steps = "text Sam on my way home, then open the route home"
        for (lead in listOf("", "hey robot, ", "please ", "Robot, can you "))
            assertEquals(lead, SkillCommand.Teach("heading home", steps, Lang.EN), route("${lead}teach a skill called heading home: $steps"))
        assertEquals(SkillCommand.Teach("heading home", steps, Lang.EN), route("Teach Robot a skill called heading home: $steps"))
        assertEquals(SkillCommand.Teach("pasta", "set a timer for 12 minutes", Lang.EN), route("new skill pasta: set a timer for 12 minutes"))
        // D9: no colon from speech to text; the name ends where the first step parses.
        assertEquals(SkillCommand.Teach("heading home", "text Sam on my way home then open the route home", Lang.EN),
            route("teach a skill called heading home text Sam on my way home then open the route home"))
        assertEquals(SkillCommand.Teach("good night", "turn off the flashlight", Lang.EN, whenISay = true), route("when I say good night, turn off the flashlight"))
        assertEquals(SkillCommand.Teach("good night", "turn off the flashlight", Lang.EN, whenISay = true), route("when I say good night turn off the flashlight"))
        // D8: only a name; Robot asks for the steps.
        assertEquals(SkillCommand.Teach("heading home", null, Lang.EN), route("teach a skill called heading home"))
        assertEquals(SkillCommand.Teach("heading home", null, Lang.EN), route("Teach a skill called heading home."))
    }

    @Test fun teachInFrench() {
        val fr = "texte à Sam que je m'en viens, pis ouvre l'itinéraire vers la maison"
        assertEquals(SkillCommand.Teach("je rentre", fr, Lang.FR), route("apprends une compétence qui s'appelle je rentre : $fr"))
        assertEquals(SkillCommand.Teach("je rentre", fr, Lang.FR), route("Apprends une skill qui s'appelle je rentre : $fr"))
        assertEquals(SkillCommand.Teach("pâtes", "mets une minuterie de 12 minutes", Lang.FR), route("nouvelle compétence pâtes : mets une minuterie de 12 minutes"))
        assertEquals(SkillCommand.Teach("bonne nuit", "éteins la lampe de poche", Lang.FR, whenISay = true), route("quand je dis bonne nuit, éteins la lampe de poche"))
        assertEquals(SkillCommand.Teach("je rentre", "texte à Sam que je m'en viens pis ouvre l'itinéraire vers la maison", Lang.FR),
            route("apprends une compétence qui s'appelle je rentre texte à Sam que je m'en viens pis ouvre l'itinéraire vers la maison"))
        assertEquals(SkillCommand.Teach("je rentre", null, Lang.FR), route("apprends une compétence qui s'appelle je rentre"))
    }

    @Test fun saveThat() {
        assertEquals(SkillCommand.SaveThat(null, Lang.EN), route("save that as a skill"))
        assertEquals(SkillCommand.SaveThat(null, Lang.EN), route("Hey robot, save those as a skill please"))
        assertEquals(SkillCommand.SaveThat("pasta", Lang.EN), route("save that as a skill called pasta"))
        assertEquals(SkillCommand.SaveThat("pasta", Lang.EN), route("make that a skill called pasta"))
        assertEquals(SkillCommand.SaveThat(null, Lang.EN), route("make that a skill"))
        assertEquals(SkillCommand.SaveThat(null, Lang.FR), route("enregistre ça comme compétence"))
        assertEquals(SkillCommand.SaveThat("pâtes", Lang.FR), route("garde ça comme compétence pâtes"))
        assertEquals(SkillCommand.SaveThat(null, Lang.FR), route("fais-en une compétence"))
        // "Save" needs "skill" or « compétence ».
        assertNull(route("save that"))
        assertNull(route("save that note"))
    }

    @Test fun run() {
        assertEquals(SkillCommand.Run(home, emptyMap(), Lang.EN), route("heading home"))
        assertEquals(SkillCommand.Run(home, emptyMap(), Lang.EN), route("Hey robot, heading home please"))
        assertEquals(SkillCommand.Run(home, emptyMap(), Lang.EN), route("on my way home"))
        assertEquals(SkillCommand.Run(home, emptyMap(), Lang.FR), route("je rentre"))
        for (t in listOf("run heading home", "do heading home", "start heading home", "run the skill heading home"))
            assertEquals(t, SkillCommand.Run(home, emptyMap(), Lang.EN), route(t))
        for (t in listOf("lance je rentre", "fais je rentre", "part je rentre", "lance la compétence je rentre"))
            assertEquals(t, SkillCommand.Run(home, emptyMap(), Lang.FR), route(t))
        // Values after the name (D10).
        assertEquals(SkillCommand.Run(pasta, mapOf("length" to "480"), Lang.EN), route("pasta for 8 minutes"))
        assertEquals(SkillCommand.Run(pasta, mapOf("length" to "480"), Lang.FR), route("lance pasta pour 8 minutes"))
        assertEquals(SkillCommand.Run(home, mapOf("who" to "Marie"), Lang.EN), route("heading home to Marie"))
        // Words that aren't all values: ordinary talk in phase A.
        assertNull(route("pasta for a while"))
        assertNull(route("pasta is my favourite food"))
        assertNull(route("run to the store"))
    }

    @Test fun askAboutListDelete() {
        assertEquals(SkillCommand.About(home, "heading home", Lang.EN), route("what does heading home do?"))
        assertEquals(SkillCommand.About(home, "heading home", Lang.EN), route("What does the skill heading home do"))
        assertEquals(SkillCommand.About(home, "je rentre", Lang.FR), route("qu'est-ce que fait je rentre?"))
        assertEquals(SkillCommand.About(home, "je rentre", Lang.FR), route("qu'est-ce que je rentre fait"))
        assertEquals(SkillCommand.About(null, "fox", Lang.EN), route("what does the skill fox do"))
        assertNull(route("what does the fox do"))
        assertNull(route("what does a router do"))
        for (t in listOf("what skills do I have?", "list my skills", "Hey robot, show my skills"))
            assertEquals(t, SkillCommand.ListAll(Lang.EN), route(t))
        for (t in listOf("c'est quoi mes compétences?", "montre mes compétences", "quelles compétences j'ai?"))
            assertEquals(t, SkillCommand.ListAll(Lang.FR), route(t))
        assertEquals(SkillCommand.Delete(home, "heading home", Lang.EN), route("delete the skill heading home"))
        assertEquals(SkillCommand.Delete(pasta, "pasta", Lang.EN), route("remove the skill pasta"))
        assertEquals(SkillCommand.Delete(home, "je rentre", Lang.FR), route("efface la compétence je rentre"))
        assertEquals(SkillCommand.Delete(null, "pâtes", Lang.FR), route("supprime la compétence pâtes"))
    }

    /** D8: the steps (or a name) said after a pause may start with the wake phrase or "please": they are read without it. */
    @Test fun wordsAfterAPauseLoseTheirLead() {
        assertEquals("text Sam on my way home, then open the route home", SkillRouter.withoutLead("hey robot, text Sam on my way home, then open the route home"))
        assertEquals("Pasta", SkillRouter.withoutLead("please Pasta"))
        assertEquals("", SkillRouter.withoutLead("hey robot"))
        val d = SkillTeach.teach("heading home", SkillRouter.withoutLead("Hey robot, text Sam on my way home, then open the route home"), Lang.EN, "s-new123", 1L, false)
        assertEquals(listOf(StepKind.SEND_TEXT, StepKind.MAPS_NAVIGATE), d.steps.map { it.kind })
    }

    @Test fun theReplysLanguage() {
        assertEquals(Lang.FR, SkillRouter.lang("lance je rentre", route("lance je rentre")!!))
        assertEquals(Lang.FR, SkillRouter.lang("efface la compétence je rentre", route("efface la compétence je rentre")!!))
        assertEquals(Lang.EN, SkillRouter.lang("teach a skill called x", SkillCommand.Teach("x", null, Lang.EN)))
    }

    /** §5's misfire table: none of these is a skill phrase, with skills or without. */
    @Test fun misfires() {
        val never = listOf("teach me Spanish", "when I say so", "when I said that", "pis, ça va?", "run to the store", "save that note",
            "remember that Sam likes tea", "turn off the flashlight", "start car mode", "start a timer", "set a timer for 5 minutes",
            "lock the phone for 10 minutes", "end the lock", "run my evening routine", "delete my evening routine", "every weekday at 7, tell me the weather",
            "ask Jarvis what time it is", "go get Claude", "thanks Jarvis", "read my messages", "report a bug", "search the web for cats", "free up memory",
            "what's the weather", "text Sam I'm on my way", "open the camera", "what does that mean", "list my routines", "brief me",
            "quand je dis bonjour", "apprends-moi l'espagnol", "enregistre ça", "lance ma routine du soir", "barre le téléphone pour 10 minutes")
        for (t in never) assertNull(t, route(t))
    }

    /** RoutineRouter, LockRouter and HelperRouter don't take a skill phrase either: the panel's order holds both ways. */
    @Test fun theOtherRoutersLeaveSkillPhrasesAlone() {
        val phrases = listOf("teach a skill called heading home: text Sam on my way home, then open the route home", "save that as a skill",
            "what skills do I have", "delete the skill heading home", "what does heading home do", "heading home", "pasta for 8 minutes",
            "apprends une compétence qui s'appelle je rentre : texte à Sam que je m'en viens", "enregistre ça comme compétence",
            "c'est quoi mes compétences", "efface la compétence je rentre", "lance je rentre")
        val jarvis = HelperName("h-jarvis", listOf(listOf("jarvis")))
        for (t in phrases) {
            assertNull(t, RoutineRouter.route(t, now, listOf("Evening routine")))
            assertNull(t, LockRouter.route(t, now, listOf("Bedtime")))
            assertNull(t, HelperRouter.route(t, listOf(HelperName.CLAUDE, jarvis)))
        }
        // And the routines' phrases are never skills'.
        for (t in listOf("run my evening routine", "every weekday at 7, tell me the weather and my calendar", "when I turn off my alarm, read my brief",
                "delete my evening routine", "list my routines", "remind me in the morning to bring the passport", "brief me", "lance ma routine du soir",
                "chaque matin de semaine à 7 h, dis-moi la météo")) assertNull(t, route(t))
        assertTrue(true)
    }
}
