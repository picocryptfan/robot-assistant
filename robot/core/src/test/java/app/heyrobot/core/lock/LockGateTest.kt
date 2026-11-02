// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.lock.GateDecision.ALLOW
import app.heyrobot.core.lock.GateDecision.COVER
import app.heyrobot.core.lock.GateDecision.IGNORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule of the lock gate, in its order. */
class LockGateTest {
    // review-lock 7: the call's window came before the audio mode said so.
    @Test fun aCallStartingUncoversTheDialerAndNothingElse() {
        val dialer = "com.example.dialer"
        val waiting = f.copy(dialer = dialer, inCall = false, allowed = emptySet(), dialerActivities = emptySet())
        val call = waiting.copy(inCall = true)
        assertEquals(COVER, LockGate.decide(dialer, "x.InCallActivity", waiting))
        assertFalse(LockGate.callUncovers(dialer, "x.InCallActivity", waiting))
        assertTrue(LockGate.callUncovers(dialer, "x.InCallActivity", call))
        assertFalse(LockGate.callUncovers("com.example.other", "x.Main", call))
        assertFalse(LockGate.callUncovers(null, null, call))
    }

    private val f = GateFacts(
        active = true, ownPackage = "ai.robot_app", keyboard = "app.grapheneos.keyboard", emergencyDialer = "com.android.phone",
        dialer = "com.android.dialer", clocks = setOf("com.android.deskclock"), allowed = setOf("org.example.reader"),
    )
    private fun d(pkg: String?, cls: String? = null, facts: GateFacts = f) = LockGate.decide(pkg, cls, facts)

    /** fb11: a restarted :lock has lost dialerOpen: only the emergency dialer itself in front holds the cover back; nothing known doesn't. */
    @Test fun onlyTheEmergencyDialerInFrontHoldsARestartedCoverBack() {
        val facts = f.copy(emergencyDialer = "sys.emergency", dialer = "sys.dialer")
        assertEquals(true, LockGate.emergencyInFront("sys.emergency", facts))
        assertEquals(false, LockGate.emergencyInFront("sys.dialer", facts))
        assertEquals(false, LockGate.emergencyInFront(null, facts))
        assertEquals(false, LockGate.emergencyInFront(null, facts.copy(emergencyDialer = null)))
    }

    @Test fun noLockIgnoresEverything() {
        assertEquals(IGNORE, d("org.example.browser", facts = f.copy(active = false)))
        assertEquals(IGNORE, d(null))
    }

    @Test fun theKeyboardAndTheSystemsWindows() {
        assertEquals(IGNORE, d("app.grapheneos.keyboard"))
        for (p in listOf("com.android.systemui", "android", "com.android.intentresolver")) assertEquals(p, IGNORE, d(p, "x"))
    }

