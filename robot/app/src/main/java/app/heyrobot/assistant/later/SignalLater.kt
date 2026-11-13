// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.sys.LaterVault
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

    fun vault(ctx: Context): LaterVault = LaterVault.of(ctx)

    /** A validated connection now ([network], else the active one). */
    fun validated(ctx: Context, network: Network? = null): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = (network ?: cm.activeNetwork)?.let { cm.getNetworkCapabilities(it) }
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) {
        false
    }

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
        return if (schedule(ctx)) Kept.KEPT else Kept.FAILED
    }

    /** The one-shot job on any network, persisted; true when it is pending. */
    fun schedule(ctx: Context): Boolean = try {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.getPendingJob(JOB_ID) != null || js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, LaterJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(10 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .setEstimatedNetworkBytes(512L * 1024, 1024)
            .build()) == JobScheduler.RESULT_SUCCESS
    } catch (t: Throwable) {
        RLog.w("later_schedule", t)
        false
    }

    /** The job (LaterRun.run): true to try again later (no validated network, [isStopped], or the phone locked so the file can't be read). */
    fun run(ctx: Context, network: Network?, isStopped: () -> Boolean): Boolean {
        val v = vault(ctx)
        val settings by lazy { SettingsStore.load(ctx) }
        return LaterRun.run(object : LaterPort {
            override fun update(change: (List<LaterItem>) -> List<LaterItem>) = v.update(change)
            override fun online() = validated(ctx, network)
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
