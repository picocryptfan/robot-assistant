// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.GrpPerson
import app.heyrobot.core.link.requests.GrpStatus
import app.heyrobot.core.link.requests.GrpWho
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** L11's rules with nothing around them: the common times, the folder's state machine, what a backup carries. */
class LinkTogetherTest {
    private val a = "a".repeat(32); private val b = "b".repeat(32)
    private var n = 0
    private fun id() = (++n).toString(16).padStart(32, '0')
    private val hash = "0".repeat(64)
    private val day = 86_400L
    private val from = 1_790_035_200L // Midnight, UTC.
    private val to = from + 7 * day
    private val f = FolderRec(a, b, FolderState.JOINED, "Trip", false, 1)

    private fun add(rec: FolderRec = f, item: String = id(), file: String = id(), rev: Long = 1) = FolderRules.apply(rec, FoldOp(a, item, rev, FoldOpKind.ADD, file), 5)
    private fun offer(file: String, size: Long = 10, kind: OfferKind = OfferKind.DOCUMENT, name: String = "a.pdf") = FileOffer(file, name, kind, size, hash, OfferWhy.FOLDER)

    @Test fun commonTimesLeaveOutAnyonesBusyStepsAndStayInsideTheHours() {
        val busy = GroupTimes.mask(from, to, listOf(Span(from + 9 * 3600, from + 10 * 3600)))
        val t = GroupTimes.common(from, to, 60, listOf(busy), ZoneOffset.UTC, from)
        assertEquals(listOf(from + 10 * 3600, from + day + 9 * 3600, from + 2 * day + 9 * 3600), t)
        assertEquals("not before now", from + day + 9 * 3600, GroupTimes.common(from, to, 60, emptyList(), ZoneOffset.UTC, from + 21 * 3600).first())
        val all = GroupTimes.mask(from, to, listOf(Span(from, to)))
        assertTrue("everyone busy", GroupTimes.common(from, to, 60, listOf(all), ZoneOffset.UTC, from).isEmpty())
        assertTrue("too long for the hours", GroupTimes.common(from, to, 13 * 60, emptyList(), ZoneOffset.UTC, from).isEmpty())
        assertEquals("a 90-minute plan needs three free steps", from + 11 * 3600,
            GroupTimes.common(from, to, 90, listOf(GroupTimes.mask(from, to, listOf(Span(from + 9 * 3600, from + 9 * 3600 + 1), Span(from + 10 * 3600, from + 11 * 3600)))), ZoneOffset.UTC, from).first())
    }

    @Test fun aLyingMaskIsBoundedByTheWindow() {
        val m = GroupTimes.mask(from, to, listOf(Span(from - 100 * day, to + 100 * day), Span(to + day, to + 2 * day)))
        assertEquals(7 * 48 / 8, m.size)
        assertTrue(m.all { it == 0xFF.toByte() })
    }

    @Test fun aGroupsRowLimitsHold() {
        fun g(k: Int, title: String = "Dinner", at: Long = from) = LinkGroup(id(), GroupState.ASKING, title, null, null, 60, at, at + day, 1, 0, List(k) { GroupMember(id(), "P$it") })
        assertTrue(g(7).valid()); assertFalse("8 others is 9 people", g(8).valid()); assertFalse("no one", g(0).valid())
        assertFalse(g(1, "a\nb").valid())
        assertTrue(LinkGroup.decode(LinkGroup.encode(g(7)))!!.valid())
        val x = g(1); x.members[0].busy = ByteArray(GroupTimes.MASK_BYTES + 1)
        assertNull("a mask past the window", LinkGroup.decode(LinkGroup.encode(x)))
    }

    @Test fun whosInComesFromThePlanAndAPairingThatEndedIsOut() {
        val ev = app.heyrobot.core.link.requests.EventFields("x", listOf(from), 60)
        fun p(s: PlanState) = LinkPlan(a, b, true, s, 1, a, ev, 1, 1)
        assertEquals(GrpWho.WAITING, GroupRoster.who(true, null, true))
        assertEquals("never reached", GrpWho.OUT, GroupRoster.who(false, null, true))
        assertEquals(GrpWho.IN, GroupRoster.who(false, p(PlanState.ACCEPTED), true))
        assertEquals(GrpWho.OUT, GroupRoster.who(false, p(PlanState.ACCEPTED), false))
        assertEquals(GrpWho.OUT, GroupRoster.who(false, p(PlanState.DECLINED), true))
        assertEquals(GrpWho.OUT, GroupRoster.who(false, p(PlanState.CANCELLED), true))
        assertEquals(GrpWho.WAITING, GroupRoster.who(false, p(PlanState.PROPOSED), true))
    }

