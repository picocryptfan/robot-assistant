// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** A Phrase across processes: the id and its arguments, rendered by the receiver; an unknown id shows the sender's English. */
class PhraseWireTest {
    private object W : Strings("wire", area = "test") {
        val every = s("every", "{n} at {t} on {d}, {size}, {sub}, {where}, {android}, {x}.", "{n} à {t} le {d}, {size}, {sub}, {where}, {android}, {x}.")
        val sub = s("sub", "Done.", "Fait.")
    }

    private val known: (String) -> Msg? = { id -> W.all.find { it.id == id } ?: Catalogs.msg(id) }

    @Test fun roundTrip() {
        val p = W.every("n" to 3, "t" to LocalTime.of(7, 2), "d" to LocalDate.of(2026, 10, 12), "size" to Arg.Bytes(1_500_000_000),
            "sub" to W.sub(), "where" to UiStrings.inSettings(UiStrings.memory), "android" to AndroidPath.APP_PERMISSIONS, "x" to "Marie")
        val back = PhraseWire.fromJson(JSONObject(PhraseWire.encode(p)), known)!!
        for (l in listOf(Langs.EN, Langs.of(Lang.FR), Langs(Lang.FR, Lang.EN, Lang.FR, Medium.VOICE))) assertEquals(p.render(l), back.render(l))
        val list = Phrase.Join(listOf(Phrase.ListOf(listOf(Phrase.plain("a"), W.sub()), ListKind.OR), Phrase.plain("b")), " · ")
        assertEquals(list.render(Langs.of(Lang.FR)), PhraseWire.fromJson(PhraseWire.toJson(list), known)!!.render(Langs.of(Lang.FR)))
    }

    @Test fun anUnknownIdFallsBackToTheSendersEnglish() {
        val o = PhraseWire.toJson(W.sub())
        assertEquals("Done.", PhraseWire.fromJson(o) { null }!!.render(Langs.of(Lang.FR)))
        assertEquals("Done.", PhraseWire.fromJson(o.put("id", "wire.from_a_newer_robot"), known)!!.render(Langs.of(Lang.FR)))
    }

    @Test fun notAPhrase() {
        assertNull(PhraseWire.decode("not json"))
        assertNull(PhraseWire.decode("{}"))
        assertNull(PhraseWire.decode(null))
    }
}
