// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings › Display (core LookSettings) in files/robot/look.json, not in settings.json: [MAIN] writes it; both app
 * processes read it, the voice process again each time the panel opens (RobotSession.onShow), so the next panel follows
 * a change without a message between the processes. [state] is what this process shows now; Compose reads it.
 */
object LookStore {
    val state: MutableState<LookSettings> = mutableStateOf(LookSettings())

    @Volatile private var loaded = false

    /** One writer thread, so two quick taps land on disk in order (as SettingsStore). */
    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-look").apply { isDaemon = true } } }

    fun file(ctx: Context): File = File(Paths.dir(ctx), LookSettings.FILE)

    /** The look, read from the file the first time this process asks. A snapshot read: a change recomposes. */
    fun current(ctx: Context): LookSettings {
        if (!loaded) reload(ctx)
        return state.value
    }

    /** Reads look.json again (a missing or damaged file gives the defaults). */
    fun reload(ctx: Context): LookSettings = read(file(ctx)).also { state.value = it; loaded = true }

    /** [MAIN] Shows [look] at once in this process and writes it on the writer thread. */
    fun save(ctx: Context, look: LookSettings) {
        state.value = look
        loaded = true
        val f = file(ctx)
        writer.execute { write(f, look) }
    }

    /** Waits for the saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }

    internal fun read(f: File): LookSettings = LookSettings.fromJson(readOrNull(f))

    internal fun write(f: File, look: LookSettings) {
        try { writeAtomic(f, look.toJson()) } catch (t: Throwable) { RLog.w("look_save", t) }
    }
}
