// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.EngineState
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// The :voice process also hosts the wake word, so a model that crashes the process while it loads (a damaged
// file, sherpa-onnx's exit(-1) on a bad config, a native fault) must not take "hey robot" down with it in a
// loop. Before a native engine is created its guard notes "loading" on disk (fsynced); afterwards it clears it.
// A later process that finds the note knows the earlier one died while loading. Two such deaths in a row mark
// the engine broken: Robot then uses the placeholder until the person taps "Try again" (or installs new files).

/** The guard of one model bundle, persisted across :voice processes. */
data class GuardRecord(val deaths: Int = 0, val loading: Boolean = false) {
    fun encode(): String = "deaths=$deaths loading=$loading\n"

    companion object {
        fun decode(text: String?): GuardRecord {
            val kv = text.orEmpty().trim().split(' ').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return GuardRecord(kv["deaths"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0, kv["loading"] == "true")
        }
    }
}

/** Pure transitions of a [GuardRecord] (unit-tested). */
object GuardLogic {
    const val MAX_DEATHS = 2

    /**
     * Once per process, before anything is loaded: a "loading" note left by an earlier process means it died while
     * loading. [died] is false when Android says that process was killed for another reason (memory, the person).
     */
    fun atProcessStart(r: GuardRecord, died: Boolean): GuardRecord =
        if (r.loading) GuardRecord(if (died) r.deaths + 1 else r.deaths, false) else r

    fun beforeLoad(r: GuardRecord): GuardRecord = r.copy(loading = true)

    /** A load that returned: success clears the count; an exception (caught, the process lives) leaves it. */
    fun afterLoad(r: GuardRecord, ok: Boolean): GuardRecord = if (ok) GuardRecord() else r.copy(loading = false)

    fun broken(r: GuardRecord): Boolean = r.deaths >= MAX_DEATHS

    /**
     * Does an ApplicationExitInfo reason (null = unknown) mean the process died of what it was doing? Crashes,
     * native faults, aborts and exit() count; kills for memory, by the person or for a package change do not.
     */
    fun countsAsDeath(reason: Int?): Boolean = when (reason) {
        // LOW_MEMORY, PERMISSION_CHANGE, USER_REQUESTED, USER_STOPPED, DEPENDENCY_DIED, OTHER, FREEZER,
        // PACKAGE_STATE_CHANGE, PACKAGE_UPDATED.
        3, 8, 10, 11, 12, 13, 14, 15, 16 -> false
        // null/UNKNOWN, EXIT_SELF (sherpa's exit(-1)), SIGNALED, CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE,
        // EXCESSIVE_RESOURCE_USAGE.
        else -> true
    }
}

/**
 * One native model load at a time in the :voice process (speech to text, the voices, the wake word). Each engine's
 * guard notes "loading" on disk; with two loads at once (a question starting loads speech to text while the voice
 * prewarms), a crash in one would count against both, and could mark the healthy one broken. A load that waits here
 * waits at most for the other's load (a few seconds), and no reply needs the voice before the question is decoded.
 */
internal object NativeLoads {
    private val lock = java.util.concurrent.locks.ReentrantLock(true)

    /** What is loading now, or null (a code like "stt-parakeet-v3", never a path). */
    @Volatile var current: String? = null; private set

    fun <T> one(what: String, block: () -> T): T {
        lock.lock()
        try {
            current = what
            return block()
        } finally {
            current = null
            lock.unlock()
        }
    }
}

/** Which state an engine is in (pure): the fallback decision the switching engines act on. */
object EngineFallback {
    fun state(verified: Boolean, broken: Boolean, failed: Boolean, loading: Boolean, loaded: Boolean): EngineState = when {
        !verified -> EngineState.MISSING
        broken -> EngineState.BROKEN
        loaded -> EngineState.READY
        failed -> EngineState.FAILED
        loading -> EngineState.LOADING
        else -> EngineState.IDLE
    }
}

/** A [GuardRecord] kept in [file]. [lastExitReason] is asked only when an earlier process left a "loading" note. */
class EngineGuard(private val name: String, private val file: File, lastExitReason: () -> Int?) {
    private var record: GuardRecord

    init {
        val old = GuardRecord.decode(readOrNull(file))
        record = if (old.loading) {
            val reason = try { lastExitReason() } catch (_: Throwable) { null }
            GuardLogic.atProcessStart(old, GuardLogic.countsAsDeath(reason)).also {
                // The failure class only: how the earlier process ended, never what it was doing.
                RLog.i("voice_guard", "engine" to RLog.Code(name), "died" to (it.deaths > old.deaths), "exitReason" to reason, "deaths" to it.deaths)
                save(it)
            }
        } else old
        if (GuardLogic.broken(record)) RLog.i("voice_engine_broken", "engine" to RLog.Code(name), "deaths" to record.deaths)
    }

    val broken: Boolean @Synchronized get() = GuardLogic.broken(record)

    @Synchronized fun beforeLoad() = set(GuardLogic.beforeLoad(record))

    @Synchronized fun afterLoad(ok: Boolean) = set(GuardLogic.afterLoad(record, ok))

    @Synchronized fun reset() = set(GuardRecord())

    private fun set(r: GuardRecord) {
        record = r
        save(r)
    }

    private fun save(r: GuardRecord) {
        try { writeAtomic(file, r.encode()) } catch (t: Throwable) { RLog.w("voice_guard_save", t) }
    }
}
