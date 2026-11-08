// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Choice
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.ValueSource

// Pure. A step that calls a tool of an MCP server of yours (1.3, design §8.5; S3.6): the MCP step kind is an operation
// kind, OpSpec.McpCall, named by a skill's `conn:<server>:<tool>` step or by the one-step skill McpSkills makes for it,
// so it goes through the same gate as every connection's step (ConnStep.prepare hands it here). Before the card: what
// ConnStep checks (tested, D1 and D2, the Network toggle, off your network, the key, the limits), then the values typed
// by the tool's questions and the arguments checked against the pinned inputSchema (SchemaCheck, strict): a wrong
// argument is refused here, before anything is built or sent. The card shows your label, the server's name, the tool's
// name and its arguments as JSON (all that will be sent, never the server's description: longer than the card's preview,
// it is refused), and what it does. A value Robot's
// model wrote or Robot read needs a tap, as for any connection.
//
// After the call ([result]): the tool's text, cleaned and cut to 1,500 characters (McpWire.callReply), is content from
// outside the phone (ONLINE), told as the step's body; a tool that answered it failed says so in Robot's words. A call
// that may have reached the server and broke is never sent again ("may have sent").

/** How an MCP call ended, as McpClient tells it: [out] null when Stop came; [sent]: it may have reached the server. */
class McpCallEnd(val out: McpOut<McpCalled>?, val sent: Boolean, val era: McpEra?, val ms: Long = 0, val bytes: Int = 0) {
    override fun toString(): String = "McpCallEnd(${era?.id}, $sent)" // l10n: not text (toString, for the logs)
}

object McpStep {
    fun prepare(a: ConnStepAction, x: StepContext, c: Connection, op: ConnOp, spec: OpSpec.McpCall): Prepared {
        if (c.tested == null) return ConnStep.refused(ConnFailure.NOT_TESTED, c.name)
        ConnRules.refusal(c)?.let { return ConnStep.refused(it, c.name) }
        if (!x.networkOn) return ConnStep.refused(ConnFailure.NETWORK_OFF, c.name)
        ConnAway.before(c, x.net)?.let { return ConnStep.refused(it, c.name) }
        if (c.secrets.isNotEmpty() && !x.keySaved) return ConnStep.refused(ConnFailure.NO_KEY, c.name)
        ConnLimits.connection(x.limits, c, x.now)?.let { return ConnStep.refused(it, c.name, c) }
        val values = when (val v = ConnStep.values(op, a.call.args)) {
            is ConnStep.Values.Ok -> v.values
            is ConnStep.Values.Refused -> return ConnStep.refused(v.why, c.name)
        }
        val args = McpOps.arguments(op, values)
        if (SchemaCheck.check(spec.input, args, strict = true).isNotEmpty() || McpPolicy.headers(spec.input) == null)
            return ConnStep.refused(ConnFailure.BAD_VALUE, c.name)
        // The card shows every character that is sent: arguments longer than its preview are refused, never cut and sent whole.
        if (McpPolicy.canonical(args).length > ConnStep.PREVIEW_BODY) return ConnStep.refused(ConnFailure.MCP_TOO_LONG, c.name)
        val effects = ConnStep.effects(c, op, values)
        val origin = ConnConsent.origin(c) ?: return ConnStep.refused(ConnFailure.NOWHERE, c.name)
        val why = linkedSetOf<AskWhy>()
        if (ConnConsent.asks(c)) why += AskWhy.CONNECTION_FIRST
        val tap = effects.any { it in SkillEffect.RISKY } || values.values.any(ConnStep::notYours)
        if (tap) why += AskWhy.CONNECTION_TAP
        else if (values.values.any { ValueSource.MODEL in it.sources }) why += AskWhy.CONNECTION
        val spokenYes = c.spokenYes && effects.all { it in ConnStep.SPOKEN_YES }
        val prepared = ConnPrepared(ConnStrings.stepCard("title" to op.title, "name" to c.name), body(c, spec, args, effects), effects, origin, c.name,
            spec.reads, spokenYes, null)
        val ready = a.copy(prepared = prepared, consent = null)
        val choices = if (AskWhy.CONNECTION_FIRST in why) listOf(Choice(ConnStrings.allow(), ready.copy(consent = ConsentAnswer.ALLOW)),
            Choice(InfoStrings.justThisTime(), ready.copy(consent = ConsentAnswer.ONCE))) else emptyList()
        val note: Phrase? = when {
            AskWhy.CONNECTION_FIRST in why -> ConnStrings.firstUse("skill" to a.call.skill, "host" to c.address?.host.orEmpty(), "name" to c.name, "what" to op.title)
            effects.any { it in SkillEffect.RISKY } -> ConnStrings.noteRisky()
            tap -> ConnStrings.noteValue()
            AskWhy.CONNECTION in why -> ConnStrings.notePicked()
            else -> null
        }
        return Prepared.Ready(ready, why = why, choices = choices, note = note?.en(), noteText = note)
    }

