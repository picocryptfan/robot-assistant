// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
// Imported under another name: a scan for a file name (code.json, Phone lock's) reads "Encoding.json" as one.
import app.heyrobot.core.skills.SkillEncode as Encoding
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.SlotType
import app.heyrobot.core.skills.ValueSource

// Pure. One request an operation makes (plan §3, §5.1; C2): every preset (Home Assistant, ntfy, webhooks; routing builds
// its own) turns an operation into an [HttpOp], and [HttpOp.build] fills it with a step's values into an [HttpRequest].
// Each value is encoded for its place, by SkillEncode: JSON-escaped and typed in a JSON body (a number stays a number),
// percent-encoded in a path segment (never "/", "\", ".." or a control character unless a choice of yours holds it) and
// in a query (whole, "/" as %2F: only a control character is refused, C12's L5), percent-encoded in a plain-text body
// sent as a form (application/x-www-form-urlencoded) and as it is in one sent as plain text, and in a header only as
// printable ASCII (an HTTP client throws on anything else, and a line break would add a header). A body goes only under a Content-Type that fits it (ConnCheck.contentTypeFits, C12's M1). A value
// that doesn't fit its argument (type, choices, limits, length) refuses the whole request: nothing is repaired, nothing
// is sent.
//
// What a built request never holds: a key, a token, a topic or an address that is itself the key. It names the secret
// parts it needs, and ConnWire puts them in last, in the client's process, after every check (§4.2). So a preview, a
// card or a log made from it can't hold one.

/** A body's place for a secret: ntfy's topic, which its JSON publish carries in the body. */
data object TopicSlot

/** A step's values for an operation's arguments (C4 passes them; defaults fill the rest). */
object ConnValues {
    /** A constant (a step's fixed argument, or [OpParam.default]'s text) typed by its argument, or null when it doesn't fit. */
    fun fixed(v: Any?, p: OpParam): SkillValue? {
        val typed: Any = when (v) {
            is String -> if (p.type == ParamType.TEXT || p.type == ParamType.CHOICE) v else SkillTemplate.typed(p.type, v)
            is Long, is Boolean -> v
            is Int -> v.toLong()
            is Double -> v.takeIf { it.isFinite() } ?: return null
            else -> return null
        }
        val type = when {
            p.type == ParamType.NUMBER && typed is Long -> ParamType.INTEGER
            else -> p.type
        }
        return SkillValue.of(typed, type, ValueSource.FIXED).takeIf { fits(it, p) }
    }

    /** [v] fits [p]: of a type it takes, one of its choices, within its limits and its length. */
    fun fits(v: SkillValue, p: OpParam): Boolean {
        if (!Encoding.fits(v, SlotType(p.type, p.options))) return false
        return when (p.type) {
            ParamType.INTEGER -> (v.value as? Long)?.let { it >= p.min!! && it <= p.max!! } == true
            ParamType.NUMBER -> (v.value as? Number)?.toDouble()?.let { it.isFinite() && it >= p.min!! && it <= p.max!! } == true
            ParamType.TEXT -> SkillTemplate.text(v.value).let { it.isNotEmpty() && it.length <= (p.maxLen ?: ConnCheck.DEFAULT_TEXT) }
            ParamType.YES_NO -> v.value is Boolean
            else -> true
        }
    }

    /**
     * Every argument of [params] with its value: [given]'s, else its default; null when one is missing, one doesn't fit,
     * or [given] names an argument the operation doesn't have.
     */
    fun complete(params: Map<String, OpParam>, given: Map<String, SkillValue>): Map<String, SkillValue>? {
        if (!params.keys.containsAll(given.keys)) return null
        val out = LinkedHashMap<String, SkillValue>()
        for ((name, p) in params) {
            val v = given[name] ?: p.default?.let { fixed(it, p) } ?: return null
            if (!fits(v, p)) return null
            out[name] = v
        }
        return out
    }
}

/** A piece of a built body: text as it goes, or a secret's place (filled by ConnWire, JSON-escaped). */
sealed interface BodyPart {
    data class Text(val text: String) : BodyPart {
        override fun toString(): String = "Text(…)" // l10n: not text (toString, for the logs)
    }

    data class Secret(val part: SecretPart) : BodyPart
}

