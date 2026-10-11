package app.heyrobot.sys

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.Abort
import app.heyrobot.core.LocationCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Voice process (the weather tool), and the main app for its permission row. Where the phone is, coarsely, for the
// weather "here" (0.4.0). Robot holds ACCESS_COARSE_LOCATION only, asked for by the main app when you tap Allow, so
// Android gives it an approximate position; Robot rounds it again to two decimals before Open-Meteo sees it. GrapheneOS
// has no Google network location, so a GPS-only phone may be slow or give nothing indoors: a recent last known position
// of any provider is used at once, else every provider is asked for a new one for at most [TIMEOUT_MS], else a last
// known position up to an hour old (an older one may be another town). "While in use" location may be refused to the
// assistant's process when Android counts it as in the background: Robot asks the location app-op first and says so
// ([LocationCode.BACKGROUND]). Nothing here is logged; the tool logs the code.

object CoarseLocation {
    const val TIMEOUT_MS = 10_000L
    /** A last known position this recent is used without asking for a new one. */
    private const val RECENT_MS = 30 * 60_000L
    /** Without a new fix, an older one still tells the town, up to this old; older, you may have travelled. */
    private const val OLD_MS = 3_600_000L

    /** [fresh]: a new fix, not the last known one. */
    data class Fix(val lat: Double, val lon: Double, val fresh: Boolean)

    /** What [find] found: the position or none, how ([code]), the process's [importance] then and the [ms] it took. */
    data class Found(val fix: Fix?, val code: LocationCode, val importance: Int, val ms: Int)

    fun granted(ctx: Context): Boolean =
        Assistant.granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) || Assistant.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Location is on in Android's settings (true when Android won't say). */
    fun enabled(ctx: Context): Boolean = try { ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled != false } catch (_: Exception) { true }

    /**
     * Where the phone is, and how it was found or why not. Blocking, for at most about [timeoutMs] (and never past
     * [abort]'s deadline; Stop ends the wait at once): never on the main thread.
     */
    fun find(ctx: Context, timeoutMs: Long = TIMEOUT_MS, abort: Abort = Abort()): Found {
        val started = SystemClock.elapsedRealtime()
        val importance = importance()
        fun found(fix: Fix?, code: LocationCode) = Found(fix, code, importance, (SystemClock.elapsedRealtime() - started).toInt())
        if (!granted(ctx)) return found(null, LocationCode.NOT_ALLOWED)
        if (!enabled(ctx)) return found(null, LocationCode.OFF)
        // "While in use": Android says here whether it would give the assistant's process a position right now.
        if (!allowedNow(ctx)) return found(null, LocationCode.BACKGROUND)
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return found(null, LocationCode.NONE)
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList() }
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.elapsedRealtimeNanos }
        fun age(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (last != null && age(last) <= RECENT_MS) return found(Fix(last.latitude, last.longitude, fresh = false), LocationCode.RECENT)
        // Every provider at once (GPS, and a network one if the phone has it); the first position wins.
        val asking = providers.filter { it != LocationManager.PASSIVE_PROVIDER }
        val got = AtomicReference<Location?>()
        val pending = AtomicInteger(asking.size)
        val done = CountDownLatch(1)
        val signals = asking.map { CancellationSignal() }
        asking.forEachIndexed { i, p ->
            try {
                lm.getCurrentLocation(p, signals[i], ctx.mainExecutor) { l ->
                    if (l != null) got.compareAndSet(null, l)
                    if (l != null || pending.decrementAndGet() <= 0) done.countDown()
                }
            } catch (_: Exception) {
                if (pending.decrementAndGet() <= 0) done.countDown()
            }
        }
        val forget = abort.onCancel { done.countDown() }
        if (asking.isNotEmpty()) {
            try { done.await(minOf(timeoutMs, abort.remainingMs()), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        forget()
        signals.forEach { try { it.cancel() } catch (_: Exception) {} }
        if (abort.cancelled) return found(null, LocationCode.STOPPED)
        got.get()?.let { return found(Fix(it.latitude, it.longitude, fresh = true), LocationCode.FIX) }
        return when {
            last == null -> found(null, LocationCode.NONE)
            age(last) <= OLD_MS -> found(Fix(last.latitude, last.longitude, fresh = false), LocationCode.LAST_HOUR)
            else -> found(null, LocationCode.TOO_OLD)
        }
    }

    /** The location app-op as Android would decide it for this process now (it counts as one location access). */
    private fun allowedNow(ctx: Context): Boolean = try {
        ctx.getSystemService(AppOpsManager::class.java).noteOpNoThrow(AppOpsManager.OPSTR_COARSE_LOCATION, Process.myUid(), ctx.packageName,
            null, "weather") == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        true
    }

    /** How Android ranks this process now (RunningAppProcessInfo.importance), for the log; 0 if it won't say. */
    private fun importance(): Int = try {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
    } catch (_: Exception) {
        0
    }
}
