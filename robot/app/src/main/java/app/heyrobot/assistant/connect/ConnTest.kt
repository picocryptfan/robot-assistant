// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.Context
import app.heyrobot.R
import app.heyrobot.assistant.HelperLink
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.Built
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnLog
import app.heyrobot.core.connect.ConnSetupCode
import app.heyrobot.core.connect.ConnOps
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HaPick
import app.heyrobot.core.connect.HaPickers
import app.heyrobot.core.connect.HaPreset
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.NtfyPreset
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.connect.RoutingPreset
import app.heyrobot.core.connect.WebhookPreset
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.helpers.connect.ConnCall
import app.heyrobot.core.online.NetCause
import app.heyrobot.helpers.connect.ConnHttp
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.ConnFiles

// VOICE PROCESS (the connection window). A connection's Test (docs/plans/skills-connections-plan.md §3, §6.3; C3): one
// request that changes nothing and reaches none of your devices, `GET /api/` for Home Assistant ("API running.") and
// ntfy's health check (the address, the certificate and, C6 found, the token), sent by ConnHttp with the key just read
// here. When it passes, connections.json keeps when, and the kind of address it found (D1: home or internet, for
// Settings' line and the "off your home network" words), never what the server said. Home Assistant's pickers (its
// states and services, read with ConnHttp.PICKER_MAX_BYTES) are C5's; webhooks and routing servers got their Test in C6.
// Logged as codes only: the kind, whether it passed, the failure's number.
//
// C5: once Home Assistant's `GET /api/` passes, `GET /api/states` and `GET /api/services` fill its pickers (HaPick), read
// with ConnHttp.PICKER_MAX_BYTES and kept only in this process's memory (HaPickerHold) while the window is open; Test's
// line says how many lights, locks and covers it found, never a name. Lists that can't be read leave Test passed (the
// server answered and the key works) and say so. `GET /api/config` is never read: it holds your home's coordinates.
//
// C6: the other kinds. ntfy's health check checks its token too (a server with users authenticates every request first,
// NtfyPreset.older), and a server too old for it answers 404, which passes as "it answers". A webhook gets one GET to its
// address's origin with no key and nothing to its own path (WebhookPreset.test): any HTTP answer passes, and Test never
// runs the webhook. A routing server is asked for a route between two points near your Home (RoutingPreset.test, Home from
// Settings › Car mode), never where you are now; without a Home saved, Test says to save one.

/** One call as Test sends it (ConnHttp's; a fake in tests). */
typealias ConnSend = (Connection, HttpRequest, ConnSecrets, Abort) -> ConnCall

/** What Test found: [ok], its words in the menus' language ([text]), the kind of address ([where]), and whether a key is what's missing. */
class ConnTestResult(val ok: Boolean, val text: String, val where: ConnWhere?, val needsKey: Boolean, val pickers: HaPickers? = null) {
    override fun toString(): String = "ConnTestResult($ok)" // l10n: not text (toString, for the logs)
}

object ConnTest {
    /** Whether Test has a request for [c]'s kind: every kind but a hand-off (C7 checks its app instead). */
    fun offered(c: Connection): Boolean = c.kind != ConnKind.HAND_OFF

    /** A routing server's test tells it about where your Home is: the window says so first and sends after your tap. */
    fun asksFirst(c: Connection): Boolean = c.kind == ConnKind.ROUTING

    /**
     * Tests [c] (background thread): the Network toggle first (D22), then its key, then its first test request. Null when
     * its kind has none ([offered]) or Stop ended it. [send]: tests only.
     */
    fun run(ctx: Context, c: Connection, abort: Abort, send: ConnSend? = null): ConnTestResult? {
        if (!offered(c)) return null
        // GrapheneOS's Network toggle is off for Robot: say so rather than "can't reach" (nothing could be sent).
        if (!HelperLink.networkAllowed(ctx)) return failed(ctx, c, ConnFailure.NETWORK_OFF)
        if (c.kind == ConnKind.WEBHOOK) return webhook(ctx, c, abort, send)
        val request = if (c.kind == ConnKind.ROUTING) {
            val home = HandsFreeFiles.store(ctx).places().home ?: return failed(ctx, c, ConnFailure.NO_HOME)
            when (val b = RoutingPreset.test(c, home)) {
                is Built.Ok -> b.request
                is Built.Refused -> return failed(ctx, c, b.why)
            }
        } else ConnOps.test(c).firstOrNull() ?: return null
        val secrets = if (c.secrets.isEmpty()) ConnSecrets() else when (val k = ConnKeys.read(ctx, c.id)) {
            is ConnKeyRead.Present -> k.secrets
            ConnKeyRead.Absent -> return failed(ctx, c, ConnFailure.NO_KEY, needsKey = true)
            // Kept: nothing is tested without it (a server that answers without a key would look fine).
            is ConnKeyRead.Unavailable -> return said(ctx, c, R.string.conn_window_key_unavailable)
            // Removed as it was read: paste it again.
            is ConnKeyRead.Invalidated -> return said(ctx, c, R.string.conn_window_key_unreadable, needsKey = true)
            is ConnKeyRead.TooNew -> return said(ctx, c, R.string.conn_window_key_newer)
        }
        val http: ConnSend = send ?: { cc, r, s, a -> ConnHttp().call(cc, r, s, a, cause = NetCause.TAP) }
        return when (val call = http(c, request, secrets, abort)) {
            is ConnCall.Stopped -> null
            is ConnCall.Failed -> failed(ctx, c, call.why)
            is ConnCall.Answered -> when (val read = call.read) {
                // An ntfy too old for the health check: it answered, after its token was accepted.
                is ReplyRead.Failed -> if (c.kind == ConnKind.NTFY && NtfyPreset.older(read)) passed(ctx, c, call.where, extra = ConnStrings.ntfyOld())
                    else failed(ctx, c, read.why, read.status, needsKey = read.why == ConnFailure.KEY_REFUSED)
                is ReplyRead.Ok -> if (!passes(c, read.json)) said(ctx, c, R.string.conn_window_unexpected)
                    else passed(ctx, c, call.where, if (c.kind == ConnKind.HOME_ASSISTANT) pickers(c, secrets, abort, send) else null,
                        extra = if (c.kind == ConnKind.ROUTING) ConnStrings.routingTestSends() else null)
            }
        }
    }

