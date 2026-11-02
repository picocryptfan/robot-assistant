// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.HaCall
import app.heyrobot.core.skills.LinkedHandOff
import app.heyrobot.core.skills.LinkedOp
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillLinks
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.SlotType
import app.heyrobot.core.skills.StepCheck

// Pure. A connection's operations as the rest of Robot uses them (C2): the request each makes (by its kind's preset),
// what it does (its effects, computed and never declared: D12; your marks only add), and what a skill's checks see of
// it (C1's SkillLinks: [ConnLinks]). C4's runner builds a step's request here, and C3's client sends it.

/** A webhook's operation (plan §3.3): the form you filled is the request. */
object WebhookPreset {
    fun op(op: ConnOp): HttpOp {
        val s = op.spec as? OpSpec.Webhook ?: throw IllegalArgumentException()
        return HttpOp(s.method, s.path, s.query, s.headers, s.body, op.params, read = s.method == HttpMethod.GET,
            json = op.outputs.isNotEmpty() || op.check != null)
    }

    /**
     * C6, Test (plan §6.3): nothing is sent to your webhook, which may change something, and no key goes anywhere. One
     * GET to the address's origin ("/"), with no key, from a copy of the connection without its secrets (so the client
     * builds no secret into it): it proves the address, the certificate and where the server is (D1). Any HTTP answer
     * counts, a redirect or an error page included ([reached]); the body is never read.
     */
    fun test(c: Connection): Pair<Connection, HttpRequest>? {
        if (c.kind != ConnKind.WEBHOOK) return null
        val base = c.address ?: return null
        val probe = c.copy(secretAddress = false, auth = ConnAuth.None, ops = emptyList())
        return probe to HttpRequest(HttpMethod.GET, base, false, "/", emptyList(), emptyList(), null, ConnAuth.None, read = true, json = false)
    }

    /** The server answered Test at all: a 2xx, or any other status it sent (a refused key or a missing page still says it's there). */
    fun reached(r: ReplyRead): Boolean = r is ReplyRead.Ok || (r as? ReplyRead.Failed)?.status != null

    /**
     * What an address that is itself the key adds to its operation's effects when you paste it (money, as a hint from a
     * word in its path: plan §3.3): connections.json never holds that path, so Settings saves this as a mark (C9).
     */
    fun addressMarks(address: String): Set<SkillEffect> {
        val u = ConnUrl.parse(address, whole = true) ?: return emptySet()
        return if (SkillEffects.moneyHint(u.path.substringBefore('?'))) setOf(SkillEffect.MONEY) else emptySet()
    }
}

object ConnOps {
    /** [op]'s request template on [c], by [c]'s kind; null for a kind that makes no HTTP operation (routing, a hand-off). */
    fun http(c: Connection, op: ConnOp): HttpOp? = when (c.kind) {
        ConnKind.HOME_ASSISTANT -> HaPreset.op(op)
        ConnKind.NTFY -> NtfyPreset.op(op)
        ConnKind.WEBHOOK -> WebhookPreset.op(op)
        ConnKind.ROUTING, ConnKind.HAND_OFF -> null
    }

    /**
     * The request operation [opId] of [c] makes with [values] (C4: a step's arguments, typed with their sources), or why
     * none: the operation is gone, a value doesn't fit, a rule refuses it (D1, D2), an ntfy message over 4,096 bytes.
     */
    fun build(c: Connection, opId: String, values: Map<String, SkillValue>): Built {
        val op = c.op(opId) ?: return Built.Refused(ConnFailure.GONE)
        val http = http(c, op) ?: return Built.Refused(ConnFailure.GONE)
        val b = http.build(c, values)
        val s = op.spec
        if (b is Built.Ok && s is OpSpec.NtfyPublish && !NtfyPreset.messageFits(b.request, s)) return Built.Refused(ConnFailure.BAD_VALUE)
        return b
    }

    /** Test's requests for [c] (Home Assistant and ntfy; routing's is RoutingPreset.test, which needs your Home). */
    fun test(c: Connection): List<HttpRequest> = when (c.kind) {
        ConnKind.HOME_ASSISTANT -> HaPreset.test(c)
        ConnKind.NTFY -> listOf(NtfyPreset.test(c))
        else -> emptyList()
    }

