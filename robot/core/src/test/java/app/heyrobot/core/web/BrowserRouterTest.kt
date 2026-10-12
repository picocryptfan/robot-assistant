package app.heyrobot.core.web

import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0: the phrases that open a search in your browser (BrowserRouter), in English and Québec French: your words exactly,
 * the engine you named (or none: your default), the model's turn for "look it up on Google", and Robot's own "search the
 * web for …" left to WebRouter.
 */
class BrowserRouterTest {
    private fun open(q: String, engine: BrowserEngine?) = BrowserAsk.Open(q, engine)

    @Test fun englishFormsNameAnEngine() {
        assertEquals(open("tonight's Canadiens game", BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("search DuckDuckGo for tonight's Canadiens game"))
        assertEquals(open("the Debian 14 release date", BrowserEngine.GOOGLE), BrowserRouter.explicit("hey robot, look up the Debian 14 release date on Google please"))
        assertEquals(open("tide times Tadoussac", BrowserEngine.STARTPAGE), BrowserRouter.explicit("Search Startpage for tide times Tadoussac."))
        assertEquals(open("Ursula K. Le Guin", BrowserEngine.BRAVE), BrowserRouter.explicit("look Ursula K. Le Guin up on Brave Search"))
        assertEquals(open("descaling steps", BrowserEngine.BING), BrowserRouter.explicit("search for descaling steps on Bing"))
        assertEquals(open("pouding chômeur", BrowserEngine.MOJEEK), BrowserRouter.explicit("can you search Mojeek for pouding chômeur"))
        assertEquals(open("ferry schedule", BrowserEngine.QWANT), BrowserRouter.explicit("search ferry schedule on Qwant"))
        assertEquals(open("tree planting", BrowserEngine.ECOSIA), BrowserRouter.explicit("search with Ecosia for tree planting"))
        assertEquals(open("cheap flights", BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("search on duck duck go for cheap flights"))
        assertEquals(open("Stanley Cup winner", BrowserEngine.GOOGLE), BrowserRouter.explicit("open Google and search for Stanley Cup winner"))
        assertEquals(open("cheap flights", BrowserEngine.GOOGLE), BrowserRouter.explicit("search the web for cheap flights on Google"))
        assertEquals(open("weather radar", BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("duckduckgo weather radar"))
    }

    @Test fun quebecFrenchForms() {
        assertEquals(open("les horaires du traversier", BrowserEngine.STARTPAGE), BrowserRouter.explicit("cherche sur Startpage les horaires du traversier"))
        assertEquals(open("la mairesse de Montréal", BrowserEngine.GOOGLE), BrowserRouter.explicit("Cherche la mairesse de Montréal sur Google"))
        assertEquals(open("le prix de l'essence", BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("recherche le prix de l'essence sur DuckDuckGo s'il te plaît"))
        assertEquals(open("la recette de tourtière", BrowserEngine.GOOGLE), BrowserRouter.explicit("fais une recherche Google sur la recette de tourtière"))
        assertEquals(open("Gabrielle Roy", BrowserEngine.QWANT), BrowserRouter.explicit("fais une recherche sur Qwant pour Gabrielle Roy"))
        assertEquals(open("les Nordiques", BrowserEngine.GOOGLE), BrowserRouter.explicit("checke les Nordiques su Google"))
        assertEquals(open("l'heure à Paris", BrowserEngine.STARTPAGE), BrowserRouter.explicit("cherche-moi l'heure à Paris sur Startpage"))
    }

    @Test fun theBrowserWithoutAnEngineIsYourDefault() {
        assertEquals(open("pizza near me", null), BrowserRouter.explicit("search in my browser for pizza near me"))
        assertEquals(open("the Stanley Cup", null), BrowserRouter.explicit("search for the Stanley Cup in the browser"))
        assertEquals(open("les horaires de la STM", null), BrowserRouter.explicit("cherche les horaires de la STM dans mon navigateur"))
        assertEquals(open("Gilles Vigneault", null), BrowserRouter.explicit("cherche dans le navigateur Gilles Vigneault"))
        assertEquals(open("tide times", BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("search DuckDuckGo for tide times in my browser"))
    }

    @Test fun wordsThatStandForSomethingSaidBeforeAreTheModels() {
        assertEquals(BrowserAsk.Resolve(BrowserEngine.GOOGLE), BrowserRouter.explicit("look it up on Google"))
        assertEquals(BrowserAsk.Resolve(BrowserEngine.DUCKDUCKGO), BrowserRouter.explicit("search DuckDuckGo for that"))
        assertEquals(BrowserAsk.Resolve(BrowserEngine.BING), BrowserRouter.explicit("bing it"))
        assertEquals(BrowserAsk.Resolve(null), BrowserRouter.explicit("look it up in my browser"))
        assertEquals(BrowserAsk.Resolve(BrowserEngine.GOOGLE), BrowserRouter.explicit("cherche-le sur Google"))
        assertEquals(BrowserAsk.Resolve(BrowserEngine.GOOGLE), BrowserRouter.explicit("cherche ça sur Google"))
        assertEquals(BrowserAsk.Resolve(BrowserEngine.STARTPAGE), BrowserRouter.explicit("regarde ça sur Startpage"))
        // The panel leaves them to the model (no route), and describes browser_search for them.
        for (q in listOf("look it up on Google", "cherche-le sur Google", "google it", "can you check on DuckDuckGo who won last night")) {
            assertNull(q, BrowserRouter.route(q))
            assertTrue(q, BrowserRouter.named(q))
            assertTrue(q, ToolGroup.WEB in ToolRouter.route(q))
        }
        // 1.0: words that start with a pronoun, a possessive or a demonstrative are a reference too, in both languages: the model
        // writes the query (browser_search, its card first, QueryCheck), as for a bare pronoun.
        for ((q, engine) in listOf("look that dentist up on DuckDuckGo" to BrowserEngine.DUCKDUCKGO, "search Google for this place" to BrowserEngine.GOOGLE,
            "look up their hours on Bing" to BrowserEngine.BING, "search for those on Startpage" to BrowserEngine.STARTPAGE,
            "search DuckDuckGo for them" to BrowserEngine.DUCKDUCKGO, "look up his number on Google" to BrowserEngine.GOOGLE,
            "cherche ce resto sur Google" to BrowserEngine.GOOGLE, "cherche cette adresse sur Startpage" to BrowserEngine.STARTPAGE,
            "recherche ces horaires sur DuckDuckGo" to BrowserEngine.DUCKDUCKGO, "cherche celui-là sur Google" to BrowserEngine.GOOGLE,
            "cherche ça sur Qwant" to BrowserEngine.QWANT, "cherche cet hôtel sur Google" to BrowserEngine.GOOGLE)) {
            assertEquals(q, BrowserAsk.Resolve(engine), BrowserRouter.explicit(q))
            assertNull(q, BrowserRouter.route(q))
            assertNull(q, BrowserRouter.ownWords(q))
            assertTrue(q, BrowserRouter.named(q))
        }
        assertEquals(BrowserAsk.Resolve(null), BrowserRouter.explicit("search for that restaurant in my browser"))
        assertEquals(BrowserAsk.Resolve(null), BrowserRouter.explicit("cherche ce resto dans mon navigateur"))
        // An article, a name or other words that only look alike stay your own words.
        for ((q, words) in listOf("search Google for the Stanley Cup" to "the Stanley Cup", "cherche la mairesse de Montréal sur Google" to "la mairesse de Montréal",
            "cherche les horaires sur Google" to "les horaires", "search DuckDuckGo for theatre listings" to "theatre listings",
            "search Bing for He-Man toys" to "He-Man toys", "cherche c'est quoi un pouding chômeur sur Google" to "c'est quoi un pouding chômeur",
            "search Google for Hermès bags" to "Hermès bags")) {
            assertEquals(q, words, (BrowserRouter.explicit(q) as BrowserAsk.Open).query)
        }
        // WebRouter no longer takes "it" or « ça » as the words to search for.
        assertNull(WebRouter.explicit("google it"))
        assertNull(WebRouter.explicit("cherche ça sur internet"))
    }

    @Test fun robotsOwnSearchAndYourOwnThingsStayAsTheyWere() {
        assertEquals(WebRoute.Search("who won the Stanley Cup this year"), BrowserRouter.route("search the web for who won the Stanley Cup this year"))
        assertEquals(WebRoute.Search("Debian 14 release date", google = true), BrowserRouter.route("google Debian 14 release date"))
        assertEquals(WebRoute.Search("la mairesse de Montréal"), BrowserRouter.route("cherche sur le web la mairesse de Montréal"))
        assertEquals(WebRoute.Browser("tide times", BrowserEngine.DUCKDUCKGO), BrowserRouter.route("search DuckDuckGo for tide times"))
        // « … sur Google » names an engine now: your browser, not Robot's own search.
        assertNull(WebRouter.explicit("cherche les horaires sur Google"))
        assertEquals(WebRoute.Browser("les horaires", BrowserEngine.GOOGLE), BrowserRouter.route("cherche les horaires sur Google"))
        for (q in listOf("search my notes on Google", "search my contacts for Bob", "look up Bob", "cherche mes notes sur Google", "search Google Maps for pizza",
            "search YouTube for cats", "what's the weather tomorrow", "search", "is Google down?", "ask Claude to search Google for the news")) {
            assertNull(q, BrowserRouter.explicit(q))
        }
        assertFalse(BrowserRouter.named("is Google down?"))
        assertFalse(BrowserRouter.named("what's the latest news about Bing"))
        assertFalse(BrowserRouter.named("the brave little toaster"))
        assertEquals(BrowserEngine.GOOGLE, BrowserRouter.engineIn("look it up on Google"))
        assertNull(BrowserRouter.engineIn("search the web for cats"))
        assertEquals("cats", BrowserRouter.ownWords("search the web for cats"))
        assertEquals("cats", BrowserRouter.ownWords("search Bing for cats"))
        assertNull(BrowserRouter.ownWords("look it up on Bing"))
    }
}
