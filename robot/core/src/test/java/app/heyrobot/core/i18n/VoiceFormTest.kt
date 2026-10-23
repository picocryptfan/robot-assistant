// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** §4.4, §6.4: no typography character, no quote and no "›" reaches the voice; sizes and percentages in words. */
class VoiceFormTest {
    private val nb = Fmt.NBSP
    private val fine = FrTypo.FINE

    @Test fun whatTheVoiceGets() {
        assertEquals("Dis hey robot.", VoiceForm.clean("Dis «${nb}hey robot$nb».", Lang.FR))
        assertEquals("Say hey robot.", VoiceForm.clean("Say “hey robot”.", Lang.EN))
        assertEquals("Répondre, suivant ou arrêter?", VoiceForm.clean("Répondre, suivant ou arrêter$fine?", Lang.FR))
        assertEquals("Efface des choses dans Réglages, Mémoire.", VoiceForm.clean("Efface des choses dans Réglages › Mémoire.", Lang.FR))
        assertEquals("Delete things in Settings, Memory.", VoiceForm.clean("Delete things in Settings › Memory.", Lang.EN))
        assertEquals("1,5 gigaoctet libre.", VoiceForm.clean("1,5${nb}Go libre.", Lang.FR))
        assertEquals("820 mégaoctets.", VoiceForm.clean("820${nb}Mo.", Lang.FR))
        assertEquals("3.1 gigabytes available of 16 gigabytes", VoiceForm.clean("3.1 GB available of 16 GB", Lang.EN))
        assertEquals("1 gigabyte", VoiceForm.clean("1 GB", Lang.EN))
        assertEquals("30 pour cent de risque de pluie.", VoiceForm.clean("30$nb% de risque de pluie.", Lang.FR))
        assertEquals("30 percent chance of rain.", VoiceForm.clean("30% chance of rain.", Lang.EN))
        // A Québec time is said in words: espeak reads « 7 h 02 » as « sept ache zéro deux » (tools/voice_phonemes.sh).
        assertEquals("Il est 7 heures 2.", VoiceForm.clean("Il est 7${nb}h${nb}02.", Lang.FR))
        // English sentences reach the voice as written (Phrase.said); the voice fixes their times (SherpaTextToSpeech).
        assertEquals("It's 7:02.", VoiceForm.clean("It's 7:02.", Lang.EN))
    }

    @Test fun frenchClockTimes() {
        fun fr(s: String) = VoiceForm.clean(s, Lang.FR)
        // Robot's own form (Fmt.clock, no-break spaces), the model's and a person's.
        assertEquals("Il est 7 heures 2.", fr("Il est 7 h 02."))
        assertEquals("Rendez-vous à 12 heures 30.", fr("Rendez-vous à 12${nb}h${nb}30."))
        assertEquals("Rendez-vous à 12 heures 30.", fr("Rendez-vous à 12:30."))
        assertEquals("Rendez-vous à 12 heures 30.", fr("Rendez-vous à 12h30."))
        assertEquals("Le souper est à 18 heures.", fr("Le souper est à 18h."))
        assertEquals("Il est vingt et une heures.", fr("Il est 21${nb}h."))
        assertEquals("Il est une heure.", fr("Il est 1 h."))
        assertEquals("À 13 heures vingt et une.", fr("À 13 h 21."))
        assertEquals("À 19 heures 5.", fr("À 19:05."))
        assertEquals("À 9 heures.", fr("À 09:00."))
        // Midnight and after: « minuit 15 », as people say it, not « zéro heure quinze ».
        assertEquals("À minuit 15.", fr("À 0${nb}h${nb}15."))
        assertEquals("À minuit.", fr("À 0:00."))
        // « 12 h » with no minutes may be a duration (« 12 h de route »): « 12 heures », never « midi » here.
        assertEquals("À 12 heures.", fr("À 12 h."))
        assertEquals("12 heures de route.", fr("12 h de route."))
        // Ranges, with « à » or a dash.
        assertEquals("De 9 heures à 10 heures 30.", fr("De 9${nb}h à 10${nb}h${nb}30."))
        assertEquals("De 9 heures à 10 heures 30.", fr("De 9 h–10 h 30."))
        assertEquals("Ouvert 9 heures à 17 heures.", fr("Ouvert 9:00-17:00."))
        // Durations Robot writes (core ConversationCapture, FreeUp): « 1 h 30 », « il y a 2 h », « 48 h ».
        assertEquals("Ça dure une heure 30.", fr("Ça dure 1 h 30."))
        assertEquals("Il y a 2 heures.", fr("Il y a 2 h."))
        assertEquals("Dans les 48 heures.", fr("Dans les 48 h."))
    }

