// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.app.Application
import android.content.Context
import android.net.Network
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.termux.TermuxUpdateState
import app.heyrobot.core.termux.TermuxUpdates
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.sys.Paths
import app.heyrobot.sys.TermuxFacts
import app.heyrobot.sys.TermuxStore
import app.heyrobot.sys.TermuxUpdateStore
import java.util.concurrent.atomic.AtomicBoolean

// MAIN PROCESS (1.0). Settings › Termux › "Check for Termux updates": once a week (UpdateJobs.TERMUX, any network) and
// on Check now, while the switch is on and a Termux is trusted. Two GETs through GitHubClient.publicJson, to core
// TermuxUpdates' two fixed addresses (Termux's releases on api.github.com, without the updater's token; F-Droid's entry on
// f-droid.org), each asked even when the other fails; what they said goes to termux_updates.json and Settings compares it
// with the installed Termux. Nothing about you is sent, and Robot never downloads or installs another app: there is no
// download or install here, only reading two small answers. The voice log (the main app's) gets codes only.

object TermuxUpdateCheck {
    private val running = AtomicBoolean(false)

    /** The check may run: the switch is on and a Termux is trusted. */
    fun on(ctx: Context): Boolean = TermuxStore.load(ctx).checksUpdates

    /** The weekly job scheduled while [on], cancelled otherwise (Settings › Termux's changes, the app opening). Background thread. */
    fun ensure(ctx: Context) = UpdateJobs.ensureTermux(ctx, on(ctx))

    /** The weekly job: skipped when the last check is less than six days old. */
    fun autoCheck(ctx: Context, a: Abort, network: Network?) {
        if (!on(ctx)) return
        if (System.currentTimeMillis() - TermuxUpdateStore.load(ctx).checkedAt < TermuxUpdates.MIN_GAP_MS) return
        check(ctx, a, network)
    }

    /** "Check now" (on its own thread; the page reads termux_updates.json again when [running] ends). */
    fun checkNow(ctx: Context, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        Thread({
            try { if (on(app)) check(app, Abort(), null) } finally { done() }
        }, "robot-termux-updates").start()
    }

    val busy: Boolean get() = running.get()

    /** Both answers asked and kept (one at a time). Blocking. */
    fun check(ctx: Context, a: Abort, network: Network?): TermuxUpdateState? {
        if (!running.compareAndSet(false, true)) return null
        try {
            var ghFail: UpdateFail? = null
            var fdFail: UpdateFail? = null
            val gh = try {
                TermuxUpdates.parseGitHub(GitHubClient.publicJson(ctx, TermuxUpdates.GITHUB_URL, app.heyrobot.core.update.GitHubHosts.ACCEPT_JSON,
                    TermuxUpdates.MAX_GITHUB, a, network)) ?: run { ghFail = UpdateFail.BAD_ANSWER; null }
            } catch (f: GitHubClient.Failure) { ghFail = f.why; null }
            val fd = try {
                TermuxUpdates.parseFdroid(GitHubClient.publicJson(ctx, TermuxUpdates.FDROID_URL, TermuxUpdates.FDROID_ACCEPT, TermuxUpdates.MAX_FDROID, a, network))
                    ?: run { fdFail = UpdateFail.BAD_ANSWER; null }
            } catch (f: GitHubClient.Failure) { fdFail = f.why; null }
            if (a.cancelled) return null
            val old = TermuxUpdateStore.load(ctx)
            // A failed answer keeps what the last check found from it.
            val st = TermuxUpdateState(System.currentTimeMillis(), gh ?: old.github, fd ?: old.fdroid, ghFail, fdFail)
            TermuxUpdateStore.save(ctx, st)
            val s = TermuxStore.load(ctx)
            val pkg = TermuxFacts.pkg(ctx)
            val check = TermuxFacts.check(ctx, s, pkg)
            val verdict = if (gh == null && fd == null) app.heyrobot.core.termux.TermuxUpdateVerdict.FAILED else TermuxUpdates.news(pkg, check.source, st).verdict
            log(ctx, VoiceEvent.TERMUX_UPDATE_CHECK, verdict.id, (check.source?.ordinal ?: -1) + 1)
            RLog.i("termux_updates", "verdict" to verdict, "github" to (ghFail ?: RLog.Code("ok")), "fdroid" to (fdFail ?: RLog.Code("ok")))
            return st
        } catch (t: Throwable) {
            RLog.w("termux_updates", t)
            return null
        } finally {
            running.set(false)
        }
    }

    /** The main app's own small voice log (as Send feedback and the recognizer repair write it). */
    fun log(ctx: Context, e: VoiceEvent, a: Int, b: Int = 0) {
        if (!Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
        VoiceLog.add(e, a, b)
    }
}
