// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Audit 1.1.2 F36, F133 to F138: which router takes these phrases, in English and Québec French. */
class RoutersTableTest {
    private val costco = ListsHeard(names = listOf("Costco"))

    private fun item(text: String) = ListItem(ListsId.new(SecureRandom()), text, pos = 1000L, at = 0, upd = 0, by = ItemBy.YOU, how = ItemHow.TYPED)

    @Test fun anyAnsweredAsTheItem() {
        val c = ListsRouter.route("do I have any milk on my list") as ListsCommand.Check
        assertEquals("any milk", c.words)
        assertEquals(listOf("Milk"), ListsMatch.find(listOf(item("Milk"), item("Eggs")), c.words, Lang.EN).map { it.text })
        assertTrue(ListsRouter.route("is there any milk on my shopping list") is ListsCommand.Check)
    }

    @Test fun anythingReadsTheList() {
        for (q in listOf("do I have anything on my list", "is there anything on my list", "is there something on the shopping list",
            "do I have any items on my list", "j'ai-tu quelque chose sur ma liste", "est-ce qu'il y a quelque chose sur ma liste d'épicerie")) {
            assertTrue(q, ListsRouter.route(q) is ListsCommand.Read)
        }
    }

    @Test fun aGeneralQuestionIsNotYourList() {
        for (q in listOf("what's on the Billboard list", "what's on the UNESCO list", "what is the Billboard list", "is Paris on the UNESCO list",
            "are eggs on the dirty dozen list", "is there tax on groceries", "are there taxes on groceries", "what's on groceries",
            "qu'est-ce qu'il y a sur la liste de Schindler", "est-ce qu'il y a des pommes sur la liste de Schindler")) {
            assertNull(q, ListsRouter.route(q))
        }
    }

    @Test fun yourListIsStillYours() {
        assertTrue(ListsRouter.route("what's on my packing list") is ListsCommand.Read)
        assertTrue(ListsRouter.route("what's on the Costco list", costco) is ListsCommand.Read)
        assertTrue(ListsRouter.route("what's on the shopping list") is ListsCommand.Read)
        assertTrue(ListsRouter.route("what's on the list") is ListsCommand.Read)
        assertTrue(ListsRouter.route("is there milk on my groceries") is ListsCommand.Check)
        assertTrue(ListsRouter.route("is oat milk on the shopping list") is ListsCommand.Check)
        assertTrue(ListsRouter.route("do I have eggs on the Costco list", costco) is ListsCommand.Check)
        assertTrue(ListsRouter.route("qu'est-ce qu'il y a sur ma liste de bagages") is ListsCommand.Read)
        assertTrue(ListsRouter.route("qu'est-ce qu'il y a sur ma liste d'épicerie") is ListsCommand.Read)
        // A command isn't a general question.
        assertTrue(ListsRouter.route("add socks to the packing list") is ListsCommand.Add)
    }

    @Test fun aSongOnAPlaylistIsNotPlayed() {
        val ctx = MusicContext(appPicked = true)
        for (q in listOf("mets cette toune sur ma liste de lecture", "mets ça sur ma liste de lecture", "joue cette toune sur ma liste de lecture")) {
            assertNull(q, ListsRouter.route(q))
            assertNull(q, MusicRouter.route(q, ctx))
        }
    }

    @Test fun aNegationAddsNothing() {
        for (q in listOf("mets pas de lait sur ma liste", "ajoute pas de lait à ma liste d'épicerie", "mets-moi pas de lait sur ma liste",
            "mets plus de lait sur ma liste", "mets plus d'œufs sur ma liste")) {
            assertNull(q, ListsRouter.route(q))
        }
        assertTrue(ListsRouter.route("mets du lait sur ma liste") is ListsCommand.Add)
        assertTrue(ListsRouter.route("add plus size jeans to my list") is ListsCommand.Add)
    }

    @Test fun aListOfThingsIsAnAnswer() {
        for (q in listOf("fais-moi une liste de recettes faciles", "fais une liste d'idées cadeaux", "fais une liste de lecture")) {
            assertNull(q, ListsRouter.route(q))
        }
        assertEquals(ListsCommand.Make("camping", null, Lang.FR), ListsRouter.route("fais une liste de camping"))
        assertEquals(ListsCommand.Make(null, ListRole.TODO, Lang.FR), ListsRouter.route("fais une liste de choses à faire"))
        assertEquals(ListsCommand.Make("Noël", null, Lang.FR), ListsRouter.route("crée une nouvelle liste qui s'appelle Noël"))
    }

    @Test fun putBackBeforeTheItem() {
        assertEquals(ListsCommand.PutBack("eggs", ListRef.Unnamed, Lang.EN), ListsRouter.route("put back eggs on the list"))
        assertEquals(ListsCommand.PutBack("the eggs", ListRole.SHOPPING.let { ListRef.Role(it) }, Lang.EN), ListsRouter.route("put back the eggs on my shopping list"))
        assertEquals(ListsCommand.PutBack("eggs", ListRef.Unnamed, Lang.EN), ListsRouter.route("put eggs back on the list"))
        assertTrue(ListsRouter.route("add back brace to my list") is ListsCommand.Add)
        assertTrue(ListsRouter.route("add a back scrubber to the list") is ListsCommand.Add)
        assertNull(ListsRouter.route("put back the phone"))
    }

    @Test fun someonesWordsAreNotASong() {
        val ctx = MusicContext(appPicked = true)
        for (q in listOf("play the text Sam sent me", "play the email from Sam", "joue le texto de Sam", "joue le courriel de Sam", "play that again", "joue ça encore")) {
            assertNull(q, MusicRouter.route(q, ctx))
        }
        assertTrue(MusicRouter.route("play a song called Text Me", ctx) != null)
    }
}
