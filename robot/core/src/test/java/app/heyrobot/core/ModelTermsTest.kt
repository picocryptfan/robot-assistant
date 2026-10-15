// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uncensored model's publisher asks two confirmations on its model card (an access form Hugging Face doesn't enforce):
 * Robot asks them as tick boxes before the download or import, and keeps that you ticked them (settings schema 12).
 */
class ModelTermsTest {
    @Test fun onlyTheUncensoredModelHasTermsAndTheyAreThePublishersWords() {
        val t = ModelTerms.UNCENSORED
        assertEquals(t, ModelTerms.of(BrainModel.UNCENSORED))
        for (m in BrainModel.entries - BrainModel.UNCENSORED) assertNull(m.name, ModelTerms.of(m))
        // Tied to the card at the pinned commit: a new pin is asked again.
        assertEquals("${BrainModel.UNCENSORED.repo}@${BrainModel.UNCENSORED.commit!!.take(8)}", t.id)
        assertEquals("olekk", t.publisher)
        assertTrue(BrainModel.UNCENSORED.publisher.startsWith(t.publisher))
        // The model card's two checkboxes (extra_gated_fields at a4eecccd), word for word.
        assertEquals(listOf("I use this for research / personal experimentation only", "I accept responsibility for the outputs and any downstream use"), t.ticks)
        assertTrue(t.prompt.startsWith("This is an ABLITERATED model: its trained refusal behavior has been removed"))
        assertTrue(t.prompt.contains("research and personal experimentation only") && t.prompt.endsWith("accept sole responsibility for any outputs and downstream use."))
        assertTrue(ModelTerms.ALSO.contains("Gemma Prohibited Use Policy"))
    }

    @Test fun bothBoxesBeforeTheDownloadAndOnceConfirmedItIsKept() {
        val t = ModelTerms.UNCENSORED
        assertFalse(t.complete(emptySet()))
        assertFalse(t.complete(setOf(0)))
        assertFalse(t.complete(setOf(1)))
        assertTrue(t.complete(setOf(0, 1)))
        val s = RobotSettings()
        assertEquals(t, ModelTerms.missing(s, BrainModel.UNCENSORED))
        for (m in listOf(BrainModel.FAST, BrainModel.SMARTER, BrainModel.OWN)) assertNull(ModelTerms.missing(s, m))
        val confirmed = ModelTerms.confirm(s, t, 1_760_000_000_000)
        assertNull(ModelTerms.missing(confirmed, BrainModel.UNCENSORED))
        assertEquals(mapOf(t.id to 1_760_000_000_000), confirmed.confirmedTerms)
        // Saved and read back (settings.json, schema 12).
        assertNull(ModelTerms.missing(RobotSettings.fromJson(confirmed.toJson().toString()), BrainModel.UNCENSORED))
        // Terms confirmed for another pin don't count for this one.
        assertEquals(t, ModelTerms.missing(s.copy(confirmedTerms = mapOf("olekk/gemma-4-E4B-it-abliterated-litert-lm@00000000" to 1L)), BrainModel.UNCENSORED))
    }
}