    /** The answer is the kind's own: Home Assistant's API running, ntfy healthy, a route from the routing server. */
    private fun passes(c: Connection, json: Any?): Boolean = when (c.kind) {
        ConnKind.HOME_ASSISTANT -> HaPreset.running(json)
        ConnKind.NTFY -> NtfyPreset.healthy(json)
        ConnKind.ROUTING -> c.routing?.let { RoutingPreset.read(it, json) } != null
        else -> false
    }

    /** A webhook's Test: one GET to its origin, with no key; any HTTP answer passes (WebhookPreset.test). */
    private fun webhook(ctx: Context, c: Connection, abort: Abort, send: ConnSend?): ConnTestResult? {
        val (probe, request) = WebhookPreset.test(c) ?: return failed(ctx, c, ConnFailure.NOWHERE)
        val http: ConnSend = send ?: { cc, r, s, a -> ConnHttp().call(cc, r, s, a, cause = NetCause.TAP) }
        return when (val call = http(probe, request, ConnSecrets(), abort)) {
            is ConnCall.Stopped -> null
            is ConnCall.Failed -> failed(ctx, c, call.why)
            is ConnCall.Answered -> if (WebhookPreset.reached(call.read)) passed(ctx, c, call.where, extra = ConnStrings.webhookTested())
                else said(ctx, c, R.string.conn_window_unexpected)
        }
    }

    /** Home Assistant's pickers (its states and services), or null when either list couldn't be read. Stop: null too. */
    private fun pickers(c: Connection, secrets: ConnSecrets, abort: Abort, send: ConnSend?): HaPickers? {
        val reqs = ConnOps.test(c).drop(1)
        if (reqs.size != 2) return null
        val read = reqs.map { r ->
            val call = send?.invoke(c, r, secrets, abort) ?: ConnHttp().call(c, r, secrets, abort, ConnHttp.PICKER_MAX_BYTES, NetCause.TAP)
            ((call as? ConnCall.Answered)?.read as? ReplyRead.Ok)?.json ?: run {
                if (call !is ConnCall.Stopped) VoiceLog.add(VoiceEvent.CONN_SETUP, ConnLog.kind(c.kind), ConnSetupCode.PICKERS.id,
                    (call.reply as? ReplyRead.Failed)?.why?.code ?: ConnSetupCode.UNEXPECTED)
                return null
            }
        }
        val entities = HaPick.states(read[0])
        val services = HaPick.services(read[1])
        VoiceLog.add(VoiceEvent.CONN_SETUP, ConnLog.kind(c.kind), ConnSetupCode.PICKERS.id, if (entities != null && services != null) 0 else ConnSetupCode.UNEXPECTED)
        return HaPickers(entities ?: return null, services ?: return null).also { HaPickerHold.keep(c.id, it) }
    }

    /** Passed: connections.json keeps when, and where it found the server (only while the address is the one tested). */
    private fun passed(ctx: Context, c: Connection, where: ConnWhere, pickers: HaPickers? = null, extra: Phrase? = null): ConnTestResult {
        val kept = ConnFiles.store(ctx).edit { list ->
            list.map { if (it.id == c.id && it.address == c.address) it.copy(where = where, tested = ConnTested(System.currentTimeMillis())) else it }
        } != null
        log(c, true, null)
        val langs = AppLanguage.langs(ctx)
        val connected = ctx.getString(if (where == ConnWhere.HOME) R.string.conn_window_connected_home else R.string.conn_window_connected_internet)
        val found = when {
            c.kind != ConnKind.HOME_ASSISTANT -> null
            pickers == null -> ConnStrings.haListsUnread().render(langs)
            else -> HaPick.summary(pickers)?.let { ConnStrings.haFound("list" to it).render(langs) }
        }
        val words = listOfNotNull(connected, found, extra?.render(langs)).joinToString(" ")
        return ConnTestResult(true, if (kept) words else words + " " + ConnStrings.couldntSave().render(langs), where, needsKey = false, pickers = pickers)
    }

    private fun failed(ctx: Context, c: Connection, why: ConnFailure, status: Int? = null, needsKey: Boolean = false): ConnTestResult {
        log(c, false, why.code)
        return ConnTestResult(false, why.text(c.name, status).render(AppLanguage.langs(ctx)), null, needsKey)
    }

    private fun said(ctx: Context, c: Connection, res: Int, needsKey: Boolean = false): ConnTestResult {
        log(c, false, null)
        return ConnTestResult(false, ctx.getString(res), null, needsKey)
    }

    private fun log(c: Connection, ok: Boolean, why: Int?) {
        RLog.i("conn_test", "kind" to RLog.Code(c.kind.id), "ok" to ok, "why" to why) // l10n: not text (a log event's name)
        VoiceLog.add(VoiceEvent.CONN_SETUP, ConnLog.kind(c.kind), ConnSetupCode.TEST.id, if (ok) 0 else why ?: ConnSetupCode.UNEXPECTED)
    }
}