    @Test fun anItemIdIsUsedOnce() {
        val item = id(); val file = id()
        val (x, r) = add(item = item, file = file)
        assertEquals(FolderOpResult.ADDED, r)
        assertEquals("the same again", FolderOpResult.SAME, add(x, item, file).second)
        assertEquals("another file under that id", FolderOpResult.REFUSED, add(x, item, id()).second)
        assertEquals("that file under another id", FolderOpResult.REFUSED, add(x, id(), file).second)
        val (y, g) = FolderRules.apply(x, FoldOp(a, item, 2, FoldOpKind.REMOVE, null), 6)
        assertEquals(FolderOpResult.REMOVED, g)
        assertEquals("gone stays gone", FolderOpResult.REFUSED, add(y, item, file, 3).second)
        assertEquals(FolderOpResult.SAME, FolderRules.apply(y, FoldOp(a, item, 3, FoldOpKind.REMOVE, null), 6).second)
        assertTrue("its words are gone with it", y.item(item)!!.name.isEmpty())
    }

    @Test fun aRemovalNeedsAHigherCountTheirOwnLineAndAnAdd() {
        val mine = FolderRules.add(f, a, b, "x", 5, 1)!!
        assertEquals(FolderOpResult.REFUSED, FolderRules.apply(mine, FoldOp(a, a, 2, FoldOpKind.REMOVE, null), 6).second)
        assertEquals(FolderOpResult.WAIT, FolderRules.apply(f, FoldOp(a, id(), 2, FoldOpKind.REMOVE, null), 6).second)
        val item = id()
        val (x, _) = add(item = item, rev = 3)
        assertEquals(FolderOpResult.REFUSED, FolderRules.apply(x, FoldOp(a, item, 3, FoldOpKind.REMOVE, null), 6).second)
    }

    @Test fun theCapsHoldForBothSides() {
        var x = f
        repeat(LinkLimits.FOLDER_FILES) { x = add(x).first }
        assertEquals(500, x.live.size)
        assertEquals("the 501st", FolderOpResult.REFUSED, add(x).second)
        assertNull("yours too", FolderRules.add(x, id(), id(), "a", 1, 1))
        val line = id(); val y = add(f, file = line).first
        assertEquals(FolderOfferFit.FITS, FolderRules.offerFits(y, offer(line, LinkLimits.FOLDER_BYTES.coerceAtMost(app.heyrobot.core.link.requests.RequestCaps.FILE_MAX_BYTES))))
        assertEquals("size past a file's cap", FolderOfferFit.REFUSED, FolderRules.offerFits(y, offer(line, app.heyrobot.core.link.requests.RequestCaps.FILE_MAX_BYTES + 1)))
        var near = f
        repeat(5) { near = FolderRules.offered(add(near, file = "$it".padStart(32, 'e')).first, offer("$it".padStart(32, 'e'), 200L * 1024 * 1024)) }
        val over = add(near, file = line).first
        assertEquals(FolderOfferFit.REFUSED, FolderRules.offerFits(over, offer(line, 200L * 1024 * 1024)))
        assertNull(FolderRules.add(near, id(), id(), "a", 200L * 1024 * 1024, 1))
    }

    /** F71: taking out what arrived doesn't make room: its size and its line still count. Yours do make room. */
    @Test fun whatTheyTookOutStillCounts() {
        var x = f
        val mb200 = 200L * 1024 * 1024
        repeat(5) {
            val line = id(); val item = id()
            x = FolderRules.offered(add(x, item = item, file = line).first, offer(line, mb200))
            x = FolderRules.apply(x, FoldOp(a, item, 2, FoldOpKind.REMOVE, null), 6).first
        }
        assertEquals(0, x.live.size)
        assertEquals(5, x.used)
        assertEquals(5 * mb200, x.bytes)
        val line = id()
        assertEquals(FolderOfferFit.REFUSED, FolderRules.offerFits(add(x, file = line).first, offer(line, 100L * 1024 * 1024)))
        val mine = FolderRules.add(f, id(), id(), "a", mb200, 1)!!
        val out = FolderRules.remove(mine, mine.items.single().item)!!.first
        assertEquals(0, out.used)
        assertEquals(0L, out.bytes)
    }

