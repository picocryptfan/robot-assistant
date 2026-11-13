// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.wiki.WikiRoute.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiRouteTest {
    @Test
    fun p0TableRoutesFactsAndNothingElse() {
        val wrong = WikiP0.rows.mapNotNull { r ->
            val v = WikiRoute.check(r.question)
            val want = when (r.kind) { "fact" -> Verdict.FACT; "personal" -> Verdict.PERSONAL; "fresh" -> Verdict.FRESH; else -> Verdict.COMMAND }
            "${r.id} ${r.question}: $v, not $want".takeIf { v != want }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun p0TableSubjects() {
        val wrong = WikiP0.rows.filter { it.subject != null }.mapNotNull { r ->
            val s = WikiRoute.subject(r.question)
            "${r.id} ${r.question}: \"$s\"".takeIf { s != r.subject }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun languageOfEachP0Question() {
        WikiP0.rows.filter { it.kind == "fact" }.forEach { assertEquals(it.question, it.lang, WikiRoute.lang(it.question)) }
    }

    /**
     * wiki-fix-stale-card (wiki-p5-measure-b x51 to x53, phone-1.4.0-rc3 item 6): "Which ..." is a question of fact as
     * "What ..." is, and routes the same way; « Quel(le)(s) ... » already did. The must-not rows (P0's 25 to 30, 47 to 50)
     * stay out (p0TableRoutesFactsAndNothingElse runs all 50), and "which" with your own or fresh words stays out too.
     */
    @Test
    fun whichRoutesAsWhat() {
        listOf("Which planet is the largest?", "Which river flows through Paris?", "Which country did Napoleon rule?",
            "Which province joined Canada in 1949?", "Hey robot, which planet is the largest?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
        assertEquals("province joined Canada in 1949", WikiRoute.subject("Which province joined Canada in 1949?"))
        listOf("Quelle est la capitale du Canada?", "Quelle planète est la plus grosse?", "Quel explorateur a fondé Québec?",
            "Quels sont les Grands Lacs?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
        // As "What ...": the same verdict for each, your own things, fresh ones and follow-ups included.
        listOf("%s of my alarms is set?", "%s is my wife's birthday?", "%s team won last night?", "%s of these is better?",
            "%s one is it?", "%s province joined Canada in 1949?", "%s day is it today?", "%s did Alex say?",
        ).forEach { f ->
            val which = f.format("Which")
            assertEquals(which, WikiRoute.check(f.format("What")), WikiRoute.check(which))
        }
        listOf("Which of my alarms is set?", "Which team won last night?", "Which one is it?").forEach { assertFalse(it, WikiRoute.goes(it)) }

        // H1 (review-wiki-which-pick): one of the phone's things right after the lead is yours, in English and French.
        listOf("Which app uses the most battery?", "Which app is taking up the most space?", "Which note has the gate code?",
            "Which alarm goes off first?", "What app uses the most battery?", "Which apps can use the camera?", "What list has the paint colours?",
            "Which timer is for the rice?", "Which reminder is about the dentist?", "Which contact has a Halifax address?",
            "Which file is the lease?", "What appointments are on Friday?", "Which Wi-Fi network is the fastest?",
            "Quelle application utilise le plus de batterie?", "Quelle appli prend le plus d'espace?", "Quelle note a le code de la barrière?",
            "Quelle alarme sonne en premier?", "Quel rappel parle du dentiste?", "Quelle liste a les couleurs de peinture?",
            "Quels rendez-vous sont vendredi?", "Quel fichier est le bail?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
        listOf("Which planet is the largest?", "Which city is the capital of Nova Scotia?", "Quelle est la plus grande planète ?",
            "Which planet is closest to the Sun?", "Quelle ville est la capitale de la Nouvelle-Écosse?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
    }

    @Test
    fun politenessAndWakeWordsComeOff() {
        assertEquals("Louis Riel", WikiRoute.subject("Hey robot, can you tell me about Louis Riel?"))
        assertEquals("Révolution tranquille", WikiRoute.subject("Salut robot, parle-moi de la Révolution tranquille"))
        assertEquals("trou noir", WikiRoute.subject("Qu’est-ce qu’un trou noir?"))
        assertEquals("Einstein", WikiRoute.subject("Parle-moi d'Einstein"))
        assertTrue(WikiRoute.goes("Please, what is the Bluenose?"))
    }

    @Test
    fun personalQuestionsNever() {
        listOf("What's your name?", "Who are you?", "What is my wife's birthday?", "Who is our dentist?", "Where did I leave my keys?",
            "Qui est mon médecin?", "C'est quoi ton nom?", "Où est-ce que j'ai mis mes clés?", "Quelle est notre adresse?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
        assertTrue(WikiRoute.goes("What was World War I?"))
        assertTrue(WikiRoute.goes("Tell me about Viola Desmond"))
    }

    @Test
    fun freshQuestionsNever() {
        listOf("What's the latest on the election?", "Who won the Stanley Cup?", "What is the price of gold?", "What time is it?",
            "What is the weather in Halifax?", "What's the news?", "Quelle heure est-il?", "Qui a gagné la Coupe Stanley?", "C'est quoi les nouvelles?",
            "Quel est le prix de l'essence?", "Où est la tempête en ce moment?",
            "What's the weather this weekend?", "Qu'est-ce qui joue ce soir?", "Quelles sont les nouvelles cette semaine?",
        ).forEach { assertEquals(it, Verdict.FRESH, WikiRoute.check(it)) }
        // A time doesn't hide your own things: these stay personal.
        listOf("Where is my package right now?", "What's on my calendar this week?", "Où est mon colis en ce moment?",
            "Qu'est-ce que j'ai ce soir?", "Où sont mes clés en ce moment?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
    }

    @Test
    fun commandsNever() {
        listOf("Set an alarm for 7", "Call Bob", "Open the camera", "Play some jazz", "Search the web for Louis Riel", "Look up Louis Riel online",
            "Remind me to call Bob", "Envoie un texto à Marie", "Ouvre la caméra", "Cherche sur le web Louis Riel", "Recherche Louis Riel",
            "Ajoute du lait à la liste",
        ).forEach { assertEquals(it, Verdict.COMMAND, WikiRoute.check(it)) }
    }

    @Test
    fun notAQuestionOfFact() {
        listOf("Thanks", "Bonjour", "How are things", "Why", "What?", "").forEach { assertFalse(it, WikiRoute.goes(it)) }
    }

    @Test
    fun aPronounSubjectIsAFollowUpNeverTheCopys() {
        for (q in listOf("What is it?", "When was he born?", "Who was she?", "What was his name?", "Where is that?", "Who are they?",
            "C'est quoi ça?", "C'est quoi ca?", "Qui est-il?", "Quand est-il né?", "Qui était-elle?", "Qu'est-ce que c'est que cela?"))
            assertFalse(q, WikiRoute.goes(q))
        for (q in listOf("What is a fjord?", "Who was Louis Riel?", "C'est quoi un fjord?", "Qui était René Lévesque?"))
            assertEquals(q, Verdict.FACT, WikiRoute.check(q))
    }

    @Test
    fun whatIsSoNowIsFresh() {
        for (q in listOf("Who is the current prime minister of Canada?", "Who is currently the mayor of Halifax?", "Who runs Apple these days?",
            "Is Leonard Cohen still alive?", "Who leads the NDP now?", "Qui est le premier ministre actuel du Canada?",
            "Qui est actuellement le maire de Montréal?", "Qui dirige le parti en ce moment?", "Qui est présentement la gouverneure générale?",
            "Qui est la mairesse de Québec de nos jours?"))
            assertEquals(q, Verdict.FRESH, WikiRoute.check(q))
    }
}