/** A built body: its pieces and its media type. */
class ReqBody(val parts: List<BodyPart>, val contentType: String) {
    /** Its size in bytes without its secrets (a topic adds at most 66). */
    val size: Int get() = parts.sumOf { if (it is BodyPart.Text) it.text.toByteArray(Charsets.UTF_8).size else 0 }

    override fun toString(): String = "ReqBody(…)" // l10n: not text (toString, for the logs)
}

/**
 * A request ready but for its secrets. [base]: the connection's address (for an address that is itself the key, only its
 * origin: ConnWire puts the key's address there). [path]: after the base, encoded ("" or "/…"). [query]: encoded names
 * and values (a null value: the name alone, Home Assistant's `?return_response`). [headers]: plain ones, checked.
 * [auth]: how the key goes (ConnWire adds it). [read]: it only reads. [json]: the reply must be JSON (outputs or a check
 * read it). [values]: the arguments as filled, with their sources (C4's cards and taps read them); never logged.
 */
class HttpRequest(
    val method: HttpMethod,
    val base: ConnUrl,
    val secretAddress: Boolean,
    val path: String,
    val query: List<Pair<String, String?>>,
    val headers: List<Pair<String, String>>,
    val body: ReqBody?,
    val auth: ConnAuth,
    val read: Boolean,
    val json: Boolean,
    val values: Map<String, SkillValue> = emptyMap(),
) {
    /** The secret parts ConnWire must add. */
    val secrets: Set<SecretPart> get() = buildSet {
        auth.part?.let(::add)
        if (secretAddress) add(SecretPart.ADDRESS)
        body?.parts?.forEach { if (it is BodyPart.Secret) add(it.part) }
    }

    /** The encoded path and query after the base ("/api/states/light.kitchen", "/route?x=1"). */
    val tail: String get() = path + if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> if (v == null) k else "$k=$v" }

    override fun toString(): String = "HttpRequest(${method.id})" // l10n: not text (toString, for the logs)
}

/** What building gave: a request, or why none (nothing is sent). */
sealed class Built {
    class Ok(val request: HttpRequest) : Built() {
        override fun toString(): String = "Built.Ok" // l10n: not text (toString, for the logs)
    }

    data class Refused(val why: ConnFailure) : Built()
}

/**
 * An operation as a request's template (see the file's header): its [method], a [path] after the connection's address
 * (templates inside segments), [query] and [headers] as (name, template) pairs, a [body] (a JSON template may hold
 * [TopicSlot]), its arguments, whether it only reads, and whether its reply must be JSON.
 */