    /** F71: nothing that counts is trimmed; a line never offered and taken out costs nothing. */
    @Test fun walkingLinesPastTheFileCapStops() {
        var x = f
        repeat(LinkLimits.FOLDER_FILES) {
            val line = id(); val item = id()
            x = FolderRules.offered(add(x, item = item, file = line).first, offer(line, 1))
            x = FolderRules.apply(x, FoldOp(a, item, 2, FoldOpKind.REMOVE, null), 6).first
        }
        assertEquals(FolderOpResult.REFUSED, add(x).second)
        assertEquals(LinkLimits.FOLDER_FILES, x.used)
    }

    @Test fun anOfferNeedsItsLineOnceAndNeverCanRun() {
        val line = id()
        assertEquals(FolderOfferFit.NOT_YET, FolderRules.offerFits(f, offer(line)))
        val x = add(f, file = line).first
        assertEquals(FolderOfferFit.REFUSED, FolderRules.offerFits(x, offer(line, kind = OfferKind.DANGEROUS)))
        assertEquals("a path in its name", FolderOfferFit.REFUSED, FolderRules.offerFits(x, offer(line, name = "../up.pdf")))
        val y = FolderRules.offered(x, offer(line))
        assertEquals("twice", FolderOfferFit.REPEAT, FolderRules.offerFits(y, offer(line)))
        assertEquals("the line stays when refused after", y, FolderRules.refused(y, line))
        assertEquals(FolderOfferFit.REFUSED, FolderRules.offerFits(x.copy(state = FolderState.INVITED), offer(line)))
        val mine = FolderRules.add(f, id(), line, "x", 5, 1)!!
        assertEquals("your own line", FolderOfferFit.REFUSED, FolderRules.offerFits(mine, offer(line)))
    }

    @Test fun aFoldersNameIsOneLineAndNoPath() {
        for (bad in listOf("../up", "a/b", "a\\b", "..", ".", "", "a\nb", "x".repeat(41), "a‮b")) assertFalse(bad, FolderRules.nameOk(bad))
        assertTrue(FolderRules.nameOk("Trip to Halifax"))
    }

    @Test fun aBackupCarriesThatItsSharedAndNoneOfWhatsInIt() {
        val full = FolderRules.offered(add(file = a).first, offer(a))
        val bare = LinkBackupCodec.folder(full)!!
        assertTrue(bare.items.isEmpty())
        assertNull("an invite isn't joined yet", LinkBackupCodec.folder(full.copy(state = FolderState.INVITED)))
        val g = LinkGroup(a, GroupState.PROPOSED, "Dinner", null, null, 60, from, to, 1, 0, listOf(GroupMember(b, "Sam", id(), GroupAnswer.KNOWN, ByteArray(8) { -1 }, id())), from + 3600, 77, 3)
        val light = LinkBackupCodec.group(g)!!
        assertNull(light.members.single().busy); assertNull(light.members.single().ask); assertEquals(0, light.cal)
        assertNull("still asking", LinkBackupCodec.group(LinkGroup(a, GroupState.ASKING, "Dinner", null, null, 60, from, to, 1, 0, listOf(GroupMember(b, "Sam")))))
        val text = LinkBackupCodec.encode(LinkBackupFile(folders = listOf(bare), groups = listOf(light)))
        assertFalse("the file's name isn't in it", text.contains(LinkBytes.hex("a.pdf".toByteArray())))
        val back = (LinkBackupCodec.decode(text) as LinkBackupRead.Ok).file
        assertEquals(1, back.folders.size); assertEquals(1, back.groups.size)
        // Someone's made-up backup with contents or busy times is refused whole.
        val withItems = LinkBackupCodec.encode(LinkBackupFile(folders = listOf(full)))
        assertFalse(LinkBackupCodec.decode(withItems) is LinkBackupRead.Ok)
        val noGroups = org.json.JSONObject(text).apply { remove("folders"); remove("groups") }.toString()
        assertTrue("an older backup reads", LinkBackupCodec.decode(noGroups) is LinkBackupRead.Ok)
    }

    @Test fun recordsAreAll() {
        val v = GroupsView(emptyList(), listOf(GroupSeenPlan(GroupSeen(a, b, 2, a, 1, listOf(GrpPerson("Sam", GrpWho.IN))), "Dinner", 9)))
        assertEquals("Sam", GroupsView.decode(GroupsView.encode(v))!!.seen.single().seen.who.single().name)
        assertNotNull(FolderRec.decodeAll(FolderRec.encodeAll(listOf(f))))
        assertNull(FolderRec.decodeAll(ByteArray(3)))
        assertEquals("grp.status is the one group type", Flow.UPDATE, LinkPolicy.flow(RequestType.GRP_STATUS))
        assertEquals(GrpStatus::class, GrpStatus(a, 1, emptyList())::class)
    }

