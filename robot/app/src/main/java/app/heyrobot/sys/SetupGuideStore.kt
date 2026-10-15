// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.RLog
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1.0: the setup guide's own state (core GuideState) in files/robot/setup_guide.json, not in settings.json: where Finish
 * later left it, the version you saw and the one you finished. Only the main app reads and writes it. [state] is what
 * this process shows now; Compose reads it (Home's "Continue the setup guide").
 */
object SetupGuideStore {
    val state: MutableState<GuideState> = mutableStateOf(GuideState())

    @Volatile private var loaded = false

    /** One writer thread, so quick taps land on disk in order (as LookStore). */
    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-guide").apply { isDaemon = true } } }

    fun file(ctx: Context): File = File(Paths.dir(ctx), SetupGuide.FILE)

    /** The guide's state, read from the file the first time this process asks. A snapshot read: a change recomposes. */
    fun current(ctx: Context): GuideState {
        if (!loaded) reload(ctx)
        return state.value
    }

    fun reload(ctx: Context): GuideState = GuideState.fromJson(readOrNull(file(ctx))).also { state.value = it; loaded = true }

    /** Shows [s] at once in this process and writes it on the writer thread. */
    fun save(ctx: Context, s: GuideState) {
        state.value = s
        loaded = true
        val f = file(ctx)
        writer.execute { try { writeAtomic(f, s.toJson()) } catch (t: Throwable) { RLog.w("guide_save", t) } }
    }

    /** Waits for the saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }
}
