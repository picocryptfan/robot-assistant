package app.heyrobot.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a one-shot's start deadline counts from (docs/conversation-mode.md 4.1, phase 5.4): with the fast engine there,
 * from when the line was asked, the model's readiness check included, so the fast engine stands in on time even when
 * that check waits behind :brain's work thread; otherwise from when the model is ready, a load not counted.
 */
class OneShotStartTest {
    private val fast = OneShotBudget(TranslatePolicy.START_WITHIN_MS, 8_000, waitForLoad = false)

    @Test fun withTheFastEngineTheCheckCountsTowardsTheStartDeadline() = runBlocking {
        // The check came back 1 s after the line was asked: half a second is left to start, not 1.5 s.
        val r = OneShotStart.await(fast, askedAt = 10_000, now = { 11_000L }, client = CompletableDeferred("client"))
        assertEquals(OneShotStart.Wait.Ready("client", 10_000, 11_500), r)
        // The check is still waiting when the deadline passes: not started, and the check is left to finish by itself.
        val stuck = CompletableDeferred<String?>()
        val t0 = System.nanoTime()
        val late = OneShotStart.await(fast.copy(startWithinMs = 300), askedAt = 10_000, now = { 10_100L }, client = stuck)
        assertEquals(OneShotStart.Wait.Ended(OneShotResult.NotStarted), late)
        assertTrue((System.nanoTime() - t0) / 1_000_000 in 150L..2_000L)
        assertTrue(stuck.isActive)
        // Already past it when the request comes to be sent: not started at once.
        assertEquals(OneShotStart.Wait.Ended(OneShotResult.NotStarted),
            OneShotStart.await(fast, askedAt = 10_000, now = { 12_000L }, client = CompletableDeferred<String?>()))
        // A check that ends just as the deadline passes still counts.
        assertTrue(OneShotStart.await(fast, askedAt = 10_000, now = { 12_000L }, client = CompletableDeferred("client")) is OneShotStart.Wait.Ready)
    }

    @Test fun withoutItTheDeadlineCountsFromTheReadyModel() = runBlocking {
        // A load may take longer than the start deadline: waited for, and the deadline counts from then.
        val loading = CompletableDeferred<String?>()
        val load = Thread { Thread.sleep(200); loading.complete("client") }.apply { start() }
        var now = 10_000L
        val alone = OneShotBudget(100, 15_000)
        val r = OneShotStart.await(alone, askedAt = 10_000, now = { now.also { now += 5_000 } }, client = loading)
        load.join()
        assertEquals(OneShotStart.Wait.Ready("client", 10_000, 10_100), r)
        // A summary step has no start deadline.
        assertEquals(OneShotStart.Wait.Ready("client", 20_000, null),
            OneShotStart.await(OneShotBudget(null, 120_000), askedAt = 10_000, now = { 20_000L }, client = CompletableDeferred("client")))
    }

    @Test fun noModelToAskIsUnavailable() = runBlocking {
        val r = OneShotStart.await(fast, askedAt = 0, now = { 0L }, client = CompletableDeferred<String?>().apply { complete(null) })
        assertEquals(OneShotStart.Wait.Ended(OneShotResult.Failed(null, unavailable = true)), r)
        assertFalse(r is OneShotStart.Wait.Ready)
    }
}