    /** The declared outputs of [op] in its reply's [json]. */
    fun outputs(op: ConnOp, json: Any?): Map<String, SkillValue> = ConnReply.outputs(json, op.outputs)

    /** [op]'s own check and the step's [step] check both hold on [json] (a missing one holds). */
    fun checked(op: ConnOp, json: Any?, step: StepCheck.Equals? = null): Boolean =
        listOfNotNull(op.check, step).all { ConnReply.check(json, it) }

    /**
     * What [op] does whatever its values (LinkedOp.effects): a read reads online; ntfy tells people (or writes online,
     * for a topic only your devices follow); a webhook by its method, with money as a hint from a word of its address's
     * and its own path. A Home Assistant service call's effects come from its domain and entities (SkillEffects
     * .homeAssistant, through [haCall]), so they aren't here. Your marks are separate (LinkedOp.marks).
     */
    fun effects(c: Connection, op: ConnOp): Set<SkillEffect> = when (val s = op.spec) {
        OpSpec.HaRead -> setOf(SkillEffect.READS_ONLINE)
        is OpSpec.HaCall, OpSpec.HaScript -> emptySet()
        is OpSpec.NtfyPublish -> SkillEffects.ntfy(s.onlyMine)
        is OpSpec.Webhook -> SkillEffects.webhook(s.method.id, c.address?.path.orEmpty() + s.path)
    }

    /** The Home Assistant service [op] calls, for SkillEffects (a script runs through `script.turn_on`). */
    fun haCall(op: ConnOp): HaCall? = when (val s = op.spec) {
        is OpSpec.HaCall -> HaCall(s.domain, s.service, ConnOp.ENTITY, s.classes)
        OpSpec.HaScript -> HaCall(SCRIPT, TURN_ON, ConnOp.ENTITY)
        else -> null
    }

    /** It only reads: "go on" may follow it when it fails (plan §5.1). */
    fun reads(op: ConnOp): Boolean = op.spec == OpSpec.HaRead || (op.spec as? OpSpec.Webhook)?.method == HttpMethod.GET

    /** [op] of [c] as a skill's checks see it (C1's SkillLinks). */
    fun linked(c: Connection, op: ConnOp): LinkedOp = LinkedOp(
        params = op.params.mapValues { (_, p) -> SlotType(p.type, p.options) },
        outputs = op.outputs.mapValues { (_, o) -> SlotType(o.type, o.options) },
        read = reads(op),
        effects = effects(c, op),
        ha = haCall(op),
        marks = op.marks,
        required = op.params.filterValues { it.default == null }.keys,
        kind = c.kind.id,
        title = op.title,
        name = c.name,
        host = c.address?.host,
    )

    private const val SCRIPT = "script" // l10n: not text (Home Assistant's domain)
    private const val TURN_ON = "turn_on" // l10n: not text (Home Assistant's service)
}

/**
 * Your connections as a skill's checks, effects and risk see them (C1's seam, plan §5.2): a `conn:` step's operation, and
 * whether `travel_time` has a routing server (the one it names, or your only one), and a hand-off's slots (C7). Typed
 * scripts come from [others] (C8: termux.ScriptLinks, from the last listing run).
 */
class ConnLinks(private val connections: List<Connection>, private val others: SkillLinks = SkillLinks.NONE) : SkillLinks {
    override fun op(conn: String, op: String): LinkedOp? {
        val c = connections.firstOrNull { it.id == conn && it.kind in HTTP } ?: return null
        return c.op(op)?.let { ConnOps.linked(c, it) }
    }

    /** C7: a hand-off you picked, its slots and effects from what it opens. */
    override fun handOff(conn: String): LinkedHandOff? =
        connections.firstOrNull { it.id == conn && it.kind == ConnKind.HAND_OFF }?.handOff?.let(IntentSpecs::linked) ?: others.handOff(conn)

    override fun script(name: String) = others.script(name)

    override fun routing(conn: String?): Boolean = routingFor(conn) != null

    /** The routing connection `travel_time` uses: [conn] when the step names one, else your only one. */
    fun routingFor(conn: String?): Connection? {
        val all = connections.filter { it.kind == ConnKind.ROUTING }
        return if (conn != null) all.firstOrNull { it.id == conn } else all.singleOrNull()
    }

    private companion object {
        val HTTP = setOf(ConnKind.HOME_ASSISTANT, ConnKind.NTFY, ConnKind.WEBHOOK)
    }
}
