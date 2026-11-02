// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillNumbers
import app.heyrobot.core.skills.SkillStep
import java.util.Locale

// Pure. What a connection may hold (plan §3, §5.2; C2), used when it is saved and when connections.json is read: each
// field's limits, the kind's own rules (Home Assistant always has a token; ntfy's topic is always secret; an address that
// is itself the key is a webhook's), D2 (any secret makes it https only, refused when you type the address), D1 for an
// address typed as a number, every template naming one of the operation's arguments and every argument used, headers
// only from the allowlist, Home Assistant's targets only through the `entity` argument, and nothing in connections.json
// that looks like a password or key (SecretDetector, as skills refuse one): a key belongs in the connection's key file.
// Limits marked EST are starting values.

/** What is wrong with a connection, as Settings says it. */
enum class ConnProblem {
    /** Its shape: a field out of its limits, an operation that doesn't fit its kind. */
    COULDNT_SAVE,
    /** An address Robot won't use (D1, or one it can't read). */
    ADDRESS,
    /** A secret over plain http (D2). */
    KEY_OVER_HTTP,
    /** A fixed value that looks like a password or key. */
    SECRET_VALUE,
    /** C6: a routing server over plain http to anything but a typed loopback or Tailscale address. */
    LOCATION_OVER_HTTP,
    /** C12's M1: a Content-Type that doesn't fit the body (the webhook form shows it under Headers). */
    CONTENT_TYPE;

    fun text(name: String): Phrase = when (this) {
        COULDNT_SAVE -> ConnStrings.couldntSave()
        ADDRESS -> ConnStrings.addressBad()
        KEY_OVER_HTTP -> ConnStrings.keyOverHttp("name" to name)
        SECRET_VALUE -> ConnStrings.secretValue()
        LOCATION_OVER_HTTP -> ConnStrings.locationOverHttp("name" to name)
        CONTENT_TYPE -> ConnStrings.contentTypeBody()
    }
}

object ConnCheck {
    const val MAX_CONNECTIONS = 20 // EST
    const val MAX_NAME = 40
    const val MAX_TITLE = 60
    const val MAX_OPS = 40 // EST: Home Assistant's services you added
    const val MAX_PARAMS = 8 // EST
    const val MAX_OUTPUTS = 8
    const val MAX_OPTIONS = 64 // EST: the entities one operation may target
    const val MAX_OPTION = 120
    const val MAX_TEXT = 4096
    /** A text argument's most characters when its operation says none (EST). */
    const val DEFAULT_TEXT = 500
    const val MAX_PATH = 512
    const val MAX_QUERY = 16
    const val MAX_HEADERS = 8
    const val MAX_HEADER_VALUE = 1024
    const val MAX_BODY = 16 * 1024
    const val MAX_BODY_DEPTH = 8
    const val MAX_KEY = 64
    const val MAX_ANSWERS = 16
    const val MAX_VERSION = 40
    const val MAX_TAGS = 5
    const val MAX_NTFY_TITLE = 200
    const val MAX_POINTER = 200
    const val MAX_EQUALS = 200

