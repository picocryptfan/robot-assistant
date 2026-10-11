package app.heyrobot.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// VOICE PROCESS. free_memory's unload of Robot's own model (0.4.0), the same release as Settings › Model's keep-loaded timer
// (BrainKeeper.release), but now and only once the answer that asked for it has ended: Gemma is still writing that
// answer when the action runs. PanelController [take]s the request at the end of the turn and releases the model before
// the turn completes (so the next question can't race it); then [settled] waits, off the main thread, for Android to
// count the memory as free, and the card says what came back. Numbers only.

object ModelUnload {
    /** Android's available memory when the unload was asked for, and when (elapsed ms); null: none waiting. */
    @Volatile private var asked: Pair<Long, Long>? = null
    /** For tests. */
    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** The action ran: unload after this answer. [available]: Android's available memory now. */
    fun request(available: Long) {
        asked = available to clock()
    }

    /**
     * The available memory when an action asked for the unload, once (null: none asked, or one so old its answer never
     * ended here); the caller then releases the model.
     */
    fun take(): Long? {
        val a = asked ?: return null
        asked = null
        return a.first.takeIf { clock() - a.second < STALE_MS }
    }

    private const val STALE_MS = 10 * 60_000L

    /** Waits (up to about 4 s, off the main thread) for most of the model's memory to show as free: the available memory then. */
    suspend fun settled(ctx: Context, before: Long): Long = withContext(Dispatchers.IO) {
        var after = available(ctx)
        for (i in 0 until 16) {
            if (after - before >= RELEASED) break
            delay(250)
            after = available(ctx)
        }
        after
    }

    /** Most of Fast's 2 GB is back (an E4B's 4 GB passes it sooner). */
    private const val RELEASED = 1_500_000_000L

    fun available(ctx: Context): Long = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
}
