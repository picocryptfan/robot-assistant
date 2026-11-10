// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * fix-covered-partial L2: Robot's own short messages. A toast is a window of its own that isn't trusted, so while one shows
 * a tap on car mode's Yes or the summary's Send is marked partly covered and refused (CoveredTouches). While one of those
 * screens is up it shows the message in its own place instead; another app's toast still refuses the tap.
 */
object RobotToast {
    private class Up(val owner: Any, val show: (String) -> Unit)

    @Volatile private var up: Up? = null

    /** [owner] is up and shows Robot's messages itself, on the main thread. */
    fun up(owner: Any, show: (String) -> Unit) {
        up = Up(owner, show)
    }

    /** [owner] is going; a later screen's hold stays. */
    fun down(owner: Any) {
        if (up?.owner === owner) up = null
    }

    /** Where a message goes now: a screen's own place, or null for a toast. */
    internal fun screen(): ((String) -> Unit)? = up?.show

    fun show(ctx: Context, text: String, long: Boolean = false) {
        val s = screen()
        if (s != null) Handler(Looper.getMainLooper()).post { s(text) }
        else Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
