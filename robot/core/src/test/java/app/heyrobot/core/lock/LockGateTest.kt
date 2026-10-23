// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.lock.GateDecision.ALLOW
import app.heyrobot.core.lock.GateDecision.COVER
import app.heyrobot.core.lock.GateDecision.IGNORE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every rule of the lock gate, in its order. */
class LockGateTest {
    private val f = GateFacts(
        active = true, ownPackage = "ai.robot_app", keyboard = "app.grapheneos.keyboard", emergencyDialer = "com.android.phone",
        dialer = "com.android.dialer", clocks = setOf("com.android.deskclock"), allowed = setOf("org.example.reader"),
    )
    private fun d(pkg: String?, cls: String? = null, facts: GateFacts = f) = LockGate.decide(pkg, cls, facts)

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
        for (c in listOf("app.heyrobot.lock.LockCoverActivity", "app.heyrobot.lock.LockCodeActivity", "app.heyrobot.assistant.PanelLaunchActivity",
            "app.heyrobot.assistant.car.CarModeActivity", "android.widget.FrameLayout")) assertEquals(c, ALLOW, d("ai.robot_app", c))
        // An edit session (the code, then Change locks) opens Settings for 5 minutes.
        assertEquals(ALLOW, d("ai.robot_app", "app.heyrobot.ui.MainActivity", f.copy(editSession = true)))
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
