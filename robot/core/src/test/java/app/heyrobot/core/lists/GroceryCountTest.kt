// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test Bench everyday-1.2.2-a, bug 2: "how many items are on my grocery list" went to the model, which looked for an app
 * called "Grocery List". Asking how many things a list holds is a read of that list (its reply says how many).
 */
class GroceryCountTest {
    private fun read(s: String) = ListsRouter.route(s) as? ListsCommand.Read

    @Test fun howManyIsARead() {
        for (s in listOf("how many items are on my grocery list", "How many things are on my shopping list?", "how many items do I have on my grocery list",
            "how many things are on the groceries list", "how many items are on my supermarket list", "how many items on my grocery list")) {
            assertEquals(s, ListRef.Role(ListRole.SHOPPING), read(s)?.list)
        }
        for (s in listOf("combien d'items sur ma liste d'épicerie", "Combien de choses il y a sur ma liste d'épicerie ?",
            "j'ai combien d'affaires sur ma liste d'épicerie", "combien d'articles sur ma liste de commissions")) {
            assertEquals(s, ListRef.Role(ListRole.SHOPPING), read(s)?.list)
        }
        assertEquals(ListRef.Role(ListRole.TODO), read("how many things are on my to-do list")?.list)
    }

    @Test fun otherHowManyAsksStayAway() {
        for (s in listOf("how many people live there", "how many items are in my cart", "combien de personnes vivent là")) assertEquals(s, null, ListsRouter.route(s))
    }
}
