// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import android.os.SystemClock
import app.heyrobot.assistant.WebTool
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterGate
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterPort
import app.heyrobot.core.online.LaterRun
import app.heyrobot.core.online.LaterState
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.sys.LaterVault
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.sys.SettingsStore
import java.util.UUID

// Voice process only. Signal later's two sides (docs/plans/signal-later-plan.md): [keep] (the panel) and [run] (LaterJobService,
// android:process=":voice", beside web search; the main process never runs a kept search or reads the queue). The job asks Android for any network and survives a
// reboot (persisted); Android starts it on a validated network, and [run] checks NET_CAPABILITY_VALIDATED again before
// anything goes. Each question is taken off the queue before it runs (once only). One whose ask-first rule asks (then or
// now, Later.mustAsk) never runs here: it waits, READY, for the panel, which sends it with its card. Web search's own
// gate (WebTool.prepare) and run (WebTool.run) do the rest, so What went online, today's cap and the cost are as ever.
object SignalLater {
    enum class Kept { KEPT, FULL, FAILED }

    /** The job's id, beside UpdateJobs' 4_800 to 4_805 (a different service, so ids must not clash). */
    const val JOB_ID = 4_806

    fun vault(ctx: Context): LaterVault = vaultOf(ctx)

    /** The queue's file (a seam for the tests). */
    @Volatile internal var vaultOf: (Context) -> LaterVault = { LaterVault.of(it) }

    /** A real connection now ([link] is [Link.REAL]). */
    fun validated(ctx: Context, network: Network? = null): Boolean = link(ctx, network) == Link.REAL

    /** What Robot can reach now; [code] names it in the voice log when a send is held. */
    enum class Link(val code: String) { REAL("real"), VPN_ONLY("vpn_only"), BLOCKED("blocked"), NONE("none") } // l10n: not text (log codes)

