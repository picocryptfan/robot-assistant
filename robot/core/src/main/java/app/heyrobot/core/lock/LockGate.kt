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
    /** F97: the activities the keyboard's app declares: its own screens, covered as any app's; its input windows aren't one. */
    val keyboardActivities: Set<String> = emptySet(),
    /** F44: the activities the default dialer declares (empty when they can't be read). */
    val dialerActivities: Set<String> = emptySet(),
) {
    companion object {
        val PERMISSION_CONTROLLERS = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    }
}

object LockGate {
    /** System windows that are never covered: the lock screen, the shade, the power menu and volume, Android, the share sheet. */
    val SYSTEM = setOf("com.android.systemui", "android", "com.android.intentresolver")
    /**
     * Robot's windows that are covered: Home, Settings and the setup guide (MainActivity and its Robot Notes and Robot Files
     * aliases, which hold Lists' screens and Skills' connections screens too), the backup window (:voice), a connection's window
     * (:voice), where its key is pasted and Test runs, and (1.1.2, FA8f) every other window with something
     * of yours in it: the two where a key is pasted, conversation mode's four, the Cheogram pick, the media delete
     * confirmation and the share target; and 1.4's window that picks a home-screen widget's list (it shows your lists' names).
     */
    val ROBOT_COVERED = setOf("app.heyrobot.ui.MainActivity", "app.heyrobot.ui.NotesLauncher", "app.heyrobot.ui.FilesLauncher",
        "app.heyrobot.assistant.backup.BackupActivity",
        // Skills connections: a connection's key is pasted here and Test runs here (Settings opens it).
        "app.heyrobot.assistant.connect.ConnectionWindow",
        // A helper's API key and a search service's key are pasted here (Settings opens them by class name).
        "app.heyrobot.assistant.HelperConnectionActivity", "app.heyrobot.assistant.WebSearchActivity",
        // Conversation mode: the transcript screen and its microphone (the Quick Settings tile opens it from the shade,
        // which a lock leaves open; during a lock it already shows only new lines, as on the lock screen), Settings ›
        // Voice's transcription check, a transcript's summary (it can go to a stronger AI), and Your voice (the voiceprint).
        "app.heyrobot.assistant.conversation.ConversationActivity", "app.heyrobot.assistant.conversation.TranscriptionCheckActivity",
        "app.heyrobot.assistant.conversation.SummaryActivity", "app.heyrobot.assistant.conversation.VoiceprintActivity",
        // The panel's Cheogram pick asks "Remember as ...?" and writes People, which the lock keeps closed in Settings.
        "app.heyrobot.assistant.CheogramPickActivity",
        // Android's delete confirmation for a photo or video on a free_storage card: no lock needs it, so it stops here,
        // whichever package shows that confirmation on a phone.
        "app.heyrobot.assistant.MediaDeleteActivity",
        // Robot Files in another app's Share: it refuses during a lock itself, and is covered as well.
        "app.heyrobot.ui.files.FilesShareActivity",
        // The everyday set's Ask Robot (E7, D19): another app's text or file comes in here; it refuses during a lock itself, and is covered as well.
        "app.heyrobot.assistant.ask.AskRobotActivity",
        // 1.4 Lists: the window that picks a home-screen widget's list shows your lists' names; the home-screen app opens it.
        "app.heyrobot.ui.lists.ListsWidgetSetup",
        // The push connector's translucent window in :link. Robot doesn't open it (you choose in the Push app row), and a
        // lock has no use for it, so it stays covered (fails closed: no distributor chosen).
        "org.unifiedpush.android.connector.internal.LinkActivity")


