package app.heyrobot.ui.today

import android.content.Context
import app.heyrobot.core.HomeSettings
import app.heyrobot.sys.HomeSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * fix-today-review 4: Today's settings are saved off the main thread. HomeSettingsStore.update takes a file lock, writes,
 * then settles the news (Keystore deletes, JobScheduler); here it runs on one IO thread at a time, so saves keep their
 * order, and [done] gets what was saved back on the caller's thread. fix-126-review 5: when it couldn't be saved, [done]
 * gets what the file holds, so the screen never shows a change that didn't happen; and the write runs in TodaySave's own
 * scope, so leaving the page doesn't cancel a save queued behind another. review-126-final 1: it starts on [io] itself,
 * so two saves reach it in the order they were made.
 */
internal object TodaySave {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    fun save(scope: CoroutineScope, ctx: Context, f: (HomeSettings) -> HomeSettings, done: (HomeSettings?) -> Unit = {}) {
        val app = ctx.applicationContext
        save(scope, { HomeSettingsStore.update(app, f) }, { HomeSettingsStore.current(app) }, done)
    }

    private val own = CoroutineScope(SupervisorJob())

    /** [write], or [now] when it fails, on the IO thread whatever happens to [scope]; [done] on [scope]. */
    internal fun save(scope: CoroutineScope, write: () -> HomeSettings?, now: () -> HomeSettings, done: (HomeSettings?) -> Unit) {
        val saved = own.async(io) { write() ?: now() }
        scope.launch { done(saved.await()) }
    }
}
