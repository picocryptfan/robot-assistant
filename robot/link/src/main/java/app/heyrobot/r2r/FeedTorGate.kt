// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * fix-today-tor: news through Tor waits for Tor's start ([up], TorNet.up: bootstrap done) at most [waitMs]. Past it the
 * answer is STARTING, and Tor goes on starting on its own thread, so a later ask finds it up. One start at a time: an ask
 * while one is under way waits for that one.
 */
class FeedTorGate(private val up: () -> Boolean, private val waitMs: Long = WAIT_MS) {
    enum class State { UP, STARTING, DOWN }

    private val lock = Any()
    private var pending: Future<Boolean>? = null
    private val pool: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-feed-tor").apply { isDaemon = true } } // l10n: not text (a thread's name)

    fun state(): State {
        val f = synchronized(lock) { pending?.takeIf { !it.isDone } ?: pool.submit(Callable { up() }).also { pending = it } }
        return try {
            if (f.get(waitMs, TimeUnit.MILLISECONDS)) State.UP else State.DOWN
        } catch (_: TimeoutException) {
            State.STARTING
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            State.DOWN
        } catch (_: Exception) {
            State.DOWN
        }
    }

    companion object {
        /** The longest a feed waits for Tor to start: 30 seconds (Tor's own bound is 120 s per route). */
        const val WAIT_MS = 30_000L
    }
}
