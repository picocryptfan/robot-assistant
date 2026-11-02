// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.link.DeskEntry
import app.heyrobot.core.link.DeskState
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.LinkBackupCodec
import app.heyrobot.core.link.LinkBackupFile
import app.heyrobot.core.link.LinkBackupRead
import app.heyrobot.core.link.LinkBlockBackup
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.PlanState
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L12, D14: the LINKS category and links.json. What's in it, what can never be, and how it reads back. */
class BackupLinksTest {
    private val pairing = BackupFixtures.LINKS_PAIRING
    private fun plan(state: PlanState, mine: Boolean, at: Long = 1_800_000_000) =
        LinkPlan(LinkIds.newId(), pairing, mine, state, 1, LinkIds.newId(), EventFields("Dinner", listOf(1_800_003_600L), 90, null, null, null), at, at).also { it.cal = 42 }
    private fun xfer(state: XferState, at: Long = 5) = TransferView(LinkIds.newId(), pairing, true, state,
        FileOffer(LinkIds.newId(), "receipt.pdf", OfferKind.entries.first(), 10, "ab".repeat(32), OfferWhy.entries.first()), 1, at, at, false)
    private fun read(text: String) = (LinkBackupCodec.decode(text) as LinkBackupRead.Ok).file
    private fun whole() = LinkBackupCodec.encode(BackupFixtures.linksFile())

    @Test fun theCategoryIsAppendedWithItsNamesAndCaps() {
        // After the chain's last category, and it never moves: the voice log's bits and older readers' skip follow this order.
        assertEquals(BackupCategory.EVERYDAY.ordinal + 1, BackupCategory.LINKS.ordinal)
        assertEquals("links", BackupCategory.LINKS.id)
        assertTrue(BackupCategory.LINKS.defaultOn)
        assertEquals(RestoreProcess.MAIN, BackupCategory.LINKS.writer)
        assertFalse(BackupCategory.LINKS in BackupCategory.SENSITIVE)
        assertTrue(BackupCategory.LINKS in BackupCategory.ONLY_YOURS)
        assertTrue(BackupCategory.LINKS.validName("links.json"))
        assertTrue(BackupCategory.LINKS.validName("link_settings.json"))
        for (n in listOf("identity.key", "sessions.json", "incoming/a", "transfers/a", "links.json.bak", "robot_link_v1")) assertFalse(n, BackupCategory.LINKS.validName(n))
        assertEquals(LinkBackupCodec.MAX_BYTES.toLong(), BackupCategory.LINKS.maxBytes("links.json"))
    }