    @Test fun theJoinCardSaysWhatYouAgreeTo() {
        val r = app.heyrobot.core.link.requests.LinkRequest.of(app.heyrobot.core.link.requests.FoldJoin(a, "Trip"), 1_800_000_000, "en", 3600)
        val card = LinkCardText.summary(r, app.heyrobot.core.i18n.Phrase.plain("Sam"), java.time.ZoneOffset.UTC)
        val en = card.render(app.heyrobot.core.i18n.Langs.EN)
        for (w in listOf("Sam", "without asking", "${LinkLimits.FOLDER_FILES} files", "1 GiB", "locked", "nothing opens", "leave", "keep your copies")) assertTrue(w, en.contains(w))
        assertEquals("1 GiB is the cap", 1L shl 30, LinkLimits.FOLDER_BYTES)
        val fr = card.render(app.heyrobot.core.i18n.Langs(app.heyrobot.core.i18n.Lang.FR, app.heyrobot.core.i18n.Lang.FR, app.heyrobot.core.i18n.Lang.FR))
        for (w in listOf("Sam", "sans te demander", "${LinkLimits.FOLDER_FILES} fichiers", "1 Gio", "verrouillés", "rien ne s’ouvre", "garder tes copies")) assertTrue(w, fr.contains(w))
    }

    @Test fun aNewGroupFromMainKeepsOnlyYourWordsAndPeople() {
        val g = LinkGroup(a, GroupState.CANCELLED, "Dinner", null, null, 60, 3600, 7200, 5, 6, listOf(GroupMember(b, "Sam", a, GroupAnswer.KNOWN, ByteArray(4), a)), 3600, 9, 9)
        val f = g.fresh()
        assertEquals(GroupState.ASKING, f.state)
        assertEquals(0L, f.time); assertEquals(0L, f.cal); assertEquals(0L, f.rev); assertEquals(0L, f.done)
        val m = f.members.single()
        assertEquals(b, m.pairing); assertEquals("Sam", m.name)
        assertNull(m.ask); assertNull(m.busy); assertNull(m.plan)
        assertEquals(GroupAnswer.ASKED, m.answer)
    }

    @Test fun aPeersOfferNeverTakesOutALineYouAdded() {
        val mine = FolderRules.add(f, a, b, "photo.jpg", 10, 5)!!
        assertEquals(FolderOfferFit.REFUSED, FolderRules.offerFits(mine, offer(b)))
        assertEquals(mine.items, FolderRules.refused(mine, b).items)
        assertFalse(FolderRules.refused(mine, b).items.single().gone)
    }

    @Test fun whatIsSeenOfAGroupNamesOnlyWhoIsIn() {
        val s = GroupSeen(a, b, 2, a, 1, listOf(GrpPerson("Sam", GrpWho.IN)), 2, 1)
        val back = GroupSeen.decode(GroupSeen.encode(s))!!
        assertEquals(listOf("Sam"), back.named.map { it.name })
        assertEquals(2, back.outCount); assertEquals(1, back.waitingCount)
        val told = GroupSeen(a, b, 2, a, 1, listOf(GrpPerson("Sam", GrpWho.IN), GrpPerson("Kim", GrpWho.OUT), GrpPerson("Lee", GrpWho.WAITING)))
        assertEquals("whoever sent them, other names are counts", listOf("Sam"), told.named.map { it.name })
        assertEquals(1, told.outCount); assertEquals(1, told.waitingCount)
    }

    @Test fun whatIsSeenFromAnEarlierBuildStillReads() {
        val v2 = GroupSeen.encode(GroupSeen(a, b, 2, a, 1, listOf(GrpPerson("Kim", GrpWho.OUT))))
        val v1 = v2.copyOf(v2.size - 2).also { it[3] = 1 }
        val back = GroupSeen.decode(v1)!!
        assertTrue(back.named.isEmpty()); assertEquals(1, back.outCount)
    }

    @Test fun theScreensAreToldWhenYourCalendarCouldNotBeRead() {
        val g = LinkGroup(a, GroupState.ASKING, "Dinner", null, null, 60, 1_800_000_000, 1_800_086_400, 1, 0, listOf(GroupMember(b, "Sam", id(), GroupAnswer.KNOWN, ByteArray(8) { -1 }, id())), 0, 0, 0)
        val v = GroupsView.decode(GroupsView.encode(GroupsView(listOf(g to GroupStanding(emptyList(), emptyList(), false)), emptyList())))!!
        assertFalse(v.mine.single().second.calendar)
    }
}
