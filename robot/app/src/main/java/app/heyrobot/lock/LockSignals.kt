// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import app.heyrobot.core.RLog

// All processes. How the main process (Settings › Phone lock) and :voice reach :lock: explicit intents to its receiver and
// its two windows, named by class (as RoutineIntents names :voice's), none of them exported, so no other app can send
// them anything. :lock answers by writing state.json, which the others read (LockStore.readState, PhoneLockNow).

object LockSignals {
    /** :lock's manifest receiver: the exact alarm, boot, time and zone changes, an update, and these actions. */
    const val RECEIVER = "app.heyrobot.lock.LockAlarmReceiver"
    const val COVER = "app.heyrobot.lock.LockCoverActivity"
    const val LIST_WIDGET = "app.heyrobot.ui.lists.ListsWidgetProvider"
    const val CODE = "app.heyrobot.lock.LockCodeActivity"

    /** The emergency dialer's action, exported by the phone app with no permission. */
    const val EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"

    const val ACTION_FIRE = "app.heyrobot.lock.FIRE"
    /** locks.json changed (Settings): :lock reads it again, starts or ends locks, sets its alarm again. */
    const val ACTION_LOCKS = "app.heyrobot.lock.LOCKS"
    /** You left Settings › Phone lock: the edit session ends. */
    const val ACTION_EDIT_END = "app.heyrobot.lock.EDIT_END"
    /**
     * Robot's own app ([MAIN]) is in front during a lock, since [EXTRA_AT]: it came to the front, or the edit session's 5
     * minutes ran out with it in front. Android doesn't always send a window event for it (back from the code pad, on a
     * Pixel 8), so :lock's gate looks at it from this too.
     */
    const val ACTION_FRONT = "app.heyrobot.lock.FRONT"
    /** With [ACTION_FRONT], and with [ACTION_EDIT_END] when Robot's app was in front as the session ended: since when (SystemClock.uptimeMillis). */
    const val EXTRA_AT = "at"
    /** LS13: with [ACTION_FRONT], the covered window in front (its class); none: Robot's app ([MAIN]). */
    const val EXTRA_WINDOW = "window"
    /** Robot's own app: Home and Settings (an event names an activity-alias's window by this class too). */
    const val MAIN = "app.heyrobot.ui.MainActivity"
    /** Phase B (LockRouter's card): a lock by voice until [EXTRA_END] (wall-clock ms). */
    const val ACTION_START_VOICE = "app.heyrobot.lock.START_VOICE"
    const val EXTRA_END = "end"
    /** review-lock 9: Robot opened the emergency dialer during a lock: :lock keeps that in state.json. */
    const val ACTION_EMERGENCY_OPENED = "app.heyrobot.lock.EMERGENCY_OPENED"
    /** Phase B (§9.3): Robot's car mode started ([EXTRA_ON]) or ended, and whether your remembered car started it ([EXTRA_BY_CAR]). */
    const val ACTION_CAR = "app.heyrobot.lock.CAR"
    const val EXTRA_ON = "on"
    const val EXTRA_BY_CAR = "by_car"

    /** The code pad's purpose ([EXTRA_PURPOSE]) and, for a skip, the lock ([EXTRA_LOCK]). */
    const val EXTRA_PURPOSE = "purpose"
    const val EXTRA_LOCK = "lock"
    /** The code opens a 5-minute edit session (Settings › Phone lock). */
    const val PURPOSE_EDIT = "edit"
    /** The code opens an edit session and Settings › Phone lock (the cover's Change locks). */
    const val PURPOSE_CHANGE = "change"
    /** The code ends every active lock (the cover's End early). */
    const val PURPOSE_END = "end"
    /** The code skips the lock's next time (a warning's Skip this time). */
    const val PURPOSE_SKIP = "skip"
    /** A new code, typed twice (Settings: Set a code, or Change the code during an edit session). */
    const val PURPOSE_SET = "set"
    /** The code removed (during an edit session). */
    const val PURPOSE_REMOVE = "remove"
    /**
     * 1.1, a restore's Phone lock row: the code only checked, nothing else done; the backup window
     * (:voice) takes RESULT_OK as CategoryChoice.codeOk. The code itself never leaves :lock.
     */
    const val PURPOSE_RESTORE = "restore"

    private fun send(ctx: Context, action: String, at: Long? = null, window: String? = null) {
        try {
            ctx.sendBroadcast(Intent(action).setClassName(ctx.packageName, RECEIVER).apply {
                at?.let { putExtra(EXTRA_AT, it) }
                window?.let { putExtra(EXTRA_WINDOW, it) }
            })
        } catch (t: Throwable) {
            RLog.w("lock_signal", t)
        }
    }

    fun locksChanged(ctx: Context) = send(ctx, ACTION_LOCKS)

    /** [VOICE] Car mode started or ended. */
    fun car(ctx: Context, on: Boolean, byCar: Boolean) {
        try {
            ctx.sendBroadcast(Intent(ACTION_CAR).setClassName(ctx.packageName, RECEIVER).putExtra(EXTRA_ON, on).putExtra(EXTRA_BY_CAR, byCar))
        } catch (t: Throwable) {
            RLog.w("lock_signal", t)
        }
    }

    /** [VOICE] An emergency number asked of Robot during a lock opened the emergency dialer. */
    fun emergencyOpened(ctx: Context) = send(ctx, ACTION_EMERGENCY_OPENED)

    /** [VOICE] LockRouter's card, after you tapped Lock: a lock by voice until [end] (wall-clock ms). */
    fun startVoice(ctx: Context, end: Long) {
        try {
            ctx.sendBroadcast(Intent(ACTION_START_VOICE).setClassName(ctx.packageName, RECEIVER).putExtra(EXTRA_END, end))
        } catch (t: Throwable) {
            RLog.w("lock_signal", t)
        }
    }
    /** You left Settings › Phone lock. [inFront]: Robot's app is still in front, so the gate looks at it as the session ends. */
    fun editEnded(ctx: Context, inFront: Boolean = false) = send(ctx, ACTION_EDIT_END, if (inFront) SystemClock.uptimeMillis() else null)

    /**
     * [MAIN, VOICE] Robot's app, or the covered [window] (LS13, LockFrontWatch), is in front since [at]
     * (SystemClock.uptimeMillis), during a lock.
     */
    fun front(ctx: Context, at: Long, window: String? = null) = send(ctx, ACTION_FRONT, at, window)

    /** The code pad for [purpose] ([lock]: the lock a skip is for). */
    fun code(ctx: Context, purpose: String, lock: String? = null): Intent =
        Intent().setClassName(ctx.packageName, CODE).putExtra(EXTRA_PURPOSE, purpose).apply { lock?.let { putExtra(EXTRA_LOCK, it) } }

    fun cover(ctx: Context): Intent = Intent().setClassName(ctx.packageName, COVER)

    /**
     * The phone's own emergency dialer, with the number in [data] filled in when given, which a lock never covers. Only a
     * system app's, named outright, so an app that claims the action can't take the call or see the number. Null when the
     * phone has none. The cover's Emergency call and an emergency number asked of Robot during a lock both open it.
     */
    fun emergencyDialer(ctx: Context, data: Uri? = null): Intent? {
        val i = Intent(EMERGENCY_DIAL, data)
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val a = try {
            ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_SYSTEM_ONLY).map { it.activityInfo }
                .firstOrNull { (it.applicationInfo.flags and system) != 0 }
        } catch (_: Throwable) { null } ?: return null
        return i.setClassName(a.packageName, a.name)
    }
}