    /**
     * [network] (the job's), else the active one: none means Robot's network is blocked (an always-on VPN set to block
     * while it is down), and nothing counts. Then that one and every other network Robot may see ([real]): a VPN alone never
     * counts, as one can be up and marked validated with nothing under it (airplane mode, fix-later-ready-offline); a VPN
     * over Wi-Fi or mobile data counts through that network.
     */
    @Suppress("DEPRECATION") // getAllNetworks: still the one call that lists every network an app may see
    fun link(ctx: Context, network: Network? = null): Link = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val all = cm.allNetworks.toList()
        val own = network ?: cm.activeNetwork
        link(own != null, (listOfNotNull(own) + all).distinct().mapNotNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                Net(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN), !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
            }
        })
    } catch (_: Throwable) {
        Link.NONE
    }

    /** [own]: Robot has a network of its own (the job's or the active one); without one a real network is [Link.BLOCKED]. */
    internal fun link(own: Boolean, nets: List<Net>): Link = when {
        real(nets) -> if (own) Link.REAL else Link.BLOCKED
        nets.any { it.internet && it.validated && it.vpn } -> Link.VPN_ONLY
        else -> Link.NONE
    }

    /** A network as Android describes it: internet, validated, a VPN, restricted (not for apps like Robot). */
    internal data class Net(val internet: Boolean, val validated: Boolean, val vpn: Boolean, val restricted: Boolean = false)

    /** Some network with internet that Android validated, not a VPN and not restricted. */
    internal fun real(nets: List<Net>): Boolean = nets.any { it.internet && it.validated && !it.vpn && !it.restricted }

    /** What web search's gate says of [query] now (LaterGate.of): off, it asks (a card of any kind), or it goes; an error asks (fail closed). */
    fun gate(ctx: Context, settings: RobotSettings, said: String, query: String): LaterGate = try {
        LaterGate.of(WebTool(ctx, settings, Abort(1_000), said, { QueryContext(listOf(said)) }, { _, _ -> }).prepare(Action.WebSearch(query)))
    } catch (t: Throwable) {
        RLog.w("later_asks", t)
        LaterGate.Asks
    }

    /** [said] kept with its search words and its ask-first rule; the job scheduled. */
    fun keep(ctx: Context, said: String, query: String, lang: Lang, asks: Boolean, now: Long = System.currentTimeMillis()): Kept {
        var full = false
        val item = LaterItem(UUID.randomUUID().toString(), said, query, lang, now, asks)
        vault(ctx).update { items ->
            when (val a = Later.add(Later.expire(items, now), item)) {
                is Later.Add.Kept -> a.items
                Later.Add.Full -> items.also { full = true }
            }
        } ?: return Kept.FAILED
        if (full) return Kept.FULL
        RLog.i("later", "op" to RLog.Code("kept"), "asks" to asks) // l10n: not text (a log code)
        return if (scheduleKept(ctx)) Kept.KEPT else Kept.FAILED
    }

    /**
     * review-fix-later-ready-offline-c: [keep]'s job. A run on now may end its job (it read the queue before this question)
     * after this, so it is told to run again ([LaterJobService.kept]); a job that ended within [ENDED_MS] may still read as
     * pending, so a fresh one replaces it. Otherwise [schedule].
     */
    internal fun scheduleKept(ctx: Context): Boolean = synchronized(LaterJobService.ends) {
        when {
            LaterJobService.running.get() > 0 -> { LaterJobService.kept = true; true }
            SystemClock.elapsedRealtime() - LaterJobService.endedAt < ENDED_MS -> try {
                ctx.getSystemService(JobScheduler::class.java).schedule(job(ctx).build()) == JobScheduler.RESULT_SUCCESS
            } catch (t: Throwable) {
                RLog.w("later_schedule", t)
                false
            }
            else -> schedule(ctx)
        }
    }

    /** How long after a run ends its job [scheduleKept] doesn't trust a pending job (the end reaches the system a moment later). */
    internal const val ENDED_MS = 5_000L

    /** The one-shot job on any network, persisted; true when it is pending. */
    fun schedule(ctx: Context): Boolean = try {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.getPendingJob(JOB_ID) != null || js.schedule(job(ctx).build()) == JobScheduler.RESULT_SUCCESS
    } catch (t: Throwable) {
        RLog.w("later_schedule", t)
        false
    }

    /**
     * A VPN alone keeps the job's network constraint met, so its backoff (10 minutes more each try, up to 5 hours) would
     * hold an answer for hours after Wi-Fi comes back: instead the running job is replaced by a fresh one in [RETRY_MS]
     * (Android stops the old one; its finish is ignored), carrying [until], the last try's time. True when it is scheduled.
     */
    fun retry(ctx: Context, until: Long): Boolean = try {
        val extras = PersistableBundle().apply { putLong(UNTIL, until) }
        ctx.getSystemService(JobScheduler::class.java).schedule(job(ctx).setMinimumLatency(RETRY_MS).setExtras(extras).build()) == JobScheduler.RESULT_SUCCESS
    } catch (t: Throwable) {
        RLog.w("later_schedule", t)
        false
    }

    /** The next try while only a VPN is up: about 10 minutes (later in Doze, as any job). */
    const val RETRY_MS = 10 * 60_000L

    /** The job's extra: when a VPN alone stops bringing a fresh job ([retry]); 0 before the first. */
    const val UNTIL = "until" // l10n: not text (a job extra's key)

    /** review M1: a replace ([unlocked]) under a minute after the last one is skipped, so two events at once replace once. */
    const val GAP_MS = 60_000L

    /** One [run] at a time in this process (review-fix-later-job-fire-b L1). */
    internal val runLock = Any()

    /** The clock; a seam for the tests. */
    @Volatile internal var now: () -> Long = System::currentTimeMillis

    /** review M1: Android already runs the job, its onStartJob maybe not here yet ([LaterJobService.running] still 0); a seam for the tests. */
    @Volatile internal var executing: (JobScheduler) -> Boolean = { js ->
        try { js.getPendingJobReason(JOB_ID) == JobScheduler.PENDING_JOB_REASON_EXECUTING } catch (_: Throwable) { false }
    }

    /** When [unlocked] last replaced the job, and when [online] last did (review L2); 0 before the first. */
    @Volatile internal var lastReplace = 0L
    @Volatile internal var lastOnline = 0L

    /** At :voice's start (VoiceHub.init): [unlocked] after each unlock, [online] when a real network comes. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        try {
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_USER_PRESENT) Thread({ unlocked(app) }, "robot-later-unlock").start() // l10n: not text (a thread name)
                }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT))
        } catch (t: Throwable) { RLog.w("later_hooks", t) }
        try {
            // The builder's own NOT_VPN, NOT_RESTRICTED and TRUSTED stay: a VPN alone never calls this.
            val real = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build()
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(real, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Thread({ online(app) }, "robot-later-online").start() // l10n: not text (a thread name)
                }

                // review L1: a VPN set to block connections without it leaves validated Wi-Fi blocked until it is up.
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (!blocked) onAvailable(network)
                }
            })
        } catch (t: Throwable) { RLog.w("later_hooks", t) }
        try {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(DefaultNet(app))
        } catch (t: Throwable) { RLog.w("later_hooks", t) }
    }

    /**
     * review-fix-later-job-fire-b L2: with "block connections without VPN" on, Android keeps Wi-Fi and mobile data blocked
     * for apps whether the VPN is up or not, so the unblock above never comes; the VPN becoming the default network is the
     * event. [online] still turns a VPN alone away ([link] must be [Link.REAL]).
     */
    internal class DefaultNet(private val app: Context) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Thread({ SignalLater.online(app) }, "robot-later-default").start() // l10n: not text (a thread name)
        }
    }

    /**
     * rc2 row 2: a VPN alone already meets the job's network constraint, so Wi-Fi coming back is no event for the job, and
     * the retry's job sat ready 30 minutes without running. When a real network comes, a pending job runs now, as at an
     * unlock ([unlocked]: never while a run is on, cancelled with nothing waiting, its [UNTIL] kept).
     */
    @Synchronized fun online(ctx: Context) {
        try {
            // review L2: a network flapping between Wi-Fi and mobile data resets the backoff once in [RETRY_MS], not each flap.
            if (now() - lastOnline in 0L until RETRY_MS) return
            if (link(ctx) == Link.REAL && unlocked(ctx)) lastOnline = now()
        } catch (t: Throwable) {
            RLog.w("later_online", t)
        }
    }

    /**
     * review P1: a run while locked can't read the queue and takes the backoff (up to about an hour more), so after an
     * unlock a pending job runs now, its [UNTIL] kept; with no question left waiting it is cancelled. Never while a run is on.
     */
    @Synchronized fun unlocked(ctx: Context): Boolean {
        try {
            // review M1: the network callback, the unlock and a job bringing :voice up can all come at once; one replace.
            if (LaterJobService.running.get() > 0 || now() - lastReplace in 0L until GAP_MS) return false
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return false
            val pending = js.getPendingJob(JOB_ID) ?: run {
                // review-fix-later-job-fire-b L3: a keep racing a cancel or a run's end, or :voice dying after its write,
                // can leave a waiting question with no job; nothing else brings one back.
                if (vault(ctx).load()?.any { it.state == LaterState.WAITING } == true) schedule(ctx)
                return false
            }
            if (executing(js)) return false
            val items = vault(ctx).load() ?: return false
            if (items.none { it.state == LaterState.WAITING }) {
                js.cancel(JOB_ID)
                // review-fix-later-ready-offline-c: a question kept between the read and the cancel found the job pending.
                if (vault(ctx).load()?.any { it.state == LaterState.WAITING } == true) schedule(ctx)
                return false
            }
            js.schedule(job(ctx).setExtras(pending.extras).build())
            lastReplace = now()
            return true
        } catch (t: Throwable) {
            RLog.w("later_unlock", t)
            return false
        }
    }

    private fun job(ctx: Context) = JobInfo.Builder(JOB_ID, ComponentName(ctx, LaterJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)
        .setBackoffCriteria(10 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
        .setEstimatedNetworkBytes(512L * 1024, 1024)

    /**
     * The job (LaterRun.run): true to try again later (no validated network, [isStopped], or the phone locked so the file
     * can't be read). Held for want of a real network: the voice log says why, once a run; a VPN alone, [heldVpn]. [until]:
     * the job's [UNTIL].
     */
    fun run(ctx: Context, network: Network?, until: Long = 0L, isStopped: () -> Boolean): Boolean = synchronized(runLock) {
        // review-fix-later-job-fire-b L1: a run Android starts while another is on (an unlock or a real network replaced
        // the job under it) reads the queue only once that one has saved, so one question never takes two searches.
        val v by lazy { vault(ctx) }
        val settings by lazy { SettingsStore.load(ctx) }
        var held: Link? = null
        val again = LaterRun.run(object : LaterPort {
            override fun update(change: (List<LaterItem>) -> List<LaterItem>) = v.update(change)
            override fun online(): Boolean {
                val l = link(ctx, network)
                if (l != Link.REAL && held == null) RLog.i("later", "op" to RLog.Code("held"), "why" to RLog.Code(l.code)) // l10n: not text (a log code)
                if (l != Link.REAL) held = l
                return l == Link.REAL
            }
            override fun stopped() = isStopped()
            override fun gate(item: LaterItem) = SignalLater.gate(ctx, settings, item.said, item.query)
            override fun search(item: LaterItem) = answer(ctx, settings, item)
            override fun ask(item: LaterItem) {
                LaterNotices.ask(ctx, item)
                RLog.i("later", "op" to RLog.Code("ready")) // l10n: not text (a log code)
            }
            override fun answered(item: LaterItem, text: String) {
                LaterNotices.answer(ctx, item, text)
                RLog.i("later", "op" to RLog.Code("ran")) // l10n: not text (a log code)
            }
            override fun now() = System.currentTimeMillis()
        })
        if (again && held == Link.VPN_ONLY && !isStopped()) heldVpn(ctx, { v }, until) else again
    }

    /**
     * review N1: a VPN alone with a question still waiting (or the queue unreadable, locked) brings a fresh job in
     * [RETRY_MS] until [until] (set by the first to now + [Later.WAIT_MS]; with the queue readable, the newest waiting question's
     * askedAt + [Later.WAIT_MS], review-fix-later-ready-offline-c, when every question has expired), then the
     * backoff as before; with nothing waiting the job ends. [run]'s result.
     */
    private fun heldVpn(ctx: Context, v: () -> LaterVault, until: Long): Boolean {
        val now = System.currentTimeMillis()
        val items = try { v().update { Later.expire(it, now) } } catch (t: Throwable) { RLog.w("later_read", t); null }
        if (items != null && items.none { it.state == LaterState.WAITING }) return false
        val end = items?.filter { it.state == LaterState.WAITING }?.maxOfOrNull { it.askedAt + Later.WAIT_MS } ?: if (until > 0) until else now + Later.WAIT_MS
        return !(now < end && retry(ctx, end))
    }

    /** The search's own words for [item]: its top result's title and text, and the next two titles; or why it failed. */
    private fun answer(ctx: Context, settings: RobotSettings, item: LaterItem): String {
        val l = Langs.of(item.lang)
        val tool = WebTool(ctx, settings, Abort(WebSearch.DEADLINE_MS), item.said, { QueryContext(listOf(item.said)) }, { _, _ -> })
        val ready = tool.prepare(Action.WebSearch(item.query))
        if (ready !is Prepared.Ready) return (ready as Prepared.Refused).let { it.text?.render(l) ?: it.detail }
        val done = tool.run(ready.action as Action.WebSearch)
        return when (val o = done.web) {
            is WebOutcome.Found -> found(o, l)
            is WebOutcome.Failed -> o.sentence.render(l)
            null -> done.text?.render(l) ?: done.detail
        }
    }

    internal fun found(o: WebOutcome.Found, l: Langs): String {
        val top = o.results.firstOrNull() ?: return EverydayStrings.laterNothingFound().render(l)
        val body = o.passages.firstOrNull { it.source == 0 }?.text ?: top.snippet
        val more = o.results.drop(1).take(2).joinToString("\n") { "• " + it.title } // l10n: not text (a list mark)
        return listOf(top.title, body.take(MAX_BODY).trim(), more).filter { it.isNotBlank() }.joinToString("\n")
    }

    private const val MAX_BODY = 500
}
