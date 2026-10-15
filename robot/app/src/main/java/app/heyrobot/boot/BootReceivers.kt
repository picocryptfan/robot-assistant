// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.core.RepairWhen
import app.heyrobot.sys.BootCheck
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.update.Updater

// MAIN PROCESS. The restart fallback (spec item 9), as in the probe; and the same after an update, which ends every Robot
// process as a restart does.

/**
 * What Robot does after a restart ("Survive restarts" in the README), and after an update of itself: the assistant check 3
 * minutes later ([BootCheck], not before the first unlock), and the speech recognizer setting that keeps Robot the assistant
 * (RecognizerKeeper; only with the adb grant), on a worker thread; then [more], there too. Starts nothing: the system binds
 * the assistant, and with it the wake word, by itself.
 */
internal fun BroadcastReceiver.afterRestart(ctx: Context, why: RepairWhen, more: (Context) -> Unit = {}) {
    val locked = why == RepairWhen.LOCKED_BOOT
    if (!locked) BootCheck.schedule(ctx, afterUpdate = why == RepairWhen.UPDATE)
    val done = goAsync()
    Thread({
        try {
            if (!locked) RecognizerKeeper.flushPending(ctx)
            RecognizerKeeper.check(ctx, why)
        } catch (t: Throwable) {
            RLog.w("boot_recognizer", t)
        }
        try {
            more(ctx.applicationContext)
        } catch (t: Throwable) {
            RLog.w("boot_more", t)
        } finally {
            done.finish()
        }
    }, "robot-boot-recognizer").start()
}

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
        afterRestart(ctx, if (locked) RepairWhen.LOCKED_BOOT else RepairWhen.BOOT)
    }
}

/**
 * MY_PACKAGE_REPLACED: Robot was updated (by its updater, or any other way), which ended its processes. The same as after a
 * restart ([afterRestart]: the system binds the assistant and its wake word again by itself), then the updater's part: its
 * downloads go, its daily check is scheduled again, and "Robot updated to …" is said once (Updater.afterUpdate). Not
 * exported: Android sends this to Robot alone.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        RLog.i("updated")
        afterRestart(ctx, RepairWhen.UPDATE) { Updater.afterUpdate(it) }
    }
}

/** 3 minutes after BOOT_COMPLETED (or an update): notifies if the assistant did not come back. Never binds it itself. */
class BootCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val afterUpdate = intent.getBooleanExtra(BootCheck.EXTRA_UPDATE, false)
        val done = goAsync()
        Thread({
            try { BootCheck.run(app, afterUpdate) } catch (t: Throwable) { RLog.w("boot_check", t) } finally { done.finish() }
        }, "robot-boot-check").start()
    }
}
