// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.Context
import app.heyrobot.assistant.HelperLink
import app.heyrobot.assistant.OnlineRun
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.Executed
import app.heyrobot.core.HandOffStepAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.Built
import app.heyrobot.core.connect.CallEnd
import app.heyrobot.core.connect.ConnAway
import app.heyrobot.core.connect.ConnConsent
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnLimits
import app.heyrobot.core.connect.ConnLinks
import app.heyrobot.core.connect.ConnPrepared
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.RoutingPreset
import app.heyrobot.core.connect.TravelStep
import app.heyrobot.core.output.Places
import app.heyrobot.core.skills.StepKind
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.core.connect.ConnLog
import app.heyrobot.core.connect.ConnOps
import app.heyrobot.core.connect.ConnRan
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnStep
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HaCheck
import app.heyrobot.core.connect.HaPreset
import app.heyrobot.core.connect.OpSpec
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.connect.SkillLimitCode
import app.heyrobot.core.connect.StepContext
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.helpers.connect.ConnCall
import app.heyrobot.helpers.connect.ConnHttp
import app.heyrobot.helpers.connect.McpClient
import app.heyrobot.core.connect.McpLog
import app.heyrobot.core.connect.McpOps
import app.heyrobot.core.connect.McpStep
import app.heyrobot.core.connect.McpStore
import app.heyrobot.core.connect.McpWire
import app.heyrobot.sys.Paths
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.SkillFiles

// VOICE PROCESS. A skill's connection step in the panel (docs/plans/skills-connections-plan.md §5.4, §5.5; C4): the
// panel's TurnActions (PanelController's) prepare and run a ConnStepAction here, inside TurnRunner, after the message
// quarantine and the gate and before the card (prepare), then after the card and the lock check (run). Every rule is core
// ConnStep's; this reads what it needs (connections.json, whether the key is saved and its masks, the limits, the Network
// toggle, the network's kind when Android may say) and sends.
//
// A call, in order: the Network toggle (D22) before anything else; the connection as it is now, still the one its card
// showed (a changed address was never shown, so never sent to); off your network or offline (CN4); the first-use answer it
// carries (D13: none means nothing is sent), recorded just before it goes; its values and request built again, and the
// same as its card showed (C12's L3: the request, and what it does; otherwise it asks again); the key,
// read here just before the call and only here (ConnKeys: each way it can't be read says so); the minute's and the day's
// limits again (its card may have waited) and the call counted as it goes (D16); then ConnHttp's call, which Stop cancels at
// once (OnlineRun: the turn's cancellation aborts it). A request that may have reached the server is never sent again by
// itself. The voice log keeps the kind, how it ended and buckets of its time and size: never a host, a value or a reply.
// C5: after a Home Assistant service call, an entity its answer didn't list is read back once after 2 s (HaCheck), with
// the same key and within the step's time, and core judges the step by the domain table.
// C6: travel time (core TravelStep). Its routing server is the one the step names, or your only one (ConnLinks). Where the
// phone is, is read only after the card, just before the call, the way the weather reads it (CoarseLocation: the
// permission, Android's background rule, a recent position or a new one), waiting at most TravelStep.FIX_MS so the
// call's 10 s still fit the step's 18 s; it is rounded to about 1 km in core before it goes, and never kept, logged or
// shown. A position that couldn't be had says why, and nothing is sent.
// C7: a hand-off to an app you picked is HandOffRunner's: no network, no key; it opens the app through the panel's launch.

object ConnRunner {
    /** A Home Assistant service's read-back at most: the 2 s wait and its reads (C5, EST). */
    const val READ_BACK_PHASE_MS = HaCheck.READ_BACK_WAIT_MS + 4_000L

    /** The whole step at most: the call's 10 s, a read-back, and a little for its files (EST). */
    const val DEADLINE_MS = ConnHttp.CALL_MS + READ_BACK_PHASE_MS + 2_000L

    /** An MCP tool's step at most: McpClient's 30 s, a legacy session's DELETE, and a little for its files. */
    const val MCP_DEADLINE_MS = McpWire.CALL_MS + McpClient.END_MS + 2_000L

