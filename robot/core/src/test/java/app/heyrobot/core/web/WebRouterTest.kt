// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.HelperRouter
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design A4.3 and A14 "WebRouter": the explicit forms keep your words; your own things stay with their tools. */
class WebRouterTest {
    @Test fun explicitEnglishFormsKeepYourWords() {
        assertEquals("who won the Stanley Cup this year", WebRouter.explicit("search the web for who won the Stanley Cup this year"))
        assertEquals("how to descale a Breville espresso machine", WebRouter.explicit("Search the web for how to descale a Breville espresso machine."))
        assertEquals("Pixel 10 Pro battery reviews", WebRouter.explicit("Search online for Pixel 10 Pro battery reviews?"))
        assertEquals("pizza near me", WebRouter.explicit("hey robot, search the web for pizza near me please"))
        assertEquals("the Debian 14 release date", WebRouter.explicit("look up the Debian 14 release date online"))
        assertEquals("Ursula K. Le Guin", WebRouter.explicit("look Ursula K. Le Guin up on the web"))
        assertEquals("Debian 14 release date", WebRouter.explicit("google Debian 14 release date"))
        assertEquals("descaling steps", WebRouter.explicit("search for descaling steps on the internet"))
        assertEquals("tide times Tadoussac", WebRouter.explicit("can you search the internet for tide times Tadoussac"))
    }

    @Test fun explicitFrenchForms() {
        assertEquals("la mairesse de Montréal", WebRouter.explicit("cherche sur le web la mairesse de Montréal"))
        assertEquals("les horaires du traversier", WebRouter.explicit("Cherche les horaires du traversier sur internet"))
        assertEquals("horaires du métro de Montréal", WebRouter.explicit("recherche horaires du métro de Montréal"))
        assertEquals("la recette de pouding chômeur", WebRouter.explicit("fais une recherche sur la recette de pouding chômeur"))
        assertEquals("Gabrielle Roy", WebRouter.explicit("cherche en ligne Gabrielle Roy s'il te plait"))
    }

    @Test fun yourOwnThingsAndOtherWordsStayWithTheirTools() {
        assertNull(WebRouter.explicit("search my notes for coffee"))
        assertNull(WebRouter.explicit("search my contacts for Bob"))
        assertNull(WebRouter.explicit("look up Bob"))
        assertNull(WebRouter.explicit("look up Bob's number"))
        assertNull(WebRouter.explicit("recherche dans mes notes le code du garage"))
        assertNull(WebRouter.explicit("recherche mes contacts"))
        assertNull(WebRouter.explicit("search the web for my notes"))
        assertNull(WebRouter.explicit("what's the weather tomorrow"))
        assertNull(WebRouter.explicit("search"))
        // "Ask Claude to search…" is Claude's: HelperRouter runs before WebRouter, and the form isn't web search's either.
        assertNull(WebRouter.explicit("ask Claude to search the web for the latest news"))
    }

    @Test fun theWebGroupsWordsInBothLanguages() {
        for (q in listOf("who won the game last night", "what's the latest news on the storm", "today's price of bitcoin", "search for flights", "look up ferries",
            "is it on the internet", "qui a gagné hier soir", "les dernières nouvelles", "l'actualité de Québec", "le prix de l'essence", "cherche un plombier")) {
            assertTrue(q, WebRouter.wants(q))
            assertTrue(q, ToolGroup.WEB in ToolRouter.route(q))
            // A read that goes online: your words asked for it (Safety cards' "asked").
            assertTrue(q, ToolName.WEB_SEARCH in ToolRouter.asked(q))
        }
        for (q in listOf("set a timer for 5 minutes", "text mom I'm late", "tell me a joke", "what's 15 % of 80")) {
            assertFalse(q, WebRouter.wants(q))
            assertFalse(q, ToolName.WEB_SEARCH in ToolRouter.asked(q))
        }
    }

    @Test fun theQuestionsLanguage() {
        assertEquals("fr", WebRouter.lang("Qui est la mairesse de Montréal ?"))
        assertEquals("fr", WebRouter.lang("cherche les dernières nouvelles"))
        assertEquals("en", WebRouter.lang("who won the Stanley Cup this year"))
        assertEquals("en", WebRouter.lang("Debian 14"))
    }
}
