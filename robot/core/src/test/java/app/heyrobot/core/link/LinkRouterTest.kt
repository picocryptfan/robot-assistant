// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * robot-to-robot-plan.md §4.3, D15 (LinkRouterTest; job L7): your words to another Robot in English and Québec French, the
 * people it counts as paired, and the misfires: everyday talk, texting, notes, the calendar, files, skills, routines, the web
 * and helpers keep their words, and the routers before it in the panel's order leave its phrases alone.
 */
class LinkRouterTest {
    private val now = ZonedDateTime.parse("2026-10-21T10:00:00-03:00[America/Halifax]")
    private val pairedNames = setOf("sam", "mom", "marie-ève", "uncle bob")
    private fun r(t: String, paired: Set<String> = pairedNames) = LinkRouter.route(t, now) { it.lowercase() in paired }

    private fun ask(who: String, kind: LinkAskKind, details: String, lang: Lang = Lang.EN) = LinkCommand.Ask(who, kind, details, lang)

    @Test fun freeBusyInEnglish() {
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "Friday evening"), r("ask Sam's robot when Sam is free Friday evening"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "tomorrow"), r("Hey Robot, ask Sam's robot if she's free tomorrow?"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "next week"), r("ask Sam's robot for his availability next week"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "Friday evening"), r("is Sam free Friday evening"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, ""), r("is Sam free?"))
        assertEquals(ask("Mom", LinkAskKind.FREE_BUSY, "this weekend"), r("when is Mom free this weekend"))
        assertEquals(ask("Mom", LinkAskKind.FREE_BUSY, "tomorrow"), r("is Mom available tomorrow"))
        assertEquals(ask("Uncle Bob", LinkAskKind.FREE_BUSY, "Saturday"), r("check if Uncle Bob is free Saturday"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "tonight"), r("is Sam busy tonight"))
        // Naming their Robot claims the phrase even for someone not paired (Robot then says so).
        assertEquals(ask("Jo", LinkAskKind.FREE_BUSY, "Friday"), r("ask Jo's robot when Jo is free Friday"))
    }

    @Test fun freeBusyInQuebecFrench() {
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "vendredi soir", Lang.FR), r("demande au robot de Sam s'il est libre vendredi soir"))
        assertEquals(ask("Marie-Ève", LinkAskKind.FREE_BUSY, "demain", Lang.FR), r("demande au robot de Marie-Ève si elle est disponible demain"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "demain", Lang.FR), r("est-ce que Sam est libre demain?"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "samedi", Lang.FR), r("Sam est-il libre samedi"))
        assertEquals(ask("Mom", LinkAskKind.FREE_BUSY, "", Lang.FR), r("quand est-ce que Mom est libre"))
        assertEquals(ask("Mom", LinkAskKind.FREE_BUSY, "la semaine prochaine", Lang.FR), r("quand Mom est disponible la semaine prochaine"))
        assertEquals(ask("Sam", LinkAskKind.FREE_BUSY, "ce soir", Lang.FR), r("Sam est libre ce soir"))
    }

    @Test fun plansInBothLanguages() {
        assertEquals(ask("Sam", LinkAskKind.PLAN, "dinner Friday at 7 or Saturday at 6"), r("propose dinner with Sam Friday at 7 or Saturday at 6"))
        assertEquals(ask("Sam", LinkAskKind.PLAN, "a coffee tomorrow at 10"), r("suggest a coffee with Sam tomorrow at 10"))
        assertEquals(ask("Mom", LinkAskKind.PLAN, "lunch tomorrow at noon"), r("invite Mom to lunch tomorrow at noon"))
        assertEquals(ask("Uncle Bob", LinkAskKind.PLAN, "a walk tomorrow at 7"), r("plan a walk with Uncle Bob tomorrow at 7"))
        assertEquals(ask("Jo", LinkAskKind.PLAN, "dinner Friday at 7"), r("propose dinner with Jo's robot Friday at 7"))
        assertEquals(ask("Sam", LinkAskKind.PLAN, "un souper vendredi à 19 h ou samedi à 18 h", Lang.FR),
            r("propose un souper avec Sam vendredi à 19 h ou samedi à 18 h"))
        assertEquals(ask("Mom", LinkAskKind.PLAN, "dîner demain à midi", Lang.FR), r("invite Mom à dîner demain à midi"))
        // No clock time: not a plan for their Robot (the calendar or the model takes it).
        assertNull(r("propose dinner with Sam on Friday"))
        assertNull(r("invite Mom to the wedding"))
        // Someone not paired: ordinary talk.
        assertNull(r("plan a trip with Jo Friday at 7"))
    }

    @Test fun cancelAndTightening() {
        assertEquals(ask("Sam", LinkAskKind.CANCEL_PLAN, "plan"), r("cancel my plan with Sam"))
        assertEquals(ask("Sam", LinkAskKind.CANCEL_PLAN, "dinner"), r("cancel dinner with Sam"))
        assertEquals(ask("Sam", LinkAskKind.CANCEL_PLAN, "plan", Lang.FR), r("annule mon plan avec Sam"))
        assertEquals(LinkCommand.StopAlone("Sam", Lang.EN), r("stop answering Sam on your own"))
        assertEquals(LinkCommand.StopAlone("Sam", Lang.EN), r("stop answering Sam's robot on your own"))
        assertEquals(LinkCommand.StopAlone("Mom", Lang.EN), r("don't answer Mom by yourself anymore"))
        assertEquals(LinkCommand.StopAlone("Sam", Lang.FR), r("arrête de répondre à Sam tout seul"))
        assertEquals(LinkCommand.StopAlone("Sam", Lang.FR), r("arrête de répondre toute seule au robot de Sam"))
        assertEquals(LinkCommand.StopAlone("Mom", Lang.FR), r("ne réponds plus à Mom automatiquement"))
        // Only someone paired.
        assertNull(r("cancel my plan with Jo"))
        assertNull(r("stop answering Jo on your own"))
    }

    @Test fun aRobotYouArentPairedWithIsSaidSo() {
        assertEquals(LinkCommand.NotPaired("Zed", Lang.EN), r("ask Zed's robot for the lasagna recipe"))
        assertEquals(LinkCommand.NotPaired("Zed", Lang.FR), r("demande au robot de Zed la recette de lasagne"))
    }

    @Test fun nobodyPairedAnyRobotYouNameIsSaidNotPaired() {
        val at = now
        for (q in listOf("ask Zed's robot for a pasta recipe", "ask Zed's robot for the weather", "ask Zed's robot when Zed is free Friday", "ask Zed's robot where the party is", "propose dinner with Zed's robot Friday at 7"))
            assertEquals(q, LinkCommand.NotPaired("Zed", Lang.EN), LinkRouter.named(q, at))
        assertEquals(LinkCommand.NotPaired("Zed", Lang.FR), LinkRouter.named("demande au robot de Zed la recette de lasagne", at))
        for (q in listOf("what's a robot", "is Zed free Friday", "tell Zed I'm 10 minutes away", "ask this robot for a recipe", "demande au robot de chercher une recette", "demande au robot de me dire la météo", "ask his robot where it is"))
            assertEquals(q, null, LinkRouter.named(q, at))
    }

    @Test fun aLowercaseFirstNameStillCountsWhenItsPaired() {
        assertEquals(LinkCommand.NotYet("pierre", Lang.FR), r("demande au robot de pierre la recette de lasagne", setOf("pierre")))
        assertEquals(LinkCommand.NotPaired("Pierre", Lang.FR), r("demande au robot de Pierre la recette de lasagne", emptySet()))
    }

    @Test fun notPairedWordsSayWhatRobotCanTell() {
        assertEquals("You're not paired with Zed's Robot. Pair in Settings › Robot to Robot.", LinkRouter.notPairedWords("Zed", on = true, paired = false).render(Langs.EN))
        assertEquals("Robot to Robot is off, so you're not paired with Zed's Robot right now. Turn it on and pair in Settings › Robot to Robot.", LinkRouter.notPairedWords("Zed", on = false, paired = null).render(Langs.EN))
        for (paired in listOf(true, null)) assertEquals("Couldn't send it to Zed's Robot. Try again.", LinkRouter.notPairedWords("Zed", on = true, paired = paired).render(Langs.EN))
    }

    @Test fun somethingRobotToRobotCantAskYetIsSaidSo() {
        assertEquals(LinkCommand.NotYet("Mom", Lang.EN), r("ask Mom's robot for the lasagna recipe"))
        assertEquals(LinkCommand.NotYet("Mom", Lang.FR), r("demande au robot de Mom la recette de lasagne"))
    }

    /** Everyday talk, with the people paired: none of these is Robot to Robot's. */
    @Test fun etaRemindersQuestionsAndCardsInBothLanguages() {
        assertEquals(ask("Sam", LinkAskKind.ETA, "I'm 10 minutes away"), r("tell Sam I'm 10 minutes away"))
        assertEquals(ask("Mom", LinkAskKind.ETA, "I'm about 20 minutes from home"), r("let Mom know I'm about 20 minutes from home"))
        assertEquals(ask("Uncle Bob", LinkAskKind.ETA, "I'll be there in half an hour"), r("tell Uncle Bob I'll be there in half an hour"))
        assertEquals(ask("Sam", LinkAskKind.ETA, "je suis à 10 minutes", Lang.FR), r("dis à Sam que je suis à 10 minutes"))
        assertEquals(ask("Mom", LinkAskKind.ETA, "j'arrive dans une demi-heure", Lang.FR), r("préviens Mom que j'arrive dans une demi-heure"))
        assertEquals(ask("Sam", LinkAskKind.REMINDER, "bring the charger tomorrow"), r("remind Sam to bring the charger tomorrow"))
        assertEquals(ask("Mom", LinkAskKind.REMINDER, "acheter du lait", Lang.FR), r("rappelle à Mom d'acheter du lait"))
        assertEquals(ask("Mom", LinkAskKind.CONTACT, "Uncle Bob"), r("send Mom Uncle Bob's number"))
        assertEquals(ask("Sam", LinkAskKind.CONTACT, "Dr Lee"), r("send Sam the contact card for Dr Lee"))
        assertEquals(ask("Sam", LinkAskKind.CONTACT, "Marie-Ève", Lang.FR), r("envoie à Sam les coordonnées de Marie-Ève"))
        assertEquals(ask("Sam", LinkAskKind.QUESTION, "where the party is"), r("ask Sam's robot where the party is"))
        assertEquals(ask("Jo", LinkAskKind.QUESTION, "what time dinner is"), r("ask Jo's robot what time dinner is"))
        assertEquals(ask("Mom", LinkAskKind.QUESTION, "où est la fête", Lang.FR), r("demande au robot de Mom où est la fête"))
        // Free/busy still wins its own phrases; something else asked of their Robot is still "not yet".
        assertEquals(LinkAskKind.FREE_BUSY, (r("ask Sam's robot if she's free tomorrow") as LinkCommand.Ask).kind)
        assertEquals(LinkCommand.NotYet("Mom", Lang.EN), r("ask Mom's robot for the lasagna recipe"))
    }

    @Test fun misfires() {
        for (t in listOf(
            "tell Sam I'm running late", "tell Sam I'm 10 minutes late", "remind me to call Sam tomorrow", "send Sam a text",
            "send Sam the photos", "tell Jo I'm 10 minutes away", "dis à Sam que je suis en retard", "rappelle-moi d'appeler Sam",
            "text Sam I'm free tomorrow", "tell Mom I'm available Friday", "reply to Sam: free Friday evening", "remind me to propose a toast to Sam",
            "add dinner with Sam Friday at 7 to my calendar", "what's on my calendar Friday", "am I free Friday evening", "are you free",
            "is it free tomorrow", "is the pool free Saturday", "when is the library free", "plan a trip to Montréal", "plan my week",
            "search the web for a robot vacuum", "ask Claude when Sam is free", "file this receipt", "cancel the timer", "cancel my alarm",
            "cancel the 7 o'clock alarm", "stop answering the phone", "stop", "free up space", "invite", "call Sam", "robot", "hey robot",
            "what's the weather Friday evening", "remember that Sam is free on Fridays", "my robot is broken", "is this robot free software",
            "dis à Sam que je suis libre vendredi", "est-ce que je suis libre demain", "quand est-ce que le magasin est ouvert",
            "annule la minuterie", "propose-moi une recette", "arrête de parler", "libère de l'espace", "écris à Mom que je suis disponible",
        )) assertNull(t, r(t))
        // Nobody paired: only the phrases that name someone's Robot.
        assertNull(r("is Sam free Friday", paired = emptySet()))
        assertTrue(r("ask Sam's robot when Sam is free Friday", paired = emptySet()) is LinkCommand.Ask)
    }

    /** The routers before it in the panel's order (D15) leave its phrases alone, so they reach it. */
    @Test fun theRoutersBeforeItLeaveItsPhrasesAlone() {
        val phrases = listOf("ask Sam's robot when Sam is free Friday evening", "is Sam free tomorrow", "when is Mom free this weekend",
            "propose dinner with Sam Friday at 7 or Saturday at 6", "invite Mom to lunch tomorrow at noon", "cancel my plan with Sam",
            "stop answering Sam on your own", "ask Mom's robot for the lasagna recipe", "demande au robot de Sam s'il est libre vendredi soir",
            "est-ce que Sam est libre demain", "propose un souper avec Sam vendredi à 19 h ou samedi à 18 h", "annule mon plan avec Sam",
            "arrête de répondre à Sam tout seul", "tell Sam I'm 10 minutes away", "dis à Sam que je suis à 10 minutes",
            "remind Sam to bring the charger tomorrow", "rappelle à Mom d'acheter du lait", "send Mom Uncle Bob's number",
            "envoie à Sam les coordonnées de Marie-Ève", "ask Sam's robot where the party is", "demande au robot de Mom où est la fête")
        for (t in phrases) {
            assertTrue(t, r(t) != null)
            assertNull(t, MessagesRouter.route(t))
            assertNull(t, ConversationRouter.route(t, running = false))
            assertNull(t, FeedbackRouter.route(t))
            assertNull(t, LockRouter.route(t, now, listOf("Bedtime")))
            assertNull(t, RoutineRouter.route(t, now, listOf("Evening routine")))
            assertNull(t, SkillRouter.route(t, listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())))
            assertNull(t, FilesRouter.route(t))
            assertNull(t, HelperRouter.route(t, listOf(HelperName.CLAUDE)))
        }
    }

    /** Your words never reach a log through a command's toString. */
    @Test fun commandsSayNothingOfYourWords() {
        for (c in listOf(r("ask Sam's robot when Sam is free Friday evening")!!, r("stop answering Sam on your own")!!, r("ask Mom's robot for the lasagna recipe")!!)) {
            assertTrue(c.toString(), !c.toString().contains("Sam") && !c.toString().contains("Mom") && !c.toString().contains("Friday"))
        }
    }
}
