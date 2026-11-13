// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.read.ReadAloud.Said
import org.junit.Assert.assertEquals
import org.junit.Test

/** P1 step 3: the kind first, the words in their own language, "[unclear]" said plainly, English offered last. */
class ReadSpokenTest {
    private fun said(reply: String, you: Lang): List<String> = ReadAloud.spoken(ReadAloud.parse(reply), you).map {
        when (it) {
            is Said.Robot -> "R:" + it.phrase.render(Langs.of(you))
            is Said.Words -> "W(${it.lang}):" + it.text
        }
    }

    @Test fun theKindThenTheWordsThenTheOffer() {
        assertEquals(listOf("R:A letter.", "W(en):Dear Sam,\nYour parcel is ready."), said("letter en\nDear Sam,\nYour parcel is ready.", Lang.EN))
        assertEquals(listOf("R:A sign.", "W(fr):Défense de stationner", "R:Want it in English?"), said("sign fr\nDéfense de stationner", Lang.EN))
        assertEquals(listOf("R:Un panneau.", "W(en):No parking", "R:Tu le veux en français\u202F?"), said("Sign EN\nNo parking", Lang.FR))
        assertEquals(listOf("R:Une étiquette.", "W(fr):Sucre 2 kg"), said("label fr\nSucre 2 kg", Lang.FR))
        // "other" says no kind; a first line that isn't a kind and a language is the words, offered nothing.
        assertEquals(listOf("W(es):Hola", "R:Want it in English?"), said("other es\nHola", Lang.EN))
        assertEquals(listOf("W(null):Exit only\nThis way"), said("Exit only\nThis way", Lang.EN))
        assertEquals(listOf("W(null):dragon en\nHi"), said("dragon en\nHi", Lang.EN))
    }

    @Test fun unclearIsSaidAsICantReadThisPart() {
        assertEquals(listOf("R:A receipt.", "W(en):Total", "R:I can't read this part.", "W(en):thank you"),
            said("receipt en\nTotal [unclear] thank you", Lang.EN))
        assertEquals(listOf("R:Une note.", "R:Je ne peux pas lire ce passage.", "W(fr):à demain"), said("note fr\n[UNCLEAR] à demain", Lang.FR))
        // No words at all.
        assertEquals(listOf("R:A label.", "R:I don't see any words in it."), said("label en", Lang.EN))
        assertEquals(listOf("R:I don't see any words in it."), said("", Lang.EN))
    }
}
