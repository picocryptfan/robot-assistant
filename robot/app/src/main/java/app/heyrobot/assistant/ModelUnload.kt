package app.heyrobot.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay

// VOICE PROCESS. free_memory's unload of Robot's own model (0.4.0), the same release as Settings › Model memory's timer
// (BrainKeeper.release), but now and only once the answer that asked for it has ended: Gemma is still writing that
// answer when the action runs. PanelController [take]s the request when a reply ends and runs [unload]; the card then
// says what Android reports came back. Numbers only.

object ModelUnload {
    /** Android's available memory when the unload was asked for, and when (elapsed ms); null: none waiting. */
    @Volatile private var asked: Pair<Long, Long>? = null

    /** The action ran: unload after this answer. */
    fun request(ctx: Context) {
        asked = available(ctx) to SystemClock.elapsedRealtime()
    }

    /**
     * The available memory when an action asked for the unload, once (null: none asked, or one so old its answer never
     * ended here); the caller then runs [unload].
     */
    fun take(): Long? {
        val a = asked ?: return null
        asked = null
        return a.first.takeIf { SystemClock.elapsedRealtime() - a.second < STALE_MS }
    }

    private const val STALE_MS = 10 * 60_000L

    /** Releases the model now and waits (up to about 4 s) for Android to count the memory as free: before and after. */
    suspend fun unload(ctx: Context, before: Long): Pair<Long, Long> {
        BrainKeeper.release("free_memory").join()
        var after = available(ctx)
        for (i in 0 until 16) {
            if (after - before >= RELEASED) break
            delay(250)
            after = available(ctx)
        }
        return before to after
    }

    /** Most of Gemma's 2 GB is back. */
    private const val RELEASED = 1_500_000_000L

    fun available(ctx: Context): Long = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
}
