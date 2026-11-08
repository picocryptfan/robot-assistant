// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Choice
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.Executed
import app.heyrobot.core.LogCode
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource

// Pure. A skill's connection step, decided here so the panel (ConnRunner, in :voice) and the tests ask the same rules
// (docs/plans/skills-connections-plan.md §5.4, §5.5, D13 to D16, D22; C4).
//
// Before the card ([ConnStep.prepare]): the step kind is one this Robot runs; the connection and its operation exist and
// Test passed; D1 and D2 allow a client (ConnRules.refusal); the Network toggle is on (D22); not off your network for an
// http home address, and not offline (when Android may tell, [NetNow]); its key is saved; its minute's and day's limits
// (D16); every value fits its argument (a constant typed by the operation, a value from an earlier step that didn't come
// back refuses it, never guessed). Then what the card shows, exactly what will run: your operation's title and your
// connection's name (History keeps only those, D20), where it goes, every value with where it came from, what it does
// (its effects, your marks included), and the request as it will be sent, with "Authorization: your Home Assistant
// token" where the key goes. And its reasons:
// - CONNECTION_FIRST, the first call to this connection's host (D13): Allow for this connection or Just this time, a tap.
// - CONNECTION_TAP, a tap: it locks, unlocks or opens, deletes or can spend money; or a value Robot's model wrote (free
//   words) or Robot read (from an answer, or on the phone) goes into it. A value that isn't yours never runs without a tap.
// - CONNECTION, a card: Robot's model picked one of its values from your list.
// A spoken yes may answer its card only for a change at home, a read or an app opening, with the connection's switch on
// (D14); otherwise every card it shows is tap only (ConnStepAction.tapOnly).
//
// After the call ([ConnStep.result]): the reply's declared outputs, typed, from outside the phone; its checks (the
// operation's and the step's: a pointer's value); what Robot says, in fixed words, never a server's. A request that may
// have reached the server is never sent again by itself.

/** What a connection step's card shows once prepared, and what its run needs again. Never logged: toString hides it. */
data class ConnPrepared(
    /** "Turn off a light · your Home Assistant" (D20). */
    val summary: Phrase,
    /** Where it goes, every value and its source, what it does, the request. */
    val body: Phrase,
    val effects: Set<SkillEffect>,
    /** The address's origin it was prepared for (a first-use answer names it; a changed address is never sent to). */
    val origin: String,
    /** The connection's name, for the lines after it ran. */
    val name: String,
    /** It only reads. */
    val read: Boolean,
    /** A spoken yes may answer its card (D14). */
    val spokenYes: Boolean,
    /** C12's L3: the request its card showed (a connection's step; null for a hand-off or travel time). */
    val request: ShownRequest? = null,
) {
    override fun toString(): String = "ConnPrepared(…)" // l10n: not text (toString, for the logs)
}

/**
 * C12's L3: a request as a card showed it, everything but its secrets (an HttpRequest holds none): where it goes after the
 * address, how, its headers and body. A step's run sends only the request its card showed, built again from
 * connections.json as it is then. Never logged: toString hides it.
 */
data class ShownRequest(val method: HttpMethod, val tail: String, val headers: List<Pair<String, String>>, val body: List<BodyPart>?,
    val contentType: String?, val auth: ConnAuth, val secretAddress: Boolean) {
    override fun toString(): String = "ShownRequest(${method.id})" // l10n: not text (toString, for the logs)

    companion object {
        fun of(r: HttpRequest): ShownRequest = ShownRequest(r.method, r.tail, r.headers, r.body?.parts, r.body?.contentType, r.auth, r.secretAddress)
    }
}

/** How a step's checks went: none set, held, or didn't. */
enum class CheckOutcome { NONE, PASSED, FAILED }

/**
 * What a connection step brought back for the rest of its skill ([Executed.conn]): its declared [outputs], typed, from
 * outside the phone; [content]: text from outside entered the conversation (a read answered, or outputs kept), so every
 * later step asks as it would after a read (D5); [check]; [sent]: a request that didn't work may have reached the server.
 */