    /** What a pairing can hold is listed: no field for an identity's private half, a session, an onion key, a token or a client authorization. */
    @Test fun aBackupHasNoFieldForAnyKey() {
        val o = JSONObject(whole())
        assertEquals(setOf("v", "pairings", "blocked", "desk", "plans", "transfers", "folders", "groups"), o.keys().asSequence().toSet())
        val p = o.getJSONArray("pairings").getJSONObject(0)
        assertEquals(setOf("id", "mode", "created", "name", "caps", "identity", "push", "levels", "movedOut"), p.keys().asSequence().toSet())
        assertEquals(setOf("endpoint", "p256dh", "auth"), p.getJSONObject("push").keys().asSequence().toSet())
        // Nor can one be put in by hand: a file with one is refused whole.
        for (k in listOf("identityKey", "private", "session", "prekeys", "onionKey", "peerOnion", "peerOnionAuth", "myAuth", "tokenIn", "tokenOut", "verified", "preKey"))
            assertEquals(k, LinkBackupRead.Damaged, LinkBackupCodec.decode(JSONObject(whole()).also { it.getJSONArray("pairings").getJSONObject(0).put(k, "00") }.toString()))
        assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(JSONObject(whole()).also { it.getJSONArray("pairings").getJSONObject(0).getJSONObject("push").put("key", "00") }.toString()))
        assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(JSONObject(whole()).also { it.getJSONArray("desk").getJSONObject(0).put("plain", "6869") }.toString()))
        assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(JSONObject(whole()).also { it.getJSONArray("blocked").put(JSONObject().put("identity", "05".repeat(33)).put("label", "").put("at", 1).put("session", "00")) }.toString()))
    }

    @Test fun whatTheFileHoldsReadsBack() {
        val f = read(whole())
        val p = f.pairings.single()
        assertEquals(pairing, p.id)
        assertEquals("Sam", p.name)
        assertEquals(LinkLevel.AUTO, p.levels[LinkCapability.FREE_BUSY]) // Dropped on the way in by :link: RestoreSafety.linkLevels.
        assertEquals(LinkIds.BYTES, p.movedOut!!.size)
        assertEquals(16, p.push!!.auth.size)
        assertEquals("LinkPairingBackup(mode=${p.mode})", p.toString())
        assertFalse(f.toString().contains("Sam"))
        val b = LinkBackupFile(blocked = listOf(LinkBlockBackup(ByteArray(33) { 7 }, "Spam", 3)))
        assertEquals("Spam", read(LinkBackupCodec.encode(b)).blocked.single().label)
    }

    /** D14: no waiting contents, no Undo; nothing that names a file under incoming/ or transfers/. */
    @Test fun historyCarriesNoWaitingContents() {
        val waiting = DeskEntry("11".repeat(16), pairing, DeskWay.IN, 1, DeskState.HANDLED, 5, 6, undo = "cd".repeat(16), seen = false, plain = "secret".toByteArray())
        val line = LinkBackupCodec.line(waiting)
        assertNull(line.plain)
        assertNull(line.undo)
        assertTrue(line.seen)
        val text = LinkBackupCodec.encode(LinkBackupFile(desk = listOf(line)))
        assertEquals(setOf("id", "pairing", "type", "state", "at", "done"), JSONObject(text).getJSONArray("desk").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(text.contains(LinkBytes.hex("secret".toByteArray())))
        assertEquals(DeskState.HANDLED, read(text).desk.single().state)
        // A transfer under way, or one whose file waits in incoming/ for your yes, isn't history yet.
        for (s in XferState.entries) assertEquals(s.name, s.final, LinkBackupCodec.transfer(xfer(s)) != null)
        val done = read(LinkBackupCodec.encode(LinkBackupFile(transfers = listOf(xfer(XferState.SENT))))).transfers.single()
        assertEquals(XferState.SENT, done.state)
        for (s in XferState.entries.filter { !it.final }) {
            val bad = JSONObject(LinkBackupCodec.encode(LinkBackupFile())).put("transfers", JSONArray().put(LinkBytes.hex(TransferView.encodeAll(listOf(xfer(s))))))
            assertEquals(s.name, LinkBackupRead.Damaged, LinkBackupCodec.decode(bad.toString()))
        }
    }

    /** Another phone's calendar id never comes along; a plan waiting for your answer stays behind. */
    @Test fun plansComeWithoutCalendarIdsOrAnswersOwed() {
        val mine = LinkBackupCodec.plan(plan(PlanState.ACCEPTED, mine = false))!!
        assertEquals(0L, mine.cal)
        assertNull(LinkBackupCodec.plan(plan(PlanState.PROPOSED, mine = false)))
        assertTrue(LinkBackupCodec.plan(plan(PlanState.PROPOSED, mine = true)) != null)
        // And a hand-made file gets no further.
        val withCal = JSONObject(LinkBackupCodec.encode(LinkBackupFile())).put("plans", JSONArray().put(LinkBytes.hex(LinkPlan.encode(plan(PlanState.ACCEPTED, mine = true)))))
        assertEquals(0L, read(withCal.toString()).plans.single().cal)
        val owed = JSONObject(LinkBackupCodec.encode(LinkBackupFile())).put("plans", JSONArray().put(LinkBytes.hex(LinkPlan.encode(plan(PlanState.PROPOSED, mine = false)))))
        assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(owed.toString()))
    }

    @Test fun aRestoreNeverReadGoesAfterThirtyDays() {
        val day = 24 * 3600 * 1000L
        assertFalse(LinkBackupCodec.pendingTooOld(0, 30 * day))
        assertTrue(LinkBackupCodec.pendingTooOld(0, 30 * day + 1))
    }

    @Test fun aLaterFileWaitsAndABadOneIsRefused() {
        assertEquals(2, (LinkBackupCodec.decode("{\"v\":2,\"anything\":1}") as LinkBackupRead.TooNew).v)
        // L16: kept for the update that can read it; only a damaged one is thrown away.
        assertFalse(LinkBackupCodec.decode("{\"v\":2,\"anything\":1}").dropsFile)
        assertTrue(LinkBackupRead.Damaged.dropsFile)
        // A later key at the top level (a later job's history) is ignored by this version.
        assertEquals(1, read(JSONObject(whole()).put("ledger", JSONArray()).toString()).pairings.size)
        for (t in listOf(null, "", "[]", "{}", "{\"v\":0}", "{\"v\":\"1\"}", "{\"v\":1}", "not json")) assertEquals("$t", LinkBackupRead.Damaged, LinkBackupCodec.decode(t))
        fun edited(f: (JSONObject) -> Unit) = JSONObject(whole()).also { f(it.getJSONArray("pairings").getJSONObject(0)) }.toString()
        for (bad in listOf<(JSONObject) -> Unit>(
            { it.put("id", "nope") }, { it.put("identity", "05") }, { it.put("mode", 99) }, { it.put("levels", "zz") }, { it.put("movedOut", "00") },
            { it.put("created", -1) }, { it.put("preset", 99) }, { it.remove("name") }, { it.put("caps", "") },
        )) assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(edited(bad)))
        val twice = JSONObject(whole()).also { o -> o.getJSONArray("pairings").put(o.getJSONArray("pairings").getJSONObject(0)) }
        assertEquals(LinkBackupRead.Damaged, LinkBackupCodec.decode(twice.toString()))
    }

    /** Over the cap, the oldest history goes first; who you're paired with never does. */
    @Test fun aLargeFileDropsTheOldestHistory() {
        val desk = List(9_000) { DeskEntry(LinkIds.newId(), pairing, DeskWay.IN, 1, DeskState.HANDLED, it.toLong(), it.toLong(), seen = true) }
        val f = LinkBackupFile(BackupFixtures.linksFile().pairings, emptyList(), desk, emptyList(), emptyList())
        val fit = LinkBackupCodec.fit(f)
        assertTrue(LinkBackupCodec.encode(fit).toByteArray().size <= LinkBackupCodec.MAX_BYTES)
        assertEquals(1, fit.pairings.size)
        assertTrue(fit.desk.isNotEmpty() && fit.desk.size < desk.size)
        assertEquals(8_999L, fit.desk.maxOf { it.at })
        assertTrue(LinkBackupCodec.fit(BackupFixtures.linksFile()).desk.size == 1)
    }

    /** R8: a second restore adds to pairings the first left waiting; the newer copy wins; a file it can't read has nothing to keep. */
    @Test fun aSecondRestoreKeepsPairingsStillWaiting() {
        val all = BackupFixtures.linksFile().pairings
        val first = LinkBackupCodec.encode(LinkBackupFile(pairings = all))
        val second = LinkBackupCodec.encode(LinkBackupFile(pairings = all.take(1)))
        val merged = LinkBackupCodec.pendingOver(second, first)
        assertEquals(all.map { it.id }.toSet(), (LinkBackupCodec.decode(merged) as LinkBackupRead.Ok).file.pairings.map { it.id }.toSet())
        assertEquals(first, LinkBackupCodec.pendingOver(null, first))
        assertEquals(first, LinkBackupCodec.pendingOver("not json", first))
    }

}