    @Test fun robotsOwnWindows() {
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.ui.MainActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.ui.NotesLauncher"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.ui.FilesLauncher"))
        // The backup window (:voice) is Robot's too.
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.backup.BackupActivity"))
        // 1.4 Lists: the window that picks a widget's list shows your lists' names; the home-screen app can open it.
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.ui.lists.ListsWidgetSetup"))
        // 1.1.2 (FA8f): the two windows where a key is pasted (:voice).
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.HelperConnectionActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.WebSearchActivity"))
        // Conversation mode's four windows (:voice).
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.conversation.ConversationActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.conversation.TranscriptionCheckActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.conversation.SummaryActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.conversation.VoiceprintActivity"))
        // The Cheogram pick, the media delete confirmation (:voice) and the share target.
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.CheogramPickActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.MediaDeleteActivity"))
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.ui.files.FilesShareActivity"))
        // Skills connections: a connection's window (:voice) pastes keys and runs Test; Settings opens it.
        assertEquals(COVER, d("ai.robot_app", "app.heyrobot.assistant.connect.ConnectionWindow"))
        for (c in listOf("app.heyrobot.lock.LockCoverActivity", "app.heyrobot.lock.LockCodeActivity", "app.heyrobot.assistant.PanelLaunchActivity",
            "app.heyrobot.assistant.car.CarModeActivity", "android.widget.FrameLayout")) assertEquals(c, ALLOW, d("ai.robot_app", c))
        // FA8f: each exempt window (ProcessIsolationTest checks the manifest against both lists) stays open.
        for (c in LockGate.ROBOT_EXEMPT.keys) assertEquals(c, ALLOW, d("ai.robot_app", c))
        // An edit session (the code, then Change locks) opens Settings for 5 minutes.
        for (c in LockGate.ROBOT_COVERED) assertEquals(c, ALLOW, d("ai.robot_app", c, f.copy(editSession = true)))
    }

    /** LS13: Robot's own word that one of its windows is in front names a covered window, else counts as Robot's app: it only covers. */
    @Test fun robotsWordThatAWindowIsInFrontOnlyCovers() {
        assertEquals(true, LockGate.ROBOT_APP in LockGate.ROBOT_COVERED)
        for (c in LockGate.ROBOT_COVERED) assertEquals(c, LockGate.saidInFront(c))
        for (c in LockGate.ROBOT_EXEMPT.keys + listOf("org.example.game.Main", "android.widget.FrameLayout", "")) assertEquals(c, LockGate.ROBOT_APP, LockGate.saidInFront(c))
        assertEquals(LockGate.ROBOT_APP, LockGate.saidInFront(null))
        for (c in LockGate.ROBOT_COVERED + LockGate.ROBOT_EXEMPT.keys + listOf(null, "org.example.game.Main"))
            assertEquals("$c", COVER, d("ai.robot_app", LockGate.saidInFront(c)))
    }

    @Test fun emergencyDialerOnlyWhenItIsASystemApp() {
        assertEquals(ALLOW, d("com.android.phone", "com.android.phone.EmergencyDialer"))
        // A non-system "emergency dialer" isn't passed in as one (:lock checks FLAG_SYSTEM): covered.
        assertEquals(COVER, d("org.example.fakedialer", facts = f.copy(emergencyDialer = null)))
    }

    @Test fun theDialerInAndOutOfACall() {
        assertEquals(COVER, d("com.android.dialer"))
        assertEquals(ALLOW, d("com.android.dialer", facts = f.copy(inCall = true)))
        assertEquals(ALLOW, d("com.android.dialer", facts = f.copy(dialerOpen = true)))
    }

    @Test fun duringACallAKnownDialerKeepsToItsCallScreen() {
        val inCall = "com.android.incallui.InCallActivity"
        val main = "com.android.dialer.main.impl.MainActivity"
        val call = f.copy(inCall = true, dialer = "com.google.android.dialer", dialerActivities = setOf(inCall, main))
        assertEquals(ALLOW, d("com.google.android.dialer", inCall, facts = call))
        assertEquals(COVER, d("com.google.android.dialer", main, facts = call))
        // A dialog isn't one of its activities; the call goes on under it.
        assertEquals(ALLOW, d("com.google.android.dialer", "android.app.AlertDialog", facts = call))
        // The activities couldn't be read: as a dialer it doesn't know, open.
        assertEquals(ALLOW, d("com.google.android.dialer", main, facts = call.copy(dialerActivities = emptySet())))
        // Another dialer's call screen isn't known: never covered during a call.
        assertEquals(ALLOW, d("org.example.dialer", "org.example.Screen", facts = call.copy(dialer = "org.example.dialer", dialerActivities = setOf("org.example.Screen"))))
        // No call: covered; the Emergency call fallback: open whole.
        assertEquals(COVER, d("com.google.android.dialer", inCall, facts = call.copy(inCall = false)))
        assertEquals(ALLOW, d("com.google.android.dialer", main, facts = call.copy(inCall = false, dialerOpen = true)))
    }

    @Test fun theKeyboardsOwnScreensAreCovered() {
        val kb = f.copy(keyboard = "org.example.keys", keyboardActivities = setOf("org.example.keys.SettingsActivity"))
        assertEquals(COVER, d("org.example.keys", "org.example.keys.SettingsActivity", facts = kb))
        assertEquals(GateDecision.IGNORE, d("org.example.keys", "android.inputmethodservice.SoftInputWindow", facts = kb))
        assertEquals(ALLOW, d("org.example.keys", "org.example.keys.SettingsActivity", facts = kb.copy(allowed = setOf("org.example.keys"))))
    }

    @Test fun aRingingClockHoldsTheCoverOnlyWithItsClockInFront() {
        val c = f.copy(clocks = setOf("com.android.deskclock"))
        assertEquals(true, LockGate.ringingInFront(true, "com.android.deskclock", c))
        assertEquals(true, LockGate.ringingInFront(true, null, c))
        assertEquals(false, LockGate.ringingInFront(true, "org.example.app", c))
        assertEquals(false, LockGate.ringingInFront(false, "com.android.deskclock", c))
    }

    @Test fun whatUsageAccessSaysIsInFront() {
        val e = listOf(UsageEvent(1, 1, "a", "a.A"), UsageEvent(3, 1, "b", "b.B"), UsageEvent(4, 2, "b", "b.B"), UsageEvent(2, 1, "c", "c.C"))
        assertEquals("b" to "b.B", ScreenTime.front(e))
        assertEquals(null, ScreenTime.front(listOf(UsageEvent(4, 2, "b", "b.B"))))
    }

    @Test fun clockAndAThirdPartyAlarmApp() {
        assertEquals(ALLOW, d("com.android.deskclock", "com.android.deskclock.alarms.AlarmActivity"))
        assertEquals(COVER, d("org.example.alarmclock"))
        assertEquals(ALLOW, d("org.example.alarmclock", facts = f.copy(allowed = setOf("org.example.alarmclock"))))
    }

    @Test fun grantPermissionsAgainstSafetyCenter() {
        assertEquals(ALLOW, d("com.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"))
        assertEquals(COVER, d("com.android.permissioncontroller", "com.android.permissioncontroller.safetycenter.ui.SafetyCenterActivity"))
        assertEquals(COVER, d("com.google.android.permissioncontroller", "x.ManagePermissionsActivity"))
    }

    @Test fun theLauncherTheInstallerSettingsAndTheExtras() {
        for (p in listOf("com.android.launcher3", "com.android.packageinstaller", "com.android.settings", "app.grapheneos.apps", "org.example.browser"))
            assertEquals(p, COVER, d(p, "x"))
        assertEquals(ALLOW, d("org.example.reader", "org.example.reader.Main"))
    }

    @Test fun carMode() {
        // The car's car mode during your own lock: nothing is covered. During someone else's: the maps app stays open.
        assertEquals(IGNORE, d("org.example.browser", facts = f.copy(carOwnLock = true)))
        assertEquals(ALLOW, d("org.example.maps", facts = f.copy(carApps = setOf("org.example.maps"))))
        assertEquals(COVER, d("org.example.browser", facts = f.copy(carApps = setOf("org.example.maps"))))
    }

    /**
     * 1.2 (job 6b): the never-open set, pinned name by name. Removing one loosens every lock, so it fails here first; adding
     * one is deliberate, with its source beside it in LockGate.
     */
    @Test fun theNeverOpenSetIsPinned() {
        // 1.1's ten, kept: the set only grows.
        val v11 = setOf("com.android.settings", "com.android.vending", "app.grapheneos.apps", "org.fdroid.fdroid", "com.aurora.store",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "dev.imranr.obtainium", "com.machiav3lli.fdroid", "com.looker.droidify")
        val v12 = setOf(
            // Stores missed in 1.1 (f-droid.org's API, the apps' own build files).
            "org.fdroid.basic", "dev.imranr.obtainium.fdroid", "app.accrescent.client",
            // One UI (the Galaxy Store's listings).
            "com.sec.android.app.samsungapps", "com.samsung.android.themestore", "com.sec.android.easyMover", "com.samsung.android.kidsinstaller",
            "com.samsung.android.lool", "com.samsung.android.forest", "com.sec.android.app.myfiles",
            // Stock Pixels (Google Play's listings).
            "com.google.android.apps.nbu.files", "com.google.android.apps.wellbeing",
        )
        assertEquals(v11 + v12, LockGate.NEVER_OPEN)
    }

    @Test fun neverOpenIsCoveredWhateverALockAllows() {
        for (p in LockGate.NEVER_OPEN) {
            // A lock's chosen app or extras (a file from 1.1 or a backup), car mode's maps app, a system app that says it is a clock.
            assertEquals(p, COVER, d(p, "x", f.copy(allowed = setOf(p))))
            assertEquals(p, COVER, d(p, "x", f.copy(carApps = setOf(p))))
            assertEquals(p, COVER, d(p, "x", f.copy(clocks = setOf(p))))
            assertEquals(p, COVER, d(p, "x", f.copy(allowed = setOf(p), carApps = setOf(p), clocks = setOf(p))))
        }
        // Samsung's own, with any window class.
        assertEquals(COVER, d("com.sec.android.app.samsungapps", "com.sec.android.app.samsungapps.Main", f.copy(allowed = setOf("com.sec.android.app.samsungapps"))))
        assertEquals(COVER, d("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity", f.copy(allowed = setOf("com.samsung.android.lool"))))
    }

    @Test fun theRulesBeforeItStillWin() {
        val p = "com.sec.android.app.samsungapps"
        // No lock, or the car's car mode during your own lock: nothing is covered, this set included.
        assertEquals(IGNORE, d(p, facts = f.copy(active = false, allowed = setOf(p))))
        assertEquals(IGNORE, d(p, facts = f.copy(carOwnLock = true)))
        // The other apps a lock allows stay allowed.
        assertEquals(ALLOW, d("org.example.reader", "x", f.copy(allowed = setOf("org.example.reader", p))))
    }

    @Test fun neverOpenHoldsNothingThatMustStayOpen() {
        // Emergency calls, calls, Clock, the keyboard and the system's own windows on GrapheneOS, stock Pixels and One UI
        // (their usual packages): none of them may ever be in the never-open set.
        val alwaysOpen = LockGate.SYSTEM + GateFacts.PERMISSION_CONTROLLERS + setOf(
            "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.android.server.telecom", "com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
            "app.grapheneos.keyboard", "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "ai.robot_app",
        )
        assertEquals(emptySet<String>(), LockGate.NEVER_OPEN intersect alwaysOpen)
    }
}
