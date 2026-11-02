// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import app.heyrobot.core.link.PushApp
import app.heyrobot.core.link.PushApps
import app.heyrobot.core.link.PushRoute
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.random.Random

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2 "Being woken", §5.3; R0 §5; job L3): your push
// distributor app reaching your Robot, through the UnifiedPush connector 3.3.5.
//
// The receiver ([LinkPushReceiver]) is Robot's own subclass of the connector's MessagingReceiver, in :link: exported (a
// distributor must reach it) with exactly the connector's five actions; the connector's own MessagingReceiverImpl is
// removed from the manifest, so this is the only one. A broadcast is honoured only with a registration token the connector
// gave that distributor; a message is the connector's RFC 8291 decryption of the push body with that instance's keys (its
// DefaultKeyManager: the keys in its own database, sealed by its own Keystore key, alias "UnifiedPush"). A message the
// connector couldn't decrypt reaches here marked so (it would otherwise pass the body on as plain text): refused and counted.
//
// Nothing is decided in the receiver: each event becomes an expedited job ([PushJobs]) for :link's job service, which opens
// the vault, hands the event to LinkNode and answers (§2.2: the plan's expedited job; it bypasses Doze's network limits
// within its quota). The event travels in the job's extras, held by the system in memory, never in a file: the push body is
// still the session's ciphertext (or a sealed hello) and the instance's name is random. If the job can't run before the
// phone restarts, the event is lost and the other Robot sends again (its outbox waits for an ack).

/** One event from the distributor, as a job carries it. */
class PushEvent(val kind: Kind, val instance: String, bytes: ByteArray? = null, val route: PushRoute? = null) {
    private val b = bytes?.copyOf()
    val bytes: ByteArray? get() = b?.copyOf()

    enum class Kind(val code: Int) {
        /** A push message, decrypted by RFC 8291: an envelope. */
        MESSAGE(1),
        /** A new push address for [instance]. */
        ENDPOINT(2),
        /** The distributor dropped [instance]. */
        UNREGISTERED(3),
        /** Registering [instance] failed (no network, no distributor…). */
        FAILED(4),
        /** A message the connector couldn't decrypt: counted, never used. */
        REFUSED(5);