    /**
     * The arguments as the card shows them and as they are sent: JSON with sorted keys. [prepare] refuses longer ones than
     * the card's preview, so the cut never shows; a call whose arguments grew past it is no longer as shown ([asShown]).
     */
    fun json(args: Map<String, Any?>): String = McpPolicy.canonical(args).let { if (it.length > ConnStep.PREVIEW_BODY) it.take(ConnStep.PREVIEW_BODY) + "…" else it }

    /** The card's body: the tool, its arguments, what it does. */
    fun body(c: Connection, spec: OpSpec.McpCall, args: Map<String, Any?>, effects: Set<SkillEffect>): Phrase = Phrase.Join(listOf(
        ConnStrings.mcpCardTool("tool" to ConnReply.clean(spec.tool)), ConnStrings.mcpCardArgs("args" to json(args)),
        Phrase.ListOf(ConnStep.chips(effects, c.address?.host.orEmpty()))), "\n")

    /** C12's L3 for a tool: the card showed what would be sent now (its body and effects), else nothing is sent. */
    fun asShown(p: ConnPrepared, c: Connection, op: ConnOp, spec: OpSpec.McpCall, values: Map<String, app.heyrobot.core.skills.SkillValue>): Boolean {
        val effects = ConnStep.effects(c, op, values)
        return p.request == null && p.effects == effects && p.body == body(c, spec, McpOps.arguments(op, values), effects)
    }

    fun result(a: ConnStepAction, c: Connection, op: ConnOp, end: McpCallEnd, net: NetNow?): Executed {
        val n = "name" to c.name
        val reads = ConnOps.reads(op)
        fun maybe(words: Phrase, sent: Boolean): Phrase = if (sent && !reads) Phrase.Join(listOf(words, ConnStrings.mayHaveSent(n))) else words
        val out = end.out ?: return Executed(ActionStatus.DECLINED, a.stoppedText ?: app.heyrobot.core.i18n.TurnStrings.stopped())
            .copy(conn = ConnRan(sent = end.sent))
        return when (out) {
            is McpOut.Failed -> Executed(ActionStatus.FAILED, maybe(ConnAway.after(c, net, out.why).text(c.name, out.status), end.sent)).copy(conn = ConnRan(sent = end.sent))
            is McpOut.Refused -> Executed(ActionStatus.FAILED, maybe(out.why.text(c.name, out.rpc), end.sent)).copy(conn = ConnRan(sent = end.sent))
            is McpOut.Ok -> {
                val called = out.value
                if (called.failed) Executed(ActionStatus.FAILED, McpWire.failedText(c.name)).copy(conn = ConnRan(sent = true))
                else {
                    val body = called.text.takeIf { it.isNotBlank() }?.let { Phrase.Plain(it) }
                    Executed(ActionStatus.DONE, if (reads) ConnStrings.ranRead(n) else ConnStrings.ranDone(n), body = body)
                        .copy(conn = ConnRan(content = body != null, sent = true))
                }
            }
        }
    }
}

/** The voice log's codes for MCP (VoiceEvent.MCP_CALL). */
object McpLog {
    fun era(e: McpEra?): Int = when (e) {
        null -> 0
        McpEra.MODERN -> 1
        McpEra.LEGACY -> 2
    }

    /** 0 done; 1 the tool said it failed; 100 + McpRefusal's code; 200 + ConnFailure's code; 300 stopped. */
    fun result(out: McpOut<*>?): Int = when (out) {
        null -> 300
        is McpOut.Ok -> if ((out.value as? McpCalled)?.failed == true) 1 else 0
        is McpOut.Refused -> 100 + out.why.code
        is McpOut.Failed -> 200 + out.why.code
    }

    const val LIST = 1
    const val CALL = 2
}
