// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.feedback.FeedbackCommand
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.ReportTurn
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.FeedbackFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A feedback phrase in the panel (PanelFeedback, from PanelController in 1.1): on the lock screen Robot only says to unlock
 * (through Phone lock's own sentence when it is on) and neither keeps your words nor opens anything; unlocked, your words
 * go into Robot's own prefill file before Send feedback opens with the kind, and the panel leaves only when the page
 * really opened. No test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelFeedbackTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private var locked = false
    private var lastTurn: app.heyrobot.core.feedback.ReportTurn? = null
    private var opens = true
    private var attached = true
    private var useShot: Boolean? = null
    private var held: android.graphics.Bitmap? = null
    private val launched = mutableListOf<Intent>()
    /** What the port was asked, in order: "reply <text> | <spoken>", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val host = object : PanelHost {
        override fun hidePanel() { calls += "hide" }
        override fun launch(intent: Intent): Boolean { launched += intent; return opens }
        override fun startCapture(intent: Intent) = false
    }

    private val feedback = PanelFeedback(object : PanelFeedback.Port {
        override val ctx get() = this@PanelFeedbackTest.ctx
        override val host get() = if (attached) this@PanelFeedbackTest.host else null
        override fun locked() = locked
        override fun lastTurn(replyId: String) = lastTurn
        override fun unlockFirst(p: Phrase) = Phrase.plain("[lock] " + p.render(Langs.EN))
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)} | ${spoken.render(Langs.EN)}" }
        override fun notify(p: Phrase) { calls += "notify ${p.render(Langs.EN)}" }
        override suspend fun leaveForApp() { calls += "leave" }
        override fun useScreenshot() = useShot
        override fun heldShot() = held?.let { it to "com.example.app" }
    })

    @Test fun aStaleTurnIsDeletedThoughThePageNeverOpens() {
        val f = java.io.File(app.heyrobot.sys.Paths.dir(ctx), ReportTurn.FILE)
        val at = 1_000_000L
        FeedbackFiles.keepTurn(ctx, ReportTurn("set a timer", "Timer set.", at = at))
        FeedbackFiles.dropStaleTurn(ctx, now = at + ReportTurn.FRESH_MS)
        assertTrue(f.exists())
        FeedbackFiles.dropStaleTurn(ctx, now = at + ReportTurn.FRESH_MS + 1)
        assertFalse(f.exists())
        // A file that isn't a turn, or one dated ahead (the clock set back), goes too.
        f.writeText("not json")
        FeedbackFiles.dropStaleTurn(ctx, now = at)
        assertFalse(f.exists())
        FeedbackFiles.keepTurn(ctx, ReportTurn("x", "y", at = at + 60_000))
        FeedbackFiles.dropStaleTurn(ctx, now = at)
        assertFalse(f.exists())
    }

    /** fix-seal-14 M1: the card's kept turn leaves no copy of your words or my answer outside History's vault. */
    @Test fun theKeptTurnHoldsNoWordsOutsideTheVault() {
        val t = inHistory("remind me about the pelican", "Pelican reminder set.")
        FeedbackFiles.keepTurn(ctx, t)
        val vault = app.heyrobot.sys.Paths.conversations(ctx).canonicalPath
        val plain = ctx.filesDir.walkTopDown().filter { f -> f.isFile && !f.canonicalPath.startsWith(vault) && String(f.readBytes()).contains("elican") }.toList()
        assertTrue(plain.toString(), plain.isEmpty())
        // The card reads the words back through History's vault.
        assertEquals(t, FeedbackFiles.takeTurn(ctx)?.turn)
    }

    /** fix-seal-14 M1: History can't give the turn (deleted, locked): kept, but none, so the report says so; a v1 file goes unread. */
    @Test fun aTurnHistoryCantGiveIsKeptAsMissingAndAnOldFileIsDeleted() {
        val t = inHistory("what's the pelican's name", "Percy.")
        FeedbackFiles.keepTurn(ctx, t)
        ConversationStore.delete(ctx, t.conv!!)
        assertEquals(app.heyrobot.core.feedback.KeptTurn(null), FeedbackFiles.takeTurn(ctx))
        val f = java.io.File(app.heyrobot.sys.Paths.dir(ctx), ReportTurn.FILE)
        f.writeText(ReportTurn("old words", "old answer", at = System.currentTimeMillis()).toJson())
        assertNull(FeedbackFiles.takeTurn(ctx))
        assertFalse(f.exists())
    }

    /** fix-bug-card-14 M2: the card's saved state keeps only where the turn is; recreated, it reads the words from History again. */
    @Test fun theTurnIsReadAgainFromHistoryAfterTheCardIsRecreated() {
        val t = inHistory("what's the heron's name", "Hal.")
        FeedbackFiles.keepTurn(ctx, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        assertNull(FeedbackFiles.takeTurnRef(ctx))
        assertFalse(r.toJson().contains("heron"))
        assertEquals(t, FeedbackFiles.turnFrom(ctx, app.heyrobot.core.feedback.ReportTurnRef.fromJson(r.toJson())!!))
    }

    /** fix-bug-card-14 M3: a turn History hasn't got (Save conversations off) still reaches the card, in memory only. */
    @Test fun anUnsavedTurnReachesTheCardInMemoryOnly() {
        val t = ReportTurn("what's the egret's name", "Eve.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val i = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        assertNull(FeedbackFiles.turnFrom(ctx, r))
        assertEquals(t, FeedbackFiles.cardTurn(ctx, r, i))
        assertFalse(app.heyrobot.sys.Paths.dir(ctx).walk().any { it.isFile && String(it.readBytes(), Charsets.ISO_8859_1).contains("egret") })
        // Another turn's ref doesn't take it; once the report goes it's forgotten; a stale one is never given.
        assertNull(FeedbackFiles.cardTurn(ctx, r.copy(at = r.at - 1), i))
        FeedbackFiles.dropHanded(i)
        assertNull(FeedbackFiles.cardTurn(ctx, r, i))
        val stale = t.copy(at = System.currentTimeMillis() - ReportTurn.FRESH_MS - 1)
        FeedbackFiles.keepTurn(ctx, stale)
        val si = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, stale)
        assertNull(FeedbackFiles.cardTurn(ctx, FeedbackFiles.takeTurnRef(ctx, stale.at)!!, si))
        assertNull(FeedbackFiles.cardTurn(ctx, r, FeedbackFiles.openIntent(ctx, FeedbackKind.BUG)))
    }

    /** fix-bug-card-14-b: the panel's screenshot reaches the card in memory (TurnHand), never as a file outside a vault. */
    @Test fun theScreenshotReachesTheCardInMemoryOnly() = runBlocking {
        useShot = true
        held = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val t = ReportTurn("what's the ibis's name", "Ike.", at = System.currentTimeMillis())
        feedback.report(t)
        assertEquals(1, launched.size)
        assertEquals(emptyList<String>(), ctx.filesDir.parentFile!!.walk().filter { it.isFile && it.name.startsWith("report_shot") }.map { it.name }.toList())
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        val got = FeedbackFiles.cardShot(r, launched[0])
        assertEquals("com.example.app", got?.second)
        assertTrue(got!!.first.isNotEmpty())
        assertNull(FeedbackFiles.cardShot(r.copy(token = "0".repeat(32)), launched[0]))
        FeedbackFiles.dropHanded(launched[0])
        assertNull(FeedbackFiles.cardShot(r, launched[0]))
    }

    /** fix-bug-card-14-b L-a: another app's Binder under the hand's key gives no turn (no token), and can't stall the card. */
    @Test fun anotherAppsHandIsNeverTaken() {
        val t = ReportTurn("what's the stork's name", "Sam.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        fun foreign(b: android.os.IBinder) = Intent().putExtras(android.os.Bundle().apply { putBinder("app.heyrobot.feedback.TURN_HAND", b) })
        val made = t.copy(answer = "Made up.")
        val old = object : android.os.Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean { reply?.writeString(made.toJson()); return true }
        }
        val guess = object : android.os.Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                reply?.writeString("0".repeat(32)); reply?.writeString(made.toJson()); reply?.writeString(made.toJson()); return true
            }
        }
        assertNull(FeedbackFiles.cardTurn(ctx, r, foreign(old)))
        assertNull(FeedbackFiles.cardTurn(ctx, r, foreign(guess)))
        val gate = java.util.concurrent.CountDownLatch(1)
        val stall = object : android.os.Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean { gate.await(10, java.util.concurrent.TimeUnit.SECONDS); return true }
        }
        val start = System.nanoTime()
        assertNull(FeedbackFiles.cardTurn(ctx, r, foreign(stall)))
        gate.countDown()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    /** fix-bug-card-14-b L-b: the hand forgets 10 minutes on by the clock that counts sleep, though the posted forget never ran. */
    @Test fun theHandForgetsByTheClockThatCountsSleep() {
        val t = ReportTurn("what's the crane's name", "Cy.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val i = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        assertEquals(t, FeedbackFiles.cardTurn(ctx, r, i))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ReportTurn.FRESH_MS + 1))
        assertNull(FeedbackFiles.cardTurn(ctx, r, i))
    }

    /** fix-bug-card-14-b L-c: a card recreated after the panel's process died finds no turn, and says it isn't included. */
    @Test fun aCardRecreatedAfterThePanelDiedHasTheTurnMissing() {
        val t = ReportTurn("what's the rail's name", "Ray.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val i = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        assertEquals(app.heyrobot.core.feedback.KeptTurn(t), FeedbackFiles.restoredTurn(ctx, r.toJson(), i))
        FeedbackFiles.dropHanded(i)
        assertEquals(app.heyrobot.core.feedback.KeptTurn(null), FeedbackFiles.restoredTurn(ctx, r.toJson(), i))
        assertNull(FeedbackFiles.restoredTurn(ctx, r.toJson(), i, r.at + ReportTurn.FRESH_MS + 1))
    }

    /** fix-bug-card-14-c Low 1: the card left without sending forgets the turn at once; a rotation keeps it. */
    @Test fun leavingTheCardForgetsTheTurnAtOnce() {
        val t = ReportTurn("what's the plover's name", "Pip.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val i = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        FeedbackFiles.leftCard(i, recreating = true)
        assertEquals(t, FeedbackFiles.cardTurn(ctx, r, i))
        FeedbackFiles.leftCard(i, recreating = false)
        assertNull(FeedbackFiles.cardTurn(ctx, r, i))
    }

    /** fix-bug-card-14-c Low 2: the hand is taken out of the intent, kept only when Robot launched the card; another app's can't swap it. */
    @Test fun onlyRobotsOwnLaunchHandsTheCardItsTurn() {
        val t = ReportTurn("what's the tern's name", "Tia.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val i = FeedbackFiles.openIntent(ctx, FeedbackKind.BUG, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        FeedbackFiles.takeHand(i, android.os.Process.myUid())
        assertNull(i.extras?.getBinder("app.heyrobot.feedback.TURN_HAND"))
        assertEquals(t, FeedbackFiles.cardTurn(ctx, r, FeedbackFiles.cardHand()))
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val other = object : android.os.Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean { asked.incrementAndGet(); return true }
        }
        val foreign = Intent().putExtras(android.os.Bundle().apply { putBinder("app.heyrobot.feedback.TURN_HAND", other) })
        FeedbackFiles.takeHand(foreign, android.os.Process.myUid() + 1)
        assertNull(foreign.extras?.getBinder("app.heyrobot.feedback.TURN_HAND"))
        val hand = FeedbackFiles.cardHand()
        assertEquals(t, FeedbackFiles.cardTurn(ctx, r, hand))
        FeedbackFiles.leftCard(hand, recreating = false)
        assertEquals(0, asked.get())
        assertNull(FeedbackFiles.cardTurn(ctx, r, hand))
        assertNull(FeedbackFiles.cardHand())
    }

    /** fix-bug-card-14-c Low 3: a Binder that never answers holds at most two of the card's threads, not one per open. */
    @Test fun aHandThatNeverAnswersHoldsAtMostTwoThreads() {
        val t = ReportTurn("what's the avocet's name", "Al.", at = System.currentTimeMillis())
        FeedbackFiles.keepTurn(ctx, t)
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        val gate = java.util.concurrent.CountDownLatch(1)
        val stall = object : android.os.Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                // As a real Binder call: an interrupt doesn't end it.
                val end = System.nanoTime() + 30_000_000_000L
                while (gate.count > 0 && System.nanoTime() < end) try { gate.await(50, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                return true
            }
        }
        val i = Intent().putExtras(android.os.Bundle().apply { putBinder("app.heyrobot.feedback.TURN_HAND", stall) })
        fun waiting() = Thread.getAllStackTraces().keys.filter { it.name == "report-hand" && it.isAlive }
        try {
            repeat(4) { assertNull(FeedbackFiles.cardTurn(ctx, r, i)) }
            assertTrue(waiting().size <= 2)
        } finally { gate.countDown(); waiting().forEach { it.join(5_000) } }
    }

    /** fix-bug-card-14-c: each screenshot region is closed once: the card's copy once read, the hand's when it forgets. */
    @Test fun eachScreenshotRegionIsClosedOnce() = runBlocking {
        useShot = true
        held = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        feedback.report(ReportTurn("what's the snipe's name", "Sid.", at = System.currentTimeMillis()))
        val r = FeedbackFiles.takeTurnRef(ctx)!!
        val base = closed.size
        assertTrue(FeedbackFiles.cardShot(r, launched[0]) != null)
        assertEquals(base + 1, closed.size)
        FeedbackFiles.dropHanded(launched[0])
        FeedbackFiles.dropHanded(launched[0])
        assertEquals(base + 2, closed.size)
        assertEquals(2, closed.drop(base).toSet().size)
    }

    /** fix-bug-card-14-c Low 4: a region whose setup fails is closed at once, not left to the collector. */
    @Test fun aRegionWhoseSetupFailsIsClosedAtOnce() {
        val base = closed.size
        assertNull(app.heyrobot.sys.TurnHand.share(byteArrayOf(1, 2, 3)) { throw IllegalStateException("seal") })
        assertEquals(base + 1, closed.size)
    }

    private fun inHistory(said: String, answer: String, conv: String = "c-report"): ReportTurn {
        val at = System.currentTimeMillis()
        assertTrue(ConversationStore.save(ctx, StoredConversation(conv, at, at, listOf(ChatTurn("u1", TurnRole.USER, said, at), ChatTurn("a1", TurnRole.ASSISTANT, answer, at)))))
        return ReportTurn.of(ConversationStore.load(ctx, conv)!!.turns, "a1", null, null, at)!!.copy(conv = conv)
    }

    init { app.heyrobot.sys.HistoryFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }

    // fix-bug-card-14-c: each region's close counted (left in place after: a JVM's SharedMemory.close() throws).
    private val closed = java.util.Collections.synchronizedList(ArrayList<android.os.SharedMemory>())
    @Before fun countCloses() { closed.clear(); FeedbackFiles.shotCloser = { closed += it } }

    @Before fun clean() { FeedbackFiles.takePrefill(ctx); FeedbackFiles.takeTurn(ctx) }
    @After fun cleanAfter() { FeedbackFiles.takePrefill(ctx); FeedbackFiles.takeTurn(ctx) }

    /** 1.4: locked, a bug keeps the turn before it 10 minutes and the card waits under the lock screen; your words don't. */
    /** fix-bug-card-14-b: an earlier build's screenshot files are deleted unread, at each start. */
    @Test fun anEarlierBuildsScreenshotFilesAreDeleted() {
        val names = listOf("report_shot.png", "report_shot.app", "report_shot.tmp")
        names.forEach { java.io.File(app.heyrobot.sys.Paths.dir(ctx), it).writeBytes(byteArrayOf(1, 2)) }
        FeedbackFiles.dropStaleTurn(ctx)
        assertTrue(names.none { java.io.File(app.heyrobot.sys.Paths.dir(ctx), it).exists() })
    }

    @Test fun lockedTheTurnWaitsForTheCard() = runBlocking {
        locked = true
        val t = inHistory("what's 2 plus 2", "5")
        lastTurn = t
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, "it said 5"))
        assertTrue(calls[0], calls[0].startsWith("reply [lock] "))
        assertEquals(1, launched.size)
        assertTrue(calls.toString(), "leave" !in calls)
        assertNull(FeedbackFiles.takePrefill(ctx))
        assertEquals(t, FeedbackFiles.takeTurn(ctx)?.turn)
        assertNull(FeedbackFiles.takeTurn(ctx))
    }

    @Test fun anIdeaKeepsNoTurn() = runBlocking {
        lastTurn = app.heyrobot.core.feedback.ReportTurn("hi", "Hello.", at = System.currentTimeMillis())
        feedback.run("r1", FeedbackCommand(FeedbackKind.IDEA, "more voices"))
        assertNull(FeedbackFiles.takeTurn(ctx))
    }

    @Test fun onTheLockScreenItOnlySaysToUnlock() = runBlocking {
        locked = true
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, "the timer card is cut off"))
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply [lock] ") && calls[0].contains(" | [lock] "))
        assertTrue(launched.isEmpty())
        assertNull(FeedbackFiles.takePrefill(ctx))
    }

    @Test fun unlockedYourWordsAreKeptAndThePageOpensWithItsKind() = runBlocking {
        feedback.run("r1", FeedbackCommand(FeedbackKind.IDEA, "a darker theme"))
        assertEquals(2, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply ") && !calls[0].contains("[lock]"))
        assertEquals("leave", calls[1])
        assertEquals(FeedbackKind.IDEA.name, launched.single().getStringExtra(AppPages.EXTRA_FEEDBACK_KIND))
        val kept = FeedbackFiles.takePrefill(ctx)!!
        assertEquals(FeedbackKind.IDEA, kept.kind)
        assertEquals("a darker theme", kept.text)
    }

    @Test fun thePanelStaysWhenThePageDidntOpen() = runBlocking {
        opens = false
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, null))
        assertEquals(1, launched.size)
        assertEquals(1, calls.size)
        attached = false
        feedback.run("r2", FeedbackCommand(FeedbackKind.BUG, null))
        assertEquals(1, launched.size)
        assertEquals(listOf("reply", "reply"), calls.map { it.substringBefore(' ') })
    }

    /** Item 4: locked, "unlock and the report opens" only once the card launched; else the sentence that names the page. */
    @Test fun lockedTheReportOpensOnUnlockOnlyOnceTheCardLaunched() = runBlocking {
        locked = true
        lastTurn = inHistory("what's 2 plus 2", "5")
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, null))
        val launchedOk = calls.last()
        opens = false
        feedback.run("r2", FeedbackCommand(FeedbackKind.BUG, null))
        val failed = calls.last()
        assertEquals(2, launched.size)
        assertTrue(launchedOk, launchedOk.startsWith("reply [lock] ") && !launchedOk.contains("Report a problem or send an idea"))
        assertTrue(failed, failed.startsWith("reply [lock] ") && failed.contains("Report a problem or send an idea"))
        FeedbackFiles.takeTurn(ctx)
        Unit
    }

    /** RP3: the bug button or a reply pressed and held: unlocked, the card opens with that turn and the panel goes. */
    @Test fun aTapOpensTheCardWithTheTurn() = runBlocking {
        val t = inHistory("what's 2 plus 2", "5")
        feedback.report(t)
        assertEquals(listOf("leave"), calls.toList())
        assertEquals(FeedbackKind.BUG.name, launched.single().getStringExtra(AppPages.EXTRA_FEEDBACK_KIND))
        assertEquals(t, FeedbackFiles.takeTurn(ctx)?.turn)
    }

    /** RP3: locked, a tap says Phone lock's sentence once, in a notice; the turn waits and the card opens on unlock. */
    @Test fun lockedATapSaysItOnceAndTheCardWaits() = runBlocking {
        locked = true
        val t = inHistory("what's 2 plus 2", "5")
        feedback.report(t)
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("notify [lock] ") && !calls[0].contains("Report a problem or send an idea"))
        assertEquals(1, launched.size)
        assertNull(FeedbackFiles.takePrefill(ctx))
        assertEquals(t, FeedbackFiles.takeTurn(ctx)?.turn)
        opens = false
        feedback.report(t)
        assertEquals(2, calls.size)
        assertTrue(calls[1], calls[1].startsWith("notify [lock] ") && calls[1].contains("Report a problem or send an idea"))
        FeedbackFiles.takeTurn(ctx)
        Unit
    }
}
