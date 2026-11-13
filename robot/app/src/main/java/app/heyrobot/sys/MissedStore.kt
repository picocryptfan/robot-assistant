// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.messages.MissedSettings
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// MAIN PROCESS. missed.json, "What did I miss?" (core messages/Missed.kt): its own file, so settings.json stays at
// schema 13. Settings › Messages writes it and the listener reads it, both in this process; a change reaches the
// listener's RAM at once (MissedTap), so what an app no longer picked had is gone before the screen redraws.

object MissedStore {
    @Volatile private var cached: MissedSettings? = null
    private val listeners = CopyOnWriteArrayList<(MissedSettings) -> Unit>()

    fun file(ctx: Context): File = File(Paths.dir(ctx), MissedSettings.FILE)

    /** The settings (a missing or damaged file: off, no app). */
    fun current(ctx: Context): MissedSettings = cached ?: MissedSettings.parse(readOrNull(file(ctx))).also { cached = it }

    fun update(ctx: Context, change: (MissedSettings) -> MissedSettings): MissedSettings {
        val next = synchronized(this) {
            val n = change(current(ctx))
            writeAtomic(file(ctx), MissedSettings.write(n))
            cached = n
            n
        }
        for (l in listeners) l(next)
        return next
    }

    fun listen(l: (MissedSettings) -> Unit) {
        listeners += l
    }
}
