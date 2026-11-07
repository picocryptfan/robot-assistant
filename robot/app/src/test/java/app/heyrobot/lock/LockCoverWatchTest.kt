// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import app.heyrobot.Flat
import app.heyrobot.Source
import app.heyrobot.assertHas
import app.heyrobot.assertInOrder
import org.junit.Test
import java.io.File

/** What needs a phone's window events and threads, read from the source; LockCoverServiceTest runs the cover's start, and the gate's part is core LockGateTest. */
class LockCoverWatchTest {
    private val root = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot")
    private fun flat(p: String) = Flat(Source.strip(File(root, p).readText(), keepStrings = true))
    private val service get() = flat("lock/LockAccessibilityService.kt")

    @Test fun onConnectWithALockOnTheCoverComesBack() {
        assertInOrder(service.body("override fun onServiceConnected("), "LockEngine.refresh(applicationContext)", "if (s.active) onEngine(s, startedNow = !emergency)")
    }

    /** fb13: an answer taken off the shade by a lock, or stopped by one on its way up, comes back at the lock's end. */
    @Test fun aReplyALockTookOrStoppedIsPostedAfterIt() {
        val notices = flat("assistant/LinkNotices.kt")
        assertInOrder(notices.body("private fun replies("), "if (!post(ctx, REPLIED_ID,", "return LinkShade.oweReply(ctx) LinkShade.replyPaid(ctx)", "client.seen(x.entry.id)")
        assertInOrder(notices.body("private fun pass("), "if (Assistant.locked(ctx)) return", "val owed = LinkShade.replyOwed(ctx)", "n.waiting == 0 && !owed", "if (owed || now.any { it.kind == DeskNoticeKind.REPLIED })")
        assertInOrder(notices.body("private fun post("), "PhoneLockNow.on(ctx)) return false", ".notify(id, b.build())", "cancel(ctx, id); return false", "return true")
        assertInOrder(flat("sys/LinkShade.kt").body("fun hide("), "nm.activeNotifications.any { it.id == REPLIED_ID }", "oweReply(ctx)", "nm.cancel(REPLIED_ID)")
        assertHas(flat("lock/LockEngine.kt"), "if (!snap.active && widgetsActive == true) app.heyrobot.sys.Cmd.send(ctx, app.heyrobot.sys.Cmd.LINK_DESK)")
    }

    /** fb11: :lock died while the emergency dialer was up; its in-memory dialerOpen is gone, so what's in front decides. */
    @Test fun onConnectTheCoverLeavesTheEmergencyDialerAlone() {
        val connect = service.body("override fun onServiceConnected(")
        assertInOrder(connect, "LockEngine.usageFront(applicationContext)", "LockGate.emergencyInFront(", "if (s.active) onEngine(s, startedNow = ")
    }

    /** fb11, F6: a Ready card's tap reads the same narrowed settings as the brief and the runner ("run <routine>": AskedRoutineSomeoneElseTest). */
    @Test fun aReadyTapDuringSomeoneElsesLockKeepsToTheLockScreen() {
        val panel = flat("assistant/routines/PanelRoutines.kt")
        assertHas(panel.body("private suspend fun ready("), "RoutineGate.decide(c.step, RunOrigin.READY_TAP, host.locked || someoneElse, routine, VoiceHub.settings, rs)")
    }

    /** That the cover itself tells the app below nothing, and when it comes up: LockCoverServiceTest. */
    @Test fun aLockEndingForgetsTheLastAllowedApp() {
        assertInOrder(service.body("fun onEngine("), "if (!s.active) {", "lastAllowed = null", "lastAllowedClass = null", "removeCurtain()")
    }

    @Test fun aRingingClockHoldsBackTheStartsCover() {
        assertInOrder(service.body("fun onEngine("), "LockGate.ringingInFront(alarmSounding(), frontPkg, facts)", "!held && !facts.carOwnLock) showCover()")
    }

    @Test fun aCoverThatStoppedWithNoEventAsksUsageAccessAndFailsClosed() {
        assertHas(flat("lock/LockCoverActivity.kt").body("override fun onStop("), "coverStopped(own =")
        assertInOrder(service.body("private fun afterCoverStopped("), "if (coverUp || !watching", "frontAt > coverUpAt", "LockEngine.usageFront(", "?: LockSignals.MAIN")
    }

    @Test fun theListWidgetIsDrawnAgainWhenALockStartsOrEnds() {
        assertInOrder(flat("lock/LockEngine.kt").body("fun refresh("), "snapshot = snap", "if (snap.active != widgetsActive)", "redrawWidgets(ctx)")
        assertHas(flat("lock/LockEngine.kt").body("private fun redrawWidgets("), "LockSignals.LIST_REDRAW_ACTION", "LockSignals.TODAY_REFRESH")
    }

    @Test fun aRoutineDuringSomeoneElsesLockDropsWhatWouldWait() {
        val run = flat("assistant/routines/RoutineRunner.kt").body("fun run(")
        assertInOrder(run, "PhoneLockNow.someoneElse(ctx)", "SomeoneElseLock.routines(store.settings(), someoneElse)", "Assistant.keyguard(ctx) || someoneElse",
            "SomeoneElseLock.spoken(all, someoneElse)", "if (!someoneElse) waiting += p.step", "if (!someoneElse) heldParts += held")
        assertInOrder(flat("assistant/routines/PanelRoutines.kt").body("private suspend fun brief("), "SomeoneElseLock.routines(store().settings(), someoneElse)", "host.locked || someoneElse", "held.isNotEmpty() && !someoneElse")
    }

    @Test fun anAppListeningDuringSomeoneElsesLockMustBeOneItAllows() {
        val mic = flat("assistant/RobotRecognitionService.kt")
        assertInOrder(mic.body("fun callerMay("), "if (lockRefuses(ctx, pkg)) return false", "noteProxyOpNoThrow")
        assertInOrder(mic.body("fun callerStillMay("), "!lockRefuses(ctx, pkg)", "unsafeCheckOpNoThrow")
        assertHas(mic, "PhoneLockNow.someoneElse(ctx) && pkg !in PhoneLockNow.apps(ctx)")
    }

    @Test fun aNoticesActionDuringALockKeepsTheNotice() {
        assertInOrder(flat("assistant/FilingNotices.kt").body("fun act("), "RobotFiles.locked(ctx)", "held.remove(")
        // The token is spent in tap, and only by an answer that went (FB4), after act's lock check (F99).
        assertInOrder(flat("assistant/LinkNotices.kt").body("fun act("), "Assistant.locked(ctx)", "tap(ctx, token)")
        assertHas(flat("assistant/LinkNotices.kt").body("fun tap("), "if (answer(h.card, h.choice)) { synchronized(held) { held.remove(token) }")
    }
}
