// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.GateDecision
import app.heyrobot.core.lock.GateFacts
import app.heyrobot.core.lock.LockGate
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.sys.ProtectedBroadcasts

// :LOCK PROCESS ONLY. Phone lock's accessibility service: it sees which app comes to the front and
// nothing else. Its XML asks for window-state events only, no window content (canRetrieveWindowContent false), no
// gestures, and it isn't an accessibility tool. From an event it reads the type, the package and the class, never the
// text, the source node or a description (ProcessIsolationTest scans this file for that). While no lock is on or due
// within 10 minutes it asks for no events at all. During a lock, a window that isn't allowed (core LockGate) gets the
// curtain at once and the cover right after; the curtain comes off when the cover shows, on any allowed window, or after
// a second. While the keyguard is locked neither comes: the lock screen is never covered, and the unlock decides.

class LockAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var curtain: View? = null
    private val curtainOff = Runnable { removeCurtain() }
    /** The last allowed app's package (§5.2): on unlock during a lock, the cover shows unless it is still allowed. */
    private var lastAllowed: String? = null
    private var lastAllowedClass: String? = null
    /**
     * The window in front as the last event showed it, known only while window events are on. The gate looks at it again
     * when the edit session or car mode your car started ends, and at the unlock. The keyboard, the system's windows (the
     * lock screen, the shade) and Robot's own dialogs and panel sit above it, so they don't replace it.
     */
    private var frontPkg: String? = null
    private var frontCls: String? = null
    /** What the last engine change said of the edit session and of car mode your car started (main thread). */
    private var wasEdit = false
    private var wasCar = false
    /** Robot's activities by class (an event names an activity-alias's window by its target's class). */
    private val ownActivities: Set<String> by lazy {
        try { packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty().map { it.name }.toSet() } catch (_: Throwable) { emptySet() }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> Thread({
                    // The edit session ends at screen-off (§8.2), whatever is in front: the phone may be handed on now.
                    if (LockStore.readState(applicationContext).edit > 0) try { LockEngine.endEdit(applicationContext) } catch (t: Throwable) { RLog.w("lock_edit_end", t) }
                    LockEngine.refresh(applicationContext, alive = true)
                }, "robot-lock").start()
                Intent.ACTION_USER_PRESENT -> Thread({
                    // Phase B: also where a missed alarm's lock starts (the first unlock within 3 hours).
                    val s = LockEngine.refresh(applicationContext, unlocked = true)
                    if (s.active) main.post { unlocked() }
                }, "robot-lock").start()
                else -> Thread({ LockEngine.refresh(applicationContext) }, "robot-lock").start()
            }
        }
    }

    private val shutdown = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Written at once: the phone may be gone a moment later.
            LockEngine.shutdown(applicationContext)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The unlock comes from SystemUI, so these go through ProtectedBroadcasts (1.0, ipc-3); the shutdown is the system's own.
        ProtectedBroadcasts.register(this, screen, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF), main)
        registerReceiver(shutdown, IntentFilter(Intent.ACTION_SHUTDOWN), null, main, Context.RECEIVER_NOT_EXPORTED)
        Thread({
            LockEngine.access(applicationContext, on = true)
            LockEngine.refresh(applicationContext)
        }, "robot-lock").start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Turned off in Android's settings (a force stop gives no call: the record can't see that one).
        LockEngine.access(applicationContext, on = false)
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    private fun cleanUp() {
        if (instance === this) instance = null
        LockClock.stop(applicationContext)
        try { unregisterReceiver(screen) } catch (_: Throwable) {}
        try { unregisterReceiver(shutdown) } catch (_: Throwable) {}
        removeCurtain()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        decide(event.packageName?.toString(), event.className?.toString(), fromUnlock = false)
    }

    /** Main thread: what [pkg]'s window gets during a lock. */
    private fun decide(pkg: String?, cls: String?, fromUnlock: Boolean) {
        val s = try { LockEngine.current(applicationContext) } catch (t: Throwable) { RLog.w("lock_current", t); return }
        val facts = LockEngine.facts(applicationContext, s)
        if (!fromUnlock && inFront(pkg, cls, facts)) { frontPkg = pkg; frontCls = cls }
        if (!s.active) return removeCurtain()
        // Phase B (§9.3): car mode your car started, during your own lock: no cover, whatever comes up.
        if (facts.carOwnLock) return removeCurtain()
        // Unlocked during a ringing or active call: the call screen stays; the next window decides (§5.2, §6).
        if (fromUnlock && facts.inCall) return
        if (fromUnlock && pkg == null) return showCover()
        when (LockGate.decide(pkg, cls, facts)) {
            GateDecision.COVER -> {
                // The lock screen is never covered: no curtain over it, no cover behind it. The unlock decides.
                if (!fromUnlock && keyguard()) return removeCurtain()
                LockEngine.foreground(applicationContext, packageName)
                showCover()
            }
            GateDecision.ALLOW -> {
                if (pkg != packageName) { lastAllowed = pkg; lastAllowedClass = cls }
                LockEngine.foreground(applicationContext, pkg)
                removeCurtain()
            }
            GateDecision.IGNORE -> removeCurtain()
        }
    }

    /** [pkg]'s window replaces the one in front: not the keyboard, a system window, or one of Robot's dialogs or its panel. */
    private fun inFront(pkg: String?, cls: String?, facts: GateFacts): Boolean =
        pkg != null && pkg != facts.keyboard && pkg !in LockGate.SYSTEM && (pkg != packageName || cls in ownActivities)

    /**
     * Main thread, at the unlock during a lock: the window in front decides when the gate covers it (Settings › Phone lock
     * once its edit session ended at screen-off, an app that came up over the lock screen). Otherwise the cover shows
     * unless the last allowed app is still allowed (§5.2).
     */
    private fun unlocked() {
        val pkg = frontPkg
        val cls = frontCls
        val covered = pkg != null && try {
            LockGate.decide(pkg, cls, LockEngine.facts(applicationContext, LockEngine.current(applicationContext))) == GateDecision.COVER
        } catch (t: Throwable) {
            RLog.w("lock_current", t)
            false
        }
        if (covered) decide(pkg, cls, fromUnlock = true) else decide(lastAllowed, lastAllowedClass, fromUnlock = true)
    }

    /**
     * The engine changed. Window events only while a lock is on or due soon; when a lock starts with the screen on and
     * unlocked, the cover shows at once, unless a call is on or Clock is ringing (the next window decides then). When the
     * edit session or car mode your car started ends, the window in front is looked at again.
     */
    fun onEngine(s: LockEngine.Snapshot, startedNow: Boolean) {
        main.post {
            // Phase B: Clock's alarm is heard only while a lock may start on it.
            LockClock.sync(applicationContext, s.file, main)
            val want = LockSchedule.wantsEvents(s.file, s.state, s.clocks)
            try {
                serviceInfo = serviceInfo?.apply { eventTypes = if (want) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0 }
            } catch (t: Throwable) {
                RLog.w("lock_events", t)
            }
            // Without window events, the window in front isn't known any more.
            if (!want) { frontPkg = null; frontCls = null }
            val facts = LockEngine.facts(this, s)
            val ended = (wasEdit && !facts.editSession) || (wasCar && !facts.carOwnLock)
            wasEdit = facts.editSession
            wasCar = facts.carOwnLock
            if (!s.active) return@post removeCurtain()
            if (startedNow && interactive() && !keyguard() && !facts.inCall && !facts.carOwnLock) showCover()
            else if (ended) frontPkg?.let { decide(it, frontCls, fromUnlock = false) }
        }
    }

    private fun interactive() = try { getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true }
    private fun keyguard() = try { getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { false }

    private fun showCover() {
        addCurtain()
        try {
            startActivity(LockSignals.cover(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (t: Throwable) {
            RLog.w("lock_cover", t)
        }
    }

    /** The cover is showing: the curtain comes off. */
    fun coverShown() = main.post { removeCurtain() }

    /** A full-screen, opaque, touch-consuming overlay in the cover's colour, no text, no motion, for 1 s at most (§5.1). */
    private fun addCurtain() {
        main.removeCallbacks(curtainOff)
        main.postDelayed(curtainOff, CURTAIN_MS)
        if (curtain != null) return
        try {
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val v = View(this).apply {
                setBackgroundColor(getColor(if (dark) android.R.color.system_surface_dark else android.R.color.system_surface_light))
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)
            getSystemService(WindowManager::class.java).addView(v, lp)
            curtain = v
        } catch (t: Throwable) {
            RLog.w("lock_curtain", t)
        }
    }

    private fun removeCurtain() {
        main.removeCallbacks(curtainOff)
        val v = curtain ?: return
        curtain = null
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    companion object {
        @Volatile var instance: LockAccessibilityService? = null
            private set
        private const val CURTAIN_MS = 1_000L
    }
}
