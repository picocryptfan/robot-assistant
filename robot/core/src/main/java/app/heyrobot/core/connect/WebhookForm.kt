// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepCheck

// Pure. The webhook form's core side (docs/plans/skills-connections-plan.md §3.3, §6.3; C6): what C9's screen holds while
// you fill a webhook's one request, turned into the operation connections.json keeps, with each field's problem so the
// screen can show it beside that field. Every rule is ConnCheck's (the one gate before a save): a field is checked by
// asking ConnCheck about an operation that holds only that field and the arguments it names, so the form and the file can
// never disagree. Then the whole operation once more, and every argument must be used somewhere.
//
// The address: when you say it holds a secret (Discord, Slack and n8n put one in it), the whole address goes to the key
// file and connections.json keeps only its origin (what the card shows and first-use answers name), https only (D2);
// a word in its path that hints at money ("pay", "order") becomes a mark, since connections.json never holds that path.

object WebhookForm {
    /** A field of the form, each with its own problem. */
    enum class Field { TITLE, METHOD, PATH, QUERY, HEADERS, BODY, ARGUMENTS, OUTPUTS, CHECK }

    /**
     * What the form holds: [method] as typed ("POST"), [path] after the address with `{{argument}}` slots, [query] and
     * [headers] as (name, value) pairs, a [body] (JSON when [json], else plain text; null for none), the arguments a step
     * fills ([params]), the declared [outputs] (JSON pointers), an optional [check], and your [marks].
     */
    data class Fields(
        val title: String,
        val method: String,
        val path: String = "",
        val query: List<Pair<String, String>> = emptyList(),
        val headers: List<Pair<String, String>> = emptyList(),
        val body: String? = null,
        val json: Boolean = true,
        val params: Map<String, OpParam> = emptyMap(),
        val outputs: Map<String, OpOutput> = emptyMap(),
        val check: StepCheck.Equals? = null,
        val marks: Set<SkillEffect> = emptySet(),
    ) {
        override fun toString(): String = "WebhookForm.Fields(…)" // l10n: not text (toString, for the logs)
    }

    /** The form made into an operation ([Made.Ok]), or each field's problem. */
    sealed class Made {
        class Ok(val op: ConnOp) : Made() {
            override fun toString(): String = "Made.Ok" // l10n: not text (toString, for the logs)
        }

        data class Problems(val fields: Map<Field, ConnProblem>) : Made()
    }

    private const val CONTENT_TYPE = "Content-Type" // l10n: not text (a header name)

    /** The one operation's id: a webhook has one request (ConnCheck allows one). */
    const val OP_ID = "hook" // l10n: not text (an operation id)

