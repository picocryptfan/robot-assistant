package app.heyrobot.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.sys.BootCheck

// MAIN PROCESS. The restart fallback (spec item 9), as in the probe.

/** BOOT_COMPLETED: schedules [BootCheckReceiver] 3 minutes later. Starts nothing: the system binds the assistant by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        RLog.i("boot")
        BootCheck.schedule(ctx)
    }
}

/** 3 minutes after BOOT_COMPLETED: notifies if the assistant did not come back. Never binds it itself. */
class BootCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val done = goAsync()
        Thread({
            try { BootCheck.run(app) } catch (t: Throwable) { RLog.w("boot_check", t) } finally { done.finish() }
        }, "robot-boot-check").start()
    }
}