    /** Tests only: the client's call, and the network's kind (the app always uses ConnHttp and ConnNetwork). */
    @Volatile internal var send: ConnSend? = null
    @Volatile internal var net: NetSource = ConnNetwork
    /** Tests only: where the phone is (the app always asks CoarseLocation). */
    @Volatile internal var locate: (Context, Long, Abort) -> CoarseLocation.Found = { ctx, ms, abort -> CoarseLocation.find(ctx, ms, abort) }

    /** [a] (a connection's or a hand-off's step) prepared for the gate and its card, or refused in Robot's words. Background thread. */
    fun prepare(ctx: Context, a: Action): Prepared = when (a) {
        is ConnStepAction -> ConnStep.prepare(a, context(ctx, a.call.conn, travel = a.call.kind == StepKind.TRAVEL_TIME))
        // C7: a hand-off to an app you picked (HandOffRunner).
        is HandOffStepAction -> HandOffRunner.prepare(ctx, a)
        else -> Prepared.Refused(ActionStatus.FAILED, ConnFailure.GONE.text(""))
    }

    /** What core needs to prepare a step of connection [id] (travel time: [travel], the routing server it names or your only one). */
    private fun context(ctx: Context, id: String?, travel: Boolean = false): StepContext {
        val c = if (travel) routing(ctx, id) else id?.let { ConnFiles.store(ctx).get(it) }
        val saved = c != null && (c.secrets.isEmpty() || ConnKeyFiles.saved(ctx, c.id))
        val masks = c?.let { ConnKeyFiles.masks(ctx, it.id) }.orEmpty()
        return StepContext(c, HelperLink.networkAllowed(ctx), net.now(ctx), saved, masks, SkillFiles.store(ctx).state().limits, System.currentTimeMillis(),
            places = if (travel) HandsFreeFiles.store(ctx).places() else Places(), locationAllowed = travel && CoarseLocation.granted(ctx))
    }

    /** The routing server a travel time step uses: the one it names, or your only one. */
    private fun routing(ctx: Context, id: String?): Connection? = ConnLinks(ConnFiles.store(ctx).connections()).routingFor(id)

    /**
     * Runs [a] after its card: Stop cancels the call at once and is rethrown; past the deadline it failed in time. A
     * hand-off (C7) opens its app through the panel's [launch], from its visible window.
     */
    suspend fun run(ctx: Context, a: Action, launch: (android.content.Intent) -> Boolean = { false }): Executed {
        if (a is HandOffStepAction) return HandOffRunner.run(ctx, a, launch)
        val step = a as? ConnStepAction ?: return Executed(ActionStatus.FAILED, ConnFailure.NOT_YET.text(""))
        val p = step.prepared ?: return Executed(ActionStatus.FAILED, ConnFailure.GONE.text(""))
        val ms = if (mcp(ctx, step)) MCP_DEADLINE_MS else DEADLINE_MS
        return OnlineRun.run(Abort(ms), ms) { abort -> call(ctx, step, abort) }
            // The deadline passed: whatever was sent may have arrived.
            ?: Executed(ActionStatus.FAILED, failedText(ConnFailure.TIMEOUT, p.name, !p.read)).copy(conn = ConnRan(sent = true))
    }

    private fun mcp(ctx: Context, a: ConnStepAction): Boolean = a.call.kind == StepKind.CONN &&
        a.call.conn?.let { ConnFiles.store(ctx).get(it) }?.let { c -> a.call.op?.let(c::op) }?.spec is OpSpec.McpCall

