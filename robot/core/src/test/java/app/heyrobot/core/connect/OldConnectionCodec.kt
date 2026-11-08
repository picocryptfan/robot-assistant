// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepCheck

// Pure. connections.json (plan §5.2, C2): `{"v":1,"connections":[…]}`, written by ConnJson and read by StrictJson (RFC 8259
// exactly: UTF-8, a key once, nothing after the value, 32 levels at most). The reading is strict: a file over
// [OldConnectionCodec.MAX_BYTES] (in bytes), cut short, without a whole-number "v", or with an unknown key at the top is
// damaged; one from a newer Robot (v above 1) is left alone. Inside, an unknown key, a wrong type or a value out of its
// limits (ConnCheck) drops that connection whole, and it is counted: a connection is never read in part. No key, token,
// topic or secret address is ever in the file (Connection), so its plain text holds none.

/**
 * ConnectionCodec's reader and writer as they stood at merge-all-12, before `address_marks`: what a Robot from before that
 * key does with a file this build writes. Frozen on purpose; ConnFile, ConnCheck and StrictJson are the live ones.
 */
object OldConnectionCodec {
    const val VERSION = 1
    /** connections.json is small: 20 connections of a few operations each (EST). Anything bigger wasn't written by Robot. */
    const val MAX_BYTES = 256 * 1024

    private val TOP = setOf("v", "connections")
    private val CONN = setOf("id", "kind", "name", "secret", "ops", "answers", "spoken_yes", "per_minute")
    private val CONN_MORE = setOf("address", "auth", "where", "routing", "package", "signer", "intent", "tested")
    private val OP = setOf("id", "title", "do", "params", "outputs", "marks")
    private val PARAM_MORE = setOf("options", "min", "max", "max_len", "default")
    private const val HEADER = "header" // l10n: not text (a key in connections.json)
    private val INTENT = setOf("action", "extras", "params")
    private val INTENT_MORE = setOf("data", "type")
    private val EXTRA = setOf("name", "type", "value")

    fun encode(cs: List<Connection>): String = ConnJson.write(linkedMapOf("v" to VERSION.toLong(), "connections" to cs.map(::toJson)))

    fun decode(text: String?): ConnFile = if (text == null) ConnFile.Damaged else decode(text.toByteArray(Charsets.UTF_8))

    fun decode(bytes: ByteArray): ConnFile {
        if (bytes.size > MAX_BYTES) return ConnFile.Damaged
        val root = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return ConnFile.Damaged
        val v = root["v"] as? Long ?: return ConnFile.Damaged
        if (v > VERSION) return ConnFile.TooNew(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (v < 1 || root.keys != TOP) return ConnFile.Damaged
        val list = root["connections"] as? List<*> ?: return ConnFile.Damaged
        val out = mutableListOf<Connection>()
        var dropped = 0
        for (o in list) {
            val c = (o as? Map<*, *>)?.let(::connection)?.takeIf { ConnCheck.valid(it) }
            if (c == null || out.size >= ConnCheck.MAX_CONNECTIONS || out.any { it.id == c.id }) {
                dropped++
                continue
            }
            out += c
        }
        return ConnFile.Ok(out, dropped)
    }

    // ------------------------------------------------------------------ writing

    fun toJson(c: Connection): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("id", c.id)
        put("kind", c.kind.id)
        put("name", c.name)
        c.address?.let { put("address", it.text) }
        put("secret", SecretPart.entries.filter { it in c.secrets }.map { it.id })
        when (val a = c.auth) {
            ConnAuth.None -> {}
            ConnAuth.Bearer -> put("auth", "bearer") // l10n: not text (a value in connections.json)
            ConnAuth.Basic -> put("auth", "basic") // l10n: not text (a value in connections.json)
            is ConnAuth.Header -> put("auth", linkedMapOf(HEADER to a.name))
        }
        c.where?.let { put("where", it.id) }
        c.routing?.let { put("routing", it.id) }
        // C10: a hand-off not pinned on this phone has no "signer" (from a backup that isn't yours: pick it again).
        c.handOff?.let { put("package", it.pkg); it.signer?.let { s -> put("signer", s) }; put("intent", intentJson(it.intent)) }
        put("ops", c.ops.map(::opJson))
        put("answers", LinkedHashMap(c.answers.mapValues { it.value.id }))
        put("spoken_yes", c.spokenYes)
        put("per_minute", c.perMinute.toLong())
        c.tested?.let { t -> put("tested", linkedMapOf<String, Any?>("at" to t.at).apply { t.version?.let { put("version", it) } }) }
    }

