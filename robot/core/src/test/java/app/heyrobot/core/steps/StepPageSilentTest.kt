// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import app.heyrobot.core.read.StepRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-train-14 (phone-train-14, step-by-step 4): with the steps open, "how much vanilla" on a page that never names it got
// the model's guess; it says the page doesn't say. The four that passed on the phone keep their routes.
class StepPageSilentTest {
    private val recipe = "Traintest Recipe\n\n1. Heat the oven to 350°F.\n2. Mix 2 cups flour and 1 cup butter.\n3. Bake 12 to 15 minutes."

    @Test fun aPageQuestionThePageDoesNotAnswerSaysSo() {
        for (q in listOf("how much vanilla", "How much vanilla?", "so how much vanilla do I need", "how many eggs", "combien de vanille")) {
            assertNull(q, StepRouter.during(q, recipe, null))
            assertTrue(q, StepRouter.silent(q))
        }
    }

    @Test fun questionsNotAboutThePageKeepTheirRoutes() {
        for (q in listOf("how much battery do I have left", "how long until Christmas", "what's the temperature outside",
                "how many grams in a cup", "how long is left on my timer", "set a timer for 25 minutes", "what time is it",
                "what's the weather", "next", "add butter to the list", "how many days until November 20"))
            assertFalse(q, StepRouter.silent(q))
    }

    /** fix-routing-1.4 (review-routing-1.4 L1): with the steps open, a trip, the maps, an app or a time from now isn't a
     *  page question in [StepRouter.during] either, even on a page whose words answer it; the page's own still are. */
    @Test fun duringKeepsTheSameRoutesAsSilent() {
        val page = "Store run\n\n1. Walk to the store on Main Street.\n2. Which way to the store: turn left at the lights.\n" +
            "3. How far is it to the store: about 2 km.\n4. Open maps if you get lost.\n5. Wait until the store opens at 9.\n6. Buy 2 cups flour."
        for (q in listOf("which way to the store", "how far is it to the store", "how far is it", "open maps", "how long until the store opens"))
            assertNull(q, StepRouter.during(q, page, null))
        assertEquals(app.heyrobot.core.read.StepSay(null, "how much flour"), StepRouter.during("how much flour", page, null))
    }

    /** fix-routing-1.4-b (review-fix-routing-1.4 M1): with the steps open, a light, a place or "until" the page itself
     *  answers stays on the page; a real flashlight, map or timer ask still leaves it. */
    @Test fun duringKeepsThePagesOwnLightPlaceAndUntil() {
        val page = "Shelf and cookies\n\n1. Cream 1 cup light brown sugar with the butter.\n2. Bake until golden, about 12 minutes.\n" +
            "3. Hold the reset button on the back for 5 seconds.\n4. Turn the screw in the direction of the arrow.\n" +
            "5. Buy screws at the hardware store.\n6. Turn on the light above the bench."
        val fr = "Étagère\n\n1. Visse la vis B dans le trou du panneau.\n2. Chauffe l'eau jusqu'à ce que ça bouille, environ 8 minutes."
        for ((q, p) in listOf("how much light brown sugar" to page, "how long do I bake it until golden" to page,
                "where is the reset button" to page, "which direction does the screw turn" to page,
                "où est la vis B" to fr, "combien de temps jusqu'à ce que ça bouille" to fr))
            assertEquals(q, app.heyrobot.core.read.StepSay(null, q), StepRouter.during(q, p, null))
        for (q in listOf("turn on the flashlight", "directions to the hardware store", "set a timer until 5",
                "where is the nearest hardware store", "how long until the store opens"))
            assertNull(q, StepRouter.during(q, page, null))
    }

    /** fix-routing-1.4-c (review-fix-routing-1.4-b M1): a place the page names after at/to or à/au/chez is a real place. */
    @Test fun aPlaceThePageNamesStillGoesToMaps() {
        assertNull(StepRouter.during("where is the hardware store", shelf, null))
        assertNull(StepRouter.during("où est l'épicerie", "Pain\n\n1. Achète la levure à l'épicerie.\n2. Mélange la levure et l'eau.", null))
        assertEquals(app.heyrobot.core.read.StepSay(null, "where is the reset button"), StepRouter.during("where is the reset button", shelf, null))
    }

    /** review-fix-routing-1.4-b L1: "where's" folds to "where s". */
    @Test fun wheresTheResetButtonIsThePages() {
        for (q in listOf("where's the reset button", "wheres the reset button"))
            assertEquals(q, app.heyrobot.core.read.StepSay(null, q), StepRouter.during(q, shelf, null))
    }

    /** review-fix-routing-1.4-b L3: routed-away asks a line answers, so they reach leavesThePage (a stub to false fails). */
    @Test fun answeredAsksThatStillLeave() {
        val more = shelf + "\n7. Plug it into the nearest outlet, this way up."
        for (q in listOf("where is the nearest hardware store", "which way to the hardware store", "where is the hardware store")) {
            assertTrue(q, StepAsk.answer(more, q) != null)
            assertNull(q, StepRouter.during(q, more, null))
        }
    }

    /** review-fix-routing-1.4-b L2: "how long until golden" is the page's when the line names a time. */
    @Test fun howLongUntilGoldenIsThePages() {
        assertEquals(app.heyrobot.core.read.StepSay(null, "how long until golden"), StepRouter.during("how long until golden", shelf, null))
    }

