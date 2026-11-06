// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.Prompt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-124-recall, Test Bench bug-everyday-b-1 and -4 on 1.2.3 (Pixel 8, phone-1.2.3-c): the phone's own words. "where's my
 * car" went to the model with the note and got "I don't have access to your personal location or vehicle information"; the
 * follow-up "How many people live there?" got no figure.
 */
class WhereIsRecallTest {
    private val now = 1_762_300_000_000L
    private var n = 0

    private fun saved(said: String): MemoryItem {
        val c = MemoryCommands.parse(said) as MemoryCommand.Remember
        n++
        return MemoryItem("m_where%04d".format(n), c.text, c.kind, language = c.language, createdAt = now - 60_000 + n)
    }

    private fun answer(question: String, items: List<MemoryItem>, s: MemorySettings = MemorySettings(), locked: Boolean = false): String? {
        val r = MemoryRouting.route(question, s, locked) { MemorySearch.find(it.about, items, now).isNotEmpty() } as? MemoryRoute.Run ?: return null
        val c = r.command as? MemoryCommand.Recall ?: return null
        val found = MemorySearch.find(c.about, items, now)
        val fr = c.language == "fr"
        return MemoryReplies.recallText(c.about, found, 0, fr, c.parking).render(Langs.of(if (fr) Lang.FR else Lang.EN))
    }

    private val items by lazy {
        listOf("remember my favourite food is pad thai", "remember I parked the car on level 3, row B",
            "souviens-toi que j'ai garé la voiture au niveau 3").map(::saved)
    }

    @Test fun wheresMyCarIsAnsweredFromMemoryNotByTheModel() {
        for (q in listOf("where's my car", "Where's my car?", "where’s my car", "where is my car", "where did I park", "where did I park the car"))
            answer(q, items).let { assertTrue("$q -> $it", it != null && it.contains("level 3")) }
        answer("où est ma voiture", items).let { assertTrue("$it", it != null && it.contains("niveau 3")) }
        answer("Où j'ai garé la voiture?", items).let { assertTrue("$it", it != null && it.contains("niveau 3")) }
    }

    /** fix-124-recall-b: nothing saved. "where did I park" keeps Robot's reply and says how to save one; "where's my car" routes as in 1.2.3. */
    @Test fun nothingSavedParkingSaysHowAndWheresMyRoutesAsBefore() {
        val pad = listOf(saved("remember my favourite food is pad thai"))
        for (none in listOf(emptyList(), pad)) {
            assertEquals("I don't remember anything about where you parked. Tell me where you parked and I'll remember it.", answer("where did I park", none))
            assertEquals("I don't remember anything about the car. Tell me where you parked and I'll remember it.", answer("Where did I park the car?", none))
            assertEquals("Je ne me souviens de rien sur l'endroit où tu t'es garé. Dis-moi où tu t'es stationné et je vais m'en souvenir.",
                answer("où j'ai garé", none)?.replace('\u00A0', ' ')?.replace('\u202F', ' ')?.replace('\u2019', '\''))
            for (q in listOf("where's my car", "Where is my car?", "où est ma voiture", "where's my phone", "où est mon téléphone"))
                assertNull("$q with nothing saved", answer(q, none))
        }
        // As 1.2.3 routed them: no router, the model answers.
        for (q in listOf("where's my car", "où est ma voiture", "where's my phone"))
            assertNull(q, MemoryRouting.route(q, MemorySettings(), locked = false))
        assertNull(answer("where's my phone", items))
    }

    /** fix-124-recall-b: "where's my lease" is a Files question (bug: memory took it once "where's my car" was memory's). */
    @Test fun filesClaimFirst() {
        val lease = items + saved("remember my lease ends in June")
        for (q in listOf("where's my lease", "Where is my lease?", "where's my warranty", "où est mon bail"))
            assertNull(q, MemoryRouting.route(q, MemorySettings(), locked = false) { true })
        assertNull(answer("where's my lease", lease))
        assertTrue(app.heyrobot.core.files.FilesRouter.route("where's my lease") != null ||
            app.heyrobot.core.ToolGroup.FILES in app.heyrobot.core.ToolRouter.route("where's my lease"))
    }

    @Test fun otherQuestionsStillGoToTheModel() {
        val s = MemorySettings()
        for (q in listOf("Tell me a joke", "what time is it", "where is the Eiffel Tower", "where's my nearest pharmacy", "where's my phone",
            "what do I like to eat most", "How many people live there?", "quelle heure est-il", "où est la tour Eiffel"))
            assertNull(q, MemoryRouting.route(q, s, locked = false))
        assertNull(MemoryRouting.route("where's my car", s.copy(useInAnswers = false), locked = false))
        assertFalse(MemoryRouting.route("where's my car", s.copy(on = false), locked = false) is MemoryRoute.Run)
        assertFalse(MemoryRouting.route("where's my car", s.copy(onLockScreen = false), locked = true) is MemoryRoute.Run)
    }

    @Test fun aFollowUpAskingForAFigureGetsTheNote() {
        assertEquals(Prompt.FIGURE_NOTE, Prompt.figureNote("How many people live there?", followUp = true))
        assertEquals(Prompt.FIGURE_NOTE, Prompt.figureNote("Combien de gens vivent là?", followUp = true))
        assertEquals("", Prompt.figureNote("How many people live there?", followUp = false))
        for (q in listOf("Tell me a joke", "what time is it", "how many items are on my list", "combien j'ai de rendez-vous"))
            assertEquals(q, "", Prompt.figureNote(q, followUp = true))
    }
}
