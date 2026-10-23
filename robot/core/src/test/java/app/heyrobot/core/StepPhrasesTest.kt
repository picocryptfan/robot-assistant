// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.car.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The step parser routines and skills share (1.1, moved out of RoutineRouter; RoutineRouterTest keeps its routines' cases):
 * the route phrases give maps_navigate home or work, never open_app (D4), and texts in Québec French.
 */
class StepPhrasesTest {
    private fun tool(name: String, vararg args: Pair<String, Any?>): RoutineStep =
        RoutineStep.Tool((ToolCalls.parse(RawToolCall(name, args.toMap())) as ParsedCall.Ok).action)

    private fun steps(text: String) = StepPhrases.steps(text).steps

    private val home = tool("maps_navigate", "destination" to "home")
    private val work = tool("maps_navigate", "destination" to "work")

    @Test fun theRouteHomeOrToWork() {
        for (t in listOf("open the route home", "the route home", "route home", "directions home", "navigate home", "take me home", "show me the route home",
                "ouvre l'itinéraire vers la maison", "l'itinéraire vers la maison", "ramène-moi à la maison", "ramène-moi chez nous"))
            assertEquals(t, listOf(home), steps(t))
        for (t in listOf("open the route to work", "directions to work", "take me to work", "navigate to work", "l'itinéraire vers le travail",
                "ouvre l'itinéraire vers le bureau", "amène-moi au travail"))
            assertEquals(t, listOf(work), steps(t))
        // Never an app called "route home".
        assertEquals(listOf(tool("open_app", "name" to "camera")), steps("open the camera"))
        assertEquals(listOf(tool("maps_navigate", "destination" to "the library")), steps("directions to the library"))
    }

    @Test fun theTeachingSentencesOfTheTestBench() {
        // SK1 and SK2.
        assertEquals(listOf(tool("send_text", "to" to "Sam", "message" to "on my way home"), home), steps("text Sam on my way home, then open the route home"))
        assertEquals(listOf(tool("send_text", "to" to "Sam", "message" to "I'm on my way"), home), steps("text Sam I'm on my way, then open the route home"))
        // SK4, with « pis » between two actions.
        assertEquals(listOf(tool("send_text", "to" to "Sam", "message" to "je m'en viens"), home),
            steps("texte à Sam que je m'en viens, pis ouvre l'itinéraire vers la maison"))
        assertEquals(listOf(tool("send_text", "to" to "Marie", "message" to "les pâtes sont prêtes")), steps("écris à Marie pour lui dire que les pâtes sont prêtes"))
        // SK3's first step.
        assertEquals(listOf(tool("set_timer", "seconds" to 720)), steps("set a timer for 12 minutes"))
    }

    @Test fun aPartNotUnderstoodIsNamedNeverGuessed() {
        val a = StepPhrases.steps("turn on the flashlight, then make it cozy")
        assertEquals(listOf(tool("flashlight", "on" to true)), a.steps)
        assertEquals(listOf("make it cozy"), a.unparsed)
        // « texte » without « que » is not a text yet.
        assertEquals(emptyList<RoutineStep>(), steps("texte Sam"))
    }

    @Test fun savedPlaceWordsExactlyInEnglishAndFrench() {
        for (w in listOf("home", "Home", "my home", "My home.", "la maison", "chez nous")) assertEquals(w, NavDestination.Home, SavedPlace.of(w))
        for (w in listOf("work", "my work", "le travail", "le bureau")) assertEquals(w, NavDestination.Work, SavedPlace.of(w))
        for (w in listOf("home depot", "my home town", "maison", "the office", "work out", "homes")) assertNull(w, SavedPlace.of(w))
        // The card says what opens.
        assertEquals("Open the route home in Organic Maps", Action.MapsNavigate("home").describe())
        assertEquals("Search Organic Maps for “home depot”, then tap Route", Action.MapsNavigate("home depot").describe())
    }
}