        companion object {
            fun of(code: Int): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    override fun toString(): String = "PushEvent($kind)" // l10n: not text (toString for logs and tests)

    fun extras(): PersistableBundle = PersistableBundle().apply {
        putInt(K_KIND, kind.code)
        putString(K_INSTANCE, instance)
        b?.let { putString(K_BYTES, Base64.getEncoder().encodeToString(it)) }
        route?.let { putString(K_URL, it.endpoint); putString(K_KEY, Base64.getEncoder().encodeToString(it.p256dh)); putString(K_AUTH, Base64.getEncoder().encodeToString(it.auth)) }
    }

    companion object {
        private const val K_KIND = "k" // l10n: not text (a bundle key)
        private const val K_INSTANCE = "i" // l10n: not text (a bundle key)
        private const val K_BYTES = "b" // l10n: not text (a bundle key)
        private const val K_URL = "u" // l10n: not text (a bundle key)
        private const val K_KEY = "p" // l10n: not text (a bundle key)
        private const val K_AUTH = "a" // l10n: not text (a bundle key)

        /** An instance name Robot made: a random UUID (R0 item 14), nothing else. */
        fun instanceOk(s: String): Boolean = INSTANCE.matches(s)
        private val INSTANCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}") // l10n: not text (a UUID's form)

        /** The event in a job's [extras], checked as strictly as anything that came from outside; null otherwise. */
        fun of(extras: PersistableBundle): PushEvent? = try {
            val kind = Kind.of(extras.getInt(K_KIND, 0)) ?: return null
            val instance = extras.getString(K_INSTANCE)?.takeIf(::instanceOk) ?: return null
            when (kind) {
                Kind.MESSAGE -> {
                    val b = Base64.getDecoder().decode(extras.getString(K_BYTES) ?: return null)
                    if (b.size !in 1..WebPush.MAX_PLAIN) return null
                    PushEvent(kind, instance, b)
                }
                Kind.ENDPOINT -> {
                    val url = extras.getString(K_URL) ?: return null
                    val k = Base64.getDecoder().decode(extras.getString(K_KEY) ?: return null)
                    val a = Base64.getDecoder().decode(extras.getString(K_AUTH) ?: return null)
                    if (!PushRoute.valid(url, k, a)) return null
                    PushEvent(kind, instance, route = PushRoute(url, k, a))
                }
                else -> PushEvent(kind, instance)
            }
        } catch (_: Exception) {
            null
        }

        /** The connector's keys for an endpoint (base64url, as RFC 8291 writes them) as a push route; null unless https. */
        fun routeOf(endpoint: PushEndpoint): PushRoute? = try {
            val keys = endpoint.pubKeySet ?: return null
            val k = Base64.getUrlDecoder().decode(keys.pubKey.trimEnd('='))
            val a = Base64.getUrlDecoder().decode(keys.auth.trimEnd('='))
            if (PushRoute.valid(endpoint.url, k, a)) PushRoute(endpoint.url, k, a) else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * L5-fix: what PushJobs asks of the system's JobScheduler, a seam so its cap is tested on the JVM ([SystemJobSlots] on the
 * phone).
 */
interface JobSlots {
    /** The ids of every job of Robot's still pending. */
    fun pending(): List<Int>
    /** [e]'s job under [id]: expedited when the quota allows, else an ordinary one. */
    fun schedule(id: Int, e: PushEvent): Boolean
}

/** Expedited jobs for :link's job service (app.heyrobot.link.LinkJobService, named here by string: this module never names the app). */
object PushJobs {
    const val SERVICE = "app.heyrobot.link.LinkJobService" // l10n: not text (a class name)
    /** Job ids from 0x52520000 ("RR"): the push events' own range, apart from the updater's job. */
    const val FIRST_ID = 0x52520000
    const val IDS = 0x10000
    /**
     * L5-fix: at most this many push jobs wait at once. Past it an event is dropped: a flood to a push address can't fill
     * JobScheduler (each job a wake-up of :link), and the other Robot sends again (its outbox waits for an ack).
     */
    const val MAX_PENDING = 50

    /** Schedules [e]'s job on the phone. */
    fun schedule(ctx: Context, e: PushEvent): Boolean {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return false
        return schedule(SystemJobSlots(ctx, js), e)
    }

    /**
     * Schedules [e]'s job in [slots], unless [MAX_PENDING] wait already. A job id still pending is never reused:
     * JobScheduler would replace that job, and its event with it. One caller at a time (the receiver's own thread, and this lock).
     */
    @Synchronized
    fun schedule(slots: JobSlots, e: PushEvent): Boolean {
        val taken = slots.pending().filterTo(HashSet()) { it in FIRST_ID until FIRST_ID + IDS }
        if (taken.size >= MAX_PENDING) return false
        val id = freeId(Random.nextInt(IDS)) { it in taken } ?: return false
        return slots.schedule(id, e)
    }

    /** The first id from [start] (an offset in the range) that [pending] doesn't hold; null when every one is pending. */
    fun freeId(start: Int, pending: (Int) -> Boolean): Int? {
        for (k in 0 until IDS) {
            val id = FIRST_ID + (start + k) % IDS
            if (!pending(id)) return id
        }
        return null
    }
}

/** The phone's JobScheduler as [JobSlots]: each event's job for :link's job service, the event in its extras. */
class SystemJobSlots(private val ctx: Context, private val js: JobScheduler) : JobSlots {
    override fun pending(): List<Int> = js.allPendingJobs.map { it.id }

    override fun schedule(id: Int, e: PushEvent): Boolean {
        val component = ComponentName(ctx.packageName, PushJobs.SERVICE)
        fun job(expedited: Boolean) = JobInfo.Builder(id, component)
            .setExtras(e.extras())
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExpedited(expedited)
            .build()
        // Expedited when the quota allows; else an ordinary job, which waits for Doze's windows.
        if (js.schedule(job(true)) == JobScheduler.RESULT_SUCCESS) return true
        return js.schedule(job(false)) == JobScheduler.RESULT_SUCCESS
    }
}

/** Robot's receiver of the distributor's broadcasts, in :link. Each event becomes a job; nothing else happens here. */
class LinkPushReceiver : MessagingReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                super.onReceive(context, intent)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (!PushEvent.instanceOk(instance)) return
        val e = if (!message.decrypted) {
            PushEvent(PushEvent.Kind.REFUSED, instance)
        } else {
            val b = message.content
            if (b.size !in 1..WebPush.MAX_PLAIN) return
            PushEvent(PushEvent.Kind.MESSAGE, instance, b)
        }
        PushJobs.schedule(context, e)
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        if (!PushEvent.instanceOk(instance)) return
        // A push address that isn't https (an emulator's plain-http push server included) is never used: no event at all.
        val route = PushEvent.routeOf(endpoint) ?: return
        PushJobs.schedule(context, PushEvent(PushEvent.Kind.ENDPOINT, instance, route = route))
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        if (PushEvent.instanceOk(instance)) PushJobs.schedule(context, PushEvent(PushEvent.Kind.FAILED, instance))
    }

