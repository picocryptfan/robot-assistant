// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.content.Context
import app.heyrobot.BuildConfig
import app.heyrobot.core.RLog
import app.heyrobot.core.guide.WhatsNew
import app.heyrobot.sys.SetupGuideStore

// MAIN PROCESS (UpdatedReceiver's thread). 1.5: after an update that added features, one notice that opens What's new, for
// people who mostly use Robot by voice and may not open the app for a while. Silent, on the Updates channel Robot already
// has, posted once per version and only when notifications are already allowed (nothing is asked for); it stands in for
// "Robot updated to ..." then.

object WhatsNewNotice {
    /** Posts it when What's new waits for this version and no notice said so yet; whether it was posted. */
    fun post(ctx: Context): Boolean = try {
        val now = SetupGuideStore.reload(ctx)
        val v = BuildConfig.VERSION_NAME
        WhatsNew.notifies(now, v) && UpdateNotices.whatsNew(ctx, v).also { posted ->
            if (posted) { SetupGuideStore.save(ctx, WhatsNew.notified(now, v)); SetupGuideStore.awaitWrites() }
        }
    } catch (t: Throwable) {
        RLog.w("update_notify", t)
        false
    }
}