data class ConnRan(val outputs: Map<String, SkillValue> = emptyMap(), val content: Boolean = false, val check: CheckOutcome = CheckOutcome.NONE,
    val sent: Boolean = false,
    /** A failed check's words for the skill's line ("your Home Assistant's answer wasn't what the step checks for"). */
    val what: Phrase? = null,
    /** C6: where you are went to your routing server (travel time): the conversation holds Personal.LOCATION for helpers. */
    val located: Boolean = false) {
    override fun toString(): String = "ConnRan(${outputs.size}, $content, $check, $sent)" // l10n: not text (toString, for the logs)
}

/** What Android says of the network now (CN4), read only with ACCESS_NETWORK_STATE granted: none at all, your own network, or another. */
enum class NetNow {
    /** Plainly no usable network. Android's null network isn't that (it may be blocked), so the app's ConnNetwork answers unknown. */
    NONE,
    /** Wi-Fi, Ethernet or a VPN (Tailscale's): a home connection may be reached. */
    LOCAL,
    /** Mobile data or another network without a VPN: away from your network. */
    AWAY,
}

/** CN4: "Robot can't reach your <connection> off your home network." Unknown ([NetNow] null) decides nothing. */
object ConnAway {
    /** Before a call: offline; or an http home connection away from your network (it could only reach someone else's). */
    fun before(c: Connection, net: NetNow?): ConnFailure? = when {
        net == NetNow.NONE -> ConnFailure.OFFLINE
        net == NetNow.AWAY && c.where == ConnWhere.HOME && c.address?.https != true -> ConnFailure.AWAY
        else -> null
    }

    /** After a call that failed to connect: a home connection (https, split DNS allowed) away from your network says why. */
    fun after(c: Connection, net: NetNow?, why: ConnFailure): ConnFailure =
        if (net == NetNow.AWAY && c.where == ConnWhere.HOME && why in UNREACHED) ConnFailure.AWAY else why

    private val UNREACHED = setOf(ConnFailure.UNREACHABLE, ConnFailure.TIMEOUT, ConnFailure.NOWHERE, ConnFailure.MOVED)
}

/** What the panel knows when it prepares a step (ConnRunner reads it in :voice; the tests give their own). */
class StepContext(
    /** The connection the step names, as connections.json holds it now (null: gone). */
    val connection: Connection?,
    /** D22: Robot may use the network (GrapheneOS's Network toggle). */
    val networkOn: Boolean,
    /** CN4: the network now, or null when Android may not be asked. */
    val net: NetNow?,
    /** Its key file is saved (every secret part in it). */
    val keySaved: Boolean,
    /** Each secret part's masked form, for the request's preview. */
    val masks: Map<SecretPart, String>,
    /** D16's recent calls. */
    val limits: LimitState,
    val now: Long,
    /** C6, travel time: Home and Work as Settings › Car mode saved them. */
    val places: app.heyrobot.core.output.Places = app.heyrobot.core.output.Places(),
    /** C6, travel time: Robot may see where the phone is (the location permission); nothing is read to find out. */
    val locationAllowed: Boolean = false,
)

/** The end of one call as core reads it (ConnRunner maps the client's ConnCall to it). */
sealed class CallEnd {
    /** The server answered: ConnReply's reading. [bytes]: the body's bytes read. */
    class Answered(val read: ReplyRead, val bytes: Int = 0) : CallEnd() {
        override fun toString(): String = "Answered($read)" // l10n: not text (toString, for the logs)
    }

    /** No answer: [why]; [sent]: the request may have reached the server. */
    data class Failed(val why: ConnFailure, val sent: Boolean) : CallEnd()

    data class Stopped(val sent: Boolean) : CallEnd()
}

object ConnStep {
    /** D14: the effects a spoken yes may approve (with the connection's switch on). */
    val SPOKEN_YES: Set<SkillEffect> = setOf(SkillEffect.CHANGES_HOME, SkillEffect.READS_ONLINE, SkillEffect.OPENS_APP)

    /** A request body's most characters the card shows (the rest is counted). */
    const val PREVIEW_BODY = 1200

    /** The values of a step's [args] for [op]: each constant typed by its argument, then every argument filled (defaults for the rest); or why not. */
    sealed class Values {
        class Ok(val values: Map<String, SkillValue>) : Values() {
            override fun toString(): String = "Values.Ok" // l10n: not text (toString, for the logs)
        }

