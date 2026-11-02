// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ItemTextTest: cleanup, quantities in English and Québec French, the split by code with its
 * compounds, secrets refused, the caps.
 */
class ItemTextTest {
    private fun items(s: String) = (ItemText.read(s) as ItemsRead.Ok).items
    private fun one(s: String) = ItemText.item(s)

    /** The Test Bench's LT2, in both languages: three items each, the milk and the juice with "2 L". */
    @Test fun theTestBenchSentences() {
        assertEquals(listOf(NewItem("eggs"), NewItem("bread"), NewItem("milk", "2 L")), items("eggs, bread and 2 litres of milk"))
        assertEquals(listOf(NewItem("pommes"), NewItem("fromage"), NewItem("jus", "2 L")), items("des pommes, du fromage et deux litres de jus"))
        assertEquals(listOf(NewItem("oat milk")), items("oat milk"))
        assertEquals(listOf(NewItem("lait d'avoine")), items("du lait d'avoine"))
        assertEquals(listOf(NewItem("call the bank")), items("call the bank"))
        assertEquals(listOf(NewItem("appeler la banque")), items("appeler la banque"))
    }

    @Test fun cleanup() {
        assertEquals("oat milk", ItemText.clean("  oat \t milk.  "))
        assertEquals("eggs", ItemText.clean("eggs?!"))
        assertEquals("eggs and milk", ItemText.clean("eggs\nand milk"))
        assertEquals("receipt fdp.exe", ItemText.clean("receipt‮fdp.exe"))
        assertEquals(NewItem("oat milk"), one("some oat milk."))
        assertEquals(NewItem("eggs"), one("the eggs"))
        assertEquals(NewItem("apple"), one("an apple"))
        assertEquals(NewItem("banana"), one("a banana"))
        assertEquals(NewItem("œufs"), one("les œufs"))
        assertEquals(NewItem("pain"), one("le pain"))
        assertEquals(NewItem("lessive"), one("la lessive"))
        // LS4 (found by the desk run): « thé » folds to "the" but is no article.
        assertEquals(NewItem("thé vert"), one("thé vert"))
        assertEquals(NewItem("thé"), one("du thé"))
        assertEquals(NewItem("tea"), one("The tea"))
        assertEquals(NewItem("crème solaire"), one("de la crème solaire"))
        assertEquals(NewItem("huile d'olive"), one("de l'huile d'olive"))
        assertEquals(NewItem("eau pétillante"), one("l’eau pétillante"))
        assertEquals(NewItem("pomme"), one("une pomme"))
        assertEquals(NewItem("pain"), one("du pain"))
        // An article alone is kept as said: nothing would be left.
        assertEquals(NewItem("the"), one("the"))
        assertEquals(NewItem("a"), one("a"))
        // As shown: a capital first letter, unless the first word has one further in.
        assertEquals("Oat milk", ItemText.shown("oat milk"))
        assertEquals("Œufs", ItemText.shown("œufs"))
        assertEquals("iPhone cable", ItemText.shown("iPhone cable"))
        assertEquals("2% milk", ItemText.shown("2% milk"))
        assertTrue(ItemText.same("Oat milk", "oat-milk"))
        assertTrue(ItemText.same("Œufs", "oeufs"))
        assertFalse(ItemText.same("egg", "eggs"))
    }

    @Test fun quantitiesInEnglish() {
        assertEquals(NewItem("oat milk", "2 L"), one("2 litres of oat milk"))
        assertEquals(NewItem("avocados", "3"), one("3 avocados"))
        assertEquals(NewItem("avocados", "3"), one("three avocados"))
        assertEquals(NewItem("apple", "1"), one("one apple"))
        assertEquals(NewItem("eggs", "12"), one("a dozen eggs"))
        assertEquals(NewItem("eggs", "6"), one("half a dozen eggs"))
        assertEquals(NewItem("eggs", "24"), one("two dozen eggs"))
        assertEquals(NewItem("milk", "1 L"), one("a litre of milk"))
        assertEquals(NewItem("milk", "2 L"), one("2 liters milk"))
        assertEquals(NewItem("flour", "500 g"), one("500g of flour"))
        assertEquals(NewItem("flour", "1.5 kg"), one("1.5 kg of flour"))
        assertEquals(NewItem("butter", "2 lb"), one("two pounds of butter"))
        assertEquals(NewItem("cream", "250 mL"), one("250 ml of cream"))
        assertEquals(NewItem("cereal", "2 boxes"), one("two boxes of cereal"))
        assertEquals(NewItem("tomatoes", "1 can"), one("a can of tomatoes"))
        assertEquals(NewItem("beer", "12 pack"), one("12 pack of beer"))
        assertEquals(NewItem("milk", "2 L"), one("the 2 litres of milk"))
        assertEquals(NewItem("twelve"), one("twelve"))
        assertEquals(NewItem("2"), one("2"))
        assertEquals(NewItem("2 cans"), one("2 cans"))
        // A number, a word that isn't a unit, then "of": as said.
        assertEquals(NewItem("2 pints of milk"), one("2 pints of milk"))
        // Not quantities at all.
        for (s in listOf("2% milk", "AA batteries", "size 4 diapers", "WD-40", "B12 vitamins", "7up")) assertEquals(NewItem(s), one(s))
        // A number too long to be a count stays in the words.
        assertEquals(NewItem("1234567 widgets"), one("1234567 widgets"))
    }

