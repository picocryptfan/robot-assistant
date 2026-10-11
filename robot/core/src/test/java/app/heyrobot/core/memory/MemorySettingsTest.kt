package app.heyrobot.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySettingsTest {
    private val d = MemorySettings()

    /** The design's defaults, with its open questions answered as recommended. */
    @Test fun defaults() {
        assertTrue(d.on && d.useInAnswers && d.transcripts)
        assertEquals(SuggestionStyle.QUIET_LIST, d.suggestions)
        assertFalse(d.onLockScreen)
        assertFalse(d.speakSecrets)
        assertEquals(HelperMemory.ONLY_WHEN_INCLUDED, d.helpers)
        assertFalse(d.fromContentNoCard)
        assertEquals(SearchModelOffer.NOT_OFFERED, d.searchModel)
    }

    @Test fun nothingOnTheLockScreen() {
        assertEquals(LockedMemory.USE, MemoryAccess.whenLocked(false, d, "When is Julie's birthday?"))
        assertEquals(LockedMemory.SKIP, MemoryAccess.whenLocked(true, d, "When is Julie's birthday?"))
        assertEquals(LockedMemory.SAY_UNLOCK, MemoryAccess.whenLocked(true, d, "Do you remember Julie's birthday?"))
        assertEquals(LockedMemory.SAY_UNLOCK, MemoryAccess.whenLocked(true, d, "Qu'est-ce que tu sais sur moi?"))
        assertEquals(LockedMemory.USE, MemoryAccess.whenLocked(true, d.copy(onLockScreen = true), "Do you remember?"))
        assertEquals(LockedMemory.SKIP, MemoryAccess.whenLocked(false, d.copy(on = false), "Do you remember?"))
        assertEquals("Unlock first: what you asked me to remember stays locked.", MemoryAccess.UNLOCK_FIRST)
        assertEquals("Open Settings › Memory to delete everything.", MemoryAccess.FORGET_ALL_BY_VOICE)
    }

    private val plain = MemoryItem("m_00000000aaaaaa", "Julie's birthday is May 3", createdAt = 1)
    private val secret = MemoryItem("m_00000000bbbbbb", "The door code is 4512", secret = true, createdAt = 1)

    @Test fun helpersGetMemoryOnlyThroughTheCard() {
        val given = listOf(plain, secret)
        // Default: nothing unless included, even for a trusted helper.
        assertTrue(MemoryAccess.forHelper(given, d, trusted = true).isEmpty())
        assertEquals(listOf(plain), MemoryAccess.forHelper(given, d, trusted = false, included = setOf(plain.id)))
        // A secret never goes, even included.
        assertTrue(MemoryAccess.forHelper(given, d, trusted = true, included = setOf(secret.id)).isEmpty())
        // "With helpers I trust": the non-secret items, to a trusted helper only.
        val trusting = d.copy(helpers = HelperMemory.TRUSTED_HELPERS)
        assertEquals(listOf(plain), MemoryAccess.forHelper(given, trusting, trusted = true))
        assertTrue(MemoryAccess.forHelper(given, trusting, trusted = false).isEmpty())
    }

    @Test fun contentNeedsACardAndMessagesAlways() {
        assertTrue(MemoryAccess.cardForContent(fromMessage = false, d))
        assertFalse(MemoryAccess.cardForContent(fromMessage = false, d.copy(fromContentNoCard = true)))
        assertTrue(MemoryAccess.cardForContent(fromMessage = true, d.copy(fromContentNoCard = true)))
    }

    @Test fun theSearchModelIsOfferedOnce() {
        assertTrue(MemoryAccess.offerSearchModel(d))
        for (s in listOf(SearchModelOffer.OFFERED, SearchModelOffer.DECLINED, SearchModelOffer.DOWNLOADED)) assertFalse(MemoryAccess.offerSearchModel(d.copy(searchModel = s)))
        assertFalse(MemoryAccess.offerSearchModel(d.copy(on = false)))
        assertTrue(SearchModel.TITLE.contains("165 MB"))
    }

    @Test fun secretsAreSpokenOnlyByNameAndWhenAllowed() {
        val q = "What's the door code?"
        assertTrue(MemoryAccess.speak(plain, q, d, locked = true))
        assertFalse(MemoryAccess.speak(secret, q, d, locked = false))
        val speaking = d.copy(speakSecrets = true)
        assertTrue(MemoryAccess.speak(secret, q, speaking, locked = false))
        assertFalse(MemoryAccess.speak(secret, q, speaking, locked = true))
        assertFalse(MemoryAccess.speak(secret, "what's up?", speaking, locked = false))
    }
}
