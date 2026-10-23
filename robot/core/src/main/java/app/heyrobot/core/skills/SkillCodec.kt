// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). skills.json, v1 (plan §3, D2): `{"v":1,"skills":[…]}`, exactly the fields of the plan and nothing
// else. The reading is strict: an unknown key at the top makes the file damaged; an unknown key, a wrong type or a value
// out of its limits in a skill, a question, a step or its arguments drops that skill whole, and it is counted. A skill is
// never read in part. A file from a newer Robot (v above 1) is left alone. One opening for later Robots (1.3's languages):
// "names" and "ask" need "en" and "fr", and may hold more languages ("es", "de"), each in a language key's shape and
// within the same limits (SkillCheck); 1.1 doesn't run or ask them, and writes them back as it read them.

/** What a skills file held. */
sealed class SkillFile {
    /** [dropped]: skills left out because they couldn't be read or checked. */
    data class Ok(val skills: List<Skill>, val dropped: Int = 0) : SkillFile()
    data class TooNew(val version: Int) : SkillFile()
    data object Damaged : SkillFile()
}

object SkillCodec {
    const val VERSION = 1

    private val TOP = setOf("v", "skills")
    private val SKILL = setOf("id", "rev", "name", "names", "params", "steps", "level", "provenance")
    /** The languages "names" and "ask" must hold; others are kept ([Skill.namesMore], [SkillParam.askMore]). */
    private val LANGS = setOf("en", "fr")
    private val PARAM = setOf("name", "type", "default", "ask")
    private val STEP = setOf("use", "args")
    private val PROVENANCE = setOf("made", "from_content", "created", "edited")

    fun encode(skills: List<Skill>): String = JSONObject().put("v", VERSION).put("skills", JSONArray().apply { for (s in skills) put(toJson(s)) }).toString()

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
            val s = (a.opt(i) as? JSONObject)?.let(::fromJson)
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

    fun toJson(s: Skill): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("rev", s.rev)
        put("name", s.name)
        put("names", JSONObject().put("en", JSONArray(s.namesEn)).put("fr", JSONArray(s.namesFr)).apply {
            for ((k, v) in s.namesMore.toSortedMap()) put(k, JSONArray(v))
        })
        put("params", JSONArray().apply {
            for (p in s.params) put(JSONObject().apply {
                put("name", p.name)
                put("type", p.type.id)
                if (p.type == ParamType.DURATION) put("default", p.default.toLongOrNull() ?: p.default) else put("default", p.default)
                put("ask", JSONObject().put("en", p.askEn).put("fr", p.askFr).apply { for ((k, v) in p.askMore.toSortedMap()) put(k, v) })
            })
        })
        put("steps", JSONArray().apply {
            for (st in s.steps) put(JSONObject().put("use", st.kind.id).put("args", JSONObject().apply { for ((k, v) in st.args) put(k, v) }))
        })
        put("level", s.level.id)
        put("provenance", JSONObject().apply {
            put("made", s.provenance.made.id)
            put("from_content", s.provenance.fromContent)
            put("created", s.provenance.created)
            put("edited", s.provenance.edited)
        })
    }

    /** One skill, or null when anything in it can't be read or it fails [SkillCheck]. */
    fun fromJson(o: JSONObject): Skill? = try {
        require(keys(o) == SKILL)
        val names = langs(o, "names")
        val prov = obj(o, "provenance", PROVENANCE)
        val s = Skill(
            id = str(o, "id"),
            rev = int(o, "rev"),
            name = str(o, "name"),
            namesEn = strings(names, "en"),
            namesFr = strings(names, "fr"),
            params = arr(o, "params").map { param(it) },
            steps = arr(o, "steps").map { step(it) },
            level = SkillLevel.of(str(o, "level"))!!,
            provenance = SkillProvenance(SkillMade.of(str(prov, "made"))!!, bool(prov, "from_content"), long(prov, "created"), long(prov, "edited")),
            namesMore = (keys(names) - LANGS).associateWith { strings(names, it) },
        )
        s.takeIf { SkillCheck.valid(it) }
    } catch (_: Exception) { null }

    private fun param(x: Any?): SkillParam {
        val o = x as JSONObject
        require(keys(o) == PARAM)
        val type = ParamType.of(str(o, "type"))!!
        val d = o.get("default")
        val default = if (type == ParamType.DURATION) {
            require(d is Int || d is Long)
            (d as Number).toLong().toString()
        } else d as String
        val ask = langs(o, "ask")
        return SkillParam(str(o, "name"), type, default, str(ask, "en"), str(ask, "fr"), (keys(ask) - LANGS).associateWith { str(ask, it) })
    }

    private fun step(x: Any?): SkillStep {
        val o = x as JSONObject
        require(keys(o) == STEP)
        val kind = StepKind.of(str(o, "use"))!!
        val a = o.get("args") as JSONObject
        val args = keys(a).associateWith { k ->
            when (val v = a.get(k)) {
                is String, is Boolean, is Int, is Long -> v
                is Double -> v.also { require(!it.isNaN() && !it.isInfinite()) }
                else -> throw IllegalArgumentException()
            }
        }
        return SkillStep.of(kind, args)
    }

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()
    private fun obj(o: JSONObject, k: String, keys: Set<String>): JSONObject = (o.get(k) as JSONObject).also { require(keys(it) == keys) }
    /** "names" or "ask": English and French, and maybe more languages (SkillCheck checks their keys and values). */
    private fun langs(o: JSONObject, k: String): JSONObject = (o.get(k) as JSONObject).also { require(keys(it).containsAll(LANGS)) }
    private fun arr(o: JSONObject, k: String): List<Any?> = (o.get(k) as JSONArray).let { a -> (0 until a.length()).map { a.get(it) } }
    private fun strings(o: JSONObject, k: String): List<String> = arr(o, k).map { it as String }
    private fun str(o: JSONObject, k: String): String = o.get(k) as String
    private fun bool(o: JSONObject, k: String): Boolean = o.get(k) as Boolean
    private fun int(o: JSONObject, k: String): Int = (o.get(k) as? Int) ?: throw IllegalArgumentException()
    private fun long(o: JSONObject, k: String): Long = when (val v = o.get(k)) {
        is Int -> v.toLong()
        is Long -> v
        else -> throw IllegalArgumentException()
    }
}
