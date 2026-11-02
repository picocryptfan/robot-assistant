// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import android.app.Application
import android.os.Build
import android.os.StrictMode
import app.heyrobot.core.RLog

/**
 * Runs in every process (main, :voice, :brain). It must stay free of engine references: it is the first
 * code of the UI process, and ProcessIsolationTest checks it.
 */
class RobotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app.heyrobot.core.AppId.bind(BuildConfig.APPLICATION_ID)
        val p = getProcessName()
        RLog.i("proc_start", "proc" to RLog.Code(if (p.contains(':')) p.substringAfter(':') else "main"))
        // 1.1: a restore's settings files and locks.json, staged by :voice, written by their one writer (sys/RestoreApply).
        if (!p.contains(':')) app.heyrobot.sys.RestoreApply.recover(this)
        if (!p.contains(':')) dropOldRestoredLinks()
        // 1.4: Robot Files' recovery and its screen-off and unlock hooks, in main and :voice only (sys/FilesVault.kt).
        app.heyrobot.sys.RobotFiles.start(this)
        // 1.4 Lists: the RAM copy dropped at screen-off and, in :voice, the journal merged after each unlock (sys/ListsVault.kt).
        app.heyrobot.sys.ListsFiles.start(this)
        // 1.4 Lists (LS6): the home-screen widgets redrawn after each change, at screen-off, screen-on and unlock (ui/lists/ListsWidget.kt).
        app.heyrobot.ui.lists.ListsWidgets.start(this)
        // LS13: every window Phone lock covers tells :lock it is in front, whatever task it runs in (lock/LockFrontWatch.kt).
        app.heyrobot.lock.LockFrontWatch.start(this)
        // The everyday set: What went online's writer, in main, :voice and :link only (sys/NetLog.kt).
        app.heyrobot.sys.NetLog.start(this)
        strictMode()
    }

    /** L16: Robot to Robot's restored file, plain, never read because it was never turned on: gone after 30 days. */
    private fun dropOldRestoredLinks() {
        val f = java.io.File(filesDir, app.heyrobot.core.link.LinkBackupCodec.PENDING)
        if (f.isFile && app.heyrobot.core.link.LinkBackupCodec.pendingTooOld(f.lastModified(), System.currentTimeMillis())) f.delete()
    }

    /**
     * 1.0, debug builds only: StrictMode writes disk and network use on a main thread, leaked resources and the like to
     * Android's log, never a crash or a dialog. Not under Robolectric (the JVM tests), whose Android isn't a phone's.
     */
    private fun strictMode() {
        if (!BuildConfig.DEBUG || Build.FINGERPRINT == "robolectric") return
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}
