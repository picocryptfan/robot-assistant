// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.SizeF
import app.heyrobot.core.car.CarWidgetSize
import app.heyrobot.core.car.CarWidget
import app.heyrobot.sys.CarWidgets
import app.heyrobot.sys.MusicApps

// The car screen's half of the bottom screen's widget; the ids and the questions are sys/CarWidgets.kt's.

/**
 * VOICE PROCESS. The car screen's host: [view] makes the widget's view once and keeps it while the screen lives, so a turn
 * hides it without a reload. [open]: the widget's app on the top screen, through the locks (CarModeActivity.openApp), with
 * the tap's IntentSender when it had one ([CarWidgetScreen] decides whether that screen or the launcher entry opens).
 */
class CarWidgetHost(private val activity: Activity, private val open: (IntentSender?) -> Unit) {
    private val host = AppWidgetHost(activity.applicationContext, CarWidgets.HOST_ID)
    private var made: CarWidget? = null
    private var view: AppWidgetHostView? = null

    fun start() { try { host.startListening() } catch (_: Exception) {} }
    fun stop() { try { host.stopListening() } catch (_: Exception) {} }

    /** Null: the id isn't bound to what you picked, or its app isn't signed as when you picked it. */
    fun view(w: CarWidget): AppWidgetHostView? {
        if (w == made) return view
        made = w
        val info = CarWidgets.bound(activity, w)
        view = if (info != null && MusicApps.check(activity, w.app) == MusicApps.PinNow.OK)
            try { host.createView(CarWidgetTaps(activity, open), w.id, info) } catch (_: Exception) { null } else null
        // The slot's size, once laid out and each time it changes: most widgets before Android 12 draw for it, not their cells.
        view?.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or0, ob ->
            if (r - l == or0 - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val (wd, hd) = CarWidgetSize.dp(r - l, b - t, v.resources.displayMetrics.density) ?: return@addOnLayoutChangeListener
            try { (v as AppWidgetHostView).updateAppWidgetSize(Bundle(), listOf(SizeF(wd, hd))) } catch (_: Exception) {}
        }
        return view
    }
}

/**
 * Taps inside the widget. A button that plays, pauses or skips (a broadcast or a service) works in place. One that opens a
 * screen never opens over the car screen: it goes to [open], which opens it beside the car screen when [CarWidgetScreen]
 * allows, else the widget's app's launcher entry there. A tap whose kind can't be told counts as one that opens.
 * A broadcast or a service goes out with options of its own ([noStarts]), not with RemoteViews' own options, which
 * since Android 14 lend it Robot's right to start a screen: a play button that is a broadcast opening a screen (a
 * trampoline) can't put that screen over the car screen, but it can still start a service, a foreground one too, so
 * play on a stopped player works.
 */
internal class CarWidgetTaps(base: Context, private val open: (IntentSender?) -> Unit, private val screen: (IntentSender) -> Boolean = ::opensScreen) : ContextWrapper(base) {
    override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) =
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)

    override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {
        if (screen(intent)) open(CarWidgetScreen.own(intent, fillInIntent)) else super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, noStarts())
    }

    override fun startActivity(intent: Intent) = open(null)
    override fun startActivity(intent: Intent, options: Bundle?) = open(null)
    override fun startActivities(intents: Array<out Intent>) = open(null)
    override fun startActivities(intents: Array<out Intent>, options: Bundle?) = open(null)

    companion object {
        /**
         * Options for a widget's broadcast or service: no background-start mode set. Android then lends the receiver
         * Robot's right to start a service, a foreground one too (a play button on a stopped player), never a screen
         * (PendingIntentRecord.getDefaultBackgroundStartPrivileges: ALLOW_FGS for an app targeting 34 or above). DENIED
         * would take the foreground service as well, and ALLOWED (RemoteViews' own) the screen.
         */
        fun noStarts(): Bundle = ActivityOptions.makeBasic().toBundle()

        /** An IntentSender and a PendingIntent share their parcel form (one binder), so its kind can be read back. */
        fun opensScreen(s: IntentSender): Boolean {
            val p = Parcel.obtain()
            return try {
                s.writeToParcel(p, 0)
                p.setDataPosition(0)
                PendingIntent.readPendingIntentOrNullFromParcel(p)?.isActivity ?: true
            } catch (_: Exception) { true } finally { p.recycle() }
        }
    }
}

/**
 * A widget tap that opens a screen, sent beside the car screen with NEW_TASK|LAUNCH_ADJACENT ([beside]): only an activity
 * PendingIntent (Android 12 and up) that the pinned app made itself, and only a mutable one, since Android applies a
 * sender's flags to a mutable PendingIntent alone (PendingIntentRecord.sendInner): an immutable one would open over the car
 * screen. Robot adds no fill-in. A broadcast or a service, another app's, an immutable one, or one whose send fails keeps
 * the launcher entry (false). The pinned app's own screen gives it no more than its launcher entry does: either can start
 * what it likes, and the locks in CarModeActivity.openApp run first either way.
 */
internal object CarWidgetScreen {
    data class Kind(val activity: Boolean, val immutable: Boolean, val creator: String?)

    const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

    /**
     * What a screen tap opens: [s] itself, or null (the launcher entry) when the tap carries a fill-in, a list row's item:
     * Robot sends no fill-in, and the bare template would open without the row's item (review-car-widget-taps L2).
     */
    fun <S : Any> own(s: S, fillIn: Any?): S? = if (fillIn == null) s else null

    /**
     * The creator check doesn't limit the target (review-car-widget-taps L4): the pinned app's own PendingIntent may
     * name another app's screen, which then opens beside without CarOpen.app's Phone lock check for that app. No
     * public API reads a PendingIntent's target, and the pinned app can open the same screen once it's on screen;
     * Phone lock's own cover over that app is the backstop.
     */
    fun beside(kind: Kind?, pinned: String, sdk: Int): Boolean =
        sdk >= Build.VERSION_CODES.S && kind != null && kind.activity && !kind.immutable && kind.creator == pinned

    /** True when [start] sent it; false: not one to send beside, or the send failed, so the launcher entry opens instead. */
    fun sendIf(kind: Kind?, pinned: String, sdk: Int, start: () -> Unit): Boolean =
        beside(kind, pinned, sdk) && try { start(); true } catch (_: Exception) { false }

    /**
     * review-car-widget-taps L3: a start Android refuses with a result code, not an exception (a blocked background
     * start, screen pinning), still counts as sent. No public API on Android 14 to 16 returns that code for an
     * activity start (startIntentSender and PendingIntent.send return nothing; OnFinished gets 0), so there is no
     * fallback to make. CarSplitOpen.start is the same.
     */
    fun send(ctx: Context, s: IntentSender, pinned: String): Boolean =
        sendIf(kindOf(s), pinned, Build.VERSION.SDK_INT) { ctx.startIntentSender(s, null, FLAGS, FLAGS, 0, options()) }

    /**
     * The screen's own start: the car screen is in front and lends its right to start a screen to this one send (since
     * Android 14 a sender's right goes with a PendingIntent only when its options say so), never to a broadcast.
     */
    @Suppress("DEPRECATION")
    private fun options(): Bundle = ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()

    /** Null when it can't be read: the launcher entry opens. */
    fun kindOf(s: IntentSender): Kind? {
        val p = Parcel.obtain()
        return try {
            s.writeToParcel(p, 0)
            p.setDataPosition(0)
            PendingIntent.readPendingIntentOrNullFromParcel(p)?.let { Kind(it.isActivity, it.isImmutable, it.creatorPackage) }
        } catch (_: Exception) { null } finally { p.recycle() }
    }
}
