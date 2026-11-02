// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "What went online today?" in both languages, and every misfire of everyday-set-plan.md §5.6. The routing corpus has no
 * phrase OnlineRouter takes (EverydayCorpusTest's takenOnline stays empty), so its own phrases and misfires are here.
 */
class OnlineRouterTest {
    private fun ask(t: String) = OnlineRouter.route(t) as? OnlineAsk

    @Test fun theQuestionsInEnglish() {
        for ((t, day) in listOf(
            "what went online today?" to OnlineDay.TODAY,
            "What went online" to OnlineDay.TODAY,
            "Hey Robot, what went online today?" to OnlineDay.TODAY,
            "did you go online?" to OnlineDay.TODAY,
            "did you go online yesterday" to OnlineDay.YESTERDAY,
            "have you been online today" to OnlineDay.TODAY,
            "what did you send online?" to OnlineDay.TODAY,
            "what have you sent online this week" to OnlineDay.WEEK,
            "what did Robot send online lately" to OnlineDay.WEEK,
            "tell me what went online yesterday please" to OnlineDay.YESTERDAY,
            "what servers did you connect to today" to OnlineDay.TODAY,
            "what did you connect to?" to OnlineDay.TODAY,
            "where did you go online today" to OnlineDay.TODAY,
        )) {
            val a = ask(t)
            assertEquals(t, OnlineAsk(Lang.EN, day), a)
        }
    }

    @Test fun theQuestionsInQuebecFrench() {
        for ((t, day) in listOf(
            "Qu'est-ce qui est allé en ligne aujourd'hui ?" to OnlineDay.TODAY,
            "qu’est-ce qui est allé en ligne" to OnlineDay.TODAY,
            "Es-tu allé en ligne ?" to OnlineDay.TODAY,
            "es-tu allé en ligne hier" to OnlineDay.YESTERDAY,
            "Est-ce que tu es allé en ligne cette semaine ?" to OnlineDay.WEEK,
            "Qu'est-ce que tu as envoyé en ligne ?" to OnlineDay.TODAY,
            "qu'as-tu envoyé en ligne aujourd'hui" to OnlineDay.TODAY,
            "T'es-tu connecté aujourd'hui ?" to OnlineDay.TODAY,
            "dis-moi ce qui est allé en ligne" to OnlineDay.TODAY,
            "Dis Robot, qu'est-ce qui est allé en ligne hier, s'il te plaît" to OnlineDay.YESTERDAY,
        )) {
            assertEquals(t, OnlineAsk(Lang.FR, day), ask(t))
        }
    }

    @Test fun neverARequestToGoOnlineOrAQuestionAboutSomethingElse() {
        for (t in listOf(
            // A request to go online (§5.6): the weather tool and web search, as today.
            "go online and check the weather", "Va en ligne et regarde la météo", "search online for Pixel 10 Pro battery reviews",
            "Search online for Pixel 10 Pro battery reviews?", "cherche en ligne Gabrielle Roy s'il te plait", "va en ligne et cherche les horaires",
            "look up the Debian 14 release date online",
            // Your connection, not Robot's record.
            "how do I get online", "is the internet down", "est-ce qu'Internet est en panne", "is it on the internet",
            // Not Robot's connections.
            "what's online at the library", "is Sam online", "what went online in the news today", "did Sam go online today",
            // Robot can't switch its own network: GrapheneOS's Network switch does.
            "go offline", "mets-toi hors ligne",
            // Present tense, or a statement.
            "Nothing goes online.", "what goes online", "go online", "online", "",
            // A question that only starts like one.
            "what went online today and what's the weather", "did you go online to buy shoes",
        )) assertNull(t, OnlineRouter.route(t))
    }
}