    /** The argument and output types an operation takes: values with a shape, and text. */
    val TYPES: Set<ParamType> = setOf(ParamType.TEXT, ParamType.CHOICE, ParamType.INTEGER, ParamType.NUMBER, ParamType.YES_NO)

    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private val OUTPUT = Regex("^[a-z][a-z0-9_]{0,15}$")
    private val HA_NAME = Regex("^[a-z0-9_]{1,64}$")
    private val ENTITY = Regex("^[a-z0-9_]{1,64}\\.[a-z0-9_]{1,200}$")
    private val DEVICE_CLASS = Regex("^[a-z_]{1,32}$")
    private val QUERY_NAME = Regex("^[A-Za-z0-9_.~-]{1,64}$")
    private val TAG = Regex("^[a-z0-9_+-]{1,40}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val SIGNER = Regex("^[0-9a-f]{64}$")
    private val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,63}$")
    private val X_HEADER = Regex("^X-[A-Za-z0-9-]{1,40}$", RegexOption.IGNORE_CASE)
    private val MEDIA_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}(\\s*;\\s*[A-Za-z0-9_-]{1,32}=[A-Za-z0-9_.-]{1,64})*$")
    private val ACCEPT = Regex("^[A-Za-z0-9!#$&^_.+*/;=, -]{1,200}$")

    /**
     * Header names a webhook's plain headers never take, whatever their case (plan §3.3, with C2's additions): a key's
     * (only the secret header holds one), a method override (a POST acting as a DELETE would dodge the effects computed
     * from the method), a proxy's forwarding headers, and the ones the client itself controls.
     */
    private val KEYISH = Regex("key|token|secret|auth|cookie|passw|session|signature|credential", RegexOption.IGNORE_CASE)
    private val NEVER = setOf("host", "content-length", "transfer-encoding", "connection", "cookie", "set-cookie", "upgrade", "te", // l10n: not text (header names)
        "trailer", "keep-alive", "expect", "proxy-authorization", "proxy-connection", "user-agent", "x-http-method-override", // l10n: not text (header names)
        "x-http-method", "x-method-override", "x-real-ip", "forwarded", "via", "origin", "referer") // l10n: not text (header names)

    /** Home Assistant's data keys that pick targets: only the `entity` argument does (its effects come from those entities, §3.1). */
    val HA_TARGETS: Set<String> = setOf("entity_id", "device_id", "area_id", "floor_id", "label_id", "target") // l10n: not text (Home Assistant's fields)

    /** "all" as an entity: every entity of the service's domain (plan §3.1). */
    const val ALL = "all" // l10n: not text (Home Assistant's entity_id for every entity)

    fun valid(c: Connection): Boolean = problems(c).isEmpty()

    /** A connection's or an operation's name: 1 to [max] characters on one line, as written. */
    fun validLine(v: String, max: Int): Boolean = v.isNotBlank() && v == v.trim() && v.length <= max && !CONTROL.containsMatchIn(v)

    fun validEntity(e: String): Boolean = e.length <= 255 && ENTITY.matches(e)

    /** Looks like a password or key (SecretDetector): never in connections.json. */
    fun secret(v: String): Boolean = SecretDetector.find(v).isNotEmpty()

    /** What is wrong with [c]; empty when it may be saved. */
    fun problems(c: Connection): List<ConnProblem> {
        val out = linkedSetOf<ConnProblem>()
        if (!SkillStep.validConnId(c.id) || !validLine(c.name, MAX_NAME) || c.perMinute !in 1..ConnLimits.MAX_PER_MINUTE) out += ConnProblem.COULDNT_SAVE
        if (!kindFits(c)) out += ConnProblem.COULDNT_SAVE
        c.address?.let { a ->
            if (ConnUrl.parse(a.text) != a) out += ConnProblem.ADDRESS
            if (ConnRules.secretOverHttp(c)) out += ConnProblem.KEY_OVER_HTTP
            else if (ConnRules.literal(a) is ConnRules.Lookup.Refused) out += ConnProblem.ADDRESS
            else if (ConnRules.locationOverHttp(c)) out += ConnProblem.LOCATION_OVER_HTTP
            // An address typed with a key in it, not marked as one ("…/hooks/3f2a9c1e7b4d4e2a…").
            if (!c.secretAddress && secret(a.path)) out += ConnProblem.SECRET_VALUE
        }
        c.tested?.let { t -> if (t.at < 0 || t.version?.let { v -> !validLine(v, MAX_VERSION) } == true) out += ConnProblem.COULDNT_SAVE }
        if (c.answers.size > MAX_ANSWERS || c.answers.keys.any { k -> ConnUrl.parse(k)?.let { it.path.isEmpty() && it.origin == k } != true })
            out += ConnProblem.COULDNT_SAVE
        if (c.ops.map { it.id }.distinct().size != c.ops.size) out += ConnProblem.COULDNT_SAVE
        for (op in c.ops) out += opProblems(c, op)
        // C7: a hand-off's words, as an operation's, never hold a key.
        if (c.handOff?.let { IntentSpecs.secret(it.intent) } == true) out += ConnProblem.SECRET_VALUE
        return out.toList()
    }

    /** The kind's own rules: which fields it has, how its key goes, which operations it takes. */
    private fun kindFits(c: Connection): Boolean {
        val auth = c.auth
        if (auth is ConnAuth.Header && !secretHeaderName(auth.name)) return false
        if ((c.kind == ConnKind.ROUTING) != (c.routing != null) || (c.kind == ConnKind.HAND_OFF) != (c.handOff != null)) return false
        if (c.secretAddress && (c.kind != ConnKind.WEBHOOK || c.address?.path?.isEmpty() != true)) return false
        if (c.ops.size > MAX_OPS) return false
        return when (c.kind) {
            ConnKind.HOME_ASSISTANT -> c.address != null && auth == ConnAuth.Bearer &&
                c.ops.all { it.spec is OpSpec.HaCall || it.spec == OpSpec.HaRead || it.spec == OpSpec.HaScript }
            ConnKind.NTFY -> c.address != null && (auth == ConnAuth.None || auth == ConnAuth.Bearer) && c.ops.all { it.spec is OpSpec.NtfyPublish }
            ConnKind.WEBHOOK -> c.address != null && c.ops.size <= 1 && c.ops.all { it.spec is OpSpec.Webhook }
            ConnKind.ROUTING -> c.address != null && auth != ConnAuth.Basic && c.ops.isEmpty()
            ConnKind.HAND_OFF -> c.address == null && auth == ConnAuth.None && c.ops.isEmpty() && c.where == null && c.answers.isEmpty() &&
                c.handOff!!.let { h -> h.pkg.length <= 255 && PACKAGE.matches(h.pkg) && (h.signer == null || SIGNER.matches(h.signer)) && IntentSpecs.valid(h.intent, ::paramFits) }
        }
    }

    // ------------------------------------------------------------------ headers

    /** The secret header's name (ConnAuth.Header): a header's shape, never one the client controls; Authorization may be it. */
    fun secretHeaderName(n: String): Boolean = HEADER_NAME.matches(n) && n.lowercase(Locale.ROOT) !in NEVER &&
        !n.lowercase(Locale.ROOT).startsWith("x-forwarded") && !n.lowercase(Locale.ROOT).startsWith("proxy-") &&
        n.lowercase(Locale.ROOT) != "content-type" && n.lowercase(Locale.ROOT) != "accept"

    /** A plain header's name (plan §3.3): Content-Type, Accept, or an X- name of yours that doesn't hold a key. */
    fun plainHeaderName(n: String): Boolean {
        val l = n.lowercase(Locale.ROOT)
        if (l == "content-type" || l == "accept") return true // l10n: not text (header names)
        return X_HEADER.matches(n) && l !in NEVER && !l.startsWith("x-forwarded") && !KEYISH.containsMatchIn(l)
    }

    /** A header's value as sent: printable ASCII only (an HTTP client throws on anything else, and a line break would add a header). */
    fun headerValue(v: String): Boolean = v.length <= MAX_HEADER_VALUE && printable(v)

    /** Printable ASCII only: what any header value, a key's included, may hold. */
    fun printable(v: String): Boolean = v.all { it in ' '..'~' }

    /**
     * C12's M1: the Content-Type a webhook's [body] may go under, when you set one ([type]). Plain text goes as plain text,
     * or as a form, whose values HttpOp percent-encodes; JSON as JSON. Under any other label a value encoded for one place
     * would be read in another (a quote ending a JSON string, an "&" adding a form's field).
     */
    fun contentTypeFits(body: OpBody, type: String): Boolean {
        val t = mediaType(type)
        return when (body) {
            is OpBody.Text -> t == TEXT_PLAIN || t == FORM
            is OpBody.Json -> t == JSON || (t.startsWith("application/") && t.endsWith("+json")) // l10n: not text (media types)
        }
    }

    /** A form post's Content-Type (any case, any parameters): each value of its text body percent-encoded. */
    fun form(type: String?): Boolean = type != null && mediaType(type) == FORM

    /** [type]'s media type, lower case, without its parameters ("text/plain; charset=utf-8" is text/plain). */
    private fun mediaType(type: String): String = type.substringBefore(';').trim().lowercase(Locale.ROOT)

    private const val TEXT_PLAIN = "text/plain" // l10n: not text (a media type)
    private const val FORM = "application/x-www-form-urlencoded" // l10n: not text (a media type)
    private const val JSON = "application/json" // l10n: not text (a media type)

    // ------------------------------------------------------------------ operations

    private fun opProblems(c: Connection, op: ConnOp): List<ConnProblem> {
        val bad = listOf(ConnProblem.COULDNT_SAVE)
        if (!SkillStep.validOpId(op.id) || !validLine(op.title, MAX_TITLE)) return bad
        if (op.params.size > MAX_PARAMS || op.params.keys.any { !OpTemplate.NAME.matches(it) } || op.params.values.any { !paramFits(it) }) return bad
        if (op.outputs.size > MAX_OUTPUTS || op.outputs.any { (n, o) -> !OUTPUT.matches(n) || !outputFits(o) }) return bad
        if (!op.marks.all { it in ConnOp.MARKS }) return bad
        op.check?.let { k -> if (op.spec !is OpSpec.Webhook || !pointer(k.pointer) || !equalsValue(k.equals)) return bad }
        val used = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val shaped = when (val s = op.spec) {
            is OpSpec.HaCall -> haCall(op, s, used, texts)
            OpSpec.HaRead, OpSpec.HaScript -> haEntity(op, script = s == OpSpec.HaScript, all = false).also { used += ConnOp.ENTITY }
            is OpSpec.NtfyPublish -> ntfy(op, s, used, texts)
            is OpSpec.Webhook -> webhook(op, s, used, texts)
        }
        if (!shaped) return bad
        // Every template names an argument, and every argument is used.
        if (!op.params.keys.containsAll(used) || !used.containsAll(op.params.keys)) return bad
        val out = mutableListOf<ConnProblem>()
        if (texts.any(::secret) || (op.check?.equals as? String)?.let(::secret) == true) out += ConnProblem.SECRET_VALUE
        return out
    }

    /** An argument's own fields fit its type. */
    internal fun paramFits(p: OpParam): Boolean {
        if (p.type !in TYPES) return false
        val numeric = p.type == ParamType.INTEGER || p.type == ParamType.NUMBER
        if (p.type == ParamType.CHOICE) {
            if (p.options.size !in 1..MAX_OPTIONS || p.options.distinct().size != p.options.size || p.options.any { !validLine(it, MAX_OPTION) || "{{" in it || "}}" in it }) return false
        } else if (p.options.isNotEmpty()) return false
        if (numeric) {
            val lo = p.min ?: return false
            val hi = p.max ?: return false
            if (!lo.isFinite() || !hi.isFinite() || lo > hi || Math.abs(lo) > SkillCheck.MAX_NUMBER || Math.abs(hi) > SkillCheck.MAX_NUMBER) return false
            if (p.type == ParamType.INTEGER && (lo != Math.floor(lo) || hi != Math.floor(hi))) return false
        } else if (p.min != null || p.max != null) return false
        if (p.maxLen != null && (p.type != ParamType.TEXT || p.maxLen !in 1..MAX_TEXT)) return false
        p.default?.let { d -> if (!defaultFits(p, d)) return false }
        return true
    }

    /** [v] is a value [p] may take, as its default: of its type, one of its choices, within its limits. */
    fun defaultFits(p: OpParam, v: String): Boolean = when (p.type) {
        ParamType.TEXT -> v.isNotEmpty() && v.length <= (p.maxLen ?: DEFAULT_TEXT) && "{{" !in v && "}}" !in v
        ParamType.CHOICE -> v in p.options
        ParamType.INTEGER -> v.toLongOrNull()?.let { it.toString() == v && it >= p.min!! && it <= p.max!! } == true
        ParamType.NUMBER -> v.toDoubleOrNull()?.let { it.isFinite() && SkillNumbers.text(it) == v && it >= p.min!! && it <= p.max!! } == true
        ParamType.YES_NO -> v == "true" || v == "false"
        else -> false
    }

    private fun outputFits(o: OpOutput): Boolean {
        if (o.type !in TYPES || !pointer(o.pointer)) return false
        return if (o.type == ParamType.CHOICE) o.options.size in 1..MAX_OPTIONS && o.options.distinct().size == o.options.size &&
            o.options.all { validLine(it, MAX_OPTION) } else o.options.isEmpty()
    }

    /** RFC 6901: empty, or "/"-separated tokens where "~" is only "~0" or "~1". */
    fun pointer(p: String): Boolean = p.length <= MAX_POINTER && !CONTROL.containsMatchIn(p) && (p.isEmpty() || p.startsWith("/")) &&
        !Regex("~(?![01])").containsMatchIn(p)

    private fun equalsValue(v: Any): Boolean = when (v) {
        is String -> v.length <= MAX_EQUALS && !CONTROL.containsMatchIn(v)
        is Boolean, is Long -> true
        is Double -> v.isFinite()
        else -> false
    }

    /** Home Assistant's `entity` argument: a choice of entity ids you picked; scripts only `script.…`; "all" only where [all]. */
    private fun haEntity(op: ConnOp, script: Boolean, all: Boolean): Boolean {
        val p = op.params[ConnOp.ENTITY] ?: return false
        if (p.type != ParamType.CHOICE) return false
        return p.options.all { e -> (e == ALL && all && p.options.size == 1) || (validEntity(e) && (!script || e.startsWith("script."))) } // l10n: not text (Home Assistant's script domain)
    }

    private fun haCall(op: ConnOp, s: OpSpec.HaCall, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (!HA_NAME.matches(s.domain) || !HA_NAME.matches(s.service)) return false
        if (!haEntity(op, script = false, all = SkillEffects.haAll(s.domain))) return false
        used += ConnOp.ENTITY
        val entities = op.params.getValue(ConnOp.ENTITY).options
        if (s.classes.any { (e, k) -> e !in entities || !e.startsWith("cover.") || !DEVICE_CLASS.matches(k) }) return false // l10n: not text (Home Assistant's cover domain)
        if (s.data.keys.any { !HA_NAME.matches(it) || it in HA_TARGETS }) return false
        return jsonTemplate(s.data, 1, used, texts)
    }

    private fun ntfy(op: ConnOp, s: OpSpec.NtfyPublish, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (!textTemplate(s.message, MAX_TEXT, used, texts) || s.message.isEmpty()) return false
        s.title?.let { if (it.isEmpty() || !textTemplate(it, MAX_NTFY_TITLE, used, texts) || CONTROL.containsMatchIn(it)) return false }
        when (val pr = s.priority) {
            null -> {}
            is Long -> if (pr !in 1L..5L) return false
            is String -> {
                val n = OpTemplate.whole(pr) ?: return false
                val p = op.params[n] ?: return false
                // A whole number 1 to 5: ntfy's JSON takes the priority as a number, never as text.
                if (p.type != ParamType.INTEGER || p.min!! < 1.0 || p.max!! > 5.0) return false
                used += n
            }
            else -> return false
        }
        return s.tags.size <= MAX_TAGS && s.tags.all { TAG.matches(it) }
    }

    private fun webhook(op: ConnOp, s: OpSpec.Webhook, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (!pathTemplate(s.path, used, texts)) return false
        if (s.query.size > MAX_QUERY || s.query.any { (n, v) -> !QUERY_NAME.matches(n) || !textTemplate(v, MAX_PATH, used, texts) || CONTROL.containsMatchIn(v) }) return false
        if (s.headers.size > MAX_HEADERS || s.headers.map { it.first.lowercase(Locale.ROOT) }.distinct().size != s.headers.size) return false
        for ((n, v) in s.headers) {
            if (!plainHeaderName(n)) return false
            when (n.lowercase(Locale.ROOT)) {
                "content-type" -> if (s.body == null || !MEDIA_TYPE.matches(v) || !contentTypeFits(s.body, v)) return false // l10n: not text (a header name)
                "accept" -> if (!ACCEPT.matches(v)) return false // l10n: not text (a header name)
                else -> if (!textTemplate(v, MAX_HEADER_VALUE, used, texts) || !headerValue(OpTemplate.words(v) ?: return false)) return false
            }
        }
        return when (val b = s.body) {
            null -> true
            is OpBody.Json -> s.method.body && size(b.tree) <= MAX_BODY && jsonTemplate(b.tree, 0, used, texts)
            is OpBody.Text -> s.method.body && b.template.isNotEmpty() && textTemplate(b.template, MAX_BODY, used, texts)
        }
    }

    /** A path after the address: empty, or "/" segments of URL characters, templates inside them. */
    private fun pathTemplate(p: String, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (p.isEmpty()) return true
        if (p.length > MAX_PATH || !p.startsWith("/")) return false
        for (seg in p.substring(1).split('/')) {
            val parts = OpTemplate.parts(seg) ?: return false
            if (parts.isEmpty()) return false
            for (part in parts) when (part) {
                is OpTemplate.Part.Words -> if (!ConnUrl.chars(part.text, "")) return false
                is OpTemplate.Part.Slot -> used += part.name
            }
            if (parts.all { it is OpTemplate.Part.Words } && !ConnUrl.segment(seg)) return false
        }
        texts += OpTemplate.words(p) ?: return false
        return true
    }

    /** Text with templates inside: at most [max] characters, and it reads. */
    private fun textTemplate(t: String, max: Int, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (t.length > max) return false
        used += OpTemplate.names(t) ?: return false
        texts += OpTemplate.words(t) ?: return false
        return true
    }

    /** A JSON template: at most [MAX_BODY_DEPTH] deep, keys on one line, its strings templates that read. */
    private fun jsonTemplate(tree: Any?, depth: Int, used: MutableList<String>, texts: MutableList<String>): Boolean {
        if (depth > MAX_BODY_DEPTH) return false
        return when (tree) {
            null, is Boolean, is Long -> true
            is Double -> tree.isFinite()
            is String -> textTemplate(tree, MAX_TEXT, used, texts)
            is Map<*, *> -> tree.all { (k, v) -> k is String && k.isNotEmpty() && k.length <= MAX_KEY && !CONTROL.containsMatchIn(k) &&
                OpTemplate.parts(k)?.all { it is OpTemplate.Part.Words } == true && jsonTemplate(v, depth + 1, used, texts) }
            is List<*> -> tree.all { jsonTemplate(it, depth + 1, used, texts) }
            else -> false
        }
    }

    /** About how many bytes [tree] takes as JSON. */
    private fun size(tree: Any?): Int = try { ConnJson.write(tree).toByteArray(Charsets.UTF_8).size } catch (_: IllegalArgumentException) { Int.MAX_VALUE }
}
