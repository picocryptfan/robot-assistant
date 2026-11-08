// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONArray
import org.json.JSONObject

// Test only. 1.2's SkillCodec as it shipped on release/1.2 (the same file at feature/today abf387402), frozen for S3.1
// (skills 1.3 plan §4 "Downgrade"): what a 1.2 Robot writes, and what it does with a skills file 1.3 wrote. It checks each
// skill with today's SkillCheck, which for 1.2's content applies 1.2's rules. Never change it to make a test pass.

object SkillCodec12 {
    /** The newest file this Robot reads; it writes [version]'s, the lowest that holds what it saves. */
    const val VERSION = 2

    private val TOP = setOf("v", "skills")
    private val SKILL = setOf("id", "rev", "name", "names", "params", "steps", "level", "provenance")
    /** The languages "names", "ask" and a choice's labels must hold; others are kept ([Skill.namesMore], [SkillParam.askMore], [SkillOption.more]). */
    private val LANGS = setOf("en", "fr")
    private val PARAM = setOf("name", "type", "default", "ask")
    private val STEP = setOf("use", "args")
    private val PROVENANCE = setOf("made", "from_content", "created", "edited")
    // v2's optional keys (D6).
    private val SKILL_2 = setOf("limits")
    private val PARAM_2 = setOf("options", "min", "max", "unit")
    private val STEP_2 = setOf("id", "if", "check", "on_fail")
    private val LIMITS = setOf("per_hour")
    private const val IS = "is" // l10n: not text (a key in the skills file)
    private const val LESS = "less_than" // l10n: not text (a key in the skills file)
    private const val MORE = "more_than" // l10n: not text (a key in the skills file)
    private const val SET = "set" // l10n: not text (a condition in the skills file)
    private const val UNSET = "unset" // l10n: not text (a condition in the skills file)

    /** The lowest file version that holds [skills] (D6): 1 when 1.1 reads every one of them whole. */
    fun version(skills: List<Skill>): Int = skills.maxOfOrNull(::needs) ?: 1

    /** The lowest file version that holds [s]: 2 when it uses anything 1.1 doesn't read. */
    fun needs(s: Skill): Int {
        if (s.perHour != null || s.params.any { it.type.since > 1 || it.options.isNotEmpty() || it.min != null || it.max != null || it.unit != null }) return 2
        for (st in s.steps) {
            if (st.kind.since > 1 || st.id != null || st.cond != null || st.check != null || st.onFail != OnFail.STOP || st.conn != null || st.op != null) return 2
            // 1.1 reads a template only as exactly `{{param}}` of the argument's own type.
            for ((k, v) in st.args) if (SkillTemplate.looksLike(v)) {
                val p = SkillTemplate.name(v)?.let(s::param)
                if (p == null || st.kind.args[k] != p.type) return 2
            }
        }
        return 1
    }

    fun encode(skills: List<Skill>): String =
        JSONObject().put("v", version(skills)).put("skills", JSONArray().apply { for (s in skills) put(toJson(s)) }).toString()

    fun decode(json: String?): SkillFile {
        if (json == null || json.toByteArray(Charsets.UTF_8).size > SkillCheck.MAX_BYTES) return SkillFile.Damaged
        val o = try { JSONObject(json) } catch (_: Exception) { return SkillFile.Damaged }
        val v = o.opt("v")
        if (v !is Int && v !is Long) return SkillFile.Damaged
        val version = (v as Number).toInt()
        if (version > VERSION) return SkillFile.TooNew(version)
        if (version < 1 || keys(o) != TOP) return SkillFile.Damaged
        val a = o.opt("skills") as? JSONArray ?: return SkillFile.Damaged
        val out = mutableListOf<Skill>()
        var dropped = 0
        val ids = HashSet<String>()
        val keys = HashSet<String>()
        for (i in 0 until a.length()) {
            val s = (a.opt(i) as? JSONObject)?.let { fromJson(it, version) }
            if (s == null || out.size >= SkillCheck.MAX_SKILLS || s.id in ids || s.keys.any { it in keys }) {
                dropped++
                continue
            }
            ids += s.id
            keys += s.keys
            out += s
        }
        return SkillFile.Ok(out, dropped)
    }

    /** A skills file's own version ([decode]'s "v"), or null when it has none that reads as a whole number. */
    fun versionOf(json: String?): Int? = try {
        (JSONObject(json ?: return null).opt("v") as? Number)?.takeIf { it is Int || it is Long }?.toInt()
    } catch (_: Exception) { null }