    @Test fun whatTheFrenchClockLeavesAlone() {
        val same = listOf(
            "3,5 km", "1,5 Go", "version 1.1", "Appelle le 514 555-1234.", "Appelle le 514 555 1234.", "Il y a 12 habitants.",
            "Le A12 h est fermé.", "12 hommes", "Il a fait 1,5 h de route.", "Le temps : 1:30:00.", "Format 16:9.",
            "Il est 7 heures 2.", "minuit 15", "Le 24/7 h", "À 25:00.", "À 7:75.", "Score 3:2.", "La RN12 h", "100 km/h",
            "Il est 12 heures 30.", "vingt et une heures",
        )
        for (s in same) assertEquals(s, VoiceForm.clock(s, Lang.FR))
        // What clean adds is only sizes in words: the clock leaves « 1,5 Go » to them.
        assertEquals("3,5 km", VoiceForm.clean("3,5${nb}km", Lang.FR))
        assertEquals("1,5 gigaoctet", VoiceForm.clean("1,5${nb}Go", Lang.FR))
        assertEquals("version 1.1", VoiceForm.clean("version 1.1", Lang.FR))
        assertEquals("Appelle le 514 555-1234.", VoiceForm.clean("Appelle le 514 555-1234.", Lang.FR))
    }

    @Test fun englishClockTimes() {
        fun en(s: String) = VoiceForm.clock(s, Lang.EN)
        // Kokoro's front end turns ":" into ",", and English espeak reads "7,02" as "seven zero two".
        assertEquals("It's 7 oh 2.", en("It's 7:02."))
        assertEquals("At 9 oh 5 p.m.", en("At 9:05 p.m."))
        assertEquals("At 7 o'clock.", en("At 7:00."))
        assertEquals("At 7 p.m.", en("At 7:00 PM."))
        assertEquals("At 19 hundred.", en("At 19:00."))
        assertEquals("At 19 oh 5.", en("At 19:05."))
        assertEquals("At midnight.", en("At 0:00."))
        assertEquals("At 12:15 a.m.", en("At 00:15."))
        assertEquals("At 7:30.", en("At 07:30."))
        // "twelve thirty p m", "nineteen thirty": already right.
        for (s in listOf("At 12:30 p.m.", "At 19:30.", "At 10:45 a.m.", "Lunch at 12:30.", "John 3:16", "It's 1:30:00.", "version 1.1",
            "It's 1.5 GB.", "Call 514-555-1234.", "At 13:00 p.m.", "7 oh 2", "noon"))
            assertEquals(s, en(s))
    }

    @Test fun clockTimesChangeOnce() {
        val samples = listOf("Il est 7${nb}h${nb}02.", "À 12:30.", "De 9 h–10 h 30.", "À 0 h 15.", "À 1 h 01.", "À 21 h 31.")
        for (s in samples) {
            val once = VoiceForm.clean(s, Lang.FR)
            assertEquals(once, VoiceForm.clean(once, Lang.FR))
            assertEquals(once, VoiceForm.clock(once, Lang.FR))
        }
        for (s in listOf("At 7:02.", "At 7:00 PM.", "At 0:15.", "At 19:00.")) VoiceForm.clock(s, Lang.EN).let { assertEquals(it, VoiceForm.clock(it, Lang.EN)) }
    }

    @Test fun noTypographyCharacterLeft() {
        val samples = listOf("Note$nb: « une liste »$fine; c’est tout$fine!", "Réglages › Mémoire › Importer", "“Quoted” and «${nb}cité$nb»",
            "1${nb}234${nb}Mo", "Paramètres › Applications › Robot", "À 7${nb}h${nb}02$nb!")
        for (s in samples) for (lang in Lang.entries) {
            val out = VoiceForm.clean(s, lang)
            assertFalse(out, Regex("[\u00A0\u202F«»“”›]").containsMatchIn(out))
            assertEquals(out, VoiceForm.clean(out, lang))
        }
    }
}
