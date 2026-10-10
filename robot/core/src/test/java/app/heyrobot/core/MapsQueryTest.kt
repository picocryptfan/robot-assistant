package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Organic Maps is asked to search: the place, without "near me" (it searches around you already). */
class MapsQueryTest {
    @Test fun nearMeWordsAreLeftOut() {
        val cases = listOf(
            "pharmacy near me" to "pharmacy",
            "a pharmacy near me" to "pharmacy",
            "a pharmacy nearby" to "pharmacy",
            "an ATM nearby" to "ATM",
            "coffee, near me" to "coffee",
            "Coffee near me." to "Coffee",
            "the nearest gas station" to "gas station",
            "Nearest gas station" to "gas station",
            "closest pharmacy" to "pharmacy",
            "gas station closest to me" to "gas station",
            "a bakery around here" to "bakery",
            "pizza close by" to "pizza",
            "ATM near my location" to "ATM",
            "the nearest pharmacy near me" to "pharmacy",
            // "nearest … to me": the "to me" goes with "nearest".
            "the nearest pharmacy to me" to "pharmacy",
            "closest gas station to here" to "gas station",
            "the closest Tim Hortons to me" to "Tim Hortons",
            "nearest hospital to my location" to "hospital",
            // French: "la"/"le" stay (they can start a name), "une" goes.
            "pharmacie près de moi" to "pharmacie",
            "pharmacie pres de moi" to "pharmacie",
            "une pharmacie à proximité" to "pharmacie",
            "la pharmacie la plus proche" to "la pharmacie",
            "la pharmacie la plus proche de moi" to "la pharmacie",
            "la pharmacie la plus proche d'ici" to "la pharmacie",
            "le dépanneur le plus près" to "le dépanneur",
            "le dépanneur le plus proche" to "le dépanneur",
            "un dépanneur proche de moi" to "dépanneur",
            "l’hôpital le plus proche" to "l’hôpital",
            "boulangerie autour de moi" to "boulangerie",
        )
        for ((raw, want) in cases) assertEquals(raw, want, MapsQuery.clean(raw))
    }

    @Test fun namesKeepTheirArticles() {
        val cases = listOf(
            "The Keg near me" to "The Keg",
            "The Bay near me" to "The Bay",
            "The Brick nearby" to "The Brick",
            "La Baie près de moi" to "La Baie",
            "Les Halles près de moi" to "Les Halles",
            "La Belle Province près de moi" to "La Belle Province",
            "Le Château près de moi" to "Le Château",
            "L'Avenue near me" to "L'Avenue",
            "Des Moines nearby" to "Des Moines",
            "Any Questions Bar nearby" to "Any Questions Bar",
        )
        for ((raw, want) in cases) assertEquals(raw, want, MapsQuery.clean(raw))
    }

    @Test fun placesAndNamesStayAsTheyAre() {
        for (s in listOf("Central Station", "Nearby Café", "The Keg", "Restaurant near Central Park", "Gare Centrale", "1 rue de la Paix, Paris",
                "A&W", "Une Belle Journée")) {
            assertEquals(s, MapsQuery.clean(s))
        }
        // Nothing but the "near me" words: kept, so the search isn't empty.
        assertEquals("near me", MapsQuery.clean("near me"))
        assertEquals("nearby", MapsQuery.clean(" nearby "))
    }

    @Test fun directionsSayToTapRoute() {
        // Organic Maps can't route to a name: the card promises a search, then Route.
        val d = Action.MapsNavigate("Central Station").describe()
        assertTrue(d, d.contains("Search Organic Maps for “Central Station”"))
        assertTrue(d, d.contains("tap Route"))
        assertFalse(d, d.contains("directions to"))
        assertEquals("Search Organic Maps for “pharmacy”", Action.MapsSearch("pharmacy").describe())
    }
}