    /**
     * Robot's windows the cover leaves open, each with why. The gate allows every Robot window not in [ROBOT_COVERED]
     * (the panel and Robot's dialogs have no class of their own to list), so this list is what ProcessIsolationTest
     * checks the merged manifest against: every activity and activity-alias there is covered or here, and a new window
     * fails the build, by name, until it is decided. When unsure, a window is covered, not listed here.
     */
    val ROBOT_EXEMPT: Map<String, String> = mapOf(
        "app.heyrobot.lock.LockCoverActivity" to
            "the cover itself", // l10n: not text (a build check's reason)
        "app.heyrobot.lock.LockCodeActivity" to
            "the code pad: it ends a lock early or opens Settings › Phone lock for an edit session", // l10n: not text (a build check's reason)
        "app.heyrobot.assistant.PanelLaunchActivity" to
            "shows nothing: asks the assistant service to show the panel, which a lock leaves open", // l10n: not text (a build check's reason)
        "app.heyrobot.assistant.headset.HeadsetEntryActivity" to
            "shows nothing: passes a headset's or car's voice button on to the panel", // l10n: not text (a build check's reason)
        "app.heyrobot.assistant.CaptureActivity" to
            "shows nothing: opens the camera, the photo picker or the file picker, decided by their own package", // l10n: not text (a build check's reason)
        "app.heyrobot.assistant.car.CarModeActivity" to
            "car mode's screen, open during a lock by design (phone-lock-design §9.3)", // l10n: not text (a build check's reason)
        "app.heyrobot.assistant.listen.ListenSheetActivity" to
            "another app's RECOGNIZE_SPEECH: open only for an app the lock allows (it refuses any other itself, and shows nothing of yours), everyday-set-plan 6.5", // l10n: not text (a build check's reason)
        "app.heyrobot.ui.tts.TtsCheckDataActivity" to
            "shows nothing: tells Android which of the voices are downloaded, and finishes", // l10n: not text (a build check's reason)
        "app.heyrobot.ui.tts.TtsSampleTextActivity" to
            "shows nothing: gives Android the sample sentence, and finishes", // l10n: not text (a build check's reason)
        "app.heyrobot.ui.tts.TtsInstallDataActivity" to
            "shows nothing: opens Settings, in MainActivity, which is covered", // l10n: not text (a build check's reason)
        "androidx.activity.ComponentActivity" to
            "debug builds only: the empty window the Compose UI tests start", // l10n: not text (a build check's reason)
    )
    /** Robot's own app, Home and Settings (MainActivity), which [ROBOT_COVERED] covers. */
    const val ROBOT_APP = "app.heyrobot.ui.MainActivity"

    /**
     * LS13: the window the gate decides on when Robot's own processes say one of their windows is in front during a lock
     * (:lock's FRONT signal). A covered window says so as it comes up, since Android may send Phone lock no event for it (a
     * data-sensitive window, LT8 step 4). Only a covered window says so; any other class, or none, counts as Robot's app,
     * covered too, so the word can only cover a window, never open one.
     */
    fun saidInFront(cls: String?): String = cls?.takeIf { it in ROBOT_COVERED } ?: ROBOT_APP

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

    /**
     * F44: the dialers whose call screen is known by class (their in-call activity, from AOSP's incallui). Any other dialer
     * stays open whole during a call, since covering its unknown call screen could stop you answering or hanging up:
     * Settings › Phone lock › What a lock can't stop says so.
     */
    val KNOWN_DIALERS = setOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer")

    private fun dialerOwnScreen(p: String, c: String, f: GateFacts): Boolean =
        p in KNOWN_DIALERS && c in f.dialerActivities && !c.contains("incall", ignoreCase = true)

    /**
     * F95: a lock starting now leaves the screen alone, until the next window decides, while an alarm sounds with its clock
     * in front or the window in front unknown. Another app in front is covered: its heads-up stays over the cover, and an
     * app's own alarm sound can't hold a cover back from itself.
     */
    fun ringingInFront(alarmSounding: Boolean, front: String?, f: GateFacts): Boolean =
        alarmSounding && (front == null || front in f.clocks)

    /** The emergency dialer is in front ([front]: what usage access says), so a restarted :lock doesn't bring the cover over it. */
    fun emergencyInFront(front: String?, f: GateFacts): Boolean = front != null && front == f.emergencyDialer

    /**
     * The dialer's window [pkg], [cls] was covered just before the audio mode said a call rings or is on (the window came
     * first): with the mode known it's the call's screen, so the cover goes. Only what the gate allows now, never a dialer's
     * own screen, and any other app stays covered.
     */
    fun callUncovers(pkg: String?, cls: String?, f: GateFacts): Boolean =
        f.inCall && pkg != null && pkg == f.dialer && decide(pkg, cls, f) == GateDecision.ALLOW

    fun decide(pkg: CharSequence?, cls: CharSequence?, f: GateFacts): GateDecision {
        val p = pkg?.toString() ?: return GateDecision.IGNORE
        val c = cls?.toString().orEmpty()
        // 1. No lock, or the car's car mode during your own lock.
        if (!f.active || f.carOwnLock) return GateDecision.IGNORE
        // 2. The keyboard and the system's own windows.
        if ((p == f.keyboard && c !in f.keyboardActivities) || p in SYSTEM) return GateDecision.IGNORE
        // 3. Robot: Home and Settings are covered (unless an edit session is open); the cover, the code pad, the panel and
        //    its windows, car mode's screen are allowed.
        if (p == f.ownPackage) return if (c in ROBOT_COVERED && !f.editSession) GateDecision.COVER else GateDecision.ALLOW
        // 4. The emergency dialer, a system app.
        if (p == f.emergencyDialer) return GateDecision.ALLOW
        // 5. The dialer, only while a call rings or is on, or just after the cover's Emergency call fell back to it.
        //    F44: during a call, on a dialer whose call screen is known by class, only that screen (and its dialogs, not activities).
        if (p == f.dialer) return if (f.dialerOpen || p in f.allowed || (f.inCall && !dialerOwnScreen(p, c, f))) GateDecision.ALLOW else GateDecision.COVER
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
