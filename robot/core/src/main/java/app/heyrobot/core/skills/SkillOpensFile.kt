// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * auto-open-i: the skills' ticked apps in a backup ("skill_opens.json", BackupCategory.SKILL_OPENS), apart from skills.json.
 * A 1.3 Robot reads skills.json up to v3 and refuses any key it doesn't know, so a v4 file with one tick would make it skip
 * every skill; a backup keeps skills.json without "opens" (v3 or lower) and the ticks here, in a category 1.3 skips by name.
 * {"v":1,"skills":{"<skill id>":["<package>",…]}}.
 */
object SkillOpensFile {
    const val NAME = "skill_opens.json" // l10n: not text (a file name)
    const val VERSION = 1
    private const val V = "v" // l10n: not text (a key in the file)
    private const val SKILLS = "skills" // l10n: not text (a key in the file)
    private const val OPENS = "opens" // l10n: not text (a key in skills.json)
    /** skills.json's last version without "opens" (1.3's). */
    private const val NO_OPENS = 3

    /** Each ticked skill's apps by its id; a skill without a tick isn't in it. */
    fun of(skills: List<Skill>): Map<String, Set<String>> = skills.filter { it.opens.isNotEmpty() }.associate { it.id to it.opens }

    fun encode(opens: Map<String, Set<String>>): String = JSONObject().apply {
        put(V, VERSION)
        put(SKILLS, JSONObject().apply { for ((id, p) in opens.toSortedMap()) if (p.isNotEmpty()) put(id, JSONArray(p.sorted())) })
    }.toString()

    /** The file's "v", or null when it has none or isn't JSON. */
    fun version(text: String?): Int? = try { JSONObject(text!!).get(V) as? Int } catch (_: Exception) { null }

    /** The ticks, or null when the file breaks a rule (another key, an empty list, a bad package): refused whole. */
    fun decode(text: String?): Map<String, Set<String>>? = try {
        val o = JSONObject(text!!)
        require(o.keys().asSequence().toSet() == setOf(V, SKILLS) && o.get(V) == VERSION)
        val s = o.get(SKILLS) as JSONObject
        s.keys().asSequence().associateWith { id ->
            val a = s.get(id) as JSONArray
            (0 until a.length()).map { a.get(it) as String }.also { p -> require(p.isNotEmpty() && p.all(SkillCodec.PACKAGE::matches)) }.toSet()
        }
    } catch (_: Exception) { null }

    /**
     * What a backup holds for [skillsJson]: skills.json without any tick (so at most v3, read whole by 1.3) and the ticks'
     * file, or the file as it is and null when no skill is ticked, or it isn't read whole here (nothing of it is lost).
     */
    fun split(skillsJson: String): Pair<String, String?> {
        val ok = SkillCodec.decode(skillsJson) as? SkillFile.Ok ?: return skillsJson to null
        val opens = of(ok.skills)
        if (ok.dropped > 0) return stripped(skillsJson) to opens.takeIf { it.isNotEmpty() }?.let(::encode)
        if (opens.isEmpty()) return skillsJson to null
        return SkillCodec.encode(ok.skills.map { it.copy(opens = emptySet()) }) to encode(opens)
    }

    /**
     * review-auto-open-b L3: [json] with no skill's "opens" and its v 4 made 3, every entry otherwise as it is, one this Robot
     * can't read included (so a later Robot still reads it); that one's tick is dropped. The file as it is when no entry has one.
     */
    private fun stripped(json: String): String {
        val o = JSONObject(json)
        val a = o.getJSONArray(SKILLS)
        var had = false
        for (i in 0 until a.length()) if ((a.opt(i) as? JSONObject)?.remove(OPENS) != null) had = true
        if (!had) return json
        if (o.getInt(V) > NO_OPENS) o.put(V, NO_OPENS)
        return o.toString()
    }

    /** [skills] with each one's ticks from [opens] put back (an id no skill has is left out); null: as they are. */
    fun attach(skills: List<Skill>, opens: Map<String, Set<String>>?): List<Skill> =
        if (opens.isNullOrEmpty()) skills else skills.map { s -> opens[s.id]?.let { s.copy(opens = s.opens + it) } ?: s }
}