        data class Refused(val why: ConnFailure) : Values()
    }

    fun values(op: ConnOp, args: Map<String, StepArg>): Values = values(op.params, args)

    /** [values] for any set of arguments (C7: a hand-off's slots). */
    fun values(params: Map<String, OpParam>, args: Map<String, StepArg>): Values {
        val given = LinkedHashMap<String, SkillValue>()
        for ((k, a) in args) {
            val p = params[k] ?: return Values.Refused(ConnFailure.BAD_VALUE)
            given[k] = when (a) {
                is StepArg.Fixed -> ConnValues.fixed(a.value, p) ?: return Values.Refused(ConnFailure.BAD_VALUE)
                is StepArg.Value -> a.value
                StepArg.Missing -> return Values.Refused(ConnFailure.NO_VALUE)
            }
        }
        return ConnValues.complete(params, given)?.let { Values.Ok(it) } ?: Values.Refused(ConnFailure.BAD_VALUE)
    }

    /**
     * What [op] of [c] does with [values]: its kind's effects, your marks, and for a Home Assistant service, the domains of
     * the service and of every entity it sends (so a lock reached through `homeassistant.turn_off` counts as security).
     */
    fun effects(c: Connection, op: ConnOp, values: Map<String, SkillValue>): Set<SkillEffect> {
        val ha = ConnOps.haCall(op)
        val entities = (values[ConnOp.ENTITY]?.value as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return ConnOps.effects(c, op) + op.marks + (ha?.let { SkillEffects.homeAssistant(it, entities) }.orEmpty())
    }

    /**
     * C12's L3: what [p]'s card showed is what [op] of [c] would send now with [values], as [r] (built again): the same
     * request and the same effects. Otherwise the step asks again, and nothing is sent.
     */
    fun asShown(p: ConnPrepared, c: Connection, op: ConnOp, values: Map<String, SkillValue>, r: HttpRequest): Boolean =
        p.request != null && p.request == ShownRequest.of(r) && p.effects == effects(c, op, values)

    /** A value isn't yours: words Robot's model wrote, or anything Robot read from an answer or on the phone. A tap, always. */
    fun notYours(v: SkillValue): Boolean = ValueSource.MODEL in v.free || ValueSource.ONLINE in v.sources || ValueSource.PHONE in v.sources

    /** [a] prepared with what [x] knows: ready for the gate with its reasons and card, or refused with Robot's words. */
    fun prepare(a: ConnStepAction, x: StepContext): Prepared {
        // C6: travel time, which sends where you are (TravelStep).
        if (a.call.kind == StepKind.TRAVEL_TIME) return TravelStep.prepare(a, x)
        if (a.call.kind != StepKind.CONN) return refused(ConnFailure.NOT_YET, "")
        val c = x.connection?.takeIf { it.id == a.call.conn } ?: return refused(ConnFailure.GONE, "")
        val op = a.call.op?.let(c::op) ?: return refused(ConnFailure.GONE, c.name)
        // 1.3: a tool of an MCP server of yours (McpStep).
        (op.spec as? OpSpec.McpCall)?.let { return McpStep.prepare(a, x, c, op, it) }
        if (ConnOps.http(c, op) == null) return refused(ConnFailure.GONE, c.name)
        if (c.tested == null) return refused(ConnFailure.NOT_TESTED, c.name)
        ConnRules.refusal(c)?.let { return refused(it, c.name) }
        if (!x.networkOn) return refused(ConnFailure.NETWORK_OFF, c.name)
        ConnAway.before(c, x.net)?.let { return refused(it, c.name) }
        if (c.secrets.isNotEmpty() && !x.keySaved) return refused(ConnFailure.NO_KEY, c.name)
        ConnLimits.connection(x.limits, c, x.now)?.let { return refused(it, c.name, c) }
        val values = when (val v = values(op, a.call.args)) {
            is Values.Ok -> v.values
            is Values.Refused -> return refused(v.why, c.name)
        }
        val request = when (val b = ConnOps.build(c, op.id, values)) {
            is Built.Ok -> b.request
            is Built.Refused -> return refused(b.why, c.name)
        }
        val effects = effects(c, op, values)
        val origin = ConnConsent.origin(c) ?: return refused(ConnFailure.NOWHERE, c.name)
        val why = linkedSetOf<AskWhy>()
        if (ConnConsent.asks(c)) why += AskWhy.CONNECTION_FIRST
        val tap = effects.any { it in SkillEffect.RISKY } || values.values.any(::notYours)
        if (tap) why += AskWhy.CONNECTION_TAP
        else if (values.values.any { ValueSource.MODEL in it.sources }) why += AskWhy.CONNECTION
        val spokenYes = c.spokenYes && effects.all { it in SPOKEN_YES }
        val prepared = ConnPrepared(ConnStrings.stepCard("title" to op.title, "name" to c.name), body(c, op, request, values, effects, x.masks), effects,
            origin, c.name, ConnOps.reads(op), spokenYes, ShownRequest.of(request))
        val ready = a.copy(prepared = prepared, consent = null)
        val choices = if (AskWhy.CONNECTION_FIRST in why) listOf(Choice(ConnStrings.allow(), ready.copy(consent = ConsentAnswer.ALLOW)),
            Choice(InfoStrings.justThisTime(), ready.copy(consent = ConsentAnswer.ONCE))) else emptyList()
        val host = c.address?.host.orEmpty()
        val note: Phrase? = when {
            AskWhy.CONNECTION_FIRST in why -> ConnStrings.firstUse("skill" to a.call.skill, "host" to host, "name" to c.name, "what" to op.title)
            effects.any { it in SkillEffect.RISKY } -> ConnStrings.noteRisky()
            tap -> ConnStrings.noteValue()
            AskWhy.CONNECTION in why -> ConnStrings.notePicked()
            else -> null
        }
        return Prepared.Ready(ready, why = why, choices = choices, note = note?.en(), noteText = note)
    }

    internal fun refused(why: ConnFailure, name: String, c: Connection? = null): Prepared.Refused {
        val limit = when (why) {
            ConnFailure.LIMIT_MINUTE -> c?.perMinute
            ConnFailure.LIMIT_DAY -> ConnLimits.PER_DAY
            else -> null
        }
        return Prepared.Refused(ActionStatus.FAILED, why.text(name, limit = limit))
    }

    /** The card's body (see the file's header): where, the values, what it does, the request. */
    private fun body(c: Connection, op: ConnOp, r: HttpRequest, values: Map<String, SkillValue>, effects: Set<SkillEffect>, masks: Map<SecretPart, String>): Phrase {
        val host = c.address?.host.orEmpty()
        val lines = mutableListOf<Phrase>(ConnStrings.hostLine("host" to host, "name" to c.name))
        for ((k, v) in values) lines += ConnStrings.valueLine("arg" to k, "value" to SkillTemplate.text(v.value), "source" to source(v))
        val chips = chips(effects, host)
        if (chips.isNotEmpty()) lines += ConnStrings.effectsLine("list" to Phrase.ListOf(chips))
        if (SkillEffect.RUNS_CODE in effects) lines += ConnStrings.cantSeeScript()
        val p = ConnPreview.of(r, c, masks)
        lines += ConnStrings.previewRequest("method" to p.method, "url" to p.url)
        for (h in p.headers) lines += ConnStrings.previewHeader("line" to h)
        p.auth?.let { lines += it }
        p.body?.let { b ->
            lines += ConnStrings.previewBody("body" to b.take(PREVIEW_BODY))
            if (b.length > PREVIEW_BODY) lines += ConnStrings.previewMore("n" to (b.length - PREVIEW_BODY).toString())
        }
        return Phrase.Join(lines, "\n")
    }

    /** Where [v] came from, as its card line says it: what isn't yours first. */
    fun source(v: SkillValue): Phrase = when {
        ValueSource.ONLINE in v.sources -> ConnStrings.sourceOnline()
        ValueSource.PHONE in v.sources -> ConnStrings.sourcePhone()
        ValueSource.MODEL in v.free -> ConnStrings.sourceWritten()
        ValueSource.MODEL in v.sources -> ConnStrings.sourcePicked()
        ValueSource.YOURS in v.sources -> ConnStrings.sourceYours()
        else -> ConnStrings.sourceFixed()
    }

    /** The effects as the card's words, in a fixed order. */
    fun chips(effects: Set<SkillEffect>, host: String): List<Phrase> = buildList {
        val h = "host" to host
        if (SkillEffect.SECURITY in effects) add(ConnStrings.effectSecurity())
        if (SkillEffect.CHANGES_HOME in effects) add(ConnStrings.effectHome())
        if (SkillEffect.MESSAGES_SOMEONE in effects) add(ConnStrings.effectTells())
        if (SkillEffect.WRITES_ONLINE in effects) add(ConnStrings.effectWrites(h))
        if (SkillEffect.DELETES in effects) add(ConnStrings.effectDeletes(h))
        if (SkillEffect.RUNS_CODE in effects) add(ConnStrings.effectCode())
        if (SkillEffect.MONEY in effects) add(ConnStrings.effectMoney())
        if (SkillEffect.OPENS_APP in effects) add(ConnStrings.effectApp())
        if (SkillEffect.READS_ONLINE in effects) add(ConnStrings.effectReads(h))
    }

    /**
     * What [a] (prepared) of [c]'s operation [op] did, from the call's [end]: Robot's words for its card, its outputs and
     * checks for the rest of the skill ([Executed.conn]). [net]: CN4's network, read again at the call. [readBack]: for a
     * Home Assistant service, the states read back after the wait for entities its reply didn't list (HaCheck; null:
     * that read didn't come back).
     */
    fun result(a: ConnStepAction, c: Connection, op: ConnOp, end: CallEnd, net: NetNow?, readBack: Map<String, String?> = emptyMap()): Executed {
        val n = "name" to c.name
        return when (end) {
            is CallEnd.Stopped -> Executed(ActionStatus.DECLINED, a.stoppedText ?: app.heyrobot.core.i18n.TurnStrings.stopped())
                .copy(conn = ConnRan(sent = end.sent))
            is CallEnd.Failed -> {
                val why = ConnAway.after(c, net, end.why)
                val may = end.sent && !ConnOps.reads(op)
                val text = if (may) Phrase.Join(listOf(why.text(c.name), ConnStrings.mayHaveSent(n))) else why.text(c.name)
                Executed(ActionStatus.FAILED, text).copy(conn = ConnRan(sent = end.sent))
            }
            is CallEnd.Answered -> when (val read = end.read) {
                is ReplyRead.Failed -> {
                    // C12's L4: a 2xx Robot couldn't read came from a server that took the request: a write may have happened.
                    val words = read.why.text(c.name, read.status)
                    val may = read.status?.let { it in 200..299 } == true && !ConnOps.reads(op)
                    Executed(ActionStatus.FAILED, if (may) Phrase.Join(listOf(words, ConnStrings.mayHaveSent(n))) else words).copy(conn = ConnRan(sent = true))
                }
                is ReplyRead.Ok -> {
                    val outputs = ConnOps.outputs(op, read.json)
                    val checks = op.check != null || a.call.check != null
                    // C5: Home Assistant's own check of a service call, from the domain table and the read-back.
                    val ha = (op.spec as? OpSpec.HaCall)?.let { s -> (values(op, a.call.args) as? Values.Ok)?.let { HaCheck.outcome(s, read.json, it.values, readBack) } }
                    val check = when {
                        checks && !ConnOps.checked(op, read.json, a.call.check) -> CheckOutcome.FAILED
                        ha?.failed == true -> CheckOutcome.FAILED
                        checks || ha?.passed == true -> CheckOutcome.PASSED
                        else -> CheckOutcome.NONE
                    }
                    val content = outputs.isNotEmpty() || ConnOps.reads(op)
                    val ran = ConnRan(outputs, content, check, sent = true)
                    val body = if (outputs.isEmpty()) null else Phrase.Join(outputs.map { (k, v) ->
                        ConnStrings.outputLine("output" to k, "value" to SkillTemplate.text(v.value)) }, "\n")
                    val haFailed = ha?.let(HaCheck::failedWords)
                    val said = when {
                        ConnOps.reads(op) -> ConnStrings.ranRead(n)
                        ha?.passed == true -> HaCheck.doneWords(ha)?.let { ConnStrings.haDone(n, "what" to it) } ?: ConnStrings.ranDone(n)
                        ha?.seen?.values?.any { it == HaPreset.Seen.MOVING } == true -> ConnStrings.haMoving(n)
                        else -> ConnStrings.ranDone(n)
                    }
                    if (check == CheckOutcome.FAILED) Executed(ActionStatus.FAILED, ConnStrings.checkCard(n), body = body).copy(conn = ran.copy(what = haFailed ?: checkWhat(c)))
                    else Executed(ActionStatus.DONE, said, body = body).copy(conn = ran)
                }
            }
        }
    }

    /** The words of a failed check of yours: "your Home Assistant's answer wasn't what the step checks for" (Home Assistant's own: HaCheck). */
    fun checkWhat(c: Connection): Phrase = ConnStrings.checkWhat("name" to c.name)

    /** A request that needs its reply read as JSON: its operation's own, or this step's check. */
    fun wantingJson(r: HttpRequest, stepCheck: Boolean): HttpRequest = if (r.json || !stepCheck) r
        else HttpRequest(r.method, r.base, r.secretAddress, r.path, r.query, r.headers, r.body, r.auth, r.read, json = true, values = r.values)
}

/** The voice log's codes for a skill step's check (SKILL_CHECK's a, D21). Stable numbers. */
enum class SkillCheckCode(override val id: Int, override val title: String) : LogCode {
    PASSED(1, "passed"), FAILED(2, "didn't pass: the skill stopped"), WENT_ON(3, "didn't work: the skill went on"),
}

/** The voice log's codes for a connection's set-up (CONN_SETUP's b, D21; C5). Stable numbers. */
enum class ConnSetupCode(override val id: Int, override val title: String) : LogCode {
    TEST(1, "Test"), PICKERS(2, "Home Assistant's lists for the pickers");

