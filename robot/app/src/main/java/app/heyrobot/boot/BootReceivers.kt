package app.heyrobot.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.core.RepairWhen
import app.heyrobot.sys.BootCheck
import app.heyrobot.sys.RecognizerKeeper

// MAIN PROCESS. The restart fallback (spec item 9), as in the probe.

/**
 * BOOT_COMPLETED: schedules [BootCheckReceiver] 3 minutes later. Starts nothing: the system binds the assistant by itself.
 * Since 0.2.3 also LOCKED_BOOT_COMPLETED (direct-boot aware), and both check the speech recognizer setting that keeps
 * Robot the assistant at the next restart (RecognizerKeeper; only with the adb grant).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val locked = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (!locked && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        RLog.i("boot", "locked" to locked)
        if (!locked) BootCheck.schedule(ctx)
        val done = goAsync()
        Thread({
            try {
                if (!locked) RecognizerKeeper.flushPending(ctx)
                RecognizerKeeper.check(ctx, if (locked) RepairWhen.LOCKED_BOOT else RepairWhen.BOOT)
            } catch (t: Throwable) {
                RLog.w("boot_recognizer", t)
            } finally {
                done.finish()
            }
        }, "robot-boot-recognizer").start()
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