    /** An MCP tool's call (S3.6): what its card showed, its pin, the key, the limits, then McpClient; never sent twice. */
    private fun mcp(ctx: Context, a: ConnStepAction, p: app.heyrobot.core.connect.ConnPrepared, c: app.heyrobot.core.connect.Connection,
        op: app.heyrobot.core.connect.ConnOp, spec: OpSpec.McpCall, values: Map<String, app.heyrobot.core.skills.SkillValue>,
        now: app.heyrobot.core.connect.NetNow?, abort: Abort): Executed {
        if (!McpStep.asShown(p, c, op, spec, values)) return no(ConnFailure.CHANGED, c.name)
        val pin = McpStore(Paths.dir(ctx)).read(c.id)?.pins?.firstOrNull { it.name == spec.tool } ?: return no(ConnFailure.GONE, c.name)
        val secrets = if (c.secrets.isEmpty()) ConnSecrets() else when (val k = ConnKeys.read(ctx, c.id)) {
            is ConnKeyRead.Present -> k.secrets
            ConnKeyRead.Absent -> return no(ConnFailure.NO_KEY, c.name)
            is ConnKeyRead.Unavailable -> return no(ConnFailure.KEY_UNAVAILABLE, c.name)
            is ConnKeyRead.Invalidated -> return no(ConnFailure.KEY_UNREADABLE, c.name)
            is ConnKeyRead.TooNew -> return no(ConnFailure.KEY_NEWER, c.name)
        }
        limited(ctx, c)?.let { return no(it, c.name, c) }
        a.consent?.let { ConnFiles.store(ctx).answer(c.id, p.origin, it) }
        val end = McpClient().call(c, pin, McpOps.arguments(op, values), secrets, abort, app.heyrobot.BuildConfig.VERSION_NAME, reads = spec.reads)
        VoiceLog.add(VoiceEvent.MCP_CALL, McpLog.era(end.era), McpLog.result(end.out), McpLog.CALL)
        return McpStep.result(a, c, op, end, now)
    }

    /** One step's call (OnlineRun's thread; blocks). */
    private fun call(ctx: Context, a: ConnStepAction, abort: Abort): Executed {
        val p = a.prepared ?: return no(ConnFailure.GONE, "")
        // D22, before anything is read or sent.
        if (!HelperLink.networkAllowed(ctx)) return no(ConnFailure.NETWORK_OFF, p.name)
        if (a.call.kind == StepKind.TRAVEL_TIME) return travel(ctx, a, p, abort)
        val c = a.call.conn?.let { ConnFiles.store(ctx).get(it) } ?: return no(ConnFailure.GONE, p.name)
        // Still the address its card showed, and tested since.
        if (c.tested == null || ConnConsent.origin(c) != p.origin) return no(ConnFailure.NOT_TESTED, c.name)
        val op = a.call.op?.let(c::op) ?: return no(ConnFailure.GONE, c.name)
        val now = net.now(ctx)
        ConnAway.before(c, now)?.let { return no(it, c.name) }
        // D13: a host without "Allow for this connection" gets nothing without the card's answer.
        if (ConnConsent.asks(c) && a.consent == null) return Executed(ActionStatus.FAILED, ConnStrings.firstUnanswered("name" to c.name))
        val values = when (val v = ConnStep.values(op, a.call.args)) {
            is ConnStep.Values.Ok -> v.values
            is ConnStep.Values.Refused -> return no(v.why, c.name)
        }
        (op.spec as? OpSpec.McpCall)?.let { return mcp(ctx, a, p, c, op, it, values, now, abort) }
        val built = when (val b = ConnOps.build(c, op.id, values)) {
            is Built.Ok -> b.request
            is Built.Refused -> return no(b.why, c.name)
        }
        // C12's L3: what its card showed, or nothing: an operation changed since (its service, its entity, what it does) asks again.
        if (!ConnStep.asShown(p, c, op, values, built)) return no(ConnFailure.CHANGED, c.name)
        val request = ConnStep.wantingJson(built, a.call.check != null)
        val secrets = if (c.secrets.isEmpty()) ConnSecrets() else when (val k = ConnKeys.read(ctx, c.id)) {
            is ConnKeyRead.Present -> k.secrets
            ConnKeyRead.Absent -> return no(ConnFailure.NO_KEY, c.name)
            is ConnKeyRead.Unavailable -> return no(ConnFailure.KEY_UNAVAILABLE, c.name)
            is ConnKeyRead.Invalidated -> return no(ConnFailure.KEY_UNREADABLE, c.name)
            is ConnKeyRead.TooNew -> return no(ConnFailure.KEY_NEWER, c.name)
        }
        limited(ctx, c)?.let { return no(it, c.name, c) }
        // The first-use answer, kept as it goes (Settings shows "Just this time" too; only Allow spares the card next time).
        a.consent?.let { ConnFiles.store(ctx).answer(c.id, p.origin, it) }
        val http: ConnSend = send ?: { cc, r, s, ab -> ConnHttp().call(cc, r, s, ab) }
        val r = http(c, request, secrets, abort)
        val end = when (r) {
            is ConnCall.Answered -> CallEnd.Answered(r.read, r.bytes)
            is ConnCall.Failed -> CallEnd.Failed(r.why, r.sent)
            is ConnCall.Stopped -> CallEnd.Stopped(r.sent)
        }
        VoiceLog.add(VoiceEvent.CONN_CALL, ConnLog.kind(c.kind), ConnLog.result(end), ConnLog.timeAndSize(r.ms, (r as? ConnCall.Answered)?.bytes ?: 0))
        // C5: Home Assistant's own check reads back what its answer didn't list.
        val spec = op.spec as? OpSpec.HaCall
        val json = ((end as? CallEnd.Answered)?.read as? ReplyRead.Ok)?.json
        val back = if (spec != null && json != null) readBack(c, HaCheck.toReadBack(spec, json, values), secrets, abort, http) else emptyMap()
        return ConnStep.result(a, c, op, end, now, back)
    }

