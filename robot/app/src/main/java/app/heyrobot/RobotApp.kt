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
        val p = getProcessName()
        RLog.i("proc_start", "proc" to RLog.Code(if (p.contains(':')) p.substringAfter(':') else "main"))
        strictMode()
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
