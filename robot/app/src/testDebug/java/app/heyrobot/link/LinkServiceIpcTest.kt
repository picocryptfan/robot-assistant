// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import app.heyrobot.core.link.ListShareItem
import android.app.KeyguardManager
import android.os.IBinder
import android.os.UserManager
import android.os.Parcel
import android.os.ParcelFileDescriptor
import app.heyrobot.core.feedback.FeedbackRef
import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.feedback.ReportKey
import android.os.Process
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNotice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskNotices
import app.heyrobot.core.link.DeskState
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.Verified
import app.heyrobot.sys.LinkSettingsStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.TimeUnit

/**
 * 1.5, Robot to Robot (robot-to-robot-plan.md §5.1; job L3): LinkService's hand-written IPC through real Parcels. Only
 * Robot's own uid is answered (another one, an isolated process's included, gets REPLY_REFUSED and nothing else); the
 * interface token is required; a frame is exactly the call's fields within their caps, nothing left over; a call that isn't
 * one is refused. Robot to Robot off: STATUS answers, and every call that needs the node is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkServiceIpcTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun binder(): IBinder = Robolectric.buildService(LinkService::class.java).create().get().onBind(null)

    private fun call(b: IBinder, code: Int, write: Parcel.() -> Unit, token: Boolean = true): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        if (token) data.writeInterfaceToken(LinkIpcProtocol.DESCRIPTOR)
        data.write()
        b.transact(code, data, reply, 0)
        reply.setDataPosition(0)
        data.recycle()
        return reply
    }

    @Test fun onlyRobotsOwnUidIsAnswered() {
        val b = binder()
        val ok = call(b, LinkIpcProtocol.STATUS, {})
        assertEquals(LinkIpcProtocol.REPLY_OK, ok.readInt())
        // Robot to Robot off: Off or locked (Robolectric has no Keystore), off, then the node's numbers all zero.
        assertTrue(ok.readInt() in listOf(LinkIpcProtocol.VAULT_OFF, LinkIpcProtocol.VAULT_LOCKED))
        assertEquals(0, ok.readInt())
        ok.readInt()
        repeat(8) { assertEquals(0, ok.readInt()) }
        assertEquals(0, ok.dataAvail())
        // Another uid (an isolated process's: 99000 and up): refused, nothing more.
        ShadowBinder.setCallingUid(99_123)
        try {
            val no = call(b, LinkIpcProtocol.STATUS, {})
            assertEquals(LinkIpcProtocol.REPLY_REFUSED, no.readInt())
            assertEquals(0, no.dataAvail())
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
    }

    @Test fun framesAreReadStrictly() {
        val b = binder()
        // No interface token: refused.
        assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.STATUS, {}, token = false).readInt())
        // Something left over after STATUS's (empty) fields: refused.
        assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.STATUS, { writeInt(1) }).readInt())
        // A code that isn't a call: not handled.
        val data = Parcel.obtain().apply { writeInterfaceToken(LinkIpcProtocol.DESCRIPTOR) }
        val reply = Parcel.obtain()
        assertEquals(false, b.transact(LinkIpcProtocol.LAST + 1, data, reply, 0))
        // SEND with a byte array longer than its cap, a pairing id that isn't 16 bytes, or a field missing: refused before
        // anything (Robot to Robot is off here, so a well-formed one is refused too, by REPLY_REFUSED from the node).
        val id = ByteArray(16) { 1 }
        val good: Parcel.() -> Unit = { writeByteArray(id); writeInt(70); writeByteArray(id); writeLong(1_790_000_000L); writeLong(604_800); writeByteArray(ByteArray(512)) }
        for (bad in listOf<Parcel.() -> Unit>(
            { writeByteArray(id); writeInt(70); writeByteArray(id); writeLong(1); writeLong(1); writeByteArray(ByteArray(LinkIpcProtocol.MAX_REQUEST + 1)) },
            { writeByteArray(ByteArray(15)); writeInt(70); writeByteArray(id); writeLong(1); writeLong(1); writeByteArray(ByteArray(512)) },
            { writeByteArray(ByteArray(16)); writeInt(70); writeByteArray(id); writeLong(1); writeLong(1); writeByteArray(ByteArray(512)) },
            { writeByteArray(id); writeInt(70); writeByteArray(id); writeLong(1) },
            { good(); writeInt(0) },
            { writeInt(Int.MAX_VALUE) },
        )) assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.SEND, bad).readInt())
        val r = call(b, LinkIpcProtocol.SEND, good)
        assertEquals(LinkIpcProtocol.REPLY_REFUSED, r.readInt())
        assertEquals(0, r.dataAvail())
        // USE_CODE: a text past its cap, a form or mode that isn't one: refused.
        for (bad in listOf<Parcel.() -> Unit>(
            { writeString("x".repeat(LinkIpcProtocol.MAX_CODE_TEXT + 1)); writeInt(1); writeInt(1) },
            { writeString("ROBOT-PAIR-abc"); writeInt(2); writeInt(1) },
            { writeString("ROBOT-PAIR-abc"); writeInt(1); writeInt(9) },
            { writeString(null); writeInt(1); writeInt(1) },
        )) assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.USE_CODE, bad).readInt())
    }

    /** L5's calls: each one's good frame is refused with nothing after it while Robot to Robot is off, as is every bad one. */
    @Test fun pairingCallsAreFramedStrictlyAndRefusedWhileOff() {
        val b = binder()
        val id = ByteArray(16) { 2 }
        val good = mapOf<Int, Parcel.() -> Unit>(
            LinkIpcProtocol.CODE_STATE to { writeByteArray(id) }, LinkIpcProtocol.PAIRINGS to {}, LinkIpcProtocol.SAFETY to { writeByteArray(id) },
            LinkIpcProtocol.VERIFY to { writeByteArray(id) }, LinkIpcProtocol.SET_PERSON to { writeByteArray(id); writeString("") },
            LinkIpcProtocol.SET_PRESET to { writeByteArray(id); writeInt(1) }, LinkIpcProtocol.SET_LEVEL to { writeByteArray(id); writeInt(1); writeInt(2) },
            LinkIpcProtocol.RAISE to { writeByteArray(id) }, LinkIpcProtocol.LOWER to { writeByteArray(id) }, LinkIpcProtocol.KEEP_MODE to { writeByteArray(id) },
            LinkIpcProtocol.BLOCK to { writeByteArray(id); writeString("Sam") }, LinkIpcProtocol.BLOCKED to {}, LinkIpcProtocol.UNBLOCK to { writeByteArray(id) },
            LinkIpcProtocol.ANSWER_MOVED to { writeByteArray(id); writeInt(1) }, LinkIpcProtocol.CANCEL_CODE to { writeByteArray(id) },
            // L6, the desk.
            LinkIpcProtocol.DESK to {}, LinkIpcProtocol.DESK_ANSWER to { writeByteArray(id); writeInt(1) }, LinkIpcProtocol.DESK_UNDO to { writeByteArray(id) },
            LinkIpcProtocol.DESK_NOTICES to {}, LinkIpcProtocol.DESK_NOT_NOW to { writeByteArray(id); writeInt(9) },
            // L7, sending and plans.
            LinkIpcProtocol.PLAN_SEND to { writeByteArray(id); writeString("Dinner"); writeString(""); writeString(""); writeInt(1); writeLong(1_790_100_000L); writeLong(60) },
            LinkIpcProtocol.PLANS to {}, LinkIpcProtocol.PLAN_CANCEL to { writeByteArray(id); writeByteArray(id) }, LinkIpcProtocol.DESK_SEEN to { writeByteArray(id) },
            // L10, your answer to a question.
            LinkIpcProtocol.DESK_REPLY to { writeByteArray(id); writeString("At the park") },
            // L12, a backup's export, and telling a Robot you moved.
            LinkIpcProtocol.BACKUP_EXPORT to {},
            LinkIpcProtocol.SEND_MOVED to { writeByteArray(id) },
            // L8, files between Robots.
            LinkIpcProtocol.FILE_SEND to {
                writeByteArray(app.heyrobot.core.link.TransferView.encodeAll(listOf(app.heyrobot.core.link.TransferView(LinkBytes.hex(id), LinkBytes.hex(id), true, app.heyrobot.core.link.XferState.OFFERED,
                    app.heyrobot.core.link.requests.FileOffer(LinkBytes.hex(id), "a.pdf", app.heyrobot.core.link.requests.OfferKind.entries.first(), 1, "a".repeat(64), app.heyrobot.core.link.requests.OfferWhy.OFFER), 0, 0, 0, false))))
                writeByteArray(ByteArray(app.heyrobot.core.link.XferSeal.KEY_BYTES))
            },
            LinkIpcProtocol.TRANSFERS to {}, LinkIpcProtocol.TRANSFER_FILED to { writeByteArray(id); writeInt(1) },
            LinkIpcProtocol.DESK_FILE_ANSWER to { writeByteArray(id); writeByteArray(id) },
            // L11, shared folders and group plans.
            LinkIpcProtocol.FOLDER_INVITE to { writeByteArray(id); writeString("Trip") },
            LinkIpcProtocol.FOLDER_ADD to { writeByteArray(id); writeByteArray(id); writeByteArray(id); writeString("a.pdf"); writeLong(10) },
            LinkIpcProtocol.FOLDER_REMOVE to { writeByteArray(id); writeByteArray(id); writeByteArray(id) },
            LinkIpcProtocol.FOLDER_LEAVE to { writeByteArray(id); writeByteArray(id) },
            LinkIpcProtocol.FOLDERS to {},
            LinkIpcProtocol.GROUP_START to { writeByteArray(ByteArray(8)) },
            LinkIpcProtocol.GROUP_PROPOSE to { writeByteArray(id); writeLong(60) },
            LinkIpcProtocol.GROUP_CANCEL to { writeByteArray(id) },
            LinkIpcProtocol.GROUPS to {},
            // 1.5, shared lists.
            LinkIpcProtocol.LIST_SEND to { writeByteArray(id); writeByteArray(ByteArray(8)); writeByteArray(ByteArray(8)) },
            LinkIpcProtocol.LIST_LEAVE to { writeByteArray(id); writeByteArray(id) }, LinkIpcProtocol.LIST_LOST_SEEN to { writeByteArray(id); writeByteArray(id) },
            LinkIpcProtocol.LIST_PENDING to {}, LinkIpcProtocol.LIST_APPLIED to { writeInt(1); writeByteArray(id) }, LinkIpcProtocol.LIST_SHARES to {}, LinkIpcProtocol.PUSH_APPS to {},
            // Today H7b: news through Tor, refused while Robot to Robot is off (nothing then goes direct either).
            LinkIpcProtocol.FEED_TOR to {}, LinkIpcProtocol.FEED_STREAM to { writeString("news.example.org"); writeInt(443) },
        )
        assertEquals(((LinkIpcProtocol.CODE_STATE..LinkIpcProtocol.SEND_MOVED) + (LinkIpcProtocol.FOLDER_INVITE..LinkIpcProtocol.GROUPS) + (LinkIpcProtocol.LIST_SEND..LinkIpcProtocol.LAST)).toSet() - LinkService.ALLOWED_WHILE_OFF, good.keys)
        for ((code, w) in good) {
            val r = call(b, code, w)
            assertEquals(code.toString(), LinkIpcProtocol.REPLY_REFUSED, r.readInt())
            assertEquals(0, r.dataAvail())
        }
        for ((code, bad) in listOf<Pair<Int, Parcel.() -> Unit>>(
            LinkIpcProtocol.SET_PRESET to { writeByteArray(id); writeInt(9) },
            LinkIpcProtocol.SET_LEVEL to { writeByteArray(id); writeInt(13); writeInt(1) },
            LinkIpcProtocol.SET_LEVEL to { writeByteArray(id); writeInt(1); writeInt(3) },
            LinkIpcProtocol.ANSWER_MOVED to { writeByteArray(id); writeInt(2) },
            LinkIpcProtocol.BLOCK to { writeByteArray(id); writeString("x".repeat(LinkIpcProtocol.MAX_LABEL + 1)) },
            LinkIpcProtocol.SET_PERSON to { writeByteArray(id); writeString("x".repeat(LinkIpcProtocol.MAX_PERSON + 1)) },
            LinkIpcProtocol.VERIFY to { writeByteArray(ByteArray(15)) },
            LinkIpcProtocol.VERIFY to { writeByteArray(ByteArray(16)) },
            LinkIpcProtocol.PAIRINGS to { writeInt(0) },
            LinkIpcProtocol.DESK_ANSWER to { writeByteArray(id); writeInt(3) },
            LinkIpcProtocol.DESK_NOT_NOW to { writeByteArray(id); writeInt(13) },
            LinkIpcProtocol.DESK_NOTICES to { writeInt(0) },
            LinkIpcProtocol.DESK_REPLY to { writeByteArray(id); writeString("x".repeat(LinkIpcProtocol.MAX_ANSWER + 1)) },
            // L8: a frame too long, a key that isn't one.
            LinkIpcProtocol.FILE_SEND to { writeByteArray(ByteArray(LinkIpcProtocol.MAX_TRANSFER + 1)); writeByteArray(ByteArray(32)) },
            LinkIpcProtocol.TRANSFERS to { writeInt(0) },
            LinkIpcProtocol.TRANSFER_FILED to { writeByteArray(id) },
            LinkIpcProtocol.DESK_FILE_ANSWER to { writeByteArray(id) },
            LinkIpcProtocol.LIST_SEND to { writeByteArray(id); writeByteArray(ByteArray(ListShareItem.MAX_BYTES + 1)); writeByteArray(ByteArray(8)) },
            LinkIpcProtocol.LIST_LEAVE to { writeByteArray(ByteArray(15)); writeByteArray(id) },
            LinkIpcProtocol.LIST_PENDING to { writeInt(0) },
            LinkIpcProtocol.LIST_APPLIED to { writeInt(0) },
            LinkIpcProtocol.LIST_APPLIED to { writeInt(Int.MAX_VALUE) },
            LinkIpcProtocol.LIST_SHARES to { writeInt(0) },
            // L11: an id cut short, a name past its limit, a group past its size, a stray field.
            LinkIpcProtocol.FOLDER_INVITE to { writeByteArray(ByteArray(3)); writeString("Trip") },
            LinkIpcProtocol.FOLDER_INVITE to { writeByteArray(id); writeString("x".repeat(LinkIpcProtocol.MAX_TITLE + 1)) },
            LinkIpcProtocol.FOLDER_ADD to { writeByteArray(id); writeByteArray(id); writeByteArray(id); writeString("a.pdf") },
            LinkIpcProtocol.FOLDER_REMOVE to { writeByteArray(id); writeByteArray(id) },
            LinkIpcProtocol.FOLDER_LEAVE to { writeByteArray(ByteArray(15)); writeByteArray(id) },
            LinkIpcProtocol.FOLDERS to { writeInt(0) },
            LinkIpcProtocol.GROUP_START to { writeByteArray(ByteArray(app.heyrobot.core.link.LinkGroup.MAX_BYTES + 1)) },
            LinkIpcProtocol.GROUP_PROPOSE to { writeByteArray(id) },
            LinkIpcProtocol.GROUP_CANCEL to { writeByteArray(ByteArray(3)) },
            LinkIpcProtocol.GROUPS to { writeInt(0) },
            // L12: a stray field, an id cut short.
            LinkIpcProtocol.BACKUP_EXPORT to { writeInt(0) },
            LinkIpcProtocol.SEND_MOVED to { writeByteArray(ByteArray(3)) },
        )) {
            val r = call(b, code, bad)
            assertEquals(code.toString(), LinkIpcProtocol.REPLY_REFUSED, r.readInt())
            assertEquals(0, r.dataAvail())
        }
    }

    /**
     * RP5: SEAL_REPORT is answered while Robot to Robot is off (LinkService.ALLOWED_WHILE_OFF), since sending a report
     * needs none of it: sealing opens no node (so no listener, no socket and no Tor), leaves Robot to Robot off, and writes
     * nothing but its one sealed file in cache/reports/. Another uid is refused, as for every call.
     */
    @Test fun sealingWhileOffOpensNothingAndTouchesNoLinkState() {
        assertEquals(setOf(LinkIpcProtocol.STATUS, LinkIpcProtocol.SET_UP, LinkIpcProtocol.START_OVER, LinkIpcProtocol.SEAL_REPORT), LinkService.ALLOWED_WHILE_OFF)
        val ctx = RuntimeEnvironment.getApplication()
        var opened = 0
        LinkRuntime.opener = { _ -> opened++; null }
        LinkReports.sealer = { _ -> ReportFile.Sealer { p -> byteArrayOf(7) + p } }
        try {
            val b = binder()
            val ref = FeedbackRef.new()
            val plain = tmp.newFile().apply { writeBytes(ReportFile.write(ReportFile.Report(ref, "Robot feedback\n"))) }
            fun files() = ctx.dataDir.walk().filter { it.isFile }.map { it.relativeTo(ctx.dataDir).path }.toSet()
            val before = files()
            // Robolectric hands the same descriptor across, which :link closes once read: closed here only if it wasn't.
            fun seal(): Parcel = ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY).let { fd ->
                try { call(b, LinkIpcProtocol.SEAL_REPORT, { writeByteArray(ByteArray(ReportKey.BYTES) { 5 }); writeString(ref); writeFileDescriptor(fd.fileDescriptor) }) }
                finally { runCatching { fd.close() } }
            }
            val r = seal()
            assertEquals(LinkIpcProtocol.REPLY_OK, r.readInt())
            assertEquals(plain.length().toInt() + 1, r.readInt())
            assertEquals(0, r.dataAvail())
            val sealed = java.io.File(java.io.File(ctx.cacheDir, LinkIpcProtocol.REPORT_DIR), ReportFile.name(ref))
            assertEquals(setOf(sealed.relativeTo(ctx.dataDir).path), files() - before)
            assertTrue((before - files()).isEmpty())
            assertEquals(0, opened)
            assertEquals(false, LinkRuntime.on(ctx))
            ShadowBinder.setCallingUid(99_123)
            try {
                val no = seal()
                assertEquals(LinkIpcProtocol.REPLY_REFUSED, no.readInt())
                assertEquals(0, no.dataAvail())
            } finally {
                ShadowBinder.setCallingUid(Process.myUid())
            }
        } finally {
            LinkRuntime.opener = null
            LinkReports.sealer = LinkReports.LIBSIGNAL
        }
    }

    /**
     * L5-fix: the lock rules at LinkService, on a real node (TestLinkNode, through LinkRuntime's test seam). Locked: what
     * pairs, verifies, loosens or unblocks is refused; raising, unpairing, blocking, tightening and the reads answer.
     */
    @Test fun aLockedPhoneRefusesWhatPairsVerifiesOrLoosens() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val test = TestLinkNode(tmp.newFolder())
        val one = test.pairing(); val two = test.pairing(); val three = test.pairing()
        LinkRuntime.opener = { test.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        val svc = Robolectric.buildService(LinkService::class.java).create().get()
        try {
            val b = svc.onBind(null)
            fun id(p: String) = LinkBytes.unhex(p, 16)!!
            fun answer(code: Int, w: Parcel.() -> Unit): Int = call(b, code, w).let { r -> if (r.readInt() == LinkIpcProtocol.REPLY_OK) r.readInt() else -1 }
            // Unlocked, making a code reaches the node (no route here: no carrier).
            keyguard.setIsDeviceLocked(false)
            assertEquals(MakeCodeResult.NO_ROUTE.code, answer(LinkIpcProtocol.MAKE_CODE) { writeInt(LinkMode.PRIVATE.code); writeInt(0) })
            keyguard.setIsDeviceLocked(true)
            val refused = listOf<Pair<Int, Parcel.() -> Unit>>(
                LinkIpcProtocol.MAKE_CODE to { writeInt(LinkMode.PRIVATE.code); writeInt(1) },
                // L12: what a backup holds of Robot to Robot, and telling your Robots you moved.
                LinkIpcProtocol.BACKUP_EXPORT to {},
                LinkIpcProtocol.SEND_MOVED to { writeByteArray(ByteArray(16) { 1 }) },
                LinkIpcProtocol.USE_CODE to { writeString("ROBOT-PAIR-abc"); writeInt(1); writeInt(LinkMode.PRIVATE.code) },
                LinkIpcProtocol.VERIFY to { writeByteArray(id(one)) },
                LinkIpcProtocol.LOWER to { writeByteArray(id(one)) },
                // L11: shared folders and group plans, every call, only unlocked.
                LinkIpcProtocol.FOLDER_INVITE to { writeByteArray(id(one)); writeString("Trip") },
                LinkIpcProtocol.FOLDER_ADD to { writeByteArray(id(one)); writeByteArray(id(one)); writeByteArray(id(one)); writeString("a.pdf"); writeLong(10) },
                LinkIpcProtocol.FOLDER_REMOVE to { writeByteArray(id(one)); writeByteArray(id(one)); writeByteArray(id(one)) },
                LinkIpcProtocol.FOLDER_LEAVE to { writeByteArray(id(one)); writeByteArray(id(one)) },
                LinkIpcProtocol.FOLDERS to {},
                LinkIpcProtocol.GROUP_START to { writeByteArray(ByteArray(8)) },
                LinkIpcProtocol.GROUP_PROPOSE to { writeByteArray(id(one)); writeLong(60) },
                LinkIpcProtocol.GROUP_CANCEL to { writeByteArray(id(one)) },
                LinkIpcProtocol.GROUPS to {},
                // 1.5: shared lists, their words and what you'd send, only unlocked.
                LinkIpcProtocol.LIST_PENDING to {}, LinkIpcProtocol.LIST_SHARES to {},
                LinkIpcProtocol.LIST_APPLIED to { writeInt(1); writeByteArray(id(one)) },
                LinkIpcProtocol.LIST_LEAVE to { writeByteArray(id(one)); writeByteArray(id(one)) },
                LinkIpcProtocol.LIST_LOST_SEEN to { writeByteArray(id(one)); writeByteArray(id(one)) },
                LinkIpcProtocol.LIST_SEND to { writeByteArray(id(one)); writeByteArray(ByteArray(8)); writeByteArray(ByteArray(8)) },
                LinkIpcProtocol.UNBLOCK to { writeByteArray(ByteArray(16) { 4 }) },
                LinkIpcProtocol.ANSWER_MOVED to { writeByteArray(id(one)); writeInt(1) },
                // "Someone else" has free/busy Off: Ask or Answer on its own lets more in, as does the family preset.
                LinkIpcProtocol.SET_LEVEL to { writeByteArray(id(one)); writeInt(LinkLevels.code(LinkCapability.FREE_BUSY)); writeInt(LinkLevels.levelCode(LinkLevel.ASK)) },
                LinkIpcProtocol.SET_PRESET to { writeByteArray(id(one)); writeInt(LinkLevels.presetCode(LinkPreset.FAMILY_OR_FRIEND)) },
                // L7: sending to another Robot, and your plans (their words), only unlocked (§4.4).
                LinkIpcProtocol.SEND to { writeByteArray(id(one)); writeInt(10); writeByteArray(ByteArray(16) { 5 }); writeLong(1_790_000_000L); writeLong(604_800); writeByteArray(ByteArray(512)) },
                LinkIpcProtocol.PLAN_SEND to { writeByteArray(id(one)); writeString("Dinner"); writeString(""); writeString(""); writeInt(1); writeLong(1_790_100_000L); writeLong(60) },
                LinkIpcProtocol.PLANS to {},
                LinkIpcProtocol.PLAN_CANCEL to { writeByteArray(id(one)); writeByteArray(ByteArray(16) { 6 }) },
            )
            for ((code, w) in refused) assertEquals(code.toString(), -1, answer(code, w))
            assertEquals(LinkLevel.OFF, test.state.pairings.getValue(one).levels[LinkCapability.FREE_BUSY])
            assertEquals(Verified.NO, test.state.pairings.getValue(one).verified)
            // What tightens, and the reads, still answer.
            assertEquals(1, answer(LinkIpcProtocol.SET_LEVEL) { writeByteArray(id(one)); writeInt(LinkLevels.code(LinkCapability.EVENTS)); writeInt(LinkLevels.levelCode(LinkLevel.OFF)) })
            assertEquals(1, answer(LinkIpcProtocol.SET_PRESET) { writeByteArray(id(two)); writeInt(LinkLevels.presetCode(LinkPreset.SOMEONE_ELSE)) })
            assertEquals(LinkIpcProtocol.REPLY_OK, call(b, LinkIpcProtocol.PAIRINGS, {}).readInt())
            assertEquals(LinkIpcProtocol.REPLY_OK, call(b, LinkIpcProtocol.BLOCKED, {}).readInt())
            assertEquals(0, answer(LinkIpcProtocol.ANSWER_MOVED) { writeByteArray(id(one)); writeInt(0) })
            assertEquals(1, answer(LinkIpcProtocol.RAISE) { writeByteArray(id(one)) })
            assertEquals(1, answer(LinkIpcProtocol.BLOCK) { writeByteArray(id(two)); writeString("Bob") })
            assertEquals(1, answer(LinkIpcProtocol.UNPAIR) { writeByteArray(id(three)) })
            assertEquals(listOf(one), test.state.pairings.keys.toList())
        } finally {
            svc.onUnbind(null)
            LinkService.released(5)
            keyguard.setIsDeviceLocked(false)
            LinkSettingsStore.update(ctx) { it.copy(on = false) }
            LinkRuntime.node(ctx)
            LinkRuntime.opener = null
        }
    }

    /**
     * L6: the desk through LinkService, on a real node and desk (TestLinkNode). A card waits; its notices answer while locked
     * (kinds and counts only); its view, your answer and Undo are refused while locked and answered unlocked; the view
     * carries the request as a frame that rebuilds its plaintext.
     */
    @Test fun theDeskShowsCardsOnlyUnlockedAndItsNoticesAlways() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val test = TestLinkNode(tmp.newFolder())
        val sam = test.pairing()
        test.state.pairings.getValue(sam).levels = LinkLevels.set(test.state.pairings.getValue(sam).levels, LinkCapability.ETA, LinkLevel.ASK)!!
        LinkRuntime.opener = { test.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        val svc = Robolectric.buildService(LinkService::class.java).create().get()
        try {
            val b = svc.onBind(null)
            val eta = LinkRequest.of(EtaTell(10, "Atwater"), System.currentTimeMillis() / 1000, "en")
            assertEquals(AckState.WAITING, test.node.onLinkThread { test.desk.delivered(sam, eta) }.get(10, TimeUnit.SECONDS))
            assertTrue(test.pings.get() > 0)
            // Locked: the notices answer (a card waits, from whom); the view is refused.
            keyguard.setIsDeviceLocked(true); test.locked = true
            val n = call(b, LinkIpcProtocol.DESK_NOTICES, {}).let { r -> assertEquals(LinkIpcProtocol.REPLY_OK, r.readInt()); DeskNotices.decode(r.createByteArray()!!)!! }
            assertEquals(1, n.waiting)
            assertEquals(listOf(DeskNotice(DeskNoticeKind.CARDS, sam, 1)), n.notices)
            assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.DESK, {}).readInt())
            assertEquals(LinkIpcProtocol.REPLY_REFUSED, call(b, LinkIpcProtocol.DESK_ANSWER, { writeByteArray(ByteArray(16) { 3 }); writeInt(1) }).readInt())
            // Unlocked: the card, read again and checked, and your yes.
            keyguard.setIsDeviceLocked(false); test.locked = false
            val v = call(b, LinkIpcProtocol.DESK, {}).let { r -> assertEquals(LinkIpcProtocol.REPLY_OK, r.readInt()); DeskView.decode(r.createByteArray()!!)!! }
            val card = v.cards.single()
            assertEquals(sam, card.pairing)
            assertEquals("Atwater", (card.request.body as EtaTell).place)
            assertEquals(DeskYes.GOT_IT, card.yes)
            val yes = call(b, LinkIpcProtocol.DESK_ANSWER, { writeByteArray(LinkBytes.unhex(card.id, 16)!!); writeInt(DeskChoice.YES.code) })
            assertEquals(LinkIpcProtocol.REPLY_OK, yes.readInt()); assertEquals(1, yes.readInt())
            assertEquals(0, test.box.count())
            assertEquals(DeskState.ANSWERED, test.state.desk.getValue(eta.id).state)
            // Not now on a proposal is a code-only line.
            assertEquals(1, call(b, LinkIpcProtocol.DESK_NOT_NOW, { writeByteArray(LinkBytes.unhex(sam, 16)!!); writeInt(LinkLevels.code(LinkCapability.ETA)) }).let { it.readInt(); it.readInt() })
        } finally {
            svc.onUnbind(null)
            LinkService.released(5)
            keyguard.setIsDeviceLocked(false)
            LinkSettingsStore.update(ctx) { it.copy(on = false) }
            LinkRuntime.node(ctx)
            LinkRuntime.opener = null
        }
    }

    /**
     * fix-link-unbind-141 (review-fix-tor-lows-141-d F1): onUnbind runs on :link's main thread, so it never waits for the
     * service's lock, which a call's node() holds while a turn-off's close() runs (up to 15 s): it returns at once, and
     * the release follows once the lock is free.
     */
    @Test fun onUnbindNeverWaitsBehindClose() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        val test = TestLinkNode(tmp.newFolder())
        LinkRuntime.opener = { test.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val svc = Robolectric.buildService(LinkService::class.java).create().get()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val closing = Thread { synchronized(svc) { entered.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        fun awake() = test.node.status().get(5, TimeUnit.SECONDS).awake
        try {
            val b = svc.onBind(null)
            val r = call(b, LinkIpcProtocol.MAKE_CODE, { writeInt(LinkMode.PRIVATE.code); writeInt(0) })
            assertEquals(LinkIpcProtocol.REPLY_OK, r.readInt())
            assertTrue("a call holds :link awake", awake())
            closing.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val unbind = Thread { svc.onUnbind(null) }
            unbind.start()
            unbind.join(2_000)
            assertFalse("onUnbind returns while close() holds the service's lock", unbind.isAlive)
            gate.countDown()
            closing.join(5_000)
            assertTrue(LinkService.released(5))
            assertFalse("released once the lock is free", awake())
        } finally {
            gate.countDown()
            LinkService.released(5)
            LinkSettingsStore.update(ctx) { it.copy(on = false) }
            LinkRuntime.node(ctx)
            LinkRuntime.opener = null
        }
    }

    /**
     * fix-link-unbind-141 (review F1): a client binding again before the last unbind's release has run keeps :link awake:
     * its call takes a hold of its own, the queued release leaves that hold alone, and only its own unbind lets :link sleep.
     */
    @Test fun aBindLandingBeforeTheReleaseKeepsLinkAwake() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        val test = TestLinkNode(tmp.newFolder())
        LinkRuntime.opener = { test.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val svc = Robolectric.buildService(LinkService::class.java).create().get()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        fun awake() = test.node.status().get(5, TimeUnit.SECONDS).awake
        try {
            val b = svc.onBind(null)
            fun make() = call(b, LinkIpcProtocol.MAKE_CODE, { writeInt(LinkMode.PRIVATE.code); writeInt(0) }).readInt()
            assertEquals(LinkIpcProtocol.REPLY_OK, make())
            assertTrue(awake())
            LinkService.onReleaser { started.countDown(); gate.await(10, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            svc.onUnbind(null) // its release waits behind the block above
            assertEquals(LinkIpcProtocol.REPLY_OK, make()) // a new client's call lands first
            gate.countDown()
            assertTrue(LinkService.released(5))
            assertTrue("the new client keeps :link awake", awake())
            svc.onUnbind(null)
            assertTrue(LinkService.released(5))
            assertFalse("its own unbind lets :link sleep", awake())
        } finally {
            gate.countDown()
            LinkService.released(5)
            LinkSettingsStore.update(ctx) { it.copy(on = false) }
            LinkRuntime.node(ctx)
            LinkRuntime.opener = null
        }
    }

    /**
     * fix-link-unbind-141-b (review-fix-link-unbind-141 L1): a stale call (sent before its client unbound) that takes a
     * hold after the unbind's release is let go at destroy, and one reaching a destroyed service is refused and holds
     * nothing, even while a release is still queued.
     */
    @Test fun aStaleCallLeavesNoHoldPastDestroy() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        val test = TestLinkNode(tmp.newFolder())
        LinkRuntime.opener = { test.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        fun awake() = test.node.status().get(5, TimeUnit.SECONDS).awake
        try {
            val svc = Robolectric.buildService(LinkService::class.java).create().get()
            val b = svc.onBind(null)
            fun make(on: IBinder) = call(on, LinkIpcProtocol.MAKE_CODE, { writeInt(LinkMode.PRIVATE.code); writeInt(0) }).readInt()
            assertEquals(LinkIpcProtocol.REPLY_OK, make(b))
            svc.onUnbind(null)
            assertTrue(LinkService.released(5))
            assertFalse(awake())
            assertEquals(LinkIpcProtocol.REPLY_OK, make(b)) // the stale call lands after the release
            assertTrue(awake())
            svc.onDestroy()
            assertTrue(LinkService.released(5))
            assertFalse("destroy lets the stale call's hold go", awake())
            // A second service: a stale call after its destroy, while the destroy's release still waits behind a block.
            val svc2 = Robolectric.buildService(LinkService::class.java).create().get()
            val b2 = svc2.onBind(null)
            assertEquals(LinkIpcProtocol.REPLY_OK, make(b2))
            LinkService.onReleaser { started.countDown(); gate.await(10, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            svc2.onUnbind(null)
            svc2.onDestroy()
            assertEquals("a destroyed service refuses", LinkIpcProtocol.REPLY_REFUSED, make(b2))
            gate.countDown()
            assertTrue(LinkService.released(5))
            assertFalse("nothing holds :link after destroy", awake())
        } finally {
            gate.countDown()
            LinkService.released(5)
            LinkSettingsStore.update(ctx) { it.copy(on = false) }
            LinkRuntime.node(ctx)
            LinkRuntime.opener = null
        }
    }

    @Test fun theFrameReaderChecksBeforeItMakesAnything() {
        val p = Parcel.obtain()
        p.writeInt(1 shl 30) // a byte array claiming a gigabyte
        p.setDataPosition(0)
        assertTrue(runCatching { IpcFrame(p).bytes(32 * 1024) }.isFailure)
        val q = Parcel.obtain()
        q.writeByteArray(ByteArray(16) { 7 }); q.writeString("Sam"); q.writeLong(5)
        q.setDataPosition(0)
        val f = IpcFrame(q)
        assertEquals("07".repeat(16), f.id())
        assertEquals("Sam", f.string(40))
        assertEquals(5L, f.long())
        f.end()
        assertTrue(runCatching { f.int() }.isFailure)
        val s = Parcel.obtain().apply { writeString("Samantha"); setDataPosition(0) }
        assertTrue(runCatching { IpcFrame(s).string(4) }.isFailure)
        assertArrayEquals(ByteArray(3) { 1 }, IpcFrame(Parcel.obtain().apply { writeByteArray(ByteArray(3) { 1 }); setDataPosition(0) }).bytes(3))
    }
}