    private fun opJson(op: ConnOp): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("id", op.id)
        put("title", op.title)
        put("do", op.spec.id)
        put("params", LinkedHashMap(op.params.mapValues { (_, p) -> paramJson(p) }))
        put("outputs", LinkedHashMap(op.outputs.mapValues { (_, o) ->
            linkedMapOf<String, Any?>("pointer" to o.pointer, "type" to o.type.id).apply { if (o.type == ParamType.CHOICE) put("options", o.options) }
        }))
        put("marks", ConnOp.MARKS.filterKeys { it in op.marks }.values.toList())
        op.check?.let { put("check", linkedMapOf("pointer" to it.pointer, "equals" to it.equals)) }
        when (val s = op.spec) {
            is OpSpec.McpCall -> {}
            is OpSpec.HaCall -> {
                put("domain", s.domain)
                put("service", s.service)
                put("data", LinkedHashMap(s.data))
                put("returns", s.returns)
                put("classes", LinkedHashMap(s.classes))
            }
            OpSpec.HaRead, OpSpec.HaScript -> {}
            is OpSpec.NtfyPublish -> {
                put("message", s.message)
                // Its own key: "title" is the operation's (yours), this is the notification's.
                s.title?.let { put("heading", it) }
                s.priority?.let { put("priority", it) }
                put("tags", s.tags)
                put("only_mine", s.onlyMine)
            }
            is OpSpec.Webhook -> {
                put("method", s.method.id)
                put("path", s.path)
                put("query", s.query.map { listOf(it.first, it.second) })
                put("headers", s.headers.map { listOf(it.first, it.second) })
                when (val b = s.body) {
                    null -> {}
                    is OpBody.Json -> put("body", linkedMapOf("json" to b.tree))
                    is OpBody.Text -> put("body", linkedMapOf("text" to b.template))
                }
            }
        }
    }

    /** C7: what a hand-off asks its app (IntentSpec). */
    private fun intentJson(i: IntentSpec): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("action", i.action.id)
        i.data?.let { put("data", it) }
        i.mime?.let { put("type", it) }
        put("extras", i.extras.map { e -> linkedMapOf("name" to e.name, "type" to e.type.id, "value" to e.value) })
        put("params", LinkedHashMap(i.params.mapValues { (_, p) -> paramJson(p) }))
    }

    private fun paramJson(p: OpParam): Map<String, Any?> = linkedMapOf<String, Any?>("type" to p.type.id).apply {
        if (p.type == ParamType.CHOICE) put("options", p.options)
        p.min?.let { put("min", num(it)) }
        p.max?.let { put("max", num(it)) }
        p.maxLen?.let { put("max_len", it.toLong()) }
        p.default?.let { put("default", it) }
    }

    /** A limit as the file holds it: a whole number as one (so it reads back the same). */
    private fun num(d: Double): Any = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d

    // ------------------------------------------------------------------ reading (null: drop the connection)

    private fun connection(o: Map<*, *>): Connection? {
        if (!o.keys.containsAll(CONN) || !(CONN + CONN_MORE).containsAll(o.keys)) return null
        val id = o["id"] as? String ?: return null
        val kind = ConnKind.of(o["kind"] as? String) ?: return null
        val name = o["name"] as? String ?: return null
        val address = o["address"]?.let { a -> ConnUrl.parse(a as? String ?: return null) ?: return null }
        val secret = (o["secret"] as? List<*>)?.map { SecretPart.of(it as? String) ?: return null } ?: return null
        if (secret.distinct().size != secret.size) return null
        val auth = when (val a = o["auth"]) {
            null -> ConnAuth.None
            "bearer" -> ConnAuth.Bearer // l10n: not text (a value in connections.json)
            "basic" -> ConnAuth.Basic // l10n: not text (a value in connections.json)
            is Map<*, *> -> if (a.keys != setOf(HEADER)) return null else ConnAuth.Header(a[HEADER] as? String ?: return null)
            else -> return null
        }
        val where = o["where"]?.let { ConnWhere.of(it as? String) ?: return null }
        val routing = o["routing"]?.let { RoutingKind.of(it as? String) ?: return null }
        val pkg = o["package"]
        val signer = o["signer"]
        val intent = o["intent"]
        // A signer only with a package; a package always with what it asks (C10: a package without a signer is a hand-off
        // to pick again).
        if ((pkg == null && signer != null) || (pkg == null) != (intent == null)) return null
        val handOff = if (pkg != null) HandOffApp(pkg as? String ?: return null, signer?.let { it as? String ?: return null },
            intent(intent as? Map<*, *> ?: return null) ?: return null) else null
        val ops = (o["ops"] as? List<*>)?.map { (it as? Map<*, *>)?.let(::op) ?: return null } ?: return null
        val answers = (o["answers"] as? Map<*, *>)?.entries?.associate { (k, v) -> (k as String) to (ConsentAnswer.of(v as? String) ?: return null) } ?: return null
        val spokenYes = o["spoken_yes"] as? Boolean ?: return null
        val perMinute = (o["per_minute"] as? Long)?.takeIf { it in 1..ConnLimits.MAX_PER_MINUTE }?.toInt() ?: return null
        val tested = o["tested"]?.let { t -> tested(t as? Map<*, *> ?: return null) ?: return null }
        val c = Connection(id, kind, name, address, SecretPart.ADDRESS in secret, auth, where, routing, handOff, ops, answers, spokenYes, perMinute, tested)
        // The parts the file says are secret are exactly the ones the connection's fields make secret.
        return c.takeIf { it.secrets == secret.toSet() }
    }

    private fun intent(i: Map<*, *>): IntentSpec? {
        if (!i.keys.containsAll(INTENT) || !(INTENT + INTENT_MORE).containsAll(i.keys)) return null
        val action = HandOffAction.of(i["action"] as? String) ?: return null
        val data = i["data"]?.let { it as? String ?: return null }
        val mime = i["type"]?.let { it as? String ?: return null }
        val extras = (i["extras"] as? List<*>)?.map { x ->
            val e = x as? Map<*, *> ?: return null
            if (e.keys != EXTRA) return null
            HandOffExtra(e["name"] as? String ?: return null, ParamType.of(e["type"] as? String) ?: return null, e["value"] as? String ?: return null)
        } ?: return null
        val params = (i["params"] as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, OpParam>()) { (k, v) ->
            (k as String) to (param(v as? Map<*, *> ?: return null) ?: return null)
        } ?: return null
        return IntentSpec(action, data, mime, extras, params)
    }

    private fun tested(t: Map<*, *>): ConnTested? {
        if (!t.keys.contains("at") || !setOf("at", "version").containsAll(t.keys)) return null
        val at = t["at"] as? Long ?: return null
        val version = t["version"]?.let { it as? String ?: return null }
        return ConnTested(at, version)
    }

    private fun op(o: Map<*, *>): ConnOp? {
        val kind = o["do"] as? String ?: return null
        val more: Set<String> = when (kind) {
            "ha_call" -> setOf("domain", "service", "data", "returns", "classes") // l10n: not text (keys in connections.json)
            "ha_read", "ha_script" -> emptySet() // l10n: not text (operation kinds in connections.json)
            "ntfy_publish" -> setOf("message", "tags", "only_mine") // l10n: not text (keys in connections.json)
            "webhook" -> setOf("method", "path", "query", "headers") // l10n: not text (keys in connections.json)
            else -> return null
        }
        val optional: Set<String> = when (kind) {
            "ntfy_publish" -> setOf("heading", "priority", "check") // l10n: not text (keys in connections.json)
            "webhook" -> setOf("body", "check") // l10n: not text (keys in connections.json)
            else -> setOf("check")
        }
        if (!o.keys.containsAll(OP + more) || !(OP + more + optional).containsAll(o.keys)) return null
        val id = o["id"] as? String ?: return null
        val title = o["title"] as? String ?: return null
        val params = (o["params"] as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, OpParam>()) { (k, v) ->
            (k as String) to (param(v as? Map<*, *> ?: return null) ?: return null)
        } ?: return null
        val outputs = (o["outputs"] as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, OpOutput>()) { (k, v) ->
            (k as String) to (output(v as? Map<*, *> ?: return null) ?: return null)
        } ?: return null
        val marks = (o["marks"] as? List<*>)?.map { m -> ConnOp.MARKS.entries.firstOrNull { it.value == m }?.key ?: return null } ?: return null
        if (marks.distinct().size != marks.size) return null
        val check = o["check"]?.let { c -> check(c as? Map<*, *> ?: return null) ?: return null }
        val spec: OpSpec = when (kind) {
            "ha_call" -> OpSpec.HaCall(o["domain"] as? String ?: return null, o["service"] as? String ?: return null, // l10n: not text (an operation kind)
                strMap(o["data"]) ?: return null, o["returns"] as? Boolean ?: return null,
                strMap(o["classes"])?.mapValues { (_, v) -> v as? String ?: return null } ?: return null)
            "ha_read" -> OpSpec.HaRead // l10n: not text (an operation kind)
            "ha_script" -> OpSpec.HaScript // l10n: not text (an operation kind)
            "ntfy_publish" -> OpSpec.NtfyPublish(o["message"] as? String ?: return null, // l10n: not text (an operation kind)
                o["heading"]?.let { it as? String ?: return null }, o["priority"]?.also { if (it !is Long && it !is String) return null },
                (o["tags"] as? List<*>)?.map { it as? String ?: return null } ?: return null, o["only_mine"] as? Boolean ?: return null)
            else -> OpSpec.Webhook(HttpMethod.of(o["method"] as? String) ?: return null, o["path"] as? String ?: return null,
                pairs(o["query"]) ?: return null, pairs(o["headers"]) ?: return null,
                o["body"]?.let { b -> body(b as? Map<*, *> ?: return null) ?: return null })
        }
        return ConnOp(id, title, spec, params, outputs, check, marks.toSet())
    }

    private fun param(p: Map<*, *>): OpParam? {
        if (!p.keys.contains("type") || !(PARAM_MORE + "type").containsAll(p.keys)) return null
        val type = ParamType.of(p["type"] as? String) ?: return null
        val options = p["options"]?.let { o -> (o as? List<*>)?.map { it as? String ?: return null } ?: return null }
        if ((type == ParamType.CHOICE) != (options != null)) return null
        val min = p["min"]?.let { number(it) ?: return null }
        val max = p["max"]?.let { number(it) ?: return null }
        val maxLen = p["max_len"]?.let { (it as? Long)?.takeIf { n -> n in 1..ConnCheck.MAX_TEXT }?.toInt() ?: return null }
        val default = p["default"]?.let { it as? String ?: return null }
        return OpParam(type, options.orEmpty(), min, max, maxLen, default)
    }

    private fun output(o: Map<*, *>): OpOutput? {
        if (!o.keys.containsAll(setOf("pointer", "type")) || !setOf("pointer", "type", "options").containsAll(o.keys)) return null
        val type = ParamType.of(o["type"] as? String) ?: return null
        val options = o["options"]?.let { x -> (x as? List<*>)?.map { it as? String ?: return null } ?: return null }
        if ((type == ParamType.CHOICE) != (options != null)) return null
        return OpOutput(o["pointer"] as? String ?: return null, type, options.orEmpty())
    }

    private fun check(c: Map<*, *>): StepCheck.Equals? {
        if (c.keys != setOf("pointer", "equals")) return null
        val v = c["equals"]
        if (v !is String && v !is Long && v !is Double && v !is Boolean) return null
        return StepCheck.Equals(c["pointer"] as? String ?: return null, v)
    }

    private fun body(b: Map<*, *>): OpBody? = when (b.keys) {
        setOf("json") -> OpBody.Json(b["json"])
        setOf("text") -> OpBody.Text(b["text"] as? String ?: return null)
        else -> null
    }

    private fun pairs(v: Any?): List<Pair<String, String>>? = (v as? List<*>)?.map { p ->
        val l = p as? List<*> ?: return null
        if (l.size != 2) return null
        (l[0] as? String ?: return null) to (l[1] as? String ?: return null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun strMap(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let { m -> if (m.keys.all { it is String }) LinkedHashMap(m as Map<String, Any?>) else null }

    private fun number(v: Any): Double? = when (v) {
        is Long -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        else -> null
    }

    /** The effect a mark's id names in the file, for tests and Settings. */
    fun mark(id: String): SkillEffect? = ConnOp.MARKS.entries.firstOrNull { it.value == id }?.key
}