class HttpOp(
    val method: HttpMethod,
    val path: String,
    val query: List<Pair<String, String?>> = emptyList(),
    val headers: List<Pair<String, String>> = emptyList(),
    val body: OpBody? = null,
    val params: Map<String, OpParam> = emptyMap(),
    val read: Boolean = false,
    val json: Boolean = false,
) {
    override fun toString(): String = "HttpOp(${method.id})" // l10n: not text (toString, for the logs)

    /** [c]'s request with [given] values (C4: a step's, typed with their sources), or why not. */
    fun build(c: Connection, given: Map<String, SkillValue>): Built {
        ConnRules.refusal(c)?.let { return Built.Refused(it) }
        val base = c.address ?: return Built.Refused(ConnFailure.NOWHERE)
        val values = ConnValues.complete(params, given) ?: return Built.Refused(ConnFailure.BAD_VALUE)
        fun slot(name: String) = params.getValue(name).let { SlotType(it.type, it.options) }
        val bad = Built.Refused(ConnFailure.BAD_VALUE)

        // The path: each template inside a segment, percent-encoded.
        val p = StringBuilder()
        if (path.isNotEmpty()) for (seg in path.substring(1).split('/')) {
            val filled = StringBuilder()
            for (part in OpTemplate.parts(seg) ?: return bad) when (part) {
                is OpTemplate.Part.Words -> filled.append(part.text)
                is OpTemplate.Part.Slot -> filled.append(Encoding.path(values.getValue(part.name), slot(part.name)) ?: return bad)
            }
            // Filled, no piece is empty or a dot segment ("{{a}}{{b}}" with "." and "." would make ".."; a choice of yours may hold "/").
            if (filled.isNotEmpty() && filled.split('/').any { !ConnUrl.segment(it) }) return bad
            p.append('/').append(filled)
        }
        val q = query.map { (name, t) ->
            name to t?.let { tmpl -> fill(tmpl, values, { v, s -> Encoding.query(v, slot(s)) }) { Encoding.percent(it) } ?: return bad }
        }
        val h = mutableListOf<Pair<String, String>>()
        var contentType: String? = null
        for ((name, t) in headers) {
            val v = fill(t, values, ::wordsOf) { it } ?: return bad
            if (!ConnCheck.headerValue(v)) return bad
            if (name.equals("Content-Type", ignoreCase = true)) contentType = v else h += name to v // l10n: not text (a header name)
        }
        // C12's M1: a body only under a label that fits it (ConnCheck refuses the rest at save; this is the last check).
        val type = contentType
        if (body != null && type != null && !ConnCheck.contentTypeFits(body, type)) return bad
        val b = when (val body = body) {
            null -> null
            is OpBody.Json -> ReqBody(jsonParts(body.tree, values, ::slot) ?: return bad, contentType ?: JSON)
            is OpBody.Text -> {
                // A form's values percent-encoded, your words as you typed them; plain text takes values as they are.
                val slotText: (SkillValue, String) -> String? = if (ConnCheck.form(type)) { v, n -> wordsOf(v, n)?.let { Encoding.percent(it) } } else ::wordsOf
                ReqBody(listOf(BodyPart.Text(fill(body.template, values, slotText) { it } ?: return bad)), contentType ?: TEXT)
            }
        }
        if (b != null && b.size > ConnCheck.MAX_BODY + MAX_FILLED) return bad
        return Built.Ok(HttpRequest(method, base, c.secretAddress, base.path + p, q, h, b, c.auth, read, json, values))
    }

    /** A value as words inside text: anything that has words (never a yes or no, or a duration's seconds). */
    private fun wordsOf(v: SkillValue, @Suppress("UNUSED_PARAMETER") name: String): String? = if (Encoding.hasWords(v.type)) SkillTemplate.text(v.value) else null

    /** [t] with each template filled by [slotText] (null: refused) and its words by [words], in one pass. */
    private fun fill(t: String, values: Map<String, SkillValue>, slotText: (SkillValue, String) -> String?, words: (String) -> String): String? {
        val out = StringBuilder()
        for (part in OpTemplate.parts(t) ?: return null) when (part) {
            is OpTemplate.Part.Words -> out.append(words(part.text))
            is OpTemplate.Part.Slot -> out.append(slotText(values[part.name] ?: return null, part.name) ?: return null)
        }
        return out.toString()
    }

    /** A JSON template filled: a whole `{{p}}` typed, templates inside words as text, [TopicSlot] as the topic's place. */
    private fun jsonParts(tree: Any?, values: Map<String, SkillValue>, slot: (String) -> SlotType): List<BodyPart>? {
        val parts = mutableListOf<BodyPart>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { parts += BodyPart.Text(sb.toString()); sb.setLength(0) } }
        fun walk(v: Any?): Boolean {
            when (v) {
                TopicSlot -> { flush(); parts += BodyPart.Secret(SecretPart.TOPIC) }
                is String -> {
                    val whole = OpTemplate.whole(v)
                    if (whole != null) sb.append(Encoding.json(values[whole] ?: return false, slot(whole)) ?: return false)
                    else sb.append(Encoding.jsonString(fill(v, values, ::wordsOf) { it } ?: return false))
                }
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, x) in v) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append(Encoding.jsonString(k as? String ?: return false)).append(':')
                        if (!walk(x)) return false
                    }
                    sb.append('}')
                }
                is List<*> -> {
                    sb.append('[')
                    v.forEachIndexed { i, x -> if (i > 0) sb.append(','); if (!walk(x)) return false }
                    sb.append(']')
                }
                else -> try { ConnJson.write(sb, v) } catch (_: IllegalArgumentException) { return false }
            }
            return true
        }
        if (!walk(tree)) return null
        flush()
        return parts
    }

    companion object {
        const val JSON = "application/json" // l10n: not text (a media type)
        const val TEXT = "text/plain; charset=utf-8" // l10n: not text (a media type)
        /** Room a filled body may take beyond its template's cap: the values it holds (8 arguments of 4,096 characters at most, EST). */
        const val MAX_FILLED = 64 * 1024
    }
}
