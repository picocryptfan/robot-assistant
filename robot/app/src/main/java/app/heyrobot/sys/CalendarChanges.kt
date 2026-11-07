// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.heyrobot.core.RLog

// Main process. today-fix-b (review finding 4): Today and the Calendar tab read the phone's calendar again only when it
// changed (a sync, another app) while they show; leaving Robot and coming back reads it anyway.

object CalendarChanges {
    private val NOTHING: () -> Unit = {}

    /** [changed] on the main thread each time the calendar provider changes, until the returned stop runs; nothing when it can't be watched. */
    fun observe(ctx: Context, changed: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = changed() }
        val cr = ctx.contentResolver
        val stop: () -> Unit = { try { cr.unregisterContentObserver(o) } catch (t: Throwable) { RLog.w("calendar_watch_stop", t) } }
        return try {
            cr.registerContentObserver(CalendarContract.CONTENT_URI, true, o)
            stop
        } catch (t: Throwable) {
            RLog.w("calendar_watch", t)
            NOTHING
        }
    }
}