    @Test fun quantitiesInQuebecFrench() {
        assertEquals(NewItem("jus", "2 L"), one("deux litres de jus"))
        assertEquals(NewItem("eau", "2 L"), one("deux litres d'eau"))
        assertEquals(NewItem("œufs", "12"), one("une douzaine d'œufs"))
        assertEquals(NewItem("œufs", "6"), one("une demi-douzaine d'œufs"))
        assertEquals(NewItem("pommes", "24"), one("deux douzaines de pommes"))
        assertEquals(NewItem("pommes", "3"), one("trois pommes"))
        assertEquals(NewItem("lait", "1 L"), one("un litre de lait"))
        assertEquals(NewItem("farine", "1,5 kg"), one("1,5 kg de farine"))
        assertEquals(NewItem("beurre", "2 lb"), one("deux livres de beurre"))
        assertEquals(NewItem("céréales", "2 boîtes"), one("deux boîtes de céréales"))
        assertEquals(NewItem("tomates", "1 canne"), one("une canne de tomates"))
        assertEquals(NewItem("yogourt", "1 pot"), one("un pot de yogourt"))
        assertEquals(NewItem("bières", "2 sacs"), one("deux sacs de bières"))
        // « pinte » isn't a unit Robot reads: as said (§2.3). « livres » with no « de » are books.
        assertEquals(NewItem("deux pintes de lait"), one("deux pintes de lait"))
        assertEquals(NewItem("livres", "2"), one("deux livres"))
        // A decimal comma isn't a separator.
        assertEquals(listOf(NewItem("farine", "1,5 kg"), NewItem("sucre")), items("1,5 kg de farine, du sucre"))
    }

    @Test fun theSplit() {
        assertEquals(listOf("eggs", "bread", "milk"), ItemText.split("eggs, bread, and milk"))
        assertEquals(listOf("eggs", "bread", "milk"), ItemText.split("eggs and bread and milk"))
        assertEquals(listOf("eggs", "bread"), ItemText.split("eggs; bread"))
        // Compounds stay one item, wherever they are.
        assertEquals(listOf("salt and pepper"), ItemText.split("salt and pepper"))
        assertEquals(listOf("eggs", "salt and pepper"), ItemText.split("eggs and salt and pepper"))
        assertEquals(listOf("some salt and pepper", "bread"), ItemText.split("some salt and pepper and bread"))
        assertEquals(listOf("mac and cheese", "bread"), ItemText.split("mac and cheese, bread"))
        assertEquals(listOf("fish and chips"), ItemText.split("fish and chips"))
        assertEquals(listOf("peanut butter and jam"), ItemText.split("peanut butter and jam"))
        assertEquals(listOf("du sel et poivre"), ItemText.split("du sel et poivre"))
        assertEquals(listOf("pain", "sel et poivre"), ItemText.split("pain et sel et poivre"))
        // "and" splits only when each side is three words or fewer, counted once the quantity and article are off.
        assertEquals(listOf("a big bag of chips and salsa"), ItemText.split("a big bag of chips and salsa"))
        assertEquals(listOf("bread", "2 litres of milk"), ItemText.split("bread and 2 litres of milk"))
        assertEquals(listOf("fromage", "deux litres de jus"), ItemText.split("fromage et deux litres de jus"))
        // A run with no separator stays one item (LS4's model may split it).
        assertEquals(listOf("eggs oat milk bread"), ItemText.split("eggs oat milk bread"))
        // A dangling "and" stays in the words.
        assertEquals(listOf("eggs and"), ItemText.split("eggs and"))
        assertEquals(listOf("eggs"), ItemText.split(",, eggs ,"))
    }

    @Test fun secretsAreRefused() {
        for (s in listOf("my PIN 4821", "eggs and my PIN 4821", "PIN 4821, eggs", "4111 1111 1111 1111", "door code 4821",
            "mot de passe Tr0ub4dor", "mon NIP 4821", "card number 4111111111111111", "046 454 286")) {
            assertEquals(s, ItemsRead.No(ItemProblem.SECRET), ItemText.read(s))
        }
        // The everyday items Lists must take: none of them is a secret.
        for (s in listOf("2% milk", "AA batteries", "size 4 diapers", "WD-40", "12 pack of beer", "B12 vitamins", "zip code labels",
            "call 514 555 0199 about the car", "room 1203 key", "2026 calendar", "dress code for Saturday")) {
            assertTrue(s, ItemText.read(s) is ItemsRead.Ok)
        }
        // A known cost of refusing (SecretDetector's words are generous): a to-do about a password is refused too.
        assertEquals(ItemsRead.No(ItemProblem.SECRET), ItemText.read("change the wifi password"))
        assertEquals(ItemProblem.SECRET, ItemText.check(NewItem("4821", "PIN")))
    }

    @Test fun theCaps() {
        assertEquals(20, items(List(20) { "item$it" }.joinToString(", ")).size)
        assertEquals(ItemsRead.No(ItemProblem.TOO_MANY), ItemText.read(List(21) { "item$it" }.joinToString(", ")))
        assertEquals(listOf(NewItem("e".repeat(120))), items("e".repeat(120)))
        assertEquals(ItemsRead.No(ItemProblem.TOO_LONG), ItemText.read("e".repeat(121)))
        assertEquals(ItemsRead.No(ItemProblem.EMPTY), ItemText.read("  . "))
        assertEquals(ItemsRead.No(ItemProblem.EMPTY), ItemText.read(""))
        assertEquals(ItemProblem.TOO_LONG, ItemText.check(NewItem("milk", "x".repeat(17))))
        assertEquals(null, ItemText.check(NewItem("milk", "x".repeat(16))))
        assertEquals(ItemProblem.EMPTY, ItemText.check(NewItem(" milk")))
        assertEquals(ItemProblem.EMPTY, ItemText.check(NewItem("milk\n")))
    }
}
