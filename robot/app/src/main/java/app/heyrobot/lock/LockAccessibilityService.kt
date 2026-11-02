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
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.GateDecision
import app.heyrobot.core.lock.GateFacts
import app.heyrobot.core.lock.LockGate
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.LockState
import app.heyrobot.sys.ProtectedBroadcasts

// :LOCK PROCESS ONLY. Phone lock's accessibility service: it sees which app comes to the front and
// nothing else. Its XML asks for window-state events only, no window content (canRetrieveWindowContent false), no
// gestures, and it isn't an accessibility tool. From an event it reads the type, the package, the class and when, never
// the text, the source node or a description (ProcessIsolationTest scans this file for that). Robot's own app, and since
// LS13 every window Phone lock covers, also says when it is in front (LockSignals.ACTION_FRONT, LockFrontWatch), as Android
// doesn't always send an event for it (none at all for a data-sensitive window to a service that isn't an accessibility
// tool, which this one isn't, on purpose). While no lock is on or due
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
     * The window in front as the last event (or Robot's app) showed it, known only while window events are on. The gate
     * looks at it again when the edit session or car mode your car started ends, and at the unlock. The keyboard, the
     * system's windows (the lock screen, the shade) and Robot's own dialogs and panel sit above it, so they don't
     * replace it.
     */
    private var frontPkg: String? = null
    private var frontCls: String? = null
    /**
     * When that window came to the front (SystemClock.uptimeMillis, as its event or Robot's app says), never later than
     * when it was kept: a time in the future would make every later word lose.
     */
    private var frontAt = 0L
    /** Window events are on: a lock is on or due within 10 minutes (main thread). */
    private var watching = false
    /** What the last engine change said of the edit session and of car mode your car started (main thread). */
    private var wasEdit = false
    private var wasCar = false
    /** F13: the cover is up, and since when (SystemClock.uptimeMillis; main thread). */
    private var coverUp = false
    private var coverUpAt = 0L
    private val coverGone = Runnable { afterCoverStopped() }
    /** review-lock 7: the window the gate last covered, and when (SystemClock.uptimeMillis; main thread). */
    private var coveredPkg: String? = null
    private var coveredCls: String? = null
    private var coveredAt = 0L
    private val modeChanged = AudioManager.OnModeChangedListener { callMaybeStarted() }
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
        try { getSystemService(AudioManager::class.java).addOnModeChangedListener(mainExecutor, modeChanged) } catch (t: Throwable) { RLog.w("lock_mode", t) }
        Thread({
            LockEngine.access(applicationContext, on = true)
            // F67: :lock restarted during a lock: the cover comes back now, not at the next window event.
            // The emergency dialer may be up: what said so was in memory, so usage access says what's in front. Unknown: the cover (fails closed).
            val s = LockEngine.refresh(applicationContext)
            val front = try { if (s.active) LockEngine.usageFront(applicationContext)?.first else null } catch (t: Throwable) { RLog.w("lock_usage_front", t); null }
            val emergency = s.active && (LockGate.emergencyInFront(front, LockEngine.facts(applicationContext, s)) ||
                LockState.emergencyHeld(s.state.emergencyUntil, System.currentTimeMillis()))
            if (s.active) onEngine(s, startedNow = !emergency)
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
        try { getSystemService(AudioManager::class.java).removeOnModeChangedListener(modeChanged) } catch (_: Throwable) {}
        removeCurtain()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        decide(event.packageName?.toString(), event.className?.toString(), fromUnlock = false, at = event.eventTime)
    }

    /**
     * Robot's app says its window ([LockSignals.MAIN]) is in front since [at]: it came to the front during a lock, or the
     * edit session ended with it there (you left Phone lock's pages, or the 5 minutes ran out). Android may send no window
     * event for it (back from the code pad, on a Pixel 8), and the gate decides on it as on one, unless a window event
     * since [at] says another window came up (at the same time: the event, which is the real one). LS13: or another covered
     * [window] came up (LockFrontWatch), whose event Android may never send (a data-sensitive window, LT8 step 4); any
     * class the gate doesn't cover counts as Robot's app (core LockGate.saidInFront), so this word only ever covers.
     */
    fun robotInFront(at: Long, window: String? = null) = main.post {
        // Only while window events are on, as for an event.
        val t = minOf(at, SystemClock.uptimeMillis())
        if (watching && t > frontAt) decide(packageName, LockGate.saidInFront(window), fromUnlock = false, at = t)
    }

    /**
     * Main thread: what [pkg]'s window gets during a lock. [at]: when it came to the front (an event, or Robot's app saying
     * so), and it is kept as the window in front; null when the gate looks again at the one kept, or at the unlock.
     */
    private fun decide(pkg: String?, cls: String?, fromUnlock: Boolean, at: Long? = null) {
        // review-lock 11: unreadable: the last state decides; with none and window events on (a lock is on or due), the cover.
        val s = try { LockEngine.current(applicationContext) } catch (t: Throwable) {
            RLog.w("lock_current", t)
            LockEngine.snapshot ?: return if (watching && !keyguard()) showCover() else Unit
        }
        val facts = LockEngine.facts(applicationContext, s)
        if (at != null && !fromUnlock && inFront(pkg, cls, facts)) { frontPkg = pkg; frontCls = cls; frontAt = minOf(at, SystemClock.uptimeMillis()) }
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
                coveredPkg = pkg; coveredCls = cls; coveredAt = SystemClock.uptimeMillis()
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

    /**
     * review-lock 7: the audio mode changed. A call's window can come before the mode says so, and nothing else would decide
     * again: a dialer window covered in the last moments gets the gate's new answer, and the cover leaves the call's screen.
     */
    private fun callMaybeStarted() {
        if (SystemClock.uptimeMillis() - coveredAt > CALL_WINDOW_MS) return
        val s = try { LockEngine.current(applicationContext) } catch (t: Throwable) { RLog.w("lock_current", t); return }
        if (!s.active || !LockGate.callUncovers(coveredPkg, coveredCls, LockEngine.facts(applicationContext, s))) return
        coveredPkg = null
        removeCurtain()
        LockCoverActivity.showing?.finish()
    }

    /** [pkg]'s window replaces the one in front: not the keyboard, a system window, or one of Robot's dialogs or its panel. */
    private fun inFront(pkg: String?, cls: String?, facts: GateFacts): Boolean =
        pkg != null && (pkg != facts.keyboard || cls in facts.keyboardActivities) && pkg !in LockGate.SYSTEM && (pkg != packageName || cls in ownActivities)

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
            watching = want
            if (!want) { frontPkg = null; frontCls = null; frontAt = 0 }
            val facts = LockEngine.facts(this, s)
            val ended = (wasEdit && !facts.editSession) || (wasCar && !facts.carOwnLock)
            wasEdit = facts.editSession
            wasCar = facts.carOwnLock
            // F96: the last allowed app belongs to the lock that just ended, not to the next one.
            if (!s.active) { lastAllowed = null; lastAllowedClass = null; return@post removeCurtain() }
            val held = facts.inCall || LockGate.ringingInFront(alarmSounding(), frontPkg, facts)
            if (startedNow && interactive() && !keyguard() && !held && !facts.carOwnLock) showCover()
            else if (ended) frontPkg?.let { decide(it, frontCls, fromUnlock = false) }
        }
    }

    private fun interactive() = try { getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true }
    private fun keyguard() = try { getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { false }

    /** F95: an alarm is sounding now, any clock's (usage alarm; Android tells this without a permission, and never whose). */
    private fun alarmSounding() = try {
        getSystemService(AudioManager::class.java).activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    } catch (_: Throwable) { false }

    private fun showCover() {
        addCurtain()
        try {
            // F94: NO_USER_ACTION: the app under the cover isn't told you left it, so a video doesn't float over in picture-in-picture.
            startActivity(LockSignals.cover(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        } catch (t: Throwable) {
            RLog.w("lock_cover", t)
        }
    }

    /** The cover is showing: the curtain comes off. */
    fun coverShown() = main.post {
        coverUp = true
        coverUpAt = SystemClock.uptimeMillis()
        main.removeCallbacks(coverGone)
        removeCurtain()
    }

    /**
     * F13: the cover stopped. [own]: it opened that window itself (the code pad, an allowed app, the emergency dialer), so
     * the next window decides as usual. Otherwise another window took the front; Android sends no event for a data-sensitive
     * one, so with none within [COVER_GONE_MS] the gate decides on what usage access says is in front.
     */
    fun coverStopped(own: Boolean) = main.post {
        coverUp = false
        main.removeCallbacks(coverGone)
        if (!own) main.postDelayed(coverGone, COVER_GONE_MS)
    }

    private fun afterCoverStopped() {
        if (coverUp || !watching || !interactive() || keyguard()) return
        // Android sends no event for some windows, and a call's screen must not be taken for Robot's app; the next window decides.
        try { if (LockEngine.facts(applicationContext, LockEngine.current(applicationContext)).inCall) return } catch (t: Throwable) { RLog.w("lock_current", t) }
        // An event since the cover came up named the window, and the gate has decided on it.
        if (frontAt > coverUpAt && frontCls != LockSignals.COVER) return
        Thread({
            val top = try { LockEngine.usageFront(applicationContext) } catch (t: Throwable) { RLog.w("lock_usage_front", t); null }
            // Nothing known of the window (no usage access): it counts as Robot's app, covered. Fails closed.
            main.post { if (!coverUp) decide(top?.first ?: packageName, top?.let { it.second } ?: LockSignals.MAIN, fromUnlock = false) }
        }, "robot-lock").start()
    }

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
        private const val COVER_GONE_MS = 1_500L
        private const val CALL_WINDOW_MS = 10_000L
    }
}
