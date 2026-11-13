// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiIpcTest {
    private fun <T> round(write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val p = Parcel.obtain()
        try {
            p.write()
            p.setDataPosition(0)
            return p.read()
        } finally {
            p.recycle()
        }
    }

    @Test fun infoHitsAndArticleRoundTrip() {
        val i = WikiInfo(1_000_000, true, false, "Wikipedia", "eng", "2026-04-01")
        assertEquals(i, round({ WikiIpc.writeInfo(this, i) }) { WikiIpc.readInfo(this) })
        val h = listOf(WikiHit("Louis_Riel", "Louis Riel"), WikiHit("Riel_(disambiguation)", ""))
        assertEquals(h, round({ WikiIpc.writeHits(this, h) }) { WikiIpc.readHits(this) })
        val a = WikiArticle("Louis_Riel", "Louis Riel", "<p>Louis Riel</p>")
        assertEquals(a, round({ WikiIpc.writeArticle(this, a) }) { WikiIpc.readArticle(this) })
    }

    @Test fun outOfBoundsRepliesReadAsNothing() {
        assertNull(round({ writeInt(WikiReader.MAX_HITS + 1) }) { WikiIpc.readHits(this) })
        assertNull(round({ writeInt(1); writeString(""); writeString("t") }) { WikiIpc.readHits(this) })
        assertNull(round({ writeInt(1); writeString("p".repeat(WikiReader.MAX_PATH + 1)); writeString("t") }) { WikiIpc.readHits(this) })
        assertNull(round({ writeLong(-1); writeInt(0); writeInt(0); writeString(null); writeString(null); writeString(null) }) { WikiIpc.readInfo(this) })
        assertNull(round({ writeLong(1); writeInt(2); writeInt(0); writeString(null); writeString(null); writeString(null) }) { WikiIpc.readInfo(this) })
        assertNull(round({ writeString("p"); writeString("t"); writeString("") }) { WikiIpc.readArticle(this) })
    }

    @Test fun theServiceRefusesWhenNotIsolated() {
        // On the JVM (and in any process but an isolated :wiki), nothing of a call is read: the first check refuses it.
        val binder = Robolectric.buildService(WikiService::class.java).create().get().onBind(null) as Binder
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(WikiIpc.DESCRIPTOR)
            d.writeString("Louis Riel")
            d.writeInt(5)
            binder.transact(WikiIpc.TITLES, d, r, 0)
            r.setDataPosition(0)
            assertEquals(WikiIpc.REPLY_NOT_ISOLATED, r.readInt())
        } finally {
            d.recycle()
            r.recycle()
        }
    }
}
