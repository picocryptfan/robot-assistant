package app.heyrobot.sys

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Voice process (the weather tool), and the main app for its permission row. Where the phone is, coarsely, for the
// weather "here" (0.4.0). Robot holds ACCESS_COARSE_LOCATION only, asked for by the main app when you tap Allow, so
// Android gives it an approximate position; Robot rounds it again to two decimals before Open-Meteo sees it. GrapheneOS
// has no Google network location, so a GPS-only phone may be slow or give nothing indoors: a recent last known position
// of any provider is used at once, else every provider is asked for a new one for at most [TIMEOUT_MS], else a last
// known position up to a day old. Nothing here is logged.

object CoarseLocation {
    const val TIMEOUT_MS = 10_000L
    /** A last known position this recent is used without asking for a new one. */
    private const val RECENT_MS = 30 * 60_000L
    /** Without a new fix, an older one still tells the town. */
    private const val OLD_MS = 24 * 3_600_000L

    /** [fresh]: a new fix, not the last known one. */
    data class Fix(val lat: Double, val lon: Double, val fresh: Boolean)

    fun granted(ctx: Context): Boolean =
        Assistant.granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) || Assistant.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Location is on in Android's settings (true when Android won't say). */
    fun enabled(ctx: Context): Boolean = try { ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled != false } catch (_: Exception) { true }

    /** Where the phone is, or null. Blocking, for at most about [timeoutMs]: never on the main thread. */
    fun find(ctx: Context, timeoutMs: Long = TIMEOUT_MS): Fix? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList() }
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.elapsedRealtimeNanos }
        fun age(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (last != null && age(last) <= RECENT_MS) return Fix(last.latitude, last.longitude, fresh = false)
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
        if (asking.isNotEmpty()) {
            try { done.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        signals.forEach { try { it.cancel() } catch (_: Exception) {} }
        got.get()?.let { return Fix(it.latitude, it.longitude, fresh = true) }
        return last?.takeIf { age(it) <= OLD_MS }?.let { Fix(it.latitude, it.longitude, fresh = false) }
    }
}
