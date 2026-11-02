// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.tts

import app.heyrobot.core.VoiceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The everyday set, E6 (everyday-set-plan.md §6.3): Robot's voices as Android's engine lists them. Heart and Kitten speak
 * English (US), Siwis French from France, so a Canadian French request is "language available, country not"; a voice
 * that isn't downloaded makes its language "missing data"; Robot's own English voice comes first.
 */
class EngineVoicesTest {
    private val all: (EngineVoice) -> Boolean = { true }
    private val none: (EngineVoice) -> Boolean = { false }
    private val kittenOnly: (EngineVoice) -> Boolean = { it == EngineVoice.KITTEN }

    @Test fun languagesInBothForms() {
        for (l in listOf("en", "eng", "EN", " eng ")) assertEquals(l, "eng", EngineVoices.lang(l))
        for (l in listOf("fr", "fra", "fre", "FRA")) assertEquals(l, "fra", EngineVoices.lang(l))
        for (l in listOf(null, "", "de", "deu", "es", "english")) assertNull(l, EngineVoices.lang(l))
        assertEquals("USA", EngineVoices.country("us"))
        assertEquals("CAN", EngineVoices.country("CA"))
        assertEquals("FRA", EngineVoices.country("fra"))
        assertEquals("", EngineVoices.country(null))
    }

    @Test fun whatEachLanguageAndCountryGets() {
        assertEquals(LangSupport.COUNTRY, EngineVoices.support("eng", "USA", all))
        assertEquals(LangSupport.LANGUAGE, EngineVoices.support("eng", "GBR", all))
        assertEquals(LangSupport.LANGUAGE, EngineVoices.support("eng", "CAN", all))
        assertEquals(LangSupport.LANGUAGE, EngineVoices.support("en", "", all))
        assertEquals(LangSupport.COUNTRY, EngineVoices.support("fra", "FRA", all))
        // Siwis is from France: Canadian French is the language, not the country.
        assertEquals(LangSupport.LANGUAGE, EngineVoices.support("fra", "CAN", all))
        assertEquals(LangSupport.LANGUAGE, EngineVoices.support("fr", "CA", all))
        assertEquals(LangSupport.NOT_SUPPORTED, EngineVoices.support("deu", "DEU", all))
        assertEquals(LangSupport.NOT_SUPPORTED, EngineVoices.support(null, null, all))
        // Not downloaded yet.
        assertEquals(LangSupport.MISSING_DATA, EngineVoices.support("eng", "USA", none))
        assertEquals(LangSupport.MISSING_DATA, EngineVoices.support("fra", "FRA", kittenOnly))
        assertEquals(LangSupport.COUNTRY, EngineVoices.support("eng", "USA", kittenOnly))
    }

    @Test fun whichVoiceSpeaks() {
        // Robot's own English voice first.
        assertEquals(EngineVoice.HEART, EngineVoices.pick(null, "eng", VoiceChoice.HEART, all))
        assertEquals(EngineVoice.KITTEN, EngineVoices.pick(null, "eng", VoiceChoice.KITTEN, all))
        assertEquals(EngineVoice.HEART, EngineVoices.pick(null, "eng", VoiceChoice.SYSTEM, all))
        assertEquals(EngineVoice.HEART, EngineVoices.pick(null, "eng", VoiceChoice.SIWIS, all))
        assertEquals(EngineVoice.KITTEN, EngineVoices.pick(null, "eng", VoiceChoice.HEART, kittenOnly))
        assertEquals(EngineVoice.SIWIS, EngineVoices.pick(null, "fra", VoiceChoice.KITTEN, all))
        // A voice the app named, when it's downloaded.
        assertEquals(EngineVoice.KITTEN, EngineVoices.pick("en-us-x-robot-kitten", "eng", VoiceChoice.HEART, all))
        assertEquals(EngineVoice.SIWIS, EngineVoices.pick("fr-fr-x-robot-siwis", "eng", VoiceChoice.HEART, all))
        assertEquals(EngineVoice.KITTEN, EngineVoices.pick("en-us-x-robot-heart", "eng", VoiceChoice.HEART, kittenOnly))
        assertEquals(EngineVoice.HEART, EngineVoices.pick("en-us-x-someone-else", "eng", VoiceChoice.HEART, all))
        // Another language is read by the English voice; nothing downloaded, nothing speaks.
        assertEquals(EngineVoice.HEART, EngineVoices.pick(null, "deu", VoiceChoice.HEART, all))
        assertNull(EngineVoices.pick(null, "fra", VoiceChoice.HEART, kittenOnly))
        assertNull(EngineVoices.pick(null, "eng", VoiceChoice.HEART, none))
        // Android's default name for a language, downloaded or not.
        assertEquals(EngineVoice.HEART, EngineVoices.default("eng", VoiceChoice.HEART, none))
        assertEquals(EngineVoice.KITTEN, EngineVoices.default("eng", VoiceChoice.HEART, kittenOnly))
        assertEquals(EngineVoice.SIWIS, EngineVoices.default("fr", VoiceChoice.HEART, none))
        assertNull(EngineVoices.default("deu", VoiceChoice.HEART, all))
    }