    override fun onUnregistered(context: Context, instance: String) {
        if (PushEvent.instanceOk(instance)) PushJobs.schedule(context, PushEvent(PushEvent.Kind.UNREGISTERED, instance))
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-push").apply { isDaemon = true } } // l10n: not text (a thread's name)
    }
}

/** Your distributor through the connector, in :link: one instance per pairing and per open code, no description (§3.8). */
class ConnectorPush(private val ctx: Context) : PushNet {
    override fun available(): Boolean = try {
        UnifiedPush.getAckDistributor(ctx) != null || UnifiedPush.getSavedDistributor(ctx) != null
    } catch (_: Exception) {
        false
    }

    override fun register(instance: String): Boolean = try {
        require(PushEvent.instanceOk(instance))
        // No messageForDistributor (it would show in the distributor's list) and no VAPID (no application server).
        UnifiedPush.register(ctx, instance, null, null)
        true
    } catch (_: Exception) {
        false
    }

    override fun unregister(instance: String) {
        try { UnifiedPush.unregister(ctx, instance) } catch (_: Exception) {}
    }

    /** The apps that offer to carry push, each with its name: Robot never picks for you, not even the only one (F27). */
    fun apps(): List<PushApp> = try {
        UnifiedPush.getDistributors(ctx).filter { it != ctx.packageName && PushApps.packageOk(it) }.take(PushApps.MAX_APPS).map { pkg ->
            val label = try { ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)) } } catch (_: Exception) { null }
            PushApp(pkg, PushApps.label(label, pkg))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** The app you chose, if it's still installed. */
    fun chosen(): String? = try { UnifiedPush.getSavedDistributor(ctx) } catch (_: Exception) { null }

    /** Saves your choice ([pkg]; empty: none). The connector drops what was registered with the app before. */
    fun use(pkg: String): Boolean = try {
        if (pkg.isEmpty()) UnifiedPush.removeDistributor(ctx) else UnifiedPush.saveDistributor(ctx, pkg)
        true
    } catch (_: Exception) {
        false
    }

    /** Start over: every registration and the chosen distributor forgotten. */
    fun forgetAll(instances: Collection<String>) {
        for (i in instances) unregister(i)
        try { UnifiedPush.removeDistributor(ctx) } catch (_: Exception) {}
    }
}
