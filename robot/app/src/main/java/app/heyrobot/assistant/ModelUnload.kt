package app.heyrobot.assistant

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.delay

// VOICE PROCESS. free_memory's unload of Robot's own model (0.4.0), the same release as Settings › Model memory's timer
// (BrainKeeper.release), but now and only once the answer that asked for it has ended: Gemma is still writing that
// answer when the action runs. PanelController calls [afterTurn] when the reply is finished; the card then says what
// Android reports came back. Numbers only.

object ModelUnload {
    /** Android's available memory when the unload was asked for, or null: none waiting. */
    @Volatile private var before: Long? = null

    /** The action ran: unload after this answer. */
    fun request(ctx: Context) {
        before = available(ctx)
    }

    /** The available memory when an action asked for the unload, once (null: none asked); the caller then runs [unload]. */
    fun take(): Long? = before.also { before = null }

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
