// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.content.Context
import android.content.Intent

// VOICE PROCESS. Puts an app beside the car screen. Android keeps the app that asks for a split in its place (on top in
// portrait, on the left on its side) and puts the app it opens in the other half; there is no public way to choose. So car
// mode stays on top and the app goes below, as in 1.2.0. 1.2.1 tried the other order (the app first, then the car screen
// asking to join it): the car screen is singleTask and already up, so Android only brought its task back to the front and
// the app never showed (phone-1.2.1, fix-car-1.2.1). CarScreenStartsTest keeps anything here from starting the car screen.

object CarSplitOpen {
    /** Opens [app] beside [ctx] (the car screen, when it's up); false when Android found nothing to open. */
    fun start(ctx: Context, app: Intent): Boolean = try {
        ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        true
    } catch (_: android.content.ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
