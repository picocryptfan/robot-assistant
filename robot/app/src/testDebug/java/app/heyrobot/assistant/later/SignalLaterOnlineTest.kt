// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterState
import app.heyrobot.sys.LaterVault
import app.heyrobot.sys.SoftMemoryKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

// fix-later-ready-offline: on the Pixel 8 in airplane mode the job took a kept question as back online 126 ms after
// the keep. A VPN alone, marked validated with nothing under it, is not a connection; one over Wi-Fi is.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignalLaterOnlineTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val js = ctx.getSystemService(JobScheduler::class.java)
    private val savedVault = SignalLater.vaultOf
    private val savedNow = SignalLater.now
    private val savedExecuting = SignalLater.executing
    @get:Rule val tmp = TemporaryFolder()

    @After fun restore() {
        SignalLater.vaultOf = savedVault
        SignalLater.now = savedNow
        SignalLater.executing = savedExecuting
        js.cancelAll()
    }

    /** The queue holds one question in each of [states]. */
    private fun queue(vararg states: LaterState) {
        val v = LaterVault(tmp.newFolder("later"), SoftMemoryKeys())
        val now = System.currentTimeMillis()
        v.update { states.mapIndexed { n, st -> LaterItem("q$n", "ferry times to digby", "ferry times digby", Lang.EN, now, false, st) } }
        SignalLater.vaultOf = { v }
    }

    @Before fun noNetworks() {
        shadowOf(cm).clearAllNetworks()
        shadowOf(cm).setDefaultNetworkActive(false)
        SignalLater.lastReplace = 0L
        SignalLater.lastOnline = 0L
        SignalLater.executing = { false }
    }

    /** [active]: Robot's default network too (the shadow keys it by type, so [id] must be the type's). */
    private fun net(id: Int, transport: Int, validated: Boolean, active: Boolean = false): Network {
        val n = ShadowNetwork.newInstance(id)
        val type = if (transport == NetworkCapabilities.TRANSPORT_VPN) ConnectivityManager.TYPE_VPN else ConnectivityManager.TYPE_WIFI
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        if (active) {
            shadowOf(cm).setDefaultNetworkActive(true)
            shadowOf(cm).setActiveNetworkInfo(info)
        }
        shadowOf(cm).addNetwork(n, info)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(n, caps)
        return n
    }

    @Test fun aVpnAloneIsNoConnection() {
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertFalse(SignalLater.validated(ctx, vpn))
        assertFalse(SignalLater.validated(ctx))
    }

    @Test fun aVpnOverValidatedWifiIsOne() {
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        val wifi = net(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, validated = true, active = true)
        assertEquals(wifi, cm.activeNetwork)
        assertTrue(SignalLater.validated(ctx, vpn))
        assertTrue(SignalLater.validated(ctx))
    }

    // review M1: no default network (an always-on VPN set to block while it is down) is no connection, even with a validated
    // Wi-Fi Robot may not use.
    @Test fun aBlockedDefaultIsNoConnection() {
        net(102, NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        assertNull(cm.activeNetwork)
        assertFalse(SignalLater.validated(ctx))
        assertEquals(SignalLater.Link.BLOCKED, SignalLater.link(ctx))
    }

    // review M2: with only a VPN up the job's network constraint stays met, so its backoff would grow to hours; the run
    // asks for no backoff and a fresh job in RETRY_MS instead, while a question waits (review N1), until Later.WAIT_MS.
    @Test fun aVpnAloneTriesAgainInMinutes() {
        // the deadline is the question's askedAt + WAIT_MS, so take the time before queue() writes it.
        val before = System.currentTimeMillis()
        queue(LaterState.WAITING)
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertEquals(SignalLater.Link.VPN_ONLY, SignalLater.link(ctx, vpn))
        assertFalse(SignalLater.run(ctx, vpn) { false })
        val job = js.getPendingJob(SignalLater.JOB_ID)
        assertNotNull(job)
        assertEquals(SignalLater.RETRY_MS, job!!.minLatencyMillis)
        val until = job.extras.getLong(SignalLater.UNTIL)
        assertTrue(until >= before + Later.WAIT_MS && until <= System.currentTimeMillis() + Later.WAIT_MS)
    }

    // review N1: with nothing left to ask (sent from the panel, dropped, expired) a VPN alone brings no fresh job.
    @Test fun aVpnAloneWithNothingWaitingEnds() {
        queue(LaterState.READY, LaterState.ANSWERED)
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertFalse(SignalLater.run(ctx, vpn) { false })
        assertNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    // review N1: past its deadline, the backoff as before, and nothing scheduled. review-fix-later-ready-offline-c: only
    // while the queue can't be read (locked); readable, a waiting question's own 24 h is the deadline (below).
    @Test fun aVpnAlonePastItsDeadlineTakesTheBackoff() {
        SignalLater.vaultOf = { throw IllegalStateException("locked") }
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertTrue(SignalLater.run(ctx, vpn, until = System.currentTimeMillis() - 1) { false })
        assertNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    // review-fix-later-ready-offline-c: a question kept after the first retry set the deadline still tries in minutes.
    @Test fun aReadableQueuePastTheDeadlineStillTriesInMinutes() {
        val before = System.currentTimeMillis()
        queue(LaterState.WAITING)
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertFalse(SignalLater.run(ctx, vpn, until = before - 1) { false })
        val job = js.getPendingJob(SignalLater.JOB_ID)!!
        assertEquals(SignalLater.RETRY_MS, job.minLatencyMillis)
        assertTrue(job.extras.getLong(SignalLater.UNTIL) >= before + Later.WAIT_MS)
    }

    // review-fix-later-ready-offline-c: a question kept while a run is on, which then ends its job, still has one.
    @Test fun aKeepDuringARunThatEndsRunsAgain() {
        val saved = LaterJobService.runner
        try {
            LaterJobService.runner = { c, _, _, _ -> assertTrue(SignalLater.scheduleKept(c)); false }
            val done = ArrayBlockingQueue<Boolean>(1)
            Robolectric.setupService(LaterJobService::class.java).start(null) { done.put(it) }
            assertEquals(true, done.poll(10, TimeUnit.SECONDS))
        } finally {
            LaterJobService.runner = saved
            LaterJobService.kept = false
            LaterJobService.endedAt = Long.MIN_VALUE / 2
        }
    }

    // ...and one kept just after a run ended its job (still pending to keep's eye) gets a fresh job, not the old one.
    @Test fun aKeepJustAfterARunEndedGetsAFreshJob() {
        val saved = LaterJobService.runner
        try {
            LaterJobService.runner = { _, _, _, _ -> false }
            val done = ArrayBlockingQueue<Boolean>(1)
            Robolectric.setupService(LaterJobService::class.java).start(null) { done.put(it) }
            assertEquals(false, done.poll(10, TimeUnit.SECONDS))
            assertTrue(SignalLater.retry(ctx, 1234L)) // the ended job, still pending
            assertTrue(SignalLater.scheduleKept(ctx))
            assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        } finally {
            LaterJobService.runner = saved
            LaterJobService.endedAt = Long.MIN_VALUE / 2
        }
    }

    // review-fix-later-ready-offline-c: a question kept between the unlock's read and its cancel still has a job.
    @Test fun anUnlockRacingAKeepLeavesAJob() {
        queue(LaterState.READY)
        val v = SignalLater.vaultOf(ctx)
        var reads = 0
        SignalLater.vaultOf = {
            if (++reads == 2) v.update { it + LaterItem("k", "ferry times to digby", "ferry times digby", Lang.EN, System.currentTimeMillis(), false, LaterState.WAITING) }
            v
        }
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.unlocked(ctx)
        assertNotNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    // review N1: locked, the queue can't be read, so the fresh job comes while the deadline holds, and keeps it.
    @Test fun aVpnAloneLockedKeepsItsDeadline() {
        SignalLater.vaultOf = { throw IllegalStateException("locked") }
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        val until = System.currentTimeMillis() + 60_000
        assertFalse(SignalLater.run(ctx, vpn, until) { false })
        assertEquals(until, js.getPendingJob(SignalLater.JOB_ID)!!.extras.getLong(SignalLater.UNTIL))
    }

    // review P1: a job held back (the lock's backoff, a VPN's wait) runs at the unlock, its deadline kept.
    @Test fun anUnlockRunsAHeldJobNow() {
        queue(LaterState.WAITING)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.unlocked(ctx)
        val job = js.getPendingJob(SignalLater.JOB_ID)!!
        assertEquals(0L, job.minLatencyMillis)
        assertEquals(1234L, job.extras.getLong(SignalLater.UNTIL))
    }

    @Test fun anUnlockWithNothingWaitingCancelsTheJob() {
        queue(LaterState.READY)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.unlocked(ctx)
        assertNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    // rc2 row 2: the retry's job sat ready 30 minutes after Wi-Fi came back; a real network now runs it at once.
    @Test fun aRealNetworkRunsARetryNow() {
        queue(LaterState.WAITING)
        net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertTrue(SignalLater.retry(ctx, 1234L))
        net(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, validated = true, active = true)
        SignalLater.online(ctx)
        val job = js.getPendingJob(SignalLater.JOB_ID)!!
        assertEquals(0L, job.minLatencyMillis)
        assertEquals(1234L, job.extras.getLong(SignalLater.UNTIL))
    }

    @Test fun aVpnAloneKeepsTheRetry() {
        queue(LaterState.WAITING)
        net(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN, validated = true, active = true)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.online(ctx)
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    @Test fun aBlockedRealNetworkKeepsTheRetry() {
        queue(LaterState.WAITING)
        assertTrue(SignalLater.retry(ctx, 1234L))
        net(102, NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        SignalLater.online(ctx)
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    // review M1: the network callback and the unlock at once (two threads) replace the job once: one run, one notice.
    @Test fun twoEventsAtOnceReplaceOnce() {
        queue(LaterState.WAITING)
        assertTrue(SignalLater.retry(ctx, 1234L))
        val gate = java.util.concurrent.CyclicBarrier(2)
        val replaced = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(2) { Thread { gate.await(); if (SignalLater.unlocked(ctx)) replaced.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(1, replaced.get())
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    @Test fun aReplaceUnderAMinuteAfterTheLastIsSkipped() {
        queue(LaterState.WAITING)
        var t = 1_000_000L
        SignalLater.now = { t }
        assertTrue(SignalLater.retry(ctx, 1234L))
        assertTrue(SignalLater.unlocked(ctx))
        assertTrue(SignalLater.retry(ctx, 1234L))
        t += SignalLater.GAP_MS - 1
        assertFalse(SignalLater.unlocked(ctx))
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        t += 1
        assertTrue(SignalLater.unlocked(ctx))
    }

    // review M1: Android already starting the job (its onStartJob not here yet) isn't replaced under it.
    @Test fun aJobAndroidRunsIsNotReplaced() {
        queue(LaterState.WAITING)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.executing = { true }
        assertFalse(SignalLater.unlocked(ctx))
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    @Test fun aRunningJobIsNotReplaced() {
        queue(LaterState.WAITING)
        assertTrue(SignalLater.retry(ctx, 1234L))
        LaterJobService.running.incrementAndGet()
        try {
            assertFalse(SignalLater.unlocked(ctx))
        } finally {
            LaterJobService.running.decrementAndGet()
        }
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    // review L2: a network flapping between Wi-Fi and mobile data resets the backoff once in RETRY_MS, not each flap.
    @Test fun aFlappingNetworkReplacesOnceInTenMinutes() {
        queue(LaterState.WAITING)
        var t = 1_000_000L
        SignalLater.now = { t }
        net(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, validated = true, active = true)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.online(ctx)
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        assertTrue(SignalLater.retry(ctx, 1234L)) // the run's backoff
        t += SignalLater.RETRY_MS - 1
        SignalLater.online(ctx)
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        t += 1
        SignalLater.online(ctx)
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    // review L1: with a VPN that blocks connections without it, Wi-Fi validates blocked; the unblock runs the retry now.
    @Test fun anUnblockedNetworkRunsTheRetry() {
        queue(LaterState.WAITING)
        val wifi = net(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, validated = true, active = true)
        assertTrue(SignalLater.retry(ctx, 1234L))
        val before = shadowOf(cm).networkCallbacks.toSet()
        SignalLater.start(ctx)
        val cb = (shadowOf(cm).networkCallbacks - before).single { it !is SignalLater.DefaultNet }
        cb.onBlockedStatusChanged(wifi, true)
        assertEquals(SignalLater.RETRY_MS, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        cb.onBlockedStatusChanged(wifi, false)
        val end = System.nanoTime() + 10_000_000_000L
        while (js.getPendingJob(SignalLater.JOB_ID)?.minLatencyMillis != 0L && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    // The callbacks are registered at :voice's start: one for real networks only (the request's own NOT_VPN), and
    // review-fix-later-job-fire-b L2's for the default network.
    @Test fun theStartListensForARealNetwork() {
        val before = shadowOf(cm).networkCallbacks.toSet()
        SignalLater.start(ctx)
        val added = shadowOf(cm).networkCallbacks - before
        assertEquals(2, added.size)
        assertEquals(1, added.count { it is SignalLater.DefaultNet })
    }

    // review-fix-later-job-fire-b L2: "block connections without VPN" keeps Wi-Fi blocked, VPN up or down, so no unblock
    // comes; the VPN becoming the default network runs the retry, a real network under it.
    @Test fun aVpnBecomingTheDefaultRunsTheRetry() {
        queue(LaterState.WAITING)
        net(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        val vpn = net(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN, validated = true, active = true)
        assertTrue(SignalLater.retry(ctx, 1234L))
        SignalLater.DefaultNet(ctx).onAvailable(vpn)
        val end = System.nanoTime() + 10_000_000_000L
        while (js.getPendingJob(SignalLater.JOB_ID)?.minLatencyMillis != 0L && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
        assertEquals(1234L, js.getPendingJob(SignalLater.JOB_ID)!!.extras.getLong(SignalLater.UNTIL))
    }

    @Test fun anUnlockWithNoJobAndNothingWaitingSchedulesNone() {
        queue(LaterState.READY, LaterState.ANSWERED)
        SignalLater.unlocked(ctx)
        assertNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    // review-fix-later-job-fire-b L3: a keep racing a cancel or a run's end (or :voice dying after its write) left a waiting
    // question with no job, and nothing brought one back; the next unlock does.
    @Test fun anUnlockGivesAWaitingQuestionWithNoJobOne() {
        queue(LaterState.WAITING)
        assertFalse(SignalLater.unlocked(ctx))
        assertEquals(0L, js.getPendingJob(SignalLater.JOB_ID)!!.minLatencyMillis)
    }

    // review-fix-later-job-fire-b L1: a run Android starts while another is on (the job replaced under it) waits for that
    // one to save before it reads the queue.
    @Test fun twoRunsAtOnceReadTheQueueInTurn() {
        queue(LaterState.WAITING)
        val v = SignalLater.vaultOf(ctx)
        val vpn = net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        val inFirst = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        SignalLater.vaultOf = {
            if (reads.incrementAndGet() == 1) { inFirst.countDown(); go.await(10, TimeUnit.SECONDS) }
            v
        }
        val first = Thread { SignalLater.run(ctx, vpn) { false } }.apply { start() }
        assertTrue(inFirst.await(10, TimeUnit.SECONDS))
        val second = Thread { SignalLater.run(ctx, vpn) { false } }.apply { start() }
        val end = System.nanoTime() + 10_000_000_000L
        while (second.state != Thread.State.BLOCKED && second.isAlive && System.nanoTime() < end) Thread.sleep(10)
        assertEquals(Thread.State.BLOCKED, second.state)
        assertEquals(1, reads.get())
        go.countDown()
        first.join(10_000)
        second.join(10_000)
        assertEquals(2, reads.get())
    }

    @Test fun noNetworkKeepsTheBackoff() {
        assertEquals(SignalLater.Link.NONE, SignalLater.link(ctx))
        assertTrue(SignalLater.run(ctx, null) { false })
        assertNull(js.getPendingJob(SignalLater.JOB_ID))
    }

    @Test fun link() {
        val wifi = SignalLater.Net(internet = true, validated = true, vpn = false)
        val vpn = SignalLater.Net(internet = true, validated = true, vpn = true)
        assertEquals(SignalLater.Link.REAL, SignalLater.link(true, listOf(vpn, wifi)))
        assertEquals(SignalLater.Link.BLOCKED, SignalLater.link(false, listOf(wifi)))
        assertEquals(SignalLater.Link.VPN_ONLY, SignalLater.link(true, listOf(vpn)))
        assertEquals(SignalLater.Link.NONE, SignalLater.link(true, emptyList()))
    }

    @Test fun wifiNotValidatedIsNoConnection() {
        val wifi = net(102, NetworkCapabilities.TRANSPORT_WIFI, validated = false)
        assertFalse(SignalLater.validated(ctx, wifi))
        net(101, NetworkCapabilities.TRANSPORT_VPN, validated = true)
        assertFalse(SignalLater.validated(ctx, wifi))
    }

    @Test fun real() {
        assertFalse(SignalLater.real(emptyList()))
        assertFalse(SignalLater.real(listOf(SignalLater.Net(internet = true, validated = true, vpn = true))))
        assertFalse(SignalLater.real(listOf(SignalLater.Net(internet = false, validated = true, vpn = false))))
        assertTrue(SignalLater.real(listOf(SignalLater.Net(internet = true, validated = true, vpn = true), SignalLater.Net(internet = true, validated = true, vpn = false))))
        assertFalse(SignalLater.real(listOf(SignalLater.Net(internet = true, validated = true, vpn = false, restricted = true))))
    }
}
