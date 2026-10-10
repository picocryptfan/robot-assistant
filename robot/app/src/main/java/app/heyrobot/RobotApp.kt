package app.heyrobot

import android.app.Application
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
    }
}
