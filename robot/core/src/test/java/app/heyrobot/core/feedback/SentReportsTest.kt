// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RP5, Reports you sent: the reference, date, kind and parts round-trip, the last 50 only, newest first, never contents. */
class SentReportsTest {
    private fun r(n: Int, kind: FeedbackKind = FeedbackKind.BUG, parts: Set<ReportPart> = setOf(ReportPart.SAID, ReportPart.SCREENSHOT)) =
        SentReport("RFB-" + "ABCDEFGH".substring(0, 4) + "%04d".format(n).map { "ABCDEFGHIJ"[it - '0'] }.joinToString(""), 1_800_000_000_000L + n, kind, parts)

    @Test fun aListRoundTripsNewestFirst() {
        val list = SentReports.add(SentReports.add(emptyList(), r(1, FeedbackKind.IDEA, emptySet())), r(2))
        assertEquals(listOf(r(2), r(1, FeedbackKind.IDEA, emptySet())), SentReports.fromJson(SentReports.toJson(list)))
    }

    @Test fun onlyTheLastFiftyAreKept() {
        val list = (1..60).fold(emptyList<SentReport>()) { l, n -> SentReports.add(l, r(n)) }
        assertEquals(SentReports.MAX, list.size)
        assertEquals(r(60), list.first())
        assertEquals(r(11), list.last())
        assertEquals(list, SentReports.fromJson(SentReports.toJson(list)))
    }

    @Test fun theSameReferenceTwiceIsOneRow() {
        val list = SentReports.add(SentReports.add(emptyList(), r(1)), r(1).copy(at = 5))
        assertEquals(listOf(r(1).copy(at = 5)), list)
    }

    @Test fun theFileHoldsNoContents() {
        val o = JSONObject(SentReports.toJson(listOf(r(1)))).getJSONArray("sent").getJSONObject(0)
        assertEquals(setOf("ref", "at", "kind", "parts"), o.keys().asSequence().toSet())
    }

    @Test fun aBadFileOrEntryIsLeftOut() {
        assertEquals(emptyList<SentReport>(), SentReports.fromJson(null))
        assertEquals(emptyList<SentReport>(), SentReports.fromJson("not json"))
        val text = """{"v":1,"sent":[{"ref":"nope","at":1,"kind":"BUG","parts":[]},{"ref":"RFB-ABCDEFGH","at":0,"kind":"BUG"},""" +
            """{"ref":"RFB-ABCDEFGH","at":2,"kind":"WHAT","parts":[]},{"ref":"RFB-ABCDEFGH","at":3,"kind":"IDEA","parts":["SAID","NOPE"]}]}"""
        val got = SentReports.fromJson(text)
        assertEquals(listOf(SentReport("RFB-ABCDEFGH", 3, FeedbackKind.IDEA, setOf(ReportPart.SAID))), got)
        assertTrue(FeedbackRef.valid(r(7).ref))
    }

    @Test fun aPickCarriesItsReportBackAndNothingElseIsTaken() {
        val h = HandedReport("RFB-ABCDEFGH", FeedbackKind.IDEA, setOf(ReportPart.SCREENSHOT, ReportPart.SAID), 12_345)
        assertEquals(h, HandedReports.of(HandedReports.data(h)))
        assertEquals("robot-report:RFB-ABCDEFGH/2/SAID.SCREENSHOT/12345", HandedReports.data(h))
        assertEquals(h.copy(parts = emptySet()), HandedReports.of(HandedReports.data(h.copy(parts = emptySet()))))
        for (bad in listOf(null, "", "content:RFB-ABCDEFGH/2/SAID/1", "robot-report:RFB-abcdefgh/2/SAID/1", "robot-report:RFB-ABCDEFGH/9/SAID/1",
            "robot-report:RFB-ABCDEFGH/2/NAME/1", "robot-report:RFB-ABCDEFGH/2/SAID/-1", "robot-report:RFB-ABCDEFGH/2/SAID/99999999999",
            "robot-report:RFB-ABCDEFGH/2/SAID/1/x", "robot-report:RFB-ABCDEFGH/2/SAID"))
            assertEquals(bad, null, HandedReports.of(bad))
    }
}
