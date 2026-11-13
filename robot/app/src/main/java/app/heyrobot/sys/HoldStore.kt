// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.messages.HoldSettings
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// MAIN PROCESS. hold.json, notifications held until a time (core messages/Hold.kt): its own file, so settings.json stays
// at schema 13. Settings › Messages writes it and the listener (people/HoldTap) reads it, both in this process.

object HoldStore {
    @Volatile private var cached: HoldSettings? = null

    fun file(ctx: Context): File = File(Paths.dir(ctx), HoldSettings.FILE)

    /** The settings (a missing or damaged file: off, no app). */
    fun current(ctx: Context): HoldSettings = cached ?: HoldSettings.parse(readOrNull(file(ctx))).also { cached = it }

    fun update(ctx: Context, change: (HoldSettings) -> HoldSettings): HoldSettings = synchronized(this) {
        val n = change(current(ctx))
        writeAtomic(file(ctx), HoldSettings.write(n))
        cached = n
        n
    }
}