    @Test fun namesAreUniqueAndCheckDataListsLanguages() {
        assertEquals(3, EngineVoice.entries.map { it.voiceName }.toSet().size)
        assertEquals(listOf("eng-USA", "fra-FRA") to emptyList<String>(), EngineVoices.dataLists(all))
        assertEquals(listOf("eng-USA") to listOf("fra-FRA"), EngineVoices.dataLists(kittenOnly))
        assertEquals(emptyList<String>() to listOf("eng-USA", "fra-FRA"), EngineVoices.dataLists(none))
        assertEquals(EngineVoice.SIWIS, EngineVoices.byName("fr-fr-x-robot-siwis"))
        assertNull(EngineVoices.byName(null))
    }

    @Test fun speechRateBecomesTheVoicesSpeed() {
        assertEquals(1.0f, EngineVoices.speed(100), 0f)
        assertEquals(1.5f, EngineVoices.speed(150), 0f)
        assertEquals(2.0f, EngineVoices.speed(400), 0f)
        assertEquals(0.5f, EngineVoices.speed(20), 0f)
        assertEquals(1.0f, EngineVoices.speed(0), 0f)
        assertEquals(1.0f, EngineVoices.speed(-50), 0f)
    }

    @Test fun bucketsAndPace() {
        assertEquals(CharsBucket.UNDER_50, CharsBucket.of(0))
        assertEquals(CharsBucket.UNDER_200, CharsBucket.of(50))
        assertEquals(CharsBucket.UNDER_1000, CharsBucket.of(999))
        assertEquals(CharsBucket.LONGER, CharsBucket.of(4_000))
        assertEquals(LoadBucket.UNDER_1S, LoadBucket.of(999))
        assertEquals(LoadBucket.UNDER_10S, LoadBucket.of(3_000))
        assertEquals(LoadBucket.LONGER, LoadBucket.of(60_000))
        // A screen reader's requests: one line a minute per result, with how many ended that way.
        val pace = VoiceForAppPace()
        assertEquals(1, pace.note(VoiceForAppResult.SPOKEN, 0))
        assertNull(pace.note(VoiceForAppResult.SPOKEN, 1_000))
        assertNull(pace.note(VoiceForAppResult.SPOKEN, 59_999))
        assertEquals(1, pace.note(VoiceForAppResult.STOPPED, 2_000))
        assertEquals(3, pace.note(VoiceForAppResult.SPOKEN, 60_000))
        assertNull(pace.note(VoiceForAppResult.SPOKEN, 60_001))
        assertEquals(2, pace.note(VoiceForAppResult.SPOKEN, 200_000))
        // Ids are the voice log's: never reused.
        for (e in listOf(VoiceForAppResult.entries, CharsBucket.entries, LoadBucket.entries, VoiceForAppProcess.entries))
            assertEquals(e.size, e.map { it.id }.toSet().size)
    }
}
