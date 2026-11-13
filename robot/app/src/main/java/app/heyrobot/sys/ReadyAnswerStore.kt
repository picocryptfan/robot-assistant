// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.messages.ReadyAnswerSettings
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

/** Ready answers' own file, ready-answers.json (settings.json stays at schema 13). */
object ReadyAnswerStore {
    fun settings(ctx: Context): ReadyAnswerSettings = ReadyAnswerSettings.decode(readOrNull(File(Paths.dir(ctx), ReadyAnswerSettings.FILE)))

    @Synchronized
    fun save(ctx: Context, s: ReadyAnswerSettings): Boolean = try {
        writeAtomic(File(Paths.dir(ctx).apply { mkdirs() }, ReadyAnswerSettings.FILE), s.encode())
        true
    } catch (t: Throwable) {
        RLog.w("ready_write", t)
        false
    }
}