    fun toJson(s: Skill): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("rev", s.rev)
        put("name", s.name)
        put("names", JSONObject().put("en", JSONArray(s.namesEn)).put("fr", JSONArray(s.namesFr)).apply {
            for ((k, v) in s.namesMore.toSortedMap()) put(k, JSONArray(v))
        })
        put("params", JSONArray().apply { for (p in s.params) put(param(p)) })
        put("steps", JSONArray().apply { for (st in s.steps) put(step(st)) })
        put("level", s.level.id)
        put("provenance", JSONObject().apply {
            put("made", s.provenance.made.id)
            put("from_content", s.provenance.fromContent)
            put("created", s.provenance.created)
            put("edited", s.provenance.edited)
        })
        s.perHour?.let { put("limits", JSONObject().put("per_hour", it)) }
    }

    private fun param(p: SkillParam): JSONObject = JSONObject().apply {
        put("name", p.name)
        put("type", p.type.id)
        when (p.type) {
            ParamType.DURATION, ParamType.INTEGER -> put("default", p.default.toLongOrNull() ?: p.default)
            ParamType.NUMBER -> put("default", p.default.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(SkillNumbers::json) ?: p.default)
            ParamType.YES_NO -> put("default", p.default.toBooleanStrictOrNull() ?: p.default)
            else -> put("default", p.default)
        }
        put("ask", JSONObject().put("en", p.askEn).put("fr", p.askFr).apply { for ((k, v) in p.askMore.toSortedMap()) put(k, v) })
        if (p.options.isNotEmpty()) put("options", JSONArray().apply {
            for (op in p.options) put(JSONObject().put("value", op.value).put("en", op.en).put("fr", op.fr).apply { for ((k, v) in op.more.toSortedMap()) put(k, v) })
        })
        p.min?.let { put("min", SkillNumbers.json(it)) }
        p.max?.let { put("max", SkillNumbers.json(it)) }
        p.unit?.let { put("unit", it) }
    }

    private fun step(st: SkillStep): JSONObject = JSONObject().apply {
        put("use", st.use)
        put("args", JSONObject().apply { for ((k, v) in st.args) put(k, v) })
        st.id?.let { put("id", it) }
        st.cond?.let { c ->
            put("if", JSONObject().put("value", c.value.toString()).apply {
                when (val t = c.test) {
                    IfTest.Set -> put(IS, SET)
                    IfTest.Unset -> put(IS, UNSET)
                    is IfTest.Is -> put(IS, t.choice)
                    is IfTest.Yes -> put(IS, t.yes)
                    is IfTest.Below -> put(LESS, SkillNumbers.json(t.n))
                    is IfTest.Above -> put(MORE, SkillNumbers.json(t.n))
                }
            })
        }
        st.check?.let { c ->
            put("check", when (c) {
                is StepCheck.Equals -> JSONObject().put("pointer", c.pointer).put("equals", c.equals)
                is StepCheck.Matches -> JSONObject().put("matches", c.regex)
            })
        }
        if (st.onFail != OnFail.STOP) put("on_fail", st.onFail.id)
    }

    /** One skill of a [version] file, or null when anything in it can't be read, it fails [SkillCheck], or a v1 file holds what needs v2. */
    fun fromJson(o: JSONObject, version: Int = VERSION): Skill? = try {
        val v2 = version >= 2
        within(o, SKILL, if (v2) SKILL_2 else emptySet())
        val names = langs(o, "names")
        val prov = obj(o, "provenance", PROVENANCE)
        val s = Skill(
            id = str(o, "id"),
            rev = int(o, "rev"),
            name = str(o, "name"),
            namesEn = strings(names, "en"),
            namesFr = strings(names, "fr"),
            params = arr(o, "params").map { param(it, v2) },
            steps = arr(o, "steps").map { step(it, v2) },
            level = SkillLevel.of(str(o, "level"))!!,
            provenance = SkillProvenance(SkillMade.of(str(prov, "made"))!!, bool(prov, "from_content"), long(prov, "created"), long(prov, "edited")),
            namesMore = (keys(names) - LANGS).associateWith { strings(names, it) },
            perHour = if (o.has("limits")) int(obj(o, "limits", LIMITS), "per_hour") else null,
        )
        s.takeIf { needs(it) <= version && SkillCheck.valid(it) }
    } catch (_: Exception) { null }

    private fun param(x: Any?, v2: Boolean): SkillParam {
        val o = x as JSONObject
        within(o, PARAM, if (v2) PARAM_2 else emptySet())
        val type = ParamType.of(str(o, "type"))!!
        val d = o.get("default")
        val default = when (type) {
            ParamType.DURATION, ParamType.INTEGER -> {
                require(d is Int || d is Long)
                (d as Number).toLong().toString()
            }
            ParamType.NUMBER -> SkillNumbers.text(SkillNumbers.read(d)!!)
            ParamType.YES_NO -> (d as Boolean).toString()
            else -> d as String
        }
        val ask = langs(o, "ask")
        val options = if (o.has("options")) arr(o, "options").map { option(it) } else emptyList()
        return SkillParam(str(o, "name"), type, default, str(ask, "en"), str(ask, "fr"), (keys(ask) - LANGS).associateWith { str(ask, it) },
            options = options, min = number(o, "min"), max = number(o, "max"), unit = if (o.has("unit")) str(o, "unit") else null)
    }

    private fun option(x: Any?): SkillOption {
        val o = x as JSONObject
        require(keys(o).containsAll(LANGS + "value"))
        return SkillOption(str(o, "value"), str(o, "en"), str(o, "fr"), (keys(o) - LANGS - "value").associateWith { str(o, it) })
    }

    private fun step(x: Any?, v2: Boolean): SkillStep {
        val o = x as JSONObject
        within(o, STEP, if (v2) STEP_2 else emptySet())
        val (kind, conn, op) = SkillStep.use(str(o, "use"))!!
        val a = o.get("args") as JSONObject
        val args = keys(a).associateWith { k ->
            when (val v = a.get(k)) {
                is String, is Boolean, is Int, is Long -> v
                is Double -> v.also { require(!it.isNaN() && !it.isInfinite()) }
                else -> throw IllegalArgumentException()
            }
        }
        val cond = if (o.has("if")) cond(o.get("if") as JSONObject) else null
        val check = if (o.has("check")) check(o.get("check") as JSONObject) else null
        val onFail = if (o.has("on_fail")) OnFail.of(str(o, "on_fail"))!! else OnFail.STOP
        return SkillStep.of(kind, args, id = if (o.has("id")) str(o, "id") else null, conn = conn, op = op, cond = cond, check = check, onFail = onFail)
    }

    private fun cond(o: JSONObject): SkillIf {
        val test = keys(o) - "value"
        require(o.has("value") && test.size == 1)
        val value = SkillRef.of(str(o, "value"))!!
        return SkillIf(value, when (test.single()) {
            IS -> when (val t = o.get(IS)) {
                is Boolean -> IfTest.Yes(t)
                SET -> IfTest.Set
                UNSET -> IfTest.Unset
                is String -> IfTest.Is(t)
                else -> throw IllegalArgumentException()
            }
            LESS -> IfTest.Below(SkillNumbers.read(o.get(LESS))!!)
            MORE -> IfTest.Above(SkillNumbers.read(o.get(MORE))!!)
            else -> throw IllegalArgumentException()
        })
    }

    private fun check(o: JSONObject): StepCheck = when (keys(o)) {
        setOf("pointer", "equals") -> StepCheck.Equals(str(o, "pointer"), when (val e = o.get("equals")) {
            is String, is Boolean -> e
            is Int -> e.toLong()
            is Long -> e
            is Double -> SkillNumbers.read(e)!!.let(SkillNumbers::json)
            else -> throw IllegalArgumentException()
        })
        setOf("matches") -> StepCheck.Matches(str(o, "matches"))
        else -> throw IllegalArgumentException()
    }

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()
    /** [o]'s keys are all of [required], and of [optional] only those it has. */
    private fun within(o: JSONObject, required: Set<String>, optional: Set<String>) =
        keys(o).let { require(it.containsAll(required) && (it - required).all { k -> k in optional }) }
    private fun obj(o: JSONObject, k: String, keys: Set<String>): JSONObject = (o.get(k) as JSONObject).also { require(keys(it) == keys) }
    /** "names" or "ask": English and French, and maybe more languages (SkillCheck checks their keys and values). */
    private fun langs(o: JSONObject, k: String): JSONObject = (o.get(k) as JSONObject).also { require(keys(it).containsAll(LANGS)) }
    private fun arr(o: JSONObject, k: String): List<Any?> = (o.get(k) as JSONArray).let { a -> (0 until a.length()).map { a.get(it) } }
    private fun strings(o: JSONObject, k: String): List<String> = arr(o, k).map { it as String }
    private fun str(o: JSONObject, k: String): String = o.get(k) as String
    private fun bool(o: JSONObject, k: String): Boolean = o.get(k) as Boolean
    private fun int(o: JSONObject, k: String): Int = (o.get(k) as? Int) ?: throw IllegalArgumentException()
    private fun number(o: JSONObject, k: String): Double? = if (o.has(k)) SkillNumbers.read(o.get(k))!! else null
    private fun long(o: JSONObject, k: String): Long = when (val v = o.get(k)) {
        is Int -> v.toLong()
        is Long -> v
        else -> throw IllegalArgumentException()
    }
}