    companion object {
        /** CONN_SETUP's c when something answered, but not as the server should. */
        const val UNEXPECTED = 99
    }
}

/** The voice log's codes for a limit reached (SKILL_LIMIT's a, D16, D21). Stable numbers. */
enum class SkillLimitCode(override val id: Int, override val title: String) : LogCode {
    SKILL_HOUR(1, "a skill's runs an hour"), CONN_MINUTE(2, "a connection's calls a minute"), CONN_DAY(3, "a connection's calls a day"),
}

/** The voice log's numbers for a connection's call (CONN_CALL, D21): its kind, how it ended, how long it took and how much came back. Never a host, a path, a value or a reply. */
object ConnLog {
    /** The connection's kind: 1 Home Assistant, 2 ntfy, 3 webhook, 4 routing, 5 hand-off. */
    fun kind(k: ConnKind): Int = k.ordinal + 1 // 6: an MCP server (1.3)

    /** 0 sent and answered 2xx; else the HTTP status's hundreds when one came (400, 500) plus ConnFailure's code. */
    fun result(end: CallEnd): Int = when (end) {
        is CallEnd.Stopped -> 0
        is CallEnd.Failed -> end.why.code
        is CallEnd.Answered -> when (val r = end.read) {
            is ReplyRead.Ok -> 0
            is ReplyRead.Failed -> (r.status?.let { (it / 100) * 100 } ?: 0) + r.why.code
        }
    }

    /** ms in buckets: 1 under 250, 2 under 500, 3 under 1 s, 4 under 2 s, 5 under 5 s, 6 under 10 s, 7 more. */
    fun ms(ms: Long): Int = MS.indexOfFirst { ms < it }.let { if (it < 0) MS.size + 1 else it + 1 }

    /** Bytes in buckets: 0 none, 1 under 1 KiB, 2 under 16 KiB, 3 under 64 KiB, 4 up to 256 KiB, 5 more. */
    fun size(bytes: Int): Int = when {
        bytes <= 0 -> 0
        bytes < 1024 -> 1
        bytes < 16 * 1024 -> 2
        bytes < 64 * 1024 -> 3
        bytes <= ConnReply.MAX_BYTES -> 4
        else -> 5
    }

    /** CONN_CALL's c: the time's bucket and the size's, together (37: under 10 s, more than 256 KiB). */
    fun timeAndSize(ms: Long, bytes: Int): Int = ms(ms) * 10 + size(bytes)

    private val MS = listOf(250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L)
}