    /** The review's 20 asks (review-fix-routing-1.4-b), run through the router: 8 stay on the page plus the 2 it got
     *  wrong (L1, L2), 8 leave plus the 2 it got wrong (M1). */
    @Test fun theReviewsTwentyAsks() {
        val fr = "Pain\n\n1. Cuis au four à 180 °C pendant 25 minutes jusqu'à ce que ce soit doré.\n2. Achète la levure à l'épicerie."
        val stay = listOf("how long do I bake it" to shelf, "where is the reset button" to shelf,
            "how long do I hold the reset button" to shelf, "which light do I turn on" to shelf, "où est la vis B" to vis,
            "combien de temps au four" to fr, "quelle température pour le four" to fr,
            "combien de temps jusqu'à ce que ce soit doré" to fr, "where's the reset button" to shelf, "how long until golden" to shelf)
        val leave = listOf("turn on the flashlight" to shelf, "where is the nearest pharmacy" to shelf,
            "set a timer for 12 minutes" to shelf, "how long is left on my timer" to shelf, "open the camera" to shelf,
            "allume la lampe de poche" to fr, "où est la pharmacie la plus proche" to fr, "how long until the store opens" to shelf,
            "where is the hardware store" to shelf, "où est l'épicerie" to fr)
        val wrong = stay.filter { (q, p) -> StepRouter.during(q, p, null) != app.heyrobot.core.read.StepSay(null, q) }.map { it.first } +
            leave.filter { (q, p) -> StepRouter.during(q, p, null) != null }.map { it.first }
        println("review's 20 asks: ${20 - wrong.size} of 20 right")
        assertEquals(emptyList<String>(), wrong)
    }

    private val shelf = "Shelf and cookies\n\n1. Cream 1 cup light brown sugar with the butter.\n2. Bake until golden, about 12 minutes.\n" +
        "3. Hold the reset button on the back for 5 seconds.\n4. Turn the screw in the direction of the arrow.\n" +
        "5. Buy screws at the hardware store.\n6. Turn on the light above the bench."
    private val vis = "Étagère\n\n1. Visse la vis B dans le trou du panneau.\n2. Chauffe l'eau jusqu'à ce que ça bouille, environ 8 minutes."

    /** review-batch-14-rest's M3: a trip, the maps, an app or the flashlight keep their routes with steps open. */
    @Test fun tripsAppsAndTheFlashlightKeepTheirRoutes() {
        for (q in listOf("how long to drive to Moncton", "combien de temps pour aller à Moncton", "which way to the pharmacy",
                "how do I get to Moncton", "where is the nearest pharmacy", "open Maps", "turn on the flashlight", "allume la lampe de poche"))
            assertFalse(q, StepRouter.silent(q))
        // The page's own questions still say so.
        assertTrue(StepRouter.silent("how much vanilla"))
    }

    /** review-batch-14-rest-b: "which way", "drive" and "how far is" without a destination are the steps' own questions. */
    @Test fun aStepQuestionWithoutADestinationIsThePages() {
        for (q in listOf("which way does the blade go", "how many turns to drive the screw in",
                "which shelf, and how far is it from the element"))
            assertTrue(q, StepRouter.silent(q))
        for (q in listOf("how long is the drive to Moncton", "how many minutes driving to Moncton", "which way to the station",
                "how far is it to Moncton"))
            assertFalse(q, StepRouter.silent(q))
    }

    /** review-batch-17-18 M1: "to", "from" and French « a » (the verb) mark no place: these lines are the page's. */
    @Test fun toFromAndTheVerbAAreNoPlace() {
        val page = "Desk\n\n1. Connect the cable to the port.\n2. Remove the screws from the base."
        val fr = "Bureau\n\n1. Le boîtier a un port USB.\n2. Range la boîte au garage.\n3. Achète la levure à l'épicerie."
        // review-batch-19 M1: « au garage » is your own garage (a maps "garage" lists repair shops), as "in the garage" is.
        for ((q, p) in listOf("where is the port" to page, "where is the base" to page, "où est le port USB" to fr, "où est le garage" to fr))
            org.junit.Assert.assertNotNull(q, StepRouter.during(q, p, null))
        for (q in listOf("où est l'épicerie")) assertNull(q, StepRouter.during(q, fr, null))
    }

    /** review-batch-19 M1: à/au/aux also mean "to" and "in": a step's own thing stays on the page; a name, a shop's kind
     *  or chez is a real place. */
    @Test fun frenchAuIsAPlaceOnlyBeforeANameOrAShop() {
        val fr = "Bureau\n\n1. Branche le câble au port USB.\n2. Visse la patte à la base.\n3. Mets les vis aux trous du haut.\n" +
            "4. Apporte le reçu chez le dentiste.\n5. Achète des vis au Canadian Tire.\n6. Prends du lait à la pharmacie."
        for (q in listOf("où est le port USB", "où est la base", "où est le trou du haut"))
            assertEquals(q, app.heyrobot.core.read.StepSay(null, q), StepRouter.during(q, fr, null))
        for (q in listOf("où est le dentiste", "où est le Canadian Tire", "où est la pharmacie"))
            assertNull(q, StepRouter.during(q, fr, null))
    }
}
