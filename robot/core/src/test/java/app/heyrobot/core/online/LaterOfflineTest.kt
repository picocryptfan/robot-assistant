// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.web.WebGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// fix-later-ready-offline: a question that needs your OK is never sent, nor taken off the queue, while there is no
// connection; it stays listed ("what is waiting") until a show with one, in English and in French.
class LaterOfflineTest {
    private fun ready(id: String, said: String, lang: Lang, at: Long) = LaterItem(id, said, "$id times", lang, at, true, LaterState.READY)

    private val en = ready("ferry", "Look up ferry times to Digby", Lang.EN, 1)
    private val fr = ready("traversier", "Cherche l'horaire du traversier pour Digby", Lang.FR, 2)

    @Test fun noConnectionSendsNothingAndBothStayListed() {
        for (items in listOf(listOf(en), listOf(fr), listOf(en, fr))) {
            val send = Later.toSend(items, online = false) { LaterGate.Asks }
            assertNull(send.item)
            assertEquals(emptySet<String>(), send.drop)
            assertEquals(items, Later.waiting(Later.drop(items, send.drop)))
        }
    }

    @Test fun backOnlineTheOldestGoesFirst() {
        assertEquals(Later.Send(setOf("ferry"), en), Later.toSend(listOf(fr, en), online = true) { LaterGate.Asks })
        assertEquals(Later.Send(setOf("traversier"), fr), Later.toSend(listOf(fr), online = true) { LaterGate.Goes })
    }

    @Test fun webSearchOffStillDropsWithNoConnectionAndSendsNothing() {
        val send = Later.toSend(listOf(en, fr), online = false) { if (it.lang == Lang.FR) LaterGate.Off(WebGate.OFF_TURN_ON) else LaterGate.Asks }
        assertEquals(Later.Send(setOf("traversier"), null), send)
        assertEquals(listOf(en), Later.waiting(Later.drop(listOf(en, fr), send.drop)))
    }
}
