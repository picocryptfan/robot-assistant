// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.core.car.CarNotice
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.HandsFreeFiles

/**
 * 1.2.4: car mode by hand, with no car connected: the home screen's Car mode button and the "Car mode" launcher shortcut
 * (res/xml/shortcuts.xml, which opens MainActivity with [ACTION]). Car mode runs in :voice: this asks its speaking service
 * there to start it the way "car mode" does (CarModeController.startByHand), or to show the car screen when it's on
 * already. Car mode off in Settings: its page instead, where you turn it on (the voice command says the same).
 */
object CarButton {
    const val ACTION = "app.heyrobot.action.CAR_MODE" // l10n: not text
    private const val SPEAKING_SERVICE = "app.heyrobot.assistant.routines.RoutineSpeakingService" // l10n: not text

    fun asked(i: Intent?): Boolean = i != null && asked(i.action, i.flags)

    /**
     * review-1.2.4 finding 4: a relaunch from Recents after car mode's screen finished replays the shortcut's intent
     * (LAUNCHED_FROM_HISTORY), which started the mic and Do Not Disturb again: only a fresh launch asks.
     */
    internal fun asked(action: String?, flags: Int): Boolean =
        action == ACTION && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    /** [asked], and the action cleared once handled, so the activity's intent can't ask again. */
    fun take(i: Intent?): Boolean = asked(i).also { if (i?.action == ACTION) i.action = null }

    /** Car mode asked for: null. Otherwise the page to open instead (Settings › Car mode). */
    fun open(ctx: Context): String? {
        val on = try { HandsFreeFiles.store(ctx).car().on } catch (t: Throwable) { RLog.w("car_button", t); false }
        if (!on) return AppPages.CAR_SETTINGS
        try {
            ctx.startForegroundService(Intent().setClassName(ctx, SPEAKING_SERVICE).setAction(CarNotice.ACTION_OPEN))
        } catch (t: Throwable) {
            RLog.w("car_button", t)
        }
        return null
    }
}