    /** [f] as connection [c]'s one operation, every rule checked as ConnCheck checks it on save. */
    fun make(c: Connection, f: Fields): Made {
        val problems = linkedMapOf<Field, ConnProblem>()
        val bad = ConnProblem.COULDNT_SAVE
        if (!ConnCheck.validLine(f.title, ConnCheck.MAX_TITLE)) problems[Field.TITLE] = bad
        val method = HttpMethod.of(f.method.trim().uppercase(java.util.Locale.ROOT))
        if (method == null) problems[Field.METHOD] = bad
        val body: OpBody? = when {
            f.body == null -> null
            !f.json -> OpBody.Text(f.body)
            else -> StrictJson.parse(f.body.toByteArray(Charsets.UTF_8))?.let { OpBody.Json(it.value) }
        }
        if (f.body != null && (body == null || method?.body == false)) problems[Field.BODY] = bad
        if (method == null) return Made.Problems(problems)
        val full = OpSpec.Webhook(method, f.path, f.query, f.headers, body)
        // Each field alone, with only the arguments it names.
        fun alone(field: Field, spec: OpSpec.Webhook, outputs: Map<String, OpOutput> = emptyMap(), check: StepCheck.Equals? = null) {
            if (field in problems) return
            val op = ConnOp(OP_ID, "x", spec, f.params.filterKeys { it in names(spec) }, outputs, check)
            ConnCheck.problems(c.copy(ops = listOf(op))).firstOrNull()?.let { problems[field] = it }
        }
        alone(Field.PATH, OpSpec.Webhook(method, f.path))
        alone(Field.QUERY, OpSpec.Webhook(method, query = f.query))
        // Content-Type needs a body: the headers are checked beside it (and without Content-Type when the body is the problem).
        val bodyOk = Field.BODY !in problems
        // C12's M1: a Content-Type that doesn't fit the body says so under Headers.
        val type = f.headers.firstOrNull { it.first.equals(CONTENT_TYPE, ignoreCase = true) }?.second
        if (bodyOk && body != null && type != null && !ConnCheck.contentTypeFits(body, type)) problems[Field.HEADERS] = ConnProblem.CONTENT_TYPE
        alone(Field.HEADERS, OpSpec.Webhook(method, headers = if (bodyOk) f.headers else f.headers.filter { !it.first.equals(CONTENT_TYPE, ignoreCase = true) },
            body = body.takeIf { bodyOk }))
        alone(Field.BODY, OpSpec.Webhook(method, body = body))
        alone(Field.OUTPUTS, OpSpec.Webhook(method), outputs = f.outputs)
        alone(Field.CHECK, OpSpec.Webhook(method), check = f.check)
        val used = names(full)
        if (f.params.keys.any { it !in used } || used.any { it !in f.params }) problems.putIfAbsent(Field.ARGUMENTS, bad)
        val op = ConnOp(OP_ID, f.title, full, f.params, f.outputs, f.check, f.marks)
        if (problems.isEmpty()) ConnCheck.problems(c.copy(ops = listOf(op))).firstOrNull()?.let { problems[Field.ARGUMENTS] = it }
        return if (problems.isEmpty()) Made.Ok(op) else Made.Problems(problems)
    }

    /** The marks the form starts from: its operation's, or for a new webhook what its address hinted at. */
    fun startMarks(c: Connection): Set<SkillEffect> = c.ops.firstOrNull()?.marks ?: c.addressMarks

    /** What a check's Equals field holds: a whole number, a decimal, true or false, or the words as typed. */
    fun checkValue(typed: String): Any {
        val v = typed.trim()
        return v.toLongOrNull() ?: ConnReply.number(v) ?: v.toBooleanStrictOrNull() ?: v
    }

    /** The arguments [s] names, wherever it names them (path, query, headers, body). */
    fun names(s: OpSpec.Webhook): Set<String> = buildSet {
        OpTemplate.names(s.path)?.let(::addAll)
        s.query.forEach { (_, v) -> OpTemplate.names(v)?.let(::addAll) }
        s.headers.forEach { (_, v) -> OpTemplate.names(v)?.let(::addAll) }
        when (val b = s.body) {
            is OpBody.Json -> OpTemplate.strings(b.tree).forEach { t -> OpTemplate.names(t)?.let(::addAll) }
            is OpBody.Text -> OpTemplate.names(b.template)?.let(::addAll)
            null -> {}
        }
    }

    /**
     * The address you typed, split for keeping: [kept] goes in connections.json (the whole address, or only its origin
     * when it holds a secret), [secret] in the key file (the whole address, or null), and [marks] what its path hints at.
     */
    class Address(val kept: ConnUrl, val secret: String?, val marks: Set<SkillEffect>) {
        override fun toString(): String = "WebhookForm.Address(…)" // l10n: not text (toString, for the logs)
    }

    /** [typed] as a webhook's address; [holdsSecret]: you said it holds one. Null with the problem when Robot can't use it. */
    fun address(typed: String, holdsSecret: Boolean): Pair<Address?, ConnProblem?> {
        val t = typed.trim()
        if (!holdsSecret) {
            val u = ConnUrl.parse(t) ?: return null to ConnProblem.ADDRESS
            return Address(u, null, emptySet()) to null
        }
        val u = ConnUrl.parse(t, whole = true) ?: return null to ConnProblem.ADDRESS
        if (!u.https) return null to ConnProblem.KEY_OVER_HTTP
        if (!ConnSecrets.valid(SecretPart.ADDRESS, t)) return null to ConnProblem.ADDRESS
        return Address(u.originOnly(), t, WebhookPreset.addressMarks(t)) to null
    }
}
