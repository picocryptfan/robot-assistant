// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

// Pure. Which windows the cover covers. The accessibility service gives only a window's
// package and class (it never reads what's on the screen); :lock fills in the facts it can read without a permission:
// the keyboard, the default dialer, the audio mode, the system Clock apps, the emergency dialer if it is a system app.

enum class GateDecision { IGNORE, ALLOW, COVER }

/**
 * What the gate decides on besides the window. [ownPackage]: Robot's application ID. [keyboard]: the current input
 * method's package. [emergencyDialer]: the package that handles com.android.phone.EmergencyDialer.DIAL, only when it is a
 * system app. [dialer]: the default dialer. [inCall]: the audio mode is ringing, in a call or screening one.
 * [dialerOpen]: the cover's Emergency call fell back to the dialer less than 3 minutes ago. [clocks]: SET_ALARM handlers
 * that are system apps. [permissionControllers]: the permission controller's packages. [allowed]: the active locks'
 * chosen apps and extras (LockSchedule.allowedPackages). [editSession]: Settings › Phone lock is open after the code.
 * [carOwnLock]: Robot's car mode, started by your remembered car, during your own lock (phase B). [carApps]: during
 * someone else's lock, what car mode adds (the maps app, phase B).
 */
data class GateFacts(
    val active: Boolean,
    val ownPackage: String,
    val keyboard: String? = null,
    val emergencyDialer: String? = null,
    val dialer: String? = null,
    val inCall: Boolean = false,
    val dialerOpen: Boolean = false,
    val clocks: Set<String> = emptySet(),
    val permissionControllers: Set<String> = PERMISSION_CONTROLLERS,
    val allowed: Set<String> = emptySet(),
    val editSession: Boolean = false,
    val carOwnLock: Boolean = false,
    val carApps: Set<String> = emptySet(),
) {
    companion object {
        val PERMISSION_CONTROLLERS = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    }
}

object LockGate {
    /** System windows that are never covered: the lock screen, the shade, the power menu and volume, Android, the share sheet. */
    val SYSTEM = setOf("com.android.systemui", "android", "com.android.intentresolver")
    /** Robot's windows that are covered: Home, Settings and the setup guide (MainActivity and its Robot Notes alias). */
    val ROBOT_COVERED = setOf("app.heyrobot.ui.MainActivity", "app.heyrobot.ui.NotesLauncher")
    /** The permission controller's one window an allowed app may need: its permission request. */
    const val GRANT_PERMISSIONS = "GrantPermissionsActivity"

    /**
     * Never open during a lock, whatever a lock allows (its chosen app, the extras, car mode's maps app, a file written
     * before this list grew or restored from a backup), and never offered by the lock editor's app picker: Android's
     * settings, app stores and installers, and the apps that can uninstall, stop or pause Robot from their own screens.
     * Every other app is covered anyway unless a lock allows it. 1.1's ten names are kept as they were; each one 1.2
     * (job 6b) added was read at the source named beside it on 20 Oct 2026, so a typo can't quietly leave one open. What
     * each app can do (below) is unchecked on a phone.
     */
    val NEVER_OPEN: Set<String> = setOf(
        // Android's settings (AOSP's package).
        "com.android.settings",
        // The package installers: AOSP's (GrapheneOS) and Google's (stock phones).
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        // App stores: Google Play, GrapheneOS Apps, F-Droid, Neo Store, Droid-ify, Aurora Store, Obtainium (1.1); 1.2:
        // F-Droid Basic (f-droid.org's API), Obtainium's F-Droid build (its build.gradle.kts, f-droid.org's API), Accrescent
        // (its build.gradle.kts).
        "com.android.vending", "app.grapheneos.apps", "org.fdroid.fdroid", "org.fdroid.basic", "com.machiav3lli.fdroid",
        "com.looker.droidify", "com.aurora.store", "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid", "app.accrescent.client",
        // One UI, each read in the Galaxy Store's own listing (appId, seller Samsung Electronics): Galaxy Store, Galaxy
        // Themes and Smart Switch install apps; Samsung Kids Installer installs Samsung Kids; Device Care stops apps and puts
        // them to sleep; Digital Wellbeing pauses apps; My Files lists unused apps to remove.
        "com.sec.android.app.samsungapps", "com.samsung.android.themestore", "com.sec.android.easyMover",
        "com.samsung.android.kidsinstaller", "com.samsung.android.lool", "com.samsung.android.forest", "com.sec.android.app.myfiles",
        // Stock Pixels, each read in its Google Play listing: Files by Google removes unused apps; Digital Wellbeing pauses
        // apps.
        "com.google.android.apps.nbu.files", "com.google.android.apps.wellbeing",
    )

    fun decide(pkg: CharSequence?, cls: CharSequence?, f: GateFacts): GateDecision {
        val p = pkg?.toString() ?: return GateDecision.IGNORE
        val c = cls?.toString().orEmpty()
        // 1. No lock, or the car's car mode during your own lock.
        if (!f.active || f.carOwnLock) return GateDecision.IGNORE
        // 2. The keyboard and the system's own windows.
        if (p == f.keyboard || p in SYSTEM) return GateDecision.IGNORE
        // 3. Robot: Home and Settings are covered (unless an edit session is open); the cover, the code pad, the panel and
        //    its windows, car mode's screen are allowed.
        if (p == f.ownPackage) return if (c in ROBOT_COVERED && !f.editSession) GateDecision.COVER else GateDecision.ALLOW
        // 4. The emergency dialer, a system app.
        if (p == f.emergencyDialer) return GateDecision.ALLOW
        // 5. The dialer, only while a call rings or is on, or just after the cover's Emergency call fell back to it.
        if (p == f.dialer) return if (f.inCall || f.dialerOpen || p in f.allowed) GateDecision.ALLOW else GateDecision.COVER
        // 6. 1.2: settings, stores, installers and the apps that can remove, stop or pause Robot, whatever a lock allows.
        if (p in NEVER_OPEN) return GateDecision.COVER
        // 7. Clock (system SET_ALARM handlers only).
        if (p in f.clocks) return GateDecision.ALLOW
        // 8. An allowed app asking for a permission; the rest of the permission controller is covered.
        if (p in f.permissionControllers) return if (c.substringAfterLast('.') == GRANT_PERMISSIONS) GateDecision.ALLOW else GateDecision.COVER
        // 9. The active locks' chosen apps and extras (and car mode's during someone else's lock).
        if (p in f.allowed || p in f.carApps) return GateDecision.ALLOW
        // 10. Everything else, the launcher included.
        return GateDecision.COVER
    }
}
