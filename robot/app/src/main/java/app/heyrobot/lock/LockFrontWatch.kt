// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import app.heyrobot.core.lock.LockGate

// MAIN AND :VOICE (RobotApp starts it in every process; only their windows are covered). LS13 (LT8 step 4 on the Pixel 8):
// during a lock, every window Phone lock covers tells :lock it is in front as it comes to the front, as MainActivity does
// (ui/settings PhoneLockPages, which also times the edit session's end), so the cover never rests on Android's window
// event alone. Android sends none for a data-sensitive window to a service that isn't an accessibility tool (Phone lock's
// isn't, on purpose), and not always one for a window coming back. Whatever task or process the window runs in, :lock's
// gate decides on its class, as on an event, and a class it doesn't cover counts as Robot's app (core LockGate.saidInFront).

object LockFrontWatch {
    /** The process's Application once it is watched (one per process on a phone; a test's runner makes one per test). */
    @Volatile private var watched: Application? = null

    fun start(app: Application) {
        if (watched === app) return
        watched = app
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Whether [window] (an activity's class) tells :lock itself: every covered window but Robot's app, which PhoneLockPages tells. */
    fun tells(window: String): Boolean = window in LockGate.ROBOT_COVERED && window != LockSignals.MAIN

    /** [a] came to the front: while Phone lock is on, :lock hears so (the files off the main thread). */
    internal fun resumed(a: Activity) {
        val window = a.javaClass.name
        if (!tells(window)) return
        val ctx = a.applicationContext
        val at = SystemClock.uptimeMillis()
        Thread({ if (mayLock(ctx)) LockSignals.front(ctx, at, window) }, "robot-lock-front").start()
    }

    /**
     * Phone lock is on (or locks.json can't be read), so a lock may be on: only :lock knows, and its gate decides, so a word
     * too many costs nothing (as PhoneLockPages decides for Robot's app).
     */
    private fun mayLock(ctx: Context): Boolean = LockStore.mayLock(ctx)

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = resumed(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