    /**
     * Each of [entities] read once, after [HaCheck.READ_BACK_WAIT_MS], within what's left of the step's time less a
     * margin ([READ_BACK_PHASE_MS] at most): its state, or null when it didn't come back. Stop ends the wait and the reads
     * at once. These reads change nothing; they count under the voice log's CONN_CALL like any call, not under the limits
     * (they belong to the step that was counted).
     */
    private fun readBack(c: Connection, entities: List<String>, secrets: ConnSecrets, abort: Abort, http: ConnSend): Map<String, String?> {
        if (entities.isEmpty()) return emptyMap()
        val budget = minOf(READ_BACK_PHASE_MS, abort.remainingMs() - MARGIN_MS)
        if (budget <= HaCheck.READ_BACK_WAIT_MS) return emptyMap()
        val phase = Abort(budget)
        val forget = abort.onCancel { phase.cancel() }
        try {
            val woke = java.util.concurrent.CountDownLatch(1)
            val unhook = phase.onCancel { woke.countDown() }
            try {
                woke.await(HaCheck.READ_BACK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return emptyMap()
            } finally {
                unhook()
            }
            val out = LinkedHashMap<String, String?>()
            for (e in entities) {
                if (phase.cancelled || phase.expired) { out[e] = null; continue }
                val req = HaCheck.readBack(c, e)
                val r = req?.let { http(c, it, secrets, phase) }
                if (r != null) VoiceLog.add(VoiceEvent.CONN_CALL, ConnLog.kind(c.kind), ConnLog.result(when (r) {
                    is ConnCall.Answered -> CallEnd.Answered(r.read, r.bytes)
                    is ConnCall.Failed -> CallEnd.Failed(r.why, r.sent)
                    is ConnCall.Stopped -> CallEnd.Stopped(r.sent)
                }), ConnLog.timeAndSize(r.ms, (r as? ConnCall.Answered)?.bytes ?: 0))
                out[e] = ((r as? ConnCall.Answered)?.read as? ReplyRead.Ok)?.json?.let(HaPreset::state)
            }
            return out
        } finally {
            forget()
        }
    }

    /** The minute's and the day's limits (D16), checked again and the call counted, in one write; why not, or null. */
    private fun limited(ctx: Context, c: Connection): ConnFailure? {
        var why: ConnFailure? = null
        val now = System.currentTimeMillis()
        val kept = SkillFiles.store(ctx).editState { s ->
            why = ConnLimits.connection(s.limits, c, now)
            if (why != null) s else s.copy(limits = ConnLimits.recordCall(s.limits, c.id, now))
        }
        // A state file that can't be written still lets the limit decide from what it read.
        val reached = why ?: if (kept == null) ConnLimits.connection(SkillFiles.store(ctx).state().limits, c, now) else null
        reached?.let { VoiceLog.add(VoiceEvent.SKILL_LIMIT, (if (it == ConnFailure.LIMIT_DAY) SkillLimitCode.CONN_DAY else SkillLimitCode.CONN_MINUTE).id,
            if (it == ConnFailure.LIMIT_DAY) ConnLimits.PER_DAY else c.perMinute) }
        return reached
    }

    /** A travel time step's call (OnlineRun's thread; blocks): see the file's header. */
    private fun travel(ctx: Context, a: ConnStepAction, p: ConnPrepared, abort: Abort): Executed {
        val c = routing(ctx, a.call.conn) ?: return no(ConnFailure.GONE, p.name)
        // Still the address its card showed, and tested since.
        if (c.tested == null || ConnConsent.origin(c) != p.origin) return no(ConnFailure.NOT_TESTED, c.name)
        ConnRules.refusal(c)?.let { return no(it, c.name) }
        val now = net.now(ctx)
        ConnAway.before(c, now)?.let { return no(it, c.name) }
        if (ConnConsent.asks(c) && a.consent == null) return Executed(ActionStatus.FAILED, ConnStrings.firstUnanswered("name" to c.name))
        val to = TravelStep.to(a) ?: return no(ConnFailure.BAD_VALUE, c.name)
        val places = HandsFreeFiles.store(ctx).places()
        val (place, missing) = RoutingPreset.place(to, places.home, places.work)
        if (place == null) return no(missing ?: ConnFailure.BAD_VALUE, c.name)
        val secrets = if (c.secrets.isEmpty()) ConnSecrets() else when (val k = ConnKeys.read(ctx, c.id)) {
            is ConnKeyRead.Present -> k.secrets
            ConnKeyRead.Absent -> return no(ConnFailure.NO_KEY, c.name)
            is ConnKeyRead.Unavailable -> return no(ConnFailure.KEY_UNAVAILABLE, c.name)
            is ConnKeyRead.Invalidated -> return no(ConnFailure.KEY_UNREADABLE, c.name)
            is ConnKeyRead.TooNew -> return no(ConnFailure.KEY_NEWER, c.name)
        }
        // Where the phone is, now, leaving the call its 10 s.
        val wait = minOf(TravelStep.FIX_MS, abort.remainingMs() - ConnHttp.CALL_MS - MARGIN_MS)
        if (wait <= 0) return no(ConnFailure.NO_LOCATION, c.name)
        val found = locate(ctx, wait, abort)
        if (abort.cancelled && !abort.expired) return Executed(ActionStatus.DECLINED, a.stoppedText ?: app.heyrobot.core.i18n.TurnStrings.stopped())
        val fix = found.fix ?: return no(TravelStep.noFix(found.code), c.name).also {
            VoiceLog.add(VoiceEvent.CONN_CALL, ConnLog.kind(c.kind), ConnLog.result(CallEnd.Failed(TravelStep.noFix(found.code), false)), ConnLog.timeAndSize(found.ms.toLong(), 0))
        }
        val request = when (val b = RoutingPreset.request(c, fix.lat, fix.lon, place)) {
            is Built.Ok -> b.request
            is Built.Refused -> return no(b.why, c.name)
        }
        limited(ctx, c)?.let { return no(it, c.name, c) }
        a.consent?.let { ConnFiles.store(ctx).answer(c.id, p.origin, it) }
        val http: ConnSend = send ?: { cc, r, s, ab -> ConnHttp().call(cc, r, s, ab) }
        val r = http(c, request, secrets, abort)
        val end = when (r) {
            is ConnCall.Answered -> CallEnd.Answered(r.read, r.bytes)
            is ConnCall.Failed -> CallEnd.Failed(r.why, r.sent)
            is ConnCall.Stopped -> CallEnd.Stopped(r.sent)
        }
        VoiceLog.add(VoiceEvent.CONN_CALL, ConnLog.kind(c.kind), ConnLog.result(end), ConnLog.timeAndSize(r.ms, (r as? ConnCall.Answered)?.bytes ?: 0))
        return TravelStep.result(a, c, end, now)
    }

    /** What the read-back leaves of the step's time, for its files and the answer (EST). */
    private const val MARGIN_MS = 1_500L

    private fun no(why: ConnFailure, name: String, c: Connection? = null): Executed =
        Executed(ActionStatus.FAILED, why.text(name, limit = if (why == ConnFailure.LIMIT_MINUTE) c?.perMinute else null))

    private fun failedText(why: ConnFailure, name: String, mayHaveSent: Boolean): Phrase =
        if (mayHaveSent) Phrase.Join(listOf(why.text(name), ConnStrings.mayHaveSent("name" to name))) else why.text(name)
}
